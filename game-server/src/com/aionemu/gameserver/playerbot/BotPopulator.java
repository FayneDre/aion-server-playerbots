package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.lifecycle.BotOutfitter;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshBuilder;
import com.aionemu.gameserver.playerbot.world.BotDirector;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.BotPresence;
import com.aionemu.gameserver.playerbot.economy.BotStigmaFitter;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.world.World;

/**
 * Fills the world with its inhabitants: which places a map asks for, who lives at each, and the standing orders that populate a fresh installation
 * on its first start. Split from {@link PlayerBotService}, which keeps who is in the world, so that the algorithm of a population and the lifecycle of
 * a bot can be read apart.
 */
public final class BotPopulator {

	private static final Logger log = LoggerFactory.getLogger(BotPopulator.class);

	/** How far around its home a new resident may appear, so a village is not a stack of people on one spot. */
	private static final float SETTLING_SPREAD = 15f;
	/** How many spots to try before giving up and standing on the place itself. A handful: most spots around a place are fine. */
	private static final int SETTLING_ATTEMPTS = 8;
	/** The setting that means "work it out": every open world map of both races, each taking as many inhabitants as its own civilians call for. */
	private static final String AUTOMATIC = "auto";

	private BotPopulator() {
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
	private static void populateEveryOpenMap() {
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
	static void populateConfiguredMaps() {
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
	public static String populate(int count, int worldId, String raceName) {
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
	private static void place(Player bot, int worldId, Vector3f home) {
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
	static Vector3f scatterAround(int worldId, Vector3f home) {
		Vector3f spot = BotPlaces.spotAround(worldId, home, SETTLING_SPREAD, SETTLING_ATTEMPTS, -1, null);
		// the centre only when nothing around the place will do at all, which means the place has no floor the navmesh knows of
		return spot != null ? spot : home;
	}

	/** @return The race of that name, or null when it is not one. Accepts the enum's own spelling, so ELYOS and ASMODIANS. */
	static Race raceOf(String raceName) {
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

	private static String report(List<String> created, String failure) {
		String summary = created.isEmpty() ? "Created no bot" : "Created " + created.size() + " bots: " + String.join(", ", created);
		return failure == null ? summary : summary + " (stopped: " + failure + ")";
	}

}
