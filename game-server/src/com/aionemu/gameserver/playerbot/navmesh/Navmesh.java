package com.aionemu.gameserver.playerbot.navmesh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A generated map, in the form the server uses at runtime: which surfaces exist, and which of them a bot may stand on.
 * <p>
 * The layout is built around two measurements. Poeta holds 39.4 million surfaces over 37.7 million columns, barely more than one each, so the grid is
 * dense and there is nothing to gain from storing columns sparsely. What did cost was bookkeeping: an int offset per column is 151 MB on its own,
 * more than the surfaces themselves. So the map is cut into tiles, each holding an offset per <em>row</em> rather than per column, and a lookup adds
 * up at most one row of counts.
 * <p>
 * The second measurement is that a whole map costs 117 MB of heap, while a bot works a camp sixty metres across, which is four tiles. Tiles are
 * therefore compressed separately and read as they are first touched, which is why opening a map is instant and costs almost nothing.
 */
public class Navmesh {

	static final String MAGIC = "AINAV3";
	/** Height resolution. Aion heights span 0..2048 m, so this fits an unsigned short with room to spare. */
	static final float Z_STEP = 0.05f;
	/** Columns per tile side. Small enough that a camp loads few tiles and a row scan is cheap, large enough that per tile overhead stays small. */
	static final int TILE_SIZE = 64;

	private final int mapId, width, height, tilesX, coarseFactor, coarseWidth;
	private final float cellSize;
	/** One bit per coarse cell, kept in memory always: it is tiny, and long routes are planned on it before any tile is read. */
	private final BitSet coarseFooting;
	private final int[] tileOffsets, tileLengths;
	private final AtomicReferenceArray<Tile> tiles;
	private final FileChannel channel;
	private final long dataStart;

	/** One square of the map. */
	private record Tile(char[] rowOffsets, byte[] counts, short[] heights, long[] walkable) {

		/** Stands for a tile with nothing in it, so an empty tile is not mistaken for one that has not been read yet. */
		static final Tile EMPTY = new Tile(null, null, null, null);
	}

