package com.aionemu.gameserver.playerbot.world;

/**
 * How much of a region's countryside the director keeps awake ({@code gameserver.playerbot.density.mode}). Villages are always full whichever it is.
 */
public enum DensityMode {

	/** Follows the players: all of it on a map somebody plays on (and for five minutes after), a fifth elsewhere, a twentieth with nobody online. */
	AUTO,
	/** Every map holds the percent of its busy countryside, whoever is online: the world the same seen or unseen. */
	FIXED,
	/** As {@link #AUTO}, but no map ever falls below the percent of its busy countryside. */
	FLOOR
}
