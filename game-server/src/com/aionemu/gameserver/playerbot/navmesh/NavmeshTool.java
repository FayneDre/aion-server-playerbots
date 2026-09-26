package com.aionemu.gameserver.playerbot.navmesh;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Offline entry point of the navmesh toolchain. Run it from the server directory, where {@code data/geo} lives:
 *
 * <pre>
 * java -cp "libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool 210010000
 * </pre>
 *
 * It deliberately starts nothing: no database, no world, no data manager. Generation is a batch job over files.
 */
public class NavmeshTool {

	private static final byte TOWN_OBJECT = 5; // DespawnableNode.DespawnableType.TOWN_OBJECT

	public static void main(String[] args) throws IOException {
		if (args.length >= 2 && args[1].equals("components")) {
			float[] probes = new float[args.length - 2];
			for (int i = 2; i < args.length; i++)
				probes[i - 2] = Float.parseFloat(args[i]);
			reportComponents(Integer.parseInt(args[0]), probes);
			return;
		}
		if (args.length == 6 && args[1].equals("path")) {
			planRoute(Integer.parseInt(args[0]), Float.parseFloat(args[2]), Float.parseFloat(args[3]), Float.parseFloat(args[4]),
				Float.parseFloat(args[5]));
			return;
		}
		if (args.length != 1 && args.length != 2 && args.length != 3) {
			System.out.println("Usage: NavmeshTool <mapId>|all");
			System.out.println("       NavmeshTool <mapId> <x> <y>   inspect one spot");
			System.out.println("       NavmeshTool <mapId> <text>    inspect the props whose model name contains that text");
			System.out.println("       NavmeshTool <mapId> path <x1> <y1> <x2> <y2>   plan a route and draw it");
			System.out.println("       NavmeshTool <mapId> components   find what is reachable from what");
			return;
		}

		long start = System.currentTimeMillis();
		Map<String, List<GeoModel>> models = GeoDataReader.readModels();
		System.out.printf("Loaded %d meshes (%d triangles) in %d ms%n", models.size(), countTriangles(models),
			System.currentTimeMillis() - start);

		if (args[0].equals("all")) { // the totals to compare against the server's own startup log
			long entities = 0, maps = 0, townClones = 0;
			for (int id : GeoDataReader.listMapIds()) {
				List<GeoPlacement> all = GeoDataReader.readPlacements(id);
				entities += all.size();
				townClones += all.stream().filter(p -> p.type() == TOWN_OBJECT && p.level() > 0)
					.mapToLong(p -> countTownClones(models, p)).sum();
				maps++;
			}
			// the server also instantiates one node per higher town level, so its own count is larger by exactly that many
			System.out.printf("Loaded %d entities on %d maps (+%d town level clones = %d)%n", entities, maps, townClones, entities + townClones);
			return;
		}

		int mapId = Integer.parseInt(args[0]);
		if (args.length == 2) {
			inspectProps(mapId, args[1]);
			return;
		}
		if (args.length == 3) {
			inspect(mapId, Float.parseFloat(args[1]), Float.parseFloat(args[2]));
			return;
		}

		List<GeoPlacement> placements = GeoDataReader.readPlacements(mapId);
		if (placements.isEmpty()) {
			System.out.println("Map " + mapId + " has no geometry file");
			return;
		}
		report(mapId, models, placements);

		start = System.currentTimeMillis();
		Heightfield field = HeightfieldBuilder.build(mapId);
		System.out.printf("Rasterized %dx%d columns holding %d surfaces (%d walkable) in %d ms%n", field.width(), field.height(),
			field.surfaceCount(), field.walkableCount(), System.currentTimeMillis() - start);
		System.out.println("Wrote " + writeHeightImage(mapId, field).toAbsolutePath());
		System.out.println("Wrote " + writeStructureImage(mapId, field).toAbsolutePath());
		System.out.println("Wrote " + writeWalkableImage(mapId, field).toAbsolutePath());

		start = System.currentTimeMillis();
		Path navmesh = NavmeshWriter.write(mapId, field);
		System.out.printf("Wrote %s (%.1f MB) in %d ms%n", navmesh.toAbsolutePath(), Files.size(navmesh) / 1048576f,
			System.currentTimeMillis() - start);
		verifyRoundTrip(mapId, field);
		System.out.println("Wrote " + writeCoarseImage(mapId, Navmesh.open(mapId)).toAbsolutePath());
	}

