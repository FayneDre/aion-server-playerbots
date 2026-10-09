package com.aionemu.gameserver.playerbot.combat.playbook;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * Which playbook belongs to which class. Authored, like {@code BotRole}, for the same reason: no data file says what a class is for.
 */
public final class BotPlaybooks {

	private static final ClassPlaybook TEMPLAR = new TemplarPlaybook();

	private BotPlaybooks() {
	}

	/** @return The class's playbook, or {@link ClassPlaybook#NONE} when it has none yet. Never null. */
	public static ClassPlaybook of(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR -> TEMPLAR;
			default -> ClassPlaybook.NONE;
		};
	}

	public static ClassPlaybook of(Player bot) {
		return of(bot.getPlayerClass());
	}
}
