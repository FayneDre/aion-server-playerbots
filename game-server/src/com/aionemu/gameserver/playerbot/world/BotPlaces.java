package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Where people gather on a map.
 * <p>
 * A bot that only farms is no more alive than a spawn point. Giving it somewhere to go needs somewhere worth going, and the world already says where
 * that is: a settlement is where the peaceful npcs stand. Poeta holds 43 of them against 986 hostile ones, and clustering them picks out Akarios
 * village and the three camps without a single new line of data being authored.
 */
public class BotPlaces {

	/** How close two townsfolk must be to count as standing in the same place. */
	private static final float GATHERING_RADIUS = 50f;
	/** Smallest reach a place is credited with, so three npcs standing together still describe somewhere rather than a point. */
	private static final float MIN_REACH = 12f;
	/** How many of them make a settlement rather than a lone npc keeping a road. */
	private static final int SETTLEMENT_SIZE = 3;
	/** How many creatures must stand together for the spot to be a place somebody works rather than two beetles by the roadside. */
	private static final int HUNTING_GROUND_SIZE = 8;
	/**
	 * How many townsfolk a village needs before it counts for one more inhabitant than a hunting ground does. Deliberately coarse: once the same
	 * population is spread over fewer places, a village counted once per three townsfolk takes a fifth of everybody and becomes the crowd again.
	 */
	private static final int TOWNSFOLK_PER_SHARE = 8;
	/** How far a resident will go for a change of scene. Past that it is not an outing, it is moving house — and it crosses everything in between. */
	/**
	 * How far above a place's own floor counts as standing on top of something rather than in it. A step is less; a plinth is more.
	 */
	public static final float STANDS_ON_A_STRUCTURE = 1.5f;

	/**
	 * @return Somewhere to stand near a place, or null when nothing around it will do.
	 *         <p>
	 *         Two rules, and both were learned from the same screenshot four times over. <b>Never the centre</b>: it is one point that every
	 *         inhabitant of the place shares, and in Akarios and at Melponeh's camp it is the obelisk, so falling back to it does not place a
	 *         villager anywhere — it stacks the village on a statue. <b>Never the top of something</b>: a plinth is walkable ground a metre above the
	 *         square, so a spot near the middle of a village climbs onto whatever stands there.
	 *         <p>
	 *         It lives here because the same two rules were needed in four places — settling a new resident, choosing somewhere to stand about,
	 *         putting a strayed bot back, and the spots a region hands out — and were written into one at a time, each fix leaving the other three
	 *         doing it wrong. A rule with four copies has four chances to be the one nobody updated.
	 * @param seed Fixes the choice to a bot, so it keeps the same corner instead of shuffling every time it is asked. Random when negative.
	 * @param refused The caller's own objection: somewhere crowded, somewhere it cannot walk to. Null when it has none.
	 */
	public static Vector3f spotAround(int worldId, Vector3f place, float spread, int attempts, int seed, Predicate<Vector3f> refused) {
		NavmeshService navmesh = NavmeshService.getInstance();
		for (int attempt = 0; attempt < attempts; attempt++) {
			double angle = seed < 0 ? Math.random() * Math.PI * 2
				: Math.PI * 2 * Math.floorMod(Integer.hashCode(seed * 0x9E3779B9) + attempt * 37, 360) / 360;
			// never the first third: the obelisk, the well and the campfire are what stands in the middle of a place
			float fraction = seed < 0 ? (float) Math.random() : Math.floorMod(seed + attempt, 10) / 10f;
			float reach = spread * (0.35f + 0.65f * fraction);
			float x = place.getX() + (float) Math.cos(angle) * reach, y = place.getY() + (float) Math.sin(angle) * reach;
			Vector3f ground = navmesh.groundNear(worldId, x, y, place.getZ());
			if (ground == null || ground.getZ() - place.getZ() > STANDS_ON_A_STRUCTURE)
				continue;
			if (refused != null && refused.test(ground))
				continue;
			// joined to the place itself: a ledge or the far side of a wall is perfectly good ground that leads nowhere, and a resident put on one
			// spends its life asking for a route out
			if (navmesh.canReach(worldId, ground.getX(), ground.getY(), ground.getZ(), place.getX(), place.getY(), place.getZ()))
				return ground;
		}
		return null;
	}

