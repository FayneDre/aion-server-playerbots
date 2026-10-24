package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Proves, from the coarse grid and the links alone, that two spots are on ground nothing joins: no stretch of ground passes under both, and no glide,
 * flight or ride leads from the one to the other. No route is searched, so it costs a few array lookups.
 * <p>
 * It exists for the searches that run out of budget. 669 of 1173 "no route" answers in half an hour were give-ups, 203 of them for journeys under
 * 150 m (24 October), and a short give-up is read as no evidence, so the bot set off at the goal regardless: walked into whatever cut it off, stepped
 * aside, made no headway and was rescued. A search from a large stretch of ground to a goal on another one spends its whole budget before it can say
 * no, which is exactly the case this answers.
 * <p>
 * Sharing a stretch is necessary for two spots to be joined, which is what makes sharing none a proof. Read over the nine 4 m cells round each spot
 * rather than the one under it: a cell only keeps a stretch covering a quarter of its floor plan, and a body on a narrow strip would otherwise find its
 * own stretch missing from its own cell. A spot with no stretch round it at all proves nothing.
 */
public final class RegionProof {

	private RegionProof() {
	}

	/** @return true when no walk and no chain of links joins the two spots, as far as the coarse grid can tell; false when they may well be joined. */
	public static boolean apart(Navmesh mesh, float fromX, float fromY, float toX, float toY) {
		Set<Integer> from = regionsAround(mesh, fromX, fromY), to = regionsAround(mesh, toX, toY);
		if (from.isEmpty() || to.isEmpty())
			return false;
		// every link, glides, flights and rides alike, with no regard to who may take them: a proof has to hold for any body
		ArrayDeque<Integer> pending = new ArrayDeque<>(from);
		Set<Integer> seen = new HashSet<>(from);
		while (!pending.isEmpty()) {
			int region = pending.poll();
			if (to.contains(region))
				return false;
			for (GlideLink link : mesh.glidesFrom(region)) {
				if (seen.add(link.toRegion()))
					pending.add(link.toRegion());
			}
		}
		return true;
	}

	private static Set<Integer> regionsAround(Navmesh mesh, float x, float y) {
		Set<Integer> regions = new HashSet<>();
		int factor = mesh.coarseFactor(), centreX = mesh.cellX(x) / factor, centreY = mesh.cellY(y) / factor;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int index = 0; index < mesh.coarseRegionCount(centreX + dx, centreY + dy); index++)
					regions.add(mesh.coarseRegion(centreX + dx, centreY + dy, index));
			}
		}
		return regions;
	}

	/**
	 * {@code NavmeshTool <map> apart <file>}: holds the proof against the real search, on journeys taken from the server's log. Each line of the file is
	 * {@code x1 y1 x2 y2}; for every pair the proof calls apart, every floor of the start is searched to every floor of the goal with the budget given
	 * as {@code -Dnavmesh.maxNodes}, and a route found is a false proof, printed.
	 *
	 * @return 0 when no proof was false, 1 otherwise.
	 */
	static int validate(int mapId, Path pairs) throws IOException {
		Navmesh mesh = Navmesh.open(mapId);
		int apart = 0, falseProofs = 0, together = 0, foundWithMore = 0;
		boolean searchTogether = Boolean.getBoolean("navmesh.apart.searchTogether");
		for (String line : Files.readAllLines(pairs)) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 4)
				continue;
			float x1 = Float.parseFloat(parts[0]), y1 = Float.parseFloat(parts[1]), x2 = Float.parseFloat(parts[2]), y2 = Float.parseFloat(parts[3]);
			if (!apart(mesh, x1, y1, x2, y2)) {
				together++;
				// with -Dnavmesh.apart.searchTogether: whether the journeys the proof leaves alone are found with the larger budget, which tells a
				// search short of budget from a grid that sees a join the fine search cannot walk
				if (searchTogether && anyRoute(mesh, x1, y1, x2, y2))
					foundWithMore++;
				continue;
			}
			apart++;
			if (anyRoute(mesh, x1, y1, x2, y2)) {
				falseProofs++;
				System.out.printf("FALSE PROOF %.1f %.1f -> %.1f %.1f%n", x1, y1, x2, y2);
			}
		}
		System.out.printf("Map %d: %d pair(s) proven apart, %d of them wrongly; %d left to the search%s%n", mapId, apart, falseProofs, together,
			searchTogether ? ", " + foundWithMore + " of which found with the larger budget" : "");
		return falseProofs == 0 ? 0 : 1;
	}

	private static boolean anyRoute(Navmesh mesh, float x1, float y1, float x2, float y2) {
		for (float z1 : floors(mesh, x1, y1)) {
			for (float z2 : floors(mesh, x2, y2)) {
				if (!BotPathFinder.findPath(mesh, x1, y1, z1, x2, y2, z2).isEmpty())
					return true;
			}
		}
		return false;
	}

	private static List<Float> floors(Navmesh mesh, float x, float y) {
		List<Float> floors = new ArrayList<>();
		int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
		if (!mesh.contains(cellX, cellY))
			return floors;
		for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
			if (mesh.isWalkable(cellX, cellY, surface))
				floors.add(mesh.surfaceZ(cellX, cellY, surface));
		}
		return floors;
	}
}
