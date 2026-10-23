package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.TownServices;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.world.World;

/**
 * Residents of the rest of the world who come to a capital for a while: the levels a capital's own residents lack.
 * <p>
 * A capital's residents are made at the level of its monsters, as everywhere, so they are mostly under 30 or from 45 to 49: on 23 October
 * Pandaemonium held 18 bots of 50 and over among 135, and a tester saw none. More than 270 bots of 50 and over live in the Abyss, Inggison and
 * Gelkmaros and never came. See {@code docs/capital-life-plan.md}.
 * <p>
 * A visitor is a sleeper woken at a teleporter of the capital, where players arrive, while a real player is in the capital. It lives the town day
 * there for 20 to 40 minutes, keeps no store and is rehomed by nothing, then walks back to the teleporter and is taken home. Where it lives is kept
 * in the database ({@link BotRoster#setVisitingFrom}), so that a visitor saved in the capital is still filed under its own map.
 */
public final class BotVisitors {

	private static final Logger log = LoggerFactory.getLogger(BotVisitors.class);

	/** The capitals that are visited, with the faction living in each. */
	private static final Map<Integer, Race> CAPITALS = Map.of(110010000, Race.ELYOS, 120010000, Race.ASMODIANS);
	/** How many visitors a capital holds at once while a real player is in it. */
	static final int VISITORS = 15;
	/** How long a visit lasts, in minutes. */
	private static final int SHORTEST_STAY = 20, LONGEST_STAY = 40;
	/** How long past the end of its visit a visitor may take to walk to the teleporter before it is taken home from where it stands, in milliseconds. */
	private static final long WALK_OUT_MILLIS = 5 * 60_000L;
	/** The lowest level that visits: a capital is reached by ascending. */
	private static final int LOWEST_LEVEL = 10;
	/** How wide a band of levels is when the town is counted for the one it lacks most. */
	private static final int BAND = 5;
	/** How often the capitals are looked at, in milliseconds: a player often only passes through one, and the visitors are to be there when they do. */
	public static final long REVIEW_MILLIS = 5000;
	/** How long the visitors stay once the last real player has left a capital, in milliseconds: one who comes straight back finds them still there. */
	private static final long LINGER_MILLIS = 5 * 60_000L;
	/**
	 * How long a visitor stands where it arrived before its day begins, in seconds. The fifteen of a capital arrive within seconds of one another,
	 * and set off together down the same street, which looked like a convoy rather than people.
	 */
	private static final int SHORTEST_LOOK_ROUND = 15, LONGEST_LOOK_ROUND = 150;
	/** How far round a teleporter a visitor arrives, in metres. */
	private static final float ARRIVAL_SPREAD = 10;

	/** One visit: where the visitor lives and where it arrived, which is its place in town and its way out. */
	private record Visit(int capital, int homeWorldId, Vector3f home, Vector3f arrival, long settled, long until) {
	}

	private static final Map<Integer, Visit> visits = new ConcurrentHashMap<>();
	/** When a real player was last seen in each capital. */
	private static final Map<Integer, Long> lastWatched = new ConcurrentHashMap<>();

	private BotVisitors() {
	}

	/**
	 * Brings every missing visitor at once to each capital that has a real player in it, and ends the visits to a capital nobody has been in for
	 * {@link #LINGER_MILLIS}. All at once and not one a review: a player often only passes through a capital, and one visitor every half minute was
	 * a town that filled up after they had left. Runs every {@link #REVIEW_MILLIS} on the lane that brings bots in and takes them out.
	 */
	public static void review() {
		long now = System.currentTimeMillis();
		// put to sleep where it stood, by the director: its next start sends it home (PlayerBotEnterWorldService.returnFromVisit)
		visits.keySet().removeIf(id -> PlayerBotService.getInstance().spawnedBot(id) == null);
		for (Map.Entry<Integer, Race> capital : CAPITALS.entrySet()) {
			int worldId = capital.getKey();
			if (World.getInstance().getAllPlayers().stream().anyMatch(player -> !player.isBot() && player.getWorldId() == worldId))
				lastWatched.put(worldId, now);
			else if (now - lastWatched.getOrDefault(worldId, 0L) >= LINGER_MILLIS) {
				// nobody to see them for a while: every visit ends, and the visitors walk back to their teleporter
				visits.replaceAll((id, visit) -> visit.capital() == worldId && visit.until() > now
					? new Visit(visit.capital(), visit.homeWorldId(), visit.home(), visit.arrival(), visit.settled(), now) : visit);
				continue;
			}
			long missing = VISITORS - visits.values().stream().filter(visit -> visit.capital() == worldId).count();
			if (missing > 0)
				bring(worldId, capital.getValue(), (int) missing, now);
		}
	}

	private static void bring(int worldId, Race race, int count, long now) {
		List<BotRoster.Sleeper> sleepers = new ArrayList<>();
		for (BotRoster.Sleeper sleeper : BotRoster.sleepers(race)) {
			int home = sleeper.resident().worldId();
			if (sleeper.level() >= LOWEST_LEVEL && !CAPITALS.containsKey(home) && !isInstance(home))
				sleepers.add(sleeper);
		}
		for (int brought = 0; brought < count && !sleepers.isEmpty(); brought++) {
			BotRoster.Sleeper chosen = choose(worldId, sleepers);
			sleepers.remove(chosen);
			bringIn(worldId, chosen, now);
		}
	}

