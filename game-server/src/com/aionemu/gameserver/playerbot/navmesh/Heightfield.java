package com.aionemu.gameserver.playerbot.navmesh;

import java.util.Arrays;

/**
 * Every surface of a map, sampled on a regular grid.
 * <p>
 * A column holds the heights of all surfaces above that spot, sorted low to high: the ground, then the floor of a bridge, then its underside, and so
 * on. This is the raw material a navmesh is built from — walkability is decided later.
 * <p>
 * Stored as one flat array with an offset per column rather than a list per column: a 2 km map has sixteen million columns, so an object per column
 * is out of the question.
 */
public class Heightfield {

	public static final float CELL_SIZE = 0.5f;

	private final int width, height;
	private final int[] offsets; // width * height + 1 entries, so a column's samples are offsets[i] .. offsets[i + 1]
	private final float[] surfaces;

	Heightfield(int width, int height, int[] offsets, float[] surfaces) {
		this.width = width;
		this.height = height;
		this.offsets = offsets;
		this.surfaces = surfaces;
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

	public int columnStart(int cellX, int cellY) {
		return offsets[cellY * width + cellX];
	}

	public int columnEnd(int cellX, int cellY) {
		return offsets[cellY * width + cellX + 1];
	}

	public float surfaceAt(int index) {
		return surfaces[index];
	}

	/** @return The highest surface over that column, or {@link Float#NaN} if nothing was sampled there. */
	public float topSurface(int cellX, int cellY) {
		int end = columnEnd(cellX, cellY);
		return columnStart(cellX, cellY) == end ? Float.NaN : surfaces[end - 1];
	}

	/** Sorts each column, so surfaces read bottom to top. */
	void sortColumns() {
		for (int column = 0; column < width * height; column++)
			Arrays.sort(surfaces, offsets[column], offsets[column + 1]);
	}
}
