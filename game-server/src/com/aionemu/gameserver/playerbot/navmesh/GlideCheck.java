package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What glide links would do for an open-world map, worked out in memory and written nowhere.
 * <p>
 * The server reads a map's {@code .glide} file the moment one is there, so finding out whether the open world wants them cannot be done by writing them.
 * This builds the map's ground the way the generator does, finds the links the way it does, and says how many there would be, what the file would weigh,
 * and for each obelisk standing on ground apart from the rest of the map whether a chain of glides takes it there: the bots resurrected on those are the
 * ones the mesh strands.
 */
final class GlideCheck {

	/** How far above or below an obelisk's recorded height its floor may be, as in the obelisk audit. */
	private static final float FLOOR_REACH = 2.5f;

	private GlideCheck() {
	}

	static void run(int mapId) throws IOException {
		long started = System.currentTimeMillis();
		Heightfield field = HeightfieldBuilder.build(mapId);
		int[] regions = field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(mapId));
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int region : regions)
			if (region != 0)
				sizes.merge(region, 1, Integer::sum);
		int main = sizes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
		System.out.printf(Locale.ROOT, "Map %d: ground built in %d s, %d stretches, the main one %d surfaces%n", mapId, (System.currentTimeMillis() - started) / 1000,
			sizes.size(), sizes.getOrDefault(main, 0));

		List<ObeliskAudit.Obelisk> apart = new ArrayList<>();
		for (ObeliskAudit.Obelisk obelisk : ObeliskAudit.readObelisks(Path.of("data/static_data")).getOrDefault(mapId, List.of())) {
			int surface = floorOf(field, obelisk);
			if (surface != -1 && regions[surface] != main) // inside geometry is no ground question, and on the main ground there is nothing to glide to
				apart.add(obelisk);
		}
		System.out.printf(Locale.ROOT, "  %d obelisk(s) on ground apart from the main one%n", apart.size());
		for (GlideLinks.Rules rules : CANDIDATES) {
			started = System.currentTimeMillis();
			List<GlideLink> links = GlideLinks.find(field, regions, rules);
			Map<Integer, List<GlideLink>> from = new HashMap<>();
			for (GlideLink link : links)
				from.computeIfAbsent(link.fromRegion(), k -> new ArrayList<>()).add(link);
			int freed = 0;
			for (ObeliskAudit.Obelisk obelisk : apart) {
				if (glidedTo(from, regions[floorOf(field, obelisk)]).contains(main))
					freed++;
			}
			System.out.printf(Locale.ROOT, "  %-30s %8d links, %5.1f MB, %d of %d obelisk(s) freed, %d s%n", rules, links.size(), (4 + links.size() * 32L) / 1e6, freed, apart.size(),
				(System.currentTimeMillis() - started) / 1000);
		}
	}

	/** The rules measured against each other: the instances', then fewer links a pair, then landing on large ground only, then both. */
	private static final List<GlideLinks.Rules> CANDIDATES = List.of(new GlideLinks.Rules(40, 40, 24), new GlideLinks.Rules(40, 40, 4), new GlideLinks.Rules(40, 400, 24),
		new GlideLinks.Rules(40, 400, 4), new GlideLinks.Rules(400, 2000, 4));

	/** @return The walkable surface under the obelisk or beside its plinth, nearest its recorded height within {@link #FLOOR_REACH}, or -1. */
	private static int floorOf(Heightfield field, ObeliskAudit.Obelisk obelisk) {
		int cellX = (int) (obelisk.x() / Heightfield.CELL_SIZE), cellY = (int) (obelisk.y() / Heightfield.CELL_SIZE);
		int best = -1;
		for (int ring = 0; ring <= 8 && best == -1; ring++) {
			for (int dy = -ring; dy <= ring; dy++) {
				for (int dx = -ring; dx <= ring; dx++) {
					int x = cellX + dx, y = cellY + dy;
					if (Math.max(Math.abs(dx), Math.abs(dy)) != ring || x < 0 || y < 0 || x >= field.width() || y >= field.height())
						continue;
					for (int surface = field.columnStart(x, y); surface < field.columnEnd(x, y); surface++) {
						float gap = Math.abs(field.surfaceAt(surface) - obelisk.z());
						if (field.isWalkable(surface) && gap <= FLOOR_REACH && (best == -1 || gap < Math.abs(field.surfaceAt(best) - obelisk.z())))
							best = surface;
					}
				}
			}
		}
		return best;
	}

	private static Set<Integer> glidedTo(Map<Integer, List<GlideLink>> from, int start) {
		Set<Integer> seen = new HashSet<>(List.of(start));
		ArrayDeque<Integer> pending = new ArrayDeque<>(seen);
		while (!pending.isEmpty()) {
			for (GlideLink link : from.getOrDefault(pending.poll(), List.of())) {
				if (seen.add(link.toRegion()))
					pending.add(link.toRegion());
			}
		}
		return seen;
	}
}
