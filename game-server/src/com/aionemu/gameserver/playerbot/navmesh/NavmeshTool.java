package com.aionemu.gameserver.playerbot.navmesh;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;

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
		if (args.length != 1 && args.length != 2 && args.length != 3) {
			System.out.println("Usage: NavmeshTool <mapId>|all");
			System.out.println("       NavmeshTool <mapId> <x> <y>   inspect one spot");
			System.out.println("       NavmeshTool <mapId> <text>    inspect the props whose model name contains that text");
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
	}

	/**
	 * Reads the file back and compares it against what was generated, column by column. A format is only as good as the proof that it survives the
	 * trip, and a silent corruption here would surface much later as a bot walking into a wall.
	 */
	private static void verifyRoundTrip(int mapId, Heightfield field) throws IOException {
		long start = System.currentTimeMillis();
		Navmesh navmesh = Navmesh.load(mapId);
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
		System.out.printf("Loaded back in %d ms holding %.0f MB, %d surfaces compared, %d mismatches%n", loadTime,
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
