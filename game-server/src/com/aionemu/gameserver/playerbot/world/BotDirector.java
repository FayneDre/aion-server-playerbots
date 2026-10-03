package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.PlayerBotService;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.world.BotPlaces.Settlement;
import com.aionemu.gameserver.world.World;

/**
 * Keeps each region holding the people it should, which is more of them where somebody is playing.
 * <p>
 * The population used to be decided once, by {@code //bot populate}, and never looked at again: a region held whatever had been created on it, awake
 * for ever, whether anybody was there or not. That is enough to make a world which <i>contains</i> people and not enough to make one that feels
 * inhabited, because the countryside at its quiet density is thin by design — Poeta's ten occupied grounds are spread over 288000 square metres.
 * <p>
 * So every inhabitant is a character in the database and only some of them are in the world. This reviews the difference and closes it: it wakes
 * inhabitants where a region is short and puts them back to sleep where it is over-full, and a player arriving on a map is what raises the density
 * around them. That is the one change which alters what the world feels like rather than what it contains.
 * <p>
 * <b>Only the countryside breathes.</b> Settlements are a fixed cast — see {@link BotPresence#civic}.
 * <p>
 * Two rules hold the whole thing together, and each of them is a thing that cannot be taken back once it has been seen or lost:
 * <ul>
 * <li><b>Departures are not watched; arrivals do not care.</b> A bot does not vanish in front of somebody, and refusing costs nothing because the
 * region is already as full as it should be. Arrivals used to ask the same question and it was the wrong one: on any server people log in and appear
 * where they stand, so nobody reads that as a fault — and asking left a village permanently short, since a villager's home is the one place a player
 * is standing in.
 * <li><b>The bot picks the moment, not this.</b> Here decides that a region holds too many; {@code PlayerBotAI.tryRetire} decides whether this
 * particular one may go now.
 * <li><b>A bot somebody owns is not part of any of this.</b> It is not counted, woken, slept or moved. The pool query settles that at the source.
 * </ul>
 */
public class BotDirector {

	private static final Logger log = LoggerFactory.getLogger(BotDirector.class);

	/**
	 * How often the world is reviewed. Long on purpose: a region's population is not a thing that should twitch, and the surge only has to be in
	 * place by the time a player has walked somewhere, not by the time they have turned round.
	 */
	private static final long REVIEW_INTERVAL_MILLIS = 30000;
	/** Share of the countryside that is awake on a map somebody is playing on. All of it: this is the density the plan already raised. */
	private static final float ATTENTION_BUSY = 1f;
	/** On a quiet map, with somebody playing elsewhere. A fifth of the countryside reads as a countryside; the rest is cost nobody is observing. */
	private static final float ATTENTION_QUIET = 0.2f;
	/**
	 * With nobody online at all. A floor rather than the nothing it is tempting to make it: a sleeping bot earns no experience, and the population
	 * model is a flow — a band holds what arrives times how long it stays. Put the whole world to sleep and no career advances overnight, so a
	 * server comes back every morning to the population it had the first day. This keeps the careers moving at a cost nobody is paying attention to.
	 */
	private static final float ATTENTION_UNWATCHED = 0.05f;
	/**
	 * How long a map keeps its busy density after the last real player leaves. Nobody is served by a countryside that empties the moment somebody
	 * logs out — least of all the engine, which activates and deactivates the npc ai of nine regions around every arrival and departure.
	 */
	private static final long LINGER_MILLIS = 300000;
	/**
	 * Changes a review may make while anybody is online, each way. The work itself is on its own thread, but it is not free: every wake is a character
	 * loaded out of the database, and a hundred of them in one go is a burst of queries the real players are also waiting behind.
	 */
	private static final int CHANGES_PER_REVIEW = 25;
	/**
	 * How far over its target a region may be before anybody is sent away. Departures only: an arrival cannot churn, since a region that fills to its
	 * target has nothing further to ask for, where one that empties to it sits on the boundary and is found one over, then one under, every half
	 * minute.
	 */
	private static final int DEAD_BAND = 1;

	private static final BotDirector INSTANCE = new BotDirector();

