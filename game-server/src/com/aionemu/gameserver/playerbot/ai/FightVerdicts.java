package com.aionemu.gameserver.playerbot.ai;

import java.util.List;

import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.combat.playbook.ClassPlaybook;
import com.aionemu.gameserver.playerbot.positioning.BotPosition;

/**
 * The judgements of a fight that need nothing but what is known of it: whether a fight has stalled, whether to close in, whether the fight is lost,
 * what to defend against, and whether the bot is fit to start one. {@link PlayerBotAI} reads the world and acts; these say what its readings mean.
 */
final class FightVerdicts {

	/**
	 * How long a fight may go without a tick of its attack loop before it is considered lost.
	 * <p>
	 * The decision tick does nothing while the bot is attacking, so a lost attack task left a bot standing beside a mob for ever: seen in game after
	 * an hour, three bots of nineteen frozen that way and the count rising, with no exception in the log and every pool thread idle. Whatever loses
	 * the task, this notices and lets the bot decide again — and says so, so the cause stays visible instead of being papered over.
	 */
	static final long STALLED_FIGHT_MILLIS = 15000;
	/**
	 * How long a fight may run without the bot landing anything before it is given up.
	 * <p>
	 * A different failure from the one above, and invisible to it: the attack loop is alive and ticking, and every tick does nothing. The loop only
	 * swings through {@code BotAttackManager.autoAttack}, which requires line of sight, so a target standing in reach behind a rock, a root or the
	 * lip of a slope is attacked for ever at no cost to itself -- while it answers, which is how it was seen: a bot at four metres, facing the thing
	 * killing it, never swinging. {@link #STALLED_FIGHT_MILLIS} cannot catch this because it measures the gap between ticks, and the ticks are fine.
	 * <p>
	 * Longer than {@code BotChase.MAX_CHASE_MILLIS}, because a bot crossing open ground to something far away has landed nothing yet either, and that
	 * is not a stuck fight but a fight that has not started.
	 */
	static final long FRUITLESS_FIGHT_MILLIS = 20000;
	/**
	 * Health below which a bot that is outnumbered breaks off and leaves.
	 * <p>
	 * Being outmatched on level needs no threshold — that fight is lost at full health and the bot runs at once — but being set upon by two things
	 * its own size is a fight it may well win, and leaving at the first scratch would mean never finishing one.
	 */
	static final int RETREAT_HP_PERCENT = 40;
	/** Health below which the bot waits to regenerate instead of looking for a fight. */
	static final int MIN_ENGAGE_HP_PERCENT = 90;
	/**
	 * Mana below which the bot sits down rather than starting another fight.
	 * <p>
	 * Nothing used to look at mana at all, so a bot at full health and an empty bar fought on for ever with its auto attack alone — and never sat, so
	 * it never recovered, because sitting is what pays the eightfold regeneration rate. Seen across whole regions: casters permanently out of mana,
	 * which also made every measurement of their damage a measurement of a character with its kit switched off.
	 * <p>
	 * Above {@code BotSkillManager.MANA_RESERVE_PERCENT} on purpose. The reserve is the point where the bot stops spending on skills; if the bot were
	 * allowed to stand up at or below it, it would rise from its rest already unable to cast and sit straight back down.
	 */
	static final int MIN_ENGAGE_MP_PERCENT = 35;

	/** What the watchdog makes of a fight that is on. */
	enum Stall {
		/** Nothing wrong with it. */
		KEEP,
		/**
		 * No swing for a while, and the next one still waiting its turn: a busy server, not a lost loop, and the two want opposite things done about
		 * them. Every occurrence measured so far has been the second kind and not the first: seventeen bots at one and the same second, thirty three
		 * seconds after a start, while the pool worked through a hundred wakes of some twenty database round trips each -- and in the run before
		 * that, fifteen inside one second, likewise just after a start. Letting those fights go threw the fight away and blamed the loop for the
		 * schedule, which is a cause invented rather than found.
		 */
		QUEUED,
		/** The attack loop has stopped: the fight is let go. */
		LOOP_STOPPED,
		/**
		 * The loop runs and lands nothing: the fight is let go, and the target left alone afterwards, like one that could not be reached. Something
		 * about where it stands makes it unfightable from here, and the bot has no way to tell what; without that, the next decision tick picks the
		 * same target back out of the same list and the fight that landed nothing starts again -- which is what standing still in front of a monster
		 * looks like from outside.
		 */
		FRUITLESS
	}

	private FightVerdicts() {
	}

	/**
	 * @param swingQueued true when the next swing is scheduled and has not run yet.
	 * @return What to do about a fight whose loop last ticked at {@code lastAttackTick} and last landed something at {@code lastBlow}. The loop's
	 *         gap is read first: a fight whose loop is lost has landed nothing either, and the loop is what is wrong with it.
	 */
	static Stall stall(long now, long lastAttackTick, long lastBlow, boolean swingQueued) {
		if (now - lastAttackTick >= STALLED_FIGHT_MILLIS)
			return swingQueued ? Stall.QUEUED : Stall.LOOP_STOPPED;
		return now - lastBlow < FRUITLESS_FIGHT_MILLIS ? Stall.KEEP : Stall.FRUITLESS;
	}

