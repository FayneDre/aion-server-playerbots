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

	/**
	 * What stops a body, taken from the engine's own movement mask instead of being restated here, so a generated map cannot describe different
	 * geometry from the raycasts the server walks bots by.
	 * <p>
	 * It was restated once, as physical and doors only, on the assumption that see-through volumes do not stop anyone. They do:
	 * {@code DEFAULT_COLLISIONS} includes {@code PHYSICAL_SEE_THROUGH}, which is how fences, railings and stacked crates are flagged — solid to
	 * walk into, transparent to look through. Leaving them out made routes run straight through palisades the engine then refused to let anyone
	 * cross, and a bot following such a route wedged itself in the fence it could not see coming.
	 */
	private static final int SOLID = CollisionIntention.DEFAULT_COLLISIONS.getId() & 0xFF;
	/**
	 * Tallest vertical face a body simply steps over rather than walks around, matching the climb the route search itself allows between two
	 * neighbouring cells. Below it lies the rise of a stair, a kerb, a root; above it, a fence.
	 */
	private static final float STEPPABLE_RISE = 0.6f;

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
				// masked to a byte's worth of bits: the intentions are stored in a byte, and PHYSICAL_SEE_THROUGH is its sign bit
				int intentions = part.collisionIntentions() & 0xFF;
				if ((intentions & CollisionIntention.WALK.getId()) != 0)
					noWalkVolumes.add(place(part, placement));
				else if ((intentions & SOLID) != 0)
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
		field.block(blockedColumns());
		field.erode(Heightfield.AGENT_RADIUS_CELLS);
		return field;
	}

	/**
	 * Every column something stands across: the invisible walls the game authors itself (meshes flagged {@link CollisionIntention#WALK}, which the
	 * engine only consults for npc random walk and which say exactly where the designers did not want anyone to go), and the walls of ordinary
	 * solid geometry.
	 * <p>
	 * This cannot go through {@link #forEachCoveredCell}, which answers a different question. That one asks what a body can stand on, so it drops
	 * triangles with no footprint — correctly, since nobody stands on a wall. But a fence, a palisade, a railing, the side of a crate: those are
	 * all walls, all vertical, all dropped, and a map built that way says the ground through a barricade is open. Routes then ran straight through
	 * fences the engine refused to let anyone cross, and a bot following one wedged itself into the timber it never saw coming.
	 */
	private BitSet blockedColumns() {
		BitSet blocked = new BitSet(width * height);
		for (PlacedMesh volume : noWalkVolumes)
			markFootprint(volume, false, blocked); // authored no-walk volumes block everything they cover, whichever way their faces point
		for (PlacedMesh solid : solids)
			markFootprint(solid, true, blocked);
		return blocked;
	}

	/**
	 * Marks every cell a mesh's triangles cross, by filling the ones their footprint contains and then walking their edges — which is all a
	 * vertical triangle has to offer.
	 *
	 * @param wallsOnly Keep only faces tall enough to stop a body. Floors, roofs and the rise of a step are what a bot walks on and over, so
	 *          blocking those would wall it into every staircase in the game.
	 */
	private void markFootprint(PlacedMesh mesh, boolean wallsOnly, BitSet blocked) {
		float[] vertices = mesh.vertices();
		for (int i = 0; i < mesh.indices().length; i += 3) {
			int a = mesh.indices()[i] * 3, b = mesh.indices()[i + 1] * 3, c = mesh.indices()[i + 2] * 3;
			float ax = vertices[a], ay = vertices[a + 1], az = vertices[a + 2];
			float bx = vertices[b], by = vertices[b + 1], bz = vertices[b + 2];
			float cx = vertices[c], cy = vertices[c + 1], cz = vertices[c + 2];

			if (wallsOnly) {
				float rise = Math.max(az, Math.max(bz, cz)) - Math.min(az, Math.min(bz, cz));
				if (rise < STEPPABLE_RISE)
					continue;
			}
			float footprint = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
			if (Math.abs(footprint) >= 1e-6f)
				fillFootprint(ax, ay, bx, by, cx, cy, footprint, blocked);
			markEdge(ax, ay, bx, by, blocked);
			markEdge(bx, by, cx, cy, blocked);
			markEdge(cx, cy, ax, ay, blocked);
		}
	}

	private void fillFootprint(float ax, float ay, float bx, float by, float cx, float cy, float footprint, BitSet blocked) {
		int minX = Math.max(0, cell(Math.min(ax, Math.min(bx, cx))));
		int maxX = Math.min(width - 1, cell(Math.max(ax, Math.max(bx, cx))));
		int minY = Math.max(0, cell(Math.min(ay, Math.min(by, cy))));
		int maxY = Math.min(height - 1, cell(Math.max(ay, Math.max(by, cy))));
		for (int cellY = minY; cellY <= maxY; cellY++) {
			for (int cellX = minX; cellX <= maxX; cellX++) {
				float x = centre(cellX), y = centre(cellY);
				float w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / footprint;
				float w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / footprint;
				if (w0 >= 0 && w1 >= 0 && 1 - w0 - w1 >= 0)
					blocked.set(cellY * width + cellX);
			}
		}
	}

	/** Walks a triangle edge across the grid, so geometry too thin to contain any cell centre still blocks the cells it crosses. */
	private void markEdge(float x0, float y0, float x1, float y1, BitSet blocked) {
		float dx = x1 - x0, dy = y1 - y0;
		int steps = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / (Heightfield.CELL_SIZE / 2)));
		for (int step = 0; step <= steps; step++) {
			float ratio = (float) step / steps;
			int cellX = cell(x0 + dx * ratio), cellY = cell(y0 + dy * ratio);
			if (cellX >= 0 && cellY >= 0 && cellX < width && cellY < height)
				blocked.set(cellY * width + cellX);
		}
	}

	private interface SurfaceSink {
		void accept(int column, float z, boolean walkable);
	}

	private void sample(SurfaceSink sink) {
		float half = Heightfield.SLOPE_WINDOW / 2;
		for (int cellY = 0; cellY < height; cellY++) {
			for (int cellX = 0; cellX < width; cellX++) {
				float x = centre(cellX), y = centre(cellY);
				float z = terrain.heightAt(x, y);
				if (Float.isNaN(z))
					continue;
				// slope measured over a stride rather than a cell: the heightmap is defined every two metres, so a half metre window reports the
				// steepest single triangle and severs a walkable hillside wherever one triangle happens to tip past the limit
				float slopeX = (terrain.heightAt(x + half, y) - terrain.heightAt(x - half, y)) / Heightfield.SLOPE_WINDOW;
				float slopeY = (terrain.heightAt(x, y + half) - terrain.heightAt(x, y - half)) / Heightfield.SLOPE_WINDOW;
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
