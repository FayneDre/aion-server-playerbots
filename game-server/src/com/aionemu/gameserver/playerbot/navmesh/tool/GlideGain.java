package com.aionemu.gameserver.playerbot.navmesh.tool;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
import com.aionemu.gameserver.playerbot.navmesh.LedgeGlides;
import com.aionemu.gameserver.playerbot.navmesh.Navmesh;

/**
 * What a glide would save on the journeys a bot makes, before any rule is written ({@code NavmeshTool <map> glidegain [samples]}).
 * <p>
 * The planner only asks for a glide when the walk found nothing, so nothing says how often a glide would have been quicker. This samples journeys of 80 to 400 m between
 * two walkable places of the map, plans each on foot and by glides alone, and puts the two side by side by time (a run at {@link #RUN_SPEED}, a glide at {@link #GLIDE_SPEED},
 * the walk to the take-off included), by the drop between the two places. Samples are drawn uniformly over the map's ground, so they stand for where a bot may be sent and not for
 * where it goes: the figure is a range to size the work by, to be read with the bots' own routes.
 * <p>
 * Written for backlog point 4 ({@code docs/navigation/plans/glide-use-plan.md}, milestone 0).
 */
final class GlideGain {

	/** A bot's run, in metres a second. */
	static final float RUN_SPEED = 6f;
	/** The speed of a glide leg, the same as {@code BotMoveController.GLIDE_SPEED}. */
	static final float GLIDE_SPEED = 8f;
	/** Metres forward for a metre of drop, the same as {@code BotMoveController.GLIDE_RATIO}. */
	static final float GLIDE_RATIO = 2.5f;
	private static final float MIN_RANGE = 80f, MAX_RANGE = 400f;
	/** The least share of the walk's time a glide must save to be counted as worth taking. */
	private static final float WORTH = 0.30f;
	private static final String[] BANDS = { "drop under 8 m", "drop 8 to 30 m", "drop over 30 m" };

	private GlideGain() {
	}

	static int run(int mapId, int samples) throws IOException {
		Navmesh mesh = Navmesh.open(mapId);
		Random random = new Random(mapId);
		int[] sampled = new int[3], walkable = new int[3], gliding = new int[3], quicker = new int[3], shortcuts = new int[3];
		double[] savedSeconds = new double[3];
		int attempts = 0;
		while (sum(sampled) < samples && attempts++ < samples * 60) {
			float[] start = anyGround(mesh, random);
			if (start == null)
				continue;
			double angle = random.nextDouble() * 2 * Math.PI, range = MIN_RANGE + random.nextDouble() * (MAX_RANGE - MIN_RANGE);
			float[] goal = groundNear(mesh, random, (float) (start[0] + Math.cos(angle) * range), (float) (start[1] + Math.sin(angle) * range));
			if (goal == null)
				continue;
			int band = Math.abs(start[2] - goal[2]) < 8 ? 0 : Math.abs(start[2] - goal[2]) <= 30 ? 1 : 2;
			// a glide only goes down: the other way round is a walk by the nature of it
			if (goal[2] > start[2])
				continue;
			sampled[band]++;
			BotPathFinder.Route walk = BotPathFinder.walkingOnly(mesh, start[0], start[1], start[2], goal[0], goal[1], goal[2]);
			if (walk.isEmpty())
				continue;
			walkable[band]++;
			// the bound for a link the files do not hold (they join stretches of ground that no walk joins, and these two are joined): a glide from the start itself could reach the
			// goal (2.5 m forward for 1 m of drop), and the walk takes at least twice the crow's flight, so a shortcut link here would have saved a good part of the time
			double crow = Math.hypot(goal[0] - start[0], goal[1] - start[1]);
			if (band > 0 && crow <= GLIDE_RATIO * (start[2] - goal[2]) && seconds(walk) * (1 - WORTH) > crow / GLIDE_SPEED)
				shortcuts[band]++;
			BotPathFinder.Route glide = BotPathFinder.glidingOnly(mesh, start[0], start[1], start[2], goal[0], goal[1], goal[2]);
			if (glide.isEmpty() || glide.glides().isEmpty())
				continue;
			gliding[band]++;
			double walkSeconds = seconds(walk), glideSeconds = seconds(glide);
			if (glideSeconds < walkSeconds * (1 - WORTH)) {
				quicker[band]++;
				savedSeconds[band] += walkSeconds - glideSeconds;
			}
		}
		System.out.printf(Locale.ROOT, "Map %d: %d descents of %.0f to %.0f m sampled; walk at %.0f m/s, glide at %.0f m/s, worth = %.0f%% of the time saved%n", mapId, sum(sampled),
			MIN_RANGE, MAX_RANGE, RUN_SPEED, GLIDE_SPEED, WORTH * 100);
		for (int band = 0; band < 3; band++) {
			System.out.printf(Locale.ROOT,
				"  %-15s sampled %4d, walkable %4d, glide route in the files %4d (worth it %4d, %.0f s saved on average), a shortcut link would be worth it %4d (%.0f%% of the walkable)%n",
				BANDS[band], sampled[band], walkable[band], gliding[band], quicker[band], quicker[band] == 0 ? 0 : savedSeconds[band] / quicker[band], shortcuts[band],
				walkable[band] == 0 ? 0 : 100.0 * shortcuts[band] / walkable[band]);
		}
		return 0;
	}

