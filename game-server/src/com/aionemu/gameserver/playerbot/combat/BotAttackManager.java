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
	 * Tells whether the fight is over, which is not the same question as whether the bot can swing right now.
	 * <p>
	 * {@code Creature.canAttack()} must not be used here: it is false while casting, resting or under a stun, all of which are passing states. Using
	 * it made the bot abandon every fight the moment it cast a spell, then re-engage the same mob when it got hit back.
	 *
	 * @return false if the fight is over and the caller should stop attacking.
	 */
	public static boolean canKeepFighting(Player bot, Creature target) {
		if (!bot.isSpawned() || bot.isDead())
			return false;
		if (target == null || target.isDead() || !bot.canSee(target))
			return false;
		// A player stops being a legitimate target without dying or leaving: a duel ends, a PvP zone is left. Asked of the engine rather than
		// worked out here, since it is the same question it answers for a real player's attacks, duels included. Npcs are left out on purpose: a
		// mob's standing does not change mid fight, and one that never was aggressive would be dropped the moment the bot swung at it.
		return !(target instanceof Player) || bot.isEnemy(target);
	}

	/**
	 * Performs a single auto attack if the target is reachable. Closing the distance is the caller's job.
	 */
	public static void autoAttack(Player bot, Creature target) {
		if (isInAttackRange(bot, target) && GeoService.getInstance().canSee(bot, target)) {
			bot.getPosition().setH(PositionUtil.getHeadingTowards(bot, target));
			// PlayerController applies its own range, line of sight and attack speed checks, so bots obey the same rules as real players
			bot.getController().attackTarget(target, 0, true);
		}
	}

	public static boolean isInAttackRange(Player bot, Creature target) {
		return PositionUtil.isInAttackRange(bot, target, bot.getGameStats().getAttackRange().getCurrent() / 1000f);
	}
}
