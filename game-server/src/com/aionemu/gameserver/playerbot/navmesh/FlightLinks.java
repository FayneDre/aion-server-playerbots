package com.aionemu.gameserver.playerbot.navmesh;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the ways across open air between islands of ground, which a body takes by flying: from the edge of one large stretch of ground to the edge of another.
 * <p>
 * A glide link goes down off an edge and lands within ten metres; that frees a terrace and not an archipelago. Reshanta is sixteen thousand islands, the large
 * ones a few hundred metres apart with nothing between them, and a bot there could not reach a hunting ground on the island next door. A flight link pairs the
 * nearest edges of two islands, either way up and in both directions, within what one flight pays for.
 * <p>
 * Found from the floors alone, like the glide links: whether the air is clear is the server's question, asked of the game's geometry when the bot flies it.
 */
final class FlightLinks {

	/** The fewest surfaces an island needs to be flown from or onto: the open world's rule for a glide landing, so crumbs of ground are left out. */
	static final int MIN_ISLAND_SURFACES = 2000;
	/**
	 * The longest crossing, across and up or down. One flight holds 60 points at one a second, and a crossing is flown as a climb, a level run and a descent at
	 * about 12 m/s: 180 m across and 200 up or down cost about 41 of them, the take-off and the margin included. 120 up or down was too little: from the
	 * Elyos island of Reshanta the island beside it is 184 m lower, and the only way left went down a cliff under an overhang.
	 */
	static final float MAX_ACROSS = 180, MAX_RISE = 200;
	/**
	 * How much open sky a take-off and a landing need above the ground: a flight climbs out of one and comes down into the other, straight up and down. A
	 * ledge under an overhang is ground the floors call an edge and the air calls a ceiling; the heightfield holds the tops of what is above, so it is seen here
	 * rather than refused at every height by the server.
	 */
	static final float OPEN_SKY = 20;
	/** How many crossings a pair of islands keeps in each direction, and how far apart their take-offs must be. */
	private static final int PER_PAIR = 3;
	private static final float SPACING = 40;
	/** How many of the shortest crossings of a pair are held while searching, out of which the spaced ones are kept. */
	private static final int CANDIDATES = 24;
	/** One edge sample per this many cells along each axis: a 4 m square, the coarse grid's cell, which is fine enough for an edge and keeps the pairing cheap. */
	private static final int SAMPLE_CELLS = 8;
	private static final int[] STEP_X = { 1, 0, -1, 0 }, STEP_Y = { 0, 1, 0, -1 };

