package com.aionemu.gameserver.playerbot.combat;

/**
 * How readily a bot starts a fight with a player it meets in a PvP zone. Drawn once from the bot's object id, so it never changes and needs no
 * memory: human players differ the same way, and a bot that attacked on sight would empty the zone of everyone else.
 */
public enum BotTemperament {

	/** Attacks an enemy in range: about 1 bot in 4. */
	FIGHTER,
	/** Attacks only an enemy that is weakened or of a lower level: about 1 bot in 4. */
	OPPORTUNIST,
	/** Never starts a fight, only defends itself: about 1 bot in 2. */
	PEACEFUL;

	/** An opportunist counts an enemy as weakened at or below this share of its life. */
	public static final int WEAKENED_HP_PERCENT = 50;

	/**
	 * @return the temperament of the bot with this object id. The id goes through a mixing step first, as object ids of bots are consecutive
	 *         and would otherwise alternate in a pattern.
	 */
	public static BotTemperament of(int objectId) {
		int h = objectId;
		h ^= h >>> 16;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		h *= 0xc2b2ae35;
		h ^= h >>> 16;
		return switch (h & 3) {
			case 0 -> FIGHTER;
			case 1 -> OPPORTUNIST;
			default -> PEACEFUL;
		};
	}

	/**
	 * @param invasion true while the bot is on an invasion (a rift), where it fights every enemy it meets whatever its temperament
	 * @return true if a bot of this temperament may start a fight with that enemy
	 */
	public boolean mayStartAgainst(boolean invasion, int botLevel, int targetLevel, int targetHpPercent) {
		if (invasion)
			return true;
		return switch (this) {
			case FIGHTER -> true;
			case OPPORTUNIST -> targetLevel < botLevel || targetHpPercent <= WEAKENED_HP_PERCENT;
			case PEACEFUL -> false;
		};
	}
}
