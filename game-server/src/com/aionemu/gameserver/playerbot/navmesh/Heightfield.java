package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	/** Distance the terrain slope is measured over, about a stride. */
	public static final float SLOPE_WINDOW = 2f;
	/** Steepest ground a bot may walk on, matching the 45° the engine's own movement probe allows. */
	public static final float MAX_SLOPE_COSINE = (float) Math.cos(Math.toRadians(45));
	/**
	 * How many cells of walkable ground to shave off along every edge, so routes keep a body's width from what a body cannot pass.
	 * <p>
	 * Overridable with {@code -Dnavmesh.erode=<cells>} so the offline tool can measure what erosion costs in connectivity. It is the one generation
	 * rule that removes ground no obstacle stands on, so when the mesh comes out in pieces this is the first thing to vary.
	 */
	public static final int AGENT_RADIUS_CELLS = Integer.getInteger("navmesh.erode", 1);
	/** Fine cells per side of a coarse cell, making the coarse grid 4 m. */
	public static final int COARSE_FACTOR = 8;
	private static final int[] NEIGHBOUR_X = { 1, 1, 0, -1, -1, -1, 0, 1 }, NEIGHBOUR_Y = { 0, 1, 1, 1, 0, -1, -1, -1 };
	/** How much of a coarse cell must be walkable for it to count. Low on purpose: the coarse grid only guides, it never decides. */
	private static final float COARSE_THRESHOLD = 0.25f;

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

	/**
	 * Shaves walkable ground back from every edge by the width of a body.
	 * <p>
	 * Without this the grid says a bot may stand on anything flat enough, including the top of a fallen tree trunk it reaches by a gentle branch, or
	 * the half metre of ledge against a wall. A route then leads somewhere a body does not fit and does not belong. Eroding is what turns "this cell
	 * is flat" into "a body fits here", and it is why narrow features disappear on their own rather than needing to be recognised.
	 */
	void erode(int radius) {
		BitSet footing = new BitSet(width * height);
		for (int column = 0; column < width * height; column++) {
			for (int i = offsets[column]; i < offsets[column + 1]; i++) {
				if (walkable.get(i)) {
					footing.set(column);
					break;
				}
			}
		}

		BitSet eroded = new BitSet(width * height);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (!footing.get(y * width + x))
					continue;
				if (hasEdgeNearby(footing, x, y, radius))
					eroded.set(y * width + x);
			}
		}
		block(eroded);
	}

	private boolean hasEdgeNearby(BitSet footing, int x, int y, int radius) {
		for (int offsetY = -radius; offsetY <= radius; offsetY++) {
			for (int offsetX = -radius; offsetX <= radius; offsetX++) {
				int nextX = x + offsetX, nextY = y + offsetY;
				if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height || !footing.get(nextY * width + nextX))
					return true; // off the map counts as an edge, which keeps bots off the very rim
			}
		}
		return false;
	}

	/**
	 * Sums the grid up into 4 m cells, which is what makes long routes searchable.
	 * <p>
	 * A\* over half metre cells explores a hopeless area once a goal is a few hundred metres away. Planning roughly first and refining afterwards
	 * costs one bit per 4 m of map, so the whole of Poeta guides in 72 KB, small enough to keep loaded while the detailed tiles stay on disk.
	 */
	/**
	 * Labels every walkable surface with the region of ground it belongs to, joining neighbours the same way the path finder steps between them.
	 * <p>
	 * Per surface, not per column, and that distinction is the whole point. A column can hold walkable ground at more than one height — a valley
	 * floor and the cliff top above it, a camp and the mushroom canopy over it — and labelling the column could only ever describe one of them. It
	 * described the highest, so a camp sunk at z=100 under a canopy at z=143 belonged to the canopy's region and no route to it existed: the rough
	 * pass refused every journey there in nought milliseconds, for a place a player walks into.
	 * <p>
	 * Lives here rather than in the tool that reports it, so the map is generated from the same answer the analysis gives. Two routines answering
	 * this question separately is how the mesh and the engine came to disagree in the first place.
	 *
	 * @param climbTolerance The height a body may gain over half a metre, which is the path finder's own {@code STEP_TOLERANCE}.
	 * @return One region id per surface, indexed as {@code surfaces} is, 0 for anything not walkable. Ids start at 1 and count up.
	 */
	int[] regions(float climbTolerance) {
		int[] region = new int[surfaces.length];
		int[] stack = new int[surfaces.length];
		int next = 0;
		for (int origin = 0; origin < region.length; origin++) {
			if (region[origin] != 0 || !walkable.get(origin))
				continue;
			int id = ++next, top = 0;
			stack[top++] = origin;
			region[origin] = id;
			while (top > 0) {
				int surface = stack[--top];
				int column = columnOf(surface);
				int x = column % width, y = column / width;
				float z = surfaces[surface];
				for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
					int nextX = x + NEIGHBOUR_X[direction], nextY = y + NEIGHBOUR_Y[direction];
					if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height)
						continue;
					boolean diagonal = NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0;
					float reach = CELL_SIZE * (diagonal ? 1.41421f : 1) + climbTolerance;
					// every walkable height of the neighbouring column, not just its highest: which one a body steps onto depends on where it is
					// standing, and that is exactly what a single label per column cannot say
					for (int neighbour = columnStart(nextX, nextY); neighbour < columnEnd(nextX, nextY); neighbour++) {
						if (region[neighbour] != 0 || !walkable.get(neighbour))
							continue;
						if (Math.abs(surfaces[neighbour] - z) > reach)
							continue;
						region[neighbour] = id;
						stack[top++] = neighbour;
					}
				}
			}
		}
		return region;
	}

	/**
	 * @return The column a surface belongs to, found by binary search over the offsets. Storing it per surface would cost as much again as the heights
	 *         themselves, on arrays of tens of millions of entries.
	 */
	private int columnOf(int surface) {
		int low = 0, high = width * height - 1;
		while (low < high) {
			int middle = (low + high + 1) >>> 1;
			if (offsets[middle] <= surface)
				low = middle;
			else
				high = middle - 1;
		}
		return low;
	}

	/**
	 * Sums the fine grid up into 4 m cells carrying every region present in each one.
	 * <p>
	 * A plain "is there ground here" bit was not enough, and this is the whole reason long routes failed. A coarse cell counted as routable on a
	 * quarter of its ground, so a rough route crossed freely between two regions the fine grid keeps apart — and every attempt to refine that
	 * crossing came back empty. Carrying the region turns the rough grid from a hint into a promise: a rough route stays inside one region, and a
	 * region is by construction something a body can walk across.
	 * <p>
	 * Every region rather than the majority one, because 4 m of map is wide enough to hold two floors of ground. Keeping only the commonest hid the
	 * lower one entirely, which is how a camp in a hollow became a place with no route to it. A cell now says "these regions pass through here" and
	 * the route picks one of them and stays in it.
	 *
	 * @param regions One region id per surface, as {@link #regions(float)} returns them.
	 * @return The regions of each coarse cell, empty where too little of it is walkable to plan through.
	 */
	CoarseRegions coarseRegions(int[] regions) {
		int coarseWidth = (width + COARSE_FACTOR - 1) / COARSE_FACTOR, coarseHeight = (height + COARSE_FACTOR - 1) / COARSE_FACTOR;
		int[] offsets = new int[coarseWidth * coarseHeight + 1];
		List<Integer> ids = new ArrayList<>();
		Map<Integer, Integer> tally = new HashMap<>();
		Set<Integer> inColumn = new HashSet<>();
		for (int coarseY = 0; coarseY < coarseHeight; coarseY++) {
			for (int coarseX = 0; coarseX < coarseWidth; coarseX++) {
				tally.clear();
				int total = 0;
				for (int y = coarseY * COARSE_FACTOR; y < Math.min(height, (coarseY + 1) * COARSE_FACTOR); y++) {
					for (int x = coarseX * COARSE_FACTOR; x < Math.min(width, (coarseX + 1) * COARSE_FACTOR); x++) {
						total++;
						// counted once per column, whatever the region: what matters is how much of the cell's floor plan a region covers, and a
						// column with three surfaces of one region is still one column's worth of ground
						inColumn.clear();
						for (int surface = columnStart(x, y); surface < columnEnd(x, y); surface++) {
							if (regions[surface] != 0)
								inColumn.add(regions[surface]);
						}
						for (int id : inColumn)
							tally.merge(id, 1, Integer::sum);
					}
				}
				for (Map.Entry<Integer, Integer> entry : tally.entrySet()) {
					if (entry.getValue() >= total * COARSE_THRESHOLD)
						ids.add(entry.getKey());
				}
				offsets[coarseY * coarseWidth + coarseX + 1] = ids.size();
			}
		}
		int[] flat = new int[ids.size()];
		for (int i = 0; i < flat.length; i++)
			flat[i] = ids.get(i);
		return new CoarseRegions(coarseWidth, coarseHeight, offsets, flat);
	}

	/**
	 * The regions of every coarse cell, flattened the same way the surfaces of a column are: a cell's ids are
	 * {@code ids[offsets[cell] .. offsets[cell + 1]]}.
	 */
	record CoarseRegions(int width, int height, int[] offsets, int[] ids) {
	}

	BitSet coarseFooting() {
		int coarseWidth = (width + COARSE_FACTOR - 1) / COARSE_FACTOR, coarseHeight = (height + COARSE_FACTOR - 1) / COARSE_FACTOR;
		BitSet coarse = new BitSet(coarseWidth * coarseHeight);
		for (int coarseY = 0; coarseY < coarseHeight; coarseY++) {
			for (int coarseX = 0; coarseX < coarseWidth; coarseX++) {
				int standable = 0, total = 0;
				for (int y = coarseY * COARSE_FACTOR; y < Math.min(height, (coarseY + 1) * COARSE_FACTOR); y++) {
					for (int x = coarseX * COARSE_FACTOR; x < Math.min(width, (coarseX + 1) * COARSE_FACTOR); x++) {
						total++;
						if (hasFooting(x, y))
							standable++;
					}
				}
				if (total > 0 && standable >= total * COARSE_THRESHOLD)
					coarse.set(coarseY * coarseWidth + coarseX);
			}
		}
		return coarse;
	}

	/** Clears every surface of the given columns, used for the no-walk volumes the game authors itself. */
	void block(BitSet columns) {
		for (int column = columns.nextSetBit(0); column >= 0; column = columns.nextSetBit(column + 1))
			walkable.clear(offsets[column], offsets[column + 1]);
	}

	/**
	 * Clears only the surfaces of a column that an obstacle actually stands in the way of.
	 * <p>
	 * A body on a surface at {@code z} fills the space up to {@code z + AGENT_HEIGHT}. An obstacle matters when it reaches into that space and rises
	 * far enough above the surface to be more than a step. Anything entirely above it is a roof or a canopy the body walks under, and anything
	 * entirely below is ground it stands on.
	 * <p>
	 * The difference is not academic: a tree's leaves sit twenty-seven metres up, and clearing whole columns for them deleted the ground under every
	 * tree in the map. Routes then took fifty-five metres to cover nineteen, going the long way round open grass a player walks straight across.
	 */
	void blockSpan(int column, float low, float high, float steppableRise) {
		for (int i = offsets[column]; i < offsets[column + 1]; i++) {
			float z = surfaces[i];
			if (high > z + steppableRise && low < z + AGENT_HEIGHT)
				walkable.clear(i);
		}
	}
}
