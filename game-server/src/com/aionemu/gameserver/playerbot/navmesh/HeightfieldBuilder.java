package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Samples a map's surfaces onto {@link Heightfield}'s grid and decides which of them a bot could stand on.
 * <p>
 * Works in two passes over the same geometry: the first counts how many surfaces each column gets, the second writes them. Counting first is what
 * allows the flat storage, and geometry is cheap to walk twice compared to keeping millions of growable lists alive.
 */
public class HeightfieldBuilder {

	/** Physical obstacles only. Skill and see-through volumes do not stop a body, and MOVEABLE geometry is ships and boxes that come and go. */
	private static final byte SOLID = (byte) (CollisionIntention.PHYSICAL.getId() | CollisionIntention.DOOR.getId());

	private final TerrainData terrain;
	private final List<PlacedMesh> solids, noWalkVolumes;
	private final int width, height;

	private HeightfieldBuilder(TerrainData terrain, List<PlacedMesh> solids, List<PlacedMesh> noWalkVolumes, int width, int height) {
		this.terrain = terrain;
		this.solids = solids;
		this.noWalkVolumes = noWalkVolumes;
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
		List<PlacedMesh> solids = new ArrayList<>(), noWalkVolumes = new ArrayList<>();
		for (GeoPlacement placement : GeoDataReader.readPlacements(mapId)) {
			List<GeoModel> parts = models.get(placement.modelName());
			if (parts == null)
				continue;
			for (GeoModel part : parts) {
				if ((part.collisionIntentions() & CollisionIntention.WALK.getId()) != 0)
					noWalkVolumes.add(place(part, placement));
				else if ((part.collisionIntentions() & SOLID) != 0)
					solids.add(place(part, placement));
			}
		}

		List<Integer> worldSize = terrain.worldSize();
		int width = (int) (worldSize.get(0) / Heightfield.CELL_SIZE);
		int height = (int) (worldSize.get(1) / Heightfield.CELL_SIZE);
		return new HeightfieldBuilder(terrain, solids, noWalkVolumes, width, height).rasterize();
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
		sample((column, z, walkable) -> counts[column + 1]++);

		int[] offsets = counts;
		for (int i = 1; i < offsets.length; i++)
			offsets[i] += offsets[i - 1];

		float[] surfaces = new float[offsets[offsets.length - 1]];
		BitSet walkable = new BitSet(surfaces.length);
		int[] cursor = offsets.clone();
		sample((column, z, isWalkable) -> {
			int index = cursor[column]++;
			surfaces[index] = z;
			walkable.set(index, isWalkable);
		});

		Heightfield field = new Heightfield(width, height, offsets, surfaces, walkable);
		field.sortColumns();
		field.applyHeadroom();
		field.block(noWalkColumns());
		return field;
	}

	/**
	 * The game authors its own invisible walls, meshes flagged {@link CollisionIntention#WALK}. The engine only consults them for npc random walk;
	 * they are exactly what tells a bot where the designers did not want anyone to go.
	 */
	private BitSet noWalkColumns() {
		BitSet blocked = new BitSet(width * height);
		for (PlacedMesh volume : noWalkVolumes)
			forEachCoveredCell(volume, (column, z, walkable) -> blocked.set(column));
		return blocked;
	}

	private interface SurfaceSink {
		void accept(int column, float z, boolean walkable);
	}

	private void sample(SurfaceSink sink) {
		float half = Heightfield.CELL_SIZE / 2;
		for (int cellY = 0; cellY < height; cellY++) {
			for (int cellX = 0; cellX < width; cellX++) {
				float x = centre(cellX), y = centre(cellY);
				float z = terrain.heightAt(x, y);
				if (Float.isNaN(z))
					continue;
				// terrain slope from the gradient across this cell, which stays inside one terrain triangle at this resolution
				float slopeX = (terrain.heightAt(x + half, y) - terrain.heightAt(x - half, y)) / Heightfield.CELL_SIZE;
				float slopeY = (terrain.heightAt(x, y + half) - terrain.heightAt(x, y - half)) / Heightfield.CELL_SIZE;
				float normalZ = 1 / (float) Math.sqrt(slopeX * slopeX + slopeY * slopeY + 1);
				sink.accept(cellY * width + cellX, z, normalZ >= Heightfield.MAX_SLOPE_COSINE);
			}
		}
		for (PlacedMesh mesh : solids)
			forEachCoveredCell(mesh, sink);
	}

	/**
	 * Drops every triangle onto the grid. A triangle contributes a surface to each column whose centre falls inside its footprint, at the height of
	 * its plane there. Walls project to a line and so contribute almost nothing, which is exactly right: you cannot stand on a wall.
	 */
	private void forEachCoveredCell(PlacedMesh mesh, SurfaceSink sink) {
		float[] vertices = mesh.vertices();
		for (int i = 0; i < mesh.indices().length; i += 3) {
			int a = mesh.indices()[i] * 3, b = mesh.indices()[i + 1] * 3, c = mesh.indices()[i + 2] * 3;
			float ax = vertices[a], ay = vertices[a + 1], az = vertices[a + 2];
			float bx = vertices[b], by = vertices[b + 1], bz = vertices[b + 2];
			float cx = vertices[c], cy = vertices[c + 1], cz = vertices[c + 2];

			float footprint = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
			if (Math.abs(footprint) < 1e-6f)
				continue; // vertical or degenerate: no footprint to stand on

			boolean walkable = isGentleEnough(ax, ay, az, bx, by, bz, cx, cy, cz);
			int minX = Math.max(0, cell(Math.min(ax, Math.min(bx, cx))));
			int maxX = Math.min(width - 1, cell(Math.max(ax, Math.max(bx, cx))));
			int minY = Math.max(0, cell(Math.min(ay, Math.min(by, cy))));
			int maxY = Math.min(height - 1, cell(Math.max(ay, Math.max(by, cy))));

			for (int cellY = minY; cellY <= maxY; cellY++) {
				for (int cellX = minX; cellX <= maxX; cellX++) {
					float x = centre(cellX), y = centre(cellY);
					// barycentric coordinates of the cell centre within the triangle's footprint
					float w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / footprint;
					float w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / footprint;
					float w2 = 1 - w0 - w1;
					if (w0 < 0 || w1 < 0 || w2 < 0)
						continue;
					sink.accept(cellY * width + cellX, w0 * az + w1 * bz + w2 * cz, walkable);
				}
			}
		}
	}

	/** @return true if the triangle's face is flat enough to stand on. */
	private static boolean isGentleEnough(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
		float ux = bx - ax, uy = by - ay, uz = bz - az;
		float vx = cx - ax, vy = cy - ay, vz = cz - az;
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return length > 0 && Math.abs(nz) / length >= Heightfield.MAX_SLOPE_COSINE;
	}

	private static int cell(float world) {
		return (int) (world / Heightfield.CELL_SIZE);
	}

	private static float centre(int cell) {
		return (cell + 0.5f) * Heightfield.CELL_SIZE;
	}
}
