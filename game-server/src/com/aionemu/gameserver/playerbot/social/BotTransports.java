package com.aionemu.gameserver.playerbot.social;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Takes a grouped bot along when its player travels by something a bot does not use: a flight transporter, a windstream, a teleporter, a scroll.
 * <p>
 * Seen in game on 20 October: the player took the flight from Eltnen's obelisk, the three bots of the group tried to walk after a body moving at twenty
 * metres a second, were refused a route every second for a minute, were rescued to their own homes across the map, and one died walking to a leader a
 * kilometre away. A teleporter left them the same way, only at once.
 * <p>
 * The bot does what {@link BotInstances} does at a portal: it is put beside the player when the player has arrived. While a flight is under way it
 * stays where it is ({@link #isLeaderInTransit}), since the place it would walk to is moving through the air; the player's arrival then reads as the
 * group having taken the same flight. A teleport is recognised by the player being far from where they were a moment ago, on this map or another.
 */
public final class BotTransports {

	private static final Logger log = LoggerFactory.getLogger(BotTransports.class);
	/** How far beyond what they could have covered the player must be from where they were for it to be a teleport, in metres. */
	private static final float TELEPORT_JUMP = 100;
	/**
	 * The fastest a player covers ground on their own, in metres a second: a run under a scroll, a ride, wings. Two looks are usually a second apart, but
	 * a bot that is gliding or riding itself does not look, and a jump made meanwhile is still a jump if it beats this.
	 */
	private static final float PLAYER_TOP_SPEED = 20;
	/** How near the player's arrival a bot already is when there is nothing to take it to. */
	private static final float ALREADY_WITH_THEM = 60;

	/** Where the player was seen, from the bot's point of view. */
	private record Sighting(int worldId, float x, float y, float z, long at) {
	}

	/** One bot's view of its player's travels: where it last saw them, and whether it owes them a join. */
	private static final class Watch {

		private Sighting last;
		private boolean joinPending;
		private String how;
	}

	private static final Map<Integer, Watch> watches = new ConcurrentHashMap<>();

	private BotTransports() {
	}

	/** @return true while the bot's player is on a flight transporter or a windstream, during which the bot neither walks after them nor flies with them. */
	public static boolean isLeaderInTransit(Player bot) {
		Player player = BotInstances.playerToFollow(bot);
		return player != null && player.isUsingFlightTransporterOrWindstream();
	}

	/**
	 * Looks at where the bot's player has gone, and puts the bot beside them once they have arrived somewhere it could not follow.
	 *
	 * @return true if the bot has just been moved, in which case whatever it was about to do was decided somewhere it no longer is.
	 */
	public static boolean followThePlayer(Player bot) {
		Player player = BotInstances.playerToFollow(bot);
		if (player == null || !player.isOnline() || bot.isInInstance() || player.isInInstance()) {
			watches.remove(bot.getObjectId()); // instances are BotInstances' business, and a bot with no player owes nobody a join
			return false;
		}
		Watch watch = watches.computeIfAbsent(bot.getObjectId(), _ -> new Watch());
		if (!player.isSpawned())
			return false; // still loading the map it has just been sent to: its position is already the new one, but it is not in the world there yet
		long now = System.currentTimeMillis();
		Sighting seen = new Sighting(player.getWorldId(), player.getX(), player.getY(), player.getZ(), now);
		Sighting before = watch.last;
		watch.last = seen;
		if (player.isUsingFlightTransporterOrWindstream()) {
			if (!watch.joinPending)
				log.info("Bot {} waits for {}, who has taken a flight", bot.getName(), player.getName());
			watch.joinPending = true;
			watch.how = "a flight";
			return false;
		}
		if (before != null && (before.worldId() != seen.worldId() || PositionUtil.getDistance(before.x(), before.y(), before.z(), seen.x(), seen.y(),
			seen.z()) > TELEPORT_JUMP + PLAYER_TOP_SPEED * (now - before.at()) / 1000f)) {
			watch.joinPending = true;
			watch.how = before.worldId() != seen.worldId() ? "a teleport to map " + seen.worldId() : "a teleport";
		}
		if (!watch.joinPending || !bot.isSpawned() || bot.isDead())
			return false; // a dead bot comes back first, and is taken along on the tick after
		watch.joinPending = false;
		if (bot.getWorldId() == player.getWorldId() && PositionUtil.getDistance(bot, player) <= ALREADY_WITH_THEM)
			return false; // the player came to it, or it was already there
		log.info("Bot {} joins {} at {} {} {} on map {}, after {}", bot.getName(), player.getName(), Math.round(player.getX()), Math.round(player.getY()),
			Math.round(player.getZ()), player.getWorldId(), watch.how);
		BotInstances.moveBeside(bot, player);
		return true;
	}
}
