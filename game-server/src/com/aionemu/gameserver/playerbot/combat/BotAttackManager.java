package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Player-typed counterpart of {@link com.aionemu.gameserver.ai.manager.SimpleAttackManager}, which only accepts an NpcAI and can therefore not be
 * reused for bots.
 */
public class BotAttackManager {

	private BotAttackManager() {
	}

	/**
	 * Performs a single auto attack if the bot currently can.
	 *
	 * @return false if the fight is over and the caller should stop attacking.
	 */
	public static boolean attackTick(Player bot, Creature target) {
		if (!bot.isSpawned() || bot.isDead() || !bot.canAttack())
			return false;
		if (target == null || target.isDead() || !bot.canSee(target))
			return false;

		if (isInAttackRange(bot, target) && GeoService.getInstance().canSee(bot, target)) {
			bot.getPosition().setH(PositionUtil.getHeadingTowards(bot, target));
			// PlayerController applies its own range, line of sight and attack speed checks, so bots obey the same rules as real players
			bot.getController().attackTarget(target, 0, true);
		}
		return true;
	}

	private static boolean isInAttackRange(Player bot, Creature target) {
		return PositionUtil.isInAttackRange(bot, target, bot.getGameStats().getAttackRange().getCurrent() / 1000f);
	}
}
