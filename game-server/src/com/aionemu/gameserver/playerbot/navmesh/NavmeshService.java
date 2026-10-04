package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.playerbot.BotScheduler;

/**
 * Serves generated maps to the bots that need them.
 * <p>
 * Opening a map reads its tile directory and nothing else, so it is instant. Tiles are then read as bots walk into them, which keeps a camp down to
 * a handful of tiles instead of the 117 MB a whole map would cost.
 */
public class NavmeshService {

	private static final Logger log = LoggerFactory.getLogger(NavmeshService.class);

	private final Map<Integer, Navmesh> open = new ConcurrentHashMap<>();
	/** Maps we already know have no file, so a bot walking there does not retry on every step. */
	private final Set<Integer> missing = ConcurrentHashMap.newKeySet();

	public static NavmeshService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Plans a route for the bot, opening its map on first use.
	 * <p>
	 * The whole {@link BotPathFinder.Route} and not its waypoints, because an empty one says two different things and the caller must tell them
	 * apart: a search that gave up is no evidence, while a search that finished having found nothing is proof that the goal cannot be walked to.
	 * Collapsing both into an empty list cost 211 refused journeys on one bot in twenty-five minutes -- it stood on a roof, was proved to have no
	 * way down, and walked at the goal regardless because the proof was thrown away here.
	 *
	 * @return The route. A map with no mesh reads as a search that gave up, since the absence of a mesh proves nothing either.
	 */
	public BotPathFinder.Route findRoute(Player bot, float goalX, float goalY, float goalZ) {
		Navmesh mesh = get(bot.getWorldId());
		if (mesh == null)
			return new BotPathFinder.Route(List.of(), true);

		BotPathFinder.Route route = BotPathFinder.findPath(mesh, bot.getX(), bot.getY(), bot.getZ(), goalX, goalY, goalZ);
		if (route.isEmpty() && route.gaveUp())
			log.debug("Path search for {} gave up short of {} {}", bot.getName(), goalX, goalY);
		return route;
	}

	/**
	 * Finds walkable ground near a spot, for putting a bot down on it.
	 *
	 * @return The nearest ground a body fits on, or null when the map has no mesh or nothing walkable is near enough.
	 */
	public Vector3f groundNear(int worldId, float x, float y, float z) {
		Navmesh mesh = get(worldId);
		return mesh == null ? null : BotPathFinder.nearestGround(mesh, x, y, z);
	}

