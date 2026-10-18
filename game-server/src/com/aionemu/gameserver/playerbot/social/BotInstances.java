package com.aionemu.gameserver.playerbot.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.lifecycle.BotMapChange;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotLanding;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.world.WorldMapInstance;

/**
 * Takes a grouped bot into the instance its player has entered, and out of it once the player has left.
 * <p>
 * A player goes in through a portal, and the engine then expects each member to walk to the same portal and use it: {@code PortalService} finds the
 * instance registered to the group and puts the member in it. A bot cannot be asked to do that (it sees no portal and opens no dialog), and it does
 * not have to be: the engine's own answer, the instance the player is standing in, is already in hand. So the bot is registered with that instance and
 * teleported beside the player, the way {@link BotSquads} brings its members to their leader.
 * <p>
 * The way out is the same in reverse. A bot left in an instance its player has gone from is useless there and lost to the world outside, so it
 * joins the player wherever they went; one left with no group at all is put at the instance's exit, as the engine does with a player who logs in
 * inside an instance that is no longer theirs.
 */
public final class BotInstances {

	private static final Logger log = LoggerFactory.getLogger(BotInstances.class);

	private BotInstances() {
	}

	/**
	 * @return true if the bot has just been moved to another map or instance, in which case whatever it was about to do was decided somewhere it no
	 *         longer is.
	 */
	public static boolean followThePlayer(Player bot) {
		if (!bot.isSpawned() || bot.isDead())
			return false; // a dead bot comes back first, and is taken along on the tick after
		Player player = playerToFollow(bot);
		if (player == null) {
			if (bot.isInInstance() && bot.getCurrentTeam() == null) {
				leave(bot);
				return true;
			}
			return false;
		}
		// still loading the map it has just been sent to: its position is already the new one, but it is not in the world there yet
		if (!player.isSpawned() || player.getWorldId() == bot.getWorldId() && player.getInstanceId() == bot.getInstanceId())
			return false;
		if (player.isInInstance())
			return enter(bot, player);
		if (bot.isInInstance()) {
			join(bot, player);
			return true;
		}
		return false; // both outside, on different maps: travel, which bots do not do yet
	}

	/**
	 * @return The real player leading the bot's group, or null. A squad is led by a bot and never goes into an instance of its own accord, so it is
	 *         left out, and so is a group whose leader has left it (see {@link BotGroupManager#leaderToFollow}).
	 */
	private static Player playerToFollow(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		Player leader = team.getLeaderObject();
		if (leader == null || leader.equals(bot) || leader.isBot() || !team.hasMember(leader.getObjectId()))
			return null;
		return leader;
	}

	private static boolean enter(Player bot, Player player) {
		WorldMapInstance instance = player.getPosition().getWorldMapInstance();
		// a solo instance, or a group's that is already full: the bot waits outside, as a member would be turned away at the portal
		if (instance.isFull() && !instance.isRegistered(bot.getObjectId())) {
			log.debug("Bot {} cannot follow {} into map {}: the instance is full", bot.getName(), player.getName(), instance.getMapId());
			return false;
		}
		// what PortalService does for every member it lets in, and what lets the bot be found again as a member of this instance
		instance.register(bot.getObjectId());
		moveBeside(bot, player);
		log.info("Bot {} follows {} into map {} (instance {})", bot.getName(), player.getName(), instance.getMapId(), instance.getInstanceId());
		return true;
	}

	private static void join(Player bot, Player player) {
		int leftMap = bot.getWorldId();
		moveBeside(bot, player);
		log.info("Bot {} leaves map {} behind {}, who has gone to map {}", bot.getName(), leftMap, player.getName(), player.getWorldId());
	}

	private static void leave(Player bot) {
		int leftMap = bot.getWorldId();
		TeleportService.moveToInstanceExit(bot, leftMap, bot.getRace());
		BotMapChange.arrive(bot);
		log.info("Bot {} has no group in map {} and is put at its exit, map {}", bot.getName(), leftMap, bot.getWorldId());
	}

	/** Teleports the bot to its own place beside the player, on floor the player's ground joins, as {@link BotSquads} does for its members. */
	private static void moveBeside(Player bot, Player player) {
		Vector3f spot = BotGroupManager.formationSpot(bot, player);
		Vector3f ground = NavmeshService.getInstance().groundNear(player.getWorldId(), spot.getX(), spot.getY(), spot.getZ());
		if (ground != null) {
			Vector3f joined = BotLanding.toWalkableGround(player.getWorldId(), ground, new Vector3f(player.getX(), player.getY(), player.getZ()),
				bot.getObjectId(), false);
			spot = joined != null ? joined : ground;
		} else {
			spot = new Vector3f(player.getX(), player.getY(), player.getZ()); // no mesh to say where the floor is: the player's feet are on it
		}
		TeleportService.teleportTo(bot, player.getWorldId(), player.getInstanceId(), spot.getX(), spot.getY(), spot.getZ());
		BotMapChange.arrive(bot);
	}
}
