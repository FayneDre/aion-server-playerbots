package com.aionemu.gameserver.playerbot.navmesh;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The lifts and boats of a map: ground the client carries a body across and the geometry does not hold, so the mesh sees the two ends as islands.
 * <p>
 * Sanctum is four of them. The obelisk district reaches the city only by the Sky Canal lift, the Protectors Hall only by the lift in the middle of the Artisans
 * Hall, and the Outer Dock only by the boat from the Airship Dock, which is why 10 of its 34 places had nobody living in them. None of the three is anywhere in
 * the data, so the ends are where a tester stood with {@code //bot note} on 20 October, and the times are the ones the tester gave.
 * <p>
 * Written the way the glide and flight links are, by {@code NavmeshTool <mapId> rides}, because a stretch of ground is numbered only while the mesh is built:
 * each end is put on the nearest floor of a real stretch round where the tester stood, since both lifts are stood on and their floor is not the mesh's.
 */
public final class RideLinks {

	/** A lift or a boat between two spots, taken either way. */
	record Ride(String name, float fromX, float fromY, float fromZ, float toX, float toY, float toZ, int seconds) {
	}

	/**
	 * The Sky Canal lift (1692 1615 567 to 1777 1616 593, 20 s) is left out: since the centre bridges are floors, the obelisk district walks into the city, 726
	 * m from the obelisk, so the lift is a short cut inside one stretch, which the planner, chaining stretches, has no use for.
	 */
	private static final Map<Integer, List<Ride>> RIDES = Map.of(110010000, List.of(
		new Ride("Artisans Hall lift", 1867, 1507, 591, 1867, 1508, 805, 20),
		new Ride("Airship Dock boat", 1669, 1497, 543, 1791, 2005, 518, 180)));

	/** How far round a tester's spot the floor of an end is looked for, across and up or down, in metres. */
	private static final float SNAP_ACROSS = 15, SNAP_HEIGHT = 6;
	/**
	 * The fewest surfaces a stretch needs to be an end, so a lift's own cap or a crate is not mistaken for the floor round it. A flight's rule of 2000 was
	 * tried first and refused the Protectors Hall, whose floor by the lift holds about 1500 surfaces within 40 m.
	 */
	private static final int MIN_STRETCH_SURFACES = 200;

	private RideLinks() {
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".ride");
	}

	/** @return The rides of a map in both directions, empty when it has no file. */
	public static List<GlideLink> read(int mapId) throws IOException {
		Path file = fileOf(mapId);
		if (!Files.isRegularFile(file))
			return List.of();
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			int count = in.readInt();
			List<GlideLink> links = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				links.add(new GlideLink(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt(), false,
					in.readInt()));
			return links;
		}
	}

	static void write(int mapId, List<GlideLink> links) throws IOException {
		Path file = fileOf(mapId);
		if (links.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
			out.writeInt(links.size());
			for (GlideLink link : links) {
				out.writeFloat(link.fromX());
				out.writeFloat(link.fromY());
				out.writeFloat(link.fromZ());
				out.writeFloat(link.toX());
				out.writeFloat(link.toY());
				out.writeFloat(link.toZ());
				out.writeInt(link.fromRegion());
				out.writeInt(link.toRegion());
				out.writeInt(link.rideSeconds());
			}
		}
	}

	/**
	 * Puts each ride of the map on the mesh's floors and writes its {@code .ride} file, the {@code .nav} untouched.
	 *
	 * @return One line per ride, saying where its ends landed or why it was left out.
	 */
	public static List<String> writeAlone(int mapId) throws IOException {
		List<Ride> rides = RIDES.getOrDefault(mapId, List.of());
		List<String> report = new ArrayList<>();
		if (rides.isEmpty()) {
			write(mapId, List.of());
			return report;
		}
		Heightfield field = HeightfieldBuilder.build(mapId);
		int[] regions = field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(mapId));
		int[] sizes = sizesOf(regions);
		List<GlideLink> links = new ArrayList<>();
		for (Ride ride : rides) {
			report.add(String.format(Locale.ROOT, "%s: under its start %s, under its end %s", ride.name(), under(field, regions, sizes, ride.fromX(), ride.fromY(), ride.fromZ()),
				under(field, regions, sizes, ride.toX(), ride.toY(), ride.toZ())));
			float[] from = floorNear(field, regions, sizes, ride.fromX(), ride.fromY(), ride.fromZ());
			float[] to = floorNear(field, regions, sizes, ride.toX(), ride.toY(), ride.toZ());
			if (from == null || to == null || from[3] == to[3]) {
				report.add(String.format(Locale.ROOT, "%s: left out, %s", ride.name(),
					from == null || to == null ? "no stretch of ground near " + (from == null ? "its start" : "its end") : "both ends are on stretch " + (int) from[3]));
				continue;
			}
			links.add(new GlideLink(from[0], from[1], from[2], to[0], to[1], to[2], (int) from[3], (int) to[3], false, ride.seconds()));
			links.add(new GlideLink(to[0], to[1], to[2], from[0], from[1], from[2], (int) to[3], (int) from[3], false, ride.seconds()));
			report.add(String.format(Locale.ROOT, "%s: %.1f %.1f %.1f on stretch %d (%d surfaces) <-> %.1f %.1f %.1f on stretch %d (%d surfaces), %d s", ride.name(), from[0],
				from[1], from[2], (int) from[3], sizes[(int) from[3]], to[0], to[1], to[2], (int) to[3], sizes[(int) to[3]], ride.seconds()));
		}
		write(mapId, links);
		report.add(String.format(Locale.ROOT, "Wrote %d ride(s) to %s", links.size() / 2, fileOf(mapId).toAbsolutePath()));
		return report;
	}

	/** @return The surfaces of the column under a spot within reach of its height, each with its stretch and the stretch's size: what an end was placed against. */
	private static String under(Heightfield field, int[] regions, int[] sizes, float x, float y, float z) {
		int cellX = (int) (x / Heightfield.CELL_SIZE), cellY = (int) (y / Heightfield.CELL_SIZE);
		List<String> found = new ArrayList<>();
		for (int surface = field.columnStart(cellX, cellY); surface < field.columnEnd(cellX, cellY); surface++) {
			if (Math.abs(field.surfaceAt(surface) - z) <= SNAP_HEIGHT)
				found.add(String.format(Locale.ROOT, "z %.1f %s stretch %d (%d)", field.surfaceAt(surface), field.isWalkable(surface) ? "walkable" : "not walkable",
					regions[surface], regions[surface] == 0 ? 0 : sizes[regions[surface]]));
		}
		return found.isEmpty() ? "nothing" : String.join(", ", found);
	}

	private static int[] sizesOf(int[] regions) {
		int highest = 0;
		for (int region : regions)
			highest = Math.max(highest, region);
		int[] sizes = new int[highest + 1];
		for (int region : regions)
			sizes[region]++;
		return sizes;
	}

	/**
	 * @return On the largest stretch within reach, the walkable surface of it nearest the spot, as x, y, z and the stretch's id; null when there is none.
	 *         The largest, because the floor a lift is boarded from is the room's: nearest alone chose the ring of 775 surfaces round the Artisans Hall's lift
	 *         shaft, 0.3 m from the tester, over the hall itself. Height counts double among the surfaces of that stretch.
	 */
	private static float[] floorNear(Heightfield field, int[] regions, int[] sizes, float x, float y, float z) {
		int reach = Math.round(SNAP_ACROSS / Heightfield.CELL_SIZE);
		int centreX = (int) (x / Heightfield.CELL_SIZE), centreY = (int) (y / Heightfield.CELL_SIZE);
		float[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int cellY = Math.max(0, centreY - reach); cellY <= Math.min(field.height() - 1, centreY + reach); cellY++) {
			for (int cellX = Math.max(0, centreX - reach); cellX <= Math.min(field.width() - 1, centreX + reach); cellX++) {
				float cx = (cellX + 0.5f) * Heightfield.CELL_SIZE, cy = (cellY + 0.5f) * Heightfield.CELL_SIZE;
				double across = Math.hypot(cx - x, cy - y);
				if (across > SNAP_ACROSS)
					continue;
				for (int surface = field.columnStart(cellX, cellY); surface < field.columnEnd(cellX, cellY); surface++) {
					float height = field.surfaceAt(surface);
					int region = regions[surface];
					if (!field.isWalkable(surface) || region == 0 || sizes[region] < MIN_STRETCH_SURFACES || Math.abs(height - z) > SNAP_HEIGHT)
						continue;
					double distance = across + 2 * Math.abs(height - z);
					int bestSize = best == null ? 0 : sizes[(int) best[3]];
					if (sizes[region] > bestSize || sizes[region] == bestSize && distance < bestDistance) {
						bestDistance = distance;
						best = new float[] { cx, cy, height, region };
					}
				}
			}
		}
		return best;
	}
}
