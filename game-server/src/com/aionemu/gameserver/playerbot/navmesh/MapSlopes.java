package com.aionemu.gameserver.playerbot.navmesh;

import java.util.List;
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

	/**
	 * A box of a map where the ground is climbed at a steeper slope than the map's, and only there.
	 * <p>
	 * Raising the limit for a whole map was measured on Morheim on 31 October: at 62 degrees the way into the cave of the east opens (137 m for 66 m as the crow flies;
	 * at 55 it is a walk of 3 330 m), and four stretches of routes through a gate, a prop and two trees open elsewhere on the map. A zone gives the one and not the others.
	 */
	private record Zone(int mapId, float minX, float minY, float maxX, float maxY, int degrees) {
		boolean contains(int map, float x, float y) {
			return map == mapId && x >= minX && x <= maxX && y >= minY && y <= maxY;
		}
	}

	/** Morheim: the hill that the way into the cave of the east climbs, from the south of the entrance (759 307) over the top (733 333) down to the cave (790 375). */
	private static final List<Zone> ZONES = List.of(new Zone(220020000, 725, 295, 800, 385, STAIRS_DEGREES));

	private MapSlopes() {
	}

	/** Whether {@code navmesh.slope} is set: read once, when the class loads, so it is set with {@code -D} on the command line; the generator asks about a zone once for every terrain sample and every triangle of a map. */
	private static final Integer SLOPE_OVERRIDE = Integer.getInteger("navmesh.slope");
	private static final boolean SLOPE_OVERRIDDEN = SLOPE_OVERRIDE != null;

	/** @return The cosine of the steepest slope of the zone the spot lies in, 1 (no relaxation) outside every zone of the map; the property {@code navmesh.slope} switches the zones off. */
	static float zoneCosine(int mapId, float x, float y) {
		if (SLOPE_OVERRIDDEN)
			return 1f;
		for (Zone zone : ZONES) {
			if (zone.contains(mapId, x, y))
				return (float) Math.cos(Math.toRadians(zone.degrees()));
		}
		return 1f;
	}

	/** @return The cosine of the steepest slope of any zone of the map, 1 when it has none. */
	static float steepestZoneCosine(int mapId) {
		float steepest = 1f;
		if (!SLOPE_OVERRIDDEN) {
			for (Zone zone : ZONES) {
				if (zone.mapId() == mapId)
					steepest = Math.min(steepest, (float) Math.cos(Math.toRadians(zone.degrees())));
			}
		}
		return steepest;
	}

	/** @return The steepest walkable slope of the map in degrees; the property {@code navmesh.slope} overrides it everywhere, for measuring. */
	static int degrees(int mapId) {
		if (SLOPE_OVERRIDDEN)
			return SLOPE_OVERRIDE;
		return STAIRS_AS_RAMPS.contains(mapId) ? STAIRS_DEGREES : WALKABLE_DEGREES;
	}

	/** @return The steepest walkable face of a building on the map in degrees: its stairs. The map's own everywhere but where buildings model them as ramps. */
	static int buildingDegrees(int mapId) {
		return BUILDING_STAIRS_AS_RAMPS.contains(mapId) && !SLOPE_OVERRIDDEN ? STAIRS_DEGREES : degrees(mapId);
	}

	static float cosine(int mapId) {
		return (float) Math.cos(Math.toRadians(degrees(mapId)));
	}

	static float buildingCosine(int mapId) {
		return (float) Math.cos(Math.toRadians(buildingDegrees(mapId)));
	}

	/** @return How steep a body climbs between two cells, as the rise over the run: the steeper of the ground's and the buildings' limits. */
	public static float tangent(int mapId) {
		return (float) Math.tan(Math.acos(Math.min(steepestZoneCosine(mapId), (float) Math.cos(Math.toRadians(Math.max(degrees(mapId), buildingDegrees(mapId)))))));
	}
}
