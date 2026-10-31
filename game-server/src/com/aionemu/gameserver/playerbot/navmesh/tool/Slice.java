package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.HeightfieldBuilder;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * The ground round a spot drawn as text, after the steps of the generator that can take it away: one character per cell, north up.
 * <p>
 * {@code whyblocked} counts the surfaces round a spot and {@code seams} names the stretches; neither shows where the cells that are shut lie, which is what tells a wall that
 * runs across a way from a few cells shut by a prop. {@code .} is a cell with a walkable surface within a body's height of the one asked for, {@code #} one with a surface that
 * is not walkable, a space one with no surface there; {@code X} marks the spot itself.
 */
final class Slice {

	/** The steps drawn: the walls, which take the ground behind solid geometry, and the erosion, which shaves a body's width off every edge. */
	private static final Set<String> STAGES = Set.of("head room", "walls", "erosion");
	/** How far above or below the height asked for a surface is still the same floor, in metres. */
	private static final float SAME_FLOOR = 1.5f;

	private Slice() {
	}

	static void run(int mapId, float x, float y, float z, int radiusCells) throws IOException {
		int centreX = (int) (x / Heightfield.CELL_SIZE), centreY = (int) (y / Heightfield.CELL_SIZE);
		System.out.printf(Locale.ROOT, "Map %d round %.1f %.1f z %.1f, %d cells (%.1f m) each way, north up; . walkable  # not walkable  X the spot%n", mapId, x, y, z, radiusCells,
			radiusCells * Heightfield.CELL_SIZE);
		HeightfieldBuilder.build(mapId, (stage, field) -> {
			if (!STAGES.contains(stage) && !stage.startsWith("passages kept"))
				return;
			System.out.println("after " + stage + ":");
			for (int dy = radiusCells; dy >= -radiusCells; dy--) {
				StringBuilder row = new StringBuilder();
				for (int dx = -radiusCells; dx <= radiusCells; dx++) {
					int cx = centreX + dx, cy = centreY + dy;
					char mark = ' ';
					if (cx >= 0 && cy >= 0 && cx < field.width() && cy < field.height()) {
						for (int i = field.columnStart(cx, cy); i < field.columnEnd(cx, cy); i++) {
							if (Math.abs(field.surfaceAt(i) - z) > SAME_FLOOR)
								continue;
							if (field.isWalkable(i)) {
								mark = '.';
								break;
							}
							mark = '#';
						}
					}
					row.append(dx == 0 && dy == 0 ? 'X' : mark);
				}
				System.out.println(row);
			}
		});
	}
}
