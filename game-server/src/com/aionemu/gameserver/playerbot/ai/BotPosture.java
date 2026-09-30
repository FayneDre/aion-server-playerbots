package com.aionemu.gameserver.playerbot.ai;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotRestManager;

/**
 * How the bot is holding itself, and the time the client needs to draw a change of it: sitting, standing, weapon out, weapon away.
 * <p>
 * Nobody has to send that timing for a real player, which is why it is easy to miss that somebody must. Their client refuses input until the
 * animation it is playing has finished, so the pauses an onlooker sees between sitting, standing, drawing and setting off are produced by the acting
 * client and never travel. A bot has no acting client, so the server produces them — and every transition therefore goes through this one door,
 * rather than each caller remembering to reserve the time. Two of them had already forgotten: drawing the weapon and walking off arrived in the same
 * instant, which is what showed as a bot sliding to its feet, and sheathing before sitting down claimed no time at all while its own comment said it
 * did.
 * <p>
 * The lengths are empirical. Animations live in the client and are exposed nowhere server side, so they are tuned by watching — but what was wrong
 * was never their value, it was a pair with no delay between it at all, and no value of a delay that does not exist is the right one.
 */
class BotPosture {

	/** Getting to its feet. */
	private static final long STAND_UP_MILLIS = 1000;
	/** Drawing or putting away the weapon, which blends worse with what comes next than walking does. */
	private static final long DRAW_WEAPON_MILLIS = 1200;
	/** Sitting down. Shorter than standing up: a body dropping to the ground reads as finished sooner than one climbing off it. */
	private static final long SIT_DOWN_MILLIS = 800;

	private final Player bot;
	/** When the client is expected to be done drawing the last change, so nothing new is sent on top of it. */
	private volatile long animatingUntil;

	BotPosture(Player bot) {
		this.bot = bot;
	}

	/** @return true while the client is still expected to be playing the bot's last change of posture. */
	boolean isAnimating() {
		return System.currentTimeMillis() < animatingUntil;
	}

	/**
	 * @return true if the bot was actually resting and is now getting up, in which case the caller has to leave it the tick.
	 *         <p>
	 *         Mandatory before any action: {@code Creature.canAttack()} is false while resting, and a seated bot walking looks like it is gliding
	 *         across the ground.
	 */
	boolean standUp() {
		if (!BotRestManager.standUp(bot))
			return false;
		hold(STAND_UP_MILLIS);
		return true;
	}

	/** Sits the bot down to recover, which multiplies its health regeneration eightfold and is therefore not cosmetic. */
	void sitDown() {
		BotRestManager.sitDown(bot);
		hold(SIT_DOWN_MILLIS);
	}

	/** @return true if the weapon was actually drawn just now. Idempotent otherwise: this is called on every attack tick. */
	boolean drawWeapon(Creature target) {
		if (!BotAttackManager.enterAttackMode(bot, target))
			return false;
		hold(DRAW_WEAPON_MILLIS);
		return true;
	}

	/** @return true if the weapon was actually put away just now. */
	boolean sheathe() {
		if (!BotAttackManager.leaveAttackMode(bot))
			return false;
		hold(DRAW_WEAPON_MILLIS);
		return true;
	}

	/**
	 * Claims the next stretch of time for a change that was just started.
	 * <p>
	 * Measured from the end of whatever is already playing rather than from now, because these come in chains — get up, draw, walk in — and a claim
	 * that started from the present would let the second animation land on top of the first, which is the whole of what this exists to prevent.
	 */
	private void hold(long millis) {
		animatingUntil = Math.max(animatingUntil, System.currentTimeMillis()) + millis;
	}
}
