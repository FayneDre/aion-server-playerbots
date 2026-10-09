package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Settings for the playerbot system.
 */
public class PlayerBotConfig {

	/** Whether bots run at all. Turning this off leaves every bot character in the database, simply unspawned. */
	@Property(key = "gameserver.playerbot.enable", defaultValue = "true")
	public static boolean ENABLE;

	/**
	 * Maps to keep populated, as {@code <mapId>:<count>:<race>} separated by semicolons — for example
	 * {@code 210010000:45:ELYOS;220010000:45:ASMODIANS}.
	 * <p>
	 * Carried out on startup, and only for a map that has no inhabitants yet, so it populates a fresh installation once and does nothing on every
	 * start after that. It is how a server other than the one this was written on ends up with a living map without anyone having to type a command
	 * or write a row into the database by hand.
	 */
	@Property(key = "gameserver.playerbot.populate", defaultValue = "")
	public static String POPULATE;

	/**
	 * Whether bots in a group stand where their class belongs in a fight: melee behind the enemy, ranged back from it. Off, they walk to the enemy as they
	 * always did, which is what the positioning figures logged every few minutes are compared against.
	 */
	@Property(key = "gameserver.playerbot.positioning", defaultValue = "true")
	public static boolean POSITIONING;
}
