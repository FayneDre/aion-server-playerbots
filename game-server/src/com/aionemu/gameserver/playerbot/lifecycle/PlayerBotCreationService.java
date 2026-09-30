package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.dao.PlayerQuestListDAO;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.ClassChangeService;
import com.aionemu.gameserver.services.NameRestrictionService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.idfactory.IDFactory;

/**
 * Creates bot characters in the database without a client.
 * <p>
 * This is the headless half of {@code CM_CREATE_CHARACTER}: the packet only gathers what the creation screen collected, then calls the same two
 * service methods used here. Bots are cloned from an existing character rather than built from scratch, because a default {@link PlayerAppearance}
 * is all zeroes, including a height of 0, which the client cannot render.
 * <p>
 * Bots live on their own accounts, in a reserved id range. Accounts belong to the login server and the {@code players} table has no foreign key on
 * them, so these ids never need to exist there. It keeps bots off the player's real accounts, where they would fill up the character selection
 * screen, and makes them trivial to tell apart in the database.
 */
public class PlayerBotCreationService {

	private static final Random RANDOM = new Random();
	/** Bot accounts start here, far above anything the login server hands out. */
	public static final int BOT_ACCOUNT_ID_BASE = 900000;
	private static final AtomicInteger nextBotAccountId = new AtomicInteger();
	/** Turned over for each character made, so a population comes out half of each gender rather than however the coin fell. */
	private static final AtomicInteger nextGender = new AtomicInteger();
	private static final String[] NAME_STARTS = { "Ael", "Bri", "Cor", "Dal", "Eri", "Fen", "Gor", "Hal", "Iri", "Jor", "Kal", "Lyr", "Mor", "Nar",
		"Oly", "Pyr", "Quil", "Ras", "Syl", "Tor", "Ulf", "Ver", "Wyn", "Xan", "Yri", "Zel" };
	private static final String[] NAME_ENDS = { "an", "ar", "el", "en", "ia", "ik", "il", "is", "on", "or", "ra", "ric", "us", "wyn", "yth" };
	private static final int NAME_ATTEMPTS = 50;

	private PlayerBotCreationService() {
	}

	/**
	 * Creates a bot character of the given race, with a face of its own.
	 * <p>
	 * No character to copy from. Cloning one was the first way this worked, and it made the bot system depend on somebody having played the server
	 * first: a fresh installation has no character to point at, so it could not be populated at all. {@link BotAppearance} builds a plain face
	 * instead, which is what the creation screen starts everyone with.
	 *
	 * @return The new character, stored and then read back, so it is as complete as one that just logged in.
	 * @throws IllegalArgumentException If the name is unusable.
	 * @throws IllegalStateException If storing fails, in which case the reserved object id is released again.
	 */
	public static Player create(String name, PlayerClass playerClass, int level, Race race) {
		if (!NameRestrictionService.isValidName(name) || NameRestrictionService.isForbidden(name))
			throw new IllegalArgumentException("Invalid character name: " + name);
		if (PlayerDAO.isNameUsed(name))
			throw new IllegalArgumentException("Name already taken: " + name);

		int accountId = allocateAccountId();
		String accountName = "bot" + accountId;
		Account account = AccountService.loadAccount(accountId);

		PlayerCommonData commonData = new PlayerCommonData(IDFactory.getInstance().nextId());
		commonData.setName(name);
		commonData.setRace(race);
		// Alternating, not drawn. A coin flipped forty five times lands twenty against twenty five often enough to be noticed, and there is nothing
		// to be gained from the randomness: what is wanted is half of each, which alternating gives exactly.
		commonData.setGender(nextGender.getAndIncrement() % 2 == 0 ? Gender.MALE : Gender.FEMALE);
		commonData.setPlayerClass(playerClass);
		// levels above 9 are gated on daeva status, which a bot will never earn by running the ascension quest
		if (!playerClass.isStartingClass())
			commonData.setDaeva(true);
		commonData.setLevel(level); // after the class and daeva status, both of which cap the experience it accepts
		long exp = commonData.getExp();
		// Created at level one, as the game creates every character, and brought up to its level by the experience written further down.
		// PlayerService.newPlayer teaches a character everything it knows on the way in, and teaching anything to a half built object eventually
		// reaches something it does not have: a recipe learned at a trade level went looking for a recipe list that only the load path fills in.
		commonData.setLevel(1);

		PlayerAccountData accountData = new PlayerAccountData(commonData, BotAppearance.invent());
		Player bot = PlayerService.newPlayer(accountData, account);
		if (!PlayerService.storeNewPlayer(bot, accountName, accountId)) {
			IDFactory.getInstance().releaseId(commonData.getPlayerObjId());
			throw new IllegalStateException("Could not store new bot " + name);
		}
		PlayerService.storeCreationTime(bot.getObjectId(), new Timestamp(System.currentTimeMillis()));

		if (commonData.isDaeva()) { // daeva status is not a column: it is recomputed from the ascension quest on every load
			ClassChangeService.completeAscensionQuest(bot);
			PlayerQuestListDAO.store(bot);
		}
		storeExperience(bot.getObjectId(), exp);
		// read the character back rather than hand out the one just built: PlayerService.newPlayer only fills in what the creation screen needs to
		// store, and leaves the effect controller, the known list, the flight controller and the stat functions null. A client never sees that half
		// built object either — it returns to character selection and enters the world through PlayerService.getPlayer, which is what this mirrors.
		Player stored = PlayerBotLoader.load(name);
		if (stored == null)
			throw new IllegalStateException("Stored bot " + name + " could not be read back");
		return stored;
	}

