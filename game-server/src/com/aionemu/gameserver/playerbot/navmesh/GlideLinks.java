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
import java.util.Set;

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
	/**
	 * The least height, in metres, that makes it a drop: the climb rule already lets a body go down a slope or a kerb. A metre, as the fall that measured Esoterrace at 66 % took it:
	 * at a metre and a half, the ledges of a metre and a bit that lead down from its arrival were neither walked nor dropped from.
	 */
	static final float MIN_DROP = 1f;
	static final float MAX_DROP = 250f;
	/** Ground this small is a crate or a ledge a monster stands on, and linking to it or from it only fills the file. */
	private static final int MIN_REGION_SURFACES = 40;
	/**
	 * Maps whose way down goes from rock to rock, each too small to pass the rule above: Esoterrace's arrival reached nothing below it until every stretch of ground was linked, and
	 * its file grew from 3 to 12 MB for it, which is why the rule stays everywhere else.
	 */
	private static final Set<Integer> EVERY_STRETCH = Set.of(300250000);
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
		return find(field, regions, MIN_REGION_SURFACES);
	}

	/** @return The fewest surfaces a stretch of ground of the map needs to be glided from or onto. */
	static int minRegionSurfaces(int mapId) {
		return EVERY_STRETCH.contains(mapId) ? 1 : MIN_REGION_SURFACES;
	}

	/**
	 * The rule of the open world, stricter than the instances': glided from ground of 400 surfaces or more, onto ground of 2000 or more, 4 links a pair. Chosen by
	 * {@code glidecheck} on the nine maps with obelisks apart from their main ground: it frees all fifteen the instances' rule frees, and Enshar's file weighs 3.5 MB
	 * instead of 39.8, Cygnea's 4.8 instead of 34.4, the others between 0.1 and 1. Fewer links a pair alone saved a tenth: the weight is in the number of pairs.
	 */
	static final Rules OPEN_WORLD = new Rules(400, 2000, 4);

	/** @return The rule a map's links are found with. */
	static Rules rulesFor(int mapId) throws IOException {
		if (!MapSize.isInstance(mapId))
			return OPEN_WORLD;
		int minimum = minRegionSurfaces(mapId);
		return new Rules(minimum, minimum, MAX_PER_PAIR);
	}

	/**
	 * Finds a map's links and writes its {@code .glide} file alone, the {@code .nav} untouched. The stretches are numbered by the same computation from the same
	 * geometry as when the mesh was written, so the links name the mesh's own stretches; it takes the half minute of building the ground instead of a rebuild.
	 * <p>
	 * This is how the open world gets its links: the generator writes them for instances only, and a map whose file appears is read by the server on its next
	 * start, so writing one is a decision taken map by map.
	 *
	 * @return How many links were written.
	 */
	static int writeAlone(int mapId) throws IOException {
		Heightfield field = HeightfieldBuilder.build(mapId);
		List<GlideLink> links = find(field, field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(mapId)), rulesFor(mapId));
		write(mapId, links);
		return links.size();
	}

	/** As above, with the fewest surfaces a stretch of ground needs to be linked at all. */
	static List<GlideLink> find(Heightfield field, int[] regions, int minRegionSurfaces) {
		return find(field, regions, new Rules(minRegionSurfaces, minRegionSurfaces, MAX_PER_PAIR));
	}

	/**
	 * What is linked: the fewest surfaces of the stretch glided from and of the stretch landed on, and how many links a pair of stretches keeps.
	 * <p>
	 * One rule did for the instances. The open world has maps of a hundred and forty thousand stretches, where it made a million links, held as objects in the
	 * server's memory, so a map may need a stricter one; {@code NavmeshTool <map> glidecheck} measures the candidates.
	 */
	record Rules(int minFrom, int minLanding, int perPair) {

		@Override
		public String toString() {
			return "from " + minFrom + ", onto " + minLanding + ", " + perPair + " a pair";
		}
	}

	/** As above, with every rule given. */
	static List<GlideLink> find(Heightfield field, int[] regions, Rules rules) {
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
					if (!field.isWalkable(surface) || from == 0 || sizes[from] < rules.minFrom())
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
							if (to != from && to != 0 && sizes[to] >= rules.minLanding())
								keep(byPair, new GlideLink((x + 0.5f) * step, (y + 0.5f) * step, z, (landX + 0.5f) * step, (landY + 0.5f) * step, field.surfaceAt(landing), from, to),
									rules.perPair());
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

	/**
	 * @return The highest walkable surface below in this column, or -1 when there is none within the drop. Something a body cannot stand on above it does not stop the link: a tree, a
	 *         rail or a steep rock under the edge is glided round, and taken as the landing it cut Esoterrace's arrival off from everything below it (2 % reached where a fall measured 66).
	 *         Whether the air is really clear is the server's question, asked of the game's geometry when a route uses the link.
	 */
	private static int landingBelow(Heightfield field, int x, int y, float z) {
		int best = -1;
		for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
			float other = field.surfaceAt(surface);
			// a kerb half a metre down is not the landing: a body that steps off it falls on past, to the next ground a drop below
			if (field.isWalkable(surface) && other <= z - MIN_DROP && (best < 0 || other > field.surfaceAt(best)))
				best = surface;
		}
		if (best < 0 || z - field.surfaceAt(best) > MAX_DROP)
			return -1;
		return best;
	}

	private static void keep(Map<Long, List<GlideLink>> byPair, GlideLink link, int perPair) {
		List<GlideLink> kept = byPair.computeIfAbsent(((long) link.fromRegion() << 32) | link.toRegion(), pair -> new ArrayList<>());
		if (kept.size() >= perPair)
			return;
		float spacing = SPACING_CELLS * Heightfield.CELL_SIZE;
		for (GlideLink other : kept) {
			if (Math.hypot(other.fromX() - link.fromX(), other.fromY() - link.fromY()) < spacing)
				return;
		}
		kept.add(link);
	}
}
