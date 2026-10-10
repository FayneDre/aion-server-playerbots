package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether a bot that arrives in an instance can walk to where the monsters stand, which is the question a dungeon's mesh is for.
 * <p>
 * The obelisk audit has nothing to start from in a dungeon, which has none. What it has instead is the place the party arrives, {@code portal_loc.xml}, and the
 * places the monsters are put, {@code spawns/Instances/<id>_*.xml}. A route from the first to the second says the mesh joins what a player walks across, and
 * the spots it cannot reach are either behind a door that really is shut or a fault in the mesh: the report prints both kinds, with a command that plans the
 * route, and does not decide which.
 */
final class InstanceReach {

	private static final Path STATIC_DATA = Path.of("data/static_data");
	/** The most spots routed to. Every one is a path search, and a dungeon repeats its spots by the hundred, so a seeded sample says as much. */
	private static final int MAX_SPOTS = 250;
	/** How far, in metres, a spot may sit from walkable ground and still be asked about: a monster stands on a pedestal or a rug, and a portal lands a little above the floor. */
	private static final float SNAP = 3f;
	/** How many unreachable spots are printed. */
	private static final int SHOWN = 8;

	private static final Pattern PORTAL = Pattern
		.compile("<portal_loc\\s+world_id=\"(\\d+)\"\\s+loc_id=\"(\\d+)\"[^>]*\\sx=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");
	/** A portal's destination, in the list of what each portal npc leads to. */
	private static final Pattern PORTAL_PATH = Pattern.compile("<portal_path\\s+loc_id=\"(\\d+)\"");
	private static final Pattern SPOT = Pattern.compile("<spot\\s+x=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");

	private InstanceReach() {
	}

	/** @return 0 when every spot sampled was reached from every entry, 1 when some were not, 2 when there was nothing to ask. */
	static int run(int mapId) throws IOException {
		List<float[]> entries = entries(mapId);
		List<float[]> spots = spawnSpots(mapId);
		if (entries.isEmpty() || spots.isEmpty()) {
			System.out.printf(Locale.ROOT, "Map %d: %d entry point(s) and %d spawn spot(s), so there is nothing to route between%n", mapId, entries.size(), spots.size());
			return 2;
		}
		Navmesh mesh = Navmesh.open(mapId);
		Collections.shuffle(spots, new Random(mapId)); // seeded on the map, so two runs of the same mesh are comparable
		List<float[]> sample = spots.subList(0, Math.min(MAX_SPOTS, spots.size()));
		int worst = 0;
		for (float[] given : entries) {
			float[] entry = snapped(mesh, given);
			if (entry == null) {
				System.out.printf(Locale.ROOT, "Map %d, from %.1f %.1f %.1f: the entry has no walkable ground within %.0f m, so nothing can be asked of it%n", mapId, given[0],
					given[1], given[2], SNAP);
				worst = 1;
				continue;
			}
			int reached = 0, gaveUp = 0, noFooting = 0;
			List<float[]> missed = new ArrayList<>();
			List<Float> lengths = new ArrayList<>();
			for (float[] original : sample) {
				float[] spot = snapped(mesh, original);
				if (spot == null) {
					noFooting++; // a spot in the middle of geometry is the data's, not the route's: counted apart, and not held against the mesh
					continue;
				}
				BotPathFinder.Route route = BotPathFinder.findPath(mesh, entry[0], entry[1], entry[2], spot[0], spot[1], spot[2]);
				if (route.isEmpty()) {
					if (route.gaveUp())
						gaveUp++;
					missed.add(spot);
					continue;
				}
				reached++;
				float length = 0;
				for (int i = 1; i < route.waypoints().size(); i++)
					length += route.waypoints().get(i).distance(route.waypoints().get(i - 1));
				lengths.add(length);
			}
			Collections.sort(lengths);
			int asked = sample.size() - noFooting;
			System.out.printf(Locale.ROOT, "Map %d, from %.1f %.1f %.1f: %d of %d spots reached (%.0f%%), %d of the others gave up, %d more have no footing, median route %s m%n",
				mapId, entry[0], entry[1], entry[2], reached, asked, asked == 0 ? 0f : reached * 100f / asked, gaveUp, noFooting,
				lengths.isEmpty() ? "-" : String.valueOf(Math.round(lengths.get(lengths.size() / 2))));
			for (float[] spot : missed.subList(0, Math.min(SHOWN, missed.size())))
				System.out.printf(Locale.ROOT, "  not reached: %.1f %.1f %.1f   NavmeshTool %d path %.1f %.1f %.1f %.1f %.1f %.1f%n", spot[0], spot[1], spot[2], mapId, entry[0],
					entry[1], entry[2], spot[0], spot[1], spot[2]);
			if (!missed.isEmpty())
				worst = 1;
		}
		return worst;
	}

