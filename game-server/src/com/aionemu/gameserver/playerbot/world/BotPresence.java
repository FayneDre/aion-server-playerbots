package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.playerbot.world.BotPlaces.Settlement;

/**
 * How many inhabitants a region should have, and which place each of them belongs to.
 * <p>
 * This replaces one number per map — the count of places it had, times a density — and the reason is that the old number was fed by how many
 * monsters a map holds. Measured across the whole world, that gave Brusthonin **311** inhabitants for its 27 civilians, while Sanctum, where players
 * actually gather, got 28. The rule was validated on Poeta, a small starter valley where "how much there is to kill" and "how many people would live
 * here" happen to agree. They do not agree anywhere else.
 * <p>
 * So presence has two sources instead of one, and neither is the monster count:
 * <ul>
 * <li><b>civic</b> — one inhabitant per civilian npc in a place, which is the world's own statement of how busy that place was meant to be;
 * <li><b>field</b> — a small baseline in the hunting grounds, which the population director will raise where real players are.
 * </ul>
 * Travellers are not allocated: the occupation draw already sends some residents walking between settlements, so transit is something the population
 * does rather than a share of it.
 * <p>
 * <b>Per place, not per map.</b> Working out a map's total and then sharing it out compresses twice — the big place is crushed and the small one gets
 * a floor it has not earned. Measured at the old setting: a roadside camp of 4 npcs drew 7 inhabitants while Sanctum's main plaza drew 9. Two errors
 * in opposite directions at the same setting is the signature of a wrong shape rather than a wrong value.
 */
public class BotPresence {

	private static final Logger log = LoggerFactory.getLogger(BotPresence.class);

	/**
	 * Ground per inhabitant, in square metres, and the only figure anybody has to judge — because it is the one a person feels. It reads as "one
	 * villager for every thirteen hundred square metres of village".
	 * <p>
	 * A ratio to the npc count was the first answer, and a measurement was needed to see why it was wrong. Akarios holds 21 civilians over a 42 m
	 * radius, which is 262 square metres each; one bot per npc put 19 more in the same village, and they read as packed because they were. Sanctum
	 * meanwhile covers 155000 square metres of settlement against Poeta's 10670 — fourteen times the ground — and the ratio gave it three times the
	 * people. So the village felt crowded and the capital deserted at one and the same setting, which is what counting the wrong thing looks like.
	 * <p>
	 * These three densities were settled together, against a world total, and none of them can be moved on its own — see {@code docs/population.md}.
	 * The short of it: the 28 maps worth populating come to about two thousand inhabitants, which divides into a thousand a faction because the world
	 * was built in mirror.
	 */
	private static final float AREA_PER_RESIDENT = 1300f;
	/**
	 * Capitals are denser, and legitimately so: a capital square is genuinely busy where a starter village is not. Sanctum's 28 places then hold
	 * about six apiece against a village square's eight — busier per district than a field region, thinner than a village, which is what a capital
	 * spread over fifteen hectares should read as.
	 */
	private static final float AREA_PER_RESIDENT_IN_TOWN = 940f;
	/**
	 * Ground per hunter, in square metres of hunting country. Thirty times sparser than a village, which is what a countryside is.
	 * <p>
	 * This was a flat four per map to begin with, on the promise that the population director would raise it where players are. The director does not
	 * exist yet, so four was what Poeta's <b>forty seven</b> hunting grounds actually got: Agher's farm, Cliona lake, Kales farm, the plains and the
	 * quarry were all left empty, which is exactly the complaint that had been made of the version before it. Counting the ground answers it the same
	 * way it answered the villages.
	 * <p>
	 * It is also what moved the population off the capitals. Hunting country is on a different scale from settlement — Brusthonin's grounds cover 3.3
	 * million square metres against its villages' 76 thousand, a factor of 43 — so giving the countryside its own share took the capitals and their
	 * annexes from 29% of the world down to 20% without making any single place denser or thinner.
	 */
	private static final float AREA_PER_HUNTER = 40000f;
	/**
	 * Most hunters a region's countryside gets, however vast it is. Without it the huge high level maps swamp everything: Brusthonin's grounds alone
	 * would ask for 83. A countryside does not need more than this to read as worked, and the director will gather them where the players are anyway.
	 */
	private static final int FIELD_CAP = 60;
	/**
	 * Ground per hunter while somebody is actually playing on the map, and the whole point of the director.
	 * <p>
	 * A region at its quiet density is honest but thin: Poeta's ten occupied grounds are spread over 288000 square metres, so you can cross the
	 * countryside and meet nobody. Raising the density only where a player is costs almost nothing — a player is on one map at a time — and it is the
	 * one change that alters what the world feels like rather than what it contains.
	 */
	private static final float AREA_PER_HUNTER_WHEN_BUSY = 15000f;
	/** The same cap, raised in proportion, so a large region can actually answer the surge instead of hitting its quiet ceiling. */
	private static final int FIELD_CAP_WHEN_BUSY = 90;

