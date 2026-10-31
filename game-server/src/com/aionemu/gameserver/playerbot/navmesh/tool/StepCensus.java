package com.aionemu.gameserver.playerbot.navmesh.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.HeightfieldBuilder;
import com.aionemu.gameserver.playerbot.navmesh.MapSlopes;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

/**
 * Where two large grounds of a map meet along a step a walker takes and the mesh does not: {@code NavmeshTool <map> steps}.
 * <p>
 * The mesh joins two cells when the heights differ by no more than {@link BotPathFinder#STEP_TOLERANCE} over half a metre plus the slope. A riser of one to two metres, a stair modelled
 * as one step, a low ledge a player jumps, therefore cuts one ground from the other: the Airship Dock of Pandaemonium (1.07 m), the east of Altgard and the foot of the Fire Temple of
 * Morheim (2 m) were all that. This lists, per 10 m square, the edges that nearly join two grounds of at least {@link #LARGE} cells, with how many there are, how high the riser is
 * and whether somebody walked there (the walked-ground and patrol files of the map), since a riser a player walks is a step and one nobody walks may be a cliff.
 * <p>
 * Read only: nothing is written. Written on 31 October 2026 for the step links of the navigation work (docs/navigation/plans/step-links-plan.md).
 */
final class StepCensus {

	/** The smallest ground, in cells, that counts: a prop or a pocket is not a place two grounds meet. */
	private static final int LARGE = 400;
	/** The highest riser looked at, in metres: a player jumps about two. */
	private static final float MAX_RISER = 2.1f;
	/** The squares edges are grouped in, in metres. */
	private static final float SQUARE = 10;
	/** How far a walked point may be from a square's middle to say somebody walked it, in metres. */
	private static final float WALKED_WITHIN = 8;
	private static final int[] DX = { 1, 1, 0, -1 }, DY = { 0, 1, 1, 1 };

	private StepCensus() {
	}

	private static final class Place {
		int edges;
		float lowest = Float.MAX_VALUE, highest, sumX, sumY, zLow = Float.MAX_VALUE, zHigh = -Float.MAX_VALUE;
		final Map<Long, Integer> pairs = new HashMap<>();
	}

	static int run(int mapId) throws IOException {
		Heightfield field = HeightfieldBuilder.build(mapId);
		int[] region = field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(mapId));
		int[] size = new int[region.length + 1];
		for (int surface : region) {
			if (surface > 0)
				size[surface]++;
		}
		Map<Long, Place> places = new HashMap<>();
		int width = field.width(), height = field.height();
		for (int column = 0; column < width * height; column++) {
			int cx = column % width, cy = column / width;
			for (int surface = field.columnStart(cx, cy); surface < field.columnEnd(cx, cy); surface++) {
				int mine = region[surface];
				if (mine == 0 || size[mine] < LARGE)
					continue;
				for (int direction = 0; direction < DX.length; direction++) {
					int nx = cx + DX[direction], ny = cy + DY[direction];
					if (nx < 0 || ny < 0 || nx >= width || ny >= height)
						continue;
					float reach = Heightfield.CELL_SIZE * (direction % 2 == 1 ? 1.41421f : 1) + BotPathFinder.STEP_TOLERANCE;
					for (int other = field.columnStart(nx, ny); other < field.columnEnd(nx, ny); other++) {
						int theirs = region[other];
						if (theirs == 0 || theirs == mine || size[theirs] < LARGE)
							continue;
						float step = Math.abs(field.surfaceAt(other) - field.surfaceAt(surface));
						if (step <= reach || step > MAX_RISER)
							continue;
						float x = cx * Heightfield.CELL_SIZE, y = cy * Heightfield.CELL_SIZE;
						long key = ((long) (x / SQUARE) << 32) | (int) (y / SQUARE);
						Place place = places.computeIfAbsent(key, k -> new Place());
						place.edges++;
						place.sumX += x;
						place.sumY += y;
						place.lowest = Math.min(place.lowest, step);
						place.highest = Math.max(place.highest, step);
						place.zLow = Math.min(place.zLow, Math.min(field.surfaceAt(surface), field.surfaceAt(other)));
						place.zHigh = Math.max(place.zHigh, Math.max(field.surfaceAt(surface), field.surfaceAt(other)));
						place.pairs.merge(((long) Math.min(mine, theirs) << 32) | Math.max(mine, theirs), 1, Integer::sum);
					}
				}
			}
		}
		List<float[]> walked = walkedPoints(mapId);
		List<Map.Entry<Long, Place>> ranked = new ArrayList<>(places.entrySet());
		ranked.sort((a, b) -> Integer.compare(b.getValue().edges, a.getValue().edges));
		int onWalked = 0;
		System.out.printf(Locale.ROOT, "Map %d: %d squares of %.0f m where two grounds of %d cells or more meet along a riser of %.2f to %.1f m; %d walked points read%n", mapId, ranked.size(),
			SQUARE, LARGE, BotPathFinder.STEP_TOLERANCE + 0.5f, MAX_RISER, walked.size());
		int shown = 0;
		for (Map.Entry<Long, Place> entry : ranked) {
			Place place = entry.getValue();
			float x = place.sumX / place.edges, y = place.sumY / place.edges;
			float nearest = Float.MAX_VALUE;
			for (float[] point : walked)
				nearest = Math.min(nearest, (float) Math.hypot(point[0] - x, point[1] - y));
			boolean walkedHere = nearest <= WALKED_WITHIN;
			if (walkedHere)
				onWalked++;
			if (shown++ < 25 || walkedHere) {
				Map.Entry<Long, Integer> pair = place.pairs.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
				System.out.printf(Locale.ROOT, "  %4d edges at %.0f %.0f z %.1f to %.1f, risers %.2f to %.2f m, grounds %d and %d cells%s%n", place.edges, x, y, place.zLow, place.zHigh, place.lowest,
					place.highest, size[(int) (pair.getKey() >> 32)], size[(int) (long) pair.getKey()], walkedHere ? String.format(Locale.ROOT, ", WALKED (%.0f m)", nearest) : "");
			}
		}
		System.out.printf(Locale.ROOT, "  %d of the %d squares have a walked point within %.0f m%n", onWalked, ranked.size(), WALKED_WITHIN);
		return 0;
	}

	/** @return The x, y of the points real players walked and the patrol routes of the data that the generator was given, read from the map's own files, or none. */
	private static List<float[]> walkedPoints(int mapId) throws IOException {
		List<float[]> points = new ArrayList<>();
		Path nav = Navmesh.fileOf(mapId);
		for (String extension : new String[] { ".walked", ".patrols" }) {
			Path file = nav.resolveSibling(nav.getFileName().toString().replace(".nav", extension));
			if (!Files.exists(file))
				continue;
			for (String line : Files.readAllLines(file)) {
				String[] parts = line.trim().split("\\s+");
				if (parts.length >= 3)
					points.add(new float[] { Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]) });
			}
		}
		return points;
	}
}
