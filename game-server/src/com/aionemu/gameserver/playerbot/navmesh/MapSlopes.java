package com.aionemu.gameserver.playerbot.navmesh;

import java.util.Set;

/**
 * The steepest slope, in degrees, a body is taken to walk up on each map: the engine's own 45 everywhere, and steeper on the few maps whose stairs are modelled as ramps.
 * <p>
 * The arrival room of the Miren chamber and of its two legion barracks is cut off from the rest by a flight of stairs whose collision is a single sloped face at about 61 degrees: no
 * step is high enough to stop a body, but the face is too steep for the rule, and the whole dungeon beyond it reads as unreachable. Raising the limit for every dungeon was measured
 * and is not the answer, since it opens cliff faces elsewhere and makes the searches longer, so it is raised where it is known to be needed.
 */
final class MapSlopes {

	static final int WALKABLE_DEGREES = 45;
	private static final int STAIRS_DEGREES = 62;
	/** Miren Chamber and the two copies of Legion's Miren Barracks. */
	private static final Set<Integer> STAIRS_AS_RAMPS = Set.of(300130000, 301250000, 301290000);

	private MapSlopes() {
	}

	/** @return The steepest walkable slope of the map in degrees; the property {@code navmesh.slope} overrides it everywhere, for measuring. */
	static int degrees(int mapId) {
		Integer measured = Integer.getInteger("navmesh.slope");
		if (measured != null)
			return measured;
		return STAIRS_AS_RAMPS.contains(mapId) ? STAIRS_DEGREES : WALKABLE_DEGREES;
	}

	static float cosine(int mapId) {
		return (float) Math.cos(Math.toRadians(degrees(mapId)));
	}

	static float tangent(int mapId) {
		return (float) Math.tan(Math.toRadians(degrees(mapId)));
	}
}
