package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks the routes a tester has walked on foot, and fails when the mesh no longer joins them or joins them by a detour. One route a line, in {@code tools/golden-routes.txt}:
 *
 * <pre>
 * map x1 y1 z1 x2 y2 z2 longestWalkInMetres   # what it is
 * </pre>
 *
 * Each is asked in both directions: a fix that opens a passage one way and shuts it the other is the commonest way to break one (Morheim's cave, 31 October: the way in was opened, and
 * the way out was shut by the same change, found by the next note).
 */
final class GoldenRoutes {

	private GoldenRoutes() {
	}

	/**
	 * @param only The maps to check, all of the file's when empty.
	 * @return 0 when every route holds, 1 when one does not, 2 when the file has none to check.
	 */
	static int run(Path file, Set<Integer> only) throws IOException {
		Map<Integer, Navmesh> meshes = new HashMap<>();
		Set<Integer> unreadable = new HashSet<>();
		int checked = 0, failed = 0;
		for (String line : Files.readAllLines(file)) {
			String text = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
			String[] f = text.trim().split(" +");
			if (f.length != 8)
				continue;
			int mapId = Integer.parseInt(f[0]);
			if (!only.isEmpty() && !only.contains(mapId))
				continue;
			float[] p = new float[7];
			for (int i = 0; i < 7; i++)
				p[i] = Float.parseFloat(f[1 + i]);
			if (unreadable.contains(mapId))
				continue;
			Navmesh mesh = meshes.get(mapId);
			if (mesh == null) {
				try {
					mesh = Navmesh.open(mapId);
					meshes.put(mapId, mesh);
				} catch (IOException e) {
					System.out.println("Map " + mapId + " has no readable mesh, its routes are skipped: " + e.getMessage());
					unreadable.add(mapId);
					continue;
				}
			}
			for (int way = 0; way < 2; way++) {
				float[] from = way == 0 ? new float[] { p[0], p[1], p[2] } : new float[] { p[3], p[4], p[5] };
				float[] to = way == 0 ? new float[] { p[3], p[4], p[5] } : new float[] { p[0], p[1], p[2] };
				BotPathFinder.Route route = BotPathFinder.findPathNear(mesh, from[0], from[1], from[2], to[0], to[1], to[2]);
				float length = lengthOf(route.waypoints());
				boolean holds = !route.isEmpty() && length <= p[6];
				checked++;
				if (!holds)
					failed++;
				System.out.printf(Locale.ROOT, "%s map %d %.0f %.0f %.0f -> %.0f %.0f %.0f: %s (at most %.0f m)  %s%n", holds ? "ok  " : "FAIL", mapId, from[0], from[1], from[2],
					to[0], to[1], to[2], route.isEmpty() ? (route.gaveUp() ? "search gave up" : "no way") : String.format(Locale.ROOT, "%.0f m", length), p[6],
					line.contains("#") ? line.substring(line.indexOf('#') + 1).trim() : "");
			}
		}
		if (checked == 0) {
			System.out.println("No route to check in " + file + ": one a line, map x1 y1 z1 x2 y2 z2 longestWalkInMetres # what it is");
			return 2;
		}
		System.out.printf(Locale.ROOT, "%d walk(s) checked, %d failed%n", checked, failed);
		return failed == 0 ? 0 : 1;
	}

	private static float lengthOf(List<Vector3f> waypoints) {
		float length = 0;
		for (int i = 1; i < waypoints.size(); i++)
			length += (float) Math.sqrt(waypoints.get(i - 1).distanceSquared(waypoints.get(i)));
		return length;
	}
}