	public static final float WANDERING_RANGE = 250f;
	/** How many spots of a place to try before settling for one that merely has footing. Each try costs a path search, and a place has many spots. */
	private static final int ANCHOR_ATTEMPTS = 10;
	/** How far around a settlement to read the countryside for the level it is worth. */
	private static final float COUNTRYSIDE_RADIUS = 150f;
	/** How far a character may be from a region's own level and still belong in it. */
	/**
	 * How far a single place's level may be from a character's before that place is no use to it. A local question — this ground, this character — and
	 * not the region's band, which is measured rather than assumed.
	 */
	private static final int LEVEL_TOLERANCE = 4;
	/**
	 * How far <b>above</b> a character a place may be, which is a different question and was being answered with the same number.
	 * <p>
	 * Four either way let a bot be sent to work ground four levels over its head, while {@code BotTargetSelector.MAX_LEVEL_GAP} refuses any fight
	 * more than three above it: the bot stood in a camp it was not allowed to attack anything in, and the camp attacked it. Downwards the question is
	 * only whether the fight is worth having, so four stays; upwards it is whether the bot survives, and two is what the measurement supports.
	 * <p>
	 * Measured across all thirteen outdoor regions as the share of a region's hunting ground a character may use: about 45% at the bottom of its
	 * band, 80% in the middle, 100% at the top. It costs no population — the plan is per ground and residents are homed by level already — only where
	 * each one may wander. At the Tursin Outpost on Verteron, 17 to 20 with all 443 of its monsters rated NORMAL, it is the difference between being
	 * sent there at level 13 and not.
	 */
	private static final int LEVEL_TOLERANCE_ABOVE = 2;
	/** The quarter mark of what lives in a region, which is where its own people start. Below it is the odd stray near the gate. */
	private static final int FLOOR_PERCENTILE = 25;
	/** The ninth tenth, which is the top of what a region offers. Above it are the world bosses and the leftovers. */
	private static final int CEILING_PERCENTILE = 90;
	/** No character leaves its starting valley before this, so no region beyond them holds anybody under it. */
	private static final int ASCENSION_LEVEL = 10;
	/**
	 * How far a bot's level may drift from its home's before it moves. Tighter than the map's tolerance on purpose: the map decides who lives in the
	 * region, this decides which part of it, and the whole point is that a character of seven does not keep house on ground worth three.
	 */
	private static final int HOME_LEVEL_TOLERANCE = 2;

	private static final Map<Integer, List<Settlement>> settlementsByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, int[]> bandByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> huntingByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> homesByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Vector3f>> obelisksByMap = new ConcurrentHashMap<>();

	/**
	 * A place people gather, how far it spreads, how many of them do, and what the country around it is worth fighting at.
	 *
	 * @param reach How far the place's own occupants stand from its middle — Akarios measures 42 m, a roadside camp a dozen. Measured rather than
	 *          assumed, because a number invented for it is wrong in both directions at once: a formula gave Akarios 26 m, which packed its
	 *          inhabitants into half the ground the village actually occupies, and would have scattered a small camp's over twice its own.
	 * @param townsfolk How many civilian npcs stand here.
	 * @param level The middling level of the creatures within {@value #COUNTRYSIDE_RADIUS} metres, which is what says who belongs here. Poeta reads
	 *          as Akarios 3, then camps at 5, 6 and 7 — a valley that steepens as you walk away from the village.
	 * @param race Whose place this is, read off the civilians who stand in it, or null where nothing says. On a faction's own ground every place
	 *          answers the same thing and this changes nothing; it exists for the contested regions, where a map has no single answer and the
	 *          question has to be asked of each fort rather than of the region. Teminon is Elyos and Primum is Asmodian, and no setting anywhere says
	 *          so — the world does.
	 */
	public record Settlement(Vector3f centre, float reach, int townsfolk, int level, Race race) {

		/** @return The ground this place covers, which is what decides how many people it holds without feeling packed. */
		public float area() {
			return (float) Math.PI * reach * reach;
		}
	}

	private BotPlaces() {
	}

	/** @return The settlements of a map, largest first, or an empty list where nobody lives. */
	public static List<Settlement> settlements(int worldId) {
		return settlementsByMap.computeIfAbsent(worldId, BotPlaces::locateSettlements);
	}

	/**
	 * Every place on a map worth living at: the villages, and the hunting grounds between them.
	 * <p>
	 * A map has four villages and forty places where things worth killing stand together, and a population that only knows the villages is a
	 * population in four heaps. Poeta showed it plainly: Akarios packed with bots shoulder to shoulder, and the farms, the lake, the plains and the
	 * quarry — the places the map was actually built around — with nobody in them at all.
	 * <p>
	 * Each place carries the level of what lives there, which is the other half of the same problem: a resident belongs to its place, so the place
	 * is what says what level it should be. Drawing a level first and a home afterwards is how a character of two came to stand in a forest of
	 * eights.
	 *
	 * @return Villages first, then hunting grounds, every one of them somewhere a character could plausibly spend its day.
	 */
	public static List<Settlement> homes(int worldId) {
		// Remembered, like the two lists it is built from. Assembling it means ordering the hunting grounds by distance from one another, which
		// compares every remaining place against every one already taken — some tens of thousands of distances for a map the size of Poeta. It is
		// asked for every time a bot decides where to go, and the answer never changes.
		return homesByMap.computeIfAbsent(worldId, BotPlaces::locateHomes);
	}

