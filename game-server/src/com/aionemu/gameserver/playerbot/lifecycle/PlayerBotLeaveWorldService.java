package com.aionemu.gameserver.playerbot.lifecycle;

import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.model.animations.ObjectDeleteAnimation;
import com.aionemu.gameserver.model.gameobjects.player.FriendList;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.alliance.PlayerAllianceService;
import com.aionemu.gameserver.model.team.group.PlayerGroupService;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.playbook.BotPlaybooks;
import com.aionemu.gameserver.playerbot.economy.BotStores;
import com.aionemu.gameserver.playerbot.economy.BotTrades;
import com.aionemu.gameserver.playerbot.social.BotInstances;
import com.aionemu.gameserver.playerbot.social.BotRequests;
import com.aionemu.gameserver.playerbot.social.BotTransports;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.world.World;

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
		bot.getCommonData().setOnline(false); // the character is no longer present, and the flag is saved with it
		bot.getFriendList().setStatus(FriendList.Status.OFFLINE, bot.getCommonData());
		// a store left open goes with the body: the engine closes one on a teleport and never on leaving, which a real player's logout never needs
		BotStores.close(bot);
		bot.getAi().onGeneralEvent(AIEventType.DESPAWNED);
		// What the module keeps about this bot's life in the world, in one place: a bot woken later starts a new one. Every static map keyed by a bot
		// is either forgotten here, or cleared by the AI on DESPAWNED just above, or kept on purpose; BotMemoryTest holds the list and fails on a new one.
		BotRequests.forget(bot);
		BotTrades.forget(bot);
		BotSkillManager.forget(bot);
		BotPlaybooks.forget(bot);
		BotTransports.forget(bot);
		BotInstances.forget(bot);
		bot.getController().cancelCurrentSkill(null);
		bot.getEffectController().removeAllEffects();
		bot.getLifeStats().cancelAllTasks();
		// Leave the team, which the engine's own logout does and this did not. A bot that simply vanishes from a group leaves its place filled: the
		// team keeps a member it no longer counts, and the player sitting in that party keeps a name in their window that answers nothing. Note this
		// is not only about a player's own companion — a world-owned bot accepts any invitation it is sent, so any bot can be in a real group.
		PlayerGroupService.onPlayerLogout(bot);
		PlayerAllianceService.onPlayerLogout(bot);
		// nothing else ever saves a bot: PeriodicSaveService only handles legion warehouses, and the shutdown path saves connected players only
		PlayerService.storePlayer(bot);
		// Out of the world without the fade. Left to itself, delete() despawns with FADE_OUT, so every client that can see the bot watches a body
		// dissolve in front of it — and the only thing that decides whether anybody is watching is a test taken a moment earlier, against players who
		// move. The animation is the part that cannot be taken back, so it is the part that is given up: a character that is gone between two frames
		// reads as one that was never looked at. Nothing is skipped by this — removeObject despawns only what is still spawned.
		World.getInstance().despawn(bot, ObjectDeleteAnimation.NONE);
		bot.getController().delete();
	}
}
