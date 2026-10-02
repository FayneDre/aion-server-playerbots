package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.Collection;
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
 * <b>Only the countryside breathes.</b> Settlements are a fixed cast — see {@link BotPresence#civic}. A village thinned while nobody was watching
 * could never be filled again while somebody stood in it, because every villager's home is within sight of the obelisk a player arrives at.
 * <p>
 * Three rules hold the whole thing together, and each of them is a thing that cannot be taken back once it has been seen or lost:
 * <ul>
 * <li><b>Nobody watches it happen.</b> A character materialising in front of a player is worse than an empty field, so a wake refused is a wake not
 * done — the next review asks again half a minute later, and refusing costs one comparison.
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
	 * How far a region may be from its target before anything is done about it. Without it a map sits on the boundary and churns, waking and sleeping
	 * the same inhabitant every half minute for a difference nobody could see.
	 */
	private static final int DEAD_BAND = 1;

	private static final BotDirector INSTANCE = new BotDirector();

	/** When each map last had a real player on it, which is what makes the surge decay rather than stop. */
	private final Map<Integer, Long> lastSeenPlayer = new ConcurrentHashMap<>();
	/** What the last review concluded, for {@code //bot pool} to report. Written once a review, read rarely. */
	private volatile List<String> lastReview = List.of();

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
			Map<Vector3f, Integer> target = countByPlace(wanted(worldId, busy, anybodyOnline));

			Difference difference = sort(bots, residents, target);
			report.add(String.format("map %d: %d awake of %d, wants %d%s", worldId, difference.awake(), residents.size(),
				target.values().stream().mapToInt(count -> count).sum(), busy ? " (" + watchers.size() + " player(s))" : ""));
			if (difference.toWake().size() + difference.toSleep().size() <= DEAD_BAND)
				continue; // near enough, and churning over one inhabitant is worse than being one short

			for (BotRoster.Resident resident : difference.toWake()) {
				if (woken >= budget)
					break;
				woken++;
				BotScheduler.getInstance().enterOrLeaveWorld(() -> bots.wake(resident, watchers));
			}
			for (Player bot : difference.toSleep()) {
				if (slept >= budget)
					break;
				slept++;
				BotScheduler.getInstance().enterOrLeaveWorld(() -> bots.sleep(bot));
			}
		}
		lastReview = List.copyOf(report);
		if (woken > 0 || slept > 0)
			log.info("Population review: waking {} and putting {} to sleep across {} map(s)", woken, slept, maps.size());
	}

	/**
	 * Decides, place by place, who should be brought in and who should go.
	 * <p>
	 * Per place and not per map total, because a map total is satisfied by a crowd in one valley. Each place is compared with what it should hold, and
	 * only the places that disagree produce any work.
	 */
	private static Difference sort(PlayerBotService bots, List<BotRoster.Resident> residents, Map<Vector3f, Integer> target) {
		Map<Vector3f, List<BotRoster.Resident>> asleepHere = new LinkedHashMap<>();
		Map<Vector3f, List<Player>> awakeHere = new LinkedHashMap<>();
		int awake = 0;
		for (BotRoster.Resident resident : residents) {
			Player bot = bots.spawnedBot(resident.playerId());
			if (bot == null) {
				asleepHere.computeIfAbsent(resident.home(), home -> new ArrayList<>()).add(resident);
			} else {
				awakeHere.computeIfAbsent(resident.home(), home -> new ArrayList<>()).add(bot);
				awake++;
			}
		}
		List<BotRoster.Resident> toWake = new ArrayList<>();
		target.forEach((home, wanted) -> {
			int missing = wanted - awakeHere.getOrDefault(home, List.of()).size();
			List<BotRoster.Resident> available = asleepHere.getOrDefault(home, List.of());
			for (int i = 0; i < Math.min(missing, available.size()); i++)
				toWake.add(available.get(i));
		});
		// Anybody awake at a place the region no longer asks for, which includes every place the plan has stopped naming at all.
		List<Player> toSleep = new ArrayList<>();
		awakeHere.forEach((home, here) -> {
			int keep = target.getOrDefault(home, 0);
			for (int i = keep; i < here.size(); i++)
				toSleep.add(here.get(i));
		});
		return new Difference(awake, toWake, toSleep);
	}

	/** What one region is short of and what it has too much of, as one answer so the counting is done once. */
	private record Difference(int awake, List<BotRoster.Resident> toWake, List<Player> toSleep) {
	}

	/** @return What this region should hold at this moment: its settlements entire, plus the share of its countryside that is being watched for. */
	private static List<Settlement> wanted(int worldId, boolean busy, boolean anybodyOnline) {
		float attention = busy ? ATTENTION_BUSY : anybodyOnline ? ATTENTION_QUIET : ATTENTION_UNWATCHED;
		List<Settlement> posts = new ArrayList<>(BotPresence.civic(worldId));
		posts.addAll(BotPresence.share(BotPresence.field(worldId, busy), attention));
		return posts;
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

	/** @return What the last review concluded, a line per map, for an operator asking where the population stands. */
	public List<String> lastReview() {
		return lastReview;
	}
}
