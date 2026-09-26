package com.aionemu.gameserver.playerbot.navmesh;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * The terrain heightmap of one map, read from its PNG.
 * <p>
 * Mirrors {@link com.aionemu.gameserver.geoEngine.models.Terrain}, which is the reference: points sit on a 2 m grid, heights are 16 bit values
 * scaled into 0..2048, the whole perimeter is forced to 0, and a raw value of 0xFFFF means there is no ground at all.
 */
public class TerrainData {

	public static final int UNIT_SIZE = 2;
	private static final int MAX_Z_EXCLUSIVE = 2048;

	private final short[] heightmap;
	private final int xSize, ySize;

	private TerrainData(short[] heightmap, int xSize, int ySize) {
		this.heightmap = heightmap;
		this.xSize = xSize;
		this.ySize = ySize;
	}

	/**
	 * @return The terrain of that map, or null if it ships none. One file can serve several maps, its name listing their ids separated by commas.
	 */
	public static TerrainData load(int mapId) throws IOException {
		Path file = findFile(mapId);
		if (file == null)
			return null;
		BufferedImage image = ImageIO.read(file.toFile());
		if (!(image.getRaster().getDataBuffer() instanceof DataBufferUShort heightmap))
			return null; // material map, not a heightmap
		return new TerrainData(heightmap.getData(), image.getWidth(), image.getHeight());
	}

	private static Path findFile(int mapId) throws IOException {
		try (var files = Files.list(GeoDataReader.GEO_DIR)) {
			return files.filter(path -> {
				String name = path.getFileName().toString();
				return name.endsWith(".png")
					&& Arrays.stream(name.substring(0, name.length() - 4).split(",")).anyMatch(id -> id.startsWith(String.valueOf(mapId)));
			}).findFirst().orElse(null);
		}
	}

	/** @return The extent of the terrain in metres, as {x, y}. */
	public List<Integer> worldSize() {
		return List.of(xSize * UNIT_SIZE, ySize * UNIT_SIZE);
	}

	/**
	 * @return The ground height at that position, or {@link Float#NaN} where there is none. The two triangles the game renders per grid square are
	 *         reproduced exactly, so a slope reads the same here as in game.
	 */
	public float heightAt(float x, float y) {
		int xIndex = (int) (x / UNIT_SIZE), yIndex = (int) (y / UNIT_SIZE);
		float z1 = pointHeight(xIndex, yIndex), z2 = pointHeight(xIndex, yIndex + 1);
		float z3 = pointHeight(xIndex + 1, yIndex), z4 = pointHeight(xIndex + 1, yIndex + 1);

		// local coordinates inside the square, both in 0..1
		float u = x / UNIT_SIZE - xIndex, v = y / UNIT_SIZE - yIndex;
		// the game splits each square along the p2-p3 diagonal, into (p1, p2, p3) and (p2, p3, p4)
		if (u + v <= 1)
			return Float.isNaN(z1) ? Float.NaN : z1 + (z2 - z1) * v + (z3 - z1) * u;
		return Float.isNaN(z4) ? Float.NaN : z4 + (z2 - z4) * (1 - u) + (z3 - z4) * (1 - v);
	}

	private float pointHeight(int xIndex, int yIndex) {
		if (xIndex < 0 || yIndex < 0 || xIndex > xSize || yIndex > ySize)
			return Float.NaN;
		if (xIndex == 0 || yIndex == 0 || xIndex == xSize || yIndex == ySize)
			return 0; // the rendered terrain always falls to zero on its perimeter
		short raw = heightmap[heightmap.length == 1 ? 0 : yIndex + xIndex * ySize];
		return raw == -1 ? Float.NaN : Short.toUnsignedInt(raw) * MAX_Z_EXCLUSIVE / (0xFFFF + 1f);
	}
}
