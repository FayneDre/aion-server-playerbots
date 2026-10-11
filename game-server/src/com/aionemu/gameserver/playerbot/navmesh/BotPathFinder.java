package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Finds a route over a generated map.
 * <p>
 * A\* over the grid of walkable surfaces, then string pulling to turn the staircase it produces into the handful of waypoints a bot actually walks.
 * Grid A\* is chosen over a polygon navmesh for the reasons in docs/navmesh-plan.md: far less code, and a result that can be looked at.
 */
public class BotPathFinder {

	/**
	 * Extra height a bot may climb between two neighbouring cells, on top of what the slope between them already allows.
	 * <p>
	 * A flat limit does not work here. Cells are half a metre apart, so ground at the 45° the walkability rules accept already rises half a metre
	 * between orthogonal neighbours and seven tenths between diagonal ones. A flat half metre therefore severed every moderately steep hillside into
	 * strips: Poeta came out as eleven thousand islands, the largest covering an eighth of the map.
	 */
	public static final float STEP_TOLERANCE = 0.15f;
	/** How far above or below the requested height a start or goal surface may be found. */
	private static final float SNAP_RANGE = 3f;
	/** How many cells outwards to look for walkable ground when the exact spot is not, about six metres. */
	private static final int SNAP_RINGS = 12;
	/**
	 * Search budget for one stretch of fine grid. A stretch spans tens of metres, so this is already generous. The property {@code navmesh.maxNodes} raises it in the offline tools, to
	 * find out how long the way round really is when a stretch gives up; the server keeps the default, since each node is held in memory until the search ends.
	 */
	private static final int MAX_NODES = Integer.getInteger("navmesh.maxNodes", 150_000);
	/**
	 * How long a whole journey may be planned for. A 1.4 km crossing of Poeta takes about 1.4 s, which is why long journeys are planned off the
	 * movement threads; this only stops a hopeless one from running forever.
	 */
	private static final long PLAN_BUDGET_MILLIS = 3000;
	/** Budget for the rough pass, which covers far more ground per node. */
	private static final int MAX_COARSE_NODES = 500_000;
	/** Past this, planning goes through the coarse grid first. Below it, fine A\* is quick enough on its own. */
	public static final float LONG_DISTANCE = 150f;
	/** Coarse cells between two guide points, so each refined stretch stays about forty metres. */
	private static final int GUIDE_SPACING = 10;
	/** How far to look for a usable coarse cell, about twelve metres. */
	private static final int COARSE_SNAP_RINGS = 3;
	/** Accepts ground at any height, for guide points that only say which way to go. */
	private static final float ANY_HEIGHT = Float.MAX_VALUE;
	/** How many awkward guide points in a row may be skipped before the journey is declared hopeless. */
	private static final int MAX_GUIDE_SKIPS = 3;
	/** Most stretches of ground kept per coarse cell, the largest. The ones past it are crumbs a rough route does not go through. */
	private static final int MAX_LEVELS = 16;

	/** Prints which stretch of a long walk failed to refine and where its ends were snapped: {@code -Dnavmesh.pathDebug=true} with {@code NavmeshTool <map> path}. */
	private static final boolean DEBUG = Boolean.getBoolean("navmesh.pathDebug");

	private static final int[] NEIGHBOUR_X = { 1, 1, 0, -1, -1, -1, 0, 1 };
	private static final int[] NEIGHBOUR_Y = { 0, 1, 1, 1, 0, -1, -1, -1 };

	private BotPathFinder() {
	}

	private record Node(long key, float estimatedTotal) {
	}

	/**
	 * @param waypoints From start to goal, empty when there is no route.
	 * @param glides the indexes of the waypoints that are reached by gliding down from the one before, which is not a walk and is not planned as one
	 * @param gaveUp true when the search ran out of budget rather than proving there is no way. The caller can retry in steps, or fall back to
	 *          walking straight at the goal, but it must not conclude the place is unreachable.
	 */
	public record Route(List<Vector3f> waypoints, boolean gaveUp, Set<Integer> glides) {

		static final Route NONE = new Route(List.of(), true);

		public Route(List<Vector3f> waypoints, boolean gaveUp) {
			this(waypoints, gaveUp, Set.of());
		}

		public boolean isEmpty() {
			return waypoints.isEmpty();
		}
	}