	/** @return The spot moved onto the nearest walkable ground within {@link #SNAP} metres of it in height, or null when there is none. */
	private static float[] snapped(Navmesh mesh, float[] spot) {
		int centreX = mesh.cellX(spot[0]), centreY = mesh.cellY(spot[1]);
		int rings = Math.round(SNAP / mesh.cellSize());
		float best = Float.NaN;
		for (int offsetY = -rings; offsetY <= rings; offsetY++) {
			for (int offsetX = -rings; offsetX <= rings; offsetX++) {
				int cellX = centreX + offsetX, cellY = centreY + offsetY;
				if (!mesh.contains(cellX, cellY))
					continue;
				for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
					float z = mesh.surfaceZ(cellX, cellY, surface);
					if (mesh.isWalkable(cellX, cellY, surface) && Math.abs(z - spot[2]) <= SNAP && (Float.isNaN(best) || Math.abs(z - spot[2]) < Math.abs(best - spot[2])))
						best = z;
				}
			}
		}
		return Float.isNaN(best) ? null : new float[] { spot[0], spot[1], best };
	}

	/**
	 * @return Where a party arrives in the instance: x, y, z each. The portal list holds every portal location of the map, the way out and the ones inside as well, so
	 *         only those that a portal npc leads to are arrivals; a map none of whose locations is led to by anyone gets all of them rather than none.
	 */
	static List<float[]> entries(int mapId) throws IOException {
		return entries(STATIC_DATA.resolve("portals/portal_loc.xml"), STATIC_DATA.resolve("portals/portal_template2.xml"), mapId);
	}

	static List<float[]> entries(Path portalList, Path portalTemplates, int mapId) throws IOException {
		Set<Integer> led = new HashSet<>();
		if (Files.isRegularFile(portalTemplates)) {
			for (String line : Files.readAllLines(portalTemplates)) {
				Matcher matcher = PORTAL_PATH.matcher(line);
				if (matcher.find())
					led.add(Integer.parseInt(matcher.group(1)));
			}
		}
		List<float[]> arrivals = new ArrayList<>(), all = new ArrayList<>();
		for (String line : Files.readAllLines(portalList)) {
			Matcher matcher = PORTAL.matcher(line);
			if (!matcher.find() || Integer.parseInt(matcher.group(1)) != mapId)
				continue;
			all.add(spot(matcher));
			if (led.contains(Integer.parseInt(matcher.group(2))))
				arrivals.add(spot(matcher));
		}
		return arrivals.isEmpty() ? all : arrivals;
	}

	/** @return Every distinct place a monster or object is put in the instance, read from its spawn file. */
	static List<float[]> spawnSpots(int mapId) throws IOException {
		return spawnSpots(STATIC_DATA.resolve("spawns/Instances"), mapId);
	}

	static List<float[]> spawnSpots(Path folder, int mapId) throws IOException {
		List<float[]> found = new ArrayList<>();
		if (!Files.isDirectory(folder))
			return found;
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith(mapId + "_")).toList()) {
				for (String line : Files.readAllLines(file)) {
					Matcher matcher = SPOT.matcher(line);
					if (matcher.find())
						found.add(spot(matcher));
				}
			}
		}
		return found;
	}

	private static float[] spot(Matcher matcher) {
		int first = matcher.groupCount() - 2;
		return new float[] { Float.parseFloat(matcher.group(first)), Float.parseFloat(matcher.group(first + 1)), Float.parseFloat(matcher.group(first + 2)) };
	}
}
