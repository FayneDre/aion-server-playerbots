package com.aionemu.gameserver.playerbot.combat.playbook;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * Which playbook belongs to which class. Authored, like {@code BotRole}, for the same reason: no data file says what a class is for.
 */
public final class BotPlaybooks {

	private static final ClassPlaybook TEMPLAR = new TemplarPlaybook();
	private static final ClassPlaybook CHANTER = new ChanterPlaybook();
	private static final ClassPlaybook RANGER = new RangerPlaybook();
	private static final ClassPlaybook CLERIC = new ClericPlaybook();
	private static final ClassPlaybook GLADIATOR = new GladiatorPlaybook();
	private static final ClassPlaybook SORCERER = new SorcererPlaybook();

	private BotPlaybooks() {
	}

	/** @return The class's playbook, or {@link ClassPlaybook#NONE} when it has none yet. Never null. */
	public static ClassPlaybook of(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR -> TEMPLAR;
			case CHANTER -> CHANTER;
			case RANGER -> RANGER;
			case CLERIC -> CLERIC;
			case GLADIATOR -> GLADIATOR;
			case SORCERER -> SORCERER;
			default -> ClassPlaybook.NONE;
		};
	}

	public static ClassPlaybook of(Player bot) {
		return of(bot.getPlayerClass());
	}

	/** Forgets what the bot's class kept about it, as the bot leaves the world. */
	public static void forget(Player bot) {
		of(bot).forget(bot);
	}

	/** Has a sorcerer bot open a gate to the capital at its feet, for {@code //bot gate}. @return The line to answer with. */
	public static String openGate(Player bot) {
		return SorcererPlaybook.openGate(bot);
	}
}