	/**
	 * @return The route, whose waypoints start at the given position. Check {@link Route#gaveUp()} before treating an empty one as impossible.
	 */
	public static Route findPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		return findPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, Avoidance.NONE);
	}

	/**
	 * As above, keeping out of the ground {@code avoid} names where there is another way: it costs extra to cross, and is crossed all the same when it is the
	 * only way. The rough pass over the coarse grid does not know about it; only the fine stretches do.
	 */
	public static Route findPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid) {
		return findPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid, GlideRoutes.AirTest.ALWAYS);
	}

	/**
	 * As above, and where the ground does not join the two ends but the map has glide links, the way down by gliding.
	 *
	 * @param air whether the air along a glide is clear, asked only of links the plan would use
	 */
	static Route findPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid, GlideRoutes.AirTest air) {
		Route walking = findWalkingPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid);
		if (!walking.isEmpty() || !mesh.hasGlides())
			return walking;
		// asked after a walk that gave up as well as after one that proved there is no way: on a large stretch of ground the walk never proves it, it runs out of room
		// first, and asked only after a proof the glides were never tried. 310100000 has 206 of its 207 spots within walking and gliding of its arrival, and reached 80 %
		Route gliding = GlideRoutes.plan(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid, air);
		if (!gliding.isEmpty())
			return gliding;
		// neither found one: a proof only if both searches proved it, and the walk's own answer otherwise
		return walking.gaveUp() || !gliding.gaveUp() ? walking : gliding;
	}

	/** The route on foot alone. */
	static Route findWalkingPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid) {
		float distance = (float) Math.hypot(goalX - startX, goalY - startY);
		if (distance > LONG_DISTANCE)
			return findLongPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid);
		Route direct = findDirectPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid);
		if (!direct.isEmpty() || !direct.gaveUp())
			return direct;
		// The fine search ran out of room, which distance alone does not predict: thirty metres out of a thicket costs more of it than three hundred
		// across open grass, because what it must explore grows with the clutter rather than with the journey. Bots walked into a wood in Poeta and
		// could not plan their way out of it, at any range. The rough grid knows the way through, so it is worth asking even for a short hop.
		return findLongPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid);
	}

	/**
	 * Plans roughly on the 4 m grid, then fills in the detail one stretch at a time.
	 * <p>
	 * Fine A\* alone gives up past a few hundred metres, because at half metre cells the area it must consider grows faster than the distance. The
	 * coarse grid narrows that to a corridor, and each stretch of it is then a short search of the kind that already works.
	 */
	private static Route findLongPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ,
		Avoidance avoid) {
		List<Vector3f> guide = coarseRoute(mesh, startX, startY, startZ, goalX, goalY, goalZ);
		if (guide.isEmpty())
			return new Route(List.of(), shareGround(mesh, startX, startY, goalX, goalY));

		List<Vector3f> full = new ArrayList<>();
		float fromX = startX, fromY = startY, fromZ = startZ;
		long deadline = System.currentTimeMillis() + PLAN_BUDGET_MILLIS;
		int next = 0;
		while (next < guide.size()) {
			if (System.currentTimeMillis() > deadline)
				return new Route(List.of(), true);
			// a guide point can land on a patch the fine grid cannot reach, since a coarse cell counts as routable on a quarter of its ground.
			// skipping it and aiming further along is what keeps one awkward spot from failing the whole journey
			Route stretch = Route.NONE;
			int target = next;
			for (int skipped = 0; target < guide.size() && skipped <= MAX_GUIDE_SKIPS; skipped++, target++) {
				boolean last = target == guide.size() - 1;
				float toX = last ? goalX : guide.get(target).getX(), toY = last ? goalY : guide.get(target).getY();
				// a guide point of the levelled route is a walkable cell of the stretch the route went through, and is aimed at as it is: taken at any height, it
				// was snapped onto the floor above when its own was not walkable right there, and the search ran out of room looking for a way up. A guide
				// without a height is the older kind, which only ever knew where, so it accepts whatever ground is there, the nearest to the bot first.
				boolean unknownHeight = !last && Float.isNaN(guide.get(target).getZ());
				float guideZ = last ? goalZ : unknownHeight ? fromZ : guide.get(target).getZ();
				stretch = findDirectPath(mesh, fromX, fromY, fromZ, toX, toY, guideZ, unknownHeight ? ANY_HEIGHT : SNAP_RANGE, avoid);
				if (!stretch.isEmpty())
					break;
				if (DEBUG)
					System.out.printf("walk: stretch %d of %d from %.1f %.1f %.1f to %.1f %.1f %.1f failed (gave up %b)%n", target, guide.size(), fromX, fromY, fromZ, toX, toY, guideZ,
						stretch.gaveUp());
			}
			if (stretch.isEmpty())
				return new Route(List.of(), true);

			List<Vector3f> waypoints = stretch.waypoints();
			full.addAll(full.isEmpty() ? waypoints : waypoints.subList(1, waypoints.size()));
			Vector3f reached = waypoints.get(waypoints.size() - 1);
			fromX = reached.getX();
			fromY = reached.getY();
			fromZ = reached.getZ();
			next = target + 1;
		}
		return new Route(smooth(mesh, full, avoid), false);
	}

	/**
	 * The rough route that keeps to the level of ground the bot is on.
	 * <p>
	 * The coarse grid says which stretches of ground pass under a 4 m cell, not at what height, and a column holding a fortress platform and the ground
	 * below it carries the same stretch twice. The older search therefore went straight down through the platform, the refinement could not follow, and
	 * the planner gave up on a journey that exists: from the Eltnen fortress obelisk it found some destinations and not others, for no reason a bot
	 * could see. Here a node is a cell <b>and a level of ground in it</b>, and a step is allowed only between levels that meet.
	 * <p>
	 * The levels are read from the fine grid when a cell is first reached, so nothing is stored in the mesh file and no map needs generating again.
	 * The older search runs only when the levelled one could not answer: it ran out of nodes, or an end had no stretch of ground in its cell. When the levelled one went
	 * through all the ground it could reach, the older one is not asked: it routed over whatever region both ends shared, which on the Danuar Sanctuaries was the plane under the
	 * whole map, and every stretch of the journey ran out of room on ground at height zero.
	 *
	 * @return One point per {@link #GUIDE_SPACING} cells with the height of the level it is on, then a stand-in for the goal; empty when there is none.
	 */
	public static List<Vector3f> coarseRoute(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		int factor = mesh.coarseFactor();
		int startCell = coarseCellNear(mesh, mesh.cellX(startX) / factor, mesh.cellY(startY) / factor);
		int goalCell = coarseCellNear(mesh, mesh.cellX(goalX) / factor, mesh.cellY(goalY) / factor);
		if (startCell == -1 || goalCell == -1)
			return List.of();
		if (sharedRegions(mesh, startCell, goalCell).isEmpty())
			return List.of();
		List<Vector3f> guide = levelledRoute(mesh, startCell, startX, startY, startZ, goalCell, goalX, goalY, goalZ);
		if (guide == OUT_OF_TIME)
			return List.of(); // the caller reads an empty guide on shared ground as a search that gave up, which is what this is
		return guide != null ? guide : coarseRoute(mesh, startX, startY, goalX, goalY);
	}

	/**
	 * How long the levelled search may run, in milliseconds. Asked for a cliff top from its foot, with no way up, it went through the whole of Eltnen's
	 * ground before it could say so: 12 to 14 seconds a search, on the thread that thinks for eight bots, while the old one had answered in a fraction of
	 * that by way of a shortcut that did not exist.
	 */
	private static final long LEVELLED_BUDGET_MILLIS = 1500;
	/** What {@link #levelledRoute} answers when it ran out of time, told apart from an empty guide by being this very list. */
	private static final List<Vector3f> OUT_OF_TIME = Collections.unmodifiableList(new ArrayList<>());

	/**
	 * Not kept to the cells the grid lists a region in: it lists one only where it covers a fair part of the cell, so a path along the edge of a cell was cut there, and Altgard
	 * lost routes from two obelisks that the older search found. The stretches of each cell say exactly what joins what, which is what the region was standing in for.
	 *
	 * @return The guide; empty when the search went through all the ground it could reach without finding the goal, {@link #OUT_OF_TIME} when its time
	 *         ran out, null when it could not answer for want of ground at an end or of nodes.
	 */
	private static List<Vector3f> levelledRoute(Navmesh mesh, int startCell, float startX, float startY, float startZ, int goalCell, float goalX, float goalY,
		float goalZ) {
		Map<Integer, CellLevels> levels = new HashMap<>();
		int startLevel = nearestLevel(mesh, levelsOf(mesh, levels, startCell), startCell, startX, startY, startZ);
		int goalLevel = nearestLevel(mesh, levelsOf(mesh, levels, goalCell), goalCell, goalX, goalY, goalZ);
		if (startLevel == -1 || goalLevel == -1)
			return null;
		int start = startCell * MAX_LEVELS + startLevel, goal = goalCell * MAX_LEVELS + goalLevel;
		int width = mesh.coarseWidth(), goalCellX = goalCell % width, goalCellY = goalCell / width;
		float apart = 2 * maxClimb(mesh, true);

		Map<Integer, Float> costSoFar = new HashMap<>();
		Map<Integer, Integer> cameFrom = new HashMap<>();
		PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1]));
		costSoFar.put(start, 0f);
		open.add(new int[] { start, 0 });

		long deadline = System.currentTimeMillis() + LEVELLED_BUDGET_MILLIS;
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < MAX_COARSE_NODES) {
			if ((expanded & 1023) == 0 && System.currentTimeMillis() > deadline)
				return OUT_OF_TIME;
			int current = open.poll()[0];
			if (current == goal)
				return sampleLevelledGuide(mesh, levels, cameFrom, current, start);

			float cost = costSoFar.get(current);
			int cell = current / MAX_LEVELS, level = current % MAX_LEVELS;
			int cellX = cell % width, cellY = cell / width;
			CellLevels here = levelsOf(mesh, levels, cell);
			for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
				int nextX = cellX + NEIGHBOUR_X[direction], nextY = cellY + NEIGHBOUR_Y[direction];
				if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= mesh.coarseHeight())
					continue;
				int nextCell = coarseKey(mesh, nextX, nextY);
				CellLevels there = levelsOf(mesh, levels, nextCell);
				float step = cost + (NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0 ? 1.41421f : 1);
				float remaining = (float) Math.hypot(nextX - goalCellX, nextY - goalCellY);
				for (int next = 0; next < there.count(); next++) {
					if (levelGap(here.low()[level], here.high()[level], there.low()[next], there.high()[next]) > apart)
						continue; // a floor above or below this one, which no step joins
					if (!levelsMeet(mesh, cell, here, level, nextCell, there, next))
						continue; // close in height and still not joined: a cliff edge or a low wall runs along the border
					int node = nextCell * MAX_LEVELS + next;
					Float known = costSoFar.get(node);
					if (known != null && known <= step)
						continue;
					costSoFar.put(node, step);
					cameFrom.put(node, current);
					open.add(new int[] { node, Math.round(step + remaining) });
				}
			}
		}
		// out of nodes is no answer; out of ground is one
		return open.isEmpty() ? List.of() : null;
	}

	/**
	 * The stretches of ground inside one coarse cell: pieces a body walks across without leaving the cell, numbered largest first.
	 * <p>
	 * They were bands of height, and two terraces a metre apart with a wall between were one band: the rough route crossed from one to the other, the fine search could not, and
	 * 310100000 walked 359 m round for a stretch the guide put at 36. A stretch read by what joins what is ground the fine search can cross, so a rough route made of them can be
	 * followed.
	 *
	 * @param firstOfColumn For each of the cell's columns, row by row, the index its surfaces start at in {@code levelOfSurface}, with one more entry closing the last.
	 * @param levelOfSurface The stretch each surface of the cell belongs to, or -1 for ground nobody stands on and for the smallest pieces past {@link #MAX_LEVELS}.
	 * @param spotX Per stretch, the walkable cell of it nearest the middle of the coarse cell, which is where a rough route passing through it aims.
	 */
	private record CellLevels(short[] firstOfColumn, byte[] levelOfSurface, float[] low, float[] high, float[] spotX, float[] spotY, float[] spotZ) {

		int count() {
			return low.length;
		}

		/** @return The stretch of a surface of one of the cell's columns, -1 when it has none. */
		int levelAt(int column, int surface) {
			int index = firstOfColumn[column] + surface;
			return index < firstOfColumn[column + 1] ? levelOfSurface[index] : -1;
		}
	}

	/** @return The stretches of ground of a coarse cell, worked out the first time a search reaches it and kept in {@code cache} for the rest of it. */
	private static CellLevels levelsOf(Navmesh mesh, Map<Integer, CellLevels> cache, int cell) {
		CellLevels known = cache.get(cell);
		if (known != null)
			return known;
		int factor = mesh.coarseFactor(), columns = factor * factor;
		int originX = cell % mesh.coarseWidth() * factor, originY = cell / mesh.coarseWidth() * factor;
		short[] first = new short[columns + 1];
		for (int column = 0; column < columns; column++) {
			int x = originX + column % factor, y = originY + column / factor;
			first[column + 1] = (short) (first[column] + (mesh.contains(x, y) ? mesh.surfaceCount(x, y) : 0));
		}
		int total = first[columns];
		int[] parent = new int[total];
		for (int i = 0; i < total; i++)
			parent[i] = i;
		for (int column = 0; column < columns; column++) {
			int x = originX + column % factor, y = originY + column / factor;
			for (int surface = 0; surface < first[column + 1] - first[column]; surface++) {
				if (!mesh.isWalkable(x, y, surface))
					continue;
				float z = mesh.surfaceZ(x, y, surface);
				for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
					int localX = column % factor + NEIGHBOUR_X[direction], localY = column / factor + NEIGHBOUR_Y[direction];
					int other = localY * factor + localX;
					if (localX < 0 || localY < 0 || localX >= factor || localY >= factor || other < column)
						continue; // outside the cell, or a pair already looked at from the other side
					float climb = maxClimb(mesh, NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0);
					for (int neighbour = 0; neighbour < first[other + 1] - first[other]; neighbour++) {
						if (mesh.isWalkable(originX + localX, originY + localY, neighbour)
							&& Math.abs(mesh.surfaceZ(originX + localX, originY + localY, neighbour) - z) <= climb)
							union(parent, first[column] + surface, first[other] + neighbour);
					}
				}
			}
		}

		// the pieces by size, largest first, so that the ones dropped past the limit are crumbs
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int column = 0; column < columns; column++) {
			for (int surface = 0; surface < first[column + 1] - first[column]; surface++) {
				if (mesh.isWalkable(originX + column % factor, originY + column / factor, surface))
					sizes.merge(find(parent, first[column] + surface), 1, Integer::sum);
			}
		}
		List<Integer> roots = new ArrayList<>(sizes.keySet());
		roots.sort((a, b) -> Integer.compare(sizes.get(b), sizes.get(a)));
		int count = Math.min(MAX_LEVELS, roots.size());
		Map<Integer, Integer> levelOfRoot = new HashMap<>();
		for (int level = 0; level < count; level++)
			levelOfRoot.put(roots.get(level), level);

		byte[] levelOfSurface = new byte[total];
		float[] low = new float[count], high = new float[count], spotX = new float[count], spotY = new float[count], spotZ = new float[count];
		float[] spotDistance = new float[count];
		Arrays.fill(levelOfSurface, (byte) -1);
		Arrays.fill(low, Float.MAX_VALUE);
		Arrays.fill(high, -Float.MAX_VALUE);
		Arrays.fill(spotDistance, Float.MAX_VALUE);
		float middle = (factor - 1) / 2f;
		for (int column = 0; column < columns; column++) {
			int x = originX + column % factor, y = originY + column / factor;
			for (int surface = 0; surface < first[column + 1] - first[column]; surface++) {
				if (!mesh.isWalkable(x, y, surface))
					continue;
				Integer level = levelOfRoot.get(find(parent, first[column] + surface));
				if (level == null)
					continue;
				float z = mesh.surfaceZ(x, y, surface);
				levelOfSurface[first[column] + surface] = level.byteValue();
				low[level] = Math.min(low[level], z);
				high[level] = Math.max(high[level], z);
				float distance = (float) Math.hypot(column % factor - middle, column / factor - middle);
				if (distance < spotDistance[level]) {
					spotDistance[level] = distance;
					spotX[level] = (x + 0.5f) * mesh.cellSize();
					spotY[level] = (y + 0.5f) * mesh.cellSize();
					spotZ[level] = z;
				}
			}
		}
		CellLevels levels = new CellLevels(first, levelOfSurface, low, high, spotX, spotY, spotZ);
		cache.put(cell, levels);
		return levels;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int rootA = find(parent, a), rootB = find(parent, b);
		if (rootA != rootB)
			parent[rootB] = rootA;
	}

	/**
	 * @return The stretch of the cell a position stands on: the one with a walkable cell nearest it, a metre of height counting as four across, since the floor above a body is
	 *         close across and far up. -1 when the cell has none.
	 */
	private static int nearestLevel(Navmesh mesh, CellLevels levels, int cell, float x, float y, float z) {
		int factor = mesh.coarseFactor(), originX = cell % mesh.coarseWidth() * factor, originY = cell / mesh.coarseWidth() * factor;
		int best = -1;
		float closest = Float.MAX_VALUE;
		for (int column = 0; column < factor * factor; column++) {
			int cellX = originX + column % factor, cellY = originY + column / factor;
			int surfaces = levels.firstOfColumn()[column + 1] - levels.firstOfColumn()[column];
			for (int surface = 0; surface < surfaces; surface++) {
				int level = levels.levelAt(column, surface);
				if (level == -1)
					continue;
				float score = (float) Math.hypot((cellX + 0.5f) * mesh.cellSize() - x, (cellY + 0.5f) * mesh.cellSize() - y)
					+ 4 * Math.abs(mesh.surfaceZ(cellX, cellY, surface) - z);
				if (score < closest) {
					closest = score;
					best = level;
				}
			}
		}
		return best;
	}

	/**
	 * @return true when a body can step from a stretch of one coarse cell onto a stretch of its neighbour: some cell of the first, along the border, has a neighbour in the second
	 *         within a step. Stretches close in height were taken as joined, and along a cliff or a low wall they are not: Esoterrace's rough route ran up a bank the fine search could
	 *         not climb, and every stretch after it ran out of nodes, while the walk round was 517 m for 82 across.
	 */
	private static boolean levelsMeet(Navmesh mesh, int cell, CellLevels here, int level, int nextCell, CellLevels there, int next) {
		int factor = mesh.coarseFactor(), width = mesh.coarseWidth();
		int ax = cell % width * factor, ay = cell / width * factor, bx = nextCell % width * factor, by = nextCell / width * factor;
		// the cells of the first that touch the second: a strip along a side, or one cell at a corner
		for (int y = Math.max(ay, by - 1); y <= Math.min(ay + factor - 1, by + factor); y++) {
			for (int x = Math.max(ax, bx - 1); x <= Math.min(ax + factor - 1, bx + factor); x++) {
				int column = (y - ay) * factor + (x - ax);
				int surfaces = here.firstOfColumn()[column + 1] - here.firstOfColumn()[column];
				for (int surface = 0; surface < surfaces; surface++) {
					if (here.levelAt(column, surface) != level)
						continue;
					float z = mesh.surfaceZ(x, y, surface);
					for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
						int nx = x + NEIGHBOUR_X[direction], ny = y + NEIGHBOUR_Y[direction];
						if (nx < bx || ny < by || nx >= bx + factor || ny >= by + factor)
							continue;
						int otherColumn = (ny - by) * factor + (nx - bx);
						int others = there.firstOfColumn()[otherColumn + 1] - there.firstOfColumn()[otherColumn];
						float climb = maxClimb(mesh, NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0);
						for (int other = 0; other < others; other++) {
							if (there.levelAt(otherColumn, other) == next && Math.abs(mesh.surfaceZ(nx, ny, other) - z) <= climb)
								return true;
						}
					}
				}
			}
		}
		return false;
	}

	/** @return How far apart two height ranges are: zero when they overlap. */
	private static float levelGap(float lowA, float highA, float lowB, float highB) {
		return Math.max(0, Math.max(lowA, lowB) - Math.min(highA, highB));
	}

	/** @return One point every {@link #GUIDE_SPACING} cells, each on ground of the stretch the rough route went through there, then a stand-in for the goal. */
	private static List<Vector3f> sampleLevelledGuide(Navmesh mesh, Map<Integer, CellLevels> levels, Map<Integer, Integer> cameFrom, int current, int start) {
		List<Integer> nodes = new ArrayList<>();
		for (int node = current; node != start; node = cameFrom.get(node))
			nodes.add(node);
		Collections.reverse(nodes);

		List<Vector3f> guide = new ArrayList<>();
		for (int i = GUIDE_SPACING - 1; i < nodes.size(); i += GUIDE_SPACING) {
			int cell = nodes.get(i) / MAX_LEVELS, level = nodes.get(i) % MAX_LEVELS;
			CellLevels stretches = levels.get(cell);
			guide.add(new Vector3f(stretches.spotX()[level], stretches.spotY()[level], stretches.spotZ()[level]));
		}
		guide.add(new Vector3f()); // stands for the goal itself, whose real coordinates the caller substitutes
		return guide;
	}

	/**
	 * @return One point every {@link #GUIDE_SPACING} coarse cells along the rough route, or an empty list if there is none.
	 */
	public static List<Vector3f> coarseRoute(Navmesh mesh, float startX, float startY, float goalX, float goalY) {
		int factor = mesh.coarseFactor();
		int startCell = coarseCellNear(mesh, mesh.cellX(startX) / factor, mesh.cellY(startY) / factor);
		int goalCell = coarseCellNear(mesh, mesh.cellX(goalX) / factor, mesh.cellY(goalY) / factor);
		if (startCell == -1 || goalCell == -1)
			return List.of();
		// Two places on different stretches of ground have no rough route either, and saying so here costs nothing. Before this the rough grid
		// answered yes, the refinement then failed at the crossing, and the journey died after the budget rather than after the question.
		//
		// Both ends can stand in more than one region — a cell 4 m across holds a valley floor and the cliff top above it — so what is wanted is a
		// region they share. Each is tried in turn: a handful at most, and the first that joins them is the route.
		for (int region : sharedRegions(mesh, startCell, goalCell)) {
			List<Vector3f> guide = coarseRoute(mesh, startCell, goalCell, region);
			if (!guide.isEmpty())
				return guide;
		}
		return List.of();
	}

	/**
	 * @return true when both ends stand on a stretch of ground the rough grid joins.
	 *         <p>
	 *         Asked so that an empty rough route can be told apart from a proof. {@link #coarseRoute} returns nothing both when the two ends share no
	 *         ground at all — which settles the question — and when its own search ran out of nodes, which settles nothing; reporting both as a
	 *         search that gave up let a bot keep walking at a goal already known to be unreachable, which is the same mistake this file's caller had
	 *         one level up.
	 */
	public static boolean shareGround(Navmesh mesh, float startX, float startY, float goalX, float goalY) {
		int factor = mesh.coarseFactor();
		int startCell = coarseCellNear(mesh, mesh.cellX(startX) / factor, mesh.cellY(startY) / factor);
		int goalCell = coarseCellNear(mesh, mesh.cellX(goalX) / factor, mesh.cellY(goalY) / factor);
		return startCell != -1 && goalCell != -1 && !sharedRegions(mesh, startCell, goalCell).isEmpty();
	}

	/** @return The stretches of ground both ends stand on, largest region id last; empty when they have none in common. */
	private static List<Integer> sharedRegions(Navmesh mesh, int startCell, int goalCell) {
		int startX = startCell % mesh.coarseWidth(), startY = startCell / mesh.coarseWidth();
		int goalX = goalCell % mesh.coarseWidth(), goalY = goalCell / mesh.coarseWidth();
		List<Integer> shared = new ArrayList<>();
		for (int index = 0; index < mesh.coarseRegionCount(startX, startY); index++) {
			int region = mesh.coarseRegion(startX, startY, index);
			if (mesh.coarseHasRegion(goalX, goalY, region))
				shared.add(region);
		}
		return shared;
	}

	private static List<Vector3f> coarseRoute(Navmesh mesh, int startCell, int goalCell, int region) {
		Map<Integer, Float> costSoFar = new HashMap<>();
		Map<Integer, Integer> cameFrom = new HashMap<>();
		PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1]));
		costSoFar.put(startCell, 0f);
		open.add(new int[] { startCell, 0 });

		int expanded = 0;
		while (!open.isEmpty() && expanded++ < MAX_COARSE_NODES) {
			int current = open.poll()[0];
			if (current == goalCell)
				return sampleGuide(mesh, cameFrom, current, startCell);

			float cost = costSoFar.get(current);
			int cellX = current % mesh.coarseWidth(), cellY = current / mesh.coarseWidth();
			for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
				int nextX = cellX + NEIGHBOUR_X[direction], nextY = cellY + NEIGHBOUR_Y[direction];
				if (!mesh.coarseHasRegion(nextX, nextY, region))
					continue; // staying inside one stretch of ground is what makes a rough route refinable
				int next = coarseKey(mesh, nextX, nextY);
				float stepCost = cost + (NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0 ? 1.41421f : 1);
				Float known = costSoFar.get(next);
				if (known != null && known <= stepCost)
					continue;
				costSoFar.put(next, stepCost);
				cameFrom.put(next, current);
				float remaining = (float) Math.hypot(nextX - goalCell % mesh.coarseWidth(), nextY - goalCell / mesh.coarseWidth());
				open.add(new int[] { next, Math.round(stepCost + remaining) });
			}
		}
		return List.of();
	}

	private static List<Vector3f> sampleGuide(Navmesh mesh, Map<Integer, Integer> cameFrom, int current, int startCell) {
		List<Integer> cells = new ArrayList<>();
		for (int cell = current; cell != startCell; cell = cameFrom.get(cell))
			cells.add(cell);
		Collections.reverse(cells);

		float size = mesh.coarseFactor() * mesh.cellSize();
		List<Vector3f> guide = new ArrayList<>();
		for (int i = GUIDE_SPACING - 1; i < cells.size(); i += GUIDE_SPACING) {
			int cell = cells.get(i);
			guide.add(new Vector3f((cell % mesh.coarseWidth() + 0.5f) * size, (cell / mesh.coarseWidth() + 0.5f) * size, Float.NaN));
		}
		guide.add(new Vector3f()); // stands for the goal itself, whose real coordinates the caller substitutes
		return guide;
	}

	private static int coarseKey(Navmesh mesh, int coarseX, int coarseY) {
		return coarseY * mesh.coarseWidth() + coarseX;
	}

	/** Coarse ends need snapping too: a spot can sit in a cell the rough grid calls too thin to route through. */
	static int coarseCellNear(Navmesh mesh, int coarseX, int coarseY) {
		for (int ring = 0; ring <= COARSE_SNAP_RINGS; ring++) {
			for (int offsetY = -ring; offsetY <= ring; offsetY++) {
				for (int offsetX = -ring; offsetX <= ring; offsetX++) {
					if (ring > 0 && Math.abs(offsetX) != ring && Math.abs(offsetY) != ring)
						continue;
					if (mesh.isCoarseWalkable(coarseX + offsetX, coarseY + offsetY))
						return coarseKey(mesh, coarseX + offsetX, coarseY + offsetY);
				}
			}
		}
		return -1;
	}

	/**
	 * Walks every piece of ground joined to a spot, up to a bound, and reports whether the ground ran out first.
	 * <p>
	 * It exists because asking for a route cannot answer this at any distance. Past {@link #LONG_DISTANCE} a search is planned on the coarse grid
	 * first, and every way that plan can fail is reported as a search that gave up — correctly, since one blocked corridor is no proof that another
	 * does not exist. So the only refusal a long search can prove is the one the coarse grid already settles, and the places it reads wrong are
	 * exactly the ones it cannot be asked about. Measured: 152 route probes over Eltnen took 73 seconds and proved nothing the grid had not.
	 * <p>
	 * A flood fill proves the other direction instead. It does not ask whether somewhere can be reached from here; it asks how much ground this spot
	 * has, and an answer under the bound is a proof that there is no way off it — whatever the grid says about the column overhead. The bound is what
	 * makes it cheap: open country passes it in a fraction of the ground it actually covers, and a pocket closes long before it.
	 *
	 * @param maxCells How much ground is enough to count as somewhere rather than a pocket, in cells.
	 * @return true when the fill closed within the bound, which is proof that nothing walks off this spot. false when it did not, and when the spot
	 *         has no walkable surface at all — nothing has been proved about ground that is not there.
	 */
	public static boolean isPocket(Navmesh mesh, float x, float y, float z, int maxCells) {
		long start = nodeAt(mesh, x, y, z, SNAP_RANGE);
		if (start == -1)
			return false;

		Set<Long> reached = new HashSet<>();
		ArrayDeque<Long> pending = new ArrayDeque<>();
		reached.add(start);
		pending.add(start);
		while (!pending.isEmpty()) {
			if (reached.size() > maxCells)
				return false;
			long current = pending.poll();
			int cellX = keyX(mesh, current), cellY = keyY(mesh, current);
			float z0 = mesh.surfaceZ(cellX, cellY, keySurface(current));
			for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
				int nextX = cellX + NEIGHBOUR_X[direction], nextY = cellY + NEIGHBOUR_Y[direction];
				if (!mesh.contains(nextX, nextY))
					continue;
				boolean diagonal = NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0;
				for (int surface = 0; surface < mesh.surfaceCount(nextX, nextY); surface++) {
					// the same step rule the route search uses, so ground this calls a pocket is ground that search cannot leave either
					if (!mesh.isWalkable(nextX, nextY, surface)
						|| Math.abs(mesh.surfaceZ(nextX, nextY, surface) - z0) > maxClimb(mesh, diagonal))
						continue;
					long next = key(mesh, nextX, nextY, surface);
					if (reached.add(next))
						pending.add(next);
				}
			}
		}
		return true;
	}

	private static Route findDirectPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid) {
		return findDirectPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, SNAP_RANGE, avoid);
	}

	private static Route findDirectPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ,
		float goalSnapRange, Avoidance avoid) {
		long start = nodeAt(mesh, startX, startY, startZ, SNAP_RANGE), goal = nodeAt(mesh, goalX, goalY, goalZ, goalSnapRange);
		if (start == -1 || goal == -1)
			return new Route(List.of(), false);

		Map<Long, Float> costSoFar = new HashMap<>();
		Map<Long, Long> cameFrom = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.estimatedTotal(), b.estimatedTotal()));
		costSoFar.put(start, 0f);
		open.add(new Node(start, 0));

		int expanded = 0;
		while (!open.isEmpty() && expanded++ < MAX_NODES) {
			long current = open.poll().key();
			if (current == goal)
				return new Route(smooth(mesh, rebuild(mesh, cameFrom, current, start), avoid), false);

			float cost = costSoFar.get(current);
			int cellX = keyX(mesh, current), cellY = keyY(mesh, current);
			float z = mesh.surfaceZ(cellX, cellY, keySurface(current));
			for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
				int nextX = cellX + NEIGHBOUR_X[direction], nextY = cellY + NEIGHBOUR_Y[direction];
				if (!mesh.contains(nextX, nextY))
					continue;
				for (int surface = 0; surface < mesh.surfaceCount(nextX, nextY); surface++) {
					if (!mesh.isWalkable(nextX, nextY, surface))
						continue;
					float nextZ = mesh.surfaceZ(nextX, nextY, surface);
					if (Math.abs(nextZ - z) > maxClimb(mesh, NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0))
						continue;
					long next = key(mesh, nextX, nextY, surface);
					// ground the caller wants to keep out of costs more to cross, by the multiple it names, so the search goes round it when there is a round
					float nearness = avoid.extra((nextX + 0.5f) * mesh.cellSize(), (nextY + 0.5f) * mesh.cellSize());
					float stepCost = cost + (NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0 ? 1.41421f : 1) * mesh.cellSize() * (1 + nearness);
					Float known = costSoFar.get(next);
					if (known != null && known <= stepCost)
						continue;
					costSoFar.put(next, stepCost);
					cameFrom.put(next, current);
					open.add(new Node(next, stepCost + heuristic(mesh, nextX, nextY, goalX, goalY)));
				}
			}
		}
		if (DEBUG)
			System.out.printf("walk:   from %d %d z %.1f to %d %d z %.1f, %d nodes%n", keyX(mesh, start), keyY(mesh, start), mesh.surfaceZ(keyX(mesh, start), keyY(mesh, start), keySurface(start)),
				keyX(mesh, goal), keyY(mesh, goal), mesh.surfaceZ(keyX(mesh, goal), keyY(mesh, goal), keySurface(goal)), expanded);
		return new Route(List.of(), expanded >= MAX_NODES);
	}

	/**
	 * @return How much height may separate two neighbouring cells, which is what the steepest walkable slope produces over their distance, plus a
	 *         little for the five centimetre quantization of stored heights.
	 */
	public static float maxClimb(Navmesh mesh, boolean diagonal) {
		float distance = mesh.cellSize() * (diagonal ? 1.41421f : 1);
		return distance * mesh.slopeTangent() + STEP_TOLERANCE;
	}

	/** Straight line distance, which never overestimates and so keeps the search honest. */
	private static float heuristic(Navmesh mesh, int cellX, int cellY, float goalX, float goalY) {
		float dx = (cellX + 0.5f) * mesh.cellSize() - goalX, dy = (cellY + 0.5f) * mesh.cellSize() - goalY;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	/** Walks the trail of predecessors back to the start, then turns each grid node into a world position. */
	private static List<Vector3f> rebuild(Navmesh mesh, Map<Long, Long> cameFrom, long current, long start) {
		List<Long> nodes = new ArrayList<>();
		for (long node = current; node != start; node = cameFrom.get(node))
			nodes.add(node);
		nodes.add(start);
		Collections.reverse(nodes);

		List<Vector3f> path = new ArrayList<>(nodes.size());
		for (long node : nodes) {
			int cellX = keyX(mesh, node), cellY = keyY(mesh, node);
			path.add(new Vector3f((cellX + 0.5f) * mesh.cellSize(), (cellY + 0.5f) * mesh.cellSize(),
				mesh.surfaceZ(cellX, cellY, keySurface(node))));
		}
		return path;
	}

	/**
	 * Drops every waypoint that can be skipped without leaving walkable ground. A grid path turns at every cell; a bot only needs the corners.
	 */
	private static List<Vector3f> smooth(Navmesh mesh, List<Vector3f> path, Avoidance avoid) {
		List<Vector3f> pulled = new ArrayList<>();
		pulled.add(path.get(0));
		int anchor = 0;
		while (anchor < path.size() - 1) {
			int furthest = anchor + 1;
			for (int candidate = path.size() - 1; candidate > anchor + 1; candidate--) {
				if (hasClearLine(mesh, path.get(anchor), path.get(candidate), avoid)) {
					furthest = candidate;
					break;
				}
			}
			pulled.add(path.get(furthest));
			anchor = furthest;
		}
		return pulled;
	}

	/**
	 * @return true if a straight walk between the two points stays on walkable ground the whole way.
	 *         <p>
	 *         One line is enough, because the ground it walks is already eroded by a body's width: every cell it may cross is a cell a body fits
	 *         in. Checking parallel lines either side of it on top of that asks for two bodies' width, which found far fewer corners worth cutting
	 *         and left routes stepping half a metre at a time through gaps a player strolls through.
	 */
	private static boolean hasClearLine(Navmesh mesh, Vector3f from, Vector3f to, Avoidance avoid) {
		float distance = (float) Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.y - from.y) * (to.y - from.y));
		int steps = Math.max(1, (int) (distance / (mesh.cellSize() / 2)));
		float previousZ = from.z;
		for (int step = 1; step <= steps; step++) {
			float ratio = (float) step / steps;
			float x = from.x + (to.x - from.x) * ratio, y = from.y + (to.y - from.y) * ratio;
			// a corner is only cut across ground that does not matter: the path round it is the one that was searched for
			if (avoid.extra(x, y) > 0)
				return false;
			int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
			if (!mesh.contains(cellX, cellY))
				return false;
			float z = nearestWalkableZ(mesh, cellX, cellY, previousZ, maxClimb(mesh, true));
			if (Float.isNaN(z))
				return false;
			previousZ = z;
		}
		return true;
	}

	private static float nearestWalkableZ(Navmesh mesh, int cellX, int cellY, float z, float range) {
		float best = Float.NaN, bestDistance = range;
		for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
			if (!mesh.isWalkable(cellX, cellY, surface))
				continue;
			float candidate = mesh.surfaceZ(cellX, cellY, surface);
			if (Math.abs(candidate - z) <= bestDistance) {
				bestDistance = Math.abs(candidate - z);
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Finds walkable ground near a spot, for putting a character down on it.
	 * <p>
	 * A spot picked on a map — a scattering of villagers around a village centre, a position saved before the mesh knew better — can land inside the
	 * geometry: under a mushroom cap with no head room, inside a tree trunk, against a wall. A character left there is not merely misplaced, it is
	 * unroutable: nothing walkable is within reach of it, so every journey it ever attempts is refused, and it spends its life asking.
	 *
	 * The search is bounded vertically, and that bound is the point of it. A spot can have ground at the valley floor and again on the cliff forty
	 * metres above it; left to take whichever is nearest in plan view, this put a villager on a clifftop seventy metres above the village it was
	 * meant to live in, with no route back down. Ground a body could have walked to is ground within a step or two of where it was going.
	 *
	 * @return The nearest walkable ground within {@link #SNAP_RINGS} cells and {@link #SNAP_RANGE} metres of height, or null when there is none.
	 */
	public static Vector3f nearestGround(Navmesh mesh, float x, float y, float z) {
		long node = nodeAt(mesh, x, y, z, SNAP_RANGE);
		if (node == -1)
			return null;
		int cellX = keyX(mesh, node), cellY = keyY(mesh, node);
		return new Vector3f((cellX + 0.5f) * mesh.cellSize(), (cellY + 0.5f) * mesh.cellSize(), mesh.surfaceZ(cellX, cellY, keySurface(node)));
	}

	/**
	 * Finds the node to start from or aim at.
	 * <p>
	 * The exact spot is often not walkable: a player stands on a rock or against a wall, and the grid is deliberately conservative about head room
	 * and slopes. Snapping outwards to the nearest walkable cell is what makes "come here" work at all, and the last few metres are the reactive
	 * layer's job anyway.
	 */
	private static long nodeAt(Navmesh mesh, float x, float y, float z, float snapRange) {
		int centreX = mesh.cellX(x), centreY = mesh.cellY(y);
		for (int ring = 0; ring <= SNAP_RINGS; ring++) {
			long found = -1;
			float closest = Float.MAX_VALUE;
			for (int offsetY = -ring; offsetY <= ring; offsetY++) {
				for (int offsetX = -ring; offsetX <= ring; offsetX++) {
					if (ring > 0 && Math.abs(offsetX) != ring && Math.abs(offsetY) != ring)
						continue; // already covered by a smaller ring
					int cellX = centreX + offsetX, cellY = centreY + offsetY;
					if (!mesh.contains(cellX, cellY))
						continue;
					for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
						if (!mesh.isWalkable(cellX, cellY, surface))
							continue;
						float gap = Math.abs(mesh.surfaceZ(cellX, cellY, surface) - z);
						if (gap > snapRange || gap >= closest)
							continue;
						closest = gap;
						found = key(mesh, cellX, cellY, surface);
					}
				}
			}
			if (found != -1)
				return found;
		}
		return -1;
	}

	private static long key(Navmesh mesh, int cellX, int cellY, int surface) {
		return ((long) cellY * mesh.width() + cellX) << 8 | surface;
	}

	private static int keyX(Navmesh mesh, long key) {
		return (int) ((key >>> 8) % mesh.width());
	}

	private static int keyY(Navmesh mesh, long key) {
		return (int) ((key >>> 8) / mesh.width());
	}

	private static int keySurface(long key) {
		return (int) (key & 0xFF);
	}
}
