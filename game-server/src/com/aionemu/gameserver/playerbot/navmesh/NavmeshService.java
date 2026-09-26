package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Serves generated maps to the bots that need them.
 * <p>
 * A map costs about 117 MB of heap and a third of a second to read, so they are loaded on demand, once, and only for maps a bot actually walks. The
 * load happens on a pool thread: stalling a movement tick for a third of a second would be worse than walking without a plan for a few seconds, which
 * is what bots did before any of this existed.
 */
public class NavmeshService {

	private static final Logger log = LoggerFactory.getLogger(NavmeshService.class);

	/** A map is absent from this while loading, and maps to null once we know it has no file. */
	private final Map<Integer, Navmesh> loaded = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> loading = new ConcurrentHashMap<>();

	public static NavmeshService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Plans a route for the bot, loading its map in the background if needed.
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

	/** @return The map if it is loaded, null otherwise, starting a load the first time it is missed. */
	private Navmesh get(int mapId) {
		Navmesh mesh = loaded.get(mapId);
		if (mesh != null || loading.containsKey(mapId))
			return mesh;
		if (loading.putIfAbsent(mapId, Boolean.TRUE) == null)
			ThreadPoolManager.getInstance().executeLongRunning(() -> load(mapId));
		return null;
	}

	private void load(int mapId) {
		if (!Files.isRegularFile(Navmesh.fileOf(mapId))) {
			log.info("No navmesh for map {}, bots there will walk without a plan", mapId);
			return;
		}
		try {
			long start = System.currentTimeMillis();
			Navmesh mesh = Navmesh.load(mapId);
			loaded.put(mapId, mesh);
			log.info("Loaded navmesh for map {} in {} ms, holding {} MB", mapId, System.currentTimeMillis() - start,
				mesh.memoryFootprint() / 1048576);
		} catch (IOException e) {
			log.error("Could not load the navmesh for map " + mapId, e);
		}
	}

	/** @return A human readable summary, for the admin command. */
	public String describe() {
		if (loaded.isEmpty())
			return "No navmesh loaded";
		StringBuilder sb = new StringBuilder("Loaded navmeshes:");
		loaded.forEach((mapId, mesh) -> sb.append(String.format("%n  map %d, %dx%d cells, %d MB", mapId, mesh.width(), mesh.height(),
			mesh.memoryFootprint() / 1048576)));
		return sb.toString();
	}

	private static class SingletonHolder {

		private static final NavmeshService INSTANCE = new NavmeshService();
	}
}
