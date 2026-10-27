package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.playerbot.combat.playbook.BotPlaybooks;
import com.aionemu.gameserver.playerbot.movement.BotFlight;

/**
 * What a bot does for itself in the air, the part of a flight that is fighting knowledge. {@link BotFlight} used to call these itself until
 * 27 October 2026; it asks through {@link BotFlight.Crew} now, so that movement depends on nothing above it.
 */
public final class BotFlightCrew implements BotFlight.Crew {

	@Override
	public void onTakeOff(Player bot, boolean goingSomewhere) {
		BotPlaybooks.of(bot).onTakeOff(bot);
		BotSkillManager.tryFlightBuff(bot); // the classes with no playbook, whose flight buffs nothing else casts
		if (goingSomewhere)
			BotPotionManager.tryScroll(bot, StatEnum.FLY_SPEED);
	}

	@Override
	public void whileFlying(Player bot) {
		BotPlaybooks.of(bot).whileFlying(bot);
	}

	@Override
	public boolean drinkSerum(Player bot, int belowPercent) {
		return BotPotionManager.tryFlightPotion(bot, belowPercent);
	}
}