	/** Of random places on the ground, how many have an edge to glide off within 25 m, and what they look like: the ledge finder of the ambient glides, offline. */
	static int ledges(int mapId, int samples) throws IOException {
		Navmesh mesh = Navmesh.open(mapId);
		Random random = new Random(mapId);
		int places = 0, withLedge = 0, ledgesFound = 0;
		float dropSum = 0;
		for (int attempts = 0; places < samples && attempts < samples * 60; attempts++) {
			float[] spot = anyGround(mesh, random);
			if (spot == null)
				continue;
			places++;
			List<LedgeGlides.Ledge> found = LedgeGlides.around(mesh, spot[0], spot[1], spot[2], 25, 8, 40, random.nextLong());
			if (found.isEmpty())
				continue;
			withLedge++;
			ledgesFound += found.size();
			for (LedgeGlides.Ledge ledge : found)
				dropSum += ledge.takeoff().getZ() - ledge.landing().getZ();
			if (withLedge <= 5) {
				Vector3f off = found.get(0).takeoff(), down = found.get(0).landing();
				System.out.printf(Locale.ROOT, "  e.g. from %.0f %.0f %.0f: take off %.0f %.0f %.0f, land %.0f %.0f %.0f%n", spot[0], spot[1], spot[2], off.getX(), off.getY(),
					off.getZ(), down.getX(), down.getY(), down.getZ());
			}
		}
		System.out.printf(Locale.ROOT, "Map %d: %d places, %d with an edge to glide off within 25 m (%.0f%%), %d edges, average drop %.0f m%n", mapId, places, withLedge,
			places == 0 ? 0 : 100.0 * withLedge / places, ledgesFound, ledgesFound == 0 ? 0 : dropSum / ledgesFound);
		return 0;
	}

	private static int sum(int[] counts) {
		return counts[0] + counts[1] + counts[2];
	}

	/** @return The seconds a route takes: its glide legs at the glide speed, every other leg at a run. */
	private static double seconds(BotPathFinder.Route route) {
		List<Vector3f> points = route.waypoints();
		double total = 0;
		for (int i = 1; i < points.size(); i++) {
			Vector3f from = points.get(i - 1), to = points.get(i);
			double length = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getY() - from.getY(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
			total += length / (route.glides().contains(i) ? GLIDE_SPEED : RUN_SPEED);
		}
		return total;
	}

	/** @return x, y and z of a walkable surface at a random place of the map, or null. */
	private static float[] anyGround(Navmesh mesh, Random random) {
		return groundNear(mesh, random, random.nextFloat() * mesh.width() * mesh.cellSize(), random.nextFloat() * mesh.height() * mesh.cellSize());
	}

	/** @return x, y and z of a walkable surface in the cell holding that point (one of them at random when several), or null. */
	private static float[] groundNear(Navmesh mesh, Random random, float x, float y) {
		int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
		if (!mesh.contains(cellX, cellY))
			return null;
		int count = mesh.surfaceCount(cellX, cellY);
		if (count == 0)
			return null;
		int surface = random.nextInt(count);
		if (!mesh.isWalkable(cellX, cellY, surface))
			return null;
		return new float[] { (cellX + 0.5f) * mesh.cellSize(), (cellY + 0.5f) * mesh.cellSize(), mesh.surfaceZ(cellX, cellY, surface) };
	}
}
