package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
		Route direct = findDirectPath(mesh, startX, startY, startZ, goalX, goalY, goalZ);
		if (!direct.isEmpty() || !direct.gaveUp())
			return direct;
		// The fine search ran out of room, which distance alone does not predict: thirty metres out of a thicket costs more of it than three hundred
		// across open grass, because what it must explore grows with the clutter rather than with the journey. Bots walked into a wood in Poeta and
		// could not plan their way out of it, at any range. The rough grid knows the way through, so it is worth asking even for a short hop.
		return findLongPath(mesh, startX, startY, startZ, goalX, goalY, goalZ);
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

	/**
	 * @return true if a straight walk between the two points stays on walkable ground the whole way.
	 *         <p>
	 *         One line is enough, because the ground it walks is already eroded by a body's width: every cell it may cross is a cell a body fits
	 *         in. Checking parallel lines either side of it on top of that asks for two bodies' width, which found far fewer corners worth cutting
	 *         and left routes stepping half a metre at a time through gaps a player strolls through.
	 */
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
