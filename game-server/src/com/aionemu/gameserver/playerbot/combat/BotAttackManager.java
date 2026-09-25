package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.utils.PacketSendUtility;
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
	 * Draws the bot's weapon. A real client sends this emotion itself when entering combat (see CM_EMOTION); without it the bot deals damage but
	 * plays no attack animation, since it never leaves its neutral stance.
	 */
	public static void enterAttackMode(Player bot, Creature target) {
		if (!bot.isInState(CreatureState.WEAPON_EQUIPPED)) {
			bot.setState(CreatureState.WEAPON_EQUIPPED);
			PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.ATTACKMODE_IN_STANDING, 0, target.getObjectId()));
		}
	}

	public static void leaveAttackMode(Player bot) {
		if (bot.isInState(CreatureState.WEAPON_EQUIPPED)) {
			bot.unsetState(CreatureState.WEAPON_EQUIPPED);
			PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.NEUTRALMODE_IN_STANDING, 0, 0));
		}
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
