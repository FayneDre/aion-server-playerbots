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

	private BotGeoHelper() {
	}

	/**
	 * @return The furthest point towards the destination the bot can actually walk to, following the ground. Real obstacles (trees, walls, inclines
	 *         over 45°) stop it, mere slopes do not. Capped at one leg length, so a long route yields an intermediate waypoint.
	 */
	public static Vector3f reachablePointToward(Player bot, float x, float y, float z) {
		float angle = PositionUtil.calculateAngleFrom(bot.getX(), bot.getY(), x, y);
		float distance = (float) PositionUtil.getDistance(bot.getX(), bot.getY(), x, y);
		return GeoService.getInstance().findMovementCollision(bot, angle, Math.min(distance, MAX_LEG_DISTANCE));
	}

	public static boolean isWorthMovingTo(Player bot, Vector3f point) {
		return PositionUtil.getDistance(bot.getX(), bot.getY(), point.getX(), point.getY()) >= MIN_STEP;
	}
}
