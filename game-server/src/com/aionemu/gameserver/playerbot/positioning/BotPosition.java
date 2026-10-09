package com.aionemu.gameserver.playerbot.positioning;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * Where a class stands in a group fight, after the guide's page on positioning.
 * <p>
 * Authored, like {@code BotRole}, and for the same reason: nothing in the data says where a class belongs. It is not {@code BotRole}, though. A chanter
 * and a bard are both {@code SUPPORT} there and stand in different places here, and a rider holds no aggro duties but stands where a templar does.
 */
public enum BotPosition {

	/** In front of the enemy, on the side away from the rest of the group, so that the enemy turns its back on them. */
	FRONT,
	/** In contact with the enemy, behind it: out of its cleave, and where the skills that ask for the caster's back work. */
	BEHIND,
	/** At a distance, never in melee and never in front. */
	RANGED,
	/** At a distance, and near enough to every member to heal them. */
	HEALER,
	/** No rule: the class stays where it is. The starting classes, which are not in a group doing anything that needs one. */
	NONE;

	public static BotPosition of(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR, RIDER -> FRONT;
			case GLADIATOR, ASSASSIN, CHANTER -> BEHIND;
			case SPIRIT_MASTER, SORCERER, BARD, RANGER, GUNNER -> RANGED;
			case CLERIC -> HEALER;
			default -> NONE;
		};
	}

	public static BotPosition of(Player bot) {
		return of(bot.getPlayerClass());
	}
}