	private static List<Settlement> locateHomes(int worldId) {
		List<Settlement> villages = new ArrayList<>();
		// a village appears once per few townsfolk, so Akarios gets a handful of inhabitants and a roadside camp one, which is the proportion the
		// world itself was built in. One entry each would make the largest village as busy as the emptiest field.
		for (Settlement settlement : settlements(worldId)) {
			for (int share = 0; share < Math.max(1, settlement.townsfolk() / TOWNSFOLK_PER_SHARE); share++)
				villages.add(settlement);
		}
		return List.copyOf(interleave(villages, spreadOut(huntingGrounds(worldId)))); // shared and read by every bot, so nobody gets to change it
	}

	/**
	 * Reorders places so that each one is as far as possible from those already chosen.
	 * <p>
	 * There are always more places than people — Poeta has sixty and was given forty five — so whoever populates a map stops partway down the list,
	 * and what the list is ordered by decides what gets left out. Ordered by size, the tail was the smallest grounds, and they are small because they
	 * are the outlying ones: the lake and the farms had nobody at all while the same few busy fields had somebody each. Ordered by distance from what
	 * is already taken, stopping anywhere leaves a population spread over the whole map.
	 */
	static List<Settlement> spreadOut(List<Settlement> places) {
		List<Settlement> remaining = new ArrayList<>(places);
		List<Settlement> spread = new ArrayList<>();
		while (!remaining.isEmpty()) {
			Settlement farthest = remaining.get(0);
			double bestDistance = -1;
			for (Settlement candidate : remaining) {
				double nearestTaken = spread.stream()
					.mapToDouble(taken -> PositionUtil.getDistance(taken.centre().x, taken.centre().y, candidate.centre().x, candidate.centre().y)).min()
					.orElse(Double.MAX_VALUE);
				if (nearestTaken > bestDistance) {
					bestDistance = nearestTaken;
					farthest = candidate;
				}
			}
			remaining.remove(farthest);
			spread.add(farthest);
		}
		return spread;
	}

	/** Mixes two lists in proportion, so that stopping partway takes its share of each rather than all of the first. */
	private static List<Settlement> interleave(List<Settlement> first, List<Settlement> second) {
		List<Settlement> mixed = new ArrayList<>(first.size() + second.size());
		float step = first.isEmpty() ? Float.MAX_VALUE : (float) second.size() / first.size();
		int taken = 0;
		for (int i = 0; i < first.size(); i++) {
			mixed.add(first.get(i));
			for (int upTo = Math.round((i + 1) * step); taken < upTo && taken < second.size(); taken++)
				mixed.add(second.get(taken));
		}
		while (taken < second.size())
			mixed.add(second.get(taken++));
		return mixed;
	}

	/** @return The places where enough hostile creatures stand together to be worth working, largest first. */
	public static List<Settlement> huntingGrounds(int worldId) {
		return huntingByMap.computeIfAbsent(worldId, BotPlaces::locateHuntingGrounds);
	}

	/**
	 * Where a bot lives.
	 * <p>
	 * Loitering used to send a bot to the settlement nearest to wherever it happened to be, which means a village nobody spawned beside is a village
	 * nobody ever visits — Akarios stayed empty while three camps were crowded. A home fixed to the bot's own id spreads a population over the places
	 * a map actually has, and weighting by the number of townsfolk puts most of them where most of the world put its own people.
	 *
	 * @return The bot's home, or null on a map where nobody lives.
	 */
	public static Vector3f homeOf(int worldId, int objectId) {
		List<Settlement> places = settlements(worldId);
		if (places.isEmpty())
			return null;
		int total = places.stream().mapToInt(Settlement::townsfolk).sum();
		int pick = Math.floorMod(Integer.hashCode(objectId * 0x9E3779B9), total);
		for (Settlement settlement : places) {
			pick -= settlement.townsfolk();
			if (pick < 0)
				return settlement.centre();
		}
		return places.get(0).centre();
	}

