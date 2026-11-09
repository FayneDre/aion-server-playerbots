package com.aionemu.gameserver.playerbot.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which a bot looks for the one thing to do with this moment of a fight, most urgent first.
 * <p>
 * The order is the whole of the bot's combat judgement, so it lives in one place rather than being restated wherever a skill might be cast. It reads
 * as a set of priorities: survive, then keep the advantage the class was given, then deal damage. A cast costs a swing either way, which is why
 * staying alive comes first and why nothing here is tried twice in the same moment. {@code PlayerBotAI.useBestSkill} tries each step in turn and
 * stops at the first that acts; the engine says whether a skill goes off (mana, cooldown, range), so a step is an attempt, not a promise.
 */
final class SkillOrder {

	/** One attempt of the order. */
	enum Step {
		/** The class gets first refusal, its fight and then its heal; what it declines falls through to the steps below. */
		PLAYBOOK,
		/**
		 * A healer heals before anything else, and that is the whole of its priority: every other thing it does, the damage it is allowed included, is
		 * what it does when nobody needs mending. Itself first, since a dead healer heals nobody.
		 */
		HEAL_SELF_FIRST,
		/** Then whoever in its group is in the most danger, a healer's own. */
		HEAL_ALLY_FIRST,
		/** Hiding on the way in, once a fight and only while there is still ground to cover: on top of a target it buys the bot nothing. */
		APPROACH,
		/**
		 * The opening burst, once a fight, before the first blow: a burst spent on a mob already dying is a burst thrown away. Self buffs need no range,
		 * so this works while still closing in.
		 */
		OPENING,
		/** The defensive ability, before the heal: cheaper in mana, and it stops damage instead of repairing it, which only works ahead. */
		DEFENSIVE,
		HEAL_SELF,
		/** The flask is what a class with no heal of its own has instead, and what a healer reaches for when the heal is on cooldown. */
		POTIONS,
		/** A group mate's life outranks the bot's damage, but not the bot's own. */
		HEAL_ALLY,
		/** A leap covers ground the bot would otherwise walk, and those last metres on foot are where bots get stuck. */
		GAP_CLOSER,
		/** Damage, with whatever the book holds. */
		ATTACK
	}

	private SkillOrder() {
	}

	/**
	 * @param closing true when the target is out of weapon reach.
	 * @param targetAlmostDead true when the target is close enough to death that finishing it beats anything else. A target this close dies to the
	 *          next blow or two, and every second spent mending instead is a second it spends hitting back: a bot at low health healing in front of a mob
	 *          with a sliver left heals, gets hit, heals again, and dies to something it could have killed twice over. So the bot's own defence, heal and
	 *          flasks wait, and so does a non-healer's heal of a mate; a healer's heals do not, since that rule is about the bot's own survival and says
	 *          nothing about the tank: a monster with a sliver of health left is no reason to let the tank die under it, which is what a healer did every
	 *          time the group's target got low.
	 * @return The steps to try this moment, in order.
	 */
	static List<Step> steps(boolean healer, boolean closing, boolean approachSpent, boolean openingSpent, boolean targetAlmostDead) {
		List<Step> steps = new ArrayList<>(Step.values().length);
		steps.add(Step.PLAYBOOK);
		if (healer) {
			steps.add(Step.HEAL_SELF_FIRST);
			steps.add(Step.HEAL_ALLY_FIRST);
		}
		if (closing && !approachSpent)
			steps.add(Step.APPROACH);
		if (!openingSpent)
			steps.add(Step.OPENING);
		if (!targetAlmostDead) {
			steps.add(Step.DEFENSIVE);
			steps.add(Step.HEAL_SELF);
			steps.add(Step.POTIONS);
			if (!healer)
				steps.add(Step.HEAL_ALLY);
		}
		if (closing)
			steps.add(Step.GAP_CLOSER);
		steps.add(Step.ATTACK);
		return steps;
	}
}
