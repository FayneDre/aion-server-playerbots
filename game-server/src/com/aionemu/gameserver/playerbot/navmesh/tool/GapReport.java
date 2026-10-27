package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.GeoDataReader;
import com.aionemu.gameserver.playerbot.navmesh.GeoPlacement;
import com.aionemu.gameserver.playerbot.navmesh.GlideLink;
import com.aionemu.gameserver.playerbot.navmesh.GlideLinks;
import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.HeightfieldBuilder;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Why the places a dungeon's monsters stand are not joined to where its party arrives.
 * <p>
 * {@code reach} says how many are unreached and not why, and the why is what decides what to do about a map: a gate model in the way, a no-walk wall, a step a
 * little too high, or ground the data never joins at all. This takes the monsters' spots that sit in a different region from the arrival, groups them by region, and for
 * each of the largest finds where that region comes closest to the arrival's: the two cells, the gap between them across and up, and what is placed beside the
 * place where they meet. The answer is a place and a few model names, which is what somebody has to look at.
 */
final class GapReport {

	/** How far, in cells, to look for ground of the other region around a cell. Five metres, enough to cross a wall or a doorway that is closed. */
	private static final int WINDOW = Integer.getInteger("navmesh.gapWindow", 10);
	/**
	 * The largest difference in height, in metres, between two cells that are called a near meeting at all. {@code navmesh.gapWindow} and {@code navmesh.gapHeight} widen both, to
	 * find a ledge or a deck that no window of five metres reaches: Rentus's upper deck came closest at 14 m across and 9.5 up.
	 */
	private static final float MAX_DIFFERENCE = Float.parseFloat(System.getProperty("navmesh.gapHeight", "4"));
	/** How many unreached regions are described, the ones holding most monsters first. */
	private static final int SHOWN = 4;
	/** How far from the meeting, in metres, a placed model is listed. */
	private static final float NEAR = 7f;

	private GapReport() {
	}

	static int run(int mapId) throws IOException {
		List<float[]> arrivals = InstanceReach.entries(mapId);
		List<float[]> spots = InstanceReach.spawnSpots(mapId);
		if (arrivals.isEmpty() || spots.isEmpty()) {
			System.out.printf(Locale.ROOT, "Map %d: %d arrival(s) and %d spawn spot(s), nothing to compare%n", mapId, arrivals.size(), spots.size());
			return 2;
		}
		Heightfield field = HeightfieldBuilder.build(mapId);
		int[] region = field.regions(BotPathFinder.STEP_TOLERANCE);
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int r : region)
			if (r != 0)
				sizes.merge(r, 1, Integer::sum);
		List<GeoPlacement> placements = GeoDataReader.readPlacements(mapId);
		Map<Integer, List<GlideLink>> glides = new HashMap<>();
		for (GlideLink link : GlideLinks.read(mapId))
			glides.computeIfAbsent(link.fromRegion(), k -> new ArrayList<>()).add(link);