	/**
	 * Whether a melee class walks in to its target instead of casting at it from where it stands.
	 * <p>
	 * Anything in the book that reaches was cast before closing, which suits a caster and not a chanter: alone, it stood back and threw the few spells
	 * it has that reach, and seldom swung the staff its damage is in, as a tester reported. One cast from afar is kept, the pull, and so is casting at
	 * a target that runs or that cannot be walked to; everything else is done in reach.
	 *
	 * @param targetRunning true when the target is feared or walking back to its spawn: what reaches is what catches it.
	 */
	static boolean closesIn(boolean pulledFromAfar, BotPosition position, boolean targetRunning) {
		return pulledFromAfar && (position == BotPosition.FRONT || position == BotPosition.BEHIND) && !targetRunning;
	}

	/**
	 * Whether a fight is lost, judged two ways on purpose (see {@code PlayerBotAI.retreatIfLosing} for when it is asked):
	 * <ul>
	 * <li><b>Outmatched.</b> Something more than {@code MAX_LEVEL_GAP} above the bot is hitting it. That is the same figure which already decides
	 * which fights a bot may <i>start</i>, and the hole this closes is that the figure was never applied to the fights that start themselves — a
	 * level 12 bot walking within ten metres of a level 19 monster was attacked, and fought to the death with no rule anywhere to say otherwise. A
	 * fight not worth starting is not worth finishing, so this one goes at once, at whatever health.
	 * <li><b>Outnumbered.</b> More than one thing hating the bot at the same time. Winnable often enough to be worth trying, so it is left until the
	 * health is going, unless the class says otherwise: a gladiator holds against two normal monsters and leaves an elite at any health.
	 * </ul>
	 * Some classes are also told by their guide to leave at a number of attackers whatever their health ({@code flees}).
	 *
	 * @param attackerLevels The levels of what is attacking the bot, the ignored ones left out.
	 * @param call What the class says about being outnumbered; {@code GENERIC} when it was not asked, against a single attacker.
	 */
	static boolean retreats(int botLevel, int hpPercent, List<Integer> attackerLevels, ClassPlaybook.Outnumbered call, boolean flees) {
		boolean outmatched = attackerLevels.stream().anyMatch(level -> level > botLevel + BotTargetSelector.MAX_LEVEL_GAP);
		boolean outnumbered = call == ClassPlaybook.Outnumbered.FLEE
			|| call == ClassPlaybook.Outnumbered.GENERIC && attackerLevels.size() > 1 && hpPercent < RETREAT_HP_PERCENT;
		return outmatched || outnumbered || flees;
	}

	/**
	 * Whether a creature counts as attacking the bot, for its defence.
	 * <p>
	 * Hating it is what counts, not aiming at it: a creature that has decided to kill the bot is not necessarily swinging at it this instant. Scenery
	 * is never answered (a training dummy holds a grudge like anything else once hit, and a bot answering one stood swinging at furniture), nor what
	 * the bot has just broken off from, which still hates it. A group's healer answers only what is aimed at it, by the rule that lets such a fight go
	 * ({@link #healerHasNoSurplus}): read two ways, the add a cleric had rooted was hated enough to start a fight every tick and not aimed at it enough
	 * to keep one, so the cleric started and dropped it every second for 18 s and cast no heal while the templar died (20 October, 13:11).
	 */
	static boolean defendsAgainst(boolean dead, boolean scenery, boolean hating, boolean ignored, boolean groupHealer, boolean aimedAtBot) {
		return !dead && !scenery && hating && !ignored && (!groupHealer || aimedAtBot);
	}

	/**
	 * @return true if a group's healer, fighting something that is not after it, has no mana to spare. Mana is what a healer is for: below the
	 *         surplus it goes back to healing. A monster that is hitting the healer itself is another matter, and is fought whatever the mana.
	 */
	static boolean healerHasNoSurplus(boolean groupHealer, boolean manaToSpare, boolean targetAimsAtBot) {
		return groupHealer && !manaToSpare && !targetAimsAtBot;
	}

	/**
	 * Keeps the bot from starting a fight it is in no shape for — in particular right after resurrecting at 25% hp, next to whatever killed it.
	 * Only picking a fight is gated: it always defends itself, whatever its health. Both thresholds are reached at the figure itself.
	 */
	static boolean healthyEnoughToFight(int hpPercent, int mpPercent, boolean wantsToRest) {
		return hpPercent >= MIN_ENGAGE_HP_PERCENT && mpPercent >= MIN_ENGAGE_MP_PERCENT && !wantsToRest;
	}
}
