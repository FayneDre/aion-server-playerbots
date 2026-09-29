package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a map's navmesh when it is missing, so a server nobody has prepared by hand still gets bots that can find their way.
 * <p>
 * Done while the server is starting, before it accepts a single connection, and deliberately so. The alternative — building in the background while
 * play goes on — means the bots on that map spend their first minutes walking without a plan, wedging themselves in scenery and being put back home by
 * the safety net. A map is either ready or it is not, and a few seconds added to a startup that already takes tens of them is the cheaper half of that
 * bargain.
 * <p>
 * It skips the offline tool's diagnostic half: the height, structure and walkable images are a hundred and fifty megabytes each and are for looking
 * at, not for playing. What is left costs about a gigabyte and a few seconds for a map the size of Poeta.
 */
public class NavmeshBuilder {

	private static final Logger log = LoggerFactory.getLogger(NavmeshBuilder.class);

	private NavmeshBuilder() {
	}

	/**
	 * Makes sure a map has a navmesh, building one now if it has none.
	 *
	 * @return true if the map has a usable navmesh afterwards.
	 */
	public static boolean ensureMesh(int mapId) {
		if (Files.isRegularFile(Navmesh.fileOf(mapId)))
			return true;
		long start = System.currentTimeMillis();
		try {
			log.info("Map {} has no navmesh, building one before the server opens. This takes a few seconds.", mapId);
			Path file = NavmeshWriter.write(mapId, HeightfieldBuilder.build(mapId));
			log.info("Built the navmesh for map {} in {} s: {} ({} MB)", mapId, (System.currentTimeMillis() - start) / 1000, file.toAbsolutePath(),
				Files.size(file) / 1048576);
			return true;
		} catch (IOException | RuntimeException e) {
			log.error("Could not build the navmesh for map " + mapId + ", bots there will walk without a plan", e);
			return false;
		}
	}
}
