package com.aionemu.gameserver.playerbot.navmesh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Plans a route that goes down by gliding where the ground does not join, using the links {@link GlideLinks} found.
 * <p>
 * The walking planner answers "no way" when two places stand on different stretches of ground, and in a dungeon built to be glided that is the usual answer. This looks for the
 * chain of stretches that leads from one to the other, fewest glides first, then walks each stretch to the edge where the glide leaves and lands the next one.
 */
final class GlideRoutes {

	/** How many links of one hop are tried before the hop is given up: the nearest edge is not always one a body can walk to. */
	private static final int LINKS_TRIED = 6;
	/** How many chains of stretches are followed to a goal before the journey is given up. */
	private static final int CHAINS_TRIED = 8;
	/** How many times the chains are worked out again from one start, leaving out the links found blocked. */
	private static final int RECHAINS = 4;
	/**
	 * How long, in milliseconds, one plan may spend trying chains before it gives up. Every chain tried is several walking searches, and a goal no chain reaches would otherwise try them all:
	 * the generator's own check of random journeys went from seconds to an hour on one map for want of this.
	 */
	private static final long BUDGET_MILLIS = 2000;
	/** The same where the links are flights: a crossing of Reshanta's upper layer lands beside a walk of 700 m, which takes three seconds on its own. */
	private static final long FLIGHT_BUDGET_MILLIS = 5000;
	/** How many metres across one metre of height counts for, when telling which floor round the body is its own: floors stacked in a tower are close across and far apart in height. */
	private static final float HEIGHT_WEIGHT = 4;
	private static final boolean DEBUG = Boolean.getBoolean("navmesh.glideDebug");

	private GlideRoutes() {
	}

	/**
	 * @param air Whether a body can pass from the first point to the second, the air between being clear. The game's geometry answers it in the server; the offline tools have none and pass
	 *          a test that says yes.
	 * @return The route, whose glide landings are named in {@link BotPathFinder.Route#glides()}; an empty one when no chain of links joins the two places.
	 */
	static BotPathFinder.Route plan(Navmesh mesh, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid, AirTest air) {
		int factor = mesh.coarseFactor();
		int startCell = BotPathFinder.coarseCellNear(mesh, mesh.cellX(startX) / factor, mesh.cellY(startY) / factor);
		int goalCell = BotPathFinder.coarseCellNear(mesh, mesh.cellX(goalX) / factor, mesh.cellY(goalY) / factor);
		if (startCell == -1 || goalCell == -1)
			return new BotPathFinder.Route(List.of(), false);
		List<Integer> starts = regionsOf(mesh, startCell), goals = regionsOf(mesh, goalCell);
		// in a tower the cells round the body list thirty floors, and tried in the grid's order the body's own came fifth, after the budget had run out on the others: the
		// Illuminary Obelisk measured nothing reached. The floor whose edges are nearest, height counted most, is the body's own far more often than not
		starts.sort(Comparator.comparingDouble(region -> nearestEdge(mesh, region, startX, startY, startZ)));
		goals.sort(Comparator.comparingDouble(region -> nearestEdge(mesh, region, goalX, goalY, goalZ)));

		// a 4 m cell can hold the ground the body stands on and ground above or below it, so which of them is the body's own is not known: each is tried as the start in turn
		// a proof that no glide gets there only when every chain was followed to its end and every walk in it proved its own way shut: a chain left untried, or a walk that
		// ran out of room, proves nothing. Answering "gave up" for any failed chain turned the routes up to Verteron's plateau, proved shut before the links, into undecided ones
		boolean undecided = false;
		boolean flown = mesh.hasFlights();
		long deadline = System.currentTimeMillis() + (flown ? FLIGHT_BUDGET_MILLIS : BUDGET_MILLIS);
		if (DEBUG)
			System.out.printf("glide: starts %s goals %s%n", starts, goals);
		// links found blocked are left out and the chains worked out again: a chain whose one link for a hop is blocked was the fewest hops and not the only
		// way, and the next fewest was never tried. From the Elyos island of Reshanta it was the only chain offered, and its one crossing went under an overhang
		Set<GlideLink> blocked = new HashSet<>();
		for (int start : starts) {
			for (int round = 0; round < RECHAINS; round++) {
				int blockedBefore = blocked.size();
				List<List<Integer>> chains = flown ? shortestChains(mesh, start, goals, blocked, air, startX, startY, startZ, goalX, goalY, goalZ)
					: chainsFrom(mesh, start, goals, blocked, air);
				undecided |= chains.size() > CHAINS_TRIED;
				for (List<Integer> chain : chains.subList(0, Math.min(CHAINS_TRIED, chains.size()))) {
					if (System.currentTimeMillis() > deadline)
						return new BotPathFinder.Route(List.of(), true);
					BotPathFinder.Route route = follow(mesh, chain, startX, startY, startZ, goalX, goalY, goalZ, avoid, air, deadline, blocked);
					if (!route.isEmpty())
						return route;
					undecided |= route.gaveUp();
				}
				if (blocked.size() == blockedBefore)
					break; // nothing new was found blocked, so the same chains would come back
			}
		}
		if (DEBUG && !undecided)
			System.out.printf("glide: no chain from any of %s to %s gets there%n", starts, goals);
		return new BotPathFinder.Route(List.of(), undecided);
	}

