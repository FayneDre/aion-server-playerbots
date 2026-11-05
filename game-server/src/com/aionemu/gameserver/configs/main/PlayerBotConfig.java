package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;
import com.aionemu.gameserver.playerbot.positioning.PositioningMode;
import com.aionemu.gameserver.playerbot.social.ChatLanguage;

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
	 * When bots in a group stand where their class belongs in a fight (melee behind the enemy, ranged back from it): against bosses only, never, or always.
	 * Otherwise they walk to the enemy as they always did, which is what the positioning figures logged every few minutes are compared against.
	 */
	@Property(key = "gameserver.playerbot.positioning", defaultValue = "AUTO")
	public static volatile PositioningMode POSITIONING;

	/**
	 * Whether a bot going to another place of its own accord flies there when the trip is long enough and the ground allows it. Off, bots fly only to
	 * reach somewhere walking has been proved not to, to follow a flying player, and to get home when stuck.
	 */
	@Property(key = "gameserver.playerbot.ambient.flight", defaultValue = "true")
	public static volatile boolean AMBIENT_FLIGHT;

	/**
	 * Whether a bot strolling or loitering near a real player now and then glides off a ledge to the ground below, for the look of it. Off, bots glide only where walking is proved
	 * impossible and to follow a gliding player.
	 */
	@Property(key = "gameserver.playerbot.ambient.glide", defaultValue = "true")
	public static volatile boolean AMBIENT_GLIDE;

	/**
	 * How much of each region's countryside is awake. {@code auto} follows the players: all of it at the busy density on a map somebody plays on, a fifth
	 * elsewhere, a twentieth with nobody online. A percent from 0 to 100 holds every map at that share of its busy density whoever is online, so the world
	 * is the same seen or unseen. Villages are always full either way. Read at every review of the director (30 s).
	 */
	@Property(key = "gameserver.playerbot.density", defaultValue = "auto")
	public static volatile String DENSITY = "auto";

	/**
	 * Whether bots may attack players of the other race (real players and bots) where the engine makes them enemies, which is a PvP zone. Off, no bot
	 * ever picks a player as a target and nothing of the PvP behaviour runs.
	 */
	@Property(key = "gameserver.playerbot.pvp.enable", defaultValue = "true")
	public static volatile boolean PVP_ENABLE;

	/** How willing a bot is to start a fight with a player of the other race, as shares of the bots (relative: 25, 25 and 50 are a quarter, a quarter and a half). */
	@Property(key = "gameserver.playerbot.pvp.share.fighter", defaultValue = "25")
	public static volatile int PVP_SHARE_FIGHTER = 25;
	@Property(key = "gameserver.playerbot.pvp.share.opportunist", defaultValue = "25")
	public static volatile int PVP_SHARE_OPPORTUNIST = 25;
	@Property(key = "gameserver.playerbot.pvp.share.peaceful", defaultValue = "50")
	public static volatile int PVP_SHARE_PEACEFUL = 50;

	/** How far above its own level a bot picks a fight with a player. */
	@Property(key = "gameserver.playerbot.pvp.level_above", defaultValue = "3")
	public static volatile int PVP_LEVEL_ABOVE = 3;

	/** How far beneath its own level a bot still picks a fight with a player. */
	@Property(key = "gameserver.playerbot.pvp.level_below", defaultValue = "6")
	public static volatile int PVP_LEVEL_BELOW = 6;

	/**
	 * What a world bot earns from monsters, alone and in a group, and from player kills, as a percent of the official experience rate (a player's rates do
	 * not apply to it). The first two are never less than 1: zero is no experience at all, not a slow one. A bot in a player's group follows the player's rate.
	 */
	@Property(key = "gameserver.playerbot.xp.solo", defaultValue = "1")
	public static volatile int XP_SOLO = 1;
	@Property(key = "gameserver.playerbot.xp.group", defaultValue = "1")
	public static volatile int XP_GROUP = 1;
	@Property(key = "gameserver.playerbot.xp.pvp", defaultValue = "100")
	public static volatile int XP_PVP = 100;

	/** Whether bots speak in the chat channels and by shout; `//bot speech off` silences them further, until the next start. */
	@Property(key = "gameserver.playerbot.chat.enable", defaultValue = "true")
	public static volatile boolean CHAT_ENABLE = true;

	/** The language of everything bots say in the chat channels and by shout: FR or EN. */
	@Property(key = "gameserver.playerbot.chat.language", defaultValue = "FR")
	public static volatile ChatLanguage CHAT_LANGUAGE;

	/** Whether the server stocks the broker (the auction house, docs/capitals/plans/auction-house-plan.md). Read at startup. */
	@Property(key = "gameserver.playerbot.broker.enable", defaultValue = "true")
	public static boolean BROKER_ENABLE;

	/** Listings the house keeps in each race's broker. */
	@Property(key = "gameserver.playerbot.broker.size", defaultValue = "6000")
	public static volatile int BROKER_SIZE;

	/** Minutes between two passes of the house (settling, then topping each category up). */
	@Property(key = "gameserver.playerbot.broker.interval", defaultValue = "15")
	public static volatile int BROKER_INTERVAL_MINUTES;

	/** Minutes over which an empty house is first filled, so that the start is not slowed. */
	@Property(key = "gameserver.playerbot.broker.fill_minutes", defaultValue = "10")
	public static volatile int BROKER_FILL_MINUTES;

	/** Share of each category (names of the client's top level categories), as {@code NAME:weight} separated by semicolons. */
	@Property(key = "gameserver.playerbot.broker.category_shares", defaultValue = "WEAPON:12;ARMOR:16;ACCESSORY:8;SKILL_RELATED:4;HOME_DECOR:1;FURNITURE:2;CRAFT:22;CONSUMABLES:30;OTHER:5")
	public static String BROKER_CATEGORY_SHARES;

	/** Share of each quality inside a category, as {@code NAME:weight} separated by semicolons. */
	@Property(key = "gameserver.playerbot.broker.quality_shares", defaultValue = "COMMON:25;RARE:30;LEGEND:25;UNIQUE:12;EPIC:7;MYTHIC:1")
	public static String BROKER_QUALITY_SHARES;

	/** Template ids the house never lists and withdraws if it finds them listed, separated by commas. */
	@Property(key = "gameserver.playerbot.broker.blacklist", defaultValue = "")
	public static String BROKER_BLACKLIST;

	/** Whether the house buys players' listings priced in range (needs the house itself to be enabled). */
	@Property(key = "gameserver.playerbot.broker.buyer.enable", defaultValue = "true")
	public static boolean BROKER_BUYER_ENABLE;

	/** Seconds between two passes of the buyer. */
	@Property(key = "gameserver.playerbot.broker.buyer.interval", defaultValue = "60")
	public static volatile int BROKER_BUYER_INTERVAL_SECONDS;

	/** The most listings one pass of the buyer buys. */
	@Property(key = "gameserver.playerbot.broker.buyer.max_per_pass", defaultValue = "5")
	public static volatile int BROKER_BUYER_MAX_PER_PASS;

	/** A listing in range is bought after a random delay between these two numbers of minutes. */
	@Property(key = "gameserver.playerbot.broker.buyer.min_delay", defaultValue = "1")
	public static volatile int BROKER_BUYER_MIN_DELAY_MINUTES;

	@Property(key = "gameserver.playerbot.broker.buyer.max_delay", defaultValue = "5")
	public static volatile int BROKER_BUYER_MAX_DELAY_MINUTES;

	/** How far over the top of the known range, in percent, a unit price may be and still be bought. */
	@Property(key = "gameserver.playerbot.broker.buyer.tolerance", defaultValue = "5")
	public static volatile int BROKER_BUYER_TOLERANCE_PERCENT;

	/** Kinah the buyer spends in a day in all, on one seller, and on one kind of item. */
	@Property(key = "gameserver.playerbot.broker.buyer.daily_kinah", defaultValue = "200000000")
	public static volatile long BROKER_BUYER_DAILY_KINAH;

	@Property(key = "gameserver.playerbot.broker.buyer.daily_kinah_per_player", defaultValue = "30000000")
	public static volatile long BROKER_BUYER_DAILY_KINAH_PER_PLAYER;

	@Property(key = "gameserver.playerbot.broker.buyer.daily_kinah_per_item", defaultValue = "50000000")
	public static volatile long BROKER_BUYER_DAILY_KINAH_PER_ITEM;
}
