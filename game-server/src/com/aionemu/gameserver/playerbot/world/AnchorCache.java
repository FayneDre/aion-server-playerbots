package com.aionemu.gameserver.playerbot.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.GlideLinks;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

/**
 * The spot each place of a map is entered at, kept on disk between restarts.
 * <p>
 * Choosing one costs route searches, up to ten a place, and a search that gives up spends its whole budget first: measured at startup on 11 October,
 * 178 searches over a second, 312 s in all, three quarters of them giving up between the spots of one place. A map took up to 42 s, on whichever thread
 * asked first, the bots' own among them. The answer depends only on the spawns of the place and on the ground, so it is worked out once: a place is
 * known by its spots, and the file is thrown away when the map's mesh or glide links change.
 * <p>
 * It also keeps a place where it was. A search with a time budget can answer differently on a loaded server, and a place that moves its entrance
 * between restarts is a place its residents no longer live at ({@link BotPlaces#isSamePlace}).
 */
final class AnchorCache {

	private static final Logger log = LoggerFactory.getLogger(AnchorCache.class);

	/** Raised when the way an anchor is chosen changes, so that anchors chosen the old way are not read back. */
	private static final int VERSION = 1;

	private final Path file;
	private final String stamp;
	private final Map<Long, Vector3f> anchors = new HashMap<>();
	private boolean changed;

	private AnchorCache(Path file, String stamp) {
		this.file = file;
		this.stamp = stamp;
	}

	/** @return The anchors known for a map, none when its file is missing, unreadable or written for other ground. */
	static AnchorCache open(int worldId) {
		AnchorCache cache = new AnchorCache(Path.of("cache/playerbot", "anchors-" + worldId + ".txt"), stampOf(worldId));
		if (cache.stamp == null || !Files.isRegularFile(cache.file))
			return cache;
		try {
			List<String> lines = Files.readAllLines(cache.file);
			if (lines.isEmpty() || !lines.get(0).equals(cache.stamp))
				return cache; // the ground changed: every anchor is chosen again
			for (String line : lines.subList(1, lines.size())) {
				String[] parts = line.split(" ");
				cache.anchors.put(Long.parseLong(parts[0]),
					new Vector3f(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3])));
			}
		} catch (IOException | RuntimeException e) {
			log.warn("Map {}: anchors of its places could not be read back, they are chosen again", worldId, e);
			cache.anchors.clear();
		}
		return cache;
	}

	/** @return The anchor chosen before for a place with exactly these spots, or null. */
	Vector3f get(List<Vector3f> cluster) {
		return stamp == null ? null : anchors.get(keyOf(cluster));
	}

	void put(List<Vector3f> cluster, Vector3f anchor) {
		if (stamp != null && anchors.put(keyOf(cluster), anchor) == null)
			changed = true;
	}

	/** @return How many anchors were read back rather than chosen. */
	int size() {
		return anchors.size();
	}

	/** Writes the anchors down when any was chosen since the file was read. */
	void save() {
		if (!changed)
			return;
		List<String> lines = new ArrayList<>(anchors.size() + 1);
		lines.add(stamp);
		// Float.toString reads back to the same bits, which is what a place is matched on
		anchors.forEach((key, anchor) -> lines.add(key + " " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ()));
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
			changed = false;
		} catch (IOException e) {
			log.warn("Anchors could not be written to {}", file, e);
		}
	}

	/** @return What the anchors were chosen on: the mesh and the glide links, by size and time. Null for a map with no mesh, whose anchors cost nothing. */
	private static String stampOf(int worldId) {
		try {
			Path nav = Navmesh.fileOf(worldId);
			if (!Files.isRegularFile(nav))
				return null;
			Path glide = GlideLinks.fileOf(worldId);
			String glides = Files.isRegularFile(glide) ? Files.size(glide) + " " + Files.getLastModifiedTime(glide).toMillis() : "none";
			return "v" + VERSION + " " + Files.size(nav) + " " + Files.getLastModifiedTime(nav).toMillis() + " " + glides;
		} catch (IOException e) {
			return null;
		}
	}

	/** A place is known by its spots, in the order the spawn data gives them: the same data gives the same key. */
	private static long keyOf(List<Vector3f> cluster) {
		long key = cluster.size();
		for (Vector3f spot : cluster) {
			key = key * 1_000_003 + Float.floatToIntBits(spot.getX());
			key = key * 1_000_003 + Float.floatToIntBits(spot.getY());
			key = key * 1_000_003 + Float.floatToIntBits(spot.getZ());
		}
		return key;
	}
}