	/**
	 * What levels a region is <b>for</b>: where its people start and where they top out.
	 * <p>
	 * The middle of a region does not describe it. Taking the median and allowing four either side put Verteron's inhabitants at 10 to 17 for a
	 * region the game runs from 10 to 20, and it is wrong in both directions at once — too narrow for a region that spans ten levels, too wide for
	 * one that spans four. The spread is not a fixed width, so it has to be measured rather than assumed.
	 * <p>
	 * Measured as the quarter and the ninth tenth of what lives there. Both ends need trimming and for different reasons: every region holds a
	 * handful of level 1 creatures somewhere near its gate, and most hold one or two of level 80 that are world bosses or leftovers, so the plain
	 * minimum and maximum describe nothing. Against the regions as a player knows them this comes out right across the board — Verteron 10 to 19,
	 * Eltnen 24 to 38, Heiron 35 to 44, Theobomos 45 to 49, Poeta 2 to 8.
	 * <p>
	 * Only what one character can fight counts, the same rule the hunting grounds use: a region's elites say what a group could do there, not what
	 * somebody living there is.
	 *
	 * @return The floor and the ceiling, in that order, never below 1 and never inverted.
	 */
	private static int[] bandOf(int worldId) {
		return bandByMap.computeIfAbsent(worldId, id -> {
			List<Integer> levels = new ArrayList<>();
			for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(id)) {
				NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
				if (template == null || isTownsfolk(template) || template.getLevel() <= 0 || BotTargetSelector.needsAGroup(template))
					continue;
				for (int i = 0; i < group.getSpawnTemplates().size(); i++)
					levels.add((int) template.getLevel());
			}
			if (levels.isEmpty())
				return new int[] { 1, 1 };
			levels.sort(null);
			int ceiling = Math.max(1, percentile(levels, CEILING_PERCENTILE));
			int floor = Math.max(1, percentile(levels, FLOOR_PERCENTILE));
			// A region above the ascension is reached by ascending, so nobody under ten lives there — the two starting valleys, whose ceiling is
			// itself under ten, are the only places that hold characters below it.
			if (ceiling >= ASCENSION_LEVEL)
				floor = Math.max(floor, ASCENSION_LEVEL);
			return new int[] { Math.min(floor, ceiling), ceiling };
		});
	}

	private static int percentile(List<Integer> sorted, int percent) {
		return sorted.get(Math.clamp((long) (sorted.size() - 1) * percent / 100, 0, sorted.size() - 1));
	}

	/** @return true if a character of this level belongs on this map at all. */
	public static boolean suitsLevel(int worldId, int level) {
		return level >= bottomLevelOf(worldId) && level <= topLevelOf(worldId);
	}

	/** @return The highest level this region still has anything to offer, and the level at which a resident of it stops earning. */
	public static int topLevelOf(int worldId) {
		return bandOf(worldId)[1];
	}

	/**
	 * @return The lowest level this region is any use to, which is where its inhabitants start rather than at one.
	 *         <p>
	 *         The counterpart of {@link #topLevelOf}, and it was missing while the ceiling was not — so a region's people were created from level one
	 *         however high the region was. A level 9 priest standing in Verteron is not a resident, it is somebody the countryside would kill on
	 *         their way out of the village.
	 */
	public static int bottomLevelOf(int worldId) {
		return bandOf(worldId)[0];
	}

	/**
	 * @return What the country around this place is worth fighting at, or the map's own band where the spot is not a place people gather.
	 *         <p>
	 *         Hunting grounds are read as well as settlements, and were not. That left every caller comparing a bot against the region's middle
	 *         rather than against the ground it stands on: {@code BotDay.moveOutIfOutgrown} has been testing a constant, and a bot whose home was a
	 *         level 19 camp in a region banded 10-19 was never found to have outgrown or undergrown anything. The Tursin Outpost runs 17 to 20 with
	 *         every one of its 443 monsters rated NORMAL, so nothing else in this module could see it as dangerous either.
	 */
	public static int levelAt(int worldId, Vector3f place) {
		for (Settlement settlement : settlements(worldId)) {
			if (isSamePlace(settlement.centre(), place))
				return settlement.level();
		}
		for (Settlement ground : huntingGrounds(worldId)) {
			if (isSamePlace(ground.centre(), place))
				return ground.level();
		}
		return (bottomLevelOf(worldId) + topLevelOf(worldId)) / 2; // not a place, so the middle of what the region is for
	}

	/**
	 * @return What the ground nearest a spot is worth fighting at, which is the question anybody walking somewhere has.
	 *         <p>
	 *         By reach rather than by centre: a ground is a disc, and a bot standing at its edge is standing in it. Where nothing claims the spot the
	 *         region's middle is the answer, as above — open country between camps is not more dangerous than the region it is in.
	 */
	public static int levelAround(int worldId, float x, float y) {
		Settlement nearest = null;
		double best = Double.MAX_VALUE;
		for (Settlement ground : huntingGrounds(worldId)) {
			double distance = PositionUtil.getDistance(x, y, ground.centre().getX(), ground.centre().getY());
			if (distance <= ground.reach() && distance < best) {
				best = distance;
				nearest = ground;
			}
		}
		return nearest == null ? (bottomLevelOf(worldId) + topLevelOf(worldId)) / 2 : nearest.level();
	}

	/**
	 * @return Somewhere on this map a character of that level would plausibly live, weighted the way the population is, or null if the map has none.
	 *         <p>
	 *         What it is for: a bot grows. Born at the level of Akarios and left there, it reaches seven while still anchored on ground worth two,
	 *         and spends its days beating creatures five levels beneath it in the middle of a beginners' village. Poeta reads as Akarios 3 and its
	 *         camps at 5, 6 and 7 — the valley steepens as you walk away from the village, so growing up means moving out.
	 */
	public static Vector3f homeForLevel(int worldId, int level, int pick) {
		List<Vector3f> suitable = new ArrayList<>();
		for (Settlement place : homes(worldId)) {
			if (Math.abs(place.level() - level) <= HOME_LEVEL_TOLERANCE)
				suitable.add(place.centre());
		}
		return suitable.isEmpty() ? null : suitable.get(Math.floorMod(pick, suitable.size()));
	}

	/**
	 * How far apart two coordinates may be and still mean the same place.
	 * <p>
	 * It exists because a home makes a round trip through the database and does not come back the same number. {@code home_z} is a {@code float}
	 * column and the value returned differs from the one written in its last bit — measured in the live log, a village centre held in memory as
	 * {@code 270.65002} read back out of the pool as {@code 270.65}, with x and y identical. {@link Vector3f#equals} compares with
	 * {@code Float.compare}, so the two were not the same place, and 98 of Eltnen's 102 residents were classified as living in open country while
	 * their homes sat exactly on a village. The director then moved each of them into that village on every review, wrote the same number, and read
	 * back the other one — twenty-one rehousings every half minute, for ever.
	 * <p>
	 * A metre, because no two places this is asked about are within a metre of each other and no round trip moves a coordinate by anything like that.
	 * The rule that follows: <b>a world coordinate that has been to the database is never compared for equality, only for nearness.</b>
	 */
	public static final float SAME_PLACE = 1f;

	/** @return true if this spot is one of the places people gather, rather than a stretch of country somebody hunts. */
	public static boolean isSettlement(int worldId, Vector3f place) {
		return settlementAt(worldId, place) != null;
	}

	/**
	 * @return The settlement centre this spot stands for, as the plan itself holds it, or null when the spot is not a settlement.
	 *         <p>
	 *         Callers that key a map by place must use what this returns rather than the coordinate they were given, or the two spellings of the same
	 *         village become two entries and every lookup made with the other one misses. See {@link #SAME_PLACE}.
	 */
	public static Vector3f settlementAt(int worldId, Vector3f place) {
		for (Settlement settlement : settlements(worldId)) {
			if (isSamePlace(settlement.centre(), place))
				return settlement.centre();
		}
		return null;
	}

	/**
	 * @return true when two coordinates mean the same place, which is the only way they may ever be compared once either of them has been to the
	 *         database. See {@link #SAME_PLACE}.
	 */
	public static boolean isSamePlace(Vector3f one, Vector3f other) {
		return one != null && other != null && Math.abs(one.getX() - other.getX()) <= SAME_PLACE
			&& Math.abs(one.getY() - other.getY()) <= SAME_PLACE && Math.abs(one.getZ() - other.getZ()) <= SAME_PLACE;
	}

	/**
	 * @return The obelisk nearest a spot, or null where the map has none.
	 *         <p>
	 *         Bots never bind anywhere, so dying sent them to their race's starting location — the far end of the region from wherever they live and
	 *         work. A player binds at the obelisk nearest the ground they are working precisely so that dying costs a short walk rather than a long
	 *         one, and a bot given the same has the same.
	 */
	public static Vector3f nearestObelisk(int worldId, float x, float y) {
		return obelisksByMap.computeIfAbsent(worldId, BotPlaces::locateObelisks).stream()
			.min(Comparator.comparingDouble(spot -> PositionUtil.getDistance(x, y, spot.x, spot.y))).orElse(null);
	}

	private static List<Vector3f> locateObelisks(int worldId) {
		List<Vector3f> found = new ArrayList<>();
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			if (DataManager.BIND_POINT_DATA.getBindPointTemplate(group.getNpcId()) == null)
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates())
				found.add(new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
		}
		return found;
	}

	/** @return The settlement nearest to a point, or null if the map has none. */
	public static Vector3f nearestSettlement(int worldId, float x, float y) {
		return settlements(worldId).stream().map(Settlement::centre)
			.min(Comparator.comparingDouble(spot -> PositionUtil.getDistance(x, y, spot.x, spot.y))).orElse(null);
	}

	/**
	 * @return A settlement other than the one given, or null when the map has only one. Used to pick somewhere to walk to, so a wanderer has a
	 *         destination rather than a direction.
	 */
	public static Vector3f otherSettlement(int worldId, Vector3f current, int pick) {
		List<Vector3f> others = new ArrayList<>(settlements(worldId).stream().map(Settlement::centre).toList());
		if (others.size() < 2)
			return others.isEmpty() ? null : others.get(0);
		others.remove(current);
		return others.get(Math.floorMod(pick, others.size()));
	}

	/**
	 * Picks somewhere for a resident to go and spend a while.
	 * <p>
	 * Within reach and within its depth, and it was leaving out both that showed. A wanderer sent to any settlement on the map walked the length of
	 * the region to get there: forty five residents averaged a hundred and forty metres from home and one was six hundred away, which is not a stroll
	 * but emigration. Worse, the walk crosses everything in between, so a character of two was to be found in a forest of eights — not because it was
	 * settled there, but because its errand led through it.
	 *
	 * @param level The visitor's level, which decides where it has any business being.
	 * @param pick Whatever makes this bot's choice its own.
	 * @return Somewhere to go, or null when nowhere nearby suits it — in which case it is better off staying where it is.
	 */
	/**
	 * @return A stretch of country near home and fit for this level, or null if there is none.
	 *         <p>
	 *         Villagers have to go out to hunt, and until this existed they did not: farming left the anchor on the village, {@code roam} refuses any
	 *         target further than {@value com.aionemu.gameserver.playerbot.combat.BotTargetSelector#HOME_RADIUS} m from the anchor, and there is
	 *         nothing hostile that close to a village. A resident of Akarios therefore spent its farming hours finding nothing and walking back to
	 *         the well. Its own hunting ground, drawn near where it lives, is the whole of the instruction — everything downstream already works off
	 *         the anchor.
	 */
	/** @return Whether a character of this level has any business on ground of that one. Generous below, strict above — see {@link #LEVEL_TOLERANCE_ABOVE}. */
	private static boolean isFitFor(int placeLevel, int characterLevel) {
		return placeLevel - characterLevel <= LEVEL_TOLERANCE_ABOVE && characterLevel - placeLevel <= LEVEL_TOLERANCE;
	}

	public static Vector3f groundToWork(int worldId, Vector3f from, int level, int pick) {
		List<Vector3f> within = new ArrayList<>();
		for (Settlement ground : huntingGrounds(worldId)) {
			if (!isFitFor(ground.level(), level))
				continue;
			if (PositionUtil.getDistance(from.x, from.y, ground.centre().x, ground.centre().y) > WANDERING_RANGE)
				continue;
			within.add(ground.centre());
		}
		return within.isEmpty() ? null : within.get(Math.floorMod(pick, within.size()));
	}

	public static Vector3f placeToVisit(int worldId, Vector3f from, int level, int pick) {
		List<Vector3f> within = new ArrayList<>();
		for (Settlement place : homes(worldId)) {
			if (isSamePlace(place.centre(), from) || within.contains(place.centre()))
				continue;
			if (!isFitFor(place.level(), level))
				continue;
			if (PositionUtil.getDistance(from.x, from.y, place.centre().x, place.centre().y) > WANDERING_RANGE)
				continue;
			within.add(place.centre());
		}
		return within.isEmpty() ? null : within.get(Math.floorMod(pick, within.size()));
	}

	/**
	 * Groups the peaceful spawns of a map into the places they stand in.
	 * <p>
	 * Greedy and good enough: townsfolk are few, and two clusters that should have been one merely give a wanderer two destinations a few paces
	 * apart. Lone npcs are dropped — a road keeper is not a village.
	 */
	private static List<Settlement> locateSettlements(int worldId) {
		// Clustered per faction rather than all together, which is what lets a place say whose it is. On a faction's own ground there is only ever one
		// of these lists and the result is what it always was; in Reshanta there are two, and Teminon does not absorb Primum because they are a few
		// hundred metres apart in the same data.
		Map<Race, List<List<Vector3f>>> clustersByRace = new EnumMap<>(Race.class);
		List<float[]> countryside = new ArrayList<>(); // x, y and level of everything that would fight back
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null)
				continue;
			Race race = factionOf(template);
			for (SpawnTemplate spawn : group.getSpawnTemplates()) {
				if (race != null)
					addToCluster(clustersByRace.computeIfAbsent(race, _ -> new ArrayList<>()), new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
				else if (template.getLevel() > 0)
					countryside.add(new float[] { spawn.getX(), spawn.getY(), template.getLevel() });
			}
		}
		List<Settlement> settlements = new ArrayList<>();
		for (Map.Entry<Race, List<List<Vector3f>>> entry : clustersByRace.entrySet()) {
			for (List<Vector3f> cluster : entry.getValue()) {
				if (cluster.size() >= SETTLEMENT_SIZE) {
					Vector3f centre = anchorOf(worldId, cluster);
					settlements.add(new Settlement(centre, reachOf(centre, cluster), cluster.size(), levelAround(countryside, centre), entry.getKey()));
				}
			}
		}
		// largest first, as before and across both factions at once. The order is not cosmetic: the plan is walked from the top, so it decides which
		// places are filled when a region is given fewer people than it asks for.
		settlements.sort(Comparator.comparingInt(Settlement::townsfolk).reversed());
		return settlements;
	}

	/**
	 * Groups the hostile spawns of a map into the grounds they are hunted on.
	 * <p>
	 * The same greedy clustering as the villages, on the other half of the spawn table. A ground is kept only once enough creatures stand together
	 * to keep somebody busy: two roadside beetles are scenery, not a place to spend an afternoon, and a population scattered over every pair of them
	 * would be a population standing alone in the grass.
	 */
	private static List<Settlement> locateHuntingGrounds(int worldId) {
		List<List<Vector3f>> clusters = new ArrayList<>();
		List<float[]> creatures = new ArrayList<>();
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null || isTownsfolk(template) || template.getLevel() <= 0)
				continue;
			// A ground is somewhere one character can work, so what needs a group is not part of one. Without this the group quest camps read as
			// ordinary hunting country, the director sent residents to live in them, and they were torn apart — by creatures that come looking, so
			// refusing to attack them was never going to be enough. Measured across the world: Morheim keeps 195 grounds of its 304 and Beluslan 196
			// of 273, which are the two worst affected, and no region is left without any.
			if (BotTargetSelector.needsAGroup(template))
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates()) {
				addToCluster(clusters, new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
				creatures.add(new float[] { spawn.getX(), spawn.getY(), template.getLevel() });
			}
		}
		List<Settlement> grounds = new ArrayList<>();
		clusters.sort(Comparator.comparingInt(List<Vector3f>::size).reversed());
		for (List<Vector3f> cluster : clusters) {
			if (cluster.size() < HUNTING_GROUND_SIZE)
				continue;
			Vector3f centre = anchorOf(worldId, cluster);
			// the level of what stands here, not of the country around it: a ground is a handful of creatures in one spot, and averaging over a
			// hundred and fifty metres of everything nearby is exactly how a place of eights came to read as a place of twos
			grounds.add(new Settlement(centre, reachOf(centre, cluster), cluster.size(), levelAround(creatures, centre, GATHERING_RADIUS),
				factionNearest(worldId, centre)));
		}
		return grounds;
	}

	/**
	 * @return true if this npc is a civilian — somebody who lives here rather than something that lives off whoever walks past.
	 *         <p>
	 *         Both tribes, which is the whole point of asking it in one place. The two factions are mirrored in the data and so are their tribe
	 *         names: Elyos civilians are {@code GENERAL}, Asmodian ones {@code GENERAL_DARK}. Testing only the first meant every Asmodian map read as
	 *         having no inhabitants at all — Pandaemonium's 266 civilians counted as zero, Altgard's 103 as zero — so no Asmodian bot could have had a
	 *         home, an anchor or anywhere to stand about, and the hunting grounds absorbed the villages instead.
	 */
	private static boolean isTownsfolk(NpcTemplate template) {
		return factionOf(template) != null;
	}

	/**
	 * @return Which faction a civilian belongs to, or null if it is not a civilian at all.
	 *         <p>
	 *         The same two tribes {@link #isTownsfolk} already turned on, read for the one more thing they say. {@code TribeRelationService} settles
	 *         what they mean: {@code GENERAL} is a friend of {@code PC} and {@code GENERAL_DARK} of {@code PC_DARK}, which is the engine's own
	 *         statement of whose people these are. Nothing had to be authored for it and nothing can drift from it.
	 */
	private static Race factionOf(NpcTemplate template) {
		return switch (template.getTribe()) {
			case GENERAL -> Race.ELYOS;
			case GENERAL_DARK -> Race.ASMODIANS;
			default -> null;
		};
	}

	/**
	 * @return Whose country a spot is in, taken from the settlement nearest it, or null where the map holds no settlement at all.
	 *         <p>
	 *         A hunting ground is a cluster of monsters, and a monster belongs to nobody — so the ground has to inherit its side from the people who
	 *         work it. Nearest rather than within a radius: in a contested region the two factions' grounds meet somewhere in the middle, and a
	 *         distance threshold would leave exactly that middle unclaimed and unpopulated, which is the one part of the map worth populating.
	 */
	private static Race factionNearest(int worldId, Vector3f spot) {
		Race race = null;
		double nearest = Double.MAX_VALUE;
		for (Settlement settlement : settlements(worldId)) {
			double distance = PositionUtil.getDistance(spot.x, spot.y, settlement.centre().x, settlement.centre().y);
			if (distance < nearest) {
				nearest = distance;
				race = settlement.race();
			}
		}
		return race;
	}

	/**
	 * @return How far the furthest of a place's occupants stands from its middle, never less than {@value #MIN_REACH} m so a huddle of three still
	 *         leaves room to stand apart.
	 */
	private static float reachOf(Vector3f centre, List<Vector3f> cluster) {
		float furthest = 0;
		for (Vector3f spot : cluster)
			furthest = Math.max(furthest, (float) PositionUtil.getDistance(centre.x, centre.y, spot.x, spot.y));
		return Math.max(MIN_REACH, furthest);
	}

	/** @return How far the place at this spot spreads, or {@value #MIN_REACH} if the spot is not a place people gather. */
	public static float reachAt(int worldId, Vector3f place) {
		for (Settlement settlement : settlements(worldId)) {
			if (isSamePlace(settlement.centre(), place))
				return settlement.reach();
		}
		return MIN_REACH;
	}

	private static void addToCluster(List<List<Vector3f>> clusters, Vector3f spot) {
		for (List<Vector3f> cluster : clusters) {
			Vector3f centre = centreOf(cluster);
			if (PositionUtil.getDistance(centre.x, centre.y, spot.x, spot.y) <= GATHERING_RADIUS) {
				cluster.add(spot);
				return;
			}
		}
		clusters.add(new ArrayList<>(List.of(spot)));
	}

	private static int levelAround(List<float[]> countryside, Vector3f centre) {
		return levelAround(countryside, centre, COUNTRYSIDE_RADIUS);
	}

	/** @return The middling level of what lives within that reach of a place, which is what a character of that level would find a fair fight. */
	private static int levelAround(List<float[]> countryside, Vector3f centre, float reach) {
		List<Float> levels = new ArrayList<>();
		for (float[] creature : countryside) {
			if (PositionUtil.getDistance(centre.x, centre.y, creature[0], creature[1]) <= reach)
				levels.add(creature[2]);
		}
		if (levels.isEmpty())
			return 1;
		levels.sort(null);
		return Math.round(levels.get(levels.size() / 2));
	}

	private static Vector3f centreOf(List<Vector3f> cluster) {
		float x = 0, y = 0, z = 0;
		for (Vector3f spot : cluster) {
			x += spot.x;
			y += spot.y;
			z += spot.z;
		}
		return new Vector3f(x / cluster.size(), y / cluster.size(), z / cluster.size());
	}

	/**
	 * Picks the spot a place is entered at: the one nearest its middle that a body can actually stand on.
	 * <p>
	 * Neither half of that is optional, and each was learned by getting it wrong. The plain average is not a place at all — creatures spread over a
	 * slope, a ledge and the ground below average out to a point hanging between them, and a villager put there landed on an isolated shelf with no
	 * route off it. But a spawn is not automatically standable either: an npc is posted behind a counter, on a dais, inside a hut the mesh gives no
	 * head room, and aiming a whole village at one of those made every journey to it fail.
	 * <p>
	 * So the members are tried from the middle outwards and the first one the mesh accepts wins. Falling back to the average keeps a map with no
	 * generated mesh working as it did before.
	 */
	private static Vector3f anchorOf(int worldId, List<Vector3f> cluster) {
		Vector3f middle = centreOf(cluster);
		List<Vector3f> fromTheMiddle = new ArrayList<>(cluster);
		fromTheMiddle.sort(Comparator.comparingDouble(spot -> PositionUtil.getDistance(middle.x, middle.y, spot.x, spot.y)));
		Vector3f standable = null;
		for (int tried = 0; tried < Math.min(fromTheMiddle.size(), ANCHOR_ATTEMPTS); tried++) {
			Vector3f ground = NavmeshService.getInstance().groundNear(worldId, fromTheMiddle.get(tried).x, fromTheMiddle.get(tried).y,
				fromTheMiddle.get(tried).z);
			if (ground == null)
				continue;
			if (standable == null)
				standable = ground;
			if (leadsSomewhere(worldId, ground, cluster))
				return ground;
		}
		return standable != null ? standable : middle;
	}

	/**
	 * @return true if this spot is joined to the rest of its own place, which is what tells a place from a pocket.
	 *         <p>
	 *         Standable was not enough. A spot can be good ground inside a hollow with walls all round it — one village centre had a hundred and
	 *         forty one of its hundred and sixty nine surrounding cells blocked — and a resident anchored there can reach nothing at all: it asked
	 *         for a route out fifty five times in three minutes and was refused every time. A place people live in is one they can leave.
	 */
	private static boolean leadsSomewhere(int worldId, Vector3f ground, List<Vector3f> cluster) {
		Vector3f farthest = cluster.stream()
			.max(Comparator.comparingDouble(spot -> PositionUtil.getDistance(ground.x, ground.y, spot.x, spot.y))).orElse(null);
		if (farthest == null || PositionUtil.getDistance(ground.x, ground.y, farthest.x, farthest.y) < Heightfield.CELL_SIZE)
			return true; // a place of one spot has nowhere of its own to walk to, and nothing to prove
		return NavmeshService.getInstance().canReach(worldId, ground.x, ground.y, ground.z, farthest.x, farthest.y, farthest.z);
	}
}
