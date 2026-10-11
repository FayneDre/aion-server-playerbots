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
	/**
	 * How long, in milliseconds, one plan may spend trying chains before it gives up. Every chain tried is several walking searches, and a goal no chain reaches would otherwise try them all:
	 * the generator's own check of random journeys went from seconds to an hour on one map for want of this.
	 */
	private static final long BUDGET_MILLIS = 2000;
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

		// a 4 m cell can hold the ground the body stands on and ground above or below it, so which of them is the body's own is not known: each is tried as the start in turn
		BotPathFinder.Route failure = new BotPathFinder.Route(List.of(), false);
		long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
		if (DEBUG)
			System.out.printf("glide: starts %s goals %s%n", starts, goals);
		for (int start : starts) {
			for (List<Integer> chain : chainsFrom(mesh, start, goals)) {
				if (System.currentTimeMillis() > deadline)
					return new BotPathFinder.Route(List.of(), true);
				BotPathFinder.Route route = follow(mesh, chain, startX, startY, startZ, goalX, goalY, goalZ, avoid, air, deadline);
				if (!route.isEmpty())
					return route;
				failure = route;
			}
		}
		if (DEBUG && failure.isEmpty())
			System.out.printf("glide: no chain from any of %s to %s%n", starts, goals);
		return failure;
	}

	/**
	 * @return The chains of stretches that lead from {@code start} to each of the goals, the fewest glides first and at most {@link #CHAINS_TRIED}, both ends included. Each goal is a
	 *         stretch that touches the goal's 4 m cell, which says only that the goal may be on it, so several are offered and the walk to the goal itself decides.
	 */
	private static List<List<Integer>> chainsFrom(Navmesh mesh, int start, List<Integer> goals) {
		Map<Integer, Integer> cameFrom = new HashMap<>();
		ArrayDeque<Integer> pending = new ArrayDeque<>();
		cameFrom.put(start, 0);
		pending.add(start);
		List<List<Integer>> chains = new ArrayList<>();
		while (!pending.isEmpty() && chains.size() < CHAINS_TRIED) {
			int region = pending.poll();
			if (goals.contains(region)) {
				List<Integer> chain = new ArrayList<>();
				for (int step = region; step != 0; step = cameFrom.get(step))
					chain.add(0, step);
				chains.add(chain);
			}
			for (GlideLink link : mesh.glidesFrom(region)) {
				if (cameFrom.putIfAbsent(link.toRegion(), region) == null)
					pending.add(link.toRegion());
			}
		}
		return chains;
	}

	/** Walks each stretch of the chain to the edge it is left from, and glides down to the next. */
	private static BotPathFinder.Route follow(Navmesh mesh, List<Integer> chain, float startX, float startY, float startZ, float goalX, float goalY, float goalZ, Avoidance avoid,
		AirTest air, long deadline) {
		List<Vector3f> waypoints = new ArrayList<>();
		Set<Integer> glides = new HashSet<>();
		float x = startX, y = startY, z = startZ;
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
			boolean done = false;
			for (GlideLink link : choices.subList(0, Math.min(LINKS_TRIED, choices.size()))) {
				if (System.currentTimeMillis() > deadline)
					return new BotPathFinder.Route(List.of(), true);
				if (!air.isClear(link))
					continue;
				BotPathFinder.Route walk = BotPathFinder.findWalkingPath(mesh, x, y, z, link.fromX(), link.fromY(), link.fromZ(), avoid);
				if (walk.isEmpty()) {
					if (DEBUG)
						System.out.printf("glide:   link %.1f %.1f %.1f -> %.1f %.1f %.1f: walk from %.1f %.1f %.1f is empty (gave up %b)%n", link.fromX(), link.fromY(), link.fromZ(),
							link.toX(), link.toY(), link.toZ(), x, y, z, walk.gaveUp());
					continue;
				}
				append(waypoints, walk.waypoints());
				waypoints.add(new Vector3f(link.toX(), link.toY(), link.toZ()));
				glides.add(waypoints.size() - 1);
				x = link.toX();
				y = link.toY();
				z = link.toZ();
				done = true;
				break;
			}
			if (!done) {
				if (DEBUG)
					System.out.printf("glide: chain %s stops at hop %d: %d links from %d to %d%n", chain, hop, choices.size(), from, to);
				return new BotPathFinder.Route(List.of(), true);
			}
		}
		BotPathFinder.Route last = BotPathFinder.findWalkingPath(mesh, x, y, z, goalX, goalY, goalZ, avoid);
		if (last.isEmpty()) {
			if (DEBUG)
				System.out.printf("glide: chain %s ends at %.0f %.0f %.0f and cannot walk to the goal (gave up %b)%n", chain, x, y, z, last.gaveUp());
			return new BotPathFinder.Route(List.of(), true);
		}
		append(waypoints, last.waypoints());
		return new BotPathFinder.Route(waypoints, false, glides);
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

	/** Whether the air along a glide is clear. */
	@FunctionalInterface
	interface AirTest {

		AirTest ALWAYS = link -> true;

		boolean isClear(GlideLink link);
	}
}
