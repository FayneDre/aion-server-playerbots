package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayList;
import java.util.Arrays;
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
	/** Steepest ground a bot may walk on, matching the 45Â° the engine's own movement probe allows. */
	public static final float MAX_SLOPE_COSINE = (float) Math.cos(Math.toRadians(MapSlopes.WALKABLE_DEGREES));
	/**
	 * How many cells of walkable ground to shave off along every edge, so routes keep a body's width from what a body cannot pass.
	 * <p>
	 * Overridable with {@code -Dnavmesh.erode=<cells>} so the offline tool can measure what erosion costs in connectivity. It is the one generation
	 * rule that removes ground no obstacle stands on, so when the mesh comes out in pieces this is the first thing to vary.
	 */
	public static final int AGENT_RADIUS_CELLS = Integer.getInteger("navmesh.erode", 1);
	/**
	 * The erosion in an instance, none by default. A dungeon is built of doorways, and a doorway a metre wide is the commonest thing in one: eroded by half a metre
	 * from each side it closes, and the first dungeon measured, Dark Poeta, went from no arrival that reached anything to 95 % of its monsters reached when the erosion was taken off.
	 * What the erosion guards against in the open, a body climbing a trunk by its branches, has no counterpart indoors. Overridable like {@link #AGENT_RADIUS_CELLS}.
	 */
	public static final int INSTANCE_AGENT_RADIUS_CELLS = Integer.getInteger("navmesh.instanceErode", 0);
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

	/** Gives back to the walk every surface of the columns of the box that walked before {@code before}, whatever took it since. */
	void restoreWalkable(BitSet before, int minCellX, int minCellY, int maxCellX, int maxCellY) {
		for (int cellY = Math.max(0, minCellY); cellY <= Math.min(height - 1, maxCellY); cellY++) {
			for (int cellX = Math.max(0, minCellX); cellX <= Math.min(width - 1, maxCellX); cellX++) {
				for (int surface = columnStart(cellX, cellY); surface < columnEnd(cellX, cellY); surface++) {
					if (before.get(surface))
						walkable.set(surface);
				}
			}
		}
	}

	/** Gives back to the walk the surfaces of the columns round a point that walked before {@code before} and lie within {@code tolerance} metres of its height. @return how many were not walkable. */
	int restoreNear(BitSet before, float x, float y, float z, int cellRadius, float tolerance) {
		int centreX = (int) (x / CELL_SIZE), centreY = (int) (y / CELL_SIZE), given = 0;
		for (int cellY = Math.max(0, centreY - cellRadius); cellY <= Math.min(height - 1, centreY + cellRadius); cellY++) {
			for (int cellX = Math.max(0, centreX - cellRadius); cellX <= Math.min(width - 1, centreX + cellRadius); cellX++) {
				for (int surface = columnStart(cellX, cellY); surface < columnEnd(cellX, cellY); surface++) {
					if (before.get(surface) && !walkable.get(surface) && Math.abs(surfaces[surface] - z) <= tolerance) {
						walkable.set(surface);
						given++;
					}
				}
			}
		}
		return given;
	}

	/** How many surfaces the walked ground of the map gave back, 0 before it ran. */
	private int walkedGivenBack;

	public int walkedGivenBack() {
		return walkedGivenBack;
	}

	void addWalkedGivenBack(int count) {
		walkedGivenBack += count;
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

	/** The least difference in height between a surface and its neighbour's that makes the neighbour no footing for it, see {@link #hasEdgeNearby}. */
	private static final float EDGE_HEIGHT = 1.2f;

	/**
	 * Shaves walkable ground back from every edge by the width of a body.
	 * <p>
	 * Without this the grid says a bot may stand on anything flat enough, including the top of a fallen tree trunk it reaches by a gentle branch, or
	 * the half metre of ledge against a wall. A route then leads somewhere a body does not fit and does not belong. Eroding is what turns "this cell
	 * is flat" into "a body fits here", and it is why narrow features disappear on their own rather than needing to be recognised.
	 * <p>
	 * Surface by surface, see {@link #hasEdgeNearby}. Measured on Verteron and Reshanta on 18 October against the meshes built by column: no route on foot
	 * through a wall of the Verteron citadel or of Teminon's castle where there were several, and as many places reached or more (Teminon's npcs 99 of
	 * 101 from its obelisk, against 97). A rule in the path finder refusing a diagonal step between two shut corners was tried first and dropped: on
	 * walls a body's width thick there is no such step left to refuse, and it cost passages.
	 */
	void erode(int radius, float stepTolerance, float slopeTangent) {
		BitSet before = (BitSet) walkable.clone(); // every surface judged against the ground as it was, not as the pass leaves it
		BitSet eroded = new BitSet(surfaces.length);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int column = y * width + x;
				for (int i = offsets[column]; i < offsets[column + 1]; i++) {
					if (before.get(i) && hasEdgeNearby(before, x, y, surfaces[i], radius, stepTolerance, slopeTangent))
						eroded.set(i);
				}
			}
		}
		walkable.andNot(eroded);
	}

	/**
	 * @return true if, within the radius, a column has no walkable surface a body on this one could step to: an edge of this floor.
	 *         <p>
	 *         Asked surface by surface and at this surface's height. Asked of the column, as it was, a wall was no edge at all whenever anything else in its
	 *         column was walkable -- the ground under a house's floor, the top of the wall, a roof -- which is nearly always: the floor beside a wall kept
	 *         its full width, routes ran along walls closer than a body fits, and a thin wall at an angle was one cell thick, which a diagonal step went
	 *         through. That was every bot seen walking into a house wall in the Verteron citadel and into the castle in Teminon. And eroding a column
	 *         cleared every surface in it, the roof above a narrow alley with the alley.
	 */
	private boolean hasEdgeNearby(BitSet before, int x, int y, float z, int radius, float stepTolerance, float slopeTangent) {
		for (int offsetY = -radius; offsetY <= radius; offsetY++) {
			for (int offsetX = -radius; offsetX <= radius; offsetX++) {
				if (offsetX == 0 && offsetY == 0)
					continue;
				// A disc of a body's radius, not a square. The corner of the square is 0.7 m off at half metre cells, outside the body, and against a wall
				// at an angle it shaved that much from each side: a two metre doorway in the Verteron citadel shut, and with it the shop of a quest npc.
				if (offsetX * offsetX + offsetY * offsetY > radius * radius)
					continue;
				int nextX = x + offsetX, nextY = y + offsetY;
				if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height)
					return true; // off the map counts as an edge, which keeps bots off the very rim
				// The path finder's own step, as far up or down as the slope allows over the distance, but never under EDGE_HEIGHT: the terrain is sampled
				// every half metre and its slope judged over two, so a hillside walked every day has steps between neighbours taller than one stride allows,
				// and taken as edges they sliced it -- 207 of 300 long routes on Verteron found no way, against 134. A wall stands further off than that:
				// the ground under a house's floor two metres down, the top of a castle wall a metre and a half up.
				float reach = Math.max(EDGE_HEIGHT, (float) Math.hypot(offsetX, offsetY) * CELL_SIZE * slopeTangent + stepTolerance);
				int column = nextY * width + nextX;
				boolean footing = false;
				for (int i = offsets[column]; i < offsets[column + 1] && !footing; i++)
					footing = (before.get(i) || decorBlocked != null && decorBlocked.get(i)) && Math.abs(surfaces[i] - z) <= reach; // ground a piece of decor stands on is no edge
				if (!footing)
					return true;
			}
		}
		return false;
	}

	/**
	 * Labels every walkable surface with the region of ground it belongs to, joining neighbours the same way the path finder steps between them.
	 * <p>
	 * Per surface, not per column, and that distinction is the whole point. A column can hold walkable ground at more than one height â€” a valley
	 * floor and the cliff top above it, a camp and the mushroom canopy over it â€” and labelling the column could only ever describe one of them. It
	 * described the highest, so a camp sunk at z=100 under a canopy at z=143 belonged to the canopy's region and no route to it existed: the rough
	 * pass refused every journey there in nought milliseconds, for a place a player walks into.
	 * <p>
	 * Lives here rather than in the tool that reports it, so the map is generated from the same answer the analysis gives. Two routines answering
	 * this question separately is how the mesh and the engine came to disagree in the first place.
	 *
	 * @param climbTolerance The height a body may gain over half a metre, which is the path finder's own {@code STEP_TOLERANCE}.
	 * @return One region id per surface, indexed as {@code surfaces} is, 0 for anything not walkable. Ids start at 1 and count up.
	 */
	public int[] regions(float climbTolerance) {
		return regions(climbTolerance, 1f);
	}

	/**
	 * @param slopeTangent How steep a body walks up, as the rise over the run: 1 for the engine's 45 degrees.
	 */
	public int[] regions(float climbTolerance, float slopeTangent) {
		return regionsOf(walkable, climbTolerance, slopeTangent);
	}

	private int[] regionsOf(BitSet ground, float climbTolerance, float slopeTangent) {
		int[] region = new int[surfaces.length];
		int[] stack = new int[surfaces.length];
		int next = 0;
		for (int origin = 0; origin < region.length; origin++) {
			if (region[origin] != 0 || !ground.get(origin))
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
					float reach = CELL_SIZE * (diagonal ? 1.41421f : 1) * slopeTangent + climbTolerance;
					// every walkable height of the neighbouring column, not just its highest: which one a body steps onto depends on where it is
					// standing, and that is exactly what a single label per column cannot say
					for (int neighbour = columnStart(nextX, nextY); neighbour < columnEnd(nextX, nextY); neighbour++) {
						if (region[neighbour] != 0 || !ground.get(neighbour))
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
	 * quarter of its ground, so a rough route crossed freely between two regions the fine grid keeps apart â€” and every attempt to refine that
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

	/** @return Which surfaces are walkable now, to be kept across a step that takes ground away. */
	BitSet copyOfWalkable() {
		return (BitSet) walkable.clone();
	}

	/**
	 * Gives back the ground the erosion took where it was the only link between stretches of ground that joined before it.
	 * <p>
	 * The erosion shaves a body's width from every edge, and a passage narrower than that vanishes: the ramp into a cave, a gap between two rocks. The stretches on
	 * its two sides then stand as ground of their own, and a route between them is proved shut, though a body walks it. So each stretch left of fewer cells than
	 * the largest of its region as it was before the erosion, and of at least {@code minCells}, looks outward through the cells the erosion took, a few steps at most,
	 * for ground of a stretch already joined, and takes back only the cells on the shortest such way. Everywhere else the erosion stands, so a wall keeps a body's
	 * width off it.
	 *
	 * @param beforeErosion {@link #copyOfWalkable} as it was before {@link #erode}
	 * @param minCells how small a stretch may be and still be joined back: fewer cells than this is a speck the erosion made of a patch of ground among props
	 * @param reachCells how many cells of ground taken the way back may cross
	 * @return how many surfaces were given back
	 */
	int keepPassages(BitSet beforeErosion, int minCells, int reachCells, float climbTolerance, float slopeTangent) {
		int[] before = regionsOf(beforeErosion, climbTolerance, slopeTangent);
		int[] after = regionsOf(walkable, climbTolerance, slopeTangent);
		int labels = 0;
		for (int label : after)
			labels = Math.max(labels, label);
		int[] size = new int[labels + 1], regionBefore = new int[labels + 1];
		for (int i = 0; i < after.length; i++) {
			if (after[i] != 0) {
				size[after[i]]++;
				regionBefore[after[i]] = before[i];
			}
		}
		Map<Integer, Integer> largest = new HashMap<>(); // per region as it was before, the stretch of it that is largest now
		for (int label = 1; label <= labels; label++)
			largest.merge(regionBefore[label], label, (known, other) -> size[other] > size[known] ? other : known);
		boolean[] joined = new boolean[labels + 1];
		largest.values().forEach(label -> joined[label] = true);
		int[][] cells = new int[labels + 1][];
		List<Integer> fragments = new ArrayList<>();
		for (int label = 1; label <= labels; label++) {
			if (!joined[label] && size[label] >= minCells) {
				cells[label] = new int[size[label]];
				fragments.add(label);
			}
		}
		int[] filled = new int[labels + 1];
		for (int i = 0; i < after.length; i++) {
			if (after[i] != 0 && cells[after[i]] != null)
				cells[after[i]][filled[after[i]]++] = i;
		}
		fragments.sort((first, second) -> size[second] - size[first]); // the larger first, so that a smaller one may join onto it
		int restored = 0;
		int[] cameFrom = new int[surfaces.length]; // per surface the erosion took, the surface it was reached from: read only where reached is set
		BitSet reached = new BitSet(surfaces.length);
		for (int label : fragments) {
			restored += joinBack(label, cells[label], after, joined, beforeErosion, cameFrom, reached, reachCells, climbTolerance, slopeTangent);
			cells[label] = null;
		}
		passagesGivenBack = restored;
		return restored;
	}

	/** How many surfaces {@link #keepPassages} gave back, 0 before it ran. */
	private int passagesGivenBack;

	public int passagesGivenBack() {
		return passagesGivenBack;
	}

	/** A growing run of surface indices, so that the search for a passage boxes nothing. */
	private static final class Run {
		int[] values = new int[64];
		int size;

		void add(int value) {
			if (size == values.length)
				values = Arrays.copyOf(values, size * 2);
			values[size++] = value;
		}
	}

	/**
	 * @param cameFrom for each surface the erosion took that the search reached, the surface it was reached from
	 * @param reached the surfaces the erosion took that the search has reached; cleared again before it returns
	 * @return How many surfaces were given back to join the stretch to ground already joined, 0 when none is within reach.
	 */
	private int joinBack(int label, int[] stretch, int[] region, boolean[] joined, BitSet beforeErosion, int[] cameFrom, BitSet reached, int reachCells,
		float climbTolerance, float slopeTangent) {
		Run queue = new Run(); // every surface the erosion took that the search reached, in the order reached: the layers of the search are ranges of it
		int layerStart = 0, layerEnd = stretch.length, given = 0;
		search: for (int depth = 0; depth < reachCells && layerStart < layerEnd; depth++) {
			for (int n = layerStart; n < layerEnd; n++) {
				int surface = depth == 0 ? stretch[n] : queue.values[n];
				int column = columnOf(surface);
				int x = column % width, y = column / width;
				float z = surfaces[surface];
				for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
					int nextX = x + NEIGHBOUR_X[direction], nextY = y + NEIGHBOUR_Y[direction];
					if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height)
						continue;
					boolean diagonal = NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0;
					float reach = CELL_SIZE * (diagonal ? 1.41421f : 1) * slopeTangent + climbTolerance;
					// A step across the corner of a wall is not a passage: it is what the erosion shut on purpose, and the pass this gives ground back for must not open it
					// again. 7 stretches through the walls of a store on Sanctum's upper floor came of it before this was asked.
					if (diagonal && !(hasGroundAt(beforeErosion, x + NEIGHBOUR_X[direction], y, z, CELL_SIZE * slopeTangent + climbTolerance)
						&& hasGroundAt(beforeErosion, x, y + NEIGHBOUR_Y[direction], z, CELL_SIZE * slopeTangent + climbTolerance)))
						continue;
					for (int neighbour = columnStart(nextX, nextY); neighbour < columnEnd(nextX, nextY); neighbour++) {
						if (Math.abs(surfaces[neighbour] - z) > reach)
							continue;
						if (walkable.get(neighbour)) {
							if (region[neighbour] != label && region[neighbour] != 0 && joined[region[neighbour]]) {
								given = giveBack(surface, cameFrom, reached, label, region, joined);
								break search;
							}
						} else if (beforeErosion.get(neighbour) && !reached.get(neighbour)) {
							reached.set(neighbour);
							cameFrom[neighbour] = surface;
							queue.add(neighbour);
						}
					}
				}
			}
			layerStart = depth == 0 ? 0 : layerEnd; // the first layer was the stretch, which is not in the queue: the second is all the queue holds so far
			layerEnd = queue.size;
		}
		for (int i = 0; i < queue.size; i++)
			reached.clear(queue.values[i]);
		return given;
	}

	/** @return true if the column has a surface of the given ground within {@code reach} of the height. */
	private boolean hasGroundAt(BitSet ground, int x, int y, float z, float reach) {
		for (int i = columnStart(x, y); i < columnEnd(x, y); i++) {
			if (ground.get(i) && Math.abs(surfaces[i] - z) <= reach)
				return true;
		}
		return false;
	}

	/** Walks the way back from the surface that met joined ground to the stretch, making each surface the erosion took on it walkable again. */
	private int giveBack(int meeting, int[] cameFrom, BitSet reached, int label, int[] region, boolean[] joined) {
		int given = 0;
		for (int surface = meeting; reached.get(surface); surface = cameFrom[surface]) {
			walkable.set(surface);
			region[surface] = label;
			given++;
		}
		joined[label] = true;
		return given;
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
			if (high > z + steppableRise && low < z + AGENT_HEIGHT) {
				walkable.clear(i);
				if (decorBlocked != null)
					decorBlocked.clear(i); // a wall as well: the surface is no decor's alone
			}
		}
	}

	/**
	 * As {@link #blockSpan}, for the walls of a small piece of decor (a barrel, a pot, a fire, a tent): the surface is taken away, and remembered as taken by decor,
	 * so that {@link #erode} does not shave the ground beside it as it shaves the ground beside a wall.
	 */
	void blockSpanByDecor(int column, float low, float high, float steppableRise) {
		for (int i = offsets[column]; i < offsets[column + 1]; i++) {
			float z = surfaces[i];
			if (walkable.get(i) && high > z + steppableRise && low < z + AGENT_HEIGHT) {
				if (decorBlocked == null)
					decorBlocked = new BitSet(surfaces.length);
				walkable.clear(i);
				decorBlocked.set(i);
			}
		}
	}

	/** The surfaces that only small decor took the ground from, or null when none did: not edges for the erosion. */
	private BitSet decorBlocked;
}