	/**
	 * Splits the walkable ground into islands of mutually reachable cells, using the same step rule the path finder does.
	 * <p>
	 * This answers the question a failed long route raises and a path finder cannot: is there no way, or did the search merely not find one. A map
	 * cut into pieces by a stream or a ledge shows up here as several large islands instead of one.
	 */
	private static void reportComponents(int mapId, float... probes) throws IOException {
		Heightfield field = HeightfieldBuilder.build(mapId);
		int width = field.width(), height = field.height();
		int[] island = new int[width * height];
		int[] stack = new int[width * height];
		int[] neighbourX = { 1, 1, 0, -1, -1, -1, 0, 1 }, neighbourY = { 0, 1, 1, 1, 0, -1, -1, -1 };

		List<int[]> islands = new ArrayList<>(); // id and size
		int nextIsland = 0;
		for (int origin = 0; origin < island.length; origin++) {
			if (island[origin] != 0 || !hasFooting(field, origin % width, origin / width))
				continue;
			int id = ++nextIsland, size = 0, top = 0;
			stack[top++] = origin;
			island[origin] = id;
			while (top > 0) {
				int column = stack[--top];
				size++;
				int x = column % width, y = column / width;
				float z = topWalkable(field, x, y);
				for (int direction = 0; direction < neighbourX.length; direction++) {
					int nextX = x + neighbourX[direction], nextY = y + neighbourY[direction];
					if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height)
						continue;
					int next = nextY * width + nextX;
					if (island[next] != 0 || !hasFooting(field, nextX, nextY))
						continue;
					boolean diagonal = neighbourX[direction] != 0 && neighbourY[direction] != 0;
					float climb = Heightfield.CELL_SIZE * (diagonal ? 1.41421f : 1) + BotPathFinder.STEP_TOLERANCE;
					if (Math.abs(topWalkable(field, nextX, nextY) - z) > climb)
						continue;
					island[next] = id;
					stack[top++] = next;
				}
			}
			islands.add(new int[] { id, size });
		}

		islands.sort((a, b) -> Integer.compare(b[1], a[1]));
		System.out.printf("%d islands of walkable ground%n", islands.size());
		for (int[] entry : islands.subList(0, Math.min(8, islands.size())))
			System.out.printf("  island %d: %d cells (%.1f%% of the map)%n", entry[0], entry[1], entry[1] * 100f / island.length);

