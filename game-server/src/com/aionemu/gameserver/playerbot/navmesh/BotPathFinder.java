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

	/** How high a bot steps up between two neighbouring cells. Higher than this is a wall or a jump, and neither is walking. */
	public static final float MAX_STEP = 0.5f;
	/** How far above or below the requested height a start or goal surface may be found. */
	private static final float SNAP_RANGE = 3f;
	/** How many cells outwards to look for walkable ground when the exact spot is not, about six metres. */
	private static final int SNAP_RINGS = 12;
	/** Search budget. A route that needs more than this is either impossible or long enough to want waypoints of its own. */
	private static final int MAX_NODES = 1_000_000;

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

		public boolean isEmpty() {
			return waypoints.isEmpty();
		}
	}

	/**
	 * @return The route, whose waypoints start at the given position. Check {@link Route#gaveUp()} before treating an empty one as impossible.
	 */
	public static Route findPath(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ) {
		long start = nodeAt(mesh, startX, startY, startZ), goal = nodeAt(mesh, goalX, goalY, goalZ);
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
					if (Math.abs(nextZ - z) > MAX_STEP)
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
			float z = nearestWalkableZ(mesh, cellX, cellY, previousZ, MAX_STEP);
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
	private static long nodeAt(Navmesh mesh, float x, float y, float z) {
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
						if (gap > SNAP_RANGE || gap >= closest)
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
