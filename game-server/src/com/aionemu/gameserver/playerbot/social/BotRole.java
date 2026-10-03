package com.aionemu.gameserver.playerbot.social;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * What a class is <i>for</i> when it stands in a group.
 * <p>
 * The one authored table in this module, and deliberately so. Everything else about a bot's fighting is read from the skill data — which skills are
 * upkeep, which are kept in hand, which open a chain, which way a buff points — because the data knows and a list would rot. This is the exception
 * the design always expected: the data says what a skill does and never says what a <b>class</b> is meant to do with it. A templar and a gladiator
 * both hold a sword and a shield and both know a taunt; nothing in any file says that one of them is supposed to be hit and the other is not.
 * <p>
 * Fifteen lines rather than a derivation, and the alternative was tried on paper: a tank reads as "high defence", which is also a gladiator, a
 * chanter in plate and every templar-shaped thing in the game. The inference is weaker than the sentence.
 */
public enum BotRole {

	/** Holds what the group is fighting, and is meant to be the one taking the blows. */
	TANK,
	/** Keeps the group alive, and has no business in melee range. */
	HEALER,
	/** Heals when somebody is actually hurt, and fights the rest of the time. */
	SUPPORT,
	/** Damage, which is also every class nobody has a better word for. */
	DAMAGE;

	/**
	 * @return What this class does in a group.
	 *         <p>
	 *         {@code SUPPORT} is not padding. A chanter states 197 physical damage against 17 magical and a songweaver is built the same way: they
	 *         are classes that hit and happen to carry heals. Filed under {@code HEALER} they would spend a fight watching health bars that are
	 *         already full, which is the behaviour this whole exercise exists to stop.
	 *         <p>
	 *         A gladiator is {@code DAMAGE} and not a second tank. It can hold a mob, but putting two classes on the taunts means two bots fighting
	 *         each other for the same aggro, and the group has one tank.
	 */
	public static BotRole of(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR -> TANK;
			case CLERIC -> HEALER;
			case CHANTER, BARD -> SUPPORT;
			// Everything else, the six starting classes included. A character under ten is not in a group doing anything that needs a role, and
			// naming them here would only invite the question of what a WARRIOR is for before it has decided.
			default -> DAMAGE;
		};
	}

	public static BotRole of(Player bot) {
		return of(bot.getPlayerClass());
	}
}
