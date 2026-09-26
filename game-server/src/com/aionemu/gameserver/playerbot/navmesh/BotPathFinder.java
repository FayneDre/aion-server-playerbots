package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

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
	/** Tangent of the steepest walkable slope, matching the 45° the generator accepts. */
	private static final float MAX_WALKABLE_TANGENT = 1f;
	/** How far above or below the requested height a start or goal surface may be found. */
	private static final float SNAP_RANGE = 3f;
	/** How many cells outwards to look for walkable ground when the exact spot is not, about six metres. */
	private static final int SNAP_RINGS = 12;
	/** Search budget for one stretch of fine grid. A stretch spans tens of metres, so this is already generous. */
	private static final int MAX_NODES = 150_000;
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

	private static final int[] NEIGHBOUR_X = { 1, 1, 0, -1, -1, -1, 0, 1 };
	private static final int[] NEIGHBOUR_Y = { 0, 1, 1, 1, 0, -1, -1, -1 };

	private BotPathFinder() {
	}

	private record Node(long key, float estimatedTotal) {
	}

	/**
	 * @param waypoints From start to goal, empty when there is no route.
	 * @param gaveUp true when the search ran out of budget rather than proving there is no way. The caller can retry in steps, or fall back to
	 *          walking straight at the goal, but it must not conclude the place is unreachable.
	 */
	public record Route(List<Vector3f> waypoints, boolean gaveUp) {

		static final Route NONE = new Route(List.of(), true);

		public boolean isEmpty() {
			return waypoints.isEmpty();
		}
	}

	/**
	 * @return The route, whose waypoints start at the given position. Check {@link Route#gaveUp()} before treating an empty one as impossible.
	 */
	public static Route findPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		float distance = (float) Math.hypot(goalX - startX, goalY - startY);
		if (distance > LONG_DISTANCE)
			return findLongPath(mesh, startX, startY, startZ, goalX, goalY, goalZ);
		return findDirectPath(mesh, startX, startY, startZ, goalX, goalY, goalZ);
	}

	/**
	 * Plans roughly on the 4 m grid, then fills in the detail one stretch at a time.
	 * <p>
	 * Fine A\* alone gives up past a few hundred metres, because at half metre cells the area it must consider grows faster than the distance. The
	 * coarse grid narrows that to a corridor, and each stretch of it is then a short search of the kind that already works.
	 */
	private static Route findLongPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		List<Vector3f> guide = coarseRoute(mesh, startX, startY, goalX, goalY);
		if (guide.isEmpty())
			return new Route(List.of(), true);

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
				// the coarse grid carries no height, so an intermediate guide point accepts whatever ground is there
				stretch = findDirectPath(mesh, fromX, fromY, fromZ, toX, toY, last ? goalZ : fromZ, last ? SNAP_RANGE : ANY_HEIGHT);
				if (!stretch.isEmpty())
					break;
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
		return new Route(smooth(mesh, full), false);
	}

	/** @return One point every {@link #GUIDE_SPACING} coarse cells along the rough route, or an empty list if there is none. */
	public static List<Vector3f> coarseRoute(Navmesh mesh, float startX, float startY, float goalX, float goalY) {
		int factor = mesh.coarseFactor();
		int startCell = coarseCellNear(mesh, mesh.cellX(startX) / factor, mesh.cellY(startY) / factor);
		int goalCell = coarseCellNear(mesh, mesh.cellX(goalX) / factor, mesh.cellY(goalY) / factor);
		if (startCell == -1 || goalCell == -1)
			return List.of();

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
				if (!mesh.isCoarseWalkable(nextX, nextY))
					continue;
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
			guide.add(new Vector3f((cell % mesh.coarseWidth() + 0.5f) * size, (cell / mesh.coarseWidth() + 0.5f) * size, 0));
		}
		guide.add(new Vector3f()); // stands for the goal itself, whose real coordinates the caller substitutes
		return guide;
	}

	private static int coarseKey(Navmesh mesh, int coarseX, int coarseY) {
		return coarseY * mesh.coarseWidth() + coarseX;
	}

	/** Coarse ends need snapping too: a spot can sit in a cell the rough grid calls too thin to route through. */
	private static int coarseCellNear(Navmesh mesh, int coarseX, int coarseY) {
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

	private static Route findDirectPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		return findDirectPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, SNAP_RANGE);
	}

	private static Route findDirectPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ,
		float goalSnapRange) {
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
				return new Route(smooth(mesh, rebuild(mesh, cameFrom, current, start)), false);

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
					float stepCost = cost + (NEIGHBOUR_X[direction] != 0 && NEIGHBOUR_Y[direction] != 0 ? 1.41421f : 1) * mesh.cellSize();
					Float known = costSoFar.get(next);
					if (known != null && known <= stepCost)
						continue;
					costSoFar.put(next, stepCost);
					cameFrom.put(next, current);
					open.add(new Node(next, stepCost + heuristic(mesh, nextX, nextY, goalX, goalY)));
				}
			}
		}
		return new Route(List.of(), expanded >= MAX_NODES);
	}

	/**
	 * @return How much height may separate two neighbouring cells, which is what the steepest walkable slope produces over their distance, plus a
	 *         little for the five centimetre quantization of stored heights.
	 */
	public static float maxClimb(Navmesh mesh, boolean diagonal) {
		float distance = mesh.cellSize() * (diagonal ? 1.41421f : 1);
		return distance * MAX_WALKABLE_TANGENT + STEP_TOLERANCE;
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
	private static List<Vector3f> smooth(Navmesh mesh, List<Vector3f> path) {
		List<Vector3f> pulled = new ArrayList<>();
		pulled.add(path.get(0));
		int anchor = 0;
		while (anchor < path.size() - 1) {
			int furthest = anchor + 1;
			for (int candidate = path.size() - 1; candidate > anchor + 1; candidate--) {
				if (hasClearLine(mesh, path.get(anchor), path.get(candidate))) {
					furthest = candidate;
					break;
				}
			}
			pulled.add(path.get(furthest));
			anchor = furthest;
		}
		return pulled;
	}

	/** @return true if a straight walk between the two points stays on walkable ground the whole way. */
	private static boolean hasClearLine(Navmesh mesh, Vector3f from, Vector3f to) {
		float distance = (float) Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.y - from.y) * (to.y - from.y));
		int steps = Math.max(1, (int) (distance / (mesh.cellSize() / 2)));
		float previousZ = from.z;
		for (int step = 1; step <= steps; step++) {
			float ratio = (float) step / steps;
			float x = from.x + (to.x - from.x) * ratio, y = from.y + (to.y - from.y) * ratio;
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
