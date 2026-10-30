package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.HeightfieldBuilder;

import java.io.IOException;
import java.util.Locale;

/**
 * Which step of the generator took the walkable ground round a spot: the surfaces sampled, then head room, the authored no-walk volumes, the walls of solid geometry and the
 * erosion by a body's width. After each step it counts the walkable surfaces within a few metres of the spot and says whether the surface of the spot itself is still walkable.
 * <p>
 * {@code seams} says that ground is cut and {@code blockers} lists the models over one spot; neither says which rule of the generator did it, and the fix is different for each
 * (a slope limit, a no-walk volume that is meant, a ramp too narrow for a body once it is eroded).
 */
final class WhyBlocked {

	/** How far from the spot, in cells, ground is counted. */
	private static final int WINDOW = 12;

	private WhyBlocked() {
	}

	static void run(int mapId, float x, float y, float z) throws IOException {
		int cellX = (int) (x / Heightfield.CELL_SIZE), cellY = (int) (y / Heightfield.CELL_SIZE);
		System.out.printf(Locale.ROOT, "Map %d at %.1f %.1f z %.1f, ground within %.0f m of it after each step of the generator%n", mapId, x, y, z, WINDOW * Heightfield.CELL_SIZE);
		HeightfieldBuilder.build(mapId, (stage, field) -> {
			int surfaces = 0, walkable = 0;
			for (int dy = -WINDOW; dy <= WINDOW; dy++) {
				for (int dx = -WINDOW; dx <= WINDOW; dx++) {
					int nx = cellX + dx, ny = cellY + dy;
					if (nx < 0 || ny < 0 || nx >= field.width() || ny >= field.height())
						continue;
					for (int i = field.columnStart(nx, ny); i < field.columnEnd(nx, ny); i++) {
						if (Math.abs(field.surfaceAt(i) - z) > 3f)
							continue;
						surfaces++;
						if (field.isWalkable(i))
							walkable++;
					}
				}
			}
			String spot = "no surface";
			float nearest = Float.MAX_VALUE;
			for (int i = field.columnStart(cellX, cellY); i < field.columnEnd(cellX, cellY); i++) {
				float gap = Math.abs(field.surfaceAt(i) - z);
				if (gap <= 3f && gap < nearest) {
					nearest = gap;
					spot = String.format(Locale.ROOT, "z %.2f %s", field.surfaceAt(i), field.isWalkable(i) ? "walkable" : "BLOCKED");
				}
			}
			System.out.printf(Locale.ROOT, "  %-18s %5d surfaces within 3 m of the height, %5d walkable; the spot: %s%n", stage, surfaces, walkable, spot);
		});
	}
}
