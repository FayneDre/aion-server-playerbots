package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Finds, on the ground a body stands on, an edge it could glide off for the look of it, and the ground below that the glide lands on.
 * <p>
 * The links of {@link GlideLinks} join stretches of ground that no walk joins, so none of them starts from the ground a walking bot is on: measured on 1 November, none of 750 sampled
 * walkable journeys had a glide route. This looks at the cells round the body instead, ray by ray: the ground ends where the surface stops being within a step of the last, and past
 * that edge a floor at least {@code minDrop} metres lower, at a distance a glide covers (2.5 m forward for 1 m of drop, the same as {@code BotMoveController}), is the landing.
 * <p>
 * Geometry of the mesh only; whether the air between is clear and whether the landing is safe is for the caller to ask.
 */
public final class LedgeGlides {

	/** Metres forward for a metre of drop. */
	static final float GLIDE_RATIO = 2.5f;
	/** How far a surface may differ in height from the last on the ground and still be the same walk. */
	private static final float STEP = 0.6f;
	private static final int DIRECTIONS = 16;
	/** The share of a glide's full reach the landing must lie at: nearer would be a dive and further is a glide that cannot make it. */
	private static final float NEAREST_SHARE = 0.7f, FURTHEST_SHARE = 0.95f;

	/** Where the body leaves the ground and where it comes down. */
	public record Ledge(Vector3f takeoff, Vector3f landing) {
	}

	private LedgeGlides() {
	}

	/**
	 * @param radius How far from the body to look for an edge, in metres.
	 * @param minDrop The least height the glide comes down.
	 * @param maxDrop The most, so a bot does not leave a mountain.
	 * @return Every edge found, one at most per direction, in no order; empty when the body is nowhere near one.
	 */
	public static List<Ledge> around(Navmesh mesh, float x, float y, float z, float radius, float minDrop, float maxDrop, long seed) {
		List<Ledge> found = new ArrayList<>();
		double offset = new Random(seed).nextDouble() * 2 * Math.PI / DIRECTIONS;
		for (int direction = 0; direction < DIRECTIONS; direction++) {
			double angle = offset + direction * 2 * Math.PI / DIRECTIONS;
			float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
			float edgeX = x, edgeY = y, edgeZ = z;
			boolean left = false;
			for (float d = 1; d <= radius; d += 1) {
				float px = x + dx * d, py = y + dy * d;
				float groundZ = surfaceNear(mesh, px, py, edgeZ, STEP);
				if (Float.isNaN(groundZ)) {
					left = true;
					break;
				}
				edgeX = px;
				edgeY = py;
				edgeZ = groundZ;
			}
			if (!left)
				continue;
			Vector3f landing = landingBeyond(mesh, edgeX, edgeY, edgeZ, dx, dy, minDrop, maxDrop);
			if (landing != null)
				found.add(new Ledge(new Vector3f(edgeX, edgeY, edgeZ), landing));
		}
		return found;
	}

	/** @return The first floor along the ray past the edge that a glide reaches, or null. */
	private static Vector3f landingBeyond(Navmesh mesh, float edgeX, float edgeY, float edgeZ, float dx, float dy, float minDrop, float maxDrop) {
		float farthest = maxDrop * GLIDE_RATIO;
		for (float d = 2; d <= farthest; d += 1) {
			float px = edgeX + dx * d, py = edgeY + dy * d;
			float below = highestWalkableUnder(mesh, px, py, edgeZ - minDrop);
			if (Float.isNaN(below))
				continue;
			float drop = edgeZ - below;
			if (drop > maxDrop)
				continue;
			if (d >= NEAREST_SHARE * GLIDE_RATIO * drop && d <= FURTHEST_SHARE * GLIDE_RATIO * drop)
				return new Vector3f(px, py, below);
		}
		return null;
	}

	/** @return The height of the walkable surface of that column nearest to {@code z} and within {@code tolerance} of it, or NaN. */
	private static float surfaceNear(Navmesh mesh, float x, float y, float z, float tolerance) {
		int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
		if (!mesh.contains(cellX, cellY))
			return Float.NaN;
		float best = Float.NaN, bestGap = tolerance;
		for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
			float surfaceZ = mesh.surfaceZ(cellX, cellY, surface);
			float gap = Math.abs(surfaceZ - z);
			if (mesh.isWalkable(cellX, cellY, surface) && gap <= bestGap) {
				best = surfaceZ;
				bestGap = gap;
			}
		}
		return best;
	}

	/** @return The highest walkable surface of that column at or under {@code ceiling}, or NaN. */
	private static float highestWalkableUnder(Navmesh mesh, float x, float y, float ceiling) {
		int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
		if (!mesh.contains(cellX, cellY))
			return Float.NaN;
		float best = Float.NaN;
		for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
			float surfaceZ = mesh.surfaceZ(cellX, cellY, surface);
			if (mesh.isWalkable(cellX, cellY, surface) && surfaceZ <= ceiling && (Float.isNaN(best) || surfaceZ > best))
				best = surfaceZ;
		}
		return best;
	}
}