	/**
	 * A service town. Oriel and Pernon hold 1624 civilians each — as many as seven Sanctums — over a quarter of a square kilometre, and nobody lives
	 * there; people pass through. The only cap left, because the trouble there is not density but that the place is not a home. Everywhere else the
	 * ground limits it by itself, which is why the caps this once needed for capitals and field regions are gone.
	 */
	private static final int SERVICE_CAP = 30;
	/** Civilians above which a town is a service rather than a home. */
	private static final int SERVICE_TOWNSFOLK = 500;

	private static final Map<Integer, List<Settlement>> establishmentByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> civicByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> fieldByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> busyFieldByMap = new ConcurrentHashMap<>();

	private BotPresence() {
	}

	/**
	 * @return One entry per inhabitant the region should have, naming the place that inhabitant belongs to. A busy village appears many times over
	 *         and a roadside camp once or twice, which is the whole point.
	 */
	public static List<Settlement> establishment(int worldId) {
		return establishmentByMap.computeIfAbsent(worldId, BotPresence::describe);
	}

	/**
	 * @return Where the region's inhabitants should be <b>right now</b>, which is more of them in the countryside when somebody is playing there.
	 *         <p>
	 *         Separate from {@link #establishment(int)} because the two answer different questions. The establishment is what the region holds when
	 *         nobody is looking, and it is what gets created and kept; this is what it should look like at this moment, and the surplus is drawn from
	 *         the pool and returned to it. Creating characters for a surge and deleting them afterwards would make a region's inhabitants different
	 *         strangers on every visit, which is not a world.
	 * @param busy Whether a real player is on the map.
	 */
	public static List<Settlement> wanted(int worldId, boolean busy) {
		if (!busy)
			return establishment(worldId);
		List<Settlement> both = new ArrayList<>(civic(worldId));
		both.addAll(field(worldId, true));
		return List.copyOf(both);
	}

	/**
	 * The inhabitants of the region's settlements, who are a fixed cast and never put to sleep.
	 * <p>
	 * Which is not a simplification but the only arrangement that works, and the reason is geometric. A player arrives at a bind obelisk, in a town —
	 * where this module deliberately puts bots' bind points too. Every villager's home is one place's centre with fifteen metres of scatter, so while
	 * that player stands there, every single one of them is inside the ninety five metres a client is told about. A village thinned while nobody was
	 * looking could then never be filled again until the player left: the one place a person actually looks at would be the one place the population
	 * director is unable to fix. So settlements do not vary, and the countryside — which is spread out by construction and nowhere near a bind point —
	 * is what the director breathes in and out.
	 */
	public static List<Settlement> civic(int worldId) {
		return civicByMap.computeIfAbsent(worldId, id -> plan(id, false, true));
	}

	/**
	 * The region's hunters, which is the part of a population that may sleep.
	 * <p>
	 * Cached for both answers. The busy plan used to be worked out afresh on every call, and it is not cheap: it spreads the hunting grounds apart,
	 * which this module's own notes describe as some tens of thousands of distance comparisons for a map the size of Poeta — the reason the places
	 * themselves are cached. A director asking for it every half minute for every inhabited map would have paid that each time, on a bot thread. It is
	 * a pure function of the map's spawns, so once is enough.
	 *
	 * @param busy Whether a real player is on the map, which is what raises the density.
	 */
	public static List<Settlement> field(int worldId, boolean busy) {
		Map<Integer, List<Settlement>> cache = busy ? busyFieldByMap : fieldByMap;
		return cache.computeIfAbsent(worldId, id -> plan(id, busy, false));
	}

	/**
	 * Keeps a share of a plan, spread over the places it names rather than taken from the front of it.
	 * <p>
	 * A share of the countryside is what attention comes to in the end: at a fifth, a region works a fifth of its grounds. Which fifth matters —
	 * keeping the first few would leave one valley busy and the rest of the map deserted, so this keeps every so many entries instead. Exposed because
	 * the director is the one that knows what share this minute deserves.
	 *
	 * @param share Between 0 and 1. Always leaves at least one inhabitant where the plan named any at all: a countryside with nobody in it is a
	 *          different thing from a quiet one.
	 */
	public static List<Settlement> share(List<Settlement> posts, float share) {
		if (posts.isEmpty() || share >= 1f)
			return posts;
		int keep = Math.max(1, Math.round(posts.size() * share));
		return keep >= posts.size() ? posts : List.copyOf(thinTo(posts, keep));
	}

