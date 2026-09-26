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
import com.aionemu.gameserver.utils.ThreadPoolManager;

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
	 *
	 * @return The waypoints to walk, or an empty list when there is no plan to be had — no generated map, no route, or a search that gave up. The
	 *         caller falls back to walking straight at the goal.
	 */
	public List<Vector3f> findRoute(Player bot, float goalX, float goalY, float goalZ) {
		Navmesh mesh = get(bot.getWorldId());
		if (mesh == null)
			return List.of();

		BotPathFinder.Route route = BotPathFinder.findPath(mesh, bot.getX(), bot.getY(), bot.getZ(), goalX, goalY, goalZ);
		if (route.isEmpty()) {
			if (route.gaveUp())
				log.debug("Path search for {} gave up short of {} {}", bot.getName(), goalX, goalY);
			return List.of();
		}
		return route.waypoints();
	}

	/**
	 * Plans a journey and hands the result back when it is ready.
	 * <p>
	 * Short journeys are planned on the spot, in a few tens of milliseconds. A long one takes over a second, which no movement thread can wait for,
	 * so it is planned on a pool thread while the bot walks straight at its goal. It picks the route up at the end of its current leg.
	 */
	public void planRoute(Player bot, float goalX, float goalY, float goalZ, Consumer<List<Vector3f>> whenReady) {
		if (get(bot.getWorldId()) == null)
			return;
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), goalX, goalY) <= BotPathFinder.LONG_DISTANCE) {
			whenReady.accept(findRoute(bot, goalX, goalY, goalZ));
			return;
		}
		ThreadPoolManager.getInstance().executeLongRunning(() -> {
			List<Vector3f> route = findRoute(bot, goalX, goalY, goalZ);
			if (!route.isEmpty())
				whenReady.accept(route);
		});
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