	private FlightLinks() {
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".flight");
	}

	/** @return The links of a map, marked as flights, empty when it has no file. */
	public static List<GlideLink> read(int mapId) throws IOException {
		Path file = fileOf(mapId);
		if (!Files.isRegularFile(file))
			return List.of();
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			int count = in.readInt();
			List<GlideLink> links = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				links.add(new GlideLink(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt(), true));
			return links;
		}
	}

	/** Writes the links, or removes the file when there are none. */
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
			}
		}
	}

	/**
	 * Finds a map's links and writes its {@code .flight} file alone, the mesh untouched, the same way {@link GlideLinks#writeAlone} does: the stretches are
	 * numbered by the same computation from the same geometry, so the links name the mesh's own.
	 *
	 * @return How many links were written.
	 */
	static int writeAlone(int mapId) throws IOException {
		Heightfield field = HeightfieldBuilder.build(mapId);
		List<GlideLink> links = find(field, field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(mapId)));
		write(mapId, links);
		return links.size();
	}

	/**
	 * Samples the edges of every large island, then pairs each sample with the samples of other islands within reach, nearest first.
	 *
	 * @param regions One id per surface, as {@link Heightfield#regions} returns them.
	 */
	static List<GlideLink> find(Heightfield field, int[] regions) {
		int highest = 0;
		for (int region : regions)
			highest = Math.max(highest, region);
		int[] sizes = new int[highest + 1];
		for (int region : regions)
			sizes[region]++;

		// one edge sample per island and per 4 m square
		Map<Long, float[]> samples = new HashMap<>();
		float step = Heightfield.CELL_SIZE;
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
					int region = regions[surface];
					if (!field.isWalkable(surface) || region == 0 || sizes[region] < MIN_ISLAND_SURFACES)
						continue;
					float z = field.surfaceAt(surface);
					if (!isEdge(field, x, y, z, step + BotPathFinder.STEP_TOLERANCE) || !hasOpenSky(field, x, y, z))
						continue;
					long key = (long) region << 40 | (long) (y / SAMPLE_CELLS) << 20 | x / SAMPLE_CELLS;
					samples.putIfAbsent(key, new float[] { (x + 0.5f) * step, (y + 0.5f) * step, z, region });
				}
			}
		}

		// bucketed by the reach, so each sample looks only at its own bucket and the eight round it
		Map<Long, List<float[]>> buckets = new HashMap<>();
		for (float[] sample : samples.values())
			buckets.computeIfAbsent(bucketOf(sample[0], sample[1]), key -> new ArrayList<>()).add(sample);
		Map<Long, List<GlideLink>> candidates = new HashMap<>();
		for (float[] from : samples.values()) {
			int bucketX = (int) (from[0] / MAX_ACROSS), bucketY = (int) (from[1] / MAX_ACROSS);
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					List<float[]> near = buckets.get((long) (bucketY + dy) << 32 | (bucketX + dx) & 0xffffffffL);
					if (near == null)
						continue;
					for (float[] to : near) {
						if (to[3] == from[3] || Math.abs(to[2] - from[2]) > MAX_RISE || Math.hypot(to[0] - from[0], to[1] - from[1]) > MAX_ACROSS)
							continue;
						offer(candidates.computeIfAbsent((long) from[3] << 32 | (long) to[3], pair -> new ArrayList<>()),
							new GlideLink(from[0], from[1], from[2], to[0], to[1], to[2], (int) from[3], (int) to[3], true));
					}
				}
			}
		}

		// per pair and direction, the shortest crossings with their take-offs apart, so one blocked by a rock leaves another
		List<GlideLink> links = new ArrayList<>();
		Comparator<GlideLink> shortest = Comparator.comparingDouble(FlightLinks::length);
		for (List<GlideLink> pair : candidates.values()) {
			pair.sort(shortest);
			List<GlideLink> kept = new ArrayList<>();
			for (GlideLink link : pair) {
				if (kept.size() >= PER_PAIR)
					break;
				if (kept.stream().allMatch(other -> Math.hypot(other.fromX() - link.fromX(), other.fromY() - link.fromY()) >= SPACING))
					kept.add(link);
			}
			links.addAll(kept);
		}
		return links;
	}

	/**
	 * Keeps a candidate among the shortest {@link #CANDIDATES} of its pair. Every edge sample of an island against every one of its neighbour within reach is
	 * a great many crossings on a map of sixteen thousand islands, and only the shortest few of each pair are ever kept.
	 */
	private static void offer(List<GlideLink> best, GlideLink link) {
		double length = length(link);
		if (best.size() >= CANDIDATES && length >= length(best.get(best.size() - 1)))
			return;
		int at = 0;
		while (at < best.size() && length(best.get(at)) <= length)
			at++;
		best.add(at, link);
		if (best.size() > CANDIDATES)
			best.remove(best.size() - 1);
	}

	/** @return How far a crossing goes, a metre of height counted as a metre across: it is flown, up or down alike. */
	static double length(GlideLink link) {
		return Math.sqrt(Math.pow(link.toX() - link.fromX(), 2) + Math.pow(link.toY() - link.fromY(), 2) + Math.pow(link.toZ() - link.fromZ(), 2));
	}

	private static long bucketOf(float x, float y) {
		return (long) (int) (y / MAX_ACROSS) << 32 | (int) (x / MAX_ACROSS) & 0xffffffffL;
	}

	/** @return true if nothing stands above the ground in this column within {@link #OPEN_SKY}: no floor, no roof, no rock. */
	static boolean hasOpenSky(Heightfield field, int x, int y, float z) {
		for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
			float other = field.surfaceAt(surface);
			if (other > z + BotPathFinder.STEP_TOLERANCE && other <= z + OPEN_SKY)
				return false;
		}
		return true;
	}

	/** @return true if one of the four neighbouring columns holds no walkable ground within a step of the height: the open side of an island. */
	private static boolean isEdge(Heightfield field, int x, int y, float z, float reach) {
		for (int direction = 0; direction < STEP_X.length; direction++) {
			int nx = x + STEP_X[direction], ny = y + STEP_Y[direction];
			if (nx < 0 || ny < 0 || nx >= field.width() || ny >= field.height())
				return true;
			boolean footing = false;
			for (int surface = field.columnStart(nx, ny); surface < field.columnEnd(nx, ny) && !footing; surface++)
				footing = field.isWalkable(surface) && Math.abs(field.surfaceAt(surface) - z) <= reach;
			if (!footing)
				return true;
		}
		return false;
	}
}
