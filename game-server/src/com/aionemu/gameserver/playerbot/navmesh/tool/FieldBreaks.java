package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.GeoDataReader;
import com.aionemu.gameserver.playerbot.navmesh.GeoPlacement;
import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.HeightfieldBuilder;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where the open world's ground is cut, and by what: every pair of neighbouring monsters a hunting bot could pick, not a sample of them, the pairs the planner proves
 * apart grouped by the two stretches of ground they stand on, and for each group the place where the two stretches come closest and the models placed there.
 * <p>
 * {@link FieldReach} says how many pairs fail and prints ten of them. A bot's refusals come from a few breaks in the ground that many pairs share, and the models standing in
 * those breaks say whether they have a cause in common: a ramp too steep, a door, a rail. Each group is one break, whose pairs are counted so that the worst come first.
 * The regions are the generator's own ({@link Heightfield#regions}), found again from the geometry, so a mesh older than the generator may differ a little.
 */
final class FieldBreaks {

	/** How many breaks are described, the ones that cut most pairs first. */
	private static final int SHOWN = 15;
	/** How far from the meeting, in metres, a placed model is listed. */
	private static final float NEAR = 7f;
	/** The nearest models named for each break. */
	private static final int MODELS = 3;
	/** A stretch of ground of fewer cells than this is an islet, a spot a body stands on among props, and not a part of the map that is cut off. */
	private static final int ISLET = 100;

	private record Apart(float[] from, float[] to) {
	}

	private FieldBreaks() {
	}