	/** When each map last had a real player on it, which is what makes the surge decay rather than stop. */
	private final Map<Integer, Long> lastSeenPlayer = new ConcurrentHashMap<>();
	/** What the last review concluded, for {@code //bot pool} to report. Written once a review, read rarely. */
	private volatile List<String> lastReview = List.of();
	/**
	 * What the changes asked for by the previous review actually came to.
	 * <p>
	 * Counted because the first version of this log counted what it <b>asked</b> for and said it had done it, and a refusal is the common case rather
	 * than the exception — a region short of hunters while a player stands in it asks every half minute and is told no every half minute. Read as
	 * outcomes, that log showed a population churning once a review; it was a population correctly holding still. Plain fields: everything that writes
	 * them, the review included, runs on the one lifecycle thread.
	 */
	private final Map<PlayerBotService.Change, Integer> arrivals = new EnumMap<>(PlayerBotService.Change.class);
	private final Map<PlayerBotService.Change, Integer> departures = new EnumMap<>(PlayerBotService.Change.class);

	private BotDirector() {
	}

	public static BotDirector getInstance() {
		return INSTANCE;
	}

	/**
	 * The timer is on the tick lane because that is the only lane that can keep one, but all it does is hand the review over: reading the pool is a
	 * query, and a tick thread blocked on the database is bots standing still. Running on the same lane as the arrivals it orders also means a review
	 * never overlaps the changes the last one asked for, so nothing is decided twice from a stale picture.
	 */
	public void start() {
		BotScheduler scheduler = BotScheduler.getInstance();
		scheduler.scheduleAtFixedRate(() -> scheduler.enterOrLeaveWorld(this::review), REVIEW_INTERVAL_MILLIS, REVIEW_INTERVAL_MILLIS);
	}

	/** Works out what each region should hold, and acts on the difference. */
	private void review() {
		List<BotRoster.Resident> pool = BotRoster.pool();
		Map<Integer, List<Player>> playersByMap = realPlayersByMap();
		boolean anybodyOnline = !playersByMap.isEmpty();
		long now = System.currentTimeMillis();
		playersByMap.keySet().forEach(worldId -> lastSeenPlayer.put(worldId, now));

		Map<Integer, List<BotRoster.Resident>> residentsByMap = new HashMap<>();
		for (BotRoster.Resident resident : pool)
			residentsByMap.computeIfAbsent(resident.worldId(), id -> new ArrayList<>()).add(resident);

		// Every map that has inhabitants to place, and every map that has somebody on it. The first half is what matters and is what the old review
		// was missing: it walked the world's players and so never looked at a map whose whole population was asleep, which is now most of them.
		Set<Integer> maps = new HashSet<>(residentsByMap.keySet());
		maps.addAll(playersByMap.keySet());

		PlayerBotService bots = PlayerBotService.getInstance();
		int budget = anybodyOnline ? CHANGES_PER_REVIEW : Integer.MAX_VALUE;
		int woken = 0, slept = 0;
		List<String> report = new ArrayList<>();
		for (int worldId : maps) {
			List<Player> watchers = playersByMap.getOrDefault(worldId, List.of());
			boolean busy = isBusy(worldId, watchers, now);
			List<BotRoster.Resident> residents = residentsByMap.getOrDefault(worldId, List.of());
			Map<Vector3f, Integer> villages = countByPlace(BotPresence.civic(worldId));
			int hunters = BotPresence.share(BotPresence.field(worldId, busy), attention(busy, anybodyOnline)).size();

			Difference difference = sort(bots, worldId, residents, villages, hunters);
			report.add(String.format("map %d: %d awake of %d, wants %d%s", worldId, difference.awake(), residents.size(),
				villages.values().stream().mapToInt(count -> count).sum() + hunters, busy ? " (" + watchers.size() + " player(s))" : ""));
			// The band holds departures back and lets arrivals through, because only one of the two directions can churn. A region that wakes its way
			// up to its target then stops: there is nothing left to ask for. A region that sleeps its way down to it sits on the boundary, and the next
			// review finds it one over, or one under, and moves somebody again. Applied to both, the band left a map one inhabitant short for ever —
			// seen in game, a village missing its last resident with nobody anywhere near it, and nothing in the log to say why.
			if (difference.toWake().isEmpty() && difference.toSleep().size() <= DEAD_BAND)
				continue;

			for (BotRoster.Resident resident : difference.toWake()) {
				if (woken >= budget)
					break;
				woken++;
				BotScheduler.getInstance().enterOrLeaveWorld(() -> arrivals.merge(bots.wake(resident), 1, Integer::sum));
			}
			for (Player bot : difference.toSleep()) {
				if (slept >= budget)
					break;
				slept++;
				BotScheduler.getInstance().enterOrLeaveWorld(() -> departures.merge(bots.sleep(bot), 1, Integer::sum));
			}
		}
		// What the previous round came to, before this one's tasks start reporting into the same counters. Said as outcomes rather than as intentions:
		// a refusal is ordinary, and reading the asked-for figure as the done figure is how a population holding still came to look like one churning.
		if (!arrivals.isEmpty() || !departures.isEmpty())
			log.info("Population: {} arrived and {} left. Put off: {}", done(arrivals), done(departures), putOff());
		arrivals.clear();
		departures.clear();

		lastReview = List.copyOf(report);
		if (woken > 0 || slept > 0)
			log.info("Population review asks for {} arrival(s) and {} departure(s) across {} map(s)", woken, slept, maps.size());
	}