	/** The whole of what a region should hold when nobody is there, which is also the only plan worth announcing in the log. */
	private static List<Settlement> describe(int worldId) {
		List<Settlement> settlements = BotPlaces.settlements(worldId);
		List<Settlement> grounds = BotPlaces.huntingGrounds(worldId);
		List<Settlement> posts = new ArrayList<>(civic(worldId));
		posts.addAll(field(worldId, false));
		log.info("Map {} is a {}: {} place(s) over {} m2 and {} hunting ground(s) call for {} inhabitant(s)", worldId, kindOf(settlements, grounds),
			settlements.size(), Math.round(settlements.stream().mapToDouble(Settlement::area).sum()), grounds.size(), posts.size());
		return List.copyOf(posts);
	}

	private static List<Settlement> plan(int worldId, boolean busy, boolean civic) {
		List<Settlement> settlements = BotPlaces.settlements(worldId);
		List<Settlement> grounds = BotPlaces.huntingGrounds(worldId);
		Kind kind = kindOf(settlements, grounds);
		float perResident = kind == Kind.FIELD ? AREA_PER_RESIDENT : AREA_PER_RESIDENT_IN_TOWN;

		List<Settlement> posts = new ArrayList<>();
		if (civic) {
			for (Settlement place : settlements) {
				// its own ground, not a share of the map's. Working out a map total and then dividing it compresses twice — the big place is crushed and
				// the small one gets a floor it has not earned, which is how a camp of 4 npcs came to hold 7 people and a capital plaza 9.
				int here = Math.max(1, Math.round(place.area() / perResident));
				for (int i = 0; i < here; i++)
					posts.add(place);
			}
			if (kind == Kind.SERVICE && posts.size() > SERVICE_CAP)
				posts = thinTo(posts, SERVICE_CAP);
			return List.copyOf(posts);
		}
		// The countryside, by its own ground and spread out so the hunters are not all in one valley. Going round the list in order rather than
		// weighting it: a hunting ground is a hunting ground, and what matters is that every part of the region has somebody working it.
		List<Settlement> field = BotPlaces.spreadOut(grounds);
		if (!field.isEmpty()) {
			double groundArea = grounds.stream().mapToDouble(Settlement::area).sum();
			float perHunter = busy ? AREA_PER_HUNTER_WHEN_BUSY : AREA_PER_HUNTER;
			int hunters = Math.min(busy ? FIELD_CAP_WHEN_BUSY : FIELD_CAP, (int) Math.round(groundArea / perHunter));
			for (int i = 0; i < hunters; i++)
				posts.add(field.get(i % field.size()));
		}
		return List.copyOf(posts);
	}

	/**
	 * Keeps every so many entries rather than the first so many, so a thinned town still has somebody in each of its corners. Truncating would fill
	 * the first few places and leave the rest deserted, which reads worse than a thin population everywhere.
	 */
	private static List<Settlement> thinTo(List<Settlement> posts, int keep) {
		List<Settlement> thinned = new ArrayList<>(keep);
		for (int i = 0; i < keep; i++)
			thinned.add(posts.get((int) ((long) i * posts.size() / keep)));
		return thinned;
	}

	private enum Kind {
		/** Somewhere people hunt: the creatures that gather in packs outnumber the residents. */
		FIELD,
		/** Somewhere people live and pass through constantly. */
		TOWN,
		/** Somewhere people only pass through. */
		SERVICE
	}

	/**
	 * Works out what kind of region this is from its own spawns, rather than from a list of map ids to keep in step with the client.
	 * <p>
	 * A place where the civilians outnumber the creatures that gather in packs is a town — that separates Sanctum (250 against a handful) and Oriel
	 * (1624) from Poeta (45 against some hundreds) and Brusthonin (181 against thousands) on every map measured. Among towns, sheer size tells a
	 * service from a home.
	 */
	private static Kind kindOf(List<Settlement> settlements, List<Settlement> grounds) {
		int civilians = settlements.stream().mapToInt(Settlement::townsfolk).sum();
		int gathered = grounds.stream().mapToInt(Settlement::townsfolk).sum();
		if (civilians <= gathered)
			return Kind.FIELD;
		return civilians >= SERVICE_TOWNSFOLK ? Kind.SERVICE : Kind.TOWN;
	}
}
