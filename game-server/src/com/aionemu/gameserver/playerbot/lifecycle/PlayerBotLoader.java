package com.aionemu.gameserver.playerbot.lifecycle;

import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;

/**
 * Loads a bot's {@link Player} straight from the database, bypassing the login server and the client connection.
 */
public class PlayerBotLoader {

	private PlayerBotLoader() {
	}

	/**
	 * @return The freshly loaded player, never spawned into the world, or null if no character exists with that name.
	 */
	public static Player load(String characterName) {
		int accountId = PlayerDAO.getAccountIdByName(characterName);
		if (accountId == 0)
			return null;
		Account account = AccountService.loadAccount(accountId);
		PlayerAccountData accountData = findCharacter(account, characterName);
		if (accountData == null)
			return null;
		return PlayerService.getPlayer(accountData.getPlayerCommonData().getPlayerObjId(), account);
	}

	private static PlayerAccountData findCharacter(Account account, String characterName) {
		for (PlayerAccountData accountData : account) {
			if (accountData.getPlayerCommonData().getName().equalsIgnoreCase(characterName))
				return accountData;
		}
		return null;
	}
}
