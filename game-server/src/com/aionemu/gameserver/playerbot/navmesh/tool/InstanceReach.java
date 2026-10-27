package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	/** How high over the floor below a spot with no footing has to be to count as in the air rather than beside a floor the mesh lost, in metres. */
	private static final float IN_THE_AIR = 6f;
	/** How far an arrival set down in the air may drop to the floor under it, in metres. */
	private static final float FALL = 50f;

	private static final Pattern PORTAL = Pattern
		.compile("<portal_loc\\s+world_id=\"(\\d+)\"\\s+loc_id=\"(\\d+)\"[^>]*\\sx=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");
	/**
	 * A portal's destination, in the list of what each portal npc leads to. The entrances from outside are written {@code <portal_path dialog="10000" loc_id=...>}, so the location is
	 * not always the first attribute: read only as the first, Unstable Abyssal Splinter's entrance was missed and its three inner pads were measured as the arrivals.
	 */
	private static final Pattern PORTAL_PATH = Pattern.compile("<portal_path\\b[^>]*?\\sloc_id=\"(\\d+)\"");
	private static final String AERIAL = "aerial_spawn=\"true\"";
	private static final Pattern WINDSTREAM = Pattern.compile("<windstream\\s+mapid=\"(\\d+)\"");
	private static final Pattern WINDSTREAM_LOCATION = Pattern.compile("<location\\s+id=\"(\\d+)\"");
	private static final Pattern FLY_PATH = Pattern
		.compile("<flypath_location\\s+id=\"(\\d+)\"[^>]*\\sex=\"([-\\d.]+)\"\\s+ey=\"([-\\d.]+)\"\\s+ez=\"([-\\d.]+)\"\\s+eworld=\"(\\d+)\"");
	private static final Pattern SPOT = Pattern.compile("<spot\\s+x=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");

	/**
	 * Where the instance's own handler puts each side's party, for the maps whose arrival is not in the portal list: the three Dredgions start one faction at each end of the ship
	 * ({@code DredgionInstance.portToStartPosition}, which all three share), and a portal location there leads nowhere a body can stand.
	 */
	private static final Map<Integer, List<float[]>> HANDLER_ARRIVALS = Map.of(300110000, dredgionStarts(), 300210000, dredgionStarts(), 300440000, dredgionStarts());

	/**
	 * Where a teleporter inside the instance puts the party, for the maps whose handler moves it in code and not through the portal list: each is measured as a further arrival, since
	 * its ground is not joined to the rest. The Danuar Sanctuaries go from the hall to each wing in turn ({@code handleUseItemFinish} of npcs 701873, 701871, 701872).
	 * The two Dragon Lord's Refuges, which share a handler, from the entrance to Tiamat's platform (730625) and from there to the four realms of her
	 * dragons at the corners of the map (730673 to 730676): taken for decor perched 246 m up until the handler was read.
	 */
	private static final Map<Integer, List<float[]>> TELEPORT_LANDINGS = Map.of(301140000, danuarWings(), 301380000, danuarWings(), 300520000,
		dragonRealms(), 300630000, dragonRealms());

	private static List<float[]> dredgionStarts() {
		return List.of(new float[] { 570.468f, 166.897f, 432.29f }, new float[] { 400.741f, 166.713f, 432.29f });
	}

	private static List<float[]> dragonRealms() {
		return List.of(new float[] { 503.389f, 514.661f, 417.404f }, new float[] { 217.144f, 195.616f, 246.0712f }, new float[] { 785.866f, 197.713f, 246.0712f },
			new float[] { 217.947f, 832.552f, 246.0712f }, new float[] { 779.178f, 833.055f, 246.0712f });
	}

	private static List<float[]> danuarWings() {
		return List.of(new float[] { 1029.273f, 362.651f, 297.89f }, new float[] { 1006.0412f, 1366.468f, 337.26f }, new float[] { 846.172f, 991.731f, 300.04f });
	}

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
		// a map reached in parts, by teleporters or one faction at each end, is only read right as a whole: what some arrival reaches
		Set<float[]> reachedByAny = new HashSet<>();
		int askedOfAny = 0;
		for (float[] given : entries) {
			float[] entry = snapped(mesh, given);
			if (entry == null)
				entry = fallen(mesh, given);
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
				reachedByAny.add(original);
				float length = 0;
				for (int i = 1; i < route.waypoints().size(); i++)
					length += route.waypoints().get(i).distance(route.waypoints().get(i - 1));
				lengths.add(length);
			}
			Collections.sort(lengths);
			int asked = sample.size() - noFooting;
			askedOfAny = Math.max(askedOfAny, asked);
			System.out.printf(Locale.ROOT, "Map %d, from %.1f %.1f %.1f: %d of %d spots reached (%.0f%%), %d of the others gave up, %d more have no footing, median route %s m%n",
				mapId, entry[0], entry[1], entry[2], reached, asked, asked == 0 ? 0f : reached * 100f / asked, gaveUp, noFooting,
				lengths.isEmpty() ? "-" : String.valueOf(Math.round(lengths.get(lengths.size() / 2))));
			for (float[] spot : missed.subList(0, Math.min(SHOWN, missed.size())))
				System.out.printf(Locale.ROOT, "  not reached: %.1f %.1f %.1f   NavmeshTool %d path %.1f %.1f %.1f %.1f %.1f %.1f%n", spot[0], spot[1], spot[2], mapId, entry[0],
					entry[1], entry[2], spot[0], spot[1], spot[2]);
			if (!missed.isEmpty())
				worst = 1;
		}
		if (entries.size() > 1)
			System.out.printf(Locale.ROOT, "Map %d, from any of its %d arrivals: %d of %d spots reached (%.0f%%)%n", mapId, entries.size(), reachedByAny.size(), askedOfAny,
				askedOfAny == 0 ? 0f : reachedByAny.size() * 100f / askedOfAny);
		printNoFooting(mesh, mapId, sample);
		return worst;
	}

	/**
	 * Where the spots with no footing stand against the walkable floors of their own column: high over one is a monster in the air, a fight a walking bot
	 * cannot join, while one a little under or over is a floor the mesh lost.
	 */
	private static void printNoFooting(Navmesh mesh, int mapId, List<float[]> sample) {
		int inTheAir = 0, near = 0, noFloor = 0;
		List<String> shown = new ArrayList<>();
		for (float[] spot : sample) {
			if (snapped(mesh, spot) != null)
				continue;
			int cellX = mesh.cellX(spot[0]), cellY = mesh.cellY(spot[1]);
			float below = Float.NaN, above = Float.NaN;
			for (int surface = 0; mesh.contains(cellX, cellY) && surface < mesh.surfaceCount(cellX, cellY); surface++) {
				if (!mesh.isWalkable(cellX, cellY, surface))
					continue;
				float z = mesh.surfaceZ(cellX, cellY, surface);
				if (z <= spot[2] && (Float.isNaN(below) || z > below))
					below = z;
				else if (z > spot[2] && (Float.isNaN(above) || z < above))
					above = z;
			}
			String where;
			if (Float.isNaN(below) && Float.isNaN(above)) {
				noFloor++;
				where = "no walkable floor in its column";
			} else if (!Float.isNaN(below) && spot[2] - below > IN_THE_AIR) {
				inTheAir++;
				where = String.format(Locale.ROOT, "%.0f m over the floor below", spot[2] - below);
			} else {
				near++;
				where = Float.isNaN(below) ? String.format(Locale.ROOT, "%.1f m under a floor", above - spot[2])
					: String.format(Locale.ROOT, "%.1f m over the floor below", spot[2] - below);
			}
			if (shown.size() < SHOWN)
				shown.add(String.format(Locale.ROOT, "  no footing: %.1f %.1f %.1f, %s", spot[0], spot[1], spot[2], where));
		}
		if (inTheAir + near + noFloor == 0)
			return;
		System.out.printf(Locale.ROOT, "Map %d, the spots with no footing: %d more than %.0f m over a floor, %d nearer one, %d over none%n", mapId, inTheAir, IN_THE_AIR,
			near, noFloor);
		shown.forEach(System.out::println);
	}

	/** @return The spot moved onto the nearest walkable ground within {@link #SNAP} metres of it in height, or null when there is none. */
	static float[] snapped(Navmesh mesh, float[] spot) {
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
	 * @return The highest walkable floor under an arrival set down in the air, within {@link #FALL} metres, or null: a windstream ends where its flight path
	 *         does, over the ground its rider then drops or glides to.
	 */
	private static float[] fallen(Navmesh mesh, float[] arrival) {
		int cellX = mesh.cellX(arrival[0]), cellY = mesh.cellY(arrival[1]);
		float best = Float.NaN;
		for (int surface = 0; mesh.contains(cellX, cellY) && surface < mesh.surfaceCount(cellX, cellY); surface++) {
			float z = mesh.surfaceZ(cellX, cellY, surface);
			if (mesh.isWalkable(cellX, cellY, surface) && z <= arrival[2] && arrival[2] - z <= FALL && (Float.isNaN(best) || z > best))
				best = z;
		}
		return Float.isNaN(best) ? null : new float[] { arrival[0], arrival[1], best };
	}

	/**
	 * @return Where a party arrives in the instance: x, y, z each. The portal list holds every portal location of the map, the way out and the ones inside as well, so
	 *         only those that a portal npc leads to are arrivals; a map none of whose locations is led to by anyone gets all of them rather than none.
	 */
	static List<float[]> entries(int mapId) throws IOException {
		if (HANDLER_ARRIVALS.containsKey(mapId))
			return HANDLER_ARRIVALS.get(mapId);
		List<float[]> entries = entries(STATIC_DATA.resolve("portals/portal_loc.xml"), STATIC_DATA.resolve("portals/portal_template2.xml"), mapId);
		entries.addAll(TELEPORT_LANDINGS.getOrDefault(mapId, List.of()));
		entries.addAll(windstreamLandings(STATIC_DATA.resolve("windstreams/windstreams.xml"), STATIC_DATA.resolve("flypath_template.xml"), mapId));
		return entries;
	}

	/**
	 * @return Where each windstream of the map sets its rider down, x, y, z each. Esoterrace is crossed by its four windstreams and not on foot: measured from its
	 *         entrance alone, the whole far side counted as the mesh's fault (65 %).
	 */
	static List<float[]> windstreamLandings(Path windstreams, Path flyPaths, int mapId) throws IOException {
		List<float[]> landings = new ArrayList<>();
		if (!Files.isRegularFile(windstreams) || !Files.isRegularFile(flyPaths))
			return landings;
		Set<Integer> ids = new HashSet<>();
		boolean inMap = false;
		for (String line : Files.readAllLines(windstreams)) {
			Matcher block = WINDSTREAM.matcher(line);
			if (block.find())
				inMap = Integer.parseInt(block.group(1)) == mapId;
			Matcher location = WINDSTREAM_LOCATION.matcher(line);
			if (inMap && location.find())
				ids.add(Integer.parseInt(location.group(1)));
		}
		for (String line : Files.readAllLines(flyPaths)) {
			Matcher matcher = FLY_PATH.matcher(line);
			if (matcher.find() && ids.contains(Integer.parseInt(matcher.group(1))) && Integer.parseInt(matcher.group(5)) == mapId)
				landings.add(new float[] { Float.parseFloat(matcher.group(2)), Float.parseFloat(matcher.group(3)), Float.parseFloat(matcher.group(4)) });
		}
		return landings;
	}

	static List<float[]> entries(Path portalList, Path portalTemplates, int mapId) throws IOException {
		Set<Integer> led = new HashSet<>();
		if (Files.isRegularFile(portalTemplates)) {
			for (String line : Files.readAllLines(portalTemplates)) {
				Matcher matcher = PORTAL_PATH.matcher(line);
				while (matcher.find()) // a portal leading to several places can write them all on one line
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

	/**
	 * @return Every distinct place a monster or object is put in the instance, read from its spawn file, but those in the air. A spot marked
	 *         {@code aerial_spawn} is a flyer on a path through the air, Rentus's drakan riders among them (73 of its 338): nothing walks to it, on a map
	 *         where nobody can fly, so it measured as the mesh's fault what is the fight's shape.
	 */
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
					if (matcher.find() && !line.contains(AERIAL))
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
