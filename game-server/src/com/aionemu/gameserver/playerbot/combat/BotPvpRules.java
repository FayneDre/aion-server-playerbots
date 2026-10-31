package com.aionemu.gameserver.playerbot.combat;

/**
 * The fairness rules of a bot picking a fight with a player, apart from the world so that they can be tested alone.
 */
public final class BotPvpRules {

	/** A lone player is attacked by at most this many bots at once. */
	public static final int MAX_BOTS_ON_A_LONE_PLAYER = 2;

	private BotPvpRules() {
	}

	/**
	 * @return true if a player of that level is within what a bot picks for itself: never above its level + {@code maxLevelGap}, never beneath its
	 *         level - {@code minLevelGap}. The same band as for monsters.
	 */
	public static boolean isInLevelBand(int botLevel, int targetLevel, int maxLevelGap, int minLevelGap) {
		return targetLevel <= botLevel + maxLevelGap && targetLevel >= botLevel - minLevelGap;
	}

	/**
	 * @param loneTarget true if the player is in no team
	 * @param botsAlreadyOnTarget how many other bots are attacking that player now
	 * @return true if one more bot may join the attack
	 */
	public static boolean mayJoinAttack(boolean loneTarget, int botsAlreadyOnTarget) {
		return !loneTarget || botsAlreadyOnTarget < MAX_BOTS_ON_A_LONE_PLAYER;
	}
}
