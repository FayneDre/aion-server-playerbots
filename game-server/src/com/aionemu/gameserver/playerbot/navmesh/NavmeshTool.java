package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
		if (args.length != 1) {
			System.out.println("Usage: NavmeshTool <mapId>|all");
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
		List<GeoPlacement> placements = GeoDataReader.readPlacements(mapId);
		if (placements.isEmpty()) {
			System.out.println("Map " + mapId + " has no geometry file");
			return;
		}
		report(mapId, models, placements);
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