	/**
	 * Decides, place by place, who should be brought in and who should go.
	 * <p>
	 * Per place and not per map total, because a map total is satisfied by a crowd in one valley. Each place is compared with what it should hold, and
	 * only the places that disagree produce any work.
	 */
	private static Difference sort(PlayerBotService bots, int worldId, List<BotRoster.Resident> residents, Map<Vector3f, Integer> villages,
		int hunters) {
		Map<Vector3f, List<BotRoster.Resident>> asleepInVillage = new LinkedHashMap<>();
		Map<Vector3f, List<Player>> awakeInVillage = new LinkedHashMap<>();
		List<BotRoster.Resident> asleepInField = new ArrayList<>();
		List<Player> awakeInField = new ArrayList<>();
		int awake = 0;
		for (BotRoster.Resident resident : residents) {
			Player bot = bots.spawnedBot(resident.playerId());
			if (bot != null)
				awake++;
			// A villager belongs to its village and a hunter belongs to the countryside, and the two are counted differently on purpose. Compare the
			// countryside place by place and most of it can never be filled: a region names fifty one hunting grounds and its inhabitants were given
			// thirteen of them as homes, so the plan asks for hunters at grounds nobody lives at while inhabitants sleep at grounds already worked.
			// Measured on Poeta: fourteen awake, five asleep, and a target of thirty seven that could not move. A hunting ground is interchangeable
			// with another — the bot works the country near its own home either way — where a village is a place, and the one a player walks into.
			if (BotPlaces.isSettlement(worldId, resident.home())) {
				if (bot == null)
					asleepInVillage.computeIfAbsent(resident.home(), home -> new ArrayList<>()).add(resident);
				else
					awakeInVillage.computeIfAbsent(resident.home(), home -> new ArrayList<>()).add(bot);
			} else if (bot == null) {
				asleepInField.add(resident);
			} else {
				awakeInField.add(bot);
			}
		}

		List<BotRoster.Resident> toWake = new ArrayList<>();
		List<Player> toSleep = new ArrayList<>();
		villages.forEach((home, wanted) -> {
			int missing = wanted - awakeInVillage.getOrDefault(home, List.of()).size();
			List<BotRoster.Resident> available = asleepInVillage.getOrDefault(home, List.of());
			for (int i = 0; i < Math.min(missing, available.size()); i++)
				toWake.add(available.get(i));
		});
		// Anybody awake in a village the region no longer asks for, which includes every village the plan has stopped naming at all.
		awakeInVillage.forEach((home, here) -> {
			int keep = villages.getOrDefault(home, 0);
			for (int i = keep; i < here.size(); i++)
				toSleep.add(here.get(i));
		});
		for (int i = 0; i < Math.min(hunters - awakeInField.size(), asleepInField.size()); i++)
			toWake.add(asleepInField.get(i));
		for (int i = hunters; i < awakeInField.size(); i++)
			toSleep.add(awakeInField.get(i));
		return new Difference(awake, toWake, toSleep);
	}

	/** What one region is short of and what it has too much of, as one answer so the counting is done once. */
	private record Difference(int awake, List<BotRoster.Resident> toWake, List<Player> toSleep) {
	}

