package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.lifecycle.BotOutfitter;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLeaveWorldService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * Entry point of the playerbot system: bots are real {@link Player} objects driven by server side AI instead of a client connection.
 */
public class PlayerBotService {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotService.class);

	/**
	 * How often spawned bots are written to the database. Bots now gain levels, skills and loot unattended over hours, and nothing else writes them:
	 * the engine's own {@code PeriodicSaveService} handles legion warehouses only, and real players are saved when they log out — a door a bot never
	 * uses. A clean shutdown still saves them; this is what stands between a crash and a day of progress.
	 */
	private static final long SAVE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private final Map<Integer, Player> spawnedBots = new ConcurrentHashMap<>();

	public static PlayerBotService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Puts the world back the way it was and starts saving it. Called once, from {@code GameServer}, after the world is loaded.
	 */
	public void onStartUp() {
		ThreadPoolManager.getInstance().scheduleAtFixedRate(this::saveAll, SAVE_INTERVAL_MILLIS, SAVE_INTERVAL_MILLIS);
		Set<String> roster = BotRoster.restore();
		if (roster.isEmpty())
			return;
		int restored = 0;
		for (String characterName : roster) {
			Player bot = loadAvailableBot(characterName);
			if (bot == null) {
				log.warn("Bot {} is on the roster but no longer exists, dropping it", characterName);
				continue;
			}
			try {
				PlayerBotEnterWorldService.enterWorld(bot);
				spawnedBots.put(bot.getObjectId(), bot);
				restored++;
			} catch (RuntimeException e) {
				log.error("Could not restore bot " + characterName, e);
			}
		}
		rememberRoster(); // drops whatever could not be restored, so a broken name is not retried every restart
		log.info("Restored {} of {} bot(s) from the roster", restored, roster.size());
	}

	/** Writes every spawned bot to the database, in place, without taking it out of the world. */
	public void saveAll() {
		for (Player bot : spawnedBots.values()) {
			try {
				PlayerService.storePlayer(bot);
			} catch (RuntimeException e) {
				log.error("Could not save bot " + bot.getName(), e);
			}
		}
	}

	/** The roster is written on every change rather than at shutdown, because a shutdown that never runs is the case it exists for. */
	private void rememberRoster() {
		Set<String> names = new LinkedHashSet<>();
		spawnedBots.values().forEach(bot -> names.add(bot.getName()));
		BotRoster.remember(names);
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
		rememberRoster();
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
	/**
	 * Fills a region with people who belong to it.
	 * <p>
	 * The old version cloned one template character count times, so a map ended up with one class, one level and one set of training gear repeated.
	 * Nothing about it was a population. What a region needs is taken from the region itself: the level band comes from the creatures living there,
	 * the classes are spread over those a character of that level could be, and the gear is fitted to each one. They are residents from birth, so
	 * they stay the level their home is worth.
	 *
	 * @param commander Whoever asked, whose map is populated and whose race and looks the new characters borrow.
	 */
	public String populate(int count, Player commander, String templateName) {
		PlayerCommonData template = PlayerDAO.loadPlayerCommonDataByName(templateName);
		if (template == null)
			return "No character found with name " + templateName + " to copy from";
		int worldId = commander.getWorldId();
		int band = BotPlaces.levelOf(worldId);
		if (BotPlaces.settlements(worldId).isEmpty())
			return "Nobody lives on this map, so there is nowhere to put anyone";

		List<String> created = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name;
			try {
				name = PlayerBotCreationService.generateName();
			} catch (IllegalStateException e) {
				return report(created, "ran out of free names");
			}
			int level = Math.max(1, band + Rnd.get(-2, 2));
			PlayerClass playerClass = classFor(level);
			try {
				Player bot = PlayerBotCreationService.create(name, playerClass, level, template);
				BotRoster.setResident(name, true);
				BotOutfitter.dress(bot);
				PlayerService.storePlayer(bot);
				created.add(name + " (" + playerClass + " " + level + ")");
			} catch (IllegalArgumentException | IllegalStateException e) {
				log.warn("Could not create bot " + name, e);
				return report(created, e.getMessage());
			}
		}
		return report(created, null) + ", around level " + band;
	}

	/**
	 * @return A class a character of this level could actually be. Below ten only the four starting classes exist, and handing out a cleric of level
	 *         six would be a character the game itself cannot make.
	 */
	private static PlayerClass classFor(int level) {
		List<PlayerClass> choices = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values()) {
			if (playerClass.isStartingClass() == (level < 10))
				choices.add(playerClass);
		}
		return choices.get(Rnd.get(0, choices.size() - 1));
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
		rememberRoster(); // taken out on purpose, so a restart leaves it out too
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
	/**
	 * Reads or sets what a bot is for: a resident of the place it stands in, or an adventurer.
	 * <p>
	 * A resident gains no experience, so the region it inhabits keeps inhabitants of its own level. Takes effect on the next spawn, since the flag is
	 * set as a bot enters the world; a spawned bot is switched over on the spot as well.
	 */
	public String setKind(String characterName, Boolean resident) {
		if (resident == null)
			return characterName + " is " + (BotRoster.isResident(characterName) ? "a resident" : "an adventurer");
		BotRoster.setResident(characterName, resident);
		Player spawned = findSpawnedBot(characterName);
		if (spawned != null)
			spawned.getCommonData().setNoExp(resident);
		return characterName + " is now " + (resident ? "a resident, and will not level any further" : "an adventurer, and levels normally");
	}

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
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";

		botAi.setAnchor(commander.getX(), commander.getY(), commander.getZ()); // being sent somewhere makes it the bot's new home
		if (!botAi.walkTo(commander.getX(), commander.getY(), commander.getZ()))
			return characterName + " is blocked by an obstacle";
		return characterName + " is on its way";
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check, so the vendor trip can be tested without farming first.
	 */
	public String sell(String characterName) {
		Player bot = findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;
		if (!(bot.getAi() instanceof PlayerBotAI botAi))
			return characterName + " has no bot AI attached";
		if (!BotVendorManager.hasJunk(bot))
			return characterName + " has nothing worth selling";

		return botAi.forceSellTrip() ? characterName + " is heading to sell" : "No shop found on " + characterName + "'s map";
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
		int count = despawnEverything();
		rememberRoster(); // emptied by hand, so the world comes back empty
		return "Despawned " + count + " bot(s)";
	}

	/**
	 * Takes every bot out of the world on the way down, leaving the roster alone so the next start puts them back.
	 * <p>
	 * Not {@link #despawnAll()}: that one is an operator emptying the world on purpose, and clearing the roster is the whole point of it. Going
	 * through it here would mean every restart came back to an empty map.
	 */
	public void onShutdown() {
		log.info("Despawning {} bot(s) for shutdown", spawnedBots.size());
		despawnEverything();
	}

	/** @return How many bots were taken out. Leaves the roster untouched; the caller decides what it now means. */
	private int despawnEverything() {
		int count = spawnedBots.size();
		spawnedBots.values().forEach(bot -> {
			try {
				PlayerBotLeaveWorldService.leaveWorld(bot);
			} catch (RuntimeException e) {
				log.error("Could not despawn bot " + bot.getName(), e);
			}
		});
		spawnedBots.clear();
		return count;
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
