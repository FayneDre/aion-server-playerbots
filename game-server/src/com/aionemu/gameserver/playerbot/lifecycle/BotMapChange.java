package com.aionemu.gameserver.playerbot.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.animations.ArrivalAnimation;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.instance.InstanceService;
import com.aionemu.gameserver.world.World;

/**
 * Finishes moving a bot to another map, which the engine leaves half done for a character without a client.
 * <p>
 * A teleport to another map takes the body out of the world, gives it its new position, and then waits for the client to say the map is loaded
 * ({@code CM_LEVEL_READY}): that packet is what spawns the character again. A bot has no client, so nothing ever did, and one sent to another map
 * stayed out of the world for good while its every move was refused as the move of a despawned object. This is the part of {@code CM_LEVEL_READY}
 * that is not packets for the client.
 */
public final class BotMapChange {

	private static final Logger log = LoggerFactory.getLogger(BotMapChange.class);

	private BotMapChange() {
	}

	/**
	 * Spawns the bot where a teleport to another map has left it waiting. Called straight after such a teleport; a teleport on the same map spawns
	 * the body itself, and this then does nothing.
	 *
	 * @return true if the bot was out of the world and has now arrived.
	 */
	public static boolean arrive(Player bot) {
		if (bot.isSpawned())
			return false;
		World.getInstance().spawn(bot);
		bot.getController().onEnterWorld();
		// asked on every map, as the client's packet does: the handler of a map that is not an instance is the general one, which does nothing
		InstanceService.onEnterInstance(bot);
		bot.setPortAnimation(ArrivalAnimation.NONE);
		log.info("Bot {} arrived on map {} (instance {}) at {} {} {}", bot.getName(), bot.getWorldId(), bot.getInstanceId(), Math.round(bot.getX()),
			Math.round(bot.getY()), Math.round(bot.getZ()));
		return true;
	}
}