	/**
	 * @return The chains of stretches that lead from {@code start} to the goals, in the order the goals are given, both ends included; each the
	 *         fewest glides to its goal. Each goal is a stretch that touches the goal's 4 m cell, which says only that the goal may be on it, so several are offered and the walk to the
	 *         goal itself decides. Taken fewest glides first, the floor below the goal came before the goal's own and the budget ran out on it.
	 */
	private static List<List<Integer>> chainsFrom(Navmesh mesh, int start, List<Integer> goals, Set<GlideLink> blocked, AirTest air) {
		Map<Integer, Integer> cameFrom = new HashMap<>();
		ArrayDeque<Integer> pending = new ArrayDeque<>();
		cameFrom.put(start, 0);
		pending.add(start);
		while (!pending.isEmpty()) {
			for (GlideLink link : mesh.glidesFrom(pending.poll())) {
				if (blocked.contains(link) || !air.mayLand(link))
					continue;
				if (cameFrom.putIfAbsent(link.toRegion(), link.fromRegion()) == null)
					pending.add(link.toRegion());
			}
		}
		List<List<Integer>> chains = new ArrayList<>();
		for (int goal : goals) {
			if (!cameFrom.containsKey(goal))
				continue;
			List<Integer> chain = new ArrayList<>();
			for (int step = goal; step != 0; step = cameFrom.get(step))
				chain.add(0, step);
			chains.add(chain);
		}
		return chains;
	}

	/**
	 * @return The chains that lead from {@code start} to the goals, the shortest first, by the distance a body covers: the walks between links counted
	 *         straight, the links by their length. For a map whose links are flights, where fewest hops is the wrong measure: from an island of Reshanta's
	 *         upper layer the one crossing straight onto the large island landed 700 m from the goal, a walk of 2100 m, while the islands near the goal
	 *         were never tried.
	 */
	private static List<List<Integer>> shortestChains(Navmesh mesh, int start, List<Integer> goals, Set<GlideLink> blocked, AirTest air, float startX, float startY,
		float startZ, float goalX, float goalY, float goalZ) {
		Map<GlideLink, Double> cost = new HashMap<>();
		Map<GlideLink, GlideLink> cameFrom = new HashMap<>();
		// entries carry the cost they were queued at and a cheaper one is queued again: the stale one is skipped when it comes out
		java.util.PriorityQueue<Queued> pending = new java.util.PriorityQueue<>(Comparator.comparingDouble(Queued::cost));
		for (GlideLink link : mesh.glidesFrom(start)) {
			if (blocked.contains(link) || !air.mayLand(link))
				continue;
			double through = distance(startX, startY, startZ, link.fromX(), link.fromY(), link.fromZ()) + FlightLinks.length(link);
			if (through < cost.getOrDefault(link, Double.MAX_VALUE)) {
				cost.put(link, through);
				pending.add(new Queued(link, through));
			}
		}
		Set<GlideLink> done = new HashSet<>();
		Map<Integer, GlideLink> bestInto = new HashMap<>();
		Map<Integer, Double> bestTotal = new HashMap<>();
		Set<Integer> goalSet = new HashSet<>(goals);
		while (!pending.isEmpty()) {
			GlideLink link = pending.poll().link();
			if (!done.add(link))
				continue;
			double here = cost.get(link);
			if (goalSet.contains(link.toRegion())) {
				double total = here + distance(link.toX(), link.toY(), link.toZ(), goalX, goalY, goalZ);
				if (total < bestTotal.getOrDefault(link.toRegion(), Double.MAX_VALUE)) {
					bestTotal.put(link.toRegion(), total);
					bestInto.put(link.toRegion(), link);
				}
			}
			for (GlideLink next : mesh.glidesFrom(link.toRegion())) {
				if (next.toRegion() == start || blocked.contains(next) || done.contains(next) || !air.mayLand(next))
					continue;
				double through = here + distance(link.toX(), link.toY(), link.toZ(), next.fromX(), next.fromY(), next.fromZ()) + FlightLinks.length(next);
				if (through < cost.getOrDefault(next, Double.MAX_VALUE)) {
					cost.put(next, through);
					cameFrom.put(next, link);
					pending.add(new Queued(next, through));
				}
			}
		}
		List<Integer> reached = new ArrayList<>(bestInto.keySet());
		reached.sort(Comparator.comparingDouble(bestTotal::get));
		List<List<Integer>> chains = new ArrayList<>();
		for (int goal : reached) {
			List<Integer> chain = new ArrayList<>();
			for (GlideLink step = bestInto.get(goal); step != null; step = cameFrom.get(step))
				chain.add(0, step.toRegion());
			chain.add(0, start);
			chains.add(chain);
		}
		return chains;
	}

