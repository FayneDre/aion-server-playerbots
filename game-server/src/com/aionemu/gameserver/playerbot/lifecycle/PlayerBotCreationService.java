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
import com.aionemu.gameserver.dao.PlayerAppearanceDAO;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.dao.PlayerQuestListDAO;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
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
	private static final String[] NAME_STARTS = { "Ael", "Bri", "Cor", "Dal", "Eri", "Fen", "Gor", "Hal", "Iri", "Jor", "Kal", "Lyr", "Mor", "Nar",
		"Oly", "Pyr", "Quil", "Ras", "Syl", "Tor", "Ulf", "Ver", "Wyn", "Xan", "Yri", "Zel" };
	private static final String[] NAME_ENDS = { "an", "ar", "el", "en", "ia", "ik", "il", "is", "on", "or", "ra", "ric", "us", "wyn", "yth" };
	private static final int NAME_ATTEMPTS = 50;
	/** How many head and hair models the character creation screen offers. Picking outside this is a missing model, not a different face. */
	private static final int FACE_MODELS = 12, HAIR_MODELS = 12;
	/** How far a face or body slider may move from the character it was copied from, and how far from neutral it may ever end up. */
	private static final int FEATURE_NUDGE = 30, FEATURE_LIMIT = 90;
	/** Height moves least: its extremes are the ones that read as broken rather than as a different person. */
	private static final float HEIGHT_VARIATION = 0.04f;

	private PlayerBotCreationService() {
	}

	/**
	 * Creates a character on the same account and with the same race, gender and looks as the given template, apart from randomized skin and hair
	 * colors so bots are told apart at a glance.
	 *
	 * @param template An existing character to copy account and appearance from.
	 * @return The new character, stored and then read back, so it is as complete as one that just logged in.
	 * @throws IllegalArgumentException If the name or the template is unusable.
	 * @throws IllegalStateException If storing fails, in which case the reserved object id is released again.
	 */
	public static Player create(String name, PlayerClass playerClass, int level, PlayerCommonData template) {
		if (!NameRestrictionService.isValidName(name) || NameRestrictionService.isForbidden(name))
			throw new IllegalArgumentException("Invalid character name: " + name);
		if (PlayerDAO.isNameUsed(name))
			throw new IllegalArgumentException("Name already taken: " + name);

		int accountId = allocateAccountId();
		String accountName = "bot" + accountId;
		Account account = AccountService.loadAccount(accountId);

		PlayerCommonData commonData = new PlayerCommonData(IDFactory.getInstance().nextId());
		commonData.setName(name);
		commonData.setRace(template.getRace());
		commonData.setGender(template.getGender());
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

		PlayerAccountData accountData = new PlayerAccountData(commonData, varyAppearance(PlayerAppearanceDAO.load(template.getPlayerObjId())));
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

	/**
	 * Makes a copied face into somebody else's.
	 * <p>
	 * A population cloned from one character is forty five of the same person, which no amount of good behaviour makes up for. Three kinds of change,
	 * and they carry different risks:
	 * <ul>
	 * <li>colours are free — any value is a valid colour;</li>
	 * <li>the head and hair models are picked from what the template's race and gender actually offer, since a model number that does not exist is
	 * not a different face but a missing one;</li>
	 * <li>the sliders are nudged, never redrawn. They are signed bytes about a neutral zero, and a character built from random ones is a gargoyle:
	 * the whole reason a default appearance of all zeroes renders as something the client cannot even draw.</li>
	 * </ul>
	 * Height moves least of all. It is the one slider whose extremes read immediately as broken — a village of dwarves — so it stays within a few
	 * percent of the character it was copied from.
	 */
	private static PlayerAppearance varyAppearance(PlayerAppearance appearance) {
		appearance.setHairRGB(RANDOM.nextInt(0x1000000));
		appearance.setLipRGB(RANDOM.nextInt(0x1000000));
		appearance.setSkinRGB(RANDOM.nextInt(0x1000000));
		appearance.setEyeRGB(RANDOM.nextInt(0x1000000));

		appearance.setFace(RANDOM.nextInt(FACE_MODELS));
		appearance.setHair(RANDOM.nextInt(HAIR_MODELS));

		appearance.setFaceShape(nudge(appearance.getFaceShape()));
		appearance.setForehead(nudge(appearance.getForehead()));
		appearance.setEyeHeight(nudge(appearance.getEyeHeight()));
		appearance.setEyeSpace(nudge(appearance.getEyeSpace()));
		appearance.setEyeSize(nudge(appearance.getEyeSize()));
		appearance.setNose(nudge(appearance.getNose()));
		appearance.setNoseWidth(nudge(appearance.getNoseWidth()));
		appearance.setCheek(nudge(appearance.getCheek()));
		appearance.setMouthSize(nudge(appearance.getMouthSize()));
		appearance.setLipSize(nudge(appearance.getLipSize()));
		appearance.setJawHeigh(nudge(appearance.getJawHeigh()));
		appearance.setChinJut(nudge(appearance.getChinJut()));
		appearance.setShoulders(nudge(appearance.getShoulders()));
		appearance.setTorso(nudge(appearance.getTorso()));
		appearance.setWaist(nudge(appearance.getWaist()));
		appearance.setArmThickness(nudge(appearance.getArmThickness()));
		appearance.setLegThickness(nudge(appearance.getLegThickness()));

		appearance.setHeight(appearance.getHeight() * (1 + (RANDOM.nextFloat() - 0.5f) * 2 * HEIGHT_VARIATION));
		return appearance;
	}

	/**
	 * Moves one slider a little.
	 *
	 * @param value The stored value: a signed byte kept as an unsigned int, so 253 means -3 and not "almost the maximum".
	 * @return The nudged value in the same form, kept well inside the range so no feature reaches the extreme the sliders allow.
	 */
	private static int nudge(int value) {
		int signed = (byte) value;
		int moved = Math.clamp(signed + RANDOM.nextInt(FEATURE_NUDGE * 2 + 1) - FEATURE_NUDGE, -FEATURE_LIMIT, FEATURE_LIMIT);
		return moved & 0xFF;
	}
}