	/**
	 * Writes the experience, which the character creation insert leaves out because a client created character always starts at zero. The level is
	 * never stored as such, it is always derived from experience, so without this the bot would be back to level 1 on its next load.
	 * <p>
	 * Only this one column is updated: {@code PlayerDAO.storePlayer} reads {@code getPosition()}, which stays null until the character first enters
	 * the world.
	 */
	private static void storeExperience(int playerId, long exp) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("UPDATE `players` SET `exp` = ? WHERE `id` = ?")) {
			stmt.setLong(1, exp);
			stmt.setInt(2, playerId);
			stmt.executeUpdate();
		} catch (SQLException e) {
			LoggerFactory.getLogger(PlayerBotCreationService.class).error("Could not store the experience of player " + playerId, e);
		}
	}

	/**
	 * Hands out the next free account id in the bot range. The range is scanned once, then kept in memory, so ids stay unique across a run and
	 * survive a restart.
	 */
	private static int allocateAccountId() {
		nextBotAccountId.compareAndSet(0, highestBotAccountId() + 1);
		return nextBotAccountId.getAndIncrement();
	}

	/** @return Every character standing on a reserved bot account, which is what "all the bots" means when starting a population over. */
	public static List<String> botCharacterNames() {
		List<String> names = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT `name` FROM `players` WHERE `account_id` >= ?")) {
			stmt.setInt(1, BOT_ACCOUNT_ID_BASE);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next())
					names.add(rs.getString("name"));
			}
		} catch (SQLException e) {
			LoggerFactory.getLogger(PlayerBotCreationService.class).error("Could not list the bot characters", e);
		}
		return names;
	}

	/**
	 * @return true if any bot character already stands on that map.
	 *         <p>
	 *         Asked of the characters themselves, not of the roster that indexes them. The roster is derived data — it can be emptied, rebuilt or
	 *         lost without a single character going anywhere — and asking it instead is how a map with forty five inhabitants was judged uninhabited
	 *         and populated a second time, leaving ninety.
	 */
	public static boolean hasBotsOn(int worldId) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT 1 FROM `players` WHERE `account_id` >= ? AND `world_id` = ? LIMIT 1")) {
			stmt.setInt(1, BOT_ACCOUNT_ID_BASE);
			stmt.setInt(2, worldId);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			LoggerFactory.getLogger(PlayerBotCreationService.class).error("Could not check whether map " + worldId + " already has bots", e);
			return true; // erring towards doing nothing: populating a map twice is worse than not populating it
		}
	}

	private static int highestBotAccountId() {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT MAX(`account_id`) FROM `players` WHERE `account_id` >= ?")) {
			stmt.setInt(1, BOT_ACCOUNT_ID_BASE);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next() && rs.getInt(1) >= BOT_ACCOUNT_ID_BASE)
					return rs.getInt(1);
			}
		} catch (SQLException e) {
			LoggerFactory.getLogger(PlayerBotCreationService.class).error("Could not read the highest bot account id", e);
		}
		return BOT_ACCOUNT_ID_BASE - 1;
	}

	/**
	 * Invents a pronounceable name that passes the server's name rules and is not taken yet.
	 *
	 * @throws IllegalStateException If no free name was found, which means the syllable pool is exhausted rather than a transient failure.
	 */
	public static String generateName() {
		for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
			String name = NAME_STARTS[RANDOM.nextInt(NAME_STARTS.length)] + NAME_ENDS[RANDOM.nextInt(NAME_ENDS.length)];
			if (NameRestrictionService.isValidName(name) && !NameRestrictionService.isForbidden(name) && !PlayerDAO.isNameUsed(name))
				return name;
		}
		throw new IllegalStateException("Could not find a free bot name in " + NAME_ATTEMPTS + " attempts");
	}

}
