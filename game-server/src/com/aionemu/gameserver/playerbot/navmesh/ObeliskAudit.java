package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Checks every obelisk of every map that has a mesh: can a body that comes back from the dead there walk away?
 * <p>
 * Written because the answer was found in a server log and not in the data. Over five days 1171 bots were rescued from being unable to move, and every
 * place they clustered was a resurrection point: the obelisks of two fortresses stand on floors the mesh does not join to the rest of the map, or in
 * solid geometry. Nothing about a mesh says so until a bot dies there, so this says it before. Run it after generating a map, and before trusting a
 * new one: a map whose obelisks fail it is a map where bots will be teleported home.
 * <p>
 * Obelisks are read from the spawn files and the bind point list directly, the way the generator reads geometry, so nothing here starts the server.
 * An obelisk is judged on three things: a floor under it within a body's reach, being on the map's largest stretch of ground (told apart, since a map
 * of islands has several large ones and being off the largest is only a remark there), and a route to the nearest other obelisks of the same map. It
 * fails when it has no floor or when every route tried is proved not to exist; a search that ran out of budget proves nothing and does not fail it.
 */
public final class ObeliskAudit {

	/** How far above or below an obelisk's recorded height a floor may be and still be the one it stands on. */
	private static final float FLOOR_REACH = 2.5f;
	/** How many other obelisks of the map to try a route to. */
	private static final int ROUTES = 3;

	private static final Pattern BIND = Pattern.compile("<bind_point\\s+npcid=\"(\\d+)\"");
	private static final Pattern MAP = Pattern.compile("<spawn_map\\s+map_id=\"(\\d+)\"");
	private static final Pattern NPC = Pattern.compile("<spawn\\s+[^>]*npc_id=\"(\\d+)\"");
	private static final Pattern SPOT = Pattern.compile("<spot\\s+x=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");

	private ObeliskAudit() {
	}

	record Obelisk(int map, float x, float y, float z) {
	}

	/**
	 * @param onlyMap One map to check, or 0 for every map that has a mesh.
	 * @return 0 when every obelisk passed, 1 when any failed, so that a script can refuse a mesh.
	 */
	public static int run(int onlyMap) throws IOException {
		Path data = Path.of("data/static_data");
		Map<Integer, List<Obelisk>> byMap = readObelisks(data);
		int failed = 0, checked = 0;
		for (Map.Entry<Integer, List<Obelisk>> entry : byMap.entrySet()) {
			int map = entry.getKey();
			if ((onlyMap != 0 && map != onlyMap) || !Files.isRegularFile(Navmesh.fileOf(map)))
				continue;
			int mainRegion = largestRegion(map);
			System.out.printf("Map %d: %d obelisk(s), main ground is region %d%n", map, entry.getValue().size(), mainRegion);
			for (Obelisk obelisk : entry.getValue()) {
				checked++;
				String verdict = judge(obelisk, entry.getValue(), mainRegion);
				if (verdict.startsWith("FAIL"))
					failed++;
				System.out.printf("  %-4s %.0f %.0f %.0f  %s%n", verdict.startsWith("FAIL") ? "FAIL" : "ok", obelisk.x(), obelisk.y(), obelisk.z(),
					verdict.replaceFirst("^(FAIL|ok):? ?", ""));
			}
		}
		System.out.printf("%d obelisk(s) checked, %d fail%n", checked, failed);
		return failed == 0 ? 0 : 1;
	}