	/** @return How much of a region's countryside is awake at this moment. Settlements are not scaled at all — see {@link BotPresence#civic}. */
	private static float attention(boolean busy, boolean anybodyOnline) {
		return busy ? ATTENTION_BUSY : anybodyOnline ? ATTENTION_QUIET : ATTENTION_UNWATCHED;
	}

	/**
	 * @return true while the map counts as played on, which outlasts the player being on it. See {@link #LINGER_MILLIS}.
	 */
	private boolean isBusy(int worldId, Collection<Player> watchers, long now) {
		if (!watchers.isEmpty())
			return true;
		Long seen = lastSeenPlayer.get(worldId);
		return seen != null && now - seen < LINGER_MILLIS;
	}

	/**
	 * @return The real players of each map they are on. One pass over the world, because every map's answer comes out of the same walk — and a bot is
	 *         not a player for this purpose however much the engine thinks it is one.
	 */
	private static Map<Integer, List<Player>> realPlayersByMap() {
		Map<Integer, List<Player>> byMap = new HashMap<>();
		World.getInstance().forEachPlayer(someone -> {
			if (!someone.isBot())
				byMap.computeIfAbsent(someone.getWorldId(), id -> new ArrayList<>()).add(someone);
		});
		return byMap;
	}

	/** @return How many of each place the plan names, which is the plan read as "this many here" rather than as a list of posts. */
	private static Map<Vector3f, Integer> countByPlace(List<Settlement> posts) {
		Map<Vector3f, Integer> counts = new LinkedHashMap<>();
		for (Settlement post : posts)
			counts.merge(post.centre(), 1, Integer::sum);
		return counts;
	}

	private int done(Map<PlayerBotService.Change, Integer> changes) {
		return changes.getOrDefault(PlayerBotService.Change.DONE, 0);
	}

	/**
	 * @return Why the changes that did not happen did not happen, named rather than totalled. A cause the log invents is worse than no cause at all:
	 *         the first version of this blamed every refusal on a player being near, and reported a dozen departures refused for being watched on a
	 *         server with nobody connected to it.
	 */
	private String putOff() {
		StringBuilder reasons = new StringBuilder();
		for (PlayerBotService.Change cause : PlayerBotService.Change.values()) {
			if (cause == PlayerBotService.Change.DONE)
				continue;
			int count = arrivals.getOrDefault(cause, 0) + departures.getOrDefault(cause, 0);
			if (count > 0)
				reasons.append(reasons.isEmpty() ? "" : ", ").append(count).append(switch (cause) {
					case SEEN -> " within sight of a player";
					case BUSY -> " in a fight or on a road";
					case UNAVAILABLE -> " no longer available";
					default -> "";
				});
		}
		return reasons.isEmpty() ? "nothing" : reasons.toString();
	}

	/**
	 * What a region should hold at this moment, and the band it moves within.
	 * <p>
	 * Reported rather than worked out again by whoever asks, because the only figure worth showing an operator is the one the review itself acts on.
	 * The establishment — every inhabitant the region has a character for — is not that figure: it is what the pool holds, and the countryside is only
	 * ever awake in the share that attention justifies, so a region quoted at its establishment reads as permanently short of people nobody was ever
	 * going to wake.
	 *
	 * @return What the region wants now, the fewest it ever keeps awake, and the most it holds with somebody playing on it.
	 */
	public Band band(int worldId) {
		Map<Integer, List<Player>> playersByMap = realPlayersByMap();
		int villagers = BotPresence.civic(worldId).size();
		boolean busy = isBusy(worldId, playersByMap.getOrDefault(worldId, List.of()), System.currentTimeMillis());
		int now = villagers + BotPresence.share(BotPresence.field(worldId, busy), attention(busy, !playersByMap.isEmpty())).size();
		int quiet = villagers + BotPresence.share(BotPresence.field(worldId, false), ATTENTION_UNWATCHED).size();
		int crowded = villagers + BotPresence.share(BotPresence.field(worldId, true), ATTENTION_BUSY).size();
		return new Band(now, quiet, crowded);
	}

	/** What a region should hold at this moment, at its quietest, and at its busiest. */
	public record Band(int now, int quiet, int crowded) {
	}

	/** @return What the last review concluded, a line per map, for an operator asking where the population stands. */
	public List<String> lastReview() {
		return lastReview;
	}
}
