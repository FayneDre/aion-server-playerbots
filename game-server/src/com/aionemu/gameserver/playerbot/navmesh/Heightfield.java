package com.aionemu.gameserver.playerbot.navmesh;

import java.util.BitSet;

/**
 * Every surface of a map, sampled on a regular grid, each marked walkable or not.
 * <p>
 * A column holds the heights of all surfaces above that spot, sorted low to high: the ground, then the floor of a bridge, then its underside, and so
 * on.
 * <p>
 * Stored as flat arrays with an offset per column rather than a list per column: a 3 km map has thirty eight million columns, so an object per
 * column is out of the question.
 */
public class Heightfield {

	public static final float CELL_SIZE = 0.5f;
	/** Head room a bot needs to stand. Anything lower is a crawl space, not a floor. */
	public static final float AGENT_HEIGHT = 2f;
	/** Steepest ground a bot may walk on, matching the 45° the engine's own movement probe allows. */
	public static final float MAX_SLOPE_COSINE = (float) Math.cos(Math.toRadians(45));

	private final int width, height;
	private final int[] offsets; // width * height + 1 entries, so a column's samples are offsets[i] .. offsets[i + 1]
	private final float[] surfaces;
	private final BitSet walkable;

	Heightfield(int width, int height, int[] offsets, float[] surfaces, BitSet walkable) {
		this.width = width;
		this.height = height;
		this.offsets = offsets;
		this.surfaces = surfaces;
		this.walkable = walkable;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int surfaceCount() {
		return surfaces.length;
	}

	public int walkableCount() {
		return walkable.cardinality();
	}

	public int columnStart(int cellX, int cellY) {
		return offsets[cellY * width + cellX];
	}

	public int columnEnd(int cellX, int cellY) {
		return offsets[cellY * width + cellX + 1];
	}

	public float surfaceAt(int index) {
		return surfaces[index];
	}

	public boolean isWalkable(int index) {
		return walkable.get(index);
	}

	/** @return The highest surface over that column, or {@link Float#NaN} if nothing was sampled there. */
	public float topSurface(int cellX, int cellY) {
		int end = columnEnd(cellX, cellY);
		return columnStart(cellX, cellY) == end ? Float.NaN : surfaces[end - 1];
	}

	/** @return true if a bot could stand anywhere in that column. */
	public boolean hasFooting(int cellX, int cellY) {
		for (int i = columnStart(cellX, cellY); i < columnEnd(cellX, cellY); i++)
			if (walkable.get(i))
				return true;
		return false;
	}

	/** @return The surface index a bot standing at that height would be on, or -1 if there is none within a step. */
	public int surfaceUnder(int cellX, int cellY, float z) {
		int found = -1;
		for (int i = columnStart(cellX, cellY); i < columnEnd(cellX, cellY) && surfaces[i] <= z + AGENT_HEIGHT; i++)
			found = i;
		return found;
	}

	/** Sorts each column low to high, keeping the walkable marks with their surface. */
	void sortColumns() {
		for (int column = 0; column < width * height; column++) {
			int start = offsets[column], end = offsets[column + 1];
			for (int i = start + 1; i < end; i++) { // insertion sort: columns hold a handful of surfaces at most
				float z = surfaces[i];
				boolean flag = walkable.get(i);
				int j = i - 1;
				for (; j >= start && surfaces[j] > z; j--) {
					surfaces[j + 1] = surfaces[j];
					walkable.set(j + 1, walkable.get(j));
				}
				surfaces[j + 1] = z;
				walkable.set(j + 1, flag);
			}
		}
	}

	/**
	 * Clears surfaces a bot could not stand on because something sits right above them: the underside of a bridge, a low ceiling, a shelf.
	 */
	void applyHeadroom() {
		for (int column = 0; column < width * height; column++) {
			int end = offsets[column + 1];
			for (int i = offsets[column]; i < end - 1; i++) {
				if (surfaces[i + 1] - surfaces[i] < AGENT_HEIGHT)
					walkable.clear(i);
			}
		}
	}

	/** Clears every surface of the given columns, used for the no-walk volumes the game authors itself. */
	void block(BitSet columns) {
		for (int column = columns.nextSetBit(0); column >= 0; column = columns.nextSetBit(column + 1))
			walkable.clear(offsets[column], offsets[column + 1]);
	}
}
