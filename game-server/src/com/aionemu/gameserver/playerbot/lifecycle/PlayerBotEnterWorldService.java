package com.aionemu.gameserver.playerbot.lifecycle;

import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.world.World;

/**
 * Brings a bot into the world, mirroring the state building parts of {@link com.aionemu.gameserver.services.player.PlayerEnterWorldService} while
 * skipping everything that talks to a client connection.
 * <p>
 * Reviewed against PlayerEnterWorldService as of commit 558677569. Re-diff it after every upstream merge.
 */
public class PlayerBotEnterWorldService {

	private PlayerBotEnterWorldService() {
	}

	/**
	 * Places the bot next to the given player and spawns it, so real clients in range can see it.
	 */
	public static void enterWorld(Player bot, Player nextTo) {
		applyPassiveSkillEffects(bot);
		bot.setAi(new PlayerBotAI(bot));
		bot.setPosition(World.getInstance().createPosition(nextTo.getWorldId(), nextTo.getX() + 2, nextTo.getY(), nextTo.getZ(), nextTo.getHeading(),
			nextTo.getInstanceId()));
		World.getInstance().storeObject(bot);
		bot.getLifeStats().updateCurrentStats();
		World.getInstance().spawn(bot);
		bot.getController().onEnterWorld();
		// PlayerController never fires AI events, so the bot AI would stay in AIState.CREATED and ignore everything
		bot.getAi().onGeneralEvent(AIEventType.SPAWNED);
	}

	/**
	 * Copy of PlayerEnterWorldService#activatePassiveSkillEffects, which is private. Without it the bot misses all stat bonuses from passive skills.
	 */
	private static void applyPassiveSkillEffects(Player bot) {
		for (PlayerSkillEntry skillEntry : bot.getSkillList().getAllSkills()) {
			SkillTemplate skillTemplate = DataManager.SKILL_DATA.getSkillTemplate(skillEntry.getSkillId());
			if (skillTemplate.isPassive())
				SkillEngine.getInstance().applyEffectDirectly(skillTemplate, skillEntry.getSkillLevel(), bot, bot);
		}
	}
}
