package com.aionemu.gameserver.playerbot.movement;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Geometry queries used to keep bot movement server authoritative.
 * <p>
 * Without these the server would happily interpolate a bot straight through a tree while the client refuses to render it there, and the position
 * correction sent on arrival would look like a teleport.
 */
public class BotGeoHelper {

	/** Below this, a destination is not worth walking to and the bot is considered blocked. */
	private static final float MIN_STEP = 1.0f;
	/** The probe walks the ground in one metre steps, so its cost grows with distance. Longer routes are split into legs. */
	private static final float MAX_LEG_DISTANCE = 25f;
	/** Half the width the bot needs to squeeze through, since geo probes are lines but client side collision uses a body sized capsule. */
	private static final float BOT_RADIUS = 0.5f;
	/** Ignore a marginally shorter side probe, which just means the corridor narrows a little without actually blocking the way. */
	private static final float CLEARANCE_TOLERANCE = 0.5f;
	private static final int[] SIDES = { 1, -1 };
	/** Deviations tried when the direct way is blocked, from the mildest to a full sidestep. */
	private static final float[] DETOUR_ANGLES = { 40, 75, 110 };
	private static final float DETOUR_DISTANCE = 8f;
	/** A detour may lose some ground to the goal, since walking around an obstacle is rarely a shortcut. */
	private static final float DETOUR_MAX_LOSS = 3f;

	private BotGeoHelper() {
	}

	/**
	 * A way around an obstacle.
	 *
	 * @param side Which hand the bot passes the obstacle on, fed back into the next search so it keeps going the same way around.
	 */
	public record Detour(Vector3f point, int side) {
	}

	/**
	 * @return The furthest point towards the destination the bot can actually walk to, following the ground. Real obstacles (trees, walls, inclines
	 *         over 45°) stop it, mere slopes do not. Capped at one leg length, so a long route yields an intermediate waypoint.
	 */
	public static Vector3f reachablePointToward(Player bot, float x, float y, float z) {
		float angle = PositionUtil.calculateAngleFrom(bot.getX(), bot.getY(), x, y);
		float distance = (float) PositionUtil.getDistance(bot.getX(), bot.getY(), x, y);
		return walkableCorridor(bot, angle, Math.min(distance, MAX_LEG_DISTANCE));
	}

	/**
	 * Looks for a way past an obstacle blocking the direct way, by probing to both sides with a growing deviation.
	 *
	 * @param preferredSide The side of a detour already in progress (0 if none), tried first so the bot commits to one way around instead of
	 *          oscillating between two equally good ones.
	 * @return The best sidestep found, or null if the bot is walled in.
	 */
	public static Detour detourPointToward(Player bot, float x, float y, float z, int preferredSide) {
		float goalAngle = PositionUtil.calculateAngleFrom(bot.getX(), bot.getY(), x, y);
		double maxDistanceToGoal = PositionUtil.getDistance(bot.getX(), bot.getY(), x, y) + DETOUR_MAX_LOSS;
		int[] sides = preferredSide < 0 ? new int[] { -1, 1 } : SIDES;

		Detour best = null;
		double bestDistanceToGoal = Double.MAX_VALUE;
		for (float angle : DETOUR_ANGLES) {
			for (int side : sides) {
				Vector3f point = walkableCorridor(bot, goalAngle + side * angle, DETOUR_DISTANCE);
				if (!isWorthMovingTo(bot, point))
					continue;
				double distanceToGoal = PositionUtil.getDistance(point.getX(), point.getY(), x, y);
				if (distanceToGoal > maxDistanceToGoal || distanceToGoal >= bestDistanceToGoal)
					continue;
				best = new Detour(point, side);
				bestDistanceToGoal = distanceToGoal;
			}
			if (best != null) // no point widening the deviation once a way around is found
				break;
		}
		return best;
	}

	public static boolean isWorthMovingTo(Player bot, Vector3f point) {
		return PositionUtil.getDistance(bot.getX(), bot.getY(), point.getX(), point.getY()) >= MIN_STEP;
	}

	/**
	 * Probes how far the bot can walk in the given direction, as a body wide corridor rather than as a line.
	 * <p>
	 * A single probe is an infinitely thin ray, so it happily passes a hand's breadth from a post or a rock the client then refuses to walk through.
	 * The server keeps moving while the client model stays behind, and the position correction sent on arrival looks like a teleport. Two extra
	 * probes, deviated just enough to end up {@code BOT_RADIUS} aside at the far end, approximate the corridor the body actually needs.
	 */
	private static Vector3f walkableCorridor(Player bot, float angle, float maxDistance) {
		Vector3f ahead = probe(bot, angle, maxDistance);
		float reach = walkedDistance(bot, ahead);
		if (reach < MIN_STEP)
			return ahead;

		float spread = (float) Math.toDegrees(Math.atan(BOT_RADIUS / reach));
		float clearance = reach;
		for (int side : SIDES)
			clearance = Math.min(clearance, walkedDistance(bot, probe(bot, angle + side * spread, reach)));

		if (clearance >= reach - CLEARANCE_TOLERANCE)
			return ahead; // both sides are as clear as the middle
		return clearance < MIN_STEP ? new Vector3f(bot.getX(), bot.getY(), bot.getZ()) : probe(bot, angle, clearance);
	}

	private static Vector3f probe(Player bot, float angle, float distance) {
		return GeoService.getInstance().findMovementCollision(bot, angle, distance);
	}

	private static float walkedDistance(Player bot, Vector3f point) {
		return (float) PositionUtil.getDistance(bot.getX(), bot.getY(), point.getX(), point.getY());
	}
}
