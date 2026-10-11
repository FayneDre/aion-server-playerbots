package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How big a map is, read from the world map list, for the maps whose geometry ships without a heightmap.
 * <p>
 * The heightmap says how far a map extends, and the generator took its grid from it. Sanctum, the Cloister of Kaisinel, Reshanta and the four
 * Panesterra battlefields ship none: their ground is made of placed meshes only. Their size is in {@code world_maps.xml} all the same, which is where
 * the engine itself reads it from.
 */
final class MapSize {

	private static final Path WORLD_MAPS = Path.of("data/static_data/world_maps.xml");

	private MapSize() {
	}

	/** @return The side of the square map in metres, or 0 when the list does not give one. */
	static int of(int mapId) throws IOException {
		return of(WORLD_MAPS, mapId);
	}

	/** @return true if the world map list marks the map as an instance, a dungeon the party enters through a portal. */
	static boolean isInstance(int mapId) throws IOException {
		return isInstance(WORLD_MAPS, mapId);
	}

	/** @return true if the world map list gives the map the {@code GLIDE} flag, which is what lets a body jump off an edge and come down far from it. */
	static boolean allowsGlide(int mapId) throws IOException {
		Pattern map = Pattern.compile("<map\s+id=\"" + mapId + "\"[^>]*\sflags=\"[^\"]*\\bGLIDE\\b");
		if (!Files.isRegularFile(WORLD_MAPS))
			return false; // a tool run away from the server has no list, and a map it builds then has no glide links
		for (String line : Files.readAllLines(WORLD_MAPS)) {
			if (map.matcher(line).find())
				return true;
		}
		return false;
	}

	static boolean isInstance(Path list, int mapId) throws IOException {
		Pattern map = Pattern.compile("<map\s+id=\"" + mapId + "\"[^>]*\sinstance=\"true\"");
		for (String line : Files.readAllLines(list)) {
			if (map.matcher(line).find())
				return true;
		}
		return false;
	}

	static int of(Path list, int mapId) throws IOException {
		Pattern map = Pattern.compile("<map\\s+id=\"" + mapId + "\"[^>]*\\sworld_size=\"(\\d+)\"");
		for (String line : Files.readAllLines(list)) {
			Matcher matcher = map.matcher(line);
			if (matcher.find())
				return Integer.parseInt(matcher.group(1));
		}
		return 0;
	}
}
