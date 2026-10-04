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
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
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
	/** Whether the reviews are running, which is false for the whole of startup. See {@link #reviewNow()}. */
	private volatile boolean started;

	public void start() {
		started = true;
		BotScheduler scheduler = BotScheduler.getInstance();
		scheduler.scheduleAtFixedRate(() -> scheduler.enterOrLeaveWorld(this::review), REVIEW_INTERVAL_MILLIS, REVIEW_INTERVAL_MILLIS);
	}

	/** Works out what each region should hold, and acts on the difference. */
	/**
	 * Asks for a review now rather than at the next turn of the clock.
	 * <p>
	 * For the moments when somebody is watching and waiting: a map that has just been populated holds a hundred sleepers and nobody at all, and the
	 * ordinary half minute between reviews is half a minute of staring at an empty village. It changes the timing and nothing else — the same review,
	 * with the same budget, so arrivals still come at the pace that keeps them from appearing in a flood.
	 */
	public void reviewNow() {
		// Not before the director itself has started, and this is not a nicety. A populate at startup runs from the standing orders, which happen
		// *before* the roster is put back: a review queued there raced restoreRoster for the same characters and spawned sixteen of them twice,
		// which the world refuses with a DuplicateAionObjectException apiece. The order in onStartUp is deliberate and this has to respect it.
		if (!started)
			return;
		BotScheduler.getInstance().enterOrLeaveWorld(this::review);
	}

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
			List<Settlement> fieldPlan = BotPresence.share(BotPresence.field(worldId, busy), attention(busy, anybodyOnline));
			int hunters = fieldPlan.size();

			Difference difference = sort(id -> bots.spawnedBot(id) != null, home -> BotPlaces.settlementAt(worldId, home), residents, villages,
				fieldPlan);
			int wanted = villages.values().stream().mapToInt(count -> count).sum() + hunters;
			report.add(String.format("map %d: %d awake of %d, wants %d%s — villages %d/%d awake, %d asleep; field %d/%d awake, %d asleep", worldId,
				difference.awake(), residents.size(), villages.values().stream().mapToInt(count -> count).sum() + hunters,
				busy ? " (" + watchers.size() + " player(s))" : "", difference.villagersAwake(),
				villages.values().stream().mapToInt(count -> count).sum(), difference.villagersAsleep(), difference.huntersAwake(), hunters,
				difference.huntersAsleep()));
			// The home moves first, and they cost no budget: a row each, and no bot enters or leaves the world for them. Done before the wakes so
			// that a post filled by a mover is a post the next review already counts as lived at.
			if (!difference.rehoused().isEmpty())
				log.info("map {}: moving {} sleeper(s) house", worldId, difference.rehoused().size());
			difference.rehoused().forEach(mover -> BotRoster.setHome(mover.name(), mover.home()));
			// The band holds departures back and lets arrivals through, because only one of the two directions can churn. A region that wakes its way
			// up to its target then stops: there is nothing left to ask for. A region that sleeps its way down to it sits on the boundary, and the next
			// review finds it one over, or one under, and moves somebody again. Applied to both, the band left a map one inhabitant short for ever —
			// seen in game, a village missing its last resident with nobody anywhere near it, and nothing in the log to say why.
			if (difference.toWake().isEmpty() && difference.toSleep().size() <= DEAD_BAND)
				continue;

			// A region already holding more than it wants wakes nobody, whatever any one place inside it is short of. Without this the two rules
			// could still pull against each other -- a village asking for somebody while the countryside around it is over-supplied -- and the region
			// would be bringing people in while sending people away, which is the shape of the fault this is here to stop recurring. It sheds first;
			// what it is short of, it is short of until it has.
			if (difference.awake() <= wanted) {
				for (BotRoster.Resident resident : difference.toWake()) {
					if (woken >= budget)
						break;
					woken++;
					BotScheduler.getInstance().enterOrLeaveWorld(() -> arrivals.merge(bots.wake(resident), 1, Integer::sum));
				}
			}
			for (int playerId : difference.toSleep()) {
				if (slept >= budget)
					break;
				Player bot = bots.spawnedBot(playerId);
				if (bot == null)
					continue; // it left between the review and here, which is the outcome this was asking for anyway
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
		if (woken > 0 || slept > 0) {
			log.info("Population review asks for {} arrival(s) and {} departure(s) across {} map(s)", woken, slept, maps.size());
			// The per map lines, which until now only an operator running //bot pool ever saw. A total cannot say which region is asking for what,
			// and the question that matters about a population is never "how many" but "where, and why that one" -- measured against a run where the
			// same bots went in and out of the world 2200 times in 97 reviews and the summary line read as orderly throughout.
			report.forEach(line -> log.info("  {}", line));
		}
	}

	/**
	 * Decides, place by place, who should be brought in and who should go.
	 * <p>
	 * Per place and not per map total, because a map total is satisfied by a crowd in one valley. Each place is compared with what it should hold, and
	 * only the places that disagree produce any work.
	 */
	static Difference sort(IntPredicate isAwake, UnaryOperator<Vector3f> settlementAt, List<BotRoster.Resident> residents,
		Map<Vector3f, Integer> villages, List<Settlement> fieldPlan) {
		int hunters = fieldPlan.size();
		Map<Vector3f, List<BotRoster.Resident>> asleepInVillage = new LinkedHashMap<>();
		Map<Vector3f, List<Integer>> awakeInVillage = new LinkedHashMap<>();
		List<BotRoster.Resident> asleepInField = new ArrayList<>();
		List<Integer> awakeInField = new ArrayList<>();
		int awake = 0;
		for (BotRoster.Resident resident : residents) {
			boolean up = isAwake.test(resident.playerId());
			if (up)
				awake++;
			// A villager belongs to its village and a hunter belongs to the countryside, and the two are counted differently on purpose. Compare the
			// countryside place by place and most of it can never be filled: a region names fifty one hunting grounds and its inhabitants were given
			// thirteen of them as homes, so the plan asks for hunters at grounds nobody lives at while inhabitants sleep at grounds already worked.
			// Measured on Poeta: fourteen awake, five asleep, and a target of thirty seven that could not move. A hunting ground is interchangeable
			// with another — the bot works the country near its own home either way — where a village is a place, and the one a player walks into.
			// Keyed by the centre the plan itself holds, never by the home the resident came back from the database with: the two are the same
			// place and not the same number. See BotPlaces.SAME_PLACE.
			Vector3f post = settlementAt.apply(resident.home());
			if (post != null) {
				if (up)
					awakeInVillage.computeIfAbsent(post, home -> new ArrayList<>()).add(resident.playerId());
				else
					asleepInVillage.computeIfAbsent(post, home -> new ArrayList<>()).add(resident);
			} else if (up) {
				awakeInField.add(resident.playerId());
			} else {
				asleepInField.add(resident);
			}
		}

		List<BotRoster.Resident> toWake = new ArrayList<>();
		List<Integer> toSleep = new ArrayList<>();
		Map<Vector3f, Integer> stillShort = new LinkedHashMap<>();
		Map<Vector3f, Integer> takenLocally = new HashMap<>();
		villages.forEach((home, wanted) -> {
			int missing = wanted - awakeInVillage.getOrDefault(home, List.of()).size();
			List<BotRoster.Resident> available = asleepInVillage.getOrDefault(home, List.of());
			int local = Math.max(0, Math.min(missing, available.size()));
			for (int i = 0; i < local; i++)
				toWake.add(available.get(i));
			takenLocally.put(home, local);
			if (missing > local)
				stillShort.put(home, missing - local);
		});
		// Anybody awake in a village the region no longer asks for, which includes every village the plan has stopped naming at all.
		awakeInVillage.forEach((home, here) -> {
			int keep = villages.getOrDefault(home, 0);
			for (int i = keep; i < here.size(); i++)
				toSleep.add(here.get(i));
		});
		// The posts no village can fill from its own sleepers are set aside before the countryside takes anybody, because a settlement is a fixed
		// cast and the countryside is the part that breathes. Without it the field claimed the last sleepers first and an empty village post waited
		// on a region being in surplus -- which a region short of people never is. Only for a review: a bot set aside is rehoused below, and is the
		// village's own sleeper by the next one.
		int reserved = Math.min(stillShort.values().stream().mapToInt(Integer::intValue).sum(), asleepInField.size());
		int fieldWoken = Math.max(0, Math.min(hunters - awakeInField.size(), asleepInField.size() - reserved));
		for (int i = 0; i < fieldWoken; i++)
			toWake.add(asleepInField.get(i));
		for (int i = hunters; i < awakeInField.size(); i++)
			toSleep.add(awakeInField.get(i));
		// A village post that nobody lives at is filled by moving somebody in, because the alternative is that it is never filled at all. The
		// countryside was given this property from the start — "a hunting ground is interchangeable with another" — and villages were not, on the
		// grounds that a village is a place rather than a slot. True of the place, false of the person: a resident can move house, and a region that
		// cannot reach its own target because its people were settled against a plan that has since shifted is worse than one whose villager came
		// from the next valley. Measured on Verteron: 25 village posts, 11 villagers, and 14 hunters asleep in a countryside already full.
		List<BotRoster.Resident> spare = new ArrayList<>(asleepInField.subList(fieldWoken, asleepInField.size()));
		// A village's own sleepers beyond the post it has to fill can move on too. Without this a bot settled at a village the plan has since
		// shrunk is stranded there: the countryside only ever wakes its own sleepers, so it is never hunted with again, and no amount of shortage
		// anywhere else can reach it.
		//
		// Listed apart because these are also the ones that may have to go back to the countryside, and because taking from the end of `spare`
		// spends them before any hunter: a village post is better filled by somebody another village does not need than by taking a hunter out of
		// a countryside that is itself short.
		List<BotRoster.Resident> spareVillagers = new ArrayList<>();
		asleepInVillage.forEach((home, there) -> {
			for (int i = takenLocally.getOrDefault(home, 0); i < there.size(); i++)
				spareVillagers.add(there.get(i));
		});
		spare.addAll(spareVillagers);
		// Moved house where they sleep, and not woken to do it. Waking them was the whole fault: when a countryside is over its target, nothing is
		// drawn from it by the field rule, so `spare` holds every one of its sleepers -- which is to say exactly the bots the same review has just
		// ordered to sleep for being surplus. The region then put them to sleep by one rule and pulled them straight back out by the other, and did
		// it again half a minute later. Measured on Eltnen, 80 hunters awake against a target of 5 and 20 village posts nobody lived at: 111 bots
		// went in and out of the world three times or more in twenty minutes, the worst of them eighteen times.
		//
		// A sleeping bot's home is a row in the database, so moving it costs a write and no world at all. Once moved, the bot is a village sleeper:
		// it leaves the countryside's pool, the village branch above wakes it when that village is genuinely short, and the field rule stops seeing
		// it. That also removes the old dependency on the wake succeeding -- with four refusals in five, most movers never had their home written at
		// all, so the next review drafted the same bots over again.
		List<BotRoster.Resident> rehoused = new ArrayList<>();
		for (Map.Entry<Vector3f, Integer> village : stillShort.entrySet()) {
			for (int i = 0; i < village.getValue() && !spare.isEmpty(); i++) {
				BotRoster.Resident mover = spare.remove(spare.size() - 1);
				rehoused.add(new BotRoster.Resident(mover.playerId(), mover.name(), mover.worldId(), village.getKey()));
			}
		}
		// And the way back out of a village, which was missing and is what made the move into one a door that only opened once. A sleeper given a
		// village post stays a villager for ever otherwise: the countryside wakes only its own sleepers, and nothing ever hands one back. Verteron
		// settled at 44 residents holding 25 village posts while its countryside, wanting 59, held 40 and had no sleeper left at all -- nineteen
		// people shut in villages that did not want them, and a region that could not reach its target however long it ran.
		//
		// Only when the countryside is actually short of people, so a village is never emptied to feed a field that has enough; and only sleepers
		// the villages have no post for, which is what is left in `spare` once the short posts above have taken what they need.
		int fieldShort = hunters - (awakeInField.size() + asleepInField.size());
		if (fieldShort > 0 && !fieldPlan.isEmpty()) {
			int ground = 0;
			for (BotRoster.Resident villager : spareVillagers) {
				if (fieldShort <= 0 || !spare.contains(villager))
					continue; // already spent on a short village post, which is the nearer need
				fieldShort--;
				// round the plan rather than all to the nearest ground: the plan is already spread over the region, which is the whole reason a
				// countryside is described as a list of grounds rather than as a number.
				Vector3f home = fieldPlan.get(ground++ % fieldPlan.size()).centre();
				rehoused.add(new BotRoster.Resident(villager.playerId(), villager.name(), villager.worldId(), home));
			}
		}
		int villagersAwake = awakeInVillage.values().stream().mapToInt(List::size).sum();
		int villagersAsleep = asleepInVillage.values().stream().mapToInt(List::size).sum();
		return new Difference(awake, toWake, toSleep, rehoused, villagersAwake, villagersAsleep, awakeInField.size(), asleepInField.size());
	}

	/**
	 * What one region is short of and what it has too much of, as one answer so the counting is done once.
	 * <p>
	 * The four tallies are there because the aggregate cannot say why a region is stuck. Villages are filled place by place — only a sleeper whose
	 * home is that very village can take its post — while the countryside is interchangeable, so "70 awake of 84, wants 84" has two quite different
	 * explanations and the same shape. Split in two it reads at a glance: villagers asleep while villages are short means posts nobody lives at,
	 * hunters asleep while the field is full means the countryside is simply over-supplied.
	 */
	/** What one review concluded about one region. Package private, and holding character ids rather than {@link Player}s, so the decision can be
	 * tested without a world: everything here is a count, an id or a place. */
	record Difference(int awake, List<BotRoster.Resident> toWake, List<Integer> toSleep, List<BotRoster.Resident> rehoused, int villagersAwake,
		int villagersAsleep, int huntersAwake, int huntersAsleep) {
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
	 * Gives a new home to anybody living at a spot the plan no longer names.
	 * <p>
	 * Written straight to the roster and not queued as a change: it costs a row each, wakes nobody, and the next review reads the result. Measured on
	 * Eltnen, where twelve places stood on ground nothing walks off and seventeen people lived at one of them.
	 */
	private static void evictFromNowhere(int worldId, List<BotRoster.Resident> residents) {
		List<Settlement> countryside = BotPresence.field(worldId, true);
		if (countryside.isEmpty())
			return; // nowhere to send anybody, so leaving them where they are is the lesser harm
		int ground = 0, moved = 0;
		for (BotRoster.Resident resident : residents) {
			if (BotPlaces.isAPlace(worldId, resident.home()))
				continue;
			BotRoster.setHome(resident.name(), countryside.get(ground++ % countryside.size()).centre());
			moved++;
		}
		if (moved > 0)
			log.info("map {}: moved {} inhabitant(s) off a place that no longer exists", worldId, moved);
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
