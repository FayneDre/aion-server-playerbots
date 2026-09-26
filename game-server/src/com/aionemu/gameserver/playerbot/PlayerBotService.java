package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLeaveWorldService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.world.World;

/**
 * Entry point of the playerbot system: bots are real {@link Player} objects driven by server side AI instead of a client connection.
 */
public class PlayerBotService {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotService.class);

	private final Map<Integer, Player> spawnedBots = new ConcurrentHashMap<>();

	public static PlayerBotService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Loads a bot character from the database without spawning it, to verify that a connectionless player can be built at all.
	 *
	 * @return A human readable summary of the loaded character, or an error message.
	 */
	public String describeLoadedBot(String characterName) {
		Player bot = loadAvailableBot(characterName);
		if (bot == null)
			return "No offline character found with name " + characterName;

		return String.format("%s (objId %d): %s level %d, %d equipped items, %d skills, %d/%d HP, attack speed %d", bot.getName(), bot.getObjectId(),
			bot.getPlayerClass(), bot.getLevel(), bot.getEquipment().getEquippedItems().size(), bot.getSkillList().size(),
			bot.getLifeStats().getCurrentHp(), bot.getLifeStats().getMaxHp(), bot.getGameStats().getAttackSpeed().getCurrent());
	}

	/**
	 * Loads a bot character and spawns it next to the given player.
	 *
	 * @return A human readable result message.
	 */
	public String spawn(String characterName, Player nextTo) {
		Player bot = loadAvailableBot(characterName);
		if (bot == null)
			return "No offline character found with name " + characterName;

		try {
			PlayerBotEnterWorldService.enterWorld(bot, nextTo);
		} catch (RuntimeException e) {
			log.error("Could not spawn bot " + characterName, e);
			return "Could not spawn " + characterName + " (see server log)";
		}
		spawnedBots.put(bot.getObjectId(), bot);
		return "Spawned " + bot.getName() + " (objId " + bot.getObjectId() + ")";
	}

	/**
	 * Creates a new bot character in the database, cloned from an existing one which supplies the account, race, gender and looks.
	 *
	 * @return A human readable result message.
	 */
	public String create(String characterName, String className, int level, String templateName) {
		PlayerClass playerClass;
		try {
			playerClass = PlayerClass.valueOf(className.toUpperCase());
		} catch (IllegalArgumentException e) {
			return "Unknown class " + className;
		}
		PlayerCommonData template = PlayerDAO.loadPlayerCommonDataByName(templateName);
		if (template == null)
			return "No character found with name " + templateName + " to copy from";

		try {
			Player bot = PlayerBotCreationService.create(characterName, playerClass, level, template);
			return "Created " + bot.getName() + " (objId " + bot.getObjectId() + "): " + playerClass + " level " + level;
		} catch (IllegalArgumentException | IllegalStateException e) {
			log.warn("Could not create bot " + characterName, e);
			return "Could not create " + characterName + ": " + e.getMessage();
		}
	}

	/**
	 * Creates several bots at once with generated names, for populating an area.
	 */
	public String populate(int count, String className, int level, String templateName) {
		List<String> created = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name;
			try {
				name = PlayerBotCreationService.generateName();
			} catch (IllegalStateException e) {
				return report(created, "ran out of free names");
			}
			String result = create(name, className, level, templateName);
			if (!result.startsWith("Created "))
				return report(created, result);
			created.add(name);
		}
		return report(created, null);
	}

	private String report(List<String> created, String failure) {
		String summary = created.isEmpty() ? "Created no bot" : "Created " + created.size() + " bots: " + String.join(", ", created);
		return failure == null ? summary : summary + " (stopped: " + failure + ")";
	}

	/**
	 * Deletes a bot character and everything attached to it. Restricted to the reserved bot accounts, so a mistyped name can never wipe a real
	 * character.
	 */
	public String delete(String characterName) {
		if (findSpawnedBot(characterName) != null)
			return characterName + " is spawned, despawn it first";

		int objectId = PlayerDAO.getPlayerIdByName(characterName);
		if (objectId == 0)
			return "No character found with name " + characterName;
		if (PlayerDAO.getAccountId(objectId) < PlayerBotCreationService.BOT_ACCOUNT_ID_BASE)
			return characterName + " is not on a bot account, refusing to delete it";

		PlayerService.deletePlayerFromDB(objectId);
		return "Deleted " + characterName;
	}

	public String despawn(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		spawnedBots.remove(bot.getObjectId());
		try {
			PlayerBotLeaveWorldService.leaveWorld(bot);
		} catch (RuntimeException e) {
			log.error("Could not despawn bot " + characterName, e);
			return "Could not despawn " + characterName + " (see server log)";
		}
		boolean stillInWorld = World.getInstance().findVisibleObject(bot.getObjectId()) != null;
		return "Despawned " + bot.getName() + (stillInWorld ? " but it is still registered in the world!" : "");
	}

	/**
	 * Answers yes to a pending question window, which a bot can never answer itself since it has no client.
	 */
	public String acceptRequest(String characterName, int questionId) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		if (!bot.getResponseRequester().respond(questionId, 1))
			return characterName + " has no pending request of that type";
		return characterName + " accepted";
	}

	/**
	 * Makes the bot attack the given player's target, or the player itself if it has no other target (handy during a duel).
	 */
	public String attack(String characterName, Player commander) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		Creature target = commander.getTarget() instanceof Creature creature && !creature.equals(bot) ? creature : commander;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.startAttacking(target);
		return characterName + " attacks " + target.getName();
	}

	public String stopAttacking(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.stopAttacking();
		return characterName + " stopped";
	}

	/**
	 * Toggles whether the bot acts on its own (engaging hostiles in reach, fighting back) or only follows commands.
	 */
	public String toggleAutonomy(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.setAutonomous(!botAi.isAutonomous());
		return characterName + " autonomy " + (botAi.isAutonomous() ? "on" : "off");
	}

	/**
	 * Makes the bot walk to the given player's position.
	 */
	public String come(String characterName, Player commander) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getMoveController() instanceof BotMoveController moveController))
			return characterName + " has no bot move controller attached";

		if (bot.getAi() instanceof PlayerBotAI botAi) // being sent somewhere makes it the bot's new home, otherwise it would walk back
			botAi.setAnchor(commander.getX(), commander.getY(), commander.getZ());
		if (!moveController.moveToPoint(commander.getX(), commander.getY(), commander.getZ()))
			return characterName + " is blocked by an obstacle";
		return characterName + " is on its way";
	}

	/**
	 * Lists what a spawned bot carries. Reads live memory, not the database, which only ever sees a bot when it despawns.
	 */
	public String describeInventory(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		Storage inventory = bot.getInventory();
		StringBuilder sb = new StringBuilder(
			String.format("%s carries %d kinah in %d/%d slots:", bot.getName(), inventory.getKinah(), inventory.size(), inventory.getLimit()));
		inventory.getItems().forEach(item -> sb.append(String.format("%n  %dx %s", item.getItemCount(), item.getItemTemplate().getL10n())));
		return sb.toString();
	}

	/** @return Which generated maps are currently held in memory. */
	public String describeNavmeshes() {
		return NavmeshService.getInstance().describe();
	}

	public String listSpawnedBots() {
		if (spawnedBots.isEmpty())
			return "No bots spawned";
		StringBuilder sb = new StringBuilder("Spawned bots:");
		spawnedBots.values().forEach(bot -> sb.append(String.format("%n  %s at %.1f %.1f %.1f%s", bot.getName(), bot.getX(), bot.getY(), bot.getZ(),
			bot.getMoveController().isInMove() ? " (moving)" : "")));
		return sb.toString();
	}

	public String despawnAll() {
		int count = spawnedBots.size();
		spawnedBots.values().forEach(bot -> {
			try {
				PlayerBotLeaveWorldService.leaveWorld(bot);
			} catch (RuntimeException e) {
				log.error("Could not despawn bot " + bot.getName(), e);
			}
		});
		spawnedBots.clear();
		return "Despawned " + count + " bot(s)";
	}

	private Player loadAvailableBot(String characterName) {
		if (World.getInstance().getPlayer(characterName) != null)
			return null;
		return PlayerBotLoader.load(characterName);
	}

	private Player findSpawnedBot(String characterName) {
		return spawnedBots.values().stream().filter(bot -> bot.getName().equalsIgnoreCase(characterName)).findFirst().orElse(null);
	}

	private static class SingletonHolder {

		private static final PlayerBotService INSTANCE = new PlayerBotService();
	}
}
