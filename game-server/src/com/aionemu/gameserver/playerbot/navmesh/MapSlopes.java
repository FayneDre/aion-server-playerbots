package com.aionemu.gameserver.playerbot.navmesh;

import java.util.Set;

/**
 * The steepest slope, in degrees, a body is taken to walk up on each map: the engine's own 45 everywhere, and steeper on the few maps whose stairs are modelled as ramps.
 * <p>
 * The arrival room of the Miren chamber and of its two legion barracks is cut off from the rest by a flight of stairs whose collision is a single sloped face at about 61 degrees: no
 * step is high enough to stop a body, but the face is too steep for the rule, and the whole dungeon beyond it reads as unreachable. Raising the limit for every dungeon was measured
 * and is not the answer, since it opens cliff faces elsewhere and makes the searches longer, so it is raised where it is known to be needed.
 */
public final class MapSlopes {

	static final int WALKABLE_DEGREES = 45;
	private static final int STAIRS_DEGREES = 62;
	/** Miren Chamber and the two copies of Legion's Miren Barracks. */
	private static final Set<Integer> STAIRS_AS_RAMPS = Set.of(300130000, 301250000, 301290000);
	/**
	 * Maps whose buildings alone have their stairs modelled as ramps. Reshanta: Teminon's flights of stairs are single faces of about 57 degrees, so the
	 * landing deck, the floors above it and the ground below were stretches of their own, and 70 of 102 refused routes in half an hour were inside
	 * the base. Its islands' rock is left at 45, so a cliff does not become a path.
	 */
	private static final Set<Integer> BUILDING_STAIRS_AS_RAMPS = Set.of(400010000);

	private MapSlopes() {
	}

	/** @return The steepest walkable slope of the map in degrees; the property {@code navmesh.slope} overrides it everywhere, for measuring. */
	static int degrees(int mapId) {
		Integer measured = Integer.getInteger("navmesh.slope");
		if (measured != null)
			return measured;
		return STAIRS_AS_RAMPS.contains(mapId) ? STAIRS_DEGREES : WALKABLE_DEGREES;
	}

	/** @return The steepest walkable face of a building on the map in degrees: its stairs. The map's own everywhere but where buildings model them as ramps. */
	static int buildingDegrees(int mapId) {
		return BUILDING_STAIRS_AS_RAMPS.contains(mapId) && Integer.getInteger("navmesh.slope") == null ? STAIRS_DEGREES : degrees(mapId);
	}

	static float cosine(int mapId) {
		return (float) Math.cos(Math.toRadians(degrees(mapId)));
	}

	static float buildingCosine(int mapId) {
		return (float) Math.cos(Math.toRadians(buildingDegrees(mapId)));
	}

	/** @return How steep a body climbs between two cells, as the rise over the run: the steeper of the ground's and the buildings' limits. */
	public static float tangent(int mapId) {
		return (float) Math.tan(Math.toRadians(Math.max(degrees(mapId), buildingDegrees(mapId))));
	}
}
