package com.aionemu.gameserver.playerbot.combat;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.skillengine.change.Change;
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.condition.SkillChargeCondition;
import com.aionemu.gameserver.skillengine.effect.AbstractHealEffect;
import com.aionemu.gameserver.skillengine.effect.DamageEffect;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.effect.ProvokerEffect;
import com.aionemu.gameserver.skillengine.effect.RideRobotEffect;
import com.aionemu.gameserver.skillengine.model.ChargeSkillEntry;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.model.ChargedSkill;
import com.aionemu.gameserver.skillengine.model.HitType;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillSubType;
import com.aionemu.gameserver.playerbot.combat.playbook.BotPlaybooks;
import com.aionemu.gameserver.playerbot.combat.playbook.ClassPlaybook;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.skillengine.properties.Properties;
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
	 * Health under which a group mate is healed. Higher than the bar a bot uses for itself, because it is the only one that can help them: a bot can
	 * sit down and regenerate what it does not heal, while an ally it leaves hurt stays hurt.
	 */
	public static final int HEAL_ALLY_PERCENT = 75;
	/**
	 * Health under which a bot spends a defensive ability it was otherwise saving. Deliberately above the healing threshold: damage prevented is worth
	 * more than damage healed, and a shield raised at the last moment absorbs nothing.
	 */
	public static final int DEFENSIVE_COOLDOWN_PERCENT = 70;
	/** Mana a bot that can heal keeps out of reach of its attacks, so a fight it is losing does not find it unable to pay for a heal. */
	public static final int MANA_RESERVE_PERCENT = 25;
	/** Above this, a healer's mana is more than its own job needs and the surplus may go on damage. */
	public static final int HEALER_SPARE_MANA_PERCENT = 60;
	/**
	 * Longest cooldown a bot will spend at the start of an ordinary fight. Above it the ability is rare enough that a player keeps it for something
	 * that warrants it, and a bot has no way to tell that a given mob does — so it keeps it for the moment it is in trouble instead.
	 */
	private static final long ENGAGEMENT_COOLDOWN_MILLIS = 180_000;
	/**
	 * Shortest hide a bot will treat as a way to arrive unseen rather than as a way out. The data separates the two cleanly: the escapes last two or
	 * three seconds, long enough to break contact and no longer, while the approach skills last from twenty seconds to five minutes.
	 */
	private static final long APPROACH_HIDE_MILLIS = 10_000;
	/**
	 * How the data marks the first link of a chain. Everything else carrying a chain condition continues one, which is the distinction that matters:
	 * a continuation is a window that closes.
	 */
	private static final String FIRST_CHAIN_LINK = "_1TH";
	/**
	 * How far short of the full charge a held skill is let go. Just enough that the engine's own cancellation, scheduled for the same instant,
	 * never wins the race.
	 * <p>
	 * How far inside the top stage the bot lets go, so rounding and thread scheduling cannot drop the release back into the stage below.
	 */
	private static final long CHARGE_RELEASE_MARGIN_MILLIS = 200;
	/** Auras a bot keeps running. The engine ends the oldest past this many (see {@code EffectController}), so going further would only cycle them. */
	private static final int MAX_MANTRAS = 3;

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

	/** The states a templar has an answer to: see {@link #isCrowdControlled}. */
	private static final AbnormalState[] CONTROL_STATES = { AbnormalState.STUN, AbnormalState.STUMBLE, AbnormalState.STAGGER, AbnormalState.SPIN,
		AbnormalState.OPENAERIAL };

	/**
	 * Effects that put ground between a bot and what it is fighting. They come attached to ordinary attacks — a ranger's Parting Shot, a gladiator's
	 * Retreating Slash — so a bot picking skills for their damage alone leaps backwards mid fight and then walks the distance back, over and over.
	 * Kiting is a real tactic, but it is one a bot does not have yet, and until it does these are simply a way of undoing its own chase.
	 */
	private static final EffectType[] RETREAT_EFFECTS = { EffectType.BACKDASH };

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
		return cast(bot, bot, skills(bot, BotSkillManager::isHeal));
	}

	private static boolean isHeal(Player bot, SkillTemplate template) {
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
		if (isSavingMana(bot))
			return false; // upkeep is the most deferrable thing a bot spends on, and the first that should stop when it is poor
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
		return isSelfBuff(template) && isWorthKeepingUp(template) && !coversAnApproach(template) && !isAlreadyUp(bot, template);
	}

	/**
	 * Calls up the servant a class fights alongside.
	 * <p>
	 * A spirit master without its spirit is not a weaker spirit master, it is a cloth caster with no damage: the pet is the class. Nothing picked
	 * these up before because a summon is not a buff, not a heal and not aimed at an enemy, so every rule that chooses a skill looked straight past
	 * them — which is why bots of that class fought bare handed and died to things their level.
	 *
	 * @return true if a servant was called, in which case the bot is busy with the cast.
	 */
	public static boolean trySummonServant(Player bot) {
		if (bot.getSummon() != null) {
			BotServantAI.take(bot);
			return false;
		}
		return cast(bot, bot, skills(bot, BotSkillManager::isSummon));
	}

	private static boolean isSummon(Player bot, SkillTemplate template) {
		// the lasting companion only. The rest of the summon family puts a thing on the ground — a totem, a trap, a gate, a functional npc — and
		// each of those is a tactic with a moment, not something a bot should be calling up because it happens to know how.
		return template.hasAnyEffect(EffectType.SUMMON);
	}

	private static boolean isSelfBuff(SkillTemplate template) {
		return template.getSubType() == SkillSubType.BUFF && template.getProperties().getTargetRelation() != TargetRelationAttribute.ENEMY
			&& !isTransformation(template);
	}

	/**
	 * @return true for a skill whose point is to wear somebody else's body.
	 *         <p>
	 *         Excluded from every buff rule, for the same reason {@link #isSummon} takes only the lasting companion: a polymorph is a costume with a
	 *         moment, not something a bot keeps up because it happens to know how. The data hands rangers four of them at level 10 — White Tiger,
	 *         Krall, Mau — whose cooldowns are shorter than their durations, so the upkeep rule took them for ordinary buffs and a camp of rangers
	 *         spent its life as a menagerie.
	 */
	private static boolean isTransformation(SkillTemplate template) {
		return template.hasAnyEffect(EffectType.SHAPECHANGE);
	}

	/**
	 * Asked by stack group rather than by skill id, because two ranks of the same buff share a group while their ids differ: going by id, a bot would
	 * put its best rank up and overwrite it with a weaker one a tick later, forever.
	 */
	private static boolean isAlreadyUp(Creature target, SkillTemplate template) {
		String stack = template.getStack();
		if (stack != null) {
			if (target.getEffectController().getAbnormalEffect(stack) != null)
				return true;
		} else if (target.getEffectController().hasAbnormalEffect(template.getSkillId())) {
			return true;
		}
		return coversTheSameEffect(target, template);
	}

	/**
	 * @return true when something already on the target carries one of this skill's own effect ids, in the same slot.
	 *         <p>
	 *         Because the stack group is not how the engine decides two buffs are the same thing: {@code EffectController.isConflicting} compares
	 *         effect ids within a target slot, and ends the weaker of two that collide. Where the data gives one effect two names, the bot therefore
	 *         saw a buff that was missing and the engine saw one that was already there — so it cast it, which ended the other, which was then missing
	 *         in its turn. That is a loop with nothing to stop it, and both halves of it are in the live data: a bard's Summer and Autumn are one
	 *         {@code statup} 944261 under two stack groups, and a ranger's White Tiger and Krall are one {@code shapechange} 175 under two more.
	 *         <p>
	 *         Slot by slot, as the engine does it, so a debuff that happens to share an id with a buff is not mistaken for it.
	 */
	private static boolean coversTheSameEffect(Creature target, SkillTemplate template) {
		if (template.getEffects() == null)
			return false;
		List<Effect> active = target.getEffectController().getAbnormalEffects();
		for (EffectTemplate wanted : template.getEffects().getEffects()) {
			if (wanted.getEffectId() == 0)
				continue; // an effect with no id collides with nothing, the engine skips it too
			for (Effect up : active) {
				if (up.getTargetSlot() != template.getTargetSlot())
					continue;
				for (EffectTemplate alreadyUp : up.getEffectTemplates()) {
					if (alreadyUp.getEffectId() == wanted.getEffectId())
						return true;
				}
			}
		}
		return false;
	}

	/**
	 * Heals a group mate.
	 * <p>
	 * The same skills the bot heals itself with, minus the ones that cannot leave their caster. Which of those is which was left to the engine at
	 * first, on the assumption that it would refuse a self only heal aimed at somebody else — it does not. It silently replaces the target with the
	 * caster and reports success, so every class was offering the Bandage Heal that every character knows, healing itself, and counting it as having
	 * looked after the ally. A melee bot beside a hurt player therefore bandaged itself over and over instead of fighting, for as long as the player
	 * stayed hurt.
	 *
	 * @return true if a heal was cast, in which case the bot is busy with it.
	 */
	public static boolean tryHealAlly(Player bot, Player ally) {
		if (ally == null || ally.getLifeStats().isDead() || ally.getLifeStats().getHpPercentage() >= HEAL_ALLY_PERCENT)
			return false;
		return cast(bot, ally, skills(bot, (caster, template) -> isHeal(caster, template) && reachesOthers(template)));
	}

	/**
	 * @return true if this skill can be aimed at somebody other than its caster.
	 *         <p>
	 *         Read from {@code first_target}, which is where the data says so, and not from the target relation: a friendly skill is not thereby a
	 *         skill you may cast on a friend. Over five thousand of them are marked {@code ME}, and the engine's answer to one of those aimed
	 *         elsewhere is to quietly aim it back at the caster (see {@code FirstTargetProperty}) — never a refusal, which is what made this
	 *         invisible.
	 */
	private static boolean reachesOthers(SkillTemplate template) {
		return switch (template.getProperties().getFirstTarget()) {
			case TARGET, TARGETORME, TARGET_MYPARTY_NONVISIBLE -> true;
			case null, default -> false;
		};
	}

	/**
	 * Puts one of the bot's buffs on a group mate who is missing it. Self only buffs are left out by {@link #reachesOthers}, because they do not fail
	 * their own target check: the engine turns them back on the caster and says nothing, so the bot would buff itself and believe it had buffed the
	 * ally, whose buff stays missing for ever.
	 *
	 * @return true if one was cast.
	 */
	public static boolean tryBuffAlly(Player bot, Player ally) {
		if (ally == null || ally.getLifeStats().isDead() || isSavingMana(bot))
			return false;
		return cast(bot, ally, skills(bot, (caster, template) -> isSelfBuff(template) && reachesOthers(template) && isWorthKeepingUp(template)
			&& !coversAnApproach(template) && !isAlreadyUp(ally, template)));
	}

	/**
	 * Casts the best offensive skill the bot can currently use on the target.
	 *
	 * @return true if a skill was cast.
	 */
	public static boolean tryCastSkill(Player bot, Creature target) {
		if (isSavingMana(bot))
			return false; // the auto attack costs nothing, and no fight is lost by finishing it with the weapon
		return cast(bot, target, skills(bot, BotSkillManager::isOffensive).stream().filter(template -> !alreadyAfflicts(target, template)).toList());
	}

	/**
	 * Tells whether a weakening skill would land on a target that already carries it.
	 * <p>
	 * Re-applying one buys nothing and costs the swing that would have gone into damage. Asked by stack group, as everywhere else, so a higher rank
	 * is not laid over a lower one. Only skills whose whole purpose is the debuff are checked: an attack that happens to leave a mark is still worth
	 * casting for its damage.
	 */
	private static boolean alreadyAfflicts(Creature target, SkillTemplate template) {
		if (template.getSubType() != SkillSubType.DEBUFF || template.getStack() == null)
			return false;
		return target.getEffectController().getAbnormalEffect(template.getStack()) != null;
	}

	/**
	 * Turns on one of the bot's mantras, if it is running fewer than it is allowed.
	 * <p>
	 * Mantras are toggles, so the rule that keeps a bot from switching its own toggles off had excluded every one of them, and chanters ran none at
	 * all. They are the only toggles a bot may touch, and only ever to turn one on: casting an active one turns it off again.
	 *
	 * @return true if one was turned on. One per call, like any other buff.
	 */
	public static boolean tryChantMantra(Player bot) {
		if (isSavingMana(bot) || activeMantras(bot) >= MAX_MANTRAS)
			return false;
		return cast(bot, bot, skills(bot, BotSkillManager::isMissingMantra));
	}

	private static boolean isMissingMantra(Player bot, SkillTemplate template) {
		return isMantra(template) && !isAlreadyUp(bot, template);
	}

	private static boolean isMantra(SkillTemplate template) {
		return template.getSubType() == SkillSubType.CHANT && template.isToggle();
	}

	/**
	 * Puts an aethertech in its robot, without which the class cannot fight at all.
	 * <p>
	 * Every skill it knows carries a {@code RideRobotCondition} that asks {@code isInRobotMode()}, and its Embark toggle also grants
	 * {@code ATTACK_RANGE +4000} — four metres. So out of its robot an aethertech can cast nothing and reach nothing, which in game is a bot standing
	 * five metres from the monster killing it, target selected, doing nothing at all. Reported three times before the class was spotted as the common
	 * factor.
	 * <p>
	 * It never boarded because Embark is a toggle, and toggles were allowed to mantras alone — a rule written when mantras were the only ones anybody
	 * had looked at. The rule it was protecting still holds: casting a live toggle turns it off, so this asks first whether the robot is already on.
	 *
	 * @return true if the bot is busy boarding.
	 */
	public static boolean tryBoardRobot(Player bot) {
		if (bot.isInRobotMode())
			return false;
		return cast(bot, bot, skills(bot, (owner, template) -> isRobotToggle(template)));
	}

	/** Recognised by its effect rather than by its name or id, so it holds for every rank and for both factions' copies. */
	private static boolean isRobotToggle(SkillTemplate template) {
		if (!template.isToggle() || template.getEffects() == null)
			return false;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			if (effect instanceof RideRobotEffect)
				return true;
		}
		return false;
	}

	/**
	 * Counts the mantras currently running, by stack group rather than by skill, since the ranks of one mantra share a group and only one of them can
	 * be up. Counted from the bot's own book instead of read off the effect controller, whose aura list is private to the engine.
	 */
	private static int activeMantras(Player bot) {
		Set<String> running = new HashSet<>();
		for (PlayerSkillEntry entry : bot.getSkillList().getAllSkills()) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template != null && isMantra(template) && template.getStack() != null
				&& bot.getEffectController().getAbnormalEffect(template.getStack()) != null)
				running.add(template.getStack());
		}
		return running.size();
	}

	/**
	/**
	 * Tells whether the bot should stop spending mana on damage.
	 * <p>
	 * This used to apply to healers alone, on the reasoning that only a class with something to pay for later has anything to save for, and that for
	 * everyone else mana exists to be spent. True of one fight and false of a career: a bot picks its skills strongest first, so "spend it" means
	 * emptying the bar on the first monster and meeting the next one with nothing. Whole regions of casters were found permanently out of mana.
	 * <p>
	 * So the floor is everyone's now. Below it the bot finishes the fight with its weapon, which costs nothing, and the bar climbs back during the
	 * fight instead of only after it.
	 */
	private static boolean isSavingMana(Player bot) {
		return bot.getLifeStats().getMpPercentage() < MANA_RESERVE_PERCENT;
	}

	/**
	 * Whether a healer has more mana than its own job needs, and may spend the surplus on damage.
	 * <p>
	 * A cleric in a group used to do nothing but heal, for the whole of an instance, whatever its mana said — reported from a run of the Fire Temple
	 * where its damage was "néant". The rule it obeyed is still right at low mana and was simply stated too strongly: a healer's mana belongs to the
	 * people it is keeping alive, and only what is left over belongs to the monster. Well above {@link #MANA_RESERVE_PERCENT}, because the surplus is
	 * what this spends and a healer that dips into its reserve has stopped being a healer.
	 */
	public static boolean hasManaToSpare(Player bot) {
		return bot.getLifeStats().getMpPercentage() >= HEALER_SPARE_MANA_PERCENT;
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
		if (!isHeldBackSelfBuff(bot, template) || stance(template) != Stance.DEFENSIVE || coversAnApproach(template))
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
		Stance proc = procStance(template);
		return proc != Stance.NEITHER ? proc : statStance(template);
	}

	/**
	 * Reads a proc's side off the blow that sets it off.
	 * <p>
	 * A proc arms something that fires later, so its own effects say nothing about which way it points: what decides is whether it watches for blows
	 * the bearer lands or blows it takes. The engine asks exactly this question when it installs the observer, and the answer is mirrored here rather
	 * than guessed at, so the two cannot drift apart.
	 */
	private static Stance procStance(SkillTemplate template) {
		if (template.getEffects() == null)
			return Stance.NEITHER;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			if (effect instanceof ProvokerEffect)
				return effect.getHitType() == HitType.NMLATK || effect.getHitType() == HitType.BACKATK ? Stance.OFFENSIVE : Stance.DEFENSIVE;
		}
		return Stance.NEITHER;
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
		return template.getProperties().getTargetRelation() == TargetRelationAttribute.ENEMY && !template.hasAnyEffect(RETREAT_EFFECTS);
	}

	/**
	 * Closes the distance with a leap instead of walking it.
	 * <p>
	 * This is the one opening that is genuinely the class's own: a gladiator springs, an assassin appears behind its target, while a caster simply
	 * starts casting. Worth more than damage while still closing, because the seconds it saves are seconds the bot spends fighting rather than
	 * jogging — and because the reactive steering that covers those last metres is where bots get stuck.
	 *
	 * @return true if the bot leapt.
	 */
	public static boolean tryGapCloser(Player bot, Creature target) {
		return cast(bot, target, skills(bot, BotSkillManager::isGapCloser));
	}

	private static boolean isGapCloser(Player bot, SkillTemplate template) {
		return isOffensive(bot, template) && template.hasAnyEffect(EffectType.DASH, EffectType.MOVEBEHIND);
	}

	/**
	 * Hides the bot so it can walk in unseen.
	 * <p>
	 * An assassin that strolls up to its target in plain sight is throwing its class away. The engine drops the hide by itself on the first blow, so
	 * there is nothing to undo afterwards.
	 *
	 * @return true if the bot went unseen.
	 */
	public static boolean tryApproachUnseen(Player bot) {
		return cast(bot, bot, skills(bot, BotSkillManager::isApproachHide));
	}

	private static boolean isApproachHide(Player bot, SkillTemplate template) {
		return isSelfBuff(template) && coversAnApproach(template) && !isAlreadyUp(bot, template);
	}

	/**
	 * Tells a hide meant for arriving somewhere from one meant for getting out.
	 * <p>
	 * A long hide is neither upkeep nor a defensive button, whatever the other rules would make of it: kept up for its own sake it would leave bots
	 * invisible for ever, wandering a world they were spawned to populate, and raised mid fight it does nothing a bot knows how to use.
	 */
	private static boolean coversAnApproach(SkillTemplate template) {
		return template.hasAnyEffect(EffectType.HIDE) && buffDurationMillis(template) >= APPROACH_HIDE_MILLIS;
	}

	/**
	 * Tries each candidate in turn and stops at the first one that actually goes off.
	 * <p>
	 * Nothing is started while a cast is already running. A real player's client will not let them, and the engine has no guard of its own: a second
	 * cast merely overwrites the reference to the first, whose {@code endCast} is already scheduled and still fires. Worse, the skill's cooldown is
	 * set when the cast ends, not when it starts, so nothing in between turns the second one down either. This is the single choke point every bot
	 * cast goes through, which is where the rule belongs.
	 */
	private static boolean cast(Player bot, Creature target, List<SkillTemplate> candidates) {
		if (bot.isCasting())
			return false;
		for (SkillTemplate template : candidates) {
			Skill skill = SkillEngine.getInstance().getSkillFor(bot, template, target);
			// useNoAnimationSkill validates mp, cooldown, range and target itself, and skips the client hit time checks a bot cannot satisfy
			if (skill != null && skill.useNoAnimationSkill()) {
				if (template.isCharge())
					releaseWhenCharged(bot, skill);
				return true;
			}
		}
		return false;
	}

	/**
	 * Lets go of a skill the bot has been charging, which is the half of one nobody else will send.
	 * <p>
	 * A charge skill is the one kind the engine deliberately leaves unfinished: starting it schedules {@code cancelCurrentSkillCast} at the full
	 * charge time and nothing else, because the blow itself comes from the player letting the key go — {@code CM_USE_CHARGE_SKILL}, which a bot has
	 * no client to send. So a bard would start a three stage harp skill, stand there for its whole duration playing the animation, and land nothing
	 * at all, over and over, which is exactly what was reported.
	 * <p>
	 * Released as soon as the top stage is entered, which is <b>not</b> the end of the charge data. The last stage's time is how long the skill may be
	 * held at full charge before the engine cancels it, not time needed to get there: a three stage skill reads 1600, 1600, 5000, so the top stage
	 * begins at 3.2 s and waiting out all 8.2 s buys nothing. The whole total was being waited out, so a bot stood channelling for five seconds after
	 * its blow was ready, every cast, on all 163 charge skills in the game -- reported on an aethertech, but a bard or a gunner was doing the same.
	 * <p>
	 * The charge data is read for the stage times rather than guessed at, and the cast speed ratio the skill already worked out is applied to them,
	 * because that is what {@code useChargeSkill} compares the elapsed time against on the other side. Its own minimum is honoured too, since
	 * releasing before it logs the bot as a speed hacker.
	 */
	private static void releaseWhenCharged(Player bot, Skill skill) {
		SkillChargeCondition condition = skill.getSkillTemplate().getSkillChargeCondition();
		ChargeSkillEntry charged = condition == null ? null : DataManager.SKILL_CHARGE_DATA.getChargedSkillEntry(condition.getValue());
		if (charged == null || charged.getSkills().isEmpty())
			return;
		List<ChargedSkill> stages = charged.getSkills();
		int toTopStage = 0;
		for (int stage = 0; stage < stages.size() - 1; stage++)
			toTopStage += stages.get(stage).getTime();
		long delay = (long) ((Math.max(toTopStage, charged.getMinTime()) + CHARGE_RELEASE_MARGIN_MILLIS)
			* skill.getCastSpeedForAnimationBoostAndChargeSkills());
		BotScheduler.getInstance().schedule(() -> {
			// still the same cast: the bot may have died, been interrupted or moved on, and releasing a skill it is no longer holding would reach
			// into whatever it is doing now
			if (bot.getCastingSkill() == skill)
				bot.getController().useChargeSkill(skill, System.currentTimeMillis() - skill.getCastStartTime());
		}, Math.max(0, delay));
	}

	/**
	 * @return How far the bot's heals reach a group mate, in metres, or 0 when it has none that can be aimed at somebody else. The healer's whole place in a
	 *         fight is within this of everyone.
	 */
	public static float healReach(Player bot) {
		float reach = 0;
		for (var entry : bot.getSkillList().getAllSkills()) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template != null && !template.isPassive() && template.getProperties() != null && isHeal(bot, template) && reachesOthers(template))
				reach = Math.max(reach, template.getProperties().getFirstTargetRange());
		}
		return reach;
	}

	/**
	 * @return How far the bot can reach an enemy from, in metres: the longest of its weapon and of the skills it could aim at one. A skill that adds the
	 *         weapon's range to its own counts both, as the engine does. For where a ranged class stands: a sorcerer's orb reaches 2.5 m and its spells 25.
	 */
	public static float reach(Player bot) {
		float weapon = bot.getGameStats().getAttackRange().getCurrent() / 1000f;
		float reach = weapon;
		for (var entry : bot.getSkillList().getAllSkills()) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template == null || template.isPassive() || template.getProperties() == null || !isOffensive(bot, template))
				continue;
			Properties properties = template.getProperties();
			if (properties.getFirstTarget() != FirstTargetAttribute.TARGET)
				continue;
			reach = Math.max(reach, properties.getFirstTargetRange() + (properties.isAddWeaponRange() ? weapon : 0));
		}
		return reach;
	}

	/**
	 * @return true if the bot knows a skill of that group and it is off cooldown. For a {@link ClassPlaybook}, which claims skills the generic order never
	 *         offers and so has to ask for them by name. The group, not the id: every level of a skill has its own id and the group is what they share.
	 */
	public static boolean isReady(Player bot, String group) {
		return !readyOfGroup(bot, group).isEmpty();
	}

	/** Casts the bot's skill of that group on the target, which is the bot itself for a skill that is not aimed. @return true if it went off. */
	public static boolean tryCastGroup(Player bot, Creature target, String group) {
		return cast(bot, target, readyOfGroup(bot, group));
	}

	/**
	 * @return true if the creature is stunned, knocked back or down, spun or held in the air. These five are what Remove Shock asks for as its target
	 * status and what Unwavering Devotion raises the resistance to, so they are the ones a templar has an answer to; other ways of losing control,
	 * sleep and fear among them, are not.
	 */
	public static boolean isCrowdControlled(Creature creature) {
		for (AbnormalState state : CONTROL_STATES) {
			if (creature.getEffectController().isAbnormalSet(state))
				return true;
		}
		return false;
	}

	/** @return true if the bot has used a skill of that chain category and nothing since, which is what the next link of the chain asks for. */
	public static boolean isChainOpen(Player bot, String category) {
		return bot.getChainSkills().getCurrentChainCount(category) > 0;
	}

	/** @return true if an effect of that stack group is on the bot, which for a buff is the same name as its skill group. */
	public static boolean isUp(Player bot, String group) {
		return bot.getEffectController().getAbnormalEffect(group) != null;
	}

	private static List<SkillTemplate> readyOfGroup(Player bot, String group) {
		return bot.getSkillList().getAllSkills().stream().map(entry -> DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId()))
			.filter(template -> template != null && group.equals(template.getGroup()) && !bot.isSkillDisabled(template)).toList();
	}

	/** @return The bot's usable skills of one kind, in the order it should try them. */
	private static List<SkillTemplate> skills(Player bot, SkillFilter filter) {
		return bot.getSkillList().getAllSkills().stream().map(entry -> DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId())).filter(template -> {
			if (template == null || template.isPassive() || template.getProperties() == null)
				return false;
			// what the class decides on alone is not the generic order's to cast, whatever else it would have made of it
			if (BotPlaybooks.of(bot).claims(bot, template))
				return false;
			// a toggle cast a second time turns itself off, so the only rules allowed near one are those that check first that it is off: the mantra
			// rule, and the one that puts an aethertech in its robot
			if (template.isToggle() && !isMantra(template) && !isRobotToggle(template))
				return false;
			if (bot.isSkillDisabled(template)) // cooldowns are keyed by cooldown id, not skill id, which this handles
				return false;
			return filter.matches(bot, template);
		}).sorted(CHAIN_FIRST_THEN_STRONGEST).toList();
	}

	/**
	 * A chain continuation before anything else, then the highest id.
	 * <p>
	 * A continuation is a window that closes on its own, and it closes the moment the bot casts something else: the engine resets the chain whenever
	 * a non-chain skill passes its checks, and merely trying a chain's opening link resets a chain already in progress. So the order is not a
	 * preference but a correctness rule — asking in the wrong order destroyed the very thing being asked about.
	 * <p>
	 * Beyond that the highest id still wins. Skills are learned in level order, so it reads as "the strongest one available", which is a fair
	 * approximation and not yet a rotation.
	 */
	private static final Comparator<SkillTemplate> CHAIN_FIRST_THEN_STRONGEST = Comparator
		.comparing((SkillTemplate template) -> !continuesAChain(template))
		.thenComparing(Comparator.comparingInt(BotSkillManager::statedPower).reversed())
		.thenComparing(SkillTemplate::getSkillId, Comparator.reverseOrder());

	/**
	 * What the data says a skill does: damage as the percentage of the bot's attack the client shows, healing in points. 0 for everything else, which
	 * leaves those ordered by id as before.
	 * <p>
	 * This replaces the highest id as the way to say "the strongest one available", and the two disagree badly. A gladiator led with Body Smash (322)
	 * while Sure Strike (2519) went unused, and a cleric with Enfeebling Burst (255) while Call Lightning (3190) sat in its book — the highest id is
	 * the most recently learned skill, which is not at all the same thing as the best one.
	 * <p>
	 * Every damaging effect in the game states its value as a percentage, so they compare directly. Heals over time are excluded by the class
	 * hierarchy rather than by a rule, and that is the right answer: their value is a tick, not a total, so it was never comparable to the rest.
	 */
	public static int statedPower(SkillTemplate template) {
		if (template.getEffects() == null)
			return 0;
		int best = 0;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			if (effect instanceof DamageEffect || effect instanceof AbstractHealEffect)
				best = Math.max(best, effect.getValue() + effect.getDelta() * template.getLvl());
		}
		return best;
	}

	private static boolean continuesAChain(SkillTemplate template) {
		ChainCondition chain = template.getChainCondition();
		return chain != null && chain.getCategory() != null && !chain.getCategory().contains(FIRST_CHAIN_LINK);
	}

	private interface SkillFilter {
		boolean matches(Player bot, SkillTemplate template);
	}
}
