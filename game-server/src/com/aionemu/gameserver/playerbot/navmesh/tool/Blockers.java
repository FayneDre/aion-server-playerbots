package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.GeoDataReader;
import com.aionemu.gameserver.playerbot.navmesh.GeoModel;
import com.aionemu.gameserver.playerbot.navmesh.GeoPlacement;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which placed models have a triangle over a given spot of the plan, and at what height.
 * <p>
 * The generator says a column is blocked and the maps say a doorway is shut; what neither says is which model is in it. A report such as {@code gaps} finds the place two grounds meet
 * across a single column, and this names what is standing in that column, so that the answer to "a door? a frame? an invisible wall?" is a file name and not a guess.
 */
final class Blockers {

	/** How far, in metres, around the spot a triangle may be and still be listed: a cell is half a metre, so a cell and the next. */
	private static final float RADIUS = 0.6f;

	private Blockers() {
	}

	static void run(int mapId, float x, float y, float z, float height) throws IOException {
		Map<String, List<GeoModel>> models = GeoDataReader.readModels();
		int found = 0;
		for (GeoPlacement placement : GeoDataReader.readPlacements(mapId)) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null)
				continue;
			int partNumber = 0;
			for (GeoModel part : parts) {
				float[] v = part.vertices();
				int[] ix = part.indices();
				for (int t = 0; t < ix.length; t += 3) {
					Vector3f a = world(placement, v, ix[t]), b = world(placement, v, ix[t + 1]), c = world(placement, v, ix[t + 2]);
					float minZ = Math.min(a.z, Math.min(b.z, c.z)), maxZ = Math.max(a.z, Math.max(b.z, c.z));
					if (maxZ < z - 1 || minZ > z + height)
						continue;
					if (!near(a, b, c, x, y))
						continue;
					float nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y), ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z),
						nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
					float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					boolean wall = length != 0 && Math.abs(nz / length) < 0.7f, overhead = minZ > z + 1;
					if (!wall && !overhead)
						continue; // a floor is what the spot stands on, not what stands in its way
					System.out.printf(Locale.ROOT, "type %d intent %d part %d  %s  triangle z %.1f..%.1f, face up %.2f%n", placement.type(), part.collisionIntentions() & 0xFF,
						partNumber, placement.modelName(), minZ, maxZ, length == 0 ? 0f : nz / length);
					found++;
					break; // one line per part is enough to name it
				}
				partNumber++;
			}
		}
		System.out.printf(Locale.ROOT, "%d part(s) of placed models over %.1f %.1f between z %.1f and %.1f%n", found, x, y, z - 1, z + height);
	}

	/** The heights above the ground a body is tested at along a route: knee, waist and chest. */
	private static final float[] BODY_HEIGHTS = { 0.5f, 1.0f, 1.5f };
	/** Placements further than this from every point of the route are not looked at: no model of a map is wider. */
	private static final float PLACEMENT_REACH = 200f;

	/**
	 * Says, for each straight stretch of a route, whether it passes through a wall of a placed model at a body's height, and which model.
	 * <p>
	 * The mesh decides where a route may go; this asks the models themselves, the way the client does when it refuses to walk a body through them. A
	 * route the mesh calls open that crosses a wall here is the mesh being wrong about that wall, which is what bots seen walking into the walls of the
	 * Verteron citadel and of Teminon came down to.
	 */
	static void crossings(int mapId, List<Vector3f> route) throws IOException {
		Map<String, List<GeoModel>> models = GeoDataReader.readModels();
		// per stretch and height, the first model met: a wall is met at every height, a step or a sill only at the knee
		String[][] hits = new String[route.size() - 1][BODY_HEIGHTS.length];
		for (GeoPlacement placement : GeoDataReader.readPlacements(mapId)) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null || route.stream().noneMatch(p -> Math.hypot(p.x - placement.position().x, p.y - placement.position().y) < PLACEMENT_REACH))
				continue;
			for (GeoModel part : parts) {
				if (!isSolid(part))
					continue;
				float[] v = part.vertices();
				int[] ix = part.indices();
				for (int t = 0; t < ix.length; t += 3) {
					Vector3f a = world(placement, v, ix[t]), b = world(placement, v, ix[t + 1]), c = world(placement, v, ix[t + 2]);
					Vector3f edge1 = b.subtract(a), edge2 = c.subtract(a), normal = edge1.cross(edge2);
					float length = normal.length();
					if (length == 0 || Math.abs(normal.z / length) >= 0.7f)
						continue; // a floor or a roof: what a body stands on or under, not what it walks into
					for (int segment = 0; segment < hits.length; segment++) {
						Vector3f from = route.get(segment), to = route.get(segment + 1);
						for (int h = 0; h < BODY_HEIGHTS.length; h++) {
							if (hits[segment][h] != null)
								continue;
							float height = BODY_HEIGHTS[h];
							float hitAt = intersect(new Vector3f(from.x, from.y, from.z + height), new Vector3f(to.x, to.y, to.z + height), a, edge1, edge2);
							if (!Float.isNaN(hitAt))
								hits[segment][h] = String.format(Locale.ROOT, "%.1f m up: %s at %.1f %.1f", height, placement.modelName().replaceAll(".*/", ""),
									from.x + (to.x - from.x) * hitAt, from.y + (to.y - from.y) * hitAt);
						}
					}
				}
			}
		}
		for (int segment = 0; segment < hits.length; segment++) {
			Vector3f from = route.get(segment), to = route.get(segment + 1);
			List<String> met = java.util.Arrays.stream(hits[segment]).filter(java.util.Objects::nonNull).toList();
			System.out.printf(Locale.ROOT, "  %.1f %.1f %.1f -> %.1f %.1f %.1f: %s%n", from.x, from.y, from.z, to.x, to.y, to.z, met.isEmpty() ? "clear" : String.join("; ", met));
		}
	}

	/**
	 * @return How many of the routes have a stretch that passes through a wall of a placed model at every height a body stands: a wall, not a step or a
	 *         sill, which are met at the knee only. The measure the audit gives of how often the mesh lets a route through something solid.
	 */
	static int walledRoutes(int mapId, List<List<Vector3f>> routes) throws IOException {
		List<Vector3f[]> stretches = new java.util.ArrayList<>();
		List<Integer> owners = new java.util.ArrayList<>();
		for (int r = 0; r < routes.size(); r++) {
			List<Vector3f> route = routes.get(r);
			for (int i = 0; i + 1 < route.size(); i++) {
				stretches.add(new Vector3f[] { route.get(i), route.get(i + 1) });
				owners.add(r);
			}
		}
		boolean[][] met = new boolean[stretches.size()][BODY_HEIGHTS.length];
		String[] wallMet = new String[stretches.size()];
		Map<String, List<GeoModel>> models = GeoDataReader.readModels();
		for (GeoPlacement placement : GeoDataReader.readPlacements(mapId)) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null)
				continue;
			List<Integer> near = new java.util.ArrayList<>();
			for (int s = 0; s < stretches.size(); s++) {
				Vector3f from = stretches.get(s)[0];
				if (Math.hypot(from.x - placement.position().x, from.y - placement.position().y) < PLACEMENT_REACH)
					near.add(s);
			}
			if (near.isEmpty())
				continue;
			for (GeoModel part : parts) {
				if (!isSolid(part))
					continue;
				float[] v = part.vertices();
				int[] ix = part.indices();
				for (int t = 0; t < ix.length; t += 3) {
					Vector3f a = world(placement, v, ix[t]), b = world(placement, v, ix[t + 1]), c = world(placement, v, ix[t + 2]);
					Vector3f edge1 = b.subtract(a), edge2 = c.subtract(a), normal = edge1.cross(edge2);
					float length = normal.length();
					if (length == 0 || Math.abs(normal.z / length) >= 0.7f)
						continue;
					float minX = Math.min(a.x, Math.min(b.x, c.x)), maxX = Math.max(a.x, Math.max(b.x, c.x));
					float minY = Math.min(a.y, Math.min(b.y, c.y)), maxY = Math.max(a.y, Math.max(b.y, c.y));
					for (int s : near) {
						Vector3f from = stretches.get(s)[0], to = stretches.get(s)[1];
						if (Math.max(from.x, to.x) < minX || Math.min(from.x, to.x) > maxX || Math.max(from.y, to.y) < minY || Math.min(from.y, to.y) > maxY)
							continue;
						for (int h = 0; h < BODY_HEIGHTS.length; h++) {
							float hitAt;
							if (!met[s][h] && !Float.isNaN(hitAt = intersect(new Vector3f(from.x, from.y, from.z + BODY_HEIGHTS[h]),
								new Vector3f(to.x, to.y, to.z + BODY_HEIGHTS[h]), a, edge1, edge2))) {
								met[s][h] = true;
								if (h == BODY_HEIGHTS.length - 1)
									wallMet[s] = String.format(Locale.ROOT, "%s at %.1f %.1f %.1f", placement.modelName().replaceAll(".*/", ""),
										from.x + (to.x - from.x) * hitAt, from.y + (to.y - from.y) * hitAt, from.z + (to.z - from.z) * hitAt);
							}
						}
					}
				}
			}
		}
		java.util.Set<Integer> walled = new java.util.HashSet<>();
		for (int s = 0; s < stretches.size(); s++) {
			boolean everyHeight = true;
			for (boolean hit : met[s])
				everyHeight &= hit;
			if (everyHeight && walled.add(owners.get(s)) && walled.size() <= 12) {
				Vector3f from = stretches.get(s)[0], to = stretches.get(s)[1];
				System.out.printf(Locale.ROOT, "    %.1f %.1f %.1f -> %.1f %.1f %.1f (%.1f m) through %s%n", from.x, from.y, from.z, to.x, to.y, to.z,
					from.distance(to), wallMet[s]);
			}
		}
		return walled.size();
	}

	/**
	 * @return true for the parts a body collides with, by the generator's own rule: physical collision, and not an authored no-walk volume. Counted without
	 *         it, a route through foliage or a decoration read as a route through a wall.
	 */
	private static boolean isSolid(GeoModel part) {
		int intentions = part.collisionIntentions() & 0xFF;
		return (intentions & CollisionIntention.WALK.getId()) == 0 && (intentions & CollisionIntention.DEFAULT_COLLISIONS.getId() & 0xFF) != 0;
	}

	/** @return Where along the segment it passes through the triangle, from 0 to 1, or NaN if it does not (Moller and Trumbore). */
	private static float intersect(Vector3f from, Vector3f to, Vector3f a, Vector3f edge1, Vector3f edge2) {
		Vector3f direction = to.subtract(from);
		Vector3f p = direction.cross(edge2);
		float determinant = edge1.dot(p);
		if (Math.abs(determinant) < 1e-7f)
			return Float.NaN;
		float inverse = 1f / determinant;
		Vector3f s = from.subtract(a);
		float u = s.dot(p) * inverse;
		if (u < 0 || u > 1)
			return Float.NaN;
		Vector3f q = s.cross(edge1);
		float w = direction.dot(q) * inverse;
		if (w < 0 || u + w > 1)
			return Float.NaN;
		float along = edge2.dot(q) * inverse;
		return along >= 0 && along <= 1 ? along : Float.NaN;
	}

	private static Vector3f world(GeoPlacement placement, float[] vertices, int index) {
		return placement.toWorld(vertices[index * 3], vertices[index * 3 + 1], vertices[index * 3 + 2]);
	}

	/** @return true if the point is within {@link #RADIUS} of the triangle seen from above: inside it, or within that margin of one of its edges. */
	private static boolean near(Vector3f a, Vector3f b, Vector3f c, float x, float y) {
		float d1 = side(x, y, a, b), d2 = side(x, y, b, c), d3 = side(x, y, c, a);
		boolean negative = d1 < 0 || d2 < 0 || d3 < 0, positive = d1 > 0 || d2 > 0 || d3 > 0;
		if (!(negative && positive))
			return true; // inside, whichever way the triangle winds
		return distance(x, y, a, b) <= RADIUS || distance(x, y, b, c) <= RADIUS || distance(x, y, c, a) <= RADIUS;
	}

	private static float side(float x, float y, Vector3f from, Vector3f to) {
		return (to.x - from.x) * (y - from.y) - (to.y - from.y) * (x - from.x);
	}

	private static float distance(float x, float y, Vector3f from, Vector3f to) {
		float dx = to.x - from.x, dy = to.y - from.y;
		float length2 = dx * dx + dy * dy;
		float t = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - from.x) * dx + (y - from.y) * dy) / length2));
		return (float) Math.hypot(x - (from.x + t * dx), y - (from.y + t * dy));
	}
}
