package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.TribeClass;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
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
	private static final float WANDERING_RANGE = 250f;
	/** How many spots of a place to try before settling for one that merely has footing. Each try costs a path search, and a place has many spots. */
	private static final int ANCHOR_ATTEMPTS = 10;
	/** How far around a settlement to read the countryside for the level it is worth. */
	private static final float COUNTRYSIDE_RADIUS = 150f;
	/** How far a character may be from a region's own level and still belong in it. */
	private static final int LEVEL_TOLERANCE = 4;

	private static final Map<Integer, List<Settlement>> settlementsByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> levelByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> huntingByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> homesByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Vector3f>> obelisksByMap = new ConcurrentHashMap<>();

	/**
	 * A place people gather, how many of them do, and what the country around it is worth fighting at.
	 *
	 * @param townsfolk What makes a village draw more residents than a roadside camp.
	 * @param level The middling level of the creatures within {@value #COUNTRYSIDE_RADIUS} metres, which is what says who belongs here. Poeta reads
	 *          as Akarios 3, then camps at 5, 6 and 7 — a valley that steepens as you walk away from the village.
	 */
	public record Settlement(Vector3f centre, int townsfolk, int level) {
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
	private static List<Settlement> spreadOut(List<Settlement> places) {
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
	 * What a whole map is worth fighting at, which is the level its inhabitants should be.
	 * <p>
	 * The band belongs to the <b>region</b>, not to each camp within it: Poeta reads as Akarios 3 and its camps at 5, 6 and 7, but a character of
	 * eight walks all of it, and one of thirty has no business anywhere in it. So this decides who lives on a map, and nothing decides where they go
	 * once they do.
	 *
	 * @return The middling level of everything on the map that would fight back, or 1 where nothing does.
	 */
	public static int levelOf(int worldId) {
		return levelByMap.computeIfAbsent(worldId, id -> {
			List<Float> levels = new ArrayList<>();
			for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(id)) {
				NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
				if (template == null || isTownsfolk(template) || template.getLevel() <= 0)
					continue;
				for (int i = 0; i < group.getSpawnTemplates().size(); i++)
					levels.add((float) template.getLevel());
			}
			if (levels.isEmpty())
				return 1;
			levels.sort(null);
			return Math.round(levels.get(levels.size() / 2));
		});
	}

	/** @return true if a character of this level belongs on this map at all. */
	public static boolean suitsLevel(int worldId, int level) {
		return Math.abs(levelOf(worldId) - level) <= LEVEL_TOLERANCE;
	}

	/** @return true if this spot is one of the places people gather, rather than a stretch of country somebody hunts. */
	public static boolean isSettlement(int worldId, Vector3f place) {
		for (Settlement settlement : settlements(worldId)) {
			if (settlement.centre().equals(place))
				return true;
		}
		return false;
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
	public static Vector3f placeToVisit(int worldId, Vector3f from, int level, int pick) {
		List<Vector3f> within = new ArrayList<>();
		for (Settlement place : homes(worldId)) {
			if (place.centre().equals(from) || within.contains(place.centre()))
				continue;
			if (Math.abs(place.level() - level) > LEVEL_TOLERANCE)
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
		List<List<Vector3f>> clusters = new ArrayList<>();
		List<float[]> countryside = new ArrayList<>(); // x, y and level of everything that would fight back
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null)
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates()) {
				if (isTownsfolk(template))
					addToCluster(clusters, new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
				else if (template.getLevel() > 0)
					countryside.add(new float[] { spawn.getX(), spawn.getY(), template.getLevel() });
			}
		}
		List<Settlement> settlements = new ArrayList<>();
		clusters.sort(Comparator.comparingInt(List<Vector3f>::size).reversed());
		for (List<Vector3f> cluster : clusters) {
			if (cluster.size() >= SETTLEMENT_SIZE) {
				Vector3f centre = anchorOf(worldId, cluster);
				settlements.add(new Settlement(centre, cluster.size(), levelAround(countryside, centre)));
			}
		}
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
			grounds.add(new Settlement(centre, cluster.size(), levelAround(creatures, centre, GATHERING_RADIUS)));
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
		TribeClass tribe = template.getTribe();
		return tribe == TribeClass.GENERAL || tribe == TribeClass.GENERAL_DARK;
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
