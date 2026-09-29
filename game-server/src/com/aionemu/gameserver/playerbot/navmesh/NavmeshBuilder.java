package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Generates a map's navmesh when it is missing, so a server nobody has prepared by hand still gets bots that can find their way.
 * <p>
 * Generating one is not cheap — a few seconds and about a gigabyte for a map the size of Poeta — which is why the offline tool exists and why this
 * does its work on a background thread well after startup rather than holding the server on the way up. What it does not do is the tool's diagnostic
 * half: the height, structure and walkable images are a hundred and fifty megabytes each and are for looking at, not for playing.
 * <p>
 * Until it finishes, the bots on that map walk without a plan, which is what they did before any of this existed. They pick the mesh up the next time
 * the server starts.
 */
public class NavmeshBuilder {

	private static final Logger log = LoggerFactory.getLogger(NavmeshBuilder.class);
	/** Maps already being built, so two callers asking at once do not both start. */
	private static final Set<Integer> building = ConcurrentHashMap.newKeySet();

	private NavmeshBuilder() {
	}

	/**
	 * Builds the navmesh for a map in the background, unless it already exists or is already being built.
	 *
	 * @return true if a build was started.
	 */
	public static boolean buildIfMissing(int mapId) {
		if (Files.isRegularFile(Navmesh.fileOf(mapId)) || !building.add(mapId))
			return false;
		ThreadPoolManager.getInstance().executeLongRunning(() -> build(mapId));
		return true;
	}

	private static void build(int mapId) {
		long start = System.currentTimeMillis();
		try {
			log.info("No navmesh for map {} yet, building one. Bots there walk without a plan until the next restart.", mapId);
			Heightfield field = HeightfieldBuilder.build(mapId);
			Path file = NavmeshWriter.write(mapId, field);
			log.info("Built the navmesh for map {} in {} s: {} ({} MB)", mapId, (System.currentTimeMillis() - start) / 1000,
				file.toAbsolutePath(), Files.size(file) / 1048576);
		} catch (IOException | RuntimeException e) {
			log.error("Could not build the navmesh for map " + mapId + ", bots there will walk without a plan", e);
		} finally {
			building.remove(mapId);
		}
	}
}
