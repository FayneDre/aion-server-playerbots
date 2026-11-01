package com.aionemu.gameserver.playerbot.movement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * Sitting down to recover between fights, which a real player does through CM_EMOTION.
 * <p>
 * This is not cosmetic: {@code PlayerGameStats.getHpRegenRate()} multiplies the base rate by 8 while resting, so a standing bot takes minutes to
 * heal what a seated one heals in seconds.
 */
public class BotRestManager {

	private static final Logger log = LoggerFactory.getLogger(BotRestManager.class);

	/** How long the client takes to draw a bot getting to its feet: nothing that moves it is sent before. */
	public static final long STAND_UP_MILLIS = 1000;

	private BotRestManager() {
	}

	public static void sitDown(Player bot) {
		if (bot.isInState(CreatureState.RESTING) || bot.isDead() || !bot.isSpawned())
			return;
		foldStrayWings(bot);
		bot.setState(CreatureState.RESTING);
		PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.SIT));
	}

	/**
	 * Folds the wings of a bot that is about to sit while the engine still holds it as flying or gliding, which no flight of ours accounts for.
	 * <p>
	 * {@code Creature.isFlying()} counts a flying body that is resting as not flying, so a bot sat down in that state looks grounded to every check in
	 * this module and shows its wings to every client: a gladiator was seen resting at the Alsig obelisk, wings out, after a death and a revival. How
	 * the state was left set is not known, hence the log line.
	 */
	private static void foldStrayWings(Player bot) {
		boolean marked = bot.isInState(CreatureState.FLYING) || bot.isInState(CreatureState.GLIDING);
		if (!marked || BotFlight.isInTheAir(bot) || bot.getMoveController() instanceof BotMoveController move && (move.isGliding() || move.isRiding()))
			return;
		log.info("Bot {} sits with its wings out (state {}), and folds them", bot.getName(), bot.getState());
		bot.getFlyController().endFly(true);
	}

	/**
	 * Gets the bot back on its feet. Mandatory before any action: {@code Creature.canAttack()} is false while resting, and a seated bot walking
	 * looks like it is gliding across the ground.
	 *
	 * @return true if the bot was actually resting, in which case callers must leave it time to play the animation before moving it.
	 */
	public static boolean standUp(Player bot) {
		if (!bot.isInState(CreatureState.RESTING))
			return false;
		bot.unsetState(CreatureState.RESTING);
		PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.STAND));
		return true;
	}
}
