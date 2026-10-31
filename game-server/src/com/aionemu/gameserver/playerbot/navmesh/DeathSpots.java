package com.aionemu.gameserver.playerbot.navmesh;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where bots died lately, as ground every route keeps out of where it can.
 * <p>
 * A bot revived at its obelisk walks home along the way it came, past whatever killed it, and dies in the same place again: on 31 October one bot died 17 times in half an hour,
 * five of them within 5 m of one spot a few paces from its obelisk. Nothing was remembered from one death to the next, and the avoidance of {@link Avoidance} was only ever asked for a fight.
 * <p>
 * A cost and not a wall, as every {@link Avoidance}: a bot with no other way still goes, so no ground is closed by this, only made a longer walk to take for a quarter of an hour. The
 * rough pass of a long journey does not know of it, only the fine stretches do.
 */
public final class DeathSpots {

	/** How far from a death a point costs extra, in metres. */
	static final float RADIUS = 25;
	/** What a point near a death costs in addition to its length, as a multiple of it: a walk eight times as long is worth taking round it. */
	static final float COST = 8;
	/** How long a death is remembered, in milliseconds. */
	static final long MEMORY_MILLIS = 15 * 60_000L;
	private static final int MOST_PER_MAP = 200;

	private record Spot(float x, float y, long until) {
	}

	private static final Map<Integer, List<Spot>> byMap = new ConcurrentHashMap<>();

	private DeathSpots() {
	}

	public static void record(int worldId, float x, float y) {
		record(worldId, x, y, System.currentTimeMillis());
	}

	static void record(int worldId, float x, float y, long now) {
		List<Spot> spots = byMap.computeIfAbsent(worldId, id -> new CopyOnWriteArrayList<>());
		spots.removeIf(spot -> spot.until <= now);
		if (spots.size() < MOST_PER_MAP)
			spots.add(new Spot(x, y, now + MEMORY_MILLIS));
	}

	/** @return How many deaths of the last quarter of an hour were within {@code radius} metres of the point on this map. */
	public static int countNear(int worldId, float x, float y, float radius) {
		return countNear(worldId, x, y, radius, System.currentTimeMillis());
	}

	static int countNear(int worldId, float x, float y, float radius, long now) {
		List<Spot> spots = byMap.get(worldId);
		if (spots == null)
			return 0;
		return (int) spots.stream().filter(spot -> spot.until > now && (spot.x - x) * (spot.x - x) + (spot.y - y) * (spot.y - y) <= radius * radius).count();
	}

	/** @return What the ground round the recent deaths of this map costs, {@link Avoidance#NONE} when there are none. */
	public static Avoidance around(int worldId) {
		return around(worldId, System.currentTimeMillis());
	}

	static Avoidance around(int worldId, long now) {
		List<Spot> spots = byMap.get(worldId);
		if (spots == null)
			return Avoidance.NONE;
		List<Spot> live = spots.stream().filter(spot -> spot.until > now).toList();
		if (live.isEmpty())
			return Avoidance.NONE;
		return (x, y) -> {
			for (Spot spot : live) {
				float dx = x - spot.x, dy = y - spot.y;
				if (dx * dx + dy * dy <= RADIUS * RADIUS)
					return COST;
			}
			return 0f;
		};
	}

	/** @return the two avoidances as one, their costs added; the other when one is {@link Avoidance#NONE}. */
	public static Avoidance combined(Avoidance first, Avoidance second) {
		if (first == Avoidance.NONE)
			return second;
		if (second == Avoidance.NONE)
			return first;
		return (x, y) -> first.extra(x, y) + second.extra(x, y);
	}
}
