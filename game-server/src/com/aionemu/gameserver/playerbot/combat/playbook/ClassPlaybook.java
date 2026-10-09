package com.aionemu.gameserver.playerbot.combat.playbook;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;

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
}
