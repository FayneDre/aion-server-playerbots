package com.aionemu.gameserver.playerbot.navmesh;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

/**
 * A generated map, in the form the server uses at runtime: which surfaces exist, and which of them a bot may stand on.
 * <p>
 * The layout is built around one measurement. Poeta holds 39.4 million surfaces over 37.7 million columns, barely more than one each, so the grid is
 * dense and there is nothing to gain from storing columns sparsely. What did cost was bookkeeping: an int offset per column is 151 MB on its own,
 * more than the surfaces themselves.
 * <p>
 * So the map is cut into tiles, each holding an offset per <em>row</em> rather than per column, and a lookup adds up at most one row of counts. That
 * turns 151 MB of offsets into 1 MB, and heights quantized to {@value #Z_STEP} m halve what is left.
 */
public class Navmesh {

	static final String MAGIC = "AINAV1";
	/** Height resolution. Aion heights span 0..2048 m, so this fits an unsigned short with room to spare. */
	static final float Z_STEP = 0.05f;
	/** Columns per tile side. Small enough that a row scan is cheap, large enough that per tile overhead stays negligible. */
	static final int TILE_SIZE = 64;

	private final int mapId, width, height, tilesX;
	private final float cellSize;
	private final Tile[] tiles;

	/** One square of the map. Null entries are tiles with nothing in them at all. */
	private record Tile(char[] rowOffsets, byte[] counts, short[] heights, long[] walkable) {
	}

	private Navmesh(int mapId, int width, int height, float cellSize, int tilesX, Tile[] tiles) {
		this.mapId = mapId;
		this.width = width;
		this.height = height;
		this.cellSize = cellSize;
		this.tilesX = tilesX;
		this.tiles = tiles;
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".nav");
	}

	public static Navmesh load(int mapId) throws IOException {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(fileOf(mapId)))))) {
			byte[] magic = new byte[MAGIC.length()];
			in.readFully(magic);
			if (!MAGIC.equals(new String(magic)))
				throw new IOException("Not a navmesh file: " + fileOf(mapId));

			int storedMapId = in.readInt(), width = in.readInt(), height = in.readInt();
			float cellSize = in.readFloat();
			int tilesX = in.readInt(), tileCount = in.readInt();
			Tile[] tiles = new Tile[tileCount];
			for (int i = 0; i < tileCount; i++)
				tiles[i] = readTile(in);
			return new Navmesh(storedMapId, width, height, cellSize, tilesX, tiles);
		}
	}

	private static Tile readTile(DataInputStream in) throws IOException {
		int surfaces = in.readInt();
		if (surfaces == 0)
			return null;
		int walkableWords = (surfaces + 63) / 64;
		// one bulk read and a view per array: reading these million values one call at a time is what made loading slow
		byte[] payload = new byte[(TILE_SIZE + 1) * 2 + TILE_SIZE * TILE_SIZE + surfaces * 2 + walkableWords * 8];
		in.readFully(payload);
		ByteBuffer buffer = ByteBuffer.wrap(payload);

		char[] rowOffsets = new char[TILE_SIZE + 1];
		buffer.asCharBuffer().get(rowOffsets);
		buffer.position(buffer.position() + rowOffsets.length * 2);

		byte[] counts = new byte[TILE_SIZE * TILE_SIZE];
		buffer.get(counts);

		short[] heights = new short[surfaces];
		buffer.asShortBuffer().get(heights);
		buffer.position(buffer.position() + surfaces * 2);

		long[] walkable = new long[walkableWords];
		buffer.asLongBuffer().get(walkable);
		return new Tile(rowOffsets, counts, heights, walkable);
	}

	public int mapId() {
		return mapId;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public float cellSize() {
		return cellSize;
	}

	public int cellX(float worldX) {
		return (int) (worldX / cellSize);
	}

	public int cellY(float worldY) {
		return (int) (worldY / cellSize);
	}

	public boolean contains(int cellX, int cellY) {
		return cellX >= 0 && cellY >= 0 && cellX < width && cellY < height;
	}

	/** @return How many surfaces sit above that column. */
	public int surfaceCount(int cellX, int cellY) {
		Tile tile = tileOf(cellX, cellY);
		return tile == null ? 0 : tile.counts()[localIndex(cellX, cellY)] & 0xFF;
	}

	/** @return The height of the given surface of that column, counted from the lowest. */
	public float surfaceZ(int cellX, int cellY, int surface) {
		Tile tile = tileOf(cellX, cellY);
		return (tile.heights()[columnStart(tile, cellX, cellY) + surface] & 0xFFFF) * Z_STEP;
	}

	public boolean isWalkable(int cellX, int cellY, int surface) {
		Tile tile = tileOf(cellX, cellY);
		int index = columnStart(tile, cellX, cellY) + surface;
		return (tile.walkable()[index >> 6] & 1L << index) != 0;
	}

	/** @return true if a bot could stand anywhere in that column. */
	public boolean hasFooting(int cellX, int cellY) {
		for (int i = surfaceCount(cellX, cellY) - 1; i >= 0; i--)
			if (isWalkable(cellX, cellY, i))
				return true;
		return false;
	}

	/** @return Roughly how much heap this map holds, which is what decides how many can stay loaded at once. */
	public long memoryFootprint() {
		long bytes = 0;
		for (Tile tile : tiles) {
			if (tile == null)
				continue;
			bytes += tile.rowOffsets().length * 2L + tile.counts().length + tile.heights().length * 2L + tile.walkable().length * 8L;
		}
		return bytes;
	}

	private Tile tileOf(int cellX, int cellY) {
		return tiles[cellY / TILE_SIZE * tilesX + cellX / TILE_SIZE];
	}

	private static int localIndex(int cellX, int cellY) {
		return cellY % TILE_SIZE * TILE_SIZE + cellX % TILE_SIZE;
	}

	/** Walks one row of counts, which is what buys the small offset tables. */
	private static int columnStart(Tile tile, int cellX, int cellY) {
		int localY = cellY % TILE_SIZE, localX = cellX % TILE_SIZE;
		int start = tile.rowOffsets()[localY];
		for (int x = 0; x < localX; x++)
			start += tile.counts()[localY * TILE_SIZE + x] & 0xFF;
		return start;
	}
}
