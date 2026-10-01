package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.lifecycle.BotOutfitter;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshBuilder;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.BotPresence;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLeaveWorldService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.world.WorldType;
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
	 */
	public void onStartUp() {
		BotScheduler.getInstance().scheduleAtFixedRate(this::saveDue, SAVE_SWEEP_MILLIS, SAVE_SWEEP_MILLIS);
		runStandingOrders();
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
			log.info("Standing order: {}", clear(null));
		populateConfiguredMaps();
	}

	/**
	 * Populates any configured map that has nobody on it yet.
	 * <p>
	 * This is what makes the bot system work on a server other than the one it was written on. Everything else about a population lives in the
	 * database — the characters, the roster, where each one lives — so a fresh installation has none of it, and until now the only ways to get some
	 * were to type a command in game or to write a row into {@code server_variables} by hand. Neither is an installation step anybody should have to
	 * be told about.
	 * <p>
	 * Only ever for an empty map, so it runs once and is silent on every start afterwards, and so it can never quietly double a population.
	 */
	/**
	 * Populates every open world map of both races, building the navmeshes it finds missing.
	 * <p>
	 * This is what {@code auto} means, and the point of it is that a server should not need a decision per map. There are eighteen of them across
	 * the two factions, and asking somebody to name each one, pick a count and remember a race for it is how a feature ends up used on exactly the
	 * machine it was written on. Everything here is read from the world instead: which maps are open world, which race lives on each, and how many
	 * people a map holds — one per place it turns out to have, so a starting valley gets a village's worth and a large region gets a region's.
	 * <p>
	 * A map with no mesh is set building in the background and skipped for now: generating one takes seconds and about a gigabyte, so holding the
	 * server's startup on eighteen of them is out of the question. Those maps are populated on the next start, by which time their meshes exist.
	 */
	private void populateEveryOpenMap() {
		int populated = 0;
		for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
			if (map.isInstance() || map.getWorldType() != WorldType.ELYSEA && map.getWorldType() != WorldType.ASMODAE)
				continue;
			if (PlayerBotCreationService.hasBotsOn(map.getMapId()))
				continue;
			NavmeshBuilder.ensureMesh(map.getMapId()); // before anyone is put on it: a map without one is a map bots cannot plan a route across
			Race race = map.getWorldType() == WorldType.ASMODAE ? Race.ASMODIANS : Race.ELYOS;
			// no count: the region says how many it wants, from its own civilians rather than from its monsters
			log.info("Populating map {}: {}", map.getMapId(), populate(0, map.getMapId(), race.name()));
			populated++;
		}
		log.info("Populated {} map(s) automatically", populated);
	}

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
				NavmeshBuilder.ensureMesh(worldId); // before anyone is put on it, and before the server opens
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
	 * Creates several bots at once with generated names, for populating an area.
	 */
	/**
	 * Fills a region with people who belong to it.
	 * <p>
	 * The old version cloned one template character count times, so a map ended up with one class, one level and one set of training gear repeated.
	 * Nothing about it was a population. What a region needs is taken from the region itself: the level band comes from the creatures living there,
	 * the classes are spread over those a character of that level could be, and the gear is fitted to each one. They are residents from birth, so
	 * they stay the level their home is worth.
	 *
	 * @param commander Whoever asked, whose map is populated and whose race and looks the new characters borrow.
	 */
	public String populate(int count, int worldId, String raceName) {
		Race race = raceOf(raceName);
		if (race == null)
			return "Unknown race " + raceName + ", expected ELYOS or ASMODIANS";
		// One entry per inhabitant the region asks for, each naming the place it belongs to. A busy village appears many times over and a roadside
		// camp once, so going round the list in order puts the population where the world put its own people — which is what the count times a
		// density never did, because that count was fed by how many monsters a map holds.
		List<BotPlaces.Settlement> places = BotPresence.establishment(worldId);
		if (places.isEmpty())
			return "Nothing lives on this map, so there is nowhere to put anyone";
		if (count <= 0)
			count = places.size(); // as the region asks, which is what an unattended start wants
		log.info("Map {} takes {} inhabitant(s) across {}", worldId, count, levelSpread(places));

		List<String> created = new ArrayList<>();
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
			int level = Math.max(1, home.level() + Rnd.get(-1, 1));
			PlayerClass playerClass = classFor(level);
			try {
				Player bot = PlayerBotCreationService.create(name, playerClass, level, race);
				BotRoster.setResident(name, true);
				BotRoster.setHome(name, home.centre());
				// into the world before it is dressed: equipping asks whether its wearer is spawned, and a character with no position yet is not a
				// question that has an answer
				settle(bot, worldId, home.centre());
				BotOutfitter.dress(bot);
				PlayerService.storePlayer(bot);
				created.add(name + " (" + playerClass + " " + level + ")");
			} catch (IllegalArgumentException | IllegalStateException e) {
				log.warn("Could not create bot " + name, e);
				rememberRoster();
				return report(created, e.getMessage());
			}
		}
		rememberRoster();
		return report(created, null) + ", over " + places.size() + " places";
	}

	/**
	 * Puts a newly made resident into the world at the place it belongs, scattered a little so a village does not appear as a stack of people on one
	 * spot. Creating without spawning would mean invoking forty five characters by hand afterwards.
	 */
	private void settle(Player bot, int worldId, Vector3f home) {
		if (home != null) {
			Vector3f spot = scatterAround(worldId, home);
			bot.setPosition(World.getInstance().createPosition(worldId, spot.getX(), spot.getY(), spot.getZ(), (byte) 0, 0));
		}
		PlayerBotEnterWorldService.enterWorld(bot);
		spawnedBots.put(bot.getObjectId(), bot);
		// the roster is written once by the caller when the whole population is in, not once per inhabitant: rewriting the full set after each of
		// forty five arrivals is forty five statements to say what one says at the end
	}

	/**
	 * Finds a spot a few paces from a place, so a village is a village rather than a stack of people on one point.
	 * <p>
	 * Two conditions, and each was learned by leaving it out. The spot must be ground a body fits on, or the villager is dropped inside a hut, a
	 * trunk or a mushroom cap. And it must be ground connected to the place itself: a ledge, a hollow or the far side of a wall is perfectly good
	 * ground that leads nowhere, and a resident put on one spends its life asking for a route out. Failing both, it stands on the spot itself, which
	 * is by construction somewhere the world put something.
	 */
	private static Vector3f scatterAround(int worldId, Vector3f home) {
		NavmeshService navmesh = NavmeshService.getInstance();
		for (int attempt = 0; attempt < SETTLING_ATTEMPTS; attempt++) {
			double angle = Math.random() * Math.PI * 2;
			float spread = SETTLING_SPREAD * (float) Math.random();
			float x = home.getX() + (float) Math.cos(angle) * spread, y = home.getY() + (float) Math.sin(angle) * spread;
			Vector3f ground = navmesh.groundNear(worldId, x, y, home.getZ());
			if (ground != null && navmesh.canReach(worldId, ground.getX(), ground.getY(), ground.getZ(), home.getX(), home.getY(), home.getZ()))
				return ground;
		}
		return home;
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

		spawnedBots.remove(bot.getObjectId());
		rememberRoster(); // taken out on purpose, so a restart leaves it out too
		try {
			PlayerBotLeaveWorldService.leaveWorld(bot);
		} catch (RuntimeException e) {
			log.error("Could not despawn bot " + characterName, e);
			return "Could not despawn " + characterName + " (see server log)";
		}
		boolean stillInWorld = World.getInstance().findVisibleObject(bot.getObjectId()) != null;
		return "Despawned " + bot.getName() + (stillInWorld ? " but it is still registered in the world!" : "");
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
	public String clear(Player requester) {
		if (requester == null || requester.isStaff())
			despawnEverything();
		int deleted = 0, kept = 0;
		for (String name : PlayerBotCreationService.botCharacterNames()) {
			if (!mayCommand(name, requester)) {
				kept++;
				continue;
			}
			despawn(name); // its own bot may well be in the world, and deleting refuses a spawned character
			if (delete(name, requester).startsWith("Deleted "))
				deleted++;
			else
				kept++;
		}
		return "Deleted " + deleted + " bot character(s)" + (kept > 0 ? ", " + kept + " left alone" : "");
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
