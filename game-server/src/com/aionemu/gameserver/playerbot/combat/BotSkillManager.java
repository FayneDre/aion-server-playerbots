package com.aionemu.gameserver.playerbot.combat;

import java.util.Comparator;
import java.util.List;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;

/**
 * Picks and casts an offensive skill for a bot. Player-typed counterpart of {@link com.aionemu.gameserver.ai.manager.SkillAttackManager}, which is
 * built around NpcSkillList and an NpcAI.
 */
public class BotSkillManager {

	private BotSkillManager() {
	}

	/**
	 * Casts the best offensive skill the bot can currently use on the target.
	 *
	 * @return true if a skill was cast.
	 */
	public static boolean tryCastSkill(Player bot, Creature target) {
		for (PlayerSkillEntry entry : offensiveSkills(bot)) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			Skill skill = SkillEngine.getInstance().getSkillFor(bot, template, target);
			// useNoAnimationSkill validates mp, cooldown, range and target itself, and skips the client hit time checks a bot cannot satisfy
			if (skill != null && skill.useNoAnimationSkill())
				return true;
		}
		return false;
	}

	private static List<PlayerSkillEntry> offensiveSkills(Player bot) {
		return bot.getSkillList().getAllSkills().stream().filter(entry -> isOffensiveAndReady(bot, entry.getSkillId()))
			// skills are learned in level order, so the highest id is usually the strongest one available
			.sorted(Comparator.comparingInt(PlayerSkillEntry::getSkillId).reversed()).toList();
	}

	private static boolean isOffensiveAndReady(Player bot, int skillId) {
		SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(skillId);
		if (template == null || template.isPassive() || template.isToggle() || template.getProperties() == null)
			return false;
		if (bot.isSkillDisabled(template))
			return false;
		return template.getProperties().getTargetRelation() == TargetRelationAttribute.ENEMY;
	}
}