		int[] palette = { 0x30A030, 0x3060C0, 0xC0A030, 0xA030C0, 0x30C0C0, 0xC06030 };
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Map<Integer, Integer> colours = new HashMap<>();
		for (int rank = 0; rank < Math.min(palette.length, islands.size()); rank++)
			colours.put(islands.get(rank)[0], palette[rank]);
		for (int column = 0; column < island.length; column++)
			image.setRGB(column % width, column / width, island[column] == 0 ? 0x202020 : colours.getOrDefault(island[column], 0xC02020));
		for (int i = 0; i + 1 < probes.length; i += 2) {
			int cellX = (int) (probes[i] / Heightfield.CELL_SIZE), cellY = (int) (probes[i + 1] / Heightfield.CELL_SIZE);
			int id = cellX >= 0 && cellY >= 0 && cellX < width && cellY < height ? island[cellY * width + cellX] : 0;
			System.out.printf("  %.0f %.0f is on island %s%n", probes[i], probes[i + 1], id == 0 ? "none, it has no footing" : String.valueOf(id));
			if (id != 0)
				describeIsland(island, width, height, id);
		}
		System.out.println("Wrote " + write(mapId, "islands", image).toAbsolutePath());
	}

	/** Prints how far an island reaches, which is what says whether a long route across it is even meaningful. */
	private static void describeIsland(int[] island, int width, int height, int id) {
		int minX = width, maxX = 0, minY = height, maxY = 0;
		int farthestX = 0, farthestY = 0;
		for (int column = 0; column < island.length; column++) {
			if (island[column] != id)
				continue;
			int x = column % width, y = column / width;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			farthestX = x;
			farthestY = y;
		}
		System.out.printf("    it spans %.0f by %.0f m, from %.0f %.0f to %.0f %.0f, and reaches %.0f %.0f%n",
			(maxX - minX) * Heightfield.CELL_SIZE, (maxY - minY) * Heightfield.CELL_SIZE, minX * Heightfield.CELL_SIZE,
			minY * Heightfield.CELL_SIZE, maxX * Heightfield.CELL_SIZE, maxY * Heightfield.CELL_SIZE, farthestX * Heightfield.CELL_SIZE,
			farthestY * Heightfield.CELL_SIZE);
	}

	private static boolean hasFooting(Heightfield field, int x, int y) {
		return field.hasFooting(x, y);
	}

	private static float topWalkable(Heightfield field, int x, int y) {
		for (int i = field.columnEnd(x, y) - 1; i >= field.columnStart(x, y); i--)
			if (field.isWalkable(i))
				return field.surfaceAt(i);
		return Float.NaN;
	}

	/** Dumps the rough grid long routes are planned on, where a hole means bots cannot route through even if they could walk there. */
	private static Path writeCoarseImage(int mapId, Navmesh mesh) throws IOException {
		BufferedImage image = new BufferedImage(mesh.coarseWidth(), mesh.coarseHeight(), BufferedImage.TYPE_INT_RGB);
		int walkable = 0;
		for (int y = 0; y < mesh.coarseHeight(); y++) {
			for (int x = 0; x < mesh.coarseWidth(); x++) {
				boolean ok = mesh.isCoarseWalkable(x, y);
				if (ok)
					walkable++;
				image.setRGB(x, y, ok ? 0x30A030 : 0xC02020);
			}
		}
		System.out.printf("Coarse grid %dx%d, %d of %d cells routable%n", mesh.coarseWidth(), mesh.coarseHeight(), walkable,
			mesh.coarseWidth() * mesh.coarseHeight());
		return write(mapId, "coarse", image);
	}

	/**
	 * Plans a route over the generated file and draws it, which is the only honest way to judge one: a list of coordinates tells you nothing about
	 * whether the bot went around the building or through it.
	 */
	private static void planRoute(int mapId, float startX, float startY, float goalX, float goalY) throws IOException {
		Navmesh mesh = Navmesh.open(mapId);
		float startZ = groundAt(mesh, startX, startY), goalZ = groundAt(mesh, goalX, goalY);
		System.out.printf("From %.1f %.1f %.1f to %.1f %.1f %.1f%n", startX, startY, startZ, goalX, goalY, goalZ);

		long start = System.currentTimeMillis();
		BotPathFinder.Route result = BotPathFinder.findPath(mesh, startX, startY, startZ, goalX, goalY, goalZ);
		long elapsed = System.currentTimeMillis() - start;
		if (result.isEmpty()) {
			System.out.println((result.gaveUp() ? "Gave up searching after " : "No route exists, established in ") + elapsed + " ms");
			List<Vector3f> guide = BotPathFinder.coarseRoute(mesh, startX, startY, goalX, goalY);
			System.out.println(guide.isEmpty() ? "  the rough pass found nothing either"
				: "  the rough pass did find a way, in " + guide.size() + " guide points, so a stretch of it failed to refine");
			return;
		}
		List<Vector3f> route = result.waypoints();

		float length = 0;
		for (int i = 1; i < route.size(); i++)
			length += route.get(i).distance(route.get(i - 1));
		float direct = route.get(0).distance(route.get(route.size() - 1));
		System.out.printf("Route of %d waypoints, %.0f m walked for %.0f m as the crow flies, in %d ms%n", route.size(), length, direct, elapsed);
		for (Vector3f waypoint : route)
			System.out.printf("  %.1f %.1f %.1f%n", waypoint.x, waypoint.y, waypoint.z);
		System.out.println("Wrote " + writeRouteImage(mapId, mesh, route).toAbsolutePath());
	}

	/** Looks outwards for walkable ground, the way the path finder does, so a spot on an eroded edge still reports a sensible height. */
	private static float groundAt(Navmesh mesh, float x, float y) {
		int centreX = mesh.cellX(x), centreY = mesh.cellY(y);
		for (int ring = 0; ring <= 12; ring++) {
			for (int offsetY = -ring; offsetY <= ring; offsetY++) {
				for (int offsetX = -ring; offsetX <= ring; offsetX++) {
					if (ring > 0 && Math.abs(offsetX) != ring && Math.abs(offsetY) != ring)
						continue;
					int cellX = centreX + offsetX, cellY = centreY + offsetY;
					if (!mesh.contains(cellX, cellY))
						continue;
					for (int surface = mesh.surfaceCount(cellX, cellY) - 1; surface >= 0; surface--)
						if (mesh.isWalkable(cellX, cellY, surface))
							return mesh.surfaceZ(cellX, cellY, surface);
				}
			}
		}
		return Float.NaN;
	}

	/** Draws the route over the walkability of the area it crosses, cropped so the result is actually readable. */
	private static Path writeRouteImage(int mapId, Navmesh mesh, List<Vector3f> route) throws IOException {
		int minX = mesh.width(), maxX = 0, minY = mesh.height(), maxY = 0;
		for (Vector3f waypoint : route) {
			minX = Math.min(minX, mesh.cellX(waypoint.x));
			maxX = Math.max(maxX, mesh.cellX(waypoint.x));
			minY = Math.min(minY, mesh.cellY(waypoint.y));
			maxY = Math.max(maxY, mesh.cellY(waypoint.y));
		}
		int margin = 40;
		minX = Math.max(0, minX - margin);
		minY = Math.max(0, minY - margin);
		maxX = Math.min(mesh.width() - 1, maxX + margin);
		maxY = Math.min(mesh.height() - 1, maxY + margin);

		BufferedImage image = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_RGB);
		for (int y = minY; y <= maxY; y++)
			for (int x = minX; x <= maxX; x++)
				image.setRGB(x - minX, y - minY, mesh.surfaceCount(x, y) == 0 ? 0x000080 : mesh.hasFooting(x, y) ? 0x30A030 : 0xC02020);

		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.WHITE);
		graphics.setStroke(new BasicStroke(2));
		for (int i = 1; i < route.size(); i++)
			graphics.drawLine(mesh.cellX(route.get(i - 1).x) - minX, mesh.cellY(route.get(i - 1).y) - minY, mesh.cellX(route.get(i).x) - minX,
				mesh.cellY(route.get(i).y) - minY);
		graphics.setColor(Color.YELLOW);
		for (Vector3f waypoint : route)
			graphics.fillOval(mesh.cellX(waypoint.x) - minX - 2, mesh.cellY(waypoint.y) - minY - 2, 5, 5);
		graphics.dispose();
		return write(mapId, "route", zoomToReadable(image));
	}

	/** Blows up a small crop so single cells stay visible, keeping hard edges rather than blurring them. */
	private static BufferedImage zoomToReadable(BufferedImage image) {
		int zoom = Math.max(1, Math.min(8, 600 / Math.max(image.getWidth(), image.getHeight())));
		if (zoom == 1)
			return image;
		BufferedImage zoomed = new BufferedImage(image.getWidth() * zoom, image.getHeight() * zoom, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = zoomed.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(image, 0, 0, zoomed.getWidth(), zoomed.getHeight(), null);
		graphics.dispose();
		return zoomed;
	}

	/**
	 * Reads the file back and compares it against what was generated, column by column. A format is only as good as the proof that it survives the
	 * trip, and a silent corruption here would surface much later as a bot walking into a wall.
	 */
	private static void verifyRoundTrip(int mapId, Heightfield field) throws IOException {
		long start = System.currentTimeMillis();
		Navmesh navmesh = Navmesh.open(mapId);
		long loadTime = System.currentTimeMillis() - start;

		if (navmesh.width() != field.width() || navmesh.height() != field.height())
			throw new IllegalStateException("Round trip changed the grid size");

		long mismatches = 0, compared = 0;
		float tolerance = Navmesh.Z_STEP; // heights are quantized on the way out, so they come back rounded
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				int expected = field.columnEnd(x, y) - field.columnStart(x, y);
				if (navmesh.surfaceCount(x, y) != expected) {
					mismatches++;
					continue;
				}
				for (int i = 0; i < expected; i++) {
					compared++;
					int source = field.columnStart(x, y) + i;
					if (Math.abs(navmesh.surfaceZ(x, y, i) - field.surfaceAt(source)) > tolerance
						|| navmesh.isWalkable(x, y, i) != field.isWalkable(source))
						mismatches++;
				}
			}
		}
		System.out.printf("Read back in %d ms, %d tiles holding %.0f MB, %d surfaces compared, %d mismatches%n", loadTime, navmesh.loadedTiles(),
			navmesh.memoryFootprint() / 1048576f, compared, mismatches);
		if (mismatches > 0)
			throw new IllegalStateException("The navmesh file does not match what was generated");
	}

	/**
	 * Finds the props whose model name contains the given text and reports what the generator made of the ground around them. Checking the pipeline
	 * against a real obstacle needs one example, not a catalogue: the same code rasterizes every other prop on the map.
	 */
	private static void inspectProps(int mapId, String nameFilter) throws IOException {
		List<GeoPlacement> matches = GeoDataReader.readPlacements(mapId).stream()
			.filter(placement -> placement.modelName().toLowerCase().contains(nameFilter.toLowerCase())).toList();
		System.out.printf("%d placements of a model matching '%s'%n", matches.size(), nameFilter);
		if (matches.isEmpty())
			return;

		Heightfield field = HeightfieldBuilder.build(mapId);
		for (GeoPlacement placement : matches.stream().limit(5).toList()) {
			System.out.printf("%n%s at %.1f %.1f %.1f%n", placement.modelName(), placement.position().x, placement.position().y,
				placement.position().z);
			describe(field, placement.position().x, placement.position().y);
		}
	}

	/**
	 * Prints what the generator decided about one spot in the world, so a bot getting stuck somewhere can be checked against the data instead of
	 * guessed at.
	 */
	private static void inspect(int mapId, float x, float y) throws IOException {
		Heightfield field = HeightfieldBuilder.build(mapId);
		int cellX = (int) (x / Heightfield.CELL_SIZE), cellY = (int) (y / Heightfield.CELL_SIZE);
		if (cellX < 0 || cellY < 0 || cellX >= field.width() || cellY >= field.height()) {
			System.out.printf("%.1f %.1f is outside map %d%n", x, y, mapId);
			return;
		}
		System.out.printf("Map %d at %.1f %.1f:%n", mapId, x, y);
		describe(field, x, y);
	}

	/** Prints the column at that spot, and how much of the ground around it a bot may walk on. */
	private static void describe(Heightfield field, float x, float y) {
		int cellX = (int) (x / Heightfield.CELL_SIZE), cellY = (int) (y / Heightfield.CELL_SIZE);
		if (cellX < 0 || cellY < 0 || cellX >= field.width() || cellY >= field.height()) {
			System.out.println("  outside the map");
			return;
		}
		for (int i = field.columnStart(cellX, cellY); i < field.columnEnd(cellX, cellY); i++)
			System.out.printf("  surface z=%.2f %s%n", field.surfaceAt(i), field.isWalkable(i) ? "walkable" : "BLOCKED");
		if (field.columnStart(cellX, cellY) == field.columnEnd(cellX, cellY))
			System.out.println("  nothing at all");

		int blocked = 0, total = 0;
		int radius = (int) (3 / Heightfield.CELL_SIZE); // a 3 m circle, about the footprint of a prop plus a body
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int nx = cellX + dx, ny = cellY + dy;
				if (nx < 0 || ny < 0 || nx >= field.width() || ny >= field.height())
					continue;
				total++;
				if (!field.hasFooting(nx, ny))
					blocked++;
			}
		}
		System.out.printf("  %d of %d cells within 3 m are blocked%n", blocked, total);
	}

	/**
	 * Dumps what a bot may stand on: green walkable, red solid but not standable, blue nothing at all. This is the map a path is planned over, so
	 * anything wrong with slopes, head room or no-walk volumes shows here.
	 */
	private static Path writeWalkableImage(int mapId, Heightfield field) throws IOException {
		BufferedImage image = new BufferedImage(field.width(), field.height(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				boolean empty = field.columnStart(x, y) == field.columnEnd(x, y);
				image.setRGB(x, y, empty ? 0x000080 : field.hasFooting(x, y) ? 0x30A030 : 0xC02020);
			}
		}
		return write(mapId, "walkable", image);
	}

	/**
	 * Dumps where columns hold more than one surface, which is everything standing on the ground: buildings, bridges, props. Separating it from the
	 * height image is what tells terrain rasterization and mesh rasterization apart when one of them is wrong.
	 */
	private static Path writeStructureImage(int mapId, Heightfield field) throws IOException {
		BufferedImage image = new BufferedImage(field.width(), field.height(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				int layers = field.columnEnd(x, y) - field.columnStart(x, y);
				int rgb = switch (Math.min(layers, 3)) {
					case 0 -> 0x000080; // nothing at all
					case 1 -> 0x202020; // bare ground
					case 2 -> 0xFF8000; // one thing standing on it
					default -> 0xFF0000; // stacked geometry: a building, a bridge, a cliff overhang
				};
				image.setRGB(x, y, rgb);
			}
		}
		return write(mapId, "structures", image);
	}

	/**
	 * Dumps the top surface of every column as a grey image, dark low and bright high. This is the whole point of this stage: a generation bug is
	 * something you see here, instead of something you infer from a bot walking into a wall an hour later.
	 */
	private static Path writeHeightImage(int mapId, Heightfield field) throws IOException {
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				float z = field.topSurface(x, y);
				if (Float.isNaN(z))
					continue;
				min = Math.min(min, z);
				max = Math.max(max, z);
			}
		}

		BufferedImage image = new BufferedImage(field.width(), field.height(), BufferedImage.TYPE_INT_RGB);
		float span = Math.max(1, max - min);
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				float z = field.topSurface(x, y);
				int rgb;
				if (Float.isNaN(z)) {
					rgb = 0x000080; // no ground at all, drawn blue so holes stand out from low ground
				} else {
					int grey = Math.round((z - min) / span * 255);
					rgb = grey << 16 | grey << 8 | grey;
				}
				image.setRGB(x, y, rgb);
			}
		}

		return write(mapId, "height", image);
	}

	private static Path write(int mapId, String kind, BufferedImage image) throws IOException {
		Path out = Path.of("data/navmesh");
		Files.createDirectories(out);
		Path file = out.resolve(mapId + "-" + kind + ".png");
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private static void report(int mapId, Map<String, List<GeoModel>> models, List<GeoPlacement> placements) {
		long missing = placements.stream().filter(placement -> !models.containsKey(placement.modelName())).count();
		long despawnable = placements.stream().filter(placement -> placement.type() != 0).count();
		long triangles = 0, walkableVolumes = 0;
		for (GeoPlacement placement : placements) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null)
				continue;
			for (GeoModel part : parts) {
				triangles += part.triangleCount();
				if ((part.collisionIntentions() & CollisionIntention.WALK.getId()) != 0)
					walkableVolumes++;
			}
		}
		System.out.printf("Map %d: %d placements (%d despawnable, %d with no mesh)%n", mapId, placements.size(), despawnable, missing);
		System.out.printf("  %d triangles once placed, %d parts carrying a WALK volume%n", triangles, walkableVolumes);

		Map<String, Long> byIntention = new TreeMap<>();
		for (GeoPlacement placement : placements) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null)
				continue;
			for (GeoModel part : parts)
				byIntention.merge(CollisionIntention.toString(part.collisionIntentions()), 1L, Long::sum);
		}
		System.out.println("  placed parts by collision intention:");
		byIntention.forEach((intentions, count) -> System.out.printf("    %-60s %d%n", intentions.isEmpty() ? "(none)" : intentions, count));
	}

	/**
	 * Replicates the client logic the server follows: a town object is duplicated for every higher town level that has its own model file.
	 */
	private static long countTownClones(Map<String, List<GeoModel>> models, GeoPlacement placement) {
		long clones = 0;
		for (int townLevel = placement.level() + 1; townLevel <= 5; townLevel++) {
			if (models.containsKey(placement.modelName().replace("_01.cgf", "_0" + townLevel + ".cgf")))
				clones++;
		}
		return clones;
	}

	private static long countTriangles(Map<String, List<GeoModel>> models) {
		// aliases share their parts, so count each mesh once
		return models.values().stream().distinct().flatMap(List::stream).mapToLong(GeoModel::triangleCount).sum();
	}
}
