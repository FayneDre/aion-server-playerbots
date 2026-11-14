package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;

/**
 * How readily a bot starts a fight with a player it meets in a PvP zone. Drawn once from the bot's object id, so it never changes and needs no
 * memory: human players differ the same way, and a bot that attacked on sight would empty the zone of everyone else.
 */
public enum BotTemperament {

	/** Attacks an enemy in range: about 1 bot in 4 (the shares are options, `gameserver.playerbot.pvp.share.*`). */
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
		return of(h, PlayerBotConfig.PVP_SHARE_FIGHTER, PlayerBotConfig.PVP_SHARE_OPPORTUNIST, PlayerBotConfig.PVP_SHARE_PEACEFUL);
	}

	/** @return the temperament a mixed value draws, the shares being relative to one another; with no share at all, peaceful. */
	static BotTemperament of(int mixed, int fighters, int opportunists, int peacefuls) {
		int total = Math.max(0, fighters) + Math.max(0, opportunists) + Math.max(0, peacefuls);
		if (total == 0)
			return PEACEFUL;
		int draw = (mixed & 0x7fffffff) % total;
		if (draw < Math.max(0, fighters))
			return FIGHTER;
		return draw < Math.max(0, fighters) + Math.max(0, opportunists) ? OPPORTUNIST : PEACEFUL;
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
