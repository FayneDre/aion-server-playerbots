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

	private static final Map<Integer, List<Settlement>> settlementsByMap = new ConcurrentHashMap<>();

	/** A place people gather, and how many of them do: the count is what makes a village draw more residents than a roadside camp. */
	public record Settlement(Vector3f centre, int townsfolk) {
	}

	private BotPlaces() {
	}

	/** @return The settlements of a map, largest first, or an empty list where nobody lives. */
	public static List<Settlement> settlements(int worldId) {
		return settlementsByMap.computeIfAbsent(worldId, BotPlaces::locateSettlements);
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
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null || template.getTribe() != TribeClass.GENERAL)
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates())
				addToCluster(clusters, new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
		}
		List<Settlement> settlements = new ArrayList<>();
		clusters.sort(Comparator.comparingInt(List<Vector3f>::size).reversed());
		for (List<Vector3f> cluster : clusters) {
			if (cluster.size() >= SETTLEMENT_SIZE)
				settlements.add(new Settlement(centreOf(cluster), cluster.size()));
		}
		return settlements;
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
