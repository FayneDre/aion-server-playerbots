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
	/** How far around a settlement to read the countryside for the level it is worth. */
	private static final float COUNTRYSIDE_RADIUS = 150f;
	/** How far a character may be from a region's own level and still belong in it. */
	private static final int LEVEL_TOLERANCE = 4;

	private static final Map<Integer, List<Settlement>> settlementsByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> levelByMap = new ConcurrentHashMap<>();
	private static final Map<Integer, List<Settlement>> huntingByMap = new ConcurrentHashMap<>();

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
		List<Settlement> places = new ArrayList<>();
		// a village appears once per few townsfolk, a hunting ground once. Taken one after another by whoever populates a map, that gives Akarios a
		// handful of inhabitants and the quarry one, which is the proportion the world itself was built in. One entry each would make the largest
		// village as busy as the emptiest field.
		for (Settlement settlement : settlements(worldId)) {
			for (int share = 0; share < Math.max(1, settlement.townsfolk() / SETTLEMENT_SIZE); share++)
				places.add(settlement);
		}
		places.addAll(huntingGrounds(worldId));
		return places;
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
				if (template == null || template.getTribe() == TribeClass.GENERAL || template.getLevel() <= 0)
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
				if (template.getTribe() == TribeClass.GENERAL)
					addToCluster(clusters, new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
				else if (template.getLevel() > 0)
					countryside.add(new float[] { spawn.getX(), spawn.getY(), template.getLevel() });
			}
		}
		List<Settlement> settlements = new ArrayList<>();
		clusters.sort(Comparator.comparingInt(List<Vector3f>::size).reversed());
		for (List<Vector3f> cluster : clusters) {
			if (cluster.size() >= SETTLEMENT_SIZE) {
				Vector3f centre = centreOf(cluster);
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
			if (template == null || template.getTribe() == TribeClass.GENERAL || template.getLevel() <= 0)
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
			Vector3f centre = centreOf(cluster);
			// the level of what stands here, not of the country around it: a ground is a handful of creatures in one spot, and averaging over a
			// hundred and fifty metres of everything nearby is exactly how a place of eights came to read as a place of twos
			grounds.add(new Settlement(centre, cluster.size(), levelAround(creatures, centre, GATHERING_RADIUS)));
		}
		return grounds;
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
}
