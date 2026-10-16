package com.aionemu.gameserver.playerbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * {@code //bot} commands read from a file instead of typed in the chat, so that a test can be set up and run with no game client at all.
 * <p>
 * Every few seconds the server looks for {@value #FILE} in its own folder; each line is run as the words after {@code //bot}, oldest first, and the
 * answer goes to the log as {@code BOT INBOX}. {@code tools/bot-command.ps1} appends to the file. There is no caller, so only the commands that need no
 * position are taken here, plus {@code spawn <name> near <bot>}, which uses a bot in place of the player a spawn normally stands next to, and
 * {@code teleport <name> <map> <x> <y> <z>}, which a player would do by walking.
 * The plan is {@code docs/test-harness-plan.md}.
 */
public final class BotInbox {

	private static final Logger log = LoggerFactory.getLogger(BotInbox.class);

	/** The file read, relative to the game server's working folder. */
	public static final String FILE = "bot-commands.txt";
	/** How often the file is looked for. A test script waits on the log, so a few seconds is invisible to it and costs nothing when there is no file. */
	private static final long LOOK_MILLIS = 3000;

	private static final AtomicBoolean reading = new AtomicBoolean();

	private BotInbox() {
	}

	public static void start() {
		BotScheduler.getInstance().scheduleAtFixedRate(BotInbox::look, LOOK_MILLIS, LOOK_MILLIS);
		log.info("Reading //bot commands from {}", Path.of(FILE).toAbsolutePath());
	}

	private static void look() {
		if (!Files.exists(Path.of(FILE)) || !reading.compareAndSet(false, true))
			return;
		// on the lane that spawns and despawns bots, since half of what arrives here does exactly that, and that lane runs one thing at a time
		BotScheduler.getInstance().enterOrLeaveWorld(() -> {
			try {
				readAndRun();
			} finally {
				reading.set(false);
			}
		});
	}

	private static void readAndRun() {
		// moved aside before reading, so a line appended while these run waits for the next look instead of being lost with this file
		Path taken = Path.of(FILE + ".taken");
		List<String> lines;
		try {
			Files.move(Path.of(FILE), taken, StandardCopyOption.REPLACE_EXISTING);
			lines = Files.readAllLines(taken, StandardCharsets.UTF_8);
			Files.delete(taken);
		} catch (IOException e) {
			log.warn("Could not read " + FILE, e);
			return;
		}
		for (String line : lines) {
			String command = line.strip().replaceFirst("^(//)?bot\\s+", "");
			if (command.isEmpty() || command.startsWith("#"))
				continue;
			String answer;
			try {
				answer = run(command.split("\\s+"));
			} catch (RuntimeException e) {
				log.error("BOT INBOX failed on: " + command, e);
				continue;
			}
			log.info("BOT INBOX > {}", command);
			for (String answerLine : answer.split("\\R"))
				log.info("BOT INBOX < {}", answerLine);
		}
	}

	/** @return The answer to one command, given as the words after {@code //bot}. */
	static String run(String[] words) {
		String argument = words.length > 1 ? String.join(" ", Arrays.copyOfRange(words, 1, words.length)) : null;
		String name = words.length > 1 ? words[1] : null;
		return switch (words[0].toLowerCase()) {
			case "squad" -> BotCommands.squad(Arrays.copyOfRange(words, 1, words.length));
			case "note" -> BotDiagnostics.note(argument, null);
			case "version" -> BotDiagnostics.version();
			case "debug" -> BotDiagnostics.debug(argument);
			case "position", "positioning" -> BotCommands.positioning(argument);
			case "list" -> BotCommands.listSpawnedBots();
			case "create" -> words.length < 5 ? "Usage: create <name> <class> <level> <race>" : create(words);
			case "attack" -> words.length < 3 ? "Usage: attack <name> <monster name>" : attack(words[1], String.join(" ", Arrays.copyOfRange(words, 2, words.length)));
			case "teleport" -> words.length < 6 ? "Usage: teleport <name> <map> <x> <y> <z>" : teleport(words);
			case "spawn" -> words.length < 4 || !words[2].equalsIgnoreCase("near") ? "Usage: spawn <name> near <spawned bot>" : spawnNear(words[1], words[3]);
			case "despawn" -> name == null ? "Usage: despawn <name>" : BotCommands.despawn(name);
			case "delete" -> name == null ? "Usage: delete <name>" : BotCommands.delete(name, null);
			case "stop" -> name == null ? "Usage: stop <name>" : BotCommands.stopAttacking(name);
			case "auto" -> name == null ? "Usage: auto <name>" : BotCommands.toggleAutonomy(name);
			default -> "Not taken from the inbox: " + words[0] + ". See docs/bot-commands.md, section Testing.";
		};
	}

	private static String create(String[] words) {
		try {
			return BotCommands.create(words[1], words[2], Integer.parseInt(words[3]), words[4], null);
		} catch (NumberFormatException e) {
			return "Level must be a number";
		}
	}

	/**
	 * Sends a bot at the nearest monster of that name it can see, whatever its level: what a player does by clicking one, and the way to put a squad on a
	 * boss its leader would never pick for itself.
	 */
	private static String attack(String name, String monsterName) {
		Player bot = PlayerBotService.getInstance().findSpawnedBot(name);
		if (bot == null)
			return "No bot spawned with name " + name;
		if (!(bot.getAi() instanceof PlayerBotAI ai))
			return name + " has no bot AI attached";
		Npc[] nearest = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.isDead() && npc.getName().equalsIgnoreCase(monsterName)
				&& (nearest[0] == null || PositionUtil.getDistance(bot, npc) < PositionUtil.getDistance(bot, nearest[0])))
				nearest[0] = npc;
		});
		if (nearest[0] == null)
			return name + " sees no living " + monsterName;
		ai.startAttacking(nearest[0]);
		return String.format("%s attacks %s (level %d, %s) at %.0f m", name, monsterName, nearest[0].getLevel(), nearest[0].getObjectTemplate().getRating(),
			PositionUtil.getDistance(bot, nearest[0]));
	}

	/** Puts a spawned bot somewhere and makes that its camp, which is how a squad is taken to the ground a test is about. */
	private static String teleport(String[] words) {
		Player bot = PlayerBotService.getInstance().findSpawnedBot(words[1]);
		if (bot == null)
			return "No bot spawned with name " + words[1];
		if (!(bot.getAi() instanceof PlayerBotAI ai))
			return words[1] + " has no bot AI attached";
		try {
			int worldId = Integer.parseInt(words[2]);
			float x = Float.parseFloat(words[3]), y = Float.parseFloat(words[4]), z = Float.parseFloat(words[5]);
			// onto the floor the mesh has there, since coordinates read off spawn data or a map are seldom exactly on it
			Vector3f ground = NavmeshService.getInstance().groundNear(worldId, x, y, z);
			if (ground != null) {
				x = ground.getX();
				y = ground.getY();
				z = ground.getZ();
			}
			TeleportService.teleportTo(bot, worldId, x, y, z);
			ai.setAnchor(x, y, z);
			return words[1] + " teleported to map " + worldId + " at " + x + " " + y + " " + z + ", which is now its camp";
		} catch (NumberFormatException e) {
			return "Map and coordinates must be numbers";
		}
	}

	private static String spawnNear(String name, String nearName) {
		Player near = PlayerBotService.getInstance().findSpawnedBot(nearName);
		return near == null ? "No bot spawned with name " + nearName : BotCommands.spawn(name, near);
	}
}
