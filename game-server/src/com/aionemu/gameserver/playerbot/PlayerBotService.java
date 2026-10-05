package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.lifecycle.BotOutfitter;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshBuilder;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotDirector;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.BotPresence;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLeaveWorldService;
import com.aionemu.gameserver.playerbot.economy.BotStigmaFitter;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.world.World;

/**
 * Entry point of the playerbot system: bots are real {@link Player} objects driven by server side AI instead of a client connection.
 */
public class PlayerBotService {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotService.class);

	/**
	 * How often spawned bots are written to the database. Bots now gain levels, skills and loot unattended over hours, and nothing else writes them:
	 * the engine's own {@code PeriodicSaveService} handles legion warehouses only, and real players are saved when they log out — a door a bot never
	 * uses. A clean shutdown still saves them; this is what stands between a crash and a day of progress.
	 */
	private static final long SAVE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
	/** How often a slice of the population is written. The interval divided by this is the number of slices, so each bot is still saved every 5 min. */
	private static final long SAVE_SWEEP_MILLIS = TimeUnit.SECONDS.toMillis(10);
	/** How far around its home a new resident may appear, so a village is not a stack of people on one spot. */
	private static final float SETTLING_SPREAD = 15f;
	/** How many spots to try before giving up and standing on the place itself. A handful: most spots around a place are fine. */
	private static final int SETTLING_ATTEMPTS = 8;
	/** The setting that means "work it out": every open world map of both races, each taking as many inhabitants as its own civilians call for. */
	private static final String AUTOMATIC = "auto";

	private final Map<Integer, Player> spawnedBots = new ConcurrentHashMap<>();

	public static PlayerBotService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Puts the world back the way it was and starts saving it. Called once, from {@code GameServer}, after the world is loaded.
	 * <p>
	 * Switched off, nothing here runs: no save sweep, no standing orders, no roster restored and no director. The characters stay in the database
	 * untouched, which is the whole promise of the setting — turning bots off must not be a way to lose them.
	 */
	public void onStartUp() {
		if (!PlayerBotConfig.ENABLE) {
			log.info("Playerbots are disabled, so none is put back into the world. Their characters are left in the database untouched.");
			return;
		}
		BotScheduler.getInstance().scheduleAtFixedRate(this::saveDue, SAVE_SWEEP_MILLIS, SAVE_SWEEP_MILLIS);
		runStandingOrders();
		restoreRoster();
		warmUpPlaces();
		// Last, and it used to be first. The director reviews what each region holds against what it should hold, and both of the steps above change
		// that answer wholesale: populating a fresh installation builds a navmesh and creates thousands of characters across eighteen maps, which
		// takes minutes. Started first, the director's opening review fired into a world a tenth of the way up and began correcting a figure that was
		// still moving — on the same threads doing the populating.
		BotDirector.getInstance().start();
	}

	/**
	 * Reads every open map's places before anything needs them.
	 * <p>
	 * Finding them is cheap and proving each one can be walked to is not: the ground under every place is walked outwards until it runs out or proves
	 * big enough, some hundred and fifty times on a region. Left to the first caller, that bill lands wherever the first bot happens to ask — a tick
	 * thread, with forty other bots waiting behind it. Queued here it lands on the lifecycle lane, which exists to block, and ahead of the director's
	 * first review because that review runs on the same lane and the lane keeps its order.
	 */
	private void warmUpPlaces() {
		for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
			if (raceOfMap(map.getMapId()) == null)
				continue; // an instance, or contested ground, which is populated by command
			int worldId = map.getMapId();
			BotScheduler.getInstance().enterOrLeaveWorld(() -> BotPlaces.warmUp(worldId));
		}
	}

	/**
	 * Puts back the bots that were in the world when it last went down, so a restart is not a depopulation.
	 */
	private void restoreRoster() {
		Set<String> roster = BotRoster.restore();
		if (roster.isEmpty())
			return;
		int restored = 0;
		int left = 0;
		for (String characterName : roster) {
			// Already in the world, because populating a map on this very startup both creates a bot and puts it there. Asking to load it again
			// returns null, exactly as a deleted character does, and reading that null as "gone" printed forty five warnings about losing characters
			// that were standing right there. A null is not a reason.
			if (findSpawnedBot(characterName) != null) {
				restored++;
				continue;
			}
			// A character of somebody's own comes back when they ask for it and not before. The roster exists so the world is the world again after a
			// restart, and a player's character is not part of the world in that sense — putting it back would be the server playing it, which is the
			// one thing it must not do with somebody else's character. It leaves the roster here, so nothing goes looking for it again.
			if (BotRoster.isSomebodysOwn(characterName)) {
				left++;
				continue;
			}
			Player bot = loadAvailableBot(characterName);
			if (bot == null) {
				log.warn("Bot {} is on the roster but no longer exists, dropping it", characterName);
				continue;
			}
			try {
				PlayerBotEnterWorldService.enterWorld(bot);
				spawnedBots.put(bot.getObjectId(), bot);
				restored++;
			} catch (RuntimeException e) {
				log.error("Could not restore bot " + characterName, e);
			}
		}
		rememberRoster(); // drops whatever could not be restored, so a broken name is not retried every restart
		log.info("Restored {} of {} bot(s) from the roster, leaving {} belonging to players", restored, roster.size(), left);
	}

	/**
	 * Carries out a clearing or a populating left for the next start, then forgets it.
	 * <p>
	 * Both are the work of one command typed in game, and that is still how a person does it. This exists because populating is a job done <b>for</b>
	 * a server rather than <b>in</b> it: it wants a map with nobody on it, it takes a while, and whoever orders it may well not have a character
	 * standing there — the person maintaining this one has no client at all. An order left in {@code server_variables} is carried out on the next
	 * start and cleared, so it can never run twice.
	 */
	private void runStandingOrders() {
		if (BotRoster.takeOrder(BotRoster.CLEAR_ORDER) != null)
			log.info("Standing order: {}", clear(null, null));
		populateConfiguredMaps();
	}

	/**
	 * Populates every open world map of both races, building the navmeshes it finds missing.
	 * <p>
	 * This is what {@code auto} means, and the point of it is that a server should not need a decision per map. There are eighteen of them across
	 * the two factions, and asking somebody to name each one, pick a count and remember a race for it is how a feature ends up used on exactly the
	 * machine it was written on. Everything here is read from the world instead: which maps are open world, which race lives on each, and how many
	 * people a map holds — one per place it turns out to have, so a starting valley gets a village's worth and a large region gets a region's.
	 * <p>
	 * A map with no mesh has one built here, before the server opens and before anybody is put on it — see {@link NavmeshBuilder}. It costs seconds
	 * and about a gigabyte per map, which is why a first {@code auto} start on a fresh installation is a long one.
	 */
	private void populateEveryOpenMap() {
		int populated = 0;
		for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
			// null for an instance and for ground that belongs to neither faction, which is the same skip. Contested ground is deliberately left
			// out of the automatic pass: Reshanta is the largest map in the game and has no mesh, so building one here would be minutes added to
			// every first start. It is populated by command, which is where somebody can wait for it.
			if (raceOfMap(map.getMapId()) == null)
				continue;
			if (PlayerBotCreationService.hasBotsOn(map.getMapId()))
				continue;
			// no count: the region says how many it wants, from its own civilians rather than from its monsters. No race either: each place names
			// its own, and on a faction's own ground every one of them names this map's.
			log.info("Populating map {}: {}", map.getMapId(), populate(0, map.getMapId(), null));
			populated++;
		}
		log.info("Populated {} map(s) automatically", populated);
	}

	/**
	 * Populates any configured map that has nobody on it yet.
	 * <p>
	 * This is what makes the bot system work on a server other than the one it was written on. Everything else about a population lives in the
	 * database — the characters, the roster, where each one lives — so a fresh installation has none of it, and the only ways to get some were to
	 * type a command in game or to write a row into {@code server_variables} by hand. Neither is an installation step anybody should have to be told
	 * about.
	 * <p>
	 * Only ever for an empty map, so it runs once and is silent on every start afterwards, and so it can never quietly double a population. Which
	 * also means a map that already has inhabitants is skipped <b>before</b> its mesh is looked at: an installation that was populated under an
	 * earlier build keeps whatever meshes it had.
	 */
	private void populateConfiguredMaps() {
		if (PlayerBotConfig.POPULATE.isBlank())
			return;
		if (PlayerBotConfig.POPULATE.trim().equalsIgnoreCase(AUTOMATIC)) {
			populateEveryOpenMap();
			return;
		}
		for (String order : PlayerBotConfig.POPULATE.split(";")) {
			String[] parts = order.trim().split(":");
			if (parts.length != 3) {
				log.warn("Cannot read the populate setting '{}', expected <mapId>:<count>:<race>", order);
				continue;
			}
			try {
				int worldId = Integer.parseInt(parts[0]), count = Integer.parseInt(parts[1]);
				if (PlayerBotCreationService.hasBotsOn(worldId)) {
					log.info("Map {} already has bots living on it, leaving it alone", worldId);
					continue;
				}
				log.info("Populating map {}: {}", worldId, populate(count, worldId, parts[2]));
			} catch (NumberFormatException e) {
				log.warn("Cannot read the populate setting '{}', expected <mapId>:<count>:<race>", order);
			}
		}
	}



	/** Writes every spawned bot to the database, in place, without taking it out of the world. */
	/**
	 * Saves every bot that is due, a slice at a time.
	 * <p>
	 * Each bot is still written every {@link #SAVE_INTERVAL_MILLIS}, but not all of them in the same instant. Saving the lot at once is a burst of as
	 * many statements as there are bots — fifteen hundred of them on one connection, every five minutes, while the same database is answering real
	 * players. Spreading them over the interval costs nothing and turns the burst into a trickle.
	 * <p>
	 * Which bot is due is decided by its own object id rather than by a queue: the ids are already spread, so the arithmetic does the scattering for
	 * free and nothing has to be kept in step with bots arriving and leaving.
	 */
	private void saveDue() {
		long now = System.currentTimeMillis();
		long slices = SAVE_INTERVAL_MILLIS / SAVE_SWEEP_MILLIS;
		long currentSlice = now / SAVE_SWEEP_MILLIS % slices;
		for (Player bot : spawnedBots.values()) {
			if (Math.floorMod(bot.getObjectId(), slices) != currentSlice)
				continue;
			save(bot);
		}
	}

	private void save(Player bot) {
		try {
			PlayerService.storePlayer(bot);
		} catch (RuntimeException e) {
			log.error("Could not save bot " + bot.getName(), e);
		}
	}

	/** The roster is written on every change rather than at shutdown, because a shutdown that never runs is the case it exists for. */
	private void rememberRoster() {
		BotRoster.remember(spawnedBots.keySet());
	}

	/**
	 * Loads a bot character from the database without spawning it, to verify that a connectionless player can be built at all.
	 *
	 * @return A human readable summary of the loaded character, or an error message.
	 */
	public String describeLoadedBot(String characterName) {
		Player bot = loadAvailableBot(characterName);
		if (bot == null)
			return "No offline character found with name " + characterName;

		return String.format("%s (objId %d): %s level %d, %d equipped items, %d skills, %d/%d HP, attack speed %d", bot.getName(), bot.getObjectId(),
			bot.getPlayerClass(), bot.getLevel(), bot.getEquipment().getEquippedItems().size(), bot.getSkillList().size(),
			bot.getLifeStats().getCurrentHp(), bot.getLifeStats().getMaxHp(), bot.getGameStats().getAttackSpeed().getCurrent());
	}

	/**
	 * Loads a bot character and spawns it next to the given player.
	 *
	 * @return A human readable result message.
	 */
	public String spawn(String characterName, Player nextTo) {
		Player bot = loadAvailableBot(characterName);
		if (bot == null)
			return "No offline character found with name " + characterName;

		try {
			PlayerBotEnterWorldService.enterWorld(bot, nextTo);
		} catch (RuntimeException e) {
			log.error("Could not spawn bot " + characterName, e);
			return "Could not spawn " + characterName + " (see server log)";
		}
		spawnedBots.put(bot.getObjectId(), bot);
		rememberRoster();
		return "Spawned " + bot.getName() + " (objId " + bot.getObjectId() + ")";
	}

	/**
	 * Creates a new bot character in the database, cloned from an existing one which supplies the account, race, gender and looks.
	 *
	 * @return A human readable result message.
	 */
	public String create(String characterName, String className, int level, String raceName, Player maker) {
		PlayerClass playerClass;
		try {
			playerClass = PlayerClass.valueOf(className.toUpperCase());
		} catch (IllegalArgumentException e) {
			return "Unknown class " + className;
		}
		Race race = raceOf(raceName);
		if (race == null)
			return "Unknown race " + raceName + ", expected ELYOS or ASMODIANS";

		try {
			Player bot = PlayerBotCreationService.create(characterName, playerClass, level, race);
			// A bot somebody made by hand is theirs; a bot the world made by populating a region belongs to the world. That is the whole of the
			// distinction, and it decides both who may delete it and whether the population director is allowed to move it about.
			if (maker != null)
				BotRoster.setOwner(bot.getName(), maker.getObjectId());
			return "Created " + bot.getName() + " (objId " + bot.getObjectId() + "): " + playerClass + " level " + level;
		} catch (IllegalArgumentException | IllegalStateException e) {
			log.warn("Could not create bot " + characterName, e);
			return "Could not create " + characterName + ": " + e.getMessage();
		}
	}

	/**
	 * Fills a region with people who belong to it.
	 * <p>
	 * The old version cloned one template character count times, so a map ended up with one class, one level and one set of training gear repeated.
	 * Nothing about it was a population. What a region needs is taken from the region itself: the level band comes from the creatures living there,
	 * the classes are spread over those a character of that level could be, and the gear is fitted to each one. They are residents from birth, so
	 * they stay the level their home is worth.
	 *
	 * @param count How many to make, or 0 for as many as the region still asks for.
	 * @param raceName The faction to make them all, or null to let each place name its own — which is the usual answer, and the only one that gets
	 *          contested ground right. See {@link #factionFor}.
	 */
	public String populate(int count, int worldId, String raceName) {
		Race chosen = null;
		if (raceName != null && !raceName.isBlank()) {
			chosen = raceOf(raceName);
			if (chosen == null)
				return "Unknown race " + raceName + ", expected ELYOS or ASMODIANS";
		}
		// One entry per inhabitant the region asks for, each naming the place it belongs to. A busy village appears many times over and a roadside
		// camp once, so going round the list in order puts the population where the world put its own people — which is what the count times a
		// density never did, because that count was fed by how many monsters a map holds.
		//
		// The busy plan, which is the most a region can ever ask for, and not the quiet one it holds when nobody is there. The pool has to contain
		// what the surge will want or the surge cannot happen, and that is not a subtlety — measured on Poeta, which asks for 19 inhabitants quiet
		// and 37 with somebody playing: created at 19, a player's arrival raised the target to 37 and the director had nobody left to wake, so a map
		// with a player on it was no busier than an empty one. Creating at the fuller plan also spreads the homes over far more of the countryside,
		// which is what lets the director find a sleeper for a ground that needs one.
		// Before anybody is put on it, and on every path into here rather than on the two that remembered. A map without a navmesh is a map whose
		// inhabitants can plan no route at all: they are created, they are stored, and then they stand wherever they were dropped for ever, because
		// every journey any of them asks for is refused. It was called from the two startup paths and not from the command, so a map populated by
		// hand — a capital, typically, since those are the ones the config does not cover — got a population that could not move.
		//
		// Cheap when the mesh is already there, which is every call but the first for a given map.
		NavmeshBuilder.ensureMesh(worldId);
		List<BotPlaces.Settlement> plan = BotPresence.wanted(worldId, true);
		if (plan.isEmpty())
			return "Nothing lives on this map, so there is nowhere to put anyone";
		List<BotPlaces.Settlement> places = placesStillNeeding(worldId, plan);
		if (places.isEmpty())
			return "This map already has an inhabitant for every place it asks for";
		if (count <= 0)
			count = places.size(); // as the region still asks for, which is what an unattended start wants
		log.info("Map {} takes {} inhabitant(s) across {}", worldId, count, levelSpread(places));

		List<String> created = new ArrayList<>();
		int worn = 0;
		for (int i = 0; i < count; i++) {
			String name;
			try {
				name = PlayerBotCreationService.generateName();
			} catch (IllegalStateException e) {
				return report(created, "ran out of free names");
			}
			// one entry after another rather than one drawn at random: the list is already weighted by how populous each place is, so going round it
			// reproduces that weighting exactly, while drawing would leave some places empty and pile others up.
			BotPlaces.Settlement home = places.get(i % places.size());
			// the level of the place it lives at, not of the map: a map is one number, and living by it is what put a character of two in a forest
			// of eights. A little spread so a camp is not a rank of identical characters.
			//
			// Bounded below what the region tops out at, because the spread reaches past it: Poeta's highest place is worth 9, so the +1 made a
			// character of 10 in a valley of ones to nines — and classFor hands a character of ten a specialised class, Spirit Master for one, which
			// is a thing you become by ascending and it never had.
			//
			// Below rather than at: a resident born on the ceiling has nowhere to go, earns nothing from its first minute, and is a career already
			// over. Every new inhabitant gets at least one level of its own to climb.
			// The place's own level where the place belongs to the region, and a level drawn from the whole band where it does not.
			//
			// Clamping was the obvious thing and it piles people on the edges: Verteron holds 23 places worth 9 or less and 27 worth 17 or more, so
			// 53 of its 84 inhabitants came out at exactly 10 or exactly 19 with a hollow in between. A place outside the band says nothing about
			// what level lives there — it is a stretch of beach or a boss's lair, not a home — so it gets a level from the region at large instead of
			// being pinned to the nearest bound.
			int floor = BotPlaces.bottomLevelOf(worldId), ceiling = BotPlaces.topLevelOf(worldId);
			int level = home.level() < floor || home.level() > ceiling ? Rnd.get(floor, ceiling)
				: Math.clamp(home.level() + Rnd.get(-1, 1), floor, ceiling);
			PlayerClass playerClass = classFor(level);
			try {
				Race race = chosen != null ? chosen : factionFor(worldId, home);
				if (race == null)
					return report(created, "nothing on this map says which faction lives here, so name one: //bot populate " + count + " ELYOS");
				Player bot = PlayerBotCreationService.create(name, playerClass, level, race);
				BotRoster.setResident(name, true);
				BotRoster.setHome(name, home.centre());
				place(bot, worldId, home.centre());
				// Taught before it is dressed, and this is the order that matters: the outfitter refuses a piece of armour to a character that does not
				// already hold its mastery, and a character read back from the database knows the skills of level one whatever its level. Taught on the
				// way into the world, which is where this used to happen, a character created and left asleep would be dressed in nothing at all.
				PlayerBotEnterWorldService.learnMissingSkills(bot);
				worn += BotOutfitter.dress(bot);
				// After the skills, like the gear and for a nearer reason: which stigmas a character may socket is read from its skill tree, and the
				// engine charges kinah for each one, so a bot with neither knows nothing and can afford nothing.
				BotStigmaFitter.fit(bot);
				PlayerService.storePlayer(bot);
				created.add(name + " (" + playerClass + " " + level + ")");
			} catch (IllegalArgumentException | IllegalStateException e) {
				log.warn("Could not create bot " + name, e);
				return report(created, e.getMessage());
			}
		}
		log.info("Created {} inhabitant(s) for map {}, wearing {} piece(s) between them, all asleep", created.size(), worldId, worn);
		// the map is now full of sleepers and empty of people, and whoever typed this is standing in it waiting
		BotDirector.getInstance().reviewNow();
		return report(created, null) + ", over " + places.size() + " places";
	}

	/**
	 * @return The posts of the plan that no inhabitant lives at yet, in the plan's own order.
	 *         <p>
	 *         Because going round the plan from the top is right exactly once. The plan lists the settlements before the countryside, so a second
	 *         populate — a map being topped up, which is what a map gets after its densities change — fills the villages all over again. Measured on
	 *         Poeta after one top-up of 18: thirty seven inhabitants over sixteen homes, twelve of them in Akarios alone, with twenty one villagers
	 *         for eight village posts and a countryside asking for twenty nine hunters out of the sixteen characters left to supply them. The surplus
	 *         villagers are then asleep for ever, which is a character created for nothing, and the countryside stays short however many are made.
	 */
	private static List<BotPlaces.Settlement> placesStillNeeding(int worldId, List<BotPlaces.Settlement> plan) {
		Map<Vector3f, Integer> taken = new HashMap<>();
		for (BotRoster.Resident resident : BotRoster.pool()) {
			if (resident.worldId() == worldId)
				taken.merge(resident.home(), 1, Integer::sum);
		}
		List<BotPlaces.Settlement> needed = new ArrayList<>();
		for (BotPlaces.Settlement post : plan) {
			Integer here = taken.get(post.centre());
			if (here != null && here > 0)
				taken.put(post.centre(), here - 1); // somebody already lives at this one
			else
				needed.add(post);
		}
		return needed;
	}

	/**
	 * Puts a newly made resident down at the place it belongs, scattered a little so a village is not a stack of people on one spot — and leaves it
	 * there, asleep.
	 * <p>
	 * It used to enter the world here, on the grounds that creating without spawning would mean invoking forty five characters by hand afterwards.
	 * Nobody has to: the population director brings in as many of them as the region currently wants, and leaves the rest in the pool. Which is the
	 * whole point of there being a pool — a world of two thousand inhabitants is affordable because most of them are asleep most of the time, and
	 * creating them all awake was the one thing that made that impossible to try.
	 * <p>
	 * The position is set without spawning, which the engine has its own door for, and it has to be set before the character is stored: the save reads
	 * the coordinates off the object, so a character stored without one is a character with nowhere to be woken to.
	 */
	private void place(Player bot, int worldId, Vector3f home) {
		if (home == null)
			return;
		Vector3f spot = scatterAround(worldId, home);
		World.getInstance().setPosition(bot, worldId, spot.getX(), spot.getY(), spot.getZ(), (byte) 0);
	}

	/**
	 * Finds a spot a few paces from a place, so a village is a village rather than a stack of people on one point.
	 * <p>
	 * Two conditions, and each was learned by leaving it out. The spot must be ground a body fits on, or the villager is dropped inside a hut, a
	 * trunk or a mushroom cap. And it must be ground connected to the place itself: a ledge, a hollow or the far side of a wall is perfectly good
	 * ground that leads nowhere, and a resident put on one spends its life asking for a route out.
	 * <p>
	 * A third was learned from a screenshot: the top of a plinth is walkable ground a metre above the square, so a spot near the centre of a village
	 * climbs onto whatever stands there. In Akarios and at Melponeh's camp that is the obelisk, and residents were seen standing on the statue.
	 * <p>
	 * Failing all three it keeps looking further out rather than standing on the centre. That centre is one point shared by every inhabitant of the
	 * place, so falling back to it does not place a villager anywhere — it stacks the whole village on a single spot.
	 */
	private static Vector3f scatterAround(int worldId, Vector3f home) {
		Vector3f spot = BotPlaces.spotAround(worldId, home, SETTLING_SPREAD, SETTLING_ATTEMPTS, -1, null);
		// the centre only when nothing around the place will do at all, which means the place has no floor the navmesh knows of
		return spot != null ? spot : home;
	}

	/** @return The race of that name, or null when it is not one. Accepts the enum's own spelling, so ELYOS and ASMODIANS. */
	private static Race raceOf(String raceName) {
		try {
			Race race = Race.valueOf(raceName.toUpperCase());
			return race == Race.ELYOS || race == Race.ASMODIANS ? race : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * @return Which faction the inhabitant of a given place should belong to, or null when nothing says.
	 *         <p>
	 *         The place first, the map only as a fallback, and that order is the whole of what makes contested ground work. On a faction's own
	 *         ground the two always agree, so nothing changes there. In Reshanta the map has no answer at all and each fort has its own: Teminon is
	 *         Elyos, Primum Asmodian, and the proportion between them comes out of how much of the region each side holds rather than out of a
	 *         setting somebody has to keep true. It is the same argument the rest of this module makes about levels and densities — ask the world.
	 */
	private static Race factionFor(int worldId, BotPlaces.Settlement place) {
		return place.race() != null ? place.race() : raceOfMap(worldId);
	}

	/**
	 * @return The faction a map's <b>type</b> declares, or null when it declares none — an instance, the Abyss, Panesterra, and all of Balaurea,
	 *         whose regions carry one world type between them although Inggison is Elyos and Gelkmaros Asmodian. So this is a coarse answer and only
	 *         a fallback: {@link #factionFor} asks the place first, and the place is right in every one of those cases.
	 *         <p>
	 *         Which map it is, never who is asking. A region's inhabitants are its own: filling Morheim with Elyos because an Elyos happened to be
	 *         standing in it populates the map with people the guards would kill on sight, and that is exactly what happened the first time somebody
	 *         ran {@code //bot populate} on the other faction's ground.
	 */
	public static Race raceOfMap(int worldId) {
		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(worldId);
		if (map == null || map.isInstance())
			return null;
		return switch (map.getWorldType()) {
			case ELYSEA -> Race.ELYOS;
			case ASMODAE -> Race.ASMODIANS;
			default -> null;
		};
	}

	/**
	 * @return A class a character of this level could actually be. Below ten only the four starting classes exist, and handing out a cleric of level
	 *         six would be a character the game itself cannot make.
	 */
	private static PlayerClass classFor(int level) {
		List<PlayerClass> choices = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values()) {
			if (playerClass.isStartingClass() == (level < 10))
				choices.add(playerClass);
		}
		return choices.get(Rnd.get(0, choices.size() - 1));
	}

	/** @return How many places of each level a list holds, for comparing what a map offers against what a population was actually given. */
	private static String levelSpread(List<BotPlaces.Settlement> places) {
		Map<Integer, Integer> byLevel = new TreeMap<>();
		for (BotPlaces.Settlement place : places)
			byLevel.merge(place.level(), 1, Integer::sum);
		StringBuilder sb = new StringBuilder(places.size() + " place(s)");
		byLevel.forEach((level, n) -> sb.append(" lvl").append(level).append(':').append(n));
		return sb.toString();
	}

	private String report(List<String> created, String failure) {
		String summary = created.isEmpty() ? "Created no bot" : "Created " + created.size() + " bots: " + String.join(", ", created);
		return failure == null ? summary : summary + " (stopped: " + failure + ")";
	}

	/**
	 * Deletes a bot character and everything attached to it.
	 * <p>
	 * Two restrictions, and they answer different questions. The reserved bot accounts mean a mistyped name can never reach a real character, whoever
	 * types it. Ownership means one player cannot destroy another's: {@code //bot} is open to every account, so without this anybody could have
	 * emptied the world or deleted somebody else's companions. Staff are exempt, since moderating the server is what the access level is for.
	 *
	 * @param requester Whoever asked. Null for the server itself, which owns nothing and may delete anything.
	 */
	public String delete(String characterName, Player requester) {
		if (findSpawnedBot(characterName) != null)
			return characterName + " is spawned, despawn it first";

		int objectId = PlayerDAO.getPlayerIdByName(characterName);
		if (objectId == 0)
			return "No character found with name " + characterName;
		if (PlayerDAO.getAccountId(objectId) < PlayerBotCreationService.BOT_ACCOUNT_ID_BASE)
			return characterName + " is not on a bot account, refusing to delete it";
		if (!mayCommand(characterName, requester))
			return characterName + " belongs to somebody else";

		PlayerService.deletePlayerFromDB(objectId);
		return "Deleted " + characterName;
	}

	/**
	 * @return true if this player may do as they like with that bot: the server may, staff may, and a player may with their own.
	 *         <p>
	 *         A bot the world made has no owner, so only staff and the server may destroy it — a player emptying a region they did not populate is
	 *         the same mistake as deleting somebody else's character, only larger.
	 */
	private boolean mayCommand(String characterName, Player requester) {
		if (requester == null || requester.isStaff())
			return true;
		return BotRoster.ownerOf(characterName) == requester.getObjectId();
	}

	public String despawn(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		if (!takeOutOfWorld(bot))
			return "Could not despawn " + characterName + " (see server log)";
		rememberRoster(); // taken out on purpose, so a restart leaves it out too
		boolean stillInWorld = World.getInstance().findVisibleObject(bot.getObjectId()) != null;
		return "Despawned " + bot.getName() + (stillInWorld ? " but it is still registered in the world!" : "");
	}

	/**
	 * Takes a bot out of the world, and answers whether this call was the one that did it.
	 * <p>
	 * The claim is the removal. Every way a bot can leave — an operator's command, the director putting it to sleep, the shutdown — runs through here,
	 * and more than one of them can pick the same character at the same moment. Removing from the set first means exactly one of them proceeds, where
	 * removing and then leaving without looking at the result had both of them saving the character and firing its departure twice.
	 */
	private boolean takeOutOfWorld(Player bot) {
		if (spawnedBots.remove(bot.getObjectId()) == null)
			return false; // somebody else is already taking it out
		try {
			PlayerBotLeaveWorldService.leaveWorld(bot);
			return true;
		} catch (RuntimeException e) {
			log.error("Could not take bot " + bot.getName() + " out of the world", e);
			return false;
		}
	}

	/**
	 * @return The bot of that character id if it is in the world, or null if it is in the pool. Which is the whole of what "asleep" means — there is
	 *         no flag and nothing to keep in step, only a character that is in the world or a character that is not.
	 */
	public Player spawnedBot(int playerId) {
		return spawnedBots.get(playerId);
	}

	/**
	 * Brings one of the world's own inhabitants into the world, at the place it lives.
	 * <p>
	 * Runs on the lifecycle lane, never on a tick thread: loading a character is some twenty blocking database round trips before the first navmesh
	 * lookup.
	 * <p>
	 * <b>Whether anybody is watching is not asked.</b> It was, on the reasoning that a character materialising in front of somebody is worse than an
	 * empty field, and the reasoning was wrong about the game it is in: on any server people log in, and they appear where they log in, in front of
	 * whoever is standing there. Nobody reads that as a fault because it is what the world does.
	 * <p>
	 * It was also the one refusal that could never resolve itself. A villager's home is the village and a client is told about everything within 95 m,
	 * so while a player stands in a place there is no unseen spot anywhere in it: Verteron held 67 of the 84 it wanted and refused the same 17
	 * arrivals every half minute for as long as somebody stayed in the citadel. Departures still ask — see {@link #sleep} — and that asymmetry is the
	 * point rather than an oversight: a refused departure costs nothing, because the region is already as full as it should be and the next review
	 * will ask again, while a refused arrival leaves the region short for exactly as long as the player stays.
	 *
	 * @return false if the bot cannot be brought in now, which is never final: the next review asks again.
	 */
	public Change wake(BotRoster.Resident resident) {
		Vector3f spot = scatterAround(resident.worldId(), resident.home());
		Player bot = loadAvailableBot(resident.name());
		if (bot == null)
			return Change.UNAVAILABLE; // deleted, or still on its way out of the world; either way not this review's business
		try {
			bot.setPosition(World.getInstance().createPosition(resident.worldId(), spot.getX(), spot.getY(), spot.getZ(), (byte) 0, 0));
			PlayerBotEnterWorldService.enterWorld(bot);
			spawnedBots.put(bot.getObjectId(), bot);
			BotRoster.setInWorld(resident.playerId(), true);
			return Change.DONE;
		} catch (RuntimeException e) {
			log.error("Could not wake bot " + resident.name(), e);
			return Change.UNAVAILABLE;
		}
	}

	/**
	 * Takes one of the world's own inhabitants out of the world, if this is a moment at which it may go.
	 * <p>
	 * Three refusals, in the order that matters. A bot somebody owns is not the world's to move at all. A bot anybody can see does not vanish, and the
	 * question is asked of its own known list, which is precisely the set of clients that would be told — not an estimate of who is near. Only then is
	 * the bot itself asked, because agreeing to go is irreversible: see {@link PlayerBotAI#tryRetire()}.
	 *
	 * @return false if the bot stays, which is never final.
	 */
	public Change sleep(Player bot) {
		if (!(bot.getAi() instanceof PlayerBotAI ai) || ai.isOwned())
			return Change.UNAVAILABLE;
		if (bot.getKnownList().streamPlayers().anyMatch(other -> !other.isBot()))
			return Change.SEEN;
		if (!ai.tryRetire())
			return Change.BUSY;
		if (!takeOutOfWorld(bot))
			return Change.UNAVAILABLE;
		BotRoster.setInWorld(bot.getObjectId(), false);
		return Change.DONE;
	}

	/**
	 * Why a change to the population did or did not happen, in either direction.
	 * <p>
	 * Three refusals rather than one boolean, because the reasons are not interchangeable to anybody reading the log. Being <b>seen</b> now only ever
	 * holds a departure back — arrivals no longer ask, since people appearing is what a server looks like. Being <b>busy</b> is a bot in a fight or on
	 * a road, which passes on its own within a tick or two. Only <b>unavailable</b> is a surprise. Saying "refused" and leaving the cause to be guessed
	 * is how a server with nobody on it came to report that a dozen departures had been refused for being watched.
	 */
	public enum Change {
		DONE, SEEN, BUSY, UNAVAILABLE
	}


	/**
	 * Answers yes to a pending question window, which a bot can never answer itself since it has no client.
	 */
	public String acceptRequest(String characterName, int questionId) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		if (!bot.getResponseRequester().respond(questionId, 1))
			return characterName + " has no pending request of that type";
		return characterName + " accepted";
	}

	/**
	 * Makes the bot attack the given player's target, or the player itself if it has no other target (handy during a duel).
	 */
	public String attack(String characterName, Player commander) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		Creature target = commander.getTarget() instanceof Creature creature && !creature.equals(bot) ? creature : commander;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.startAttacking(target);
		return characterName + " attacks " + target.getName();
	}

	public String stopAttacking(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.stopAttacking();
		return characterName + " stopped";
	}

	/**
	 * Toggles whether the bot acts on its own (engaging hostiles in reach, fighting back) or only follows commands.
	 */
	/**
	 * Reads or sets what a bot is for: a resident of the place it stands in, or an adventurer.
	 * <p>
	 * A resident gains no experience, so the region it inhabits keeps inhabitants of its own level. Takes effect on the next spawn, since the flag is
	 * set as a bot enters the world; a spawned bot is switched over on the spot as well.
	 */
	public String setKind(String characterName, Boolean resident) {
		if (resident == null)
			return characterName + " is " + (BotRoster.isResident(characterName) ? "a resident" : "an adventurer");
		BotRoster.setResident(characterName, resident);
		Player spawned = findSpawnedBot(characterName);
		if (spawned != null)
			spawned.getCommonData().setNoExp(resident);
		return characterName + " is now " + (resident ? "a resident, and will not level any further" : "an adventurer, and levels normally");
	}

	public String toggleAutonomy(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.setAutonomous(!botAi.isAutonomous());
		return characterName + " autonomy " + (botAi.isAutonomous() ? "on" : "off");
	}

	/**
	 * Makes the bot walk to the given player's position.
	 */
	/**
	 * Takes a bot off the ground and brings it back down, for watching flight work before anything decides to use it.
	 *
	 * @param heightArg How high to climb, in metres, or null for the default.
	 */
	public String fly(String characterName, String heightArg) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		float height = 0;
		if (heightArg != null) {
			try {
				height = Float.parseFloat(heightArg);
			} catch (NumberFormatException e) {
				return "Not a height: " + heightArg;
			}
		}
		return BotFlight.takeOff(bot, height);
	}

	/**
	 * Flies a bot to where the commander stands, which is the one way to aim a flight at somewhere no bot can walk to: a terrace, a ledge, a rooftop.
	 */
	public String flyTo(String characterName, Player commander) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (bot.getWorldId() != commander.getWorldId())
			return characterName + " is on another map";
		return BotFlight.flyTo(bot, new Vector3f(commander.getX(), commander.getY(), commander.getZ()));
	}

	public String land(String characterName) {
		Player bot = findSpawnedBot(characterName);
		return bot == null ? "No bot spawned with name " + characterName : BotFlight.land(bot);
	}

	public String come(String characterName, Player commander) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.setAnchor(commander.getX(), commander.getY(), commander.getZ()); // being sent somewhere makes it the bot's new home
		if (!botAi.walkTo(commander.getX(), commander.getY(), commander.getZ()))
			return characterName + " is blocked by an obstacle";
		return characterName + " is on its way";
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check, so the vendor trip can be tested without farming first.
	 */
	public String sell(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";
		if (!BotVendorManager.hasJunk(bot))
			return characterName + " has nothing worth selling";

		return botAi.forceSellTrip() ? characterName + " is heading to sell" : "No shop found on " + characterName + "'s map";
	}

	/**
	 * Brings one spawned bot, or every one of them, up to what it would be given if it were created today.
	 * <p>
	 * Dressing happens once, at creation, so a world settled before a change to the wardrobe never sees it. This is the way to apply one without
	 * deleting the population and building it again — which would throw away every level, every home and every name it has.
	 * <p>
	 * Saved afterwards, one bot at a time rather than at the end, because a pass over a thousand inhabitants that is interrupted halfway should leave
	 * the half it finished written rather than nothing at all.
	 *
	 * @param characterName
	 *          One bot's name, or "all" for every spawned bot.
	 */
	public String regear(String characterName) {
		if (!characterName.equalsIgnoreCase("all")) {
			Player bot = findSpawnedBot(characterName);
			if (bot == null)
				return "No bot spawned with name " + characterName;
			String done = BotOutfitter.regear(bot);
			save(bot);
			return done;
		}
		int changed = 0;
		for (Player bot : spawnedBots.values()) {
			try {
				String done = BotOutfitter.regear(bot);
				if (!done.endsWith("had nothing to change")) {
					log.info(done);
					changed++;
				}
				save(bot);
			} catch (RuntimeException e) {
				log.error("Could not regear bot " + bot.getName(), e);
			}
		}
		return "Regeared " + changed + " of " + spawnedBots.size() + " spawned bot(s)";
	}

	/**
	 * Lists what a spawned bot carries. Reads live memory, not the database, which only ever sees a bot when it despawns.
	 */
	public String describeInventory(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		Storage inventory = bot.getInventory();
		StringBuilder sb = new StringBuilder(
			String.format("%s carries %d kinah in %d/%d slots:", bot.getName(), inventory.getKinah(), inventory.size(), inventory.getLimit()));
		inventory.getItems().forEach(item -> sb.append(String.format("%n  %dx %s", item.getItemCount(), item.getItemTemplate().getL10n())));
		return sb.toString();
	}

	/** @return Which generated maps are currently held in memory. */
	public String describeNavmeshes() {
		return NavmeshService.getInstance().describe();
	}

	public String listSpawnedBots() {
		if (spawnedBots.isEmpty())
			return "No bots spawned";
		StringBuilder sb = new StringBuilder("Spawned bots:");
		spawnedBots.values().forEach(bot -> sb.append(String.format("%n  %s at %.1f %.1f %.1f%s", bot.getName(), bot.getX(), bot.getY(), bot.getZ(),
			bot.getMoveController().isInMove() ? " (moving)" : "")));
		return sb.toString();
	}

	/**
	 * Empties the world of bots and deletes their characters.
	 * <p>
	 * Only characters on the reserved bot accounts are touched; the deletion path refuses anything else, which is what keeps a mistyped command from
	 * reaching a real player. Nothing is done about the roster: it hangs off the characters themselves, so it goes with them.
	 * <p>
	 * For an ordinary player this means their own bots and nothing else — the refused count says how many were left alone. Staff and the server clear
	 * everything, which is what the command is for.
	 *
	 * @param requester Whoever asked. Null for the server carrying out a standing order.
	 */
	/**
	 * Deletes bot characters, either everywhere or on one map.
	 * <p>
	 * The map is why this takes an argument at all. Clearing is global and populating is not — it fills the map the commander is standing on — so a
	 * clear followed by a populate emptied a whole world and refilled one region of it. That asymmetry is easy to miss until three maps are gone and
	 * one has come back.
	 * <p>
	 * It also matters that most of a world is rarely the part anybody wants rebuilt. Wiping two hundred characters to look at the dozens that are
	 * high enough to show the thing being tested is a lot of levels, homes and names spent on nothing.
	 *
	 * @param region A map id, part of a map's name, or null for everywhere. "here" is resolved by the caller.
	 */
	public String clear(Player requester, String region) {
		Integer onlyHere = null;
		if (region != null && !region.isBlank()) {
			onlyHere = resolveMap(region, requester);
			if (onlyHere == null)
				return "No map matches " + region;
		}
		// Only when the whole world is going, because this empties every map of bots and the per-name despawn below covers the scoped case anyway.
		if (onlyHere == null && (requester == null || requester.isStaff()))
			despawnEverything();
		int deleted = 0, kept = 0, elsewhere = 0;
		for (String name : PlayerBotCreationService.botCharacterNames()) {
			if (!mayCommand(name, requester)) {
				kept++;
				continue;
			}
			// Asked before the despawn, because despawning is what takes the character out of the world and with it the cheap answer.
			if (onlyHere != null && !Objects.equals(onlyHere, mapOf(name))) {
				elsewhere++;
				continue;
			}
			despawn(name); // its own bot may well be in the world, and deleting refuses a spawned character
			if (delete(name, requester).startsWith("Deleted "))
				deleted++;
			else
				kept++;
		}
		return "Deleted " + deleted + " bot character(s)" + (kept > 0 ? ", " + kept + " left alone" : "")
			+ (elsewhere > 0 ? ", " + elsewhere + " on other maps untouched" : "");
	}

	/**
	 * @return Which map a bot character is on, or null if it cannot be found.
	 *         <p>
	 *         Memory first and the database second, and that order is not only about speed: a spawned bot may have walked to another map since it was
	 *         last written, and the saved position lags by up to a save sweep. What is in the world is the truth about where it is.
	 */
	private Integer mapOf(String characterName) {
		Player spawned = findSpawnedBot(characterName);
		if (spawned != null)
			return spawned.getWorldId();
		PlayerCommonData stored = PlayerDAO.loadPlayerCommonDataByName(characterName);
		return stored == null ? null : stored.getMapId();
	}

	/**
	 * Counts who is on a map, by faction and by what they are there for.
	 * <p>
	 * Read from the world rather than from the roster or the database, because what matters is who is actually standing on the map at this instant —
	 * the roster says what should come back after a restart and the database lags a save sweep behind.
	 *
	 * @param region A map id, or part of a map's name, or null for the commander's own map.
	 */
	public String count(String region, Player commander) {
		Integer worldId = resolveMap(region, commander);
		if (worldId == null)
			return "No map matches " + region;

		int elyos = 0, asmodians = 0, companions = 0, players = 0;
		for (Player everyone : World.getInstance().getAllPlayers()) {
			if (everyone.getWorldId() != worldId)
				continue;
			if (!everyone.isBot())
				players++;
			else if (everyone.getAi() instanceof PlayerBotAI ai && ai.isOwned())
				companions++;
			else if (everyone.getRace() == Race.ELYOS)
				elyos++;
			else
				asmodians++;
		}
		String name = DataManager.WORLD_MAPS_DATA.getTemplate(worldId) == null ? String.valueOf(worldId)
			: DataManager.WORLD_MAPS_DATA.getTemplate(worldId).getName() + " (" + worldId + ")";
		// What the director is actually working towards, not what the region has characters for. The establishment was the figure here before, and it
		// answers a different question: a map asleep but for its villages was quoted as wanting nineteen when the review it answers to wanted ten.
		BotDirector.Band band = BotDirector.getInstance().band(worldId);
		return String.format("%s holds %d inhabitant(s): %d Elyos, %d Asmodian. %d companion(s), %d player(s). The region wants %d right now, out of "
			+ "%d with nobody online and %d with a player on it.", name, elyos + asmodians, elyos, asmodians, companions, players, band.now(),
			band.quiet(), band.crowded());
	}

	/** @return The map named, by id or by part of its name, or the commander's own when nothing is named. */
	private Integer resolveMap(String region, Player commander) {
		if (region == null || region.isBlank())
			return commander == null ? null : commander.getWorldId();
		try {
			return Integer.parseInt(region.trim());
		} catch (NumberFormatException e) {
			for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
				if (map.getName() != null && map.getName().toLowerCase().startsWith(region.trim().toLowerCase()))
					return map.getMapId();
			}
			return null;
		}
	}

	public String despawnAll() {
		int count = despawnEverything();
		rememberRoster(); // emptied by hand, so the world comes back empty
		return "Despawned " + count + " bot(s)";
	}

	/**
	 * Takes every bot out of the world on the way down, leaving the roster alone so the next start puts them back.
	 * <p>
	 * Not {@link #despawnAll()}: that one is an operator emptying the world on purpose, and clearing the roster is the whole point of it. Going
	 * through it here would mean every restart came back to an empty map.
	 */
	public void onShutdown() {
		// Before the count is read, let alone acted on: emptying the world walks the set of bots in it and then clears the set, so a bot the director
		// brings in between those two steps is left standing in a world nothing holds a reference to any more.
		BotScheduler.getInstance().stopEnteringAndLeaving();
		log.info("Despawning {} bot(s) for shutdown", spawnedBots.size());
		despawnEverything();
	}

	/** @return How many bots were taken out. Leaves the roster untouched; the caller decides what it now means. */
	private int despawnEverything() {
		int count = spawnedBots.size();
		spawnedBots.values().forEach(bot -> {
			try {
				PlayerBotLeaveWorldService.leaveWorld(bot);
			} catch (RuntimeException e) {
				log.error("Could not despawn bot " + bot.getName(), e);
			}
		});
		spawnedBots.clear();
		return count;
	}

	private Player loadAvailableBot(String characterName) {
		if (World.getInstance().getPlayer(characterName) != null)
			return null;
		return PlayerBotLoader.load(characterName);
	}

	private Player findSpawnedBot(String characterName) {
		return spawnedBots.values().stream().filter(bot -> bot.getName().equalsIgnoreCase(characterName)).findFirst().orElse(null);
	}

	private static class SingletonHolder {

		private static final PlayerBotService INSTANCE = new PlayerBotService();
	}
}
