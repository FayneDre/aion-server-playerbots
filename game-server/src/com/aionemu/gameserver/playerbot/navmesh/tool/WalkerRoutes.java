package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;
import com.aionemu.gameserver.playerbot.navmesh.WalkedGround;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks the patrol routes of the data on a map's mesh: each step of a route is a place the designers had a creature walk from the one before, so a mesh that does not join two
 * steps a few metres apart by a short walk cut something a body walks. No wall of a house is among them, which is what makes it a test (a scan of every pair of close points
 * is mostly walls).
 * <p>
 * The routes are {@code npc_walker/npc_walker.xml} and {@code custom_npc_walker.xml} ({@code route_id}, steps x y z); a map's are those its spawn files
 * ({@code spawns/<dir>/<map>_<name>.xml}) name by {@code walker_id}. A route's steps are about 4 m apart: two that no walk of three times the distance plus 8 m joins are a failure,
 * unless one of them has no ground near it (a flying creature's route, a swimmer's), which is counted apart.
 */
final class WalkerRoutes {

	private static final Pattern ROUTE = Pattern.compile("<walker_template route_id=\"([^\"]+)\"");
	private static final Pattern STEP = Pattern.compile("<routestep x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" z=\"([-0-9.]+)\"");
	private static final Pattern WALKER = Pattern.compile("walker_id=\"([^\"]+)\"");
	/** A step's height may be off the ground by this much and still be a step on the ground, in metres. */
	private static final float CLUSTER = 25f;
	private static final int SHOWN = 40;

	private WalkerRoutes() {
	}

	/** @return 0 when every step is joined, 1 when some are not, 2 when the map has no route to check. */
	static int run(int mapId) throws IOException {
		return run(mapId, false);
	}

	/**
	 * @param write Append to the map's patrol ground ({@link WalkedGround#patrolsFileOf}) a point every half metre along each step that is cut and has ground at both its ends, so that the next build joins it.
	 *          A step with no ground at an end is a flier's or a swimmer's and is left out: a floor under it would hang in the air.
	 */
	static int run(int mapId, boolean write) throws IOException {
		Path data = Path.of("data/static_data");
		Set<String> named = routesNamedBy(data.resolve("spawns"), mapId);
		if (named.isEmpty()) {
			System.out.println("Map " + mapId + ": no spawn names a patrol route");
			return 2;
		}
		Map<String, List<float[]>> routes = new HashMap<>();
		for (String file : List.of("npc_walker.xml", "custom_npc_walker.xml"))
			readRoutes(data.resolve("npc_walker").resolve(file), named, routes);
		Navmesh mesh = Navmesh.open(mapId);
		int steps = 0, noFooting = 0, failed = 0, failedRoutes = 0, written = 0;
		StringBuilder walkedPoints = new StringBuilder();
		Map<String, List<float[][]>> clusters = new TreeMap<>();
		for (Map.Entry<String, List<float[]>> route : routes.entrySet()) {
			List<float[]> points = route.getValue();
			boolean routeFailed = false;
			for (int i = 1; i < points.size(); i++) {
				float[] a = points.get(i - 1), b = points.get(i);
				float across = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
				if (across < 0.5f)
					continue;
				steps++;
				if (BotPathFinder.nearestGround(mesh, a[0], a[1], a[2]) == null || BotPathFinder.nearestGround(mesh, b[0], b[1], b[2]) == null) {
					noFooting++;
					continue;
				}
				if (BotPathFinder.isWithinWalk(mesh, a[0], a[1], a[2], b[0], b[1], b[2], across * 3 + 8))
					continue;
				failed++;
				routeFailed = true;
				if (write) { // kept once: the file is read as it is, and a step still cut after a build is asked again
					int pieces = Math.max(1, (int) (across / 0.5f));
					for (int piece = 0; piece <= pieces; piece++) {
						float t = (float) piece / pieces;
						walkedPoints.append(WalkedGround.line(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t));
						written++;
					}
				}
				clusters.computeIfAbsent(Math.round(a[0] / CLUSTER) + " " + Math.round(a[1] / CLUSTER), k -> new ArrayList<>()).add(new float[][] { a, b });
			}
			if (routeFailed)
				failedRoutes++;
		}
		System.out.printf(Locale.ROOT, "Map %d: %d patrol route(s), %d step(s): %d joined by a short walk, %d not (in %d route(s)), %d with no ground at a step%n", mapId, routes.size(), steps,
			steps - failed - noFooting, failed, failedRoutes, noFooting);
		if (write && written > 0) {
			Files.writeString(WalkedGround.patrolsFileOf(mapId), walkedPoints.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			System.out.println("Wrote " + written + " point(s) for the cut steps to " + WalkedGround.patrolsFileOf(mapId));
		}
		List<Map.Entry<String, List<float[][]>>> order = new ArrayList<>(clusters.entrySet());
		order.sort(Comparator.comparingInt((Map.Entry<String, List<float[][]>> e) -> e.getValue().size()).reversed());
		for (Map.Entry<String, List<float[][]>> entry : order.subList(0, Math.min(SHOWN, order.size()))) {
			float[][] example = entry.getValue().get(0);
			float across = (float) Math.hypot(example[1][0] - example[0][0], example[1][1] - example[0][1]), rise = Math.abs(example[1][2] - example[0][2]);
			System.out.printf(Locale.ROOT, "  %d step(s) round %s, %.0f degrees, e.g. NavmeshTool %d path %.1f %.1f %.1f %.1f %.1f %.1f%n", entry.getValue().size(), entry.getKey().replace(' ', ','),
				Math.toDegrees(Math.atan2(rise, across)), mapId, example[0][0], example[0][1], example[0][2], example[1][0], example[1][1], example[1][2]);
		}
		return failed == 0 ? 0 : 1;
	}

	/** @return the walker ids the spawn files of this map name. */
	private static Set<String> routesNamedBy(Path spawns, int mapId) throws IOException {
		Set<String> named = new HashSet<>();
		try (var directories = Files.list(spawns)) {
			for (Path directory : directories.filter(Files::isDirectory).toList()) {
				try (var files = Files.list(directory)) {
					for (Path file : files.filter(f -> f.getFileName().toString().startsWith(mapId + "_")).toList()) {
						for (String line : Files.readAllLines(file)) {
							Matcher m = WALKER.matcher(line);
							while (m.find())
								named.add(m.group(1));
						}
					}
				}
			}
		}
		return named;
	}

	private static void readRoutes(Path file, Set<String> wanted, Map<String, List<float[]>> into) throws IOException {
		if (!Files.exists(file))
			return;
		List<float[]> current = null;
		for (String line : Files.readAllLines(file)) {
			Matcher route = ROUTE.matcher(line);
			if (route.find()) {
				current = wanted.contains(route.group(1)) ? new ArrayList<>() : null;
				if (current != null)
					into.put(route.group(1), current);
				continue;
			}
			if (current == null)
				continue;
			Matcher step = STEP.matcher(line);
			if (step.find())
				current.add(new float[] { Float.parseFloat(step.group(1)), Float.parseFloat(step.group(2)), Float.parseFloat(step.group(3)) });
		}
	}
}
