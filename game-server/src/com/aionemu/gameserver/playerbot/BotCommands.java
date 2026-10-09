package com.aionemu.gameserver.playerbot;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.lifecycle.BotOutfitter;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotDirector;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.world.World;

/**
 * What the {@code //bot} command does, one method per subcommand. Every method answers with the line the commander reads back, and none of them
 * holds state: the bots themselves are {@link PlayerBotService}'s, the inhabitants {@link BotPopulator}'s. Split from the service so that wording for a
 * chat window and the lifecycle of a bot are not the same file.
 */
public final class BotCommands {

	private static final Logger log = LoggerFactory.getLogger(BotCommands.class);

	private BotCommands() {
	}

	/**
	 * Loads a bot character from the database without spawning it, to verify that a connectionless player can be built at all.
	 *
	 * @return A human readable summary of the loaded character, or an error message.
	 */
	public static String describeLoadedBot(String characterName) {
		Player bot = bots().loadAvailableBot(characterName);
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
	public static String spawn(String characterName, Player nextTo) {
		Player bot = bots().loadAvailableBot(characterName);
		if (bot == null)
			return "No offline character found with name " + characterName;

		try {
			PlayerBotEnterWorldService.enterWorld(bot, nextTo);
		} catch (RuntimeException e) {
			log.error("Could not spawn bot " + characterName, e);
			return "Could not spawn " + characterName + " (see server log)";
		}
		bots().register(bot);
		return "Spawned " + bot.getName() + " (objId " + bot.getObjectId() + ")";
	}

	/**
	 * Creates a new bot character in the database, cloned from an existing one which supplies the account, race, gender and looks.
	 *
	 * @return A human readable result message.
	 */
	public static String create(String characterName, String className, int level, String raceName, Player maker) {
		PlayerClass playerClass;
		try {
			playerClass = PlayerClass.valueOf(className.toUpperCase());
		} catch (IllegalArgumentException e) {
			return "Unknown class " + className;
		}
		Race race = BotPopulator.raceOf(raceName);
		if (race == null)
			return "Unknown race " + raceName + ", expected ELYOS or ASMODIANS";

		try {
			Player bot = PlayerBotCreationService.create(characterName, playerClass, level, race);
			// A bot somebody made by hand is theirs; a bot the world made by populating a region belongs to the world. That is the whole of the
			// distinction, and it decides both who may delete it and whether the population director is allowed to move it about.
			if (maker != null)
				BotRoster.setOwner(bot.getName(), maker.getObjectId());
			return "Created " + bot.getName() + " (objId " + bot.getObjectId() + "): " + playerClass + " level " + level;
		} catch (IllegalArgumentException | IllegalStateException e) {
			log.warn("Could not create bot " + characterName, e);
			return "Could not create " + characterName + ": " + e.getMessage();
		}
	}

	/**
	 * Deletes a bot character and everything attached to it.
	 * <p>
	 * Two restrictions, and they answer different questions. The reserved bot accounts mean a mistyped name can never reach a real character, whoever
	 * types it. Ownership means one player cannot destroy another's: {@code //bot} is open to every account, so without this anybody could have
	 * emptied the world or deleted somebody else's companions. Staff are exempt, since moderating the server is what the access level is for.
	 *
	 * @param requester Whoever asked. Null for the server itself, which owns nothing and may delete anything.
	 */
	public static String delete(String characterName, Player requester) {
		if (bots().findSpawnedBot(characterName) != null)
			return characterName + " is spawned, despawn it first";

		int objectId = PlayerDAO.getPlayerIdByName(characterName);
		if (objectId == 0)
			return "No character found with name " + characterName;
		if (PlayerDAO.getAccountId(objectId) < PlayerBotCreationService.BOT_ACCOUNT_ID_BASE)
			return characterName + " is not on a bot account, refusing to delete it";
		if (!mayCommand(characterName, requester))
			return characterName + " belongs to somebody else";

		PlayerService.deletePlayerFromDB(objectId);
		return "Deleted " + characterName;
	}

	/** Runs the action on the spawned bot of that name, or answers that there is none. */
	private static String withBot(String characterName, Function<Player, String> action) {
		Player bot = bots().findSpawnedBot(characterName);
		return bot == null ? "No bot spawned with name " + characterName : action.apply(bot);
	}

	/** As {@link #withBot}, for what only a bot driven by {@link PlayerBotAI} can do. */
	private static String withBotAi(String characterName, BiFunction<Player, PlayerBotAI, String> action) {
		return withBot(characterName,
			bot -> bot.getAi() instanceof PlayerBotAI ai ? action.apply(bot, ai) : characterName + " has no bot AI attached");
	}

	private static PlayerBotService bots() {
		return PlayerBotService.getInstance();
	}

	/**
	 * @return true if this player may do as they like with that bot: the server may, staff may, and a player may with their own.
	 *         <p>
	 *         A bot the world made has no owner, so only staff and the server may destroy it — a player emptying a region they did not populate is
	 *         the same mistake as deleting somebody else's character, only larger.
	 */
	private static boolean mayCommand(String characterName, Player requester) {
		if (requester == null || requester.isStaff())
			return true;
		return BotRoster.ownerOf(characterName) == requester.getObjectId();
	}

	public static String despawn(String characterName) {
		Player bot = bots().findSpawnedBot(characterName);
		if (bot == null)
			return "No bot spawned with name " + characterName;

		if (!bots().takeOutOfWorld(bot))
			return "Could not despawn " + characterName + " (see server log)";
		bots().rememberRoster(); // taken out on purpose, so a restart leaves it out too
		boolean stillInWorld = World.getInstance().findVisibleObject(bot.getObjectId()) != null;
		return "Despawned " + bot.getName() + (stillInWorld ? " but it is still registered in the world!" : "");
	}

	/**
	 * Answers yes to a pending question window, which a bot can never answer itself since it has no client.
	 */
	public static String acceptRequest(String characterName, int questionId) {
		return withBot(characterName, bot -> bot.getResponseRequester().respond(questionId, 1) ? characterName + " accepted" : characterName + " has no pending request of that type");
	}

	/**
	 * Makes the bot attack the given player's target, or the player itself if it has no other target (handy during a duel).
	 */
	public static String attack(String characterName, Player commander) {
		return withBotAi(characterName, (bot, ai) -> {
			Creature target = commander.getTarget() instanceof Creature creature && !creature.equals(bot) ? creature : commander;
			ai.startAttacking(target);
			return characterName + " attacks " + target.getName();
		});
	}

	public static String stopAttacking(String characterName) {
		return withBotAi(characterName, (bot, ai) -> {
			ai.stopAttacking();
			return characterName + " stopped";
		});
	}

	/**
	 * Reads or sets what a bot is for: a resident of the place it stands in, or an adventurer.
	 * <p>
	 * A resident gains no experience, so the region it inhabits keeps inhabitants of its own level. Takes effect on the next spawn, since the flag is
	 * set as a bot enters the world; a spawned bot is switched over on the spot as well.
	 */
	public static String setKind(String characterName, Boolean resident) {
		if (resident == null)
			return characterName + " is " + (BotRoster.isResident(characterName) ? "a resident" : "an adventurer");
		BotRoster.setResident(characterName, resident);
		Player spawned = bots().findSpawnedBot(characterName);
		if (spawned != null)
			spawned.getCommonData().setNoExp(resident);
		return characterName + " is now " + (resident ? "a resident, and will not level any further" : "an adventurer, and levels normally");
	}

	/**
	 * Toggles whether the bot acts on its own (engaging hostiles in reach, fighting back) or only follows commands.
	 */
	public static String toggleAutonomy(String characterName) {
		return withBotAi(characterName, (bot, ai) -> {
			ai.setAutonomous(!ai.isAutonomous());
			return characterName + " autonomy " + (ai.isAutonomous() ? "on" : "off");
		});
	}

	/**
	 * Takes a bot off the ground and brings it back down, for watching flight work before anything decides to use it.
	 *
	 * @param heightArg How high to climb, in metres, or null for the default.
	 */
	public static String fly(String characterName, String heightArg) {
		float height = 0;
		if (heightArg != null) {
			try {
				height = Float.parseFloat(heightArg);
			} catch (NumberFormatException e) {
				return "Not a height: " + heightArg;
			}
		}
		float climb = height;
		return withBot(characterName, bot -> BotFlight.takeOff(bot, climb));
	}

	/**
	 * Flies a bot to where the commander stands, which is the one way to aim a flight at somewhere no bot can walk to: a terrace, a ledge, a rooftop.
	 */
	public static String flyTo(String characterName, Player commander) {
		return withBot(characterName, bot -> bot.getWorldId() != commander.getWorldId() ? characterName + " is on another map"
			: BotFlight.flyTo(bot, new Vector3f(commander.getX(), commander.getY(), commander.getZ())));
	}

	public static String land(String characterName) {
		return withBot(characterName, BotFlight::land);
	}

	/**
	 * Makes the bot walk to the given player's position.
	 */
	public static String come(String characterName, Player commander) {
		return withBotAi(characterName, (bot, ai) -> {
			ai.setAnchor(commander.getX(), commander.getY(), commander.getZ()); // being sent somewhere makes it the bot's new home
			if (!ai.walkTo(commander.getX(), commander.getY(), commander.getZ()))
				return characterName + " is blocked by an obstacle";
			return characterName + " is on its way";
		});
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check, so the vendor trip can be tested without farming first.
	 */
	public static String sell(String characterName) {
		return withBotAi(characterName, (bot, ai) -> {
			if (!BotVendorManager.hasJunk(bot))
				return characterName + " has nothing worth selling";
			return ai.forceSellTrip() ? characterName + " is heading to sell" : "No shop found on " + characterName + "'s map";
		});
	}

	/**
	 * Brings one spawned bot, or every one of them, up to what it would be given if it were created today.
	 * <p>
	 * Dressing happens once, at creation, so a world settled before a change to the wardrobe never sees it. This is the way to apply one without
	 * deleting the population and building it again — which would throw away every level, every home and every name it has.
	 * <p>
	 * Saved afterwards, one bot at a time rather than at the end, because a pass over a thousand inhabitants that is interrupted halfway should leave
	 * the half it finished written rather than nothing at all.
	 *
	 * @param characterName
	 *          One bot's name, or "all" for every spawned bot.
	 */
	public static String regear(String characterName) {
		if (!characterName.equalsIgnoreCase("all")) {
			return withBot(characterName, bot -> {
				String done = BotOutfitter.regear(bot);
				bots().save(bot);
				return done;
			});
		}
		int changed = 0;
		for (Player bot : bots().spawnedBots()) {
			try {
				String done = BotOutfitter.regear(bot);
				if (!done.endsWith("had nothing to change")) {
					log.info(done);
					changed++;
				}
				bots().save(bot);
			} catch (RuntimeException e) {
				log.error("Could not regear bot " + bot.getName(), e);
			}
		}
		return "Regeared " + changed + " of " + bots().spawnedBots().size() + " spawned bot(s)";
	}

	/**
	 * Lists what a spawned bot carries. Reads live memory, not the database, which only ever sees a bot when it despawns.
	 */
	public static String describeInventory(String characterName) {
		return withBot(characterName, bot -> {
			Storage inventory = bot.getInventory();
			StringBuilder sb = new StringBuilder(
				String.format("%s carries %d kinah in %d/%d slots:", bot.getName(), inventory.getKinah(), inventory.size(), inventory.getLimit()));
			inventory.getItems().forEach(item -> sb.append(String.format("%n  %dx %s", item.getItemCount(), item.getItemTemplate().getL10n())));
			return sb.toString();
		});
	}

	/** @return Which generated maps are currently held in memory. */
	public static String describeNavmeshes() {
		return NavmeshService.getInstance().describe();
	}

	public static String listSpawnedBots() {
		if (bots().spawnedBots().isEmpty())
			return "No bots spawned";
		StringBuilder sb = new StringBuilder("Spawned bots:");
		bots().spawnedBots().forEach(bot -> sb.append(String.format("%n  %s at %.1f %.1f %.1f%s", bot.getName(), bot.getX(), bot.getY(), bot.getZ(),
			bot.getMoveController().isInMove() ? " (moving)" : "")));
		return sb.toString();
	}

	/**
	 * Empties the world of bots and deletes their characters.
	 * <p>
	 * Only characters on the reserved bot accounts are touched; the deletion path refuses anything else, which is what keeps a mistyped command from
	 * reaching a real player. Nothing is done about the roster: it hangs off the characters themselves, so it goes with them.
	 * <p>
	 * For an ordinary player this means their own bots and nothing else — the refused count says how many were left alone. Staff and the server clear
	 * everything, which is what the command is for.
	 * <p>
	 * The map is why this takes an argument at all. Clearing is global and populating is not — it fills the map the commander is standing on — so a
	 * clear followed by a populate emptied a whole world and refilled one region of it. That asymmetry is easy to miss until three maps are gone and
	 * one has come back.
	 * <p>
	 * It also matters that most of a world is rarely the part anybody wants rebuilt. Wiping two hundred characters to look at the dozens that are
	 * high enough to show the thing being tested is a lot of levels, homes and names spent on nothing.
	 *
	 * @param requester Whoever asked. Null for the server carrying out a standing order.
	 * @param region A map id, part of a map's name, or null for everywhere. "here" is the map the requester stands on.
	 */
	public static String clear(Player requester, String region) {
		Integer onlyHere = null;
		if (region != null && !region.isBlank()) {
			onlyHere = resolveMap(region, requester);
			if (onlyHere == null)
				return "No map matches " + region;
		}
		// Only when the whole world is going, because this empties every map of bots and the per-name despawn below covers the scoped case anyway.
		if (onlyHere == null && (requester == null || requester.isStaff()))
			bots().despawnEverything();
		int deleted = 0, kept = 0, elsewhere = 0;
		for (String name : PlayerBotCreationService.botCharacterNames()) {
			if (!mayCommand(name, requester)) {
				kept++;
				continue;
			}
			// Asked before the despawn, because despawning is what takes the character out of the world and with it the cheap answer.
			if (onlyHere != null && !Objects.equals(onlyHere, mapOf(name))) {
				elsewhere++;
				continue;
			}
			despawn(name); // its own bot may well be in the world, and deleting refuses a spawned character
			if (delete(name, requester).startsWith("Deleted "))
				deleted++;
			else
				kept++;
		}
		return "Deleted " + deleted + " bot character(s)" + (kept > 0 ? ", " + kept + " left alone" : "")
			+ (elsewhere > 0 ? ", " + elsewhere + " on other maps untouched" : "");
	}

	/**
	 * @return Which map a bot character is on, or null if it cannot be found.
	 *         <p>
	 *         Memory first and the database second, and that order is not only about speed: a spawned bot may have walked to another map since it was
	 *         last written, and the saved position lags by up to a save sweep. What is in the world is the truth about where it is.
	 */
	private static Integer mapOf(String characterName) {
		Player spawned = bots().findSpawnedBot(characterName);
		if (spawned != null)
			return spawned.getWorldId();
		PlayerCommonData stored = PlayerDAO.loadPlayerCommonDataByName(characterName);
		return stored == null ? null : stored.getMapId();
	}

	/**
	 * Counts who is on a map, by faction and by what they are there for.
	 * <p>
	 * Read from the world rather than from the roster or the database, because what matters is who is actually standing on the map at this instant —
	 * the roster says what should come back after a restart and the database lags a save sweep behind.
	 *
	 * @param region A map id, or part of a map's name, or null for the commander's own map.
	 */
	public static String count(String region, Player commander) {
		Integer worldId = resolveMap(region, commander);
		if (worldId == null)
			return "No map matches " + region;

		int elyos = 0, asmodians = 0, companions = 0, players = 0;
		for (Player everyone : World.getInstance().getAllPlayers()) {
			if (everyone.getWorldId() != worldId)
				continue;
			if (!everyone.isBot())
				players++;
			else if (everyone.getAi() instanceof PlayerBotAI ai && ai.isOwned())
				companions++;
			else if (everyone.getRace() == Race.ELYOS)
				elyos++;
			else
				asmodians++;
		}
		String name = DataManager.WORLD_MAPS_DATA.getTemplate(worldId) == null ? String.valueOf(worldId)
			: DataManager.WORLD_MAPS_DATA.getTemplate(worldId).getName() + " (" + worldId + ")";
		// What the director is actually working towards, not what the region has characters for. The establishment was the figure here before, and it
		// answers a different question: a map asleep but for its villages was quoted as wanting nineteen when the review it answers to wanted ten.
		BotDirector.Band band = BotDirector.getInstance().band(worldId);
		return String.format("%s holds %d inhabitant(s): %d Elyos, %d Asmodian. %d companion(s), %d player(s). The region wants %d right now, out of "
			+ "%d with nobody online and %d with a player on it.", name, elyos + asmodians, elyos, asmodians, companions, players, band.now(),
			band.quiet(), band.crowded());
	}

	/**
	 * @return The map named, by id or by part of its name, or the commander's own when nothing is named or "here" is. Every command that takes a map
	 * answers to this one rule, so a blank means one thing everywhere: somebody standing in a region knows where they are and not what it is numbered.
	 */
	private static Integer resolveMap(String region, Player commander) {
		if (region == null || region.isBlank() || region.trim().equalsIgnoreCase("here"))
			return commander == null ? null : commander.getWorldId();
		try {
			return Integer.parseInt(region.trim());
		} catch (NumberFormatException e) {
			for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
				if (map.getName() != null && map.getName().toLowerCase().startsWith(region.trim().toLowerCase()))
					return map.getMapId();
			}
			return null;
		}
	}

	/**
	 * @return What the director concluded last time it looked, which is the one window onto a population that is mostly asleep: {@link #count} counts
	 *         what is in the world, and says nothing about what should be.
	 * @param region A map id, part of a map's name or "here", or null for every map. A world of eighteen populated maps answers in eighteen lines,
	 *          which is a wall in a chat window when seventeen of them are not the one being looked at.
	 */
	public static String describePool(String region, Player commander) {
		List<String> review = BotDirector.getInstance().lastReview();
		if (review.isEmpty())
			return "No population review has run yet. The first one is half a minute after startup.";
		if (region == null || region.isBlank())
			return String.join(System.lineSeparator(), review);
		Integer worldId = resolveMap(region, commander);
		if (worldId == null)
			return "No map matches " + region;
		List<String> only = review.stream().filter(line -> line.startsWith("map " + worldId + ":")).toList();
		if (only.isEmpty())
			return "Nothing was reviewed for map " + worldId + ". It may hold no inhabitants, or the name may be a map id this world does not have.";
		return String.join(System.lineSeparator(), only);
	}

	public static String despawnAll() {
		int count = bots().despawnEverything();
		bots().rememberRoster(); // emptied by hand, so the world comes back empty
		return "Despawned " + count + " bot(s)";
	}

}
