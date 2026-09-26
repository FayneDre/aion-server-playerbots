package com.aionemu.gameserver.playerbot.lifecycle;

import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.player.PlayerService;

/**
 * Removes a bot from the world, mirroring the parts of {@link com.aionemu.gameserver.services.player.PlayerLeaveWorldService} that apply to a
 * connectionless player. Nothing here may touch the client connection.
 * <p>
 * Reviewed against PlayerLeaveWorldService as of commit 558677569. Re-diff it after every upstream merge.
 */
public class PlayerBotLeaveWorldService {

	private PlayerBotLeaveWorldService() {
	}

	public static void leaveWorld(Player bot) {
		bot.getAi().onGeneralEvent(AIEventType.DESPAWNED);
		bot.getController().cancelCurrentSkill(null);
		bot.getEffectController().removeAllEffects();
		bot.getLifeStats().cancelAllTasks();
		// nothing else ever saves a bot: PeriodicSaveService only handles legion warehouses, and the shutdown path saves connected players only
		PlayerService.storePlayer(bot);
		bot.getController().delete();
	}
}
