package com.aionemu.gameserver.playerbot.combat;

import java.util.Comparator;
import java.util.List;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.skillengine.change.Change;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillSubType;
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
	/**
	 * Health under which a bot spends a defensive ability it was otherwise saving. Deliberately above the healing threshold: damage prevented is worth
	 * more than damage healed, and a shield raised at the last moment absorbs nothing.
	 */
	public static final int DEFENSIVE_COOLDOWN_PERCENT = 70;
	/** Mana a bot that can heal keeps out of reach of its attacks, so a fight it is losing does not find it unable to pay for a heal. */
	public static final int MANA_RESERVE_PERCENT = 25;
	/**
	 * Longest cooldown a bot will spend at the start of an ordinary fight. Above it the ability is rare enough that a player keeps it for something
	 * that warrants it, and a bot has no way to tell that a given mob does — so it keeps it for the moment it is in trouble instead.
	 */
	private static final long ENGAGEMENT_COOLDOWN_MILLIS = 180_000;

	/**
	 * Effects whose whole purpose is to keep their bearer alive. Read from the data rather than from a curated list of skill ids, so a skill nobody
	 * thought about is still classified.
	 * <p>
	 * Plain heals are left out on purpose: {@link #tryHealSelf} already owns them and fires them on a health threshold of its own, which is a better
	 * trigger for an ability whose only job is to restore health than "something is going wrong".
	 */
	private static final EffectType[] PROTECTIVE_EFFECTS = { EffectType.SHIELD, EffectType.MPSHIELD, EffectType.PROTECT, EffectType.REFLECTOR,
		EffectType.SANCTUARY, EffectType.LIMITEDREDUCEDAMAGE, EffectType.MAGICCOUNTERATK, EffectType.EVADE, EffectType.ALWAYSDODGE,
		EffectType.ALWAYSBLOCK, EffectType.ALWAYSPARRY, EffectType.ALWAYSRESIST, EffectType.INVULNERABLEWING, EffectType.HIDE, EffectType.ESCAPE,
		EffectType.REBIRTH, EffectType.DISPELDEBUFF, EffectType.DISPELDEBUFFPHYSICAL, EffectType.DISPELDEBUFFMENTAL };

	/** Effects that only make a bot hit harder, and are therefore worth nothing until it has something to hit. */
	private static final EffectType[] AGGRESSIVE_EFFECTS = { EffectType.BOOSTSPELLATTACK, EffectType.ONETIMEBOOSTSKILLATTACK,
		EffectType.ONETIMEBOOSTSKILLCRITICAL, EffectType.DEATHBLOW, EffectType.BOOSTSKILLCASTINGTIME, EffectType.BOOSTSKILLCOST };

	/** What an ability is for. One that is for neither is left alone: a bot that cannot tell what a skill does should not gamble with it. */
	private enum Stance {
		DEFENSIVE,
		OFFENSIVE,
		NEITHER
	}

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
	 * Puts up one of the bot's own buffs, if any is missing.
	 * <p>
	 * Same blind spot healing had: a buff is cast on its caster, so the enemy-only filter hid every one of them and no bot had ever buffed itself.
	 *
	 * @return true if one was cast. One per call, a second apart, which is also how a player does it.
	 */
	public static boolean tryBuffSelf(Player bot) {
		return cast(bot, bot, skills(bot, BotSkillManager::isMissingSelfBuff));
	}

	/**
	 * Tells a buff a bot should simply keep up from one it should be saving.
	 * <p>
	 * The data draws the line by itself: a buff that lasts at least as long as its own cooldown can be kept up for ever, which is what makes it
	 * upkeep. One whose cooldown outlasts it cannot — it is a window the player opens on purpose, an evasion or a burst, and a bot that burns it on
	 * an empty field has thrown it away for the fight where it mattered. No list of skills to curate, which is the point.
	 */
	private static boolean isWorthKeepingUp(SkillTemplate template) {
		int cooldownMillis = template.getCooldown() * 100; // the data counts cooldowns in tenths of a second
		if (cooldownMillis <= 0)
			return true; // nothing holds it back, so there is nothing to save it for
		return buffDurationMillis(template) >= cooldownMillis;
	}

	/** @return How long the buff lasts, read the way the engine reads it: the first effect with a duration decides, longer ones are ignored. */
	private static long buffDurationMillis(SkillTemplate template) {
		if (template.getEffects() == null)
			return 0;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			long duration = effect.getDuration2() + (long) effect.getDuration1() * template.getLvl();
			if (duration > 0)
				return duration;
		}
		return 0;
	}

	private static boolean isMissingSelfBuff(Player bot, SkillTemplate template) {
		return isSelfBuff(template) && isWorthKeepingUp(template) && !isAlreadyUp(bot, template);
	}

	private static boolean isSelfBuff(SkillTemplate template) {
		return template.getSubType() == SkillSubType.BUFF && template.getProperties().getTargetRelation() != TargetRelationAttribute.ENEMY;
	}

	/**
	 * Asked by stack group rather than by skill id, because two ranks of the same buff share a group while their ids differ: going by id, a bot would
	 * put its best rank up and overwrite it with a weaker one a tick later, forever.
	 */
	private static boolean isAlreadyUp(Player bot, SkillTemplate template) {
		String stack = template.getStack();
		if (stack != null)
			return bot.getEffectController().getAbnormalEffect(stack) != null;
		return bot.getEffectController().hasAbnormalEffect(template.getSkillId());
	}

	/**
	 * Casts the best offensive skill the bot can currently use on the target.
	 *
	 * @return true if a skill was cast.
	 */
	public static boolean tryCastSkill(Player bot, Creature target) {
		if (isSavingManaToHeal(bot))
			return false; // the auto attack costs nothing, and the fight is not lost until the heal cannot be paid for
		return cast(bot, target, skills(bot, BotSkillManager::isOffensive));
	}

	/**
	 * Tells whether the bot should stop spending mana on damage.
	 * <p>
	 * Only a class that heals itself has anything to save mana for; for everyone else mana exists to be spent. Asked of the whole skill list rather
	 * than of the usable one, because a heal on cooldown is still a heal the bot will want to pay for in a few seconds.
	 */
	private static boolean isSavingManaToHeal(Player bot) {
		if (bot.getLifeStats().getMpPercentage() >= MANA_RESERVE_PERCENT)
			return false;
		for (PlayerSkillEntry entry : bot.getSkillList().getAllSkills()) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template != null && template.getProperties() != null && !template.isPassive() && isSelfHeal(bot, template))
				return true;
		}
		return false;
	}

	/**
	 * Spends a defensive ability the bot has been holding back, if it is hurt enough to need one.
	 * <p>
	 * These are the abilities {@link #isWorthKeepingUp} deliberately refuses to raise on an empty field — an evasion, a shield, a debuff cleanse.
	 * Saving them was only half of that rule; this is the other half, the moment they were being saved for.
	 *
	 * @return true if one was cast, in which case the bot is busy with it.
	 */
	public static boolean tryDefensiveCooldown(Player bot) {
		if (bot.getLifeStats().getHpPercentage() >= DEFENSIVE_COOLDOWN_PERCENT || bot.getLifeStats().isDead())
			return false;
		return cast(bot, bot, skills(bot, BotSkillManager::isDefensiveCooldown));
	}

	/**
	 * Spends an offensive ability the bot has been holding back, as the opening move of a fight.
	 * <p>
	 * A burst that lasts thirty seconds belongs at the start of a fight or nowhere: used halfway through, most of it is spent on a mob already dying.
	 *
	 * @return true if one was cast.
	 */
	public static boolean tryOpeningCooldown(Player bot) {
		return cast(bot, bot, skills(bot, BotSkillManager::isOpeningCooldown));
	}

	/**
	 * The rarer the ability, the deeper the trouble it is kept for. A shield that comes back in a minute belongs in any fight that is going badly; one
	 * that comes back in half an hour is a last resort, and spending it on a fight the bot was going to win anyway means not having it for the one it
	 * was not.
	 */
	private static boolean isDefensiveCooldown(Player bot, SkillTemplate template) {
		if (!isHeldBackSelfBuff(bot, template) || stance(template) != Stance.DEFENSIVE)
			return false;
		if ((long) template.getCooldown() * 100 <= ENGAGEMENT_COOLDOWN_MILLIS)
			return true;
		return bot.getLifeStats().getHpPercentage() < HEAL_IN_COMBAT_PERCENT;
	}

	private static boolean isOpeningCooldown(Player bot, SkillTemplate template) {
		if (!isHeldBackSelfBuff(bot, template) || stance(template) != Stance.OFFENSIVE)
			return false;
		return (long) template.getCooldown() * 100 <= ENGAGEMENT_COOLDOWN_MILLIS;
	}

	/** @return true for a self buff the upkeep rule holds back, and that is not already up. */
	private static boolean isHeldBackSelfBuff(Player bot, SkillTemplate template) {
		return isSelfBuff(template) && !isWorthKeepingUp(template) && !isAlreadyUp(bot, template);
	}

	/**
	 * Works out what an ability is for from its effects, and failing that from the stats it changes.
	 * <p>
	 * Effects come first because they are unambiguous — a shield is a shield. The stat pass is the fallback for the large family of plain stat buffs,
	 * where only the stat says whether the bot comes out harder to kill or quicker to kill things. Defence wins a tie: a buff that raises attack and
	 * defence together is still something to reach for when losing, whereas spending it on an opening throws its defensive half away.
	 */
	private static Stance stance(SkillTemplate template) {
		if (template.hasAnyEffect(PROTECTIVE_EFFECTS))
			return Stance.DEFENSIVE;
		if (template.hasAnyEffect(AGGRESSIVE_EFFECTS))
			return Stance.OFFENSIVE;
		return statStance(template);
	}

	private static Stance statStance(SkillTemplate template) {
		if (template.getEffects() == null)
			return Stance.NEITHER;
		Stance stance = Stance.NEITHER;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			if (effect.getChange() == null)
				continue;
			for (Change change : effect.getChange()) {
				if (change.getStat() == null)
					continue;
				// Only the bonuses are read. A penalty is the price of a skill, not its purpose: Berserking cuts defence and accuracy to buy eighty
				// percent attack, and reading its penalties made it look like a defensive skill — the exact opposite of what it is.
				if (change.getValue() + change.getDelta() * template.getLvl() <= 0)
					continue;
				Stance found = stanceOf(change.getStat());
				if (found == Stance.DEFENSIVE)
					return Stance.DEFENSIVE;
				if (found == Stance.OFFENSIVE)
					stance = Stance.OFFENSIVE;
			}
		}
		return stance;
	}

	/**
	 * Reads a stat's side off its own name.
	 * <p>
	 * There are over a hundred and fifty stats and the data names them consistently, so the naming classifies them better than a list that would have
	 * to be revisited every time one is added. Penetration is tested first because it reads as resistance while being the opposite of it.
	 */
	private static Stance stanceOf(StatEnum stat) {
		String name = stat.name();
		if (name.contains("PENETRATION"))
			return Stance.OFFENSIVE;
		if (name.contains("RESIST") || name.contains("DEFEN") || name.contains("EVASION") || name.contains("BLOCK") || name.contains("PARRY")
			|| name.contains("DAMAGE_REDUCE") || name.equals("MAXHP") || name.equals("REGEN_HP"))
			return Stance.DEFENSIVE;
		if (name.contains("ATTACK") || name.contains("CRITICAL") || name.contains("ACCURACY") || name.contains("DAMAGES")
			|| name.equals("BOOST_MAGICAL_SKILL") || name.equals("BOOST_SPELL_ATTACK"))
			return Stance.OFFENSIVE;
		return Stance.NEITHER;
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
