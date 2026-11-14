package com.aionemu.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.Config;
import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.teleport.TelelocationTemplate;
import com.aionemu.gameserver.playerbot.world.BotDirector;
import com.aionemu.gameserver.playerbot.world.BotLoadGuard;
import com.aionemu.gameserver.playerbot.world.DensityMode;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldMap;

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

	/**
	 * What the launcher can rely on, raised by one whenever a command of this channel or a setting the launcher writes is added or changed, so a launcher
	 * can tell a server too old for it instead of showing empty lists. A server that does not know {@code version} is level 0. {@code deploy.ps1} reads
	 * this line into the install's {@code manifest.json}, for a launcher to read while the server is stopped.
	 * <ul>
	 * <li>1 (6 November 2026): {@code version}, {@code reload-config}, {@code teleport}, {@code destinations}, and the bot settings of
	 * {@code playerbot.properties} (experience, PvP shares and level band, chat).</li>
	 * <li>2 (8 November 2026): {@code population}, and the density settings ({@code density.mode}, {@code density.percent}) applied by
	 * {@code reload-config} at the next review.</li>
	 * </ul>
	 */
	public static final int API_LEVEL = 2;

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
		} catch (NoSuchFileException e) {
			// taken back by a requester that stopped waiting: an answer now would stay in the folder until the next start
			return;
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
			case "version" -> version(ServerRelease.version(), GameServer.versionInfo.getRevision());
			case "reload-config" -> reloadConfig();
			case "teleport" -> words.length == 6 ? teleport(words) : "error usage: teleport <character id> <map id> <x> <y> <z>";
			case "destinations" -> destinations();
			case "population" -> population(BotDirector.getInstance().lastTotals(), PlayerBotConfig.DENSITY_MODE, PlayerBotConfig.DENSITY_PERCENT,
				PlayerBotConfig.GUARD_ENABLE, BotLoadGuard.getInstance().causes(), BotLoadGuard.getInstance().lastReading());
			default -> "error unknown command " + words[0];
		};
	}

	/** One {@code key value} line each: {@code version} (a release, or {@code dev}), {@code revision} (as {@code //bot version} shows it), {@code api}. */
	static String version(String release, String revision) {
		return "ok\nversion " + release + "\nrevision " + (revision == null ? "unknown" : revision) + "\napi " + API_LEVEL;
	}

	/**
	 * One {@code key value} line each: {@code mode} and {@code percent} (the density in force), {@code awake}, {@code wanted}, {@code asleep} and
	 * {@code short} (the last review's totals; absent before the first review, which comes once the startup is done), {@code guard} ({@code open},
	 * {@code off}, or {@code held} followed by its causes), {@code heap} (the percent after a collection, or {@code unknown}) and {@code route-wait} (in
	 * seconds).
	 */
	static String population(BotDirector.Totals totals, DensityMode mode, int percent, boolean guardOn, Set<BotLoadGuard.Cause> causes,
		BotLoadGuard.Reading reading) {
		List<String> lines = new ArrayList<>(List.of("ok", "mode " + mode, "percent " + percent));
		if (totals != null)
			lines.addAll(List.of("awake " + totals.awake(), "wanted " + totals.wanted(), "asleep " + totals.asleep(), "short " + totals.shortAndEmpty()));
		String guard = !guardOn ? "off" : causes.isEmpty() ? "open" : "held " + causes.stream().map(Enum::name).collect(Collectors.joining(" "));
		lines.add("guard " + guard);
		lines.add("heap " + (reading.heapPercent() < 0 ? "unknown" : reading.heapPercent()));
		lines.add("route-wait " + reading.routeWaitMillis() / 1000);
		return String.join("\n", lines);
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
		if (!isOpenWorld(mapId))
			return "error not an open-world map " + mapId;
		TeleportService.teleportTo(player, mapId, x, y, z);
		log.info("Admin channel: {} teleported to map {} {} {} {}", player.getName(), mapId, x, y, z);
		return "ok";
	}

	/** An instance map has no spawns of its own until an instance is made for the player, which a plain teleport does not do. */
	private static boolean isOpenWorld(int mapId) {
		WorldMap map = World.getInstance().getWorldMap(mapId);
		return map != null && !map.isInstanceType();
	}

	/** One line a destination: location id, map id, x, y, z, name, separated by tabs. */
	private static String destinations() {
		List<String> lines = DataManager.TELELOCATION_DATA.getAll().stream().filter(loc -> isOpenWorld(loc.getMapId())).sorted((a, b) -> Integer.compare(a.getLocId(), b.getLocId()))
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
