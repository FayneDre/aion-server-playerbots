package com.aionemu.gameserver.playerbot.navmesh;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds, stores and reads the glide links of a map.
 * <p>
 * Most dungeons that allow gliding are built round it: the party arrives on a platform high above the place it is going, and the way down is to jump. A mesh that joins only
 * what a body walks from one cell to the next sees the platform as an island, and every monster below it as out of reach. The links are found offline, with the mesh, and kept in
 * a file beside it so the mesh format does not change and a map without any has nothing extra to read.
 */
public final class GlideLinks {

	/** How far out from an edge, in cells, a body is taken to carry before it lands: 10 m, a little under what a glide covers for the heights these dungeons have. */
	static final int REACH_CELLS = 20;
	/** The least height, in metres, that makes it a glide and not a hop: the climb rule already lets a body go down a slope or a kerb. */
	static final float MIN_DROP = 1.5f;
	static final float MAX_DROP = 250f;
	/** Ground this small is a crate or a ledge a monster stands on, and linking to it or from it only fills the file. */
	private static final int MIN_REGION_SURFACES = 40;
	/** Two links between the same pair of stretches are kept at least this far apart, in cells, so a long edge gives a few choices and not thousands. */
	private static final int SPACING_CELLS = 16;
	private static final int MAX_PER_PAIR = 24;
	private static final int[] STEP_X = { 1, 1, 0, -1, -1, -1, 0, 1 }, STEP_Y = { 0, 1, 1, 1, 0, -1, -1, -1 };

	private GlideLinks() {
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".glide");
	}

	/** @return The links of a map, empty when it has no file. */
	public static List<GlideLink> read(int mapId) throws IOException {
		Path file = fileOf(mapId);
		if (!Files.isRegularFile(file))
			return List.of();
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			int count = in.readInt();
			List<GlideLink> links = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				links.add(new GlideLink(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt()));
			return links;
		}
	}

	/** Writes the links, or removes the file when there are none, so that a rebuilt mesh never keeps the links of the one before it. */
	public static void write(int mapId, List<GlideLink> links) throws IOException {
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
	 * Looks along every edge of every stretch of ground for lower ground it could land on.
	 * <p>
	 * An edge is a walkable surface with a neighbour column that holds nothing a body can step onto. From it the search goes straight out, cell by cell, and every column with walkable
	 * ground at least {@link #MIN_DROP} below that belongs to another stretch makes a link: a glide comes down anywhere along its way, and the nearest ground is often a ledge or a crate
	 * that leads nowhere. Straight out only, because that is what a glide off an edge does and because it keeps the number of candidates to the length of the edges.
	 * <p>
	 * Nothing here knows whether the air between is clear: the heightfield holds floors, not the volume above them. The planner asks the game's geometry about that when it chooses.
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
		Map<Long, List<GlideLink>> byPair = new HashMap<>();
		float step = Heightfield.CELL_SIZE;
		for (int y = 0; y < field.height(); y++) {
			for (int x = 0; x < field.width(); x++) {
				for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
					int from = regions[surface];
					if (!field.isWalkable(surface) || from == 0 || sizes[from] < MIN_REGION_SURFACES)
						continue;
					float z = field.surfaceAt(surface);
					for (int direction = 0; direction < STEP_X.length; direction++) {
						boolean diagonal = STEP_X[direction] != 0 && STEP_Y[direction] != 0;
						if (!isEdge(field, x + STEP_X[direction], y + STEP_Y[direction], z, step * (diagonal ? 1.41421f : 1) + BotPathFinder.STEP_TOLERANCE))
							continue;
						for (int distance = 1; distance <= REACH_CELLS; distance++) {
							int landX = x + STEP_X[direction] * distance, landY = y + STEP_Y[direction] * distance;
							if (landX < 0 || landY < 0 || landX >= field.width() || landY >= field.height())
								break;
							int landing = landingBelow(field, landX, landY, z);
							if (landing < 0)
								continue;
							int to = regions[landing];
							if (to != from && to != 0 && sizes[to] >= MIN_REGION_SURFACES)
								keep(byPair, new GlideLink((x + 0.5f) * step, (y + 0.5f) * step, z, (landX + 0.5f) * step, (landY + 0.5f) * step, field.surfaceAt(landing), from, to));
						}
					}
				}
			}
		}
		List<GlideLink> links = new ArrayList<>();
		byPair.values().forEach(links::addAll);
		return links;
	}

	/**
	 * @return true if the column holds no walkable ground within a step of the height, which is what the open side of a ledge looks like. Surfaces that are not walkable do not count:
	 *         the rim of a platform is often one, and a wall is told apart later, by asking the game's geometry whether the air between the two ends is clear.
	 */
	private static boolean isEdge(Heightfield field, int x, int y, float z, float reach) {
		if (x < 0 || y < 0 || x >= field.width() || y >= field.height())
			return false;
		for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
			if (field.isWalkable(surface) && Math.abs(field.surfaceAt(surface) - z) <= reach)
				return false;
		}
		return true;
	}

	/** @return The walkable surface a body falling in this column lands on, or -1 when it would land on something it cannot stand on, or on nothing. */
	private static int landingBelow(Heightfield field, int x, int y, float z) {
		int best = -1;
		for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
			float other = field.surfaceAt(surface);
			if (other < z - 0.5f && (best < 0 || other > field.surfaceAt(best)))
				best = surface;
		}
		if (best < 0 || !field.isWalkable(best) || z - field.surfaceAt(best) < MIN_DROP || z - field.surfaceAt(best) > MAX_DROP)
			return -1;
		return best;
	}

	private static void keep(Map<Long, List<GlideLink>> byPair, GlideLink link) {
		List<GlideLink> kept = byPair.computeIfAbsent(((long) link.fromRegion() << 32) | link.toRegion(), pair -> new ArrayList<>());
		if (kept.size() >= MAX_PER_PAIR)
			return;
		float spacing = SPACING_CELLS * Heightfield.CELL_SIZE;
		for (GlideLink other : kept) {
			if (Math.hypot(other.fromX() - link.fromX(), other.fromY() - link.fromY()) < spacing)
				return;
		}
		kept.add(link);
	}
}
