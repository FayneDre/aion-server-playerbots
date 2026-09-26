package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Timestamp;
import java.util.Random;

import com.aionemu.gameserver.dao.PlayerAppearanceDAO;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.services.AccountService;
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
		Account account = AccountService.loadAccount(accountId);
		if (account == null)
			throw new IllegalArgumentException("No account " + accountId + " for template " + template.getName());

		PlayerCommonData commonData = new PlayerCommonData(IDFactory.getInstance().nextId());
		commonData.setName(name);
		commonData.setRace(template.getRace());
		commonData.setGender(template.getGender());
		commonData.setPlayerClass(playerClass);
		commonData.setLevel(level); // after the class, since experience is class dependent

		PlayerAccountData accountData = new PlayerAccountData(commonData, randomizeColors(PlayerAppearanceDAO.load(template.getPlayerObjId())));
		Player bot = PlayerService.newPlayer(accountData, account);
		if (!PlayerService.storeNewPlayer(bot, account.getName(), account.getId())) {
			IDFactory.getInstance().releaseId(commonData.getPlayerObjId());
			throw new IllegalStateException("Could not store new bot " + name);
		}
		PlayerService.storeCreationTime(bot.getObjectId(), new Timestamp(System.currentTimeMillis()));
		return bot;
	}

	private static PlayerAppearance randomizeColors(PlayerAppearance appearance) {
		appearance.setHairRGB(RANDOM.nextInt(0x1000000));
		appearance.setLipRGB(RANDOM.nextInt(0x1000000));
		return appearance;
	}
}