	/**
	 * Asks whether one spot can be walked to from another.
	 * <p>
	 * Standable is not the same as reachable, and scattering villagers around a village centre is where the difference bites: a ledge, the far side
	 * of a wall or a hollow is perfectly good ground that happens to connect to nothing, and a resident put on one spends its life asking for a route
	 * out of it.
	 *
	 * @return true if a route exists, or if the map has no mesh to ask.
	 */
	public boolean canReach(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		return mesh == null || !BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ).isEmpty();
	}

	/** What a reachability question came back with, for a caller that means to remember the answer. */
	public enum Reach {
		/** A route exists. */
		YES,
		/** The search finished having found nothing, which is proof that the ground does not connect. */
		NO,
		/** The search ran out of budget, or the map has no mesh to ask. Says nothing either way. */
		UNKNOWN
	}

	/**
	 * Asks whether one spot can be walked to from another, keeping the distinction {@link #canReach} throws away.
	 * <p>
	 * It exists because {@code canReach} answers a boolean, so a search that gave up for want of budget is indistinguishable from one that proved
	 * there is no way through — and a caller that writes the answer down needs them apart. A proof holds for as long as the ground does not move and
	 * is worth keeping; a give-up holds for nothing and must not be stored, or one slow search bars a shop the bot could have walked to all along.
	 */
	public Reach reach(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return Reach.UNKNOWN; // a map with no mesh refuses nothing: its bots walk without a plan, which is not the same as having nowhere to go
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ);
		if (!route.isEmpty())
			return Reach.YES;
		return route.gaveUp() ? Reach.UNKNOWN : Reach.NO;
	}

	/**
	 * Plans a journey and hands the result back when it is ready.
	 * <p>
	 * Short journeys are planned on the spot, in a few tens of milliseconds. A long one takes over a second, which no movement thread can wait for,
	 * so it is planned on a pool thread while the bot walks straight at its goal. It picks the route up at the end of its current leg.
	 */
	public void planRoute(Player bot, float goalX, float goalY, float goalZ, Consumer<BotPathFinder.Route> whenReady) {
		if (get(bot.getWorldId()) == null)
			return;
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), goalX, goalY) <= BotPathFinder.LONG_DISTANCE) {
			whenReady.accept(findRoute(bot, goalX, goalY, goalZ));
			return;
		}
		// answered even when the search found nothing: "no way through" is an answer the caller waits for, and swallowing it left bots standing
		// still for ever waiting for a plan that was never coming
		BotScheduler.getInstance().planRoute(() -> whenReady.accept(findRoute(bot, goalX, goalY, goalZ)));
	}

	/** @return The map, opening it on first use, or null when it has no generated file. */
	private Navmesh get(int mapId) {
		if (missing.contains(mapId))
			return null;
		return open.computeIfAbsent(mapId, id -> {
			if (!Files.isRegularFile(Navmesh.fileOf(id))) {
				log.info("No navmesh for map {}, bots there will walk without a plan", id);
				missing.add(id);
				return null;
			}
			try {
				Navmesh mesh = Navmesh.open(id);
				log.info("Opened navmesh for map {}, {}x{} cells", id, mesh.width(), mesh.height());
				return mesh;
			} catch (IOException e) {
				log.error("Could not open the navmesh for map " + id, e);
				missing.add(id);
				return null;
			}
		});
	}

	/**
	 * @return The stretches of walkable ground passing under a spot, or an empty array where the map has no mesh or nothing can be stood on there.
	 *         <p>
	 *         A stretch is ground a body can actually walk across, so two spots that share one are joined and two that share none are not. Read off
	 *         the coarse grid, which the mesh keeps in memory always, so this costs an array lookup — no tile is read and no route is searched. It is
	 *         a 4 m grid, so sharing a stretch is necessary for two places to be joined rather than sufficient: it settles the gross cases and leaves
	 *         the near ones to {@link #canReach}.
	 */
	public int[] regionsAt(int worldId, float x, float y) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return new int[0];
		int coarseX = mesh.cellX(x) / mesh.coarseFactor(), coarseY = mesh.cellY(y) / mesh.coarseFactor();
		int count = mesh.coarseRegionCount(coarseX, coarseY);
		int[] regions = new int[count];
		for (int index = 0; index < count; index++)
			regions[index] = mesh.coarseRegion(coarseX, coarseY, index);
		return regions;
	}

	/**
	 * How much ground a place must have joined to it to be somewhere a bot can live, in cells — 1000 m² at half metre cells, a square 32 m on a side.
	 * <p>
	 * Measured rather than chosen, because the first number tried was chosen and was wrong by an order of magnitude. A place's ground comes in two
	 * clearly separated sizes across the maps this was run on: 23 m², 79 m², 283 m² and 615 m² for the pockets — a shelf, a rooftop, the floor of a
	 * ravine — against 4560 m², 4664 m² and 12127 m² for the whole settled part of a small map, which two or three places share and which is perfectly
	 * liveable. A bound of 12500 m² read those three as pockets and emptied two maps completely. The gap between the two groups is wide, and 1000 m²
	 * sits in the middle of it.
	 * <p>
	 * A large island that is genuinely cut off is not this test's business; the coarse grid already names those, because a stretch that big votes for a
	 * region of its own.
	 */
	private static final int LIVEABLE_CELLS = 4_000;

	/**
	 * @return true when the ground under a spot has been proved to lead nowhere, false when it has not — including where the map has no mesh, since
	 *         absence of a mesh proves nothing.
	 *         <p>
	 *         Costs no route search: see {@link BotPathFinder#isPocket}, which also says why a route search is the wrong question here.
	 */
	public boolean isPocket(int worldId, float x, float y, float z) {
		Navmesh mesh = get(worldId);
		return mesh != null && BotPathFinder.isPocket(mesh, x, y, z, LIVEABLE_CELLS);
	}

	/** @return A human readable summary, for the admin command. */
	public String describe() {
		if (open.isEmpty())
			return "No navmesh open";
		StringBuilder sb = new StringBuilder("Open navmeshes:");
		open.forEach((mapId, mesh) -> sb.append(String.format("%n  map %d, %dx%d cells, %d tiles read, %d MB", mapId, mesh.width(), mesh.height(),
			mesh.loadedTiles(), mesh.memoryFootprint() / 1048576)));
		return sb.toString();
	}

	private static class SingletonHolder {

		private static final NavmeshService INSTANCE = new NavmeshService();
	}
}