	/** @return 0 when every pair was reached, 1 when some were not, 2 when there was nothing to ask. */
	static int run(int mapId) throws IOException {
		Path data = Path.of("data/static_data");
		List<float[]> spots = FieldReach.monsterSpots(data, mapId, FieldReach.monsters(data.resolve("npcs/npc_templates.xml")));
		List<float[][]> pairs = FieldReach.pairsOf(spots);
		if (pairs.isEmpty()) {
			System.out.printf(Locale.ROOT, "Map %d: %d monster spot(s) and no two close enough to pick one after the other%n", mapId, spots.size());
			return 2;
		}
		Navmesh mesh = Navmesh.open(mapId);
		List<Apart> apart = new ArrayList<>();
		int reached = 0, gaveUp = 0, noFooting = 0;
		for (float[][] pair : pairs) {
			float[] from = InstanceReach.snapped(mesh, pair[0]), to = InstanceReach.snapped(mesh, pair[1]);
			if (from == null || to == null) {
				noFooting++;
				continue;
			}
			BotPathFinder.Route route = BotPathFinder.findPath(mesh, from[0], from[1], from[2], to[0], to[1], to[2]);
			if (!route.isEmpty())
				reached++;
			else if (route.gaveUp())
				gaveUp++;
			else
				apart.add(new Apart(from, to));
		}
		int asked = pairs.size() - noFooting;
		System.out.printf(Locale.ROOT, "Map %d: %d of %d neighbouring monster pairs reached (%.1f%%), %d proved apart, %d gave up, %d without footing%n", mapId, reached, asked,
			asked == 0 ? 0f : reached * 100f / asked, apart.size(), gaveUp, noFooting);
		if (apart.isEmpty())
			return gaveUp == 0 ? 0 : 1;

		Heightfield field = HeightfieldBuilder.build(mapId);
		int[] region = field.regions(BotPathFinder.STEP_TOLERANCE);
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int r : region)
			if (r != 0)
				sizes.merge(r, 1, Integer::sum);
		Map<String, List<Apart>> breaks = new TreeMap<>();
		int unplaced = 0;
		for (Apart pair : apart) {
			int a = GapReport.nearestSurface(field, region, pair.from[0], pair.from[1], pair.from[2]);
			int b = GapReport.nearestSurface(field, region, pair.to[0], pair.to[1], pair.to[2]);
			if (a == -1 || b == -1 || region[a] == region[b]) {
				unplaced++; // the generator's regions say these two are joined, or have no ground: the mesh file and the geometry disagree
				continue;
			}
			breaks.computeIfAbsent(Math.min(region[a], region[b]) + " " + Math.max(region[a], region[b]), k -> new ArrayList<>()).add(pair);
		}
		List<GeoPlacement> placements = GeoDataReader.readPlacements(mapId);
		List<Map.Entry<String, List<Apart>>> order = new ArrayList<>(breaks.entrySet());
		order.sort(Comparator.comparingInt((Map.Entry<String, List<Apart>> e) -> e.getValue().size()).reversed());
		int isletPairs = 0, isletBreaks = 0;
		List<Map.Entry<String, List<Apart>>> cuts = new ArrayList<>();
		for (Map.Entry<String, List<Apart>> entry : order) {
			String[] ids = entry.getKey().split(" ");
			if (Math.min(sizes.getOrDefault(Integer.parseInt(ids[0]), 0), sizes.getOrDefault(Integer.parseInt(ids[1]), 0)) < ISLET) {
				isletPairs += entry.getValue().size();
				isletBreaks++;
			} else {
				cuts.add(entry);
			}
		}
		System.out.printf(Locale.ROOT, "  %d pair(s) end on %d islet(s) of under %d cells; %d pair(s) lie between %d stretches of ground of %d cells and more; %d not placed on two regions%n",
			isletPairs, isletBreaks, ISLET, apart.size() - unplaced - isletPairs, cuts.size(), ISLET, unplaced);
		Map<String, int[]> byModel = new TreeMap<>();
		for (Map.Entry<String, List<Apart>> entry : cuts.subList(0, Math.min(SHOWN, cuts.size()))) {
			String[] ids = entry.getKey().split(" ");
			int first = Integer.parseInt(ids[0]), second = Integer.parseInt(ids[1]);
			// the smaller stretch is the one searched, since the search visits each of its cells
			if (sizes.getOrDefault(first, 0) < sizes.getOrDefault(second, 0)) {
				int swap = first;
				first = second;
				second = swap;
			}
			Apart example = entry.getValue().get(0);
			System.out.printf(Locale.ROOT, "  %d pair(s), regions %d (%d cells) and %d (%d cells), e.g. NavmeshTool %d path %.1f %.1f %.1f %.1f %.1f %.1f%n", entry.getValue().size(),
				first, sizes.getOrDefault(first, 0), second, sizes.getOrDefault(second, 0), mapId, example.from[0], example.from[1], example.from[2], example.to[0], example.to[1],
				example.to[2]);
			float[] meeting = GapReport.closest(field, region, Set.of(first), second);
			if (meeting == null) {
				System.out.println("    no ground of the larger one within 5 m of the smaller");
				continue;
			}
			System.out.printf(Locale.ROOT, "    closest %.1f m across and %.1f m up: %.1f %.1f %.1f against %.1f %.1f %.1f%n", meeting[0], meeting[1], meeting[2], meeting[3], meeting[4],
				meeting[5], meeting[6], meeting[7]);
			float midX = (meeting[2] + meeting[5]) / 2, midY = (meeting[3] + meeting[6]) / 2, midZ = (meeting[4] + meeting[7]) / 2;
			List<String> names = placements.stream().filter(p -> Math.hypot(p.position().x - midX, p.position().y - midY) <= NEAR && Math.abs(p.position().z - midZ) <= 30)
				.sorted(Comparator.comparingDouble(p -> Math.hypot(p.position().x - midX, p.position().y - midY))).limit(MODELS)
				.map(p -> p.modelName().substring(p.modelName().lastIndexOf('/') + 1)).toList();
			System.out.println("    models: " + (names.isEmpty() ? "none within " + NEAR + " m" : String.join(", ", names)));
			for (String name : names.stream().distinct().toList()) {
				int[] count = byModel.computeIfAbsent(name, k -> new int[2]);
				count[0]++;
				count[1] += entry.getValue().size();
			}
		}
		System.out.println("  models at the breaks (breaks, pairs):");
		byModel.entrySet().stream().sorted((x, y) -> y.getValue()[1] - x.getValue()[1]).limit(10)
			.forEach(e -> System.out.printf(Locale.ROOT, "    %-50s %d break(s), %d pair(s)%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
		return 1;
	}
}