	private Navmesh(int mapId, int width, int height, float cellSize, int tilesX, int coarseFactor, BitSet coarseFooting, int[] tileOffsets,
		int[] tileLengths, FileChannel channel, long dataStart) {
		this.coarseFactor = coarseFactor;
		this.coarseWidth = (width + coarseFactor - 1) / coarseFactor;
		this.coarseFooting = coarseFooting;
		this.mapId = mapId;
		this.width = width;
		this.height = height;
		this.cellSize = cellSize;
		this.tilesX = tilesX;
		this.tileOffsets = tileOffsets;
		this.tileLengths = tileLengths;
		this.tiles = new AtomicReferenceArray<>(tileOffsets.length);
		this.channel = channel;
		this.dataStart = dataStart;
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".nav");
	}

	/** Reads the header and the tile directory only. The tiles themselves are read as bots walk into them. */
	public static Navmesh open(int mapId) throws IOException {
		FileChannel channel = FileChannel.open(fileOf(mapId), StandardOpenOption.READ);
		ByteBuffer header = ByteBuffer.allocate(MAGIC.length() + 4 * 8);
		channel.read(header, 0);
		header.flip();

		byte[] magic = new byte[MAGIC.length()];
		header.get(magic);
		if (!MAGIC.equals(new String(magic))) {
			channel.close();
			throw new IOException("Not a navmesh file of this version: " + fileOf(mapId));
		}
		int storedMapId = header.getInt(), width = header.getInt(), height = header.getInt();
		float cellSize = header.getFloat();
		int tilesX = header.getInt(), tileCount = header.getInt();
		int coarseFactor = header.getInt(), coarseBytes = header.getInt();

		ByteBuffer coarse = ByteBuffer.allocate(coarseBytes);
		channel.read(coarse, header.capacity());

		ByteBuffer directory = ByteBuffer.allocate(tileCount * 8);
		channel.read(directory, header.capacity() + coarseBytes);
		directory.flip();
		int[] offsets = new int[tileCount], lengths = new int[tileCount];
		for (int i = 0; i < tileCount; i++) {
			offsets[i] = directory.getInt();
			lengths[i] = directory.getInt();
		}
		return new Navmesh(storedMapId, width, height, cellSize, tilesX, coarseFactor, BitSet.valueOf(coarse.array()), offsets, lengths, channel,
			header.capacity() + coarseBytes + directory.capacity());
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
		return tile == Tile.EMPTY ? 0 : tile.counts()[localIndex(cellX, cellY)] & 0xFF;
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

	public int coarseFactor() {
		return coarseFactor;
	}

	public int coarseWidth() {
		return coarseWidth;
	}

	public int coarseHeight() {
		return (height + coarseFactor - 1) / coarseFactor;
	}

	/** @return true if that 4 m cell holds enough walkable ground to route through. Answered without reading any tile. */
	public boolean isCoarseWalkable(int coarseX, int coarseY) {
		if (coarseX < 0 || coarseY < 0 || coarseX >= coarseWidth || coarseY >= coarseHeight())
			return false;
		return coarseFooting.get(coarseY * coarseWidth + coarseX);
	}

	public int loadedTiles() {
		int count = 0;
		for (int i = 0; i < tiles.length(); i++)
			if (tiles.get(i) != null)
				count++;
		return count;
	}

	/** @return Roughly how much heap the tiles read so far hold. */
	public long memoryFootprint() {
		long bytes = tileOffsets.length * 8L;
		for (int i = 0; i < tiles.length(); i++) {
			Tile tile = tiles.get(i);
			if (tile == null || tile == Tile.EMPTY)
				continue;
			bytes += tile.rowOffsets().length * 2L + tile.counts().length + tile.heights().length * 2L + tile.walkable().length * 8L;
		}
		return bytes;
	}

	private Tile tileOf(int cellX, int cellY) {
		int index = cellY / TILE_SIZE * tilesX + cellX / TILE_SIZE;
		Tile tile = tiles.get(index);
		if (tile == null) {
			tile = readTile(index);
			tiles.compareAndSet(index, null, tile); // two threads may read the same tile at once, which only wastes one read
		}
		return tile;
	}

	private Tile readTile(int index) {
		if (tileLengths[index] == 0)
			return Tile.EMPTY;
		try {
			ByteBuffer compressed = ByteBuffer.allocate(tileLengths[index]);
			channel.read(compressed, dataStart + tileOffsets[index]); // positional reads do not disturb other threads
			return parse(ByteBuffer.wrap(inflate(compressed.array())));
		} catch (IOException | DataFormatException e) {
			throw new IllegalStateException("Could not read tile " + index + " of navmesh " + mapId, e);
		}
	}

	private static byte[] inflate(byte[] compressed) throws DataFormatException {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(compressed);
			ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 3);
			byte[] chunk = new byte[16384];
			while (!inflater.finished()) {
				int read = inflater.inflate(chunk);
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary()))
					break;
				out.write(chunk, 0, read);
			}
			return out.toByteArray();
		} finally {
			inflater.end();
		}
	}

	private static Tile parse(ByteBuffer buffer) {
		int surfaces = buffer.getInt();
		char[] rowOffsets = new char[TILE_SIZE + 1];
		buffer.asCharBuffer().get(rowOffsets);
		buffer.position(buffer.position() + rowOffsets.length * 2);

		byte[] counts = new byte[TILE_SIZE * TILE_SIZE];
		buffer.get(counts);

		short[] heights = new short[surfaces];
		buffer.asShortBuffer().get(heights);
		buffer.position(buffer.position() + surfaces * 2);

		long[] walkable = new long[(surfaces + 63) / 64];
		buffer.asLongBuffer().get(walkable);
		return new Tile(rowOffsets, counts, heights, walkable);
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
