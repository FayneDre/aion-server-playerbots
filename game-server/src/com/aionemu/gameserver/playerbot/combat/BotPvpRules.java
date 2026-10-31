package com.aionemu.gameserver.playerbot.combat;

/**
 * The fairness rules of a bot picking a fight with a player, apart from the world so that they can be tested alone.
 */
public final class BotPvpRules {

	/** A lone player is attacked by at most this many bots at once. */
	public static final int MAX_BOTS_ON_A_LONE_PLAYER = 2;

	/** A bot against a player leaves the fight under this share of its life. */
	public static final int FLEE_HP_PERCENT = 30;
	/** With two enemy players on it, a bot already hurt to this share leaves. */
	public static final int OUTNUMBERED_HP_PERCENT = 50;
	/** Enemy players on a bot at which it leaves whatever its life. */
	public static final int CROWD = 3;
	/** A player at or under this share of life is dying already, and is not picked as a target: finishing one off is not a fight. */
	public static final int DYING_HP_PERCENT = 10;

	private BotPvpRules() {
	}

	/**
	 * @param enemyPlayers how many enemy players are fighting the bot now (attacking it, or the one it attacks)
	 * @return true if the bot is to break off: under {@value #FLEE_HP_PERCENT} % of its life against any of them, hurt to
	 *         {@value #OUTNUMBERED_HP_PERCENT} % against two, or against {@value #CROWD} or more at any health
	 */
	public static boolean shouldWithdraw(int hpPercent, int enemyPlayers) {
		if (enemyPlayers <= 0)
			return false;
		return hpPercent < FLEE_HP_PERCENT || enemyPlayers >= CROWD || enemyPlayers == 2 && hpPercent < OUTNUMBERED_HP_PERCENT;
	}

	/** @return true if a player with that share of life is too far gone to be picked as a target. */
	public static boolean isDying(int hpPercent) {
		return hpPercent <= DYING_HP_PERCENT;
	}

	/**
	 * @param skillIsForMonstersOnly the skill's target species is NPC
	 * @return true if the skill is not to be tried on that target: a monster only skill on a player. The engine would refuse it, but only after
	 *         the other tests of the cast, and a refused candidate that comes first hides the usable ones behind it.
	 */
	public static boolean isWrongSpecies(boolean skillIsForMonstersOnly, boolean targetIsPlayer) {
		return skillIsForMonstersOnly && targetIsPlayer;
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
