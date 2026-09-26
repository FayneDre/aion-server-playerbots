package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Samples a map's surfaces onto {@link Heightfield}'s grid.
 * <p>
 * Works in two passes over the same geometry: the first counts how many surfaces each column gets, the second writes them. Counting first is what
 * allows the flat storage, and geometry is cheap to walk twice compared to keeping sixteen million growable lists alive.
 */
public class HeightfieldBuilder {

	/** Physical obstacles only. Skill and see-through volumes do not stop a body, and MOVEABLE geometry is ships and boxes that come and go. */
	private static final byte SOLID = (byte) (CollisionIntention.PHYSICAL.getId() | CollisionIntention.DOOR.getId());

	private final TerrainData terrain;
	private final List<PlacedMesh> meshes;
	private final int width, height;

	private HeightfieldBuilder(TerrainData terrain, List<PlacedMesh> meshes, int width, int height) {
		this.terrain = terrain;
		this.meshes = meshes;
		this.width = width;
		this.height = height;
	}

	/** One model instance, its triangles already in world coordinates. */
	private record PlacedMesh(float[] vertices, int[] indices) {
	}

	public static Heightfield build(int mapId) throws IOException {
		TerrainData terrain = TerrainData.load(mapId);
		if (terrain == null)
			throw new IOException("Map " + mapId + " has no terrain heightmap");

		Map<String, List<GeoModel>> models = GeoDataReader.readModels();
		List<PlacedMesh> meshes = GeoDataReader.readPlacements(mapId).stream().flatMap(placement -> {
			List<GeoModel> parts = models.get(placement.modelName());
			return parts == null ? java.util.stream.Stream.<PlacedMesh> empty()
				: parts.stream().filter(part -> (part.collisionIntentions() & SOLID) != 0).map(part -> place(part, placement));
		}).toList();

		List<Integer> worldSize = terrain.worldSize();
		int width = (int) (worldSize.get(0) / Heightfield.CELL_SIZE);
		int height = (int) (worldSize.get(1) / Heightfield.CELL_SIZE);
		return new HeightfieldBuilder(terrain, meshes, width, height).rasterize();
	}

	private static PlacedMesh place(GeoModel model, GeoPlacement placement) {
		float[] vertices = new float[model.vertices().length];
		for (int v = 0; v < vertices.length; v += 3) {
			Vector3f world = placement.toWorld(model.vertices()[v], model.vertices()[v + 1], model.vertices()[v + 2]);
			vertices[v] = world.x;
			vertices[v + 1] = world.y;
			vertices[v + 2] = world.z;
		}
		return new PlacedMesh(vertices, model.indices());
	}

	private Heightfield rasterize() {
		int[] counts = new int[width * height + 1];
		sample((column, z) -> counts[column + 1]++);

		int[] offsets = counts;
		for (int i = 1; i < offsets.length; i++)
			offsets[i] += offsets[i - 1];

		float[] surfaces = new float[offsets[offsets.length - 1]];
		int[] cursor = offsets.clone();
		sample((column, z) -> surfaces[cursor[column]++] = z);

		Heightfield field = new Heightfield(width, height, offsets, surfaces);
		field.sortColumns();
		return field;
	}

	private interface SurfaceSink {
		void accept(int column, float z);
	}

	private void sample(SurfaceSink sink) {
		for (int cellY = 0; cellY < height; cellY++) {
			for (int cellX = 0; cellX < width; cellX++) {
				float z = terrain.heightAt(centre(cellX), centre(cellY));
				if (!Float.isNaN(z))
					sink.accept(cellY * width + cellX, z);
			}
		}
		for (PlacedMesh mesh : meshes)
			rasterizeMesh(mesh, sink);
	}

	/**
	 * Drops every triangle onto the grid. A triangle contributes a surface to each column whose centre falls inside its footprint, at the height of
	 * its plane there. Walls project to a line and so contribute almost nothing, which is exactly right: you cannot stand on a wall.
	 */
	private void rasterizeMesh(PlacedMesh mesh, SurfaceSink sink) {
		float[] vertices = mesh.vertices();
		for (int i = 0; i < mesh.indices().length; i += 3) {
			int a = mesh.indices()[i] * 3, b = mesh.indices()[i + 1] * 3, c = mesh.indices()[i + 2] * 3;
			float ax = vertices[a], ay = vertices[a + 1], az = vertices[a + 2];
			float bx = vertices[b], by = vertices[b + 1], bz = vertices[b + 2];
			float cx = vertices[c], cy = vertices[c + 1], cz = vertices[c + 2];

			float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
			if (Math.abs(area) < 1e-6f)
				continue; // vertical or degenerate: no footprint to stand on

			int minX = Math.max(0, cell(Math.min(ax, Math.min(bx, cx))));
			int maxX = Math.min(width - 1, cell(Math.max(ax, Math.max(bx, cx))));
			int minY = Math.max(0, cell(Math.min(ay, Math.min(by, cy))));
			int maxY = Math.min(height - 1, cell(Math.max(ay, Math.max(by, cy))));

			for (int cellY = minY; cellY <= maxY; cellY++) {
				for (int cellX = minX; cellX <= maxX; cellX++) {
					float x = centre(cellX), y = centre(cellY);
					// barycentric coordinates of the cell centre within the triangle's footprint
					float w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / area;
					float w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / area;
					float w2 = 1 - w0 - w1;
					if (w0 < 0 || w1 < 0 || w2 < 0)
						continue;
					sink.accept(cellY * width + cellX, w0 * az + w1 * bz + w2 * cz);
				}
			}
		}
	}

	private static int cell(float world) {
		return (int) (world / Heightfield.CELL_SIZE);
	}

	private static float centre(int cell) {
		return (cell + 0.5f) * Heightfield.CELL_SIZE;
	}
}
