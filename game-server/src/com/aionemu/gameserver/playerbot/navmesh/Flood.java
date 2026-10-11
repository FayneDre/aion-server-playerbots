package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a party could reach from an arrival if a body may also fall: the ground flooded from the arrival, climbing as the path finder climbs and dropping up to a given height.
 * <p>
 * A measurement, not a route: it asks whether one-way drops are what separates an arrival from the rest of a dungeon, before anything is built to use them. The path finder itself
 * only walks between neighbours that are as high as a slope allows, in both directions.
 */
final class Flood {

	private static final int[] STEP_X = { -1, 0, 1, -1, 1, -1, 0, 1 }, STEP_Y = { -1, -1, -1, 0, 0, 1, 1, 1 };
	/** How close in height, in metres, a flooded surface must be to a spawn spot to count as the ground under it. */
	private static final float UNDER = 1.5f;

	private Flood() {
	}

	/**
	 * @param drop How far down, in metres, a body may fall from one cell to another within {@code reach} cells.
	 */
	static void run(int mapId, float drop, int reach) throws IOException {
		Navmesh mesh = Navmesh.open(mapId);
		List<float[]> spots = InstanceReach.spawnSpots(mapId);
		for (float[] given : InstanceReach.entries(mapId)) {
			float[] entry = InstanceReach.snapped(mesh, given);
			if (entry == null)
				continue;
			Set<Long> seen = new HashSet<>();
			ArrayDeque<long[]> pending = new ArrayDeque<>();
			int centreX = mesh.cellX(entry[0]), centreY = mesh.cellY(entry[1]);
			for (int startY = centreY - 6; startY <= centreY + 6; startY++) {
				for (int startX = centreX - 6; startX <= centreX + 6; startX++) {
					if (!mesh.contains(startX, startY))
						continue;
					// the snapped height was found somewhere in a ring of cells around the arrival, so every cell of the ring that holds that very surface starts the flood
					for (int surface = 0; surface < mesh.surfaceCount(startX, startY); surface++) {
						if (mesh.isWalkable(startX, startY, surface) && Math.abs(mesh.surfaceZ(startX, startY, surface) - entry[2]) < 0.01f && seen.add(key(mesh, startX, startY, surface)))
							pending.add(new long[] { startX, startY, surface });
					}
				}
			}
			while (!pending.isEmpty()) {
				long[] at = pending.poll();
				int x = (int) at[0], y = (int) at[1];
				float z = mesh.surfaceZ(x, y, (int) at[2]);
				for (int direction = 0; direction < STEP_X.length; direction++) {
					boolean diagonal = STEP_X[direction] != 0 && STEP_Y[direction] != 0;
					int nextX = x + STEP_X[direction], nextY = y + STEP_Y[direction];
					visit(mesh, seen, pending, nextX, nextY, z, BotPathFinder.maxClimb(mesh, diagonal), 0);
					if (drop > 0 && isLedge(mesh, nextX, nextY, z, BotPathFinder.maxClimb(mesh, diagonal))) {
						// off a ledge a body lands on the highest ground it finds below it, out to `reach` cells: a fall, or a glide when the reach is long
						for (int step = 1; step <= reach; step++) {
							int landX = x + STEP_X[direction] * step, landY = y + STEP_Y[direction] * step;
							if (!mesh.contains(landX, landY))
								break;
							int best = -1;
							for (int surface = 0; surface < mesh.surfaceCount(landX, landY); surface++) {
								float below = z - mesh.surfaceZ(landX, landY, surface);
								if (mesh.isWalkable(landX, landY, surface) && below >= 1f && below <= drop && (best < 0 || mesh.surfaceZ(landX, landY, surface) > mesh.surfaceZ(landX, landY, best)))
									best = surface;
							}
							if (best >= 0 && seen.add(key(mesh, landX, landY, best)))
								pending.add(new long[] { landX, landY, best });
						}
					}
				}
			}
			int hit = 0, asked = 0;
			for (float[] spot : spots) {
				int cellX = mesh.cellX(spot[0]), cellY = mesh.cellY(spot[1]);
				boolean walkable = false, flooded = false;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (!mesh.contains(cellX + dx, cellY + dy))
							continue;
						for (int surface = 0; surface < mesh.surfaceCount(cellX + dx, cellY + dy); surface++) {
							if (!mesh.isWalkable(cellX + dx, cellY + dy, surface) || Math.abs(mesh.surfaceZ(cellX + dx, cellY + dy, surface) - spot[2]) > UNDER)
								continue;
							walkable = true;
							flooded |= seen.contains(key(mesh, cellX + dx, cellY + dy, surface));
						}
					}
				}
				if (walkable) {
					asked++;
					if (flooded)
						hit++;
				}
			}
			System.out.printf(Locale.ROOT, "Map %d from %.0f %.0f %.0f, drop %.1f m within %d cells: %d surfaces flooded, %d of %d spots (%.0f%%)%n", mapId, entry[0], entry[1], entry[2], drop,
				reach, seen.size(), hit, asked, asked == 0 ? 0f : hit * 100f / asked);
		}
	}

	private static void visit(Navmesh mesh, Set<Long> seen, ArrayDeque<long[]> pending, int x, int y, float z, float climb, float drop) {
		if (!mesh.contains(x, y))
			return;
		for (int surface = 0; surface < mesh.surfaceCount(x, y); surface++) {
			if (!mesh.isWalkable(x, y, surface))
				continue;
			float difference = mesh.surfaceZ(x, y, surface) - z;
			boolean allowed = drop == 0 ? Math.abs(difference) <= climb : difference <= climb && difference >= -drop;
			if (allowed && seen.add(key(mesh, x, y, surface)))
				pending.add(new long[] { x, y, surface });
		}
	}

	/** @return true if the column has no walkable ground within a step of the height, so a body leaving the flooded ground that way would fall. */
	private static boolean isLedge(Navmesh mesh, int x, int y, float z, float climb) {
		if (!mesh.contains(x, y))
			return false;
		for (int surface = 0; surface < mesh.surfaceCount(x, y); surface++) {
			if (mesh.isWalkable(x, y, surface) && Math.abs(mesh.surfaceZ(x, y, surface) - z) <= climb)
				return false;
		}
		return true;
	}

	private static long key(Navmesh mesh, int x, int y, int surface) {
		return ((long) y * mesh.width() + x) * 16 + surface;
	}
}