	private record Queued(GlideLink link, double cost) {
	}

	private static double distance(float ax, float ay, float az, float bx, float by, float bz) {
		return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
	}

	/** Walks each stretch of the chain to the edge it is left from, and glides down to the next. */
	private static BotPathFinder.Route follow(Navmesh mesh, List<Integer> chain, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid,
		AirTest air, long deadline, Set<GlideLink> blocked) {
		List<Vector3f> waypoints = new ArrayList<>();
		Set<Integer> glides = new HashSet<>(), flights = new HashSet<>();
		float x = startX, y = startY, z = startZ;
		GlideLink lastTaken = null;
		for (int hop = 0; hop + 1 < chain.size(); hop++) {
			int from = chain.get(hop), to = chain.get(hop + 1);
			final float nearX = x, nearY = y;
			List<GlideLink> choices = new ArrayList<>();
			for (GlideLink link : mesh.glidesFrom(from)) {
				if (link.toRegion() == to)
					choices.add(link);
			}
			// the edge nearest to where the body stands, and landing nearest to where it is going: a long walk to a convenient edge costs more than a long glide
			choices.sort(Comparator.comparingDouble(link -> Math.hypot(link.fromX() - nearX, link.fromY() - nearY) + Math.hypot(link.toX() - goalX, link.toY() - goalY)));
			boolean done = false, undecided = choices.size() > LINKS_TRIED;
			for (GlideLink link : choices.subList(0, Math.min(LINKS_TRIED, choices.size()))) {
				if (System.currentTimeMillis() > deadline)
					return new BotPathFinder.Route(List.of(), true);
				if (blocked.contains(link))
					continue;
				if (!air.isClear(link)) {
					blocked.add(link);
					continue;
				}
				BotPathFinder.Route walk = BotPathFinder.findWalkingPath(mesh, x, y, z, link.fromX(), link.fromY(), link.fromZ(), avoid);
				if (walk.isEmpty()) {
					undecided |= walk.gaveUp();
					// proved out of reach from where the hop before landed, it is left out of the next round's chains like a blocked one: on Reshanta every
					// chain of the first round shared that hop, and every one of them stopped at it until the budget ran out
					// and where the links are flights, one the walk ran out of room to reach as well, for the rest of this plan: from Reshanta's upper layer
					// the take-off 4 m away and 7.5 m up on the floor above was walked at, and given up on, once for every chain that began there
					if (!walk.gaveUp() || mesh.hasFlights())
						blocked.add(link);
					// the first walk, from where the body stands: refused or given up, the body is most likely not on that stretch at all but on the floor
					// below or above it, and every other take-off of it would cost the same two seconds to find out. They are all left out
					if (hop == 0 && mesh.hasFlights())
						blocked.addAll(mesh.glidesFrom(from));
					if (DEBUG)
						System.out.printf("glide:   link %.1f %.1f %.1f -> %.1f %.1f %.1f: walk from %.1f %.1f %.1f is empty (gave up %b)%n", link.fromX(), link.fromY(), link.fromZ(),
							link.toX(), link.toY(), link.toZ(), x, y, z, walk.gaveUp());
					continue;
				}
				append(waypoints, walk.waypoints());
				waypoints.add(new Vector3f(link.toX(), link.toY(), link.toZ()));
				(link.flight() ? flights : glides).add(waypoints.size() - 1);
				lastTaken = link;
				x = link.toX();
				y = link.toY();
				z = link.toZ();
				done = true;
				break;
			}
			if (!done) {
				if (DEBUG)
					System.out.printf("glide: chain %s stops at hop %d: %d links from %d to %d%n", chain, hop, choices.size(), from, to);
				return new BotPathFinder.Route(List.of(), undecided);
			}
		}
		BotPathFinder.Route last = BotPathFinder.findWalkingPath(mesh, x, y, z, goalX, goalY, goalZ, avoid);
		if (last.isEmpty()) {
			if (DEBUG)
				System.out.printf("glide: chain %s ends at %.0f %.0f %.0f and cannot walk to the goal (gave up %b)%n", chain, x, y, z, last.gaveUp());
			// where the links are flights, the landing that leaves too long a walk is left out of the next chains: another island lands nearer
			if (lastTaken != null && mesh.hasFlights())
				blocked.add(lastTaken);
			return new BotPathFinder.Route(List.of(), last.gaveUp());
		}
		append(waypoints, last.waypoints());
		return new BotPathFinder.Route(waypoints, false, glides, flights);
	}

