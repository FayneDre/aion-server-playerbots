package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

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
