package com.aionemu.gameserver.playerbot.world;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Finds ground a bot can walk away from when the place it was put down on is a trap.
 * <p>
 * The engine puts a bot where it wants it: at an obelisk when it comes back from the dead, at a saved position when it enters the world. Nothing
 * there asks whether the body can leave. Measured over five days of logs, the obelisks of the fortresses stand on floors the mesh does not join to
 * the rest of the map, or inside solid geometry, and nearly every bot resurrected there could not take a step: a rescue teleported it home ten
 * seconds later, which was the bot seen sunk in a wall and then vanishing. Everything here is asked of the mesh and of the goal the bot has, so it
 * holds on every map with a mesh and needs no list of known bad spots.
 */
public final class BotLanding {

	/** How far from the spot to look, in metres. Past a fortress's own footprint, short of moving the bot to another part of the map. */
	private static final float[] RADII = { 0, 3, 6, 10, 15, 22, 30, 40, 55, 75 };
	private static final int DIRECTIONS = 16;
	/** How near a floor must be to count as the same ground the body was inside of, when the grid says the ground is joined. */
	private static final float SAME_GROUND_RADIUS = 6;
	/** How far from a floor a body may be and still be standing on it. The engine rounds heights, and a revived body is placed a little above it. */
	private static final float FOOTING_TOLERANCE = 2.5f;
	/** How many candidates to confirm with a route search, which is the expensive question. The cheap coarse test comes first. */
	private static final int CONFIRMATIONS = 12;

	private BotLanding() {
	}

	/**
	 * @param seed Which way to start looking round each ring, so that bots put down together do not all pick the same side.
	 * @return Ground near {@code from} that has a walking route to {@code goal}, when the bot is not already on such ground. Null when the bot is
	 *         fine where it is, when the map has no mesh, or when nothing better was found within reach.
	 *         <p>
	 *         Two ways of being trapped, told apart by the coarse grid and so without a route search, which on a long and winding goal takes seconds:
	 *         <b>inside geometry</b> (no floor under the body: the spot is in a plinth or a wall), where the nearest floor of the same ground is the
	 *         answer; and <b>on a pocket</b> (a floor the goal's ground does not include), where the way out is on another level, found by trying the
	 *         floors of each column and confirming the first with a real route.
	 */
	public static Vector3f toWalkableGround(int worldId, Vector3f from, Vector3f goal, int seed) {
		NavmeshService mesh = NavmeshService.getInstance();
		if (mesh.regionsAt(worldId, goal.getX(), goal.getY()).length == 0)
			return null; // no mesh, or a goal with no footing of its own: nothing to measure against
		boolean onFloor = isOnFloor(mesh, worldId, from);
		boolean joined = sharesGround(mesh, worldId, from.getX(), from.getY(), goal);
		if (onFloor && joined)
			return null;
		int confirmations = 0;
		for (float radius : RADII) {
			for (int k = 0; k < (radius == 0 ? 1 : DIRECTIONS); k++) {
				double angle = Math.PI * 2 * (k + Math.floorMod(seed, DIRECTIONS)) / DIRECTIONS;
				float x = from.getX() + (float) Math.cos(angle) * radius, y = from.getY() + (float) Math.sin(angle) * radius;
				if (!sharesGround(mesh, worldId, x, y, goal))
					continue;
				// every floor there, not the nearest: the way out of a pocket is on another level, and the nearest floor is the pocket's own
				for (Vector3f ground : mesh.floorsAt(worldId, x, y, from.getZ())) {
					if (joined && radius <= SAME_GROUND_RADIUS)
						return ground; // only the body was inside something: the ground beside it is the same ground, as the coarse grid already says
					if (confirmations++ >= CONFIRMATIONS)
						return null;
					if (mesh.reach(worldId, ground.getX(), ground.getY(), ground.getZ(), goal.getX(), goal.getY(), goal.getZ()) == NavmeshService.Reach.YES)
						return ground;
				}
			}
		}
		return null;
	}

	/** @return true when a floor a body fits on is within a body's width of the spot, false when it is inside geometry or over nothing. */
	private static boolean isOnFloor(NavmeshService mesh, int worldId, Vector3f spot) {
		Vector3f ground = mesh.groundNear(worldId, spot.getX(), spot.getY(), spot.getZ());
		return ground != null && PositionUtil.getDistance(spot.getX(), spot.getY(), spot.getZ(), ground.getX(), ground.getY(), ground.getZ()) <= FOOTING_TOLERANCE;
	}

	/** The cheap, necessary half of the question: do a spot and the goal lie over a common stretch of ground on the coarse grid. */
	private static boolean sharesGround(NavmeshService mesh, int worldId, float x, float y, Vector3f b) {
		int[] regionsOfB = mesh.regionsAt(worldId, b.getX(), b.getY());
		for (int region : mesh.regionsAt(worldId, x, y)) {
			for (int other : regionsOfB) {
				if (region == other)
					return true;
			}
		}
		return false;
	}
}
