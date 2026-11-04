package com.aionemu.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.Config;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.teleport.TelelocationTemplate;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.world.World;

/**
 * Lets a program on the same machine, the launcher, ask the running server for a few things, and read the answer.
 * <p>
 * A request is a file {@code admin/<id>.request} of one line, the command and its words; the answer is {@code admin/<id>.response}, whose first line is
 * {@code ok} or {@code error <why>}, the rest being data. The requester writes {@code <id>.tmp} and renames it, so the server never reads half a request;
 * it removes the response once read.
 * <p>
 * Same reasoning as {@link ShutdownRequestWatcher}: no port, no key, nothing to expose. Whoever can write in the server's folder can already replace the
 * jar, so a file adds no power. The commands are the few the launcher needs, each acting on one thing; there is no way to run a chat command.
 * <p>
 * The bot inbox ({@code bot-commands.txt}) is another thing: it takes bot commands only and answers in the log.
 */
public final class AdminChannel {

	private static final Logger log = LoggerFactory.getLogger(AdminChannel.class);
	/** Relative to the working directory, which is the game-server folder. */
	private static final Path DIR = Path.of("admin");
	private static final String REQUEST = ".request";
	private static final long POLL_MILLIS = 500;
	/** A request id is a plain name: it becomes a file name. */
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

	private AdminChannel() {
	}

	public static void start() {
		try {
			Files.createDirectories(DIR);
			// what a previous run left would be answered by this one, long after anyone waits for it
			try (Stream<Path> old = Files.list(DIR)) {
				for (Path file : old.toList())
					Files.deleteIfExists(file);
			}
		} catch (IOException e) {
			log.error("Cannot prepare {}, so the admin channel is off", DIR.toAbsolutePath(), e);
			return;
		}
		Thread.ofVirtual().name("admin-channel").start(AdminChannel::watch);
		log.info("Admin channel: requests are read from {}", DIR.toAbsolutePath());
	}

	private static void watch() {
		while (!Thread.currentThread().isInterrupted()) {
			try (Stream<Path> files = Files.list(DIR)) {
				for (Path file : files.filter(f -> f.getFileName().toString().endsWith(REQUEST)).toList())
					answer(file);
			} catch (IOException e) {
				log.warn("Cannot read {}", DIR.toAbsolutePath(), e);
			}
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static void answer(Path request) {
		String name = request.getFileName().toString();
		String id = name.substring(0, name.length() - REQUEST.length());
		if (!ID.matcher(id).matches()) {
			deleteQuietly(request);
			return;
		}
		String response;
		try {
			response = run(Files.readString(request, StandardCharsets.UTF_8).strip());
		} catch (IOException | RuntimeException e) {
			log.warn("Admin request {} failed", name, e);
			response = "error " + e.getMessage();
		}
		deleteQuietly(request);
		try {
			Path tmp = DIR.resolve(id + ".tmp");
			Files.writeString(tmp, response + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, DIR.resolve(id + ".response"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log.warn("Cannot write the answer to {}", name, e);
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("Cannot remove {}", file, e);
		}
	}

	static String run(String line) {
		String[] words = line.split("\\s+");
		return switch (words[0]) {
			case "ping" -> "ok";
			case "reload-config" -> reloadConfig();
			case "teleport" -> words.length == 6 ? teleport(words) : "error usage: teleport <character id> <map id> <x> <y> <z>";
			case "destinations" -> destinations();
			default -> "error unknown command " + words[0];
		};
	}

	/** The same as {@code //reload config}: settings read at each use, the rates among them, apply at once. */
	private static String reloadConfig() {
		Config.load();
		log.info("Admin channel: configuration reloaded");
		return "ok";
	}

	private static String teleport(String[] words) {
		int objectId, mapId;
		float x, y, z;
		try {
			objectId = Integer.parseInt(words[1]);
			mapId = Integer.parseInt(words[2]);
			x = Float.parseFloat(words[3]);
			y = Float.parseFloat(words[4]);
			z = Float.parseFloat(words[5]);
		} catch (NumberFormatException e) {
			return "error numbers expected";
		}
		Player player = World.getInstance().getPlayer(objectId);
		if (player == null || player.isBot() || !player.isOnline())
			return "error not in game";
		if (World.getInstance().getWorldMap(mapId) == null)
			return "error unknown map " + mapId;
		TeleportService.teleportTo(player, mapId, x, y, z);
		log.info("Admin channel: {} teleported to map {} {} {} {}", player.getName(), mapId, x, y, z);
		return "ok";
	}

	/** One line a destination: location id, map id, x, y, z, name, separated by tabs. */
	private static String destinations() {
		List<String> lines = DataManager.TELELOCATION_DATA.getAll().stream().sorted((a, b) -> Integer.compare(a.getLocId(), b.getLocId()))
			.map(AdminChannel::describe).toList();
		return "ok\n" + String.join("\n", lines);
	}

	private static String describe(TelelocationTemplate loc) {
		// the name in the data file: the client's string id (getL10n) is only a code that the client itself turns into text
		String name = loc.getName();
		return loc.getLocId() + "\t" + loc.getMapId() + "\t" + loc.getX() + "\t" + loc.getY() + "\t" + loc.getZ() + "\t"
			+ (name == null ? "" : name.replace('\t', ' ').replace('\n', ' '));
	}
}
