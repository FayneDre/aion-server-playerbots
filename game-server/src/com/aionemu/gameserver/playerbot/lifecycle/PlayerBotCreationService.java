package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Random;

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
 */
public class PlayerBotCreationService {

	private static final Random RANDOM = new Random();

	private PlayerBotCreationService() {
	}

	/**
	 * Creates a character on the same account and with the same race, gender and looks as the given template, apart from randomized skin and hair
	 * colors so bots are told apart at a glance.
	 *
	 * @param template An existing character to copy account and appearance from.
	 * @return The new character, already stored.
	 * @throws IllegalArgumentException If the name or the template is unusable.
	 * @throws IllegalStateException If storing fails, in which case the reserved object id is released again.
	 */
	public static Player create(String name, PlayerClass playerClass, int level, PlayerCommonData template) {
		if (!NameRestrictionService.isValidName(name) || NameRestrictionService.isForbidden(name))
			throw new IllegalArgumentException("Invalid character name: " + name);
		if (PlayerDAO.isNameUsed(name))
			throw new IllegalArgumentException("Name already taken: " + name);

		int accountId = PlayerDAO.getAccountId(template.getPlayerObjId());
		String accountName = loadAccountName(template.getPlayerObjId());
		if (accountName == null)
			throw new IllegalArgumentException("No account found for template " + template.getName());
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

		PlayerAccountData accountData = new PlayerAccountData(commonData, randomizeColors(PlayerAppearanceDAO.load(template.getPlayerObjId())));
		Player bot = PlayerService.newPlayer(accountData, account);
		// the account name is only ever written to the players table, never read back, so it has to come from the template's own row
		if (!PlayerService.storeNewPlayer(bot, accountName, accountId)) {
			IDFactory.getInstance().releaseId(commonData.getPlayerObjId());
			throw new IllegalStateException("Could not store new bot " + name);
		}
		PlayerService.storeCreationTime(bot.getObjectId(), new Timestamp(System.currentTimeMillis()));

		if (commonData.isDaeva()) { // daeva status is not a column: it is recomputed from the ascension quest on every load
			ClassChangeService.completeAscensionQuest(bot);
			PlayerQuestListDAO.store(bot);
		}
		storeExperience(bot.getObjectId(), commonData.getExp());
		return bot;
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
	 * {@code Account} carries no name when loaded by id (it normally comes from the login server handshake), and no DAO reads the column back, so
	 * the denormalized copy stored on the template's own row is the only source available here.
	 */
	private static String loadAccountName(int playerId) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT `account_name` FROM `players` WHERE `id` = ?")) {
			stmt.setInt(1, playerId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next())
					return rs.getString("account_name");
			}
		} catch (SQLException e) {
			LoggerFactory.getLogger(PlayerBotCreationService.class).error("Could not read the account name of player " + playerId, e);
		}
		return null;
	}

	private static PlayerAppearance randomizeColors(PlayerAppearance appearance) {
		appearance.setHairRGB(RANDOM.nextInt(0x1000000));
		appearance.setLipRGB(RANDOM.nextInt(0x1000000));
		return appearance;
	}
}
