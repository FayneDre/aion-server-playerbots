package com.aionemu.gameserver.playerbot.navmesh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>
 * Read lazily and <em>given back</em>, which was the half that was missing: nothing released a tile and nothing closed a map, so what a map held was
 * not what its bots were using but everywhere they had ever been. See {@link #TILE_BUDGET_BYTES}.
 */
public class Navmesh {

	static final String MAGIC = "AINAV8";
	/**
	 * Finest height resolution worth storing. A map uses it unless it is too tall for a short to reach its ceiling at this step, in which case it gets
	 * a coarser one of its own, written in its header beside the origin.
	 * <p>
	 * A fixed step was an assumption about how tall a map can be, and it was wrong twice over: Eltnen has ground below the step's zero, and Kaldor — a
	 * map of floating islands — is taller than the 3276 m a short reaches at this step, so 20269 of its surfaces were written at its ceiling instead
	 * of above it. Both are one mistake: a range decided in advance for data nobody had looked at.
	 */
	static final float FINEST_Z_STEP = 0.05f;
	/** Heights are an unsigned short, so a map's span is cut into this many steps at most. */
	static final int Z_LEVELS = 0xFFFF;
	/** Columns per tile side. Small enough that a camp loads few tiles and a row scan is cheap, large enough that per tile overhead stays small. */
	static final int TILE_SIZE = 64;
	/**
	 * Most surfaces one column of the grid can hold, because the per column count is a byte.
	 * <p>
	 * A column taller than this is clipped at the top on the way out. It was clipped silently while the round trip check compared against the
	 * unclipped count, so a mesh could have been written and then refused for doing what the writer decided to do. Measured since: <b>no map clips a
	 * single column</b>, so nothing has ever been lost to it — but the count is now reported, because a limit nobody can see is one nobody will
	 * remember when a map finally reaches it.
	 * <p>
	 * Widening the count to a {@code char} would remove the limit at the cost of 37 MB a map, which is not worth paying for a case that does not
	 * occur.
	 */
	static final int MAX_SURFACES_PER_COLUMN = 255;
	/**
	 * Bytes of tiles one map keeps before it starts giving the oldest ones back.
	 * <p>
	 * Reading tiles lazily was only half the measurement. Nothing gave one back, and nothing closed a map, so a map's cost was not what its bots were
	 * using but everything they had ever walked over: measured at +46 MB in twelve minutes with a hundred bots roaming, on its way to the 117 MB a
	 * whole map costs, times every map anybody visits. Lazily read and never released is not a budget, it is a slower way of loading everything.
	 * <p>
	 * The figure is set against what the readers actually need at once, not against what is spare. A tile is about 20 KB, so this holds some 1600 of
	 * them; one path search refines over at most a few dozen, and the twelve threads that plan them share one map, so the live working set is a few
	 * hundred KB and the margin against thrashing is two orders of magnitude. Per map rather than global, which is the one compromise here: eighteen
	 * open maps would allow 576 MB, where the two or three that actually hold bots come to 64-96 MB.
	 */
	private static final long TILE_BUDGET_BYTES = 32L << 20;

	private final int mapId, width, height, tilesX, coarseFactor, coarseWidth;
	private final float cellSize;
	/**
	 * The height the map's quantised zero stands for, in metres.
	 * <p>
	 * Heights used to be quantised from an assumed zero and clamped at it, so every surface below sea level was written as if it were at sea level.
	 * It cost nothing on a flat valley and quietly falsified seven of the eighteen open maps — Eltnen alone had 13580 surfaces wrong, 0.03% of the
	 * map, which is few enough to look like a rounding complaint and quite enough to drop a bot through a ravine floor. Measuring from the map's own
	 * lowest surface costs four bytes in the header and removes the assumption.
	 */
	private final float zOrigin;
	/** Metres per quantisation step on this map, {@link #FINEST_Z_STEP} unless the map is too tall for that to reach its ceiling. */
	private final float zStep;
	/**
	 * The regions passing through each coarse cell, kept in memory always: it is small, and long routes are planned on it before any tile is read. A
	 * region is a stretch of ground a body can actually walk across, so a rough route that stays inside one is a route that can be refined.
	 * <p>
	 * Several per cell, flattened as a column's surfaces are: 4 m of map can hold two floors of ground, and keeping only the commonest of them hid
	 * the lower one entirely — which is how a camp in a hollow became a place the planner refused every route to.
	 */
	private final int[] coarseRegionOffsets, coarseRegionIds;
	private final int[] tileOffsets, tileLengths;
	private final AtomicReferenceArray<Tile> tiles;
	/**
	 * One per tile, set when it is touched and cleared when the hand passes it: a tile the hand finds already clear is given back.
	 * <p>
	 * A second-chance clock rather than a true LRU, because the pattern it has to serve is the one the class was built around — a bot works a camp of
	 * a handful of tiles — and the clock protects exactly that working set while costing one write per lookup and allocating nothing. A true LRU would
	 * want a concurrent ordering structure touched on every hit, which is the dearest part of a cache whose hits must stay cheap.
	 */
	private final AtomicIntegerArray recentlyUsed;
	/** What {@link #tiles} currently holds, so the budget is checked without walking the whole directory on every read. */
	private final AtomicLong tileBytes = new AtomicLong();
	/** Where the clock's hand stands. Guarded by {@link #evictionLock}, the only place it is read or written. */
	private int clockHand;
	/** Held only while giving tiles back, so the readers that fill the cache never contend with each other. */
	private final Object evictionLock = new Object();
	private final FileChannel channel;
	private final long dataStart;

	/** One square of the map. */
	/**
	 * One square of the grid. {@code rowOffsets} is int rather than char because a tile of 64x64 columns can hold up to a million surfaces, and a
	 * 16 bit offset wraps silently at 65535: every column of an overflowed tile then read somebody else's surfaces, which came back as heights
	 * hundreds of metres out. Dense vertical maps — floating islands, canyons — were the only ones that reached it, which is why fifteen maps were
	 * perfect and seven were not. The width costs 130 bytes a tile.
	 */
	private record Tile(int[] rowOffsets, byte[] counts, short[] heights, long[] walkable) {

		/** Stands for a tile with nothing in it, so an empty tile is not mistaken for one that has not been read yet. */
		static final Tile EMPTY = new Tile(null, null, null, null);
	}

	private Navmesh(int mapId, int width, int height, float cellSize, float zOrigin, float zStep, int tilesX, int coarseFactor, int[] coarseRegionOffsets,
		int[] coarseRegionIds, int[] tileOffsets, int[] tileLengths, FileChannel channel, long dataStart) {
		this.zOrigin = zOrigin;
		this.zStep = zStep;
		this.coarseFactor = coarseFactor;
		this.coarseWidth = (width + coarseFactor - 1) / coarseFactor;
		this.coarseRegionOffsets = coarseRegionOffsets;
		this.coarseRegionIds = coarseRegionIds;
		this.mapId = mapId;
		this.width = width;
		this.height = height;
		this.cellSize = cellSize;
		this.tilesX = tilesX;
		this.tileOffsets = tileOffsets;
		this.tileLengths = tileLengths;
		this.tiles = new AtomicReferenceArray<>(tileOffsets.length);
		this.recentlyUsed = new AtomicIntegerArray(tileOffsets.length);
		this.channel = channel;
		this.dataStart = dataStart;
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".nav");
	}

	/** Reads the header and the tile directory only. The tiles themselves are read as bots walk into them. */
	public static Navmesh open(int mapId) throws IOException {
		FileChannel channel = FileChannel.open(fileOf(mapId), StandardOpenOption.READ);
		ByteBuffer header = ByteBuffer.allocate(MAGIC.length() + 4 * 10);
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
		float zOrigin = header.getFloat(), zStep = header.getFloat();

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
		int[] flat = new int[coarseBytes / 4];
		coarse.flip();
		coarse.asIntBuffer().get(flat);
		// the offsets come first and their count follows from the map's own size, so the block needs no length of its own
		int cells = ((width + coarseFactor - 1) / coarseFactor) * ((height + coarseFactor - 1) / coarseFactor);
		int[] regionOffsets = Arrays.copyOfRange(flat, 0, cells + 1);
		int[] regionIds = Arrays.copyOfRange(flat, cells + 1, flat.length);
		return new Navmesh(storedMapId, width, height, cellSize, zOrigin, zStep, tilesX, coarseFactor, regionOffsets, regionIds, offsets, lengths, channel,
			header.capacity() + coarseBytes + directory.capacity());
	}

	public int mapId() {
		return mapId;
	}

	/** @return Metres per quantisation step, which is how far a height read back may legitimately differ from the one written. */
	public float zStep() {
		return zStep;
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
		return zOrigin + (tile.heights()[columnStart(tile, cellX, cellY) + surface] & 0xFFFF) * zStep;
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
		return coarseRegionCount(coarseX, coarseY) > 0;
	}

	/** @return How many stretches of walkable ground pass through this coarse cell, 0 where too little of it can be stood on. */
	public int coarseRegionCount(int coarseX, int coarseY) {
		if (coarseX < 0 || coarseY < 0 || coarseX >= coarseWidth || coarseY >= coarseHeight())
			return 0;
		int cell = coarseY * coarseWidth + coarseX;
		return coarseRegionOffsets[cell + 1] - coarseRegionOffsets[cell];
	}

	/** @return One of the stretches of walkable ground passing through this coarse cell. */
	public int coarseRegion(int coarseX, int coarseY, int index) {
		return coarseRegionIds[coarseRegionOffsets[coarseY * coarseWidth + coarseX] + index];
	}

	/** @return true if that stretch of ground passes through this coarse cell, which is what a rough route follows. */
	public boolean coarseHasRegion(int coarseX, int coarseY, int region) {
		int count = coarseRegionCount(coarseX, coarseY);
		for (int index = 0; index < count; index++) {
			if (coarseRegion(coarseX, coarseY, index) == region)
				return true;
		}
		return false;
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
		for (int i = 0; i < tiles.length(); i++)
			bytes += sizeOf(tiles.get(i));
		return bytes;
	}

	/**
	 * @return Roughly what this tile holds, and nothing for one that has not been read or has nothing in it.
	 *         <p>
	 *         {@code rowOffsets} counts four bytes an entry and not two. It was counted as two, which is what the field held before an overflow forced
	 *         it to int, so every report since has understated a tile by 260 bytes — harmless while the number was only printed, and not once a budget
	 *         is decided by it.
	 */
	private static long sizeOf(Tile tile) {
		if (tile == null || tile == Tile.EMPTY)
			return 0;
		return tile.rowOffsets().length * 4L + tile.counts().length + tile.heights().length * 2L + tile.walkable().length * 8L;
	}

	private Tile tileOf(int cellX, int cellY) {
		int index = cellY / TILE_SIZE * tilesX + cellX / TILE_SIZE;
		Tile tile = tiles.get(index);
		if (tile == null) {
			tile = readTile(index);
			if (tiles.compareAndSet(index, null, tile)) { // two threads may read the same tile at once, which only wastes one read
				tileBytes.addAndGet(sizeOf(tile));
				evictDownToBudget();
			}
		}
		// on hits as well as misses: a tile nobody marks is a tile the hand gives back, however busy the camp standing on it
		recentlyUsed.lazySet(index, 1);
		return tile;
	}

	/**
	 * Gives tiles back until the map is inside its budget, oldest first.
	 * <p>
	 * Safe to do under the readers' feet because a tile is immutable and {@link #tileOf} hands out the reference it loaded: a thread already holding
	 * one keeps working from it, and the worst an eviction costs is that the next lookup reads it from the file again.
	 */
	private void evictDownToBudget() {
		if (tileBytes.get() <= TILE_BUDGET_BYTES)
			return;
		synchronized (evictionLock) {
			// bounded at two passes of the hand, so a burst of readers cannot turn eviction into an unbounded scan. Two rather than one because the
			// first pass may spend itself entirely on clearing marks, which is the clock giving every tile its second chance.
			int steps = tiles.length() * 2;
			while (tileBytes.get() > TILE_BUDGET_BYTES && steps-- > 0) {
				int index = clockHand;
				clockHand = clockHand + 1 == tiles.length() ? 0 : clockHand + 1;
				Tile tile = tiles.get(index);
				if (tile == null || tile == Tile.EMPTY)
					continue; // nothing to give back, and an empty tile costs nothing to keep but a read to find out again
				if (recentlyUsed.get(index) != 0) {
					recentlyUsed.lazySet(index, 0);
					continue;
				}
				if (tiles.compareAndSet(index, tile, null))
					tileBytes.addAndGet(-sizeOf(tile));
			}
		}
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
		int[] rowOffsets = new int[TILE_SIZE + 1];
		buffer.asIntBuffer().get(rowOffsets);
		buffer.position(buffer.position() + rowOffsets.length * 4);

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