	private static String judge(Obelisk obelisk, List<Obelisk> others, int mainRegion) {
		NavmeshService mesh = NavmeshService.getInstance();
		Vector3f floor = nearestFloor(mesh, obelisk);
		if (floor == null)
			return "FAIL: no floor within " + FLOOR_REACH + " m, the obelisk is inside geometry";
		StringBuilder remarks = new StringBuilder();
		boolean onMain = false;
		for (int region : mesh.regionsAt(obelisk.map(), obelisk.x(), obelisk.y())) {
			if (region == mainRegion)
				onMain = true;
		}
		if (!onMain)
			remarks.append("not on the map's largest ground; ");

		List<Obelisk> nearest = new ArrayList<>(others);
		nearest.remove(obelisk);
		nearest.sort(Comparator.comparingDouble(other -> Math.hypot(other.x() - obelisk.x(), other.y() - obelisk.y())));
		int yes = 0, no = 0, unknown = 0;
		for (Obelisk other : nearest.subList(0, Math.min(ROUTES, nearest.size()))) {
			Vector3f target = nearestFloor(mesh, other);
			if (target == null)
				continue; // that one has its own verdict
			switch (mesh.reach(obelisk.map(), floor.getX(), floor.getY(), floor.getZ(), target.getX(), target.getY(), target.getZ())) {
				case YES -> yes++;
				case NO -> no++;
				case UNKNOWN -> unknown++;
			}
		}
		if (yes + unknown == 0 && no > 0)
			return "FAIL: no route to any of the " + no + " nearest obelisks, " + remarks + "z of the floor " + Math.round(floor.getZ());
		return "ok: routes " + yes + " found, " + no + " refused, " + unknown + " undecided; " + remarks;
	}

	/** @return The floor under an obelisk, or null when there is none within reach of its recorded height. */
	private static Vector3f nearestFloor(NavmeshService mesh, Obelisk obelisk) {
		List<Vector3f> floors = mesh.floorsAt(obelisk.map(), obelisk.x(), obelisk.y(), obelisk.z());
		if (!floors.isEmpty() && Math.abs(floors.getFirst().getZ() - obelisk.z()) <= FLOOR_REACH)
			return floors.getFirst();
		// the NPC stands on a plinth, which is solid: the floor beside it is what a resurrected body is put on
		for (float radius : new float[] { 1, 2, 3, 4 }) {
			for (int k = 0; k < 8; k++) {
				double angle = Math.PI / 4 * k;
				floors = mesh.floorsAt(obelisk.map(), obelisk.x() + (float) Math.cos(angle) * radius, obelisk.y() + (float) Math.sin(angle) * radius,
					obelisk.z());
				if (!floors.isEmpty() && Math.abs(floors.getFirst().getZ() - obelisk.z()) <= FLOOR_REACH)
					return floors.getFirst();
			}
		}
		return null;
	}

	/** @return The region that passes under the most coarse cells of the map, which is what the rest of the map is measured against. */
	private static int largestRegion(int map) throws IOException {
		Navmesh mesh = Navmesh.open(map);
		try {
			Map<Integer, Integer> cells = new HashMap<>();
			for (int y = 0; y < mesh.coarseHeight(); y++) {
				for (int x = 0; x < mesh.coarseWidth(); x++) {
					for (int index = 0; index < mesh.coarseRegionCount(x, y); index++)
						cells.merge(mesh.coarseRegion(x, y, index), 1, Integer::sum);
				}
			}
			return cells.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
		} finally {
			mesh.close();
		}
	}

	static Map<Integer, List<Obelisk>> readObelisks(Path data) throws IOException {
		Set<String> binds = new HashSet<>();
		Matcher matcher = BIND.matcher("");
		for (String line : Files.readAllLines(data.resolve("bind_points/bind_points.xml"))) {
			if (matcher.reset(line).find())
				binds.add(matcher.group(1));
		}
		Map<Integer, List<Obelisk>> byMap = new TreeMap<>();
		try (Stream<Path> files = Files.walk(data.resolve("spawns"))) {
			for (Path file : files.filter(path -> path.toString().endsWith(".xml")).toList()) {
				int map = 0;
				boolean bind = false;
				for (String line : Files.readAllLines(file)) {
					if ((matcher = MAP.matcher(line)).find()) {
						map = Integer.parseInt(matcher.group(1));
					} else if ((matcher = NPC.matcher(line)).find()) {
						bind = binds.contains(matcher.group(1));
					} else if (bind && (matcher = SPOT.matcher(line)).find()) {
						byMap.computeIfAbsent(map, k -> new ArrayList<>()).add(new Obelisk(map, Float.parseFloat(matcher.group(1)),
							Float.parseFloat(matcher.group(2)), Float.parseFloat(matcher.group(3))));
					}
				}
			}
		}
		return byMap;
	}
}