		for (float[] arrival : arrivals) {
			int origin = nearestSurface(field, region, arrival[0], arrival[1], arrival[2]);
			if (origin == -1) {
				System.out.printf(Locale.ROOT, "Arrival %.1f %.1f %.1f has no walkable ground%n", arrival[0], arrival[1], arrival[2]);
				continue;
			}
			int arrivalRegion = region[origin];
			// on a map glided down, the arrival's own ground is a ledge and the question is what the ledge and everything below it come close to
			Set<Integer> mine = glidedTo(glides, arrivalRegion);
			Map<Integer, List<float[]>> unreached = new HashMap<>();
			int reached = 0, asked = 0;
			for (float[] spot : spots) {
				int surface = nearestSurface(field, region, spot[0], spot[1], spot[2]);
				if (surface == -1)
					continue;
				asked++;
				if (mine.contains(region[surface]))
					reached++;
				else
					unreached.computeIfAbsent(region[surface], k -> new ArrayList<>()).add(spot);
			}
			System.out.printf(Locale.ROOT, "Map %d, arrival %.1f %.1f %.1f is on region %d of %d cells, %d more regions glided down to: %d of %d spawn spots are in them%n", mapId,
				arrival[0], arrival[1], arrival[2], arrivalRegion, sizes.getOrDefault(arrivalRegion, 0), mine.size() - 1, reached, asked);
			List<Map.Entry<Integer, List<float[]>>> order = new ArrayList<>(unreached.entrySet());
			order.sort(Comparator.comparingInt((Map.Entry<Integer, List<float[]>> e) -> e.getValue().size()).reversed());
			for (Map.Entry<Integer, List<float[]>> entry : order.subList(0, Math.min(SHOWN, order.size()))) {
				int other = entry.getKey();
				List<float[]> some = entry.getValue();
				System.out.printf(Locale.ROOT, "  region %d, %d cells, holds %d spot(s), the first at %.1f %.1f %.1f%n", other, sizes.getOrDefault(other, 0), some.size(), some.get(0)[0],
					some.get(0)[1], some.get(0)[2]);
				float[] meeting = closest(field, region, mine, other);
				if (meeting == null) {
					System.out.println("    no ground of the arrival's regions within 5 m of it");
					continue;
				}
				System.out.printf(Locale.ROOT, "    comes within %.1f m across and %.1f m up of region %d: %.1f %.1f %.1f against %.1f %.1f %.1f%n", meeting[0], meeting[1],
					(int) meeting[8], meeting[2], meeting[3], meeting[4], meeting[5], meeting[6], meeting[7]);
				float midX = (meeting[2] + meeting[5]) / 2, midY = (meeting[3] + meeting[6]) / 2, midZ = (meeting[4] + meeting[7]) / 2;
				placements.stream().filter(p -> Math.hypot(p.position().x - midX, p.position().y - midY) <= NEAR && Math.abs(p.position().z - midZ) <= 30)
					.sorted(Comparator.comparingDouble(p -> Math.hypot(p.position().x - midX, p.position().y - midY))).limit(6)
					.forEach(p -> System.out.printf(Locale.ROOT, "      type %d %s at %.1f %.1f %.1f%n", p.type(), p.modelName().substring(Math.max(0, p.modelName().length() - 60)),
						p.position().x, p.position().y, p.position().z));
			}
		}
		return 0;
	}

	/** @return The surface of a region nearest the spot in height within three metres, looking a few cells round it, or -1. */
	private static int nearestSurface(Heightfield field, int[] region, float x, float y, float z) {
		int cellX = (int) (x / Heightfield.CELL_SIZE), cellY = (int) (y / Heightfield.CELL_SIZE);
		int best = -1;
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				int cx = cellX + dx, cy = cellY + dy;
				if (cx < 0 || cy < 0 || cx >= field.width() || cy >= field.height())
					continue;
				for (int i = field.columnStart(cx, cy); i < field.columnEnd(cx, cy); i++) {
					float gap = Math.abs(field.surfaceAt(i) - z);
					if (region[i] != 0 && gap <= 3f && (best == -1 || gap < Math.abs(field.surfaceAt(best) - z)))
						best = i;
				}
			}
		}
		return best;
	}

	/**
	 * @return The region and every region a chain of glide links leads to from it. The links are read from the map's file, whose regions are numbered as {@link Heightfield#regions}
	 *         numbers them for the same geometry.
	 */
	private static Set<Integer> glidedTo(Map<Integer, List<GlideLink>> glides, int start) {
		Set<Integer> seen = new HashSet<>(List.of(start));
		ArrayDeque<Integer> pending = new ArrayDeque<>(seen);
		while (!pending.isEmpty()) {
			for (GlideLink link : glides.getOrDefault(pending.poll(), List.of())) {
				if (seen.add(link.toRegion()))
					pending.add(link.toRegion());
			}
		}
		return seen;
	}

	/**
	 * @return Across, up, then x, y, z of a cell of the first regions and of the second, then the first region met, for the nearest meeting of the two; null if they come within no
	 *         window.
	 */
	private static float[] closest(Heightfield field, int[] region, Set<Integer> first, int second) {
		float[] best = null;
		float bestScore = Float.MAX_VALUE;
		for (int column = 0; column < field.width() * field.height(); column++) {
			int cx = column % field.width(), cy = column / field.width();
			for (int surface = field.columnStart(cx, cy); surface < field.columnEnd(cx, cy); surface++) {
				if (region[surface] != second)
					continue;
				float z = field.surfaceAt(surface);
				for (int dy = -WINDOW; dy <= WINDOW; dy++) {
					for (int dx = -WINDOW; dx <= WINDOW; dx++) {
						int nx = cx + dx, ny = cy + dy;
						if (nx < 0 || ny < 0 || nx >= field.width() || ny >= field.height())
							continue;
						for (int other = field.columnStart(nx, ny); other < field.columnEnd(nx, ny); other++) {
							if (!first.contains(region[other]))
								continue;
							float up = Math.abs(field.surfaceAt(other) - z);
							if (up > MAX_DIFFERENCE)
								continue;
							float across = (float) Math.hypot(dx, dy) * Heightfield.CELL_SIZE;
							float score = across + up; // a gap and a step cost alike, which is only a way to pick one meeting among many
							if (score < bestScore) {
								bestScore = score;
								best = new float[] { across, up, (nx + 0.5f) * Heightfield.CELL_SIZE, (ny + 0.5f) * Heightfield.CELL_SIZE, field.surfaceAt(other),
									(cx + 0.5f) * Heightfield.CELL_SIZE, (cy + 0.5f) * Heightfield.CELL_SIZE, z, region[other] };
							}
						}
					}
				}
			}
		}
		return best;
	}
}
