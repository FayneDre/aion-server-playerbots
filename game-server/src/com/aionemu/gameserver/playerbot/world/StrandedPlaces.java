package com.aionemu.gameserver.playerbot.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.FlightLinks;
import com.aionemu.gameserver.playerbot.navmesh.GlideLinks;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

/**
 * Villages that proved at run time to be places nobody walks out of, kept on disk between restarts and left out of the plan.
 * <p>
 * The walking tests at startup ({@code BotPlaces.offThePockets}) drop a place whose ground runs out within their bound, and cannot see one whose ground is
 * larger than that and still joined to nothing. Its villagers find out instead: every errand refused, rescued at their own front door. The rescue then
 * moved such a villager to a spot of its own choosing, which is no village post; the director, which only knows posts, took it for a hunter too many, put
 * it to sleep and housed it back at the post it had just left. One bot of Reshanta went round that loop from 14:29 to past 15:11 on 24 October.
 * <p>
 * A village named here is left out of {@link BotPlaces#settlements}, so the director stops posting anybody there and moves its people as it does for any
 * place the plan dropped. The file is thrown away when the map's mesh, glide links or flight links change: a village marooned on today's ground may be
 * joined by tomorrow's, which is the whole point of writing those links.
 */
public final class StrandedPlaces {

	private static final Logger log = LoggerFactory.getLogger(StrandedPlaces.class);

	private static final Map<Integer, List<Vector3f>> byMap = new ConcurrentHashMap<>();

	private StrandedPlaces() {
	}

	/** @return true when the village at this centre was found to lead nowhere on the map's present ground. */
	public static boolean isStranded(int worldId, Vector3f centre) {
		for (Vector3f stranded : of(worldId)) {
			if (BotPlaces.isSamePlace(stranded, centre))
				return true;
		}
		return false;
	}

	/**
	 * Leaves the village out of the plan from now on, until the map's ground changes.
	 *
	 * @param why What proved it, for the log.
	 */
	public static void strand(int worldId, Vector3f centre, String why) {
		if (isStranded(worldId, centre))
			return;
		of(worldId).add(centre);
		// everything worked out from the settlements is worked out again: the director's posts, the homes a bot may move to
		BotPlaces.forgetHomes(worldId);
		BotPresence.forget(worldId);
		log.info("Map {}: the village at {} {} {} leads nowhere ({}), and is left out of the plan until the map's ground changes", worldId,
			centre.getX(), centre.getY(), centre.getZ(), why);
		save(worldId);
	}

	private static List<Vector3f> of(int worldId) {
		return byMap.computeIfAbsent(worldId, StrandedPlaces::read);
	}

	private static Path fileOf(int worldId) {
		return Path.of("cache/playerbot", "stranded-" + worldId + ".txt");
	}

	private static List<Vector3f> read(int worldId) {
		List<Vector3f> found = new CopyOnWriteArrayList<>();
		Path file = fileOf(worldId);
		String stamp = stampOf(worldId);
		if (stamp == null || !Files.isRegularFile(file))
			return found;
		try {
			List<String> lines = Files.readAllLines(file);
			if (lines.isEmpty() || !lines.getFirst().equals(stamp))
				return found; // the ground changed: every village is given its chance again
			for (String line : lines.subList(1, lines.size())) {
				String[] parts = line.split(" ");
				found.add(new Vector3f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])));
			}
		} catch (IOException | RuntimeException e) {
			log.warn("Map {}: its stranded villages could not be read back, and are all given their chance again", worldId, e);
			found.clear();
		}
		return found;
	}

	private static void save(int worldId) {
		String stamp = stampOf(worldId);
		if (stamp == null)
			return;
		List<String> lines = new ArrayList<>();
		lines.add(stamp);
		// Float.toString reads back to the same bits, which is what a place is matched on
		of(worldId).forEach(centre -> lines.add(centre.getX() + " " + centre.getY() + " " + centre.getZ()));
		try {
			Files.createDirectories(fileOf(worldId).getParent());
			Files.write(fileOf(worldId), lines);
		} catch (IOException e) {
			log.warn("Stranded villages could not be written to {}", fileOf(worldId), e);
		}
	}

	/** @return What the verdict was reached on: the mesh, the glide links and the flight links, by size and time. Null for a map with no mesh. */
	private static String stampOf(int worldId) {
		try {
			Path nav = Navmesh.fileOf(worldId);
			if (!Files.isRegularFile(nav))
				return null;
			return Files.size(nav) + " " + Files.getLastModifiedTime(nav).toMillis() + " " + describe(GlideLinks.fileOf(worldId)) + " "
				+ describe(FlightLinks.fileOf(worldId));
		} catch (IOException e) {
			return null;
		}
	}

	private static String describe(Path links) throws IOException {
		return Files.isRegularFile(links) ? Files.size(links) + " " + Files.getLastModifiedTime(links).toMillis() : "none";
	}
}
