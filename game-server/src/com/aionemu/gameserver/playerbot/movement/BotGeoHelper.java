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

	private BotGeoHelper() {
	}

	/**
	 * @return The furthest point towards the destination the bot can actually reach, with its height snapped to the ground. Equals the destination
	 *         when the way is clear, and the bot's own position when an obstacle blocks it immediately. Returns the destination unchanged when geo
	 *         data is disabled.
	 */
	public static Vector3f reachablePointToward(Player bot, float x, float y, float z) {
		return GeoService.getInstance().getClosestCollision(bot, x, y, z);
	}

	public static boolean isWorthMovingTo(Player bot, Vector3f point) {
		return PositionUtil.getDistance(bot.getX(), bot.getY(), point.getX(), point.getY()) >= MIN_STEP;
	}
}
