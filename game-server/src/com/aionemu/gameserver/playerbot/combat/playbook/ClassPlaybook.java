package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.List;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * What one class does in a fight beyond what every class does.
 * <p>
 * The generic order in {@code PlayerBotAI.useBestSkill} reads everything from the skill data, which is right for what a skill <i>is</i> and cannot say
 * what a class is meant to <i>do</i> with it: a templar holds Hand of Healing for a fifth of its health and a gladiator would spend it at half. A
 * playbook is where such a rule lives, written by class and consulted <b>first</b>, so that it can claim a moment of the fight or leave it to the
 * generic order.
 * <p>
 * Stateless, one shared instance per class. A rule that needs to remember something about one bot, such as when it was last stunned, keeps that in
 * the bot's own AI and reads it from there rather than in a field here, where every templar in the world would share it.
 */
public interface ClassPlaybook {

	/** What every class without a playbook of its own gets: no opinion, so the generic order decides alone. */
	ClassPlaybook NONE = (bot, target) -> false;

	/**
	 * Gives the class its chance to act on this moment of the fight.
	 *
	 * @param target What the bot is fighting.
	 * @return true if the bot did something, in which case it is busy and the generic order does not run. false means "nothing to say", never "could
	 *         not": a playbook that returns false must have left the bot exactly as it found it.
	 */
	boolean act(Player bot, Creature target);

	/**
	 * Gives the class its chance to look after itself and its group while it is <b>not</b> fighting: mending, cleansing, resurrecting. Called on every
	 * tick out of a fight, alone and in a group alike. Buffs are not this: see {@link #prepare}.
	 *
	 * @return true if the bot did something, in which case it is busy for this tick. Same contract as {@link #act}: false means "nothing to say" and the bot
	 *         must have been left as it was found.
	 */
	default boolean tend(Player bot) {
		return false;
	}

	/**
	 * Gives the class its chance to put up the buffs it opens a fight with, once a fight is coming: the target chosen, or the group's leader with an
	 * enemy in its sights. Called ahead of the generic upkeep. Kept apart from {@link #tend} because the two were one, and a bot strolling round a
	 * village cast its one minute buffs every minute for nobody.
	 *
	 * @return true if the bot cast something, in which case it is busy for this tick. Same contract as {@link #act}.
	 */
	default boolean prepare(Player bot) {
		return false;
	}

	/**
	 * Gives the class its chance to heal the group, ahead of the generic heal, in a fight and between fights alike.
	 * <p>
	 * A separate hook from {@link #act} and {@link #tend} because a healer's work is neither: it happens on the decision tick whatever the fight is doing, and
	 * the generic heal it replaces casts the strongest heal at one threshold for everybody, which is not how any class rations its mana. A class that claims
	 * its heals through {@link #claims} must answer for them here, or nobody does.
	 *
	 * @return true if the bot cast something, in which case it is busy for this tick. Same contract as {@link #act}.
	 */
	default boolean heal(Player bot) {
		return false;
	}

	/**
	 * Called the moment the bot has left the ground, so that a class with something to do about flight, such as raising its flight speed for the group,
	 * does it as the wings open. Nothing to say by default.
	 */
	default void onTakeOff(Player bot) {
	}

	/**
	 * @param attackers How many things have decided to kill a bot that is not in a group.
	 * @return true if this class breaks off from that many at any health, which the generic rule only does when it is losing. Never in a group.
	 */
	default boolean flees(Player bot, int attackers) {
		return false;
	}

	/** What a class says about being outnumbered, which the generic rule answers by leaving under 40 % health. */
	enum Outnumbered {
		/** Nothing: the generic rule decides. */
		GENERIC,
		/** It holds, whatever the generic rule would do. */
		STAND,
		/** It leaves, at any health. */
		FLEE
	}

	/**
	 * @param attackers Everything that has decided to kill a bot not in a group, two or more of them.
	 * @return What the class does about it. The level gap is not the class's to overrule: something too far above the bot is still left.
	 */
	default Outnumbered whenOutnumbered(Player bot, List<Creature> attackers) {
		return Outnumbered.GENERIC;
	}

	/** Called as the bot breaks off a fight it is running from, for a class with something that helps it get away. */
	default void onRetreat(Player bot) {
	}

	/** Called on every step of a flight, for a class with something to do about how long it can stay up. Must be cheap: it runs five times a second. */
	default void whileFlying(Player bot) {
	}

	/**
	 * @param inTeam Whether the bot is in a group, since a class may want other mantras for its mates than for itself.
	 * @return The skill groups of the mantras this class wants running, best first. Empty when it has no opinion, and then the newest mantra wins as it
	 *         always did. A mantra that is not on the list is tried after the ones that are.
	 */
	default List<String> mantraOrder(boolean inTeam) {
		return List.of();
	}

	/**
	 * @return true if this class decides alone when this bot casts the skill, so the generic order never offers it. Without this the two would both act on
	 *         the same skill by different rules: the generic heal fires at half health on any heal a class has, which for a templar is the half hour
	 *         Hand of Healing that is meant to be kept for a fifth.
	 */
	default boolean claims(Player bot, SkillTemplate template) {
		return false;
	}

	/**
	 * @param advanced Which family of sockets is being filled: the regular ones, or the advanced ones that open from level 45.
	 * @return The skill groups of the stigmas this class wants, in the order it wants them, for those sockets. Empty when it has no opinion, and then the
	 *         fitter ranks by role and by power as it always did. A stone that is not on the list is still worn once the list runs out.
	 */
	default List<String> preferredStigmas(boolean advanced) {
		return List.of();
	}
}
