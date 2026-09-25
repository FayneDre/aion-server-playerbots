package com.aionemu.gameserver.playerbot;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.world.World;

/**
 * Entry point of the playerbot system: bots are real {@link Player} objects driven by server side AI instead of a client connection.
 */
public class PlayerBotService {

	public static PlayerBotService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Loads a bot character from the database without spawning it, to verify that a connectionless player can be built at all.
	 *
	 * @return A human readable summary of the loaded character, or an error message.
	 */
	public String describeLoadedBot(String characterName) {
		if (World.getInstance().getPlayer(characterName) != null)
			return characterName + " is currently online, log it out first";

		Player bot = PlayerBotLoader.load(characterName);
		if (bot == null)
			return "No character found with name " + characterName;

		return String.format("%s (objId %d): %s level %d, %d equipped items, %d skills, %d/%d HP, attack speed %d",
			bot.getName(), bot.getObjectId(), bot.getPlayerClass(), bot.getLevel(), bot.getEquipment().getEquippedItems().size(),
			bot.getSkillList().size(), bot.getLifeStats().getCurrentHp(), bot.getLifeStats().getMaxHp(),
			bot.getGameStats().getAttackSpeed().getCurrent());
	}

	private static class SingletonHolder {

		private static final PlayerBotService INSTANCE = new PlayerBotService();
	}
}
