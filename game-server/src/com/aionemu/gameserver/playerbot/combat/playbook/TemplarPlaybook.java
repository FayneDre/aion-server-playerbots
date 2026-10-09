package com.aionemu.gameserver.playerbot.combat.playbook;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * How a templar plays, after the guide's "Templier 101". Everything it will do is in {@code docs/templar-plan.md}, milestone by milestone; for now it
 * has nothing to say, so a templar plays exactly as it did before this class existed.
 */
final class TemplarPlaybook implements ClassPlaybook {

	@Override
	public boolean act(Player bot, Creature target) {
		return false;
	}
}