	/** @return The sleeper of the band of levels the capital holds fewest of, drawn at random within it. */
	private static BotRoster.Sleeper choose(int worldId, List<BotRoster.Sleeper> sleepers) {
		int top = sleepers.stream().mapToInt(BotRoster.Sleeper::level).max().getAsInt();
		int[] inTown = new int[bandOf(top) + 1];
		for (Player player : World.getInstance().getAllPlayers()) {
			if (player.isBot() && player.getWorldId() == worldId && player.getLevel() >= LOWEST_LEVEL && player.getLevel() <= top)
				inTown[bandOf(player.getLevel())]++;
		}
		Map<Integer, List<BotRoster.Sleeper>> byBand = new HashMap<>();
		for (BotRoster.Sleeper sleeper : sleepers)
			byBand.computeIfAbsent(bandOf(sleeper.level()), band -> new ArrayList<>()).add(sleeper);
		List<Integer> bands = new ArrayList<>(byBand.keySet());
		Collections.shuffle(bands); // a draw between bands the town lacks as much
		List<BotRoster.Sleeper> candidates = byBand.get(thinnest(inTown, bands));
		return candidates.get(Rnd.get(0, candidates.size() - 1));
	}

	private static void bringIn(int worldId, BotRoster.Sleeper chosen, long now) {
		BotRoster.Resident resident = chosen.resident();
		Vector3f arrival = arrivalIn(worldId, resident.playerId());
		if (arrival == null)
			return; // no teleporter on the map: nowhere a visitor would come from
		long until = now + Rnd.get(SHORTEST_STAY, LONGEST_STAY) * 60_000L;
		// before it is in the world: its first tick asks where its place in town is
		long settled = now + Rnd.get(SHORTEST_LOOK_ROUND, LONGEST_LOOK_ROUND) * 1000L;
		visits.put(resident.playerId(), new Visit(worldId, resident.worldId(), resident.home(), arrival, settled, until));
		Player bot = PlayerBotService.getInstance().wakeVisitor(resident, worldId, arrival);
		if (bot == null) {
			visits.remove(resident.playerId());
			return;
		}
		log.info("Bot {} (level {}) visits map {} from map {} for {} minutes", bot.getName(), chosen.level(), worldId, resident.worldId(),
			(until - now) / 60_000);
	}

	/** @return Which band of {@link #BAND} levels from {@link #LOWEST_LEVEL} a level falls in. */
	static int bandOf(int level) {
		return (level - LOWEST_LEVEL) / BAND;
	}

	/**
	 * @param inTown How many characters of each band the town holds.
	 * @param bands The bands a visitor can be drawn from, in the order ties are settled.
	 * @return The band the town holds fewest of: the levels a capital's own residents lack.
	 */
	static int thinnest(int[] inTown, List<Integer> bands) {
		return bands.stream().min(Comparator.comparingInt(band -> inTown[band])).orElseThrow();
	}

	/** @return A spot round one of the capital's teleporters, the same one for a character every time, or null when the map has none. */
	private static Vector3f arrivalIn(int worldId, int seed) {
		List<Vector3f> teleporters = TownServices.keepersOn(worldId, TownServices.Service.TELEPORTER);
		if (teleporters.isEmpty())
			return null;
		Vector3f teleporter = teleporters.get(Math.floorMod(seed, teleporters.size()));
		Vector3f spot = BotPlaces.spotAround(worldId, teleporter, ARRIVAL_SPREAD, 8, seed, null);
		return spot != null ? spot : teleporter;
	}

	private static boolean isInstance(int worldId) {
		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(worldId);
		return map == null || map.isInstance();
	}

	/** @return true while the bot is visiting a capital. */
	public static boolean isVisitor(Player bot) {
		return visits.containsKey(bot.getObjectId());
	}

	/** @return The visitor's place in town, which is where it arrived, or null for a bot that is not visiting. */
	public static Vector3f placeInTown(Player bot) {
		Visit visit = visits.get(bot.getObjectId());
		return visit == null ? null : visit.arrival();
	}

	/** @return true while a visitor that has just arrived stands looking round, before its day begins. */
	public static boolean isArriving(Player bot) {
		Visit visit = visits.get(bot.getObjectId());
		return visit != null && System.currentTimeMillis() < visit.settled();
	}

	/** @return Where the visitor leaves from once its visit is over, the teleporter it arrived by, or null while it stays. */
	public static Vector3f wayOut(Player bot) {
		Visit visit = visits.get(bot.getObjectId());
		return visit == null || System.currentTimeMillis() < visit.until() ? null : visit.arrival();
	}

	/** @return true once a visitor has had long enough to walk to its teleporter: it is then taken home from where it stands. */
	public static boolean isOverdue(Player bot) {
		Visit visit = visits.get(bot.getObjectId());
		return visit != null && System.currentTimeMillis() >= visit.until() + WALK_OUT_MILLIS;
	}

	/** Takes a visitor home, to its own map, as a player leaves by the teleporter. */
	public static void depart(Player bot) {
		Visit visit = visits.remove(bot.getObjectId());
		if (visit == null)
			return;
		BotRoster.setVisitingFrom(bot.getObjectId(), 0);
		Vector3f spot = BotPopulator.scatterAround(visit.homeWorldId(), visit.home());
		log.info("Bot {} leaves map {} for its home on map {}", bot.getName(), visit.capital(), visit.homeWorldId());
		TeleportService.teleportTo(bot, visit.homeWorldId(), spot.getX(), spot.getY(), spot.getZ());
	}
}
