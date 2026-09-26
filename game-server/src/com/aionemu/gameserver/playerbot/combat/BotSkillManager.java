package com.aionemu.gameserver.playerbot.combat;

import java.util.Comparator;
import java.util.List;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;

/**
 * Picks and casts skills for a bot. Player-typed counterpart of {@link com.aionemu.gameserver.ai.manager.SkillAttackManager}, which is built around
 * NpcSkillList and an NpcAI.
 */
public class BotSkillManager {

	/** Health below which a bot that can heal itself does so rather than trading blows and hoping. */
	public static final int HEAL_IN_COMBAT_PERCENT = 50;
	/** Health below which healing is worth its mana once the fight is over. Above it, sitting down costs nothing and gets there anyway. */
	public static final int HEAL_AFTER_COMBAT_PERCENT = 70;
	/** Mana under which resting beats casting: it restores health and mana alike, and the next fight needs the mana either way. */
	public static final int HEAL_MIN_MP_PERCENT = 30;

	private BotSkillManager() {
	}

	/**
	 * Heals the bot if it is hurt enough to bother and knows how.
	 * <p>
	 * Until this existed, a bot's class barely showed: only skills whose target is an enemy were ever considered, so a priest fought like a warrior
	 * with worse armour, and recovered the way everyone else did — by sitting down.
	 *
	 * @param belowPercent Health under which healing is worth a cast. Lower in a fight, where a cast costs a swing, than out of one.
	 * @return true if a heal was cast, in which case the bot is busy with it.
	 */
	public static boolean tryHealSelf(Player bot, int belowPercent) {
		if (bot.getLifeStats().getHpPercentage() >= belowPercent || bot.getLifeStats().isDead())
			return false;
		return cast(bot, bot, skills(bot, BotSkillManager::isSelfHeal));
	}

	private static boolean isSelfHeal(Player bot, SkillTemplate template) {
		// FRIEND covers self and allies alike; what matters is that it is not aimed at an enemy
		if (template.getProperties().getTargetRelation() == TargetRelationAttribute.ENEMY)
			return false;
		return template.hasAnyEffect(EffectType.HEAL, EffectType.HEALINSTANT);
	}

	/**
	 * Casts the best offensive skill the bot can currently use on the target.
	 *
	 * @return true if a skill was cast.
	 */
	public static boolean tryCastSkill(Player bot, Creature target) {
		return cast(bot, target, skills(bot, BotSkillManager::isOffensive));
	}

	private static boolean isOffensive(Player bot, SkillTemplate template) {
		return template.getProperties().getTargetRelation() == TargetRelationAttribute.ENEMY;
	}

	/** Tries each candidate in turn and stops at the first one that actually goes off. */
	private static boolean cast(Player bot, Creature target, List<PlayerSkillEntry> candidates) {
		for (PlayerSkillEntry entry : candidates) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			Skill skill = SkillEngine.getInstance().getSkillFor(bot, template, target);
			// useNoAnimationSkill validates mp, cooldown, range and target itself, and skips the client hit time checks a bot cannot satisfy
			if (skill != null && skill.useNoAnimationSkill())
				return true;
		}
		return false;
	}

	/** @return The bot's usable skills of one kind, strongest first. */
	private static List<PlayerSkillEntry> skills(Player bot, SkillFilter filter) {
		return bot.getSkillList().getAllSkills().stream().filter(entry -> {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template == null || template.isPassive() || template.isToggle() || template.getProperties() == null)
				return false;
			if (bot.isSkillDisabled(template)) // cooldowns are keyed by cooldown id, not skill id, which this handles
				return false;
			return filter.matches(bot, template);
		})
			// skills are learned in level order, so the highest id is usually the strongest one available
			.sorted(Comparator.comparingInt(PlayerSkillEntry::getSkillId).reversed()).toList();
	}

	private interface SkillFilter {
		boolean matches(Player bot, SkillTemplate template);
	}
}
