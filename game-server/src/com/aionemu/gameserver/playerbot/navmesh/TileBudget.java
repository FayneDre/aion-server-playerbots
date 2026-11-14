package com.aionemu.gameserver.playerbot.navmesh;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;

/**
 * One budget for the tiles of every open navmesh, where each map used to have its own.
 * <p>
 * A budget per map put the memory where maps were open, not where bots walked: 32 MB each, a map gave tiles back only once it passed its own 32, so a
 * map walked once kept them for good, and 26 open maps came to 603 MB of tile heights on 5 November — a third of the live set, while two hundred
 * more awake bots cost 30 MB. One budget for all of them, with the maps' clocks turned in turn when the total passes it, gives back first the tiles
 * nobody has touched since the hand last passed, on whichever map they are: a map nobody walks loses its tiles to one that is busy.
 * <p>
 * The budget is a share of the heap ({@code gameserver.playerbot.navmesh.tile_budget}, in percent), so a smaller machine holds fewer tiles as it
 * holds fewer bots. Giving back starts above the budget and stops at {@link #FLOOR} of it, so it runs now and then rather than at every tile read.
 * A tile given back costs a read of a few kilobytes from the file when a bot walks onto it again; how many are read and given back is logged every
 * {@link #REPORT_MILLIS}, which is the figure that says whether the budget is too tight.
 */
final class TileBudget {

	private static final Logger log = LoggerFactory.getLogger(TileBudget.class);

	/** Where giving back stops, as a share of the budget. */
	private static final double FLOOR = 0.95;
	/** How often the reads and the giving back are reported, at most: only when a tile is read, so a quiet server says nothing. */
	private static final long REPORT_MILLIS = 300_000;

	static final TileBudget INSTANCE = new TileBudget();

	private final List<Navmesh> meshes = new CopyOnWriteArrayList<>();
	private final AtomicLong bytes = new AtomicLong();
	private final AtomicLong reads = new AtomicLong(), readNanos = new AtomicLong(), givenBack = new AtomicLong();
	/** Which map's clock turns next. Written only by the thread holding {@link #giving}. */
	private int next;
	/**
	 * Set while a thread gives tiles back. The others pass on rather than wait: the one at work takes the total under the floor for all of them, and a
	 * route thread blocked behind it would only find, once let in, that nothing is left to do.
	 */
	private final AtomicBoolean giving = new AtomicBoolean();
	private volatile long lastReport = System.currentTimeMillis();
	/** A budget in bytes set by a test, in place of the share of the heap; 0 when none is. */
	private volatile long testLimit;

	private TileBudget() {
	}

	/** @return The budget in bytes, read each time so a smaller heap or a changed property takes effect at once. */
	long limit() {
		long forTest = testLimit;
		return forTest > 0 ? forTest : Runtime.getRuntime().maxMemory() / 100 * Math.clamp(PlayerBotConfig.NAVMESH_TILE_BUDGET, 1, 100);
	}

	/** Sets the budget to a number of bytes, for a test that cannot fill a share of the heap with meshes of a few tiles; 0 goes back to the share. */
	void setLimitForTests(long bytes) {
		testLimit = bytes;
	}

	void opened(Navmesh mesh) {
		meshes.add(mesh);
	}

	void closed(Navmesh mesh, long tileBytes) {
		meshes.remove(mesh);
		bytes.addAndGet(-tileBytes);
	}

	/**
	 * A tile was read into a map: counted with the time its read and inflating took, and tiles given back across the maps if the total is now over
	 * the budget. The time is what a budget too tight costs, in threads planning routes.
	 */
	void read(long tileBytes, long nanos) {
		reads.incrementAndGet();
		readNanos.addAndGet(nanos);
		if (bytes.addAndGet(tileBytes) > limit())
			giveBack();
		report();
	}

	private void giveBack() {
		if (!giving.compareAndSet(false, true))
			return;
		try {
			long floor = (long) (limit() * FLOOR);
			// the maps as they are now: a map closed meanwhile is still turned once, which only gives back tiles nobody will read again
			Navmesh[] open = meshes.toArray(new Navmesh[0]);
			// every map's clock turned at most twice round all of them: a first turn may only clear marks, which is each tile's second chance
			for (int turns = open.length * 2; bytes.get() > floor && turns > 0; turns--) {
				next = (next + 1) % open.length;
				long freed = open[next].giveBack(bytes.get() - floor);
				if (freed > 0) {
					bytes.addAndGet(-freed);
					givenBack.addAndGet(freed);
				}
			}
		} finally {
			giving.set(false);
		}
	}

	private void report() {
		long now = System.currentTimeMillis();
		if (now - lastReport < REPORT_MILLIS)
			return;
		lastReport = now;
		log.info("Navmesh tiles: {} MB held of a {} MB budget across {} map(s); {} tile(s) read in {} ms and {} MB given back in the last {} min",
			bytes.get() >> 20, limit() >> 20, meshes.size(), reads.getAndSet(0), readNanos.getAndSet(0) / 1_000_000, givenBack.getAndSet(0) >> 20,
			REPORT_MILLIS / 60_000);
	}

	/** @return The tiles held across every map, in bytes. */
	long held() {
		return bytes.get();
	}
}