	/** @return How far the nearest edge a glide leaves this stretch from is, a metre of height counting as {@link #HEIGHT_WEIGHT} across; infinite when none leaves it. */
	private static double nearestEdge(Navmesh mesh, int region, float x, float y, float z) {
		double nearest = Double.POSITIVE_INFINITY;
		for (GlideLink link : mesh.glidesFrom(region))
			nearest = Math.min(nearest, Math.hypot(link.fromX() - x, link.fromY() - y) + HEIGHT_WEIGHT * Math.abs(link.fromZ() - z));
		return nearest;
	}

	/** Adds a stretch of the route, leaving out its first waypoint when it only repeats where the one before ended. */
	private static void append(List<Vector3f> route, List<Vector3f> stretch) {
		route.addAll(route.isEmpty() ? stretch : stretch.subList(1, stretch.size()));
	}

	/**
	 * @return The stretches of ground in the 4 m cell and the eight round it, the cell's own first. The coarse grid keeps a region in a cell only where it covers a fair part of it, so the
	 *         ground a body stands on, at the edge of a platform, can be listed in the next cell and not its own.
	 */
	private static List<Integer> regionsOf(Navmesh mesh, int cell) {
		int centreX = cell % mesh.coarseWidth(), centreY = cell / mesh.coarseWidth();
		List<Integer> regions = new ArrayList<>();
		for (int ring = 0; ring <= 1; ring++) {
			for (int y = centreY - ring; y <= centreY + ring; y++) {
				for (int x = centreX - ring; x <= centreX + ring; x++) {
					if (ring > 0 && Math.abs(x - centreX) != ring && Math.abs(y - centreY) != ring || x < 0 || y < 0 || x >= mesh.coarseWidth() || y >= mesh.coarseHeight())
						continue;
					for (int index = 0; index < mesh.coarseRegionCount(x, y); index++) {
						int region = mesh.coarseRegion(x, y, index);
						if (!regions.contains(region))
							regions.add(region);
					}
				}
			}
		}
		return regions;
	}

	/** Whether the air along a glide is clear, and whether its landing is somewhere this body may come down. */
	@FunctionalInterface
	interface AirTest {

		AirTest ALWAYS = link -> true;

		boolean isClear(GlideLink link);

		/**
		 * @return false for a landing the body must never come down at, whatever the air: asked of every link while the chains are worked out, so it must be
		 *         cheap, and a link it refuses is never part of a chain rather than found blocked one round at a time.
		 */
		default boolean mayLand(GlideLink link) {
			return true;
		}
	}
}
