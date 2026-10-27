package com.aionemu.gameserver.playerbot.navmesh.tool;

import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether a bot hunting in the open world can walk to the monsters it would pick, which is the question behind most of its give-ups.
 * <p>
 * A bot picks its next target among the monsters within {@code BotTargetSelector.CHASE_RADIUS} of it and a few metres of its height, and in ten minutes of
 * play one in two of the fights it started ended in "cannot reach and gives up". So this asks the same thing of the mesh: from where a monster stands, can
 * the monsters around it be walked to? Pairs of spawn spots of monsters, each within that radius of the other, are routed between, a seeded sample of them
 * so that two runs of the same mesh compare. The pairs that fail are printed with the command that plans them.
 */
final class FieldReach {

	/** As far as a bot looks for its next target, {@code BotTargetSelector.CHASE_RADIUS}. */
	private static final float RADIUS = 25f;
	/** As far above or below itself as a bot picks a target, {@code BotTargetSelector.MAX_Z_DELTA}. */
	private static final float HEIGHT = 8f;
	private static final int PAIRS = 400;
	private static final int SHOWN = 10;

	private static final Pattern MONSTER = Pattern.compile("<npc_template\\s+npc_id=\"(\\d+)\"[^>]*\\stype=\"MONSTER\"");
	private static final Pattern NPC = Pattern.compile("<spawn\\s+[^>]*npc_id=\"(\\d+)\"");
	private static final Pattern SPOT = Pattern.compile("<spot\\s+x=\"([-\\d.]+)\"\\s+y=\"([-\\d.]+)\"\\s+z=\"([-\\d.]+)\"");

	private FieldReach() {
	}

	/** @return 0 when every pair was reached, 1 when some were not, 2 when there was nothing to ask. */
	static int run(int mapId) throws IOException {
		Path data = Path.of("data/static_data");
		List<float[]> spots = monsterSpots(data, mapId, monsters(data.resolve("npcs/npc_templates.xml")));
		Navmesh mesh = Navmesh.open(mapId);
		List<float[][]> pairs = pairsOf(spots);
		Collections.shuffle(pairs, new Random(mapId));
		List<float[][]> sample = pairs.subList(0, Math.min(PAIRS, pairs.size()));
		if (sample.isEmpty()) {
			System.out.printf(Locale.ROOT, "Map %d: %d monster spot(s) and no two within %.0f m of each other%n", mapId, spots.size(), RADIUS);
			return 2;
		}
		int reached = 0, refused = 0, gaveUp = 0, noFooting = 0;
		List<String> failed = new ArrayList<>();
		long started = System.currentTimeMillis();
		for (float[][] pair : sample) {
			float[] from = InstanceReach.snapped(mesh, pair[0]), to = InstanceReach.snapped(mesh, pair[1]);
			if (from == null || to == null) {
				noFooting++; // a monster in the middle of geometry is the data's, not the route's
				continue;
			}
			BotPathFinder.Route route = BotPathFinder.findPath(mesh, from[0], from[1], from[2], to[0], to[1], to[2]);
			if (!route.isEmpty()) {
				reached++;
				continue;
			}
			if (route.gaveUp())
				gaveUp++;
			else
				refused++;
			failed.add(String.format(Locale.ROOT, "  %s %.1f %.1f %.1f to %.1f %.1f %.1f   NavmeshTool %d path %.1f %.1f %.1f %.1f %.1f %.1f", route.gaveUp() ? "gave up" : "no way ",
				from[0], from[1], from[2], to[0], to[1], to[2], mapId, from[0], from[1], from[2], to[0], to[1], to[2]));
		}
		int asked = sample.size() - noFooting;
		System.out.printf(Locale.ROOT, "Map %d: %d of %d neighbouring monster pairs reached (%.0f%%), %d proved apart, %d gave up, %d without footing, in %d ms%n", mapId,
			reached, asked, asked == 0 ? 0f : reached * 100f / asked, refused, gaveUp, noFooting, System.currentTimeMillis() - started);
		failed.stream().limit(SHOWN).forEach(System.out::println);
		return failed.isEmpty() ? 0 : 1;
	}

	/** @return The ids of every monster template: what a bot hunts, and not the guards, merchants and townsfolk the spawn files also hold. */
	static Set<Integer> monsters(Path templates) throws IOException {
		Set<Integer> ids = new HashSet<>();
		try (Stream<String> lines = Files.lines(templates)) {
			lines.forEach(line -> {
				Matcher matcher = MONSTER.matcher(line);
				if (matcher.find())
					ids.add(Integer.parseInt(matcher.group(1)));
			});
		}
		return ids;
	}

	/** @return Every spawn spot of a monster on the map, from the map's files under {@code spawns/Npcs}. */
	static List<float[]> monsterSpots(Path data, int mapId, Set<Integer> monsters) throws IOException {
		List<float[]> spots = new ArrayList<>();
		Path folder = data.resolve("spawns/Npcs");
		if (!Files.isDirectory(folder))
			return spots;
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith(mapId + "_")).toList()) {
				boolean monster = false;
				for (String line : Files.readAllLines(file)) {
					Matcher matcher = NPC.matcher(line);
					if (matcher.find()) {
						monster = monsters.contains(Integer.parseInt(matcher.group(1)));
						continue;
					}
					if (monster && (matcher = SPOT.matcher(line)).find())
						spots.add(new float[] { Float.parseFloat(matcher.group(1)), Float.parseFloat(matcher.group(2)), Float.parseFloat(matcher.group(3)) });
				}
			}
		}
		return spots;
	}

	/** @return Every pair of spots within {@link #RADIUS} across and {@link #HEIGHT} up of each other, each pair once, found through a grid of the radius. */
	static List<float[][]> pairsOf(List<float[]> spots) {
		Map<Long, List<Integer>> grid = new HashMap<>();
		for (int i = 0; i < spots.size(); i++)
			grid.computeIfAbsent(cellOf((int) Math.floor(spots.get(i)[0] / RADIUS), (int) Math.floor(spots.get(i)[1] / RADIUS)), k -> new ArrayList<>()).add(i);
		List<float[][]> pairs = new ArrayList<>();
		for (int i = 0; i < spots.size(); i++) {
			float[] spot = spots.get(i);
			int cx = (int) Math.floor(spot[0] / RADIUS), cy = (int) Math.floor(spot[1] / RADIUS);
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int j : grid.getOrDefault(cellOf(cx + dx, cy + dy), List.of())) {
						if (j <= i)
							continue; // each pair once, in the order of the files, so two runs ask the same question the same way round
						float[] other = spots.get(j);
						double across = Math.hypot(other[0] - spot[0], other[1] - spot[1]);
						if (across > 1 && across <= RADIUS && Math.abs(other[2] - spot[2]) <= HEIGHT) // not two spawns of the same place
							pairs.add(new float[][] { spot, other });
					}
				}
			}
		}
		return pairs;
	}

	private static long cellOf(int cellX, int cellY) {
		return ((long) cellX << 32) ^ (cellY & 0xffffffffL);
	}
}
