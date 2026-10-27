package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans offline the journeys a running server gave up, to tell a search that ran out of time from a way that does not exist. One journey a line:
 *
 * <pre>
 * map startX startY startZ goalX goalY goalZ cause
 * </pre>
 *
 * where the cause is the word the log gave (long, noway, again, cutoff, or any other: each is counted apart). {@code tools/measure.sh} writes such a
 * file from the day plans given up in a window of the log. The search budgets are the game's unless raised by their system properties
 * ({@code -Dnavmesh.planBudget} and the others {@link BotPathFinder} reads), which is how "found at ten times the budget" was measured on 25 October.
 * <p>
 * Written into the toolchain on 27 October 2026, after living two days in a scratch folder as {@code Plans.java}: the replay that showed 46 % of the
 * day plans given up were found offline was one a later session could not rerun.
 */
final class JourneyReplay {

	private JourneyReplay() {
	}

	/**
	 * @param every Replays one line in so many of each cause, for a large file.
	 * @return 0, or 1 when the file holds no journey.
	 */
	static int run(Path file, int every) throws IOException {
		Map<Integer, Navmesh> meshes = new HashMap<>();
		Map<String, long[]> outcomes = new TreeMap<>();
		Map<String, Integer> seenByCause = new HashMap<>();
		List<String> lines = Files.readAllLines(file);
		for (String line : lines) {
			String[] f = line.trim().split(" +");
			if (f.length < 8)
				continue;
			String cause = f[7];
			int seen = seenByCause.merge(cause, 1, Integer::sum);
			if ((seen - 1) % every != 0)
				continue;
			int mapId = Integer.parseInt(f[0]);
			float[] p = new float[6];
			for (int i = 0; i < 6; i++)
				p[i] = Float.parseFloat(f[1 + i]);
			Navmesh mesh = meshes.computeIfAbsent(mapId, JourneyReplay::open);
			if (mesh == null)
				continue;
			long started = System.nanoTime();
			BotPathFinder.Route route = BotPathFinder.findPath(mesh, p[0], p[1], p[2], p[3], p[4], p[5]);
			long ms = (System.nanoTime() - started) / 1_000_000;
			String outcome = !route.isEmpty() ? (route.glides().isEmpty() && route.rides().isEmpty() && route.flights().isEmpty() ? "found on foot" : "found with links")
				: route.gaveUp() ? "gave up" : route.goalCutOff() ? "goal cut off" : "no way";
			long[] counted = outcomes.computeIfAbsent(cause + " -> " + outcome, k -> new long[2]);
			counted[0]++;
			counted[1] += ms;
			System.out.printf(Locale.ROOT, "%d %.0f %.0f %.0f -> %.0f %.0f %.0f (%.0f m) %s -> %s in %d ms%n", mapId, p[0], p[1], p[2], p[3], p[4], p[5],
				Math.hypot(p[3] - p[0], p[4] - p[1]), cause, outcome, ms);
		}
		if (outcomes.isEmpty()) {
			System.out.println("No journey in " + file + ": one a line, map startX startY startZ goalX goalY goalZ cause");
			return 1;
		}
		System.out.println("---");
		outcomes.forEach((k, v) -> System.out.printf(Locale.ROOT, "%4d  %s (avg %d ms)%n", v[0], k, v[1] / v[0]));
		return 0;
	}

	private static Navmesh open(int mapId) {
		try {
			return Navmesh.open(mapId);
		} catch (IOException e) {
			System.out.println("Map " + mapId + " has no readable mesh, its journeys are skipped: " + e.getMessage());
			return null;
		}
	}
}
