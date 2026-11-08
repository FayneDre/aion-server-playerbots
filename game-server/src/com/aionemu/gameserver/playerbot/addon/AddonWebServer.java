package com.aionemu.gameserver.playerbot.addon;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.AionConnection;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves the pages the interface addons show in their window, the client's embedded browser loading them.
 * <p>
 * The browser carries nothing of the game session (the publisher's web sign-on it was built for no longer exists), so an addon names its character in
 * the address ({@code ?name=}) and the page belongs to that character only when the request comes from the address its game connection comes from:
 * another machine cannot read someone else's page by typing a name. On the server's own machine, its addresses count as one.
 */
public final class AddonWebServer {

	/**
	 * What the pages offer the addons, raised when an addon of an earlier level would no longer work with them, or a new addon needs pages an
	 * earlier server lacks. The installation's manifest.json carries it (deploy.ps1, package-release.ps1 read this line), for the launcher to
	 * compare with the level an addons release asks for; {@code /addon/version} answers it too.
	 */
	public static final int API_LEVEL = 1;

	private static final Logger log = LoggerFactory.getLogger(AddonWebServer.class);
	private static final String COMBAT_METER = "/addon/combat-meter";
	private static final String VERSION = "/addon/version";

	private static HttpServer server;

	private AddonWebServer() {
	}

	public static synchronized void start() {
		if (PlayerBotConfig.ADDON_PORT <= 0 || server != null)
			return;
		try {
			server = HttpServer.create(new InetSocketAddress(PlayerBotConfig.ADDON_PORT), 16);
		} catch (IOException e) {
			log.error("Cannot open port {} for the addon pages, so the addons have none", PlayerBotConfig.ADDON_PORT, e);
			return;
		}
		server.createContext(COMBAT_METER, AddonWebServer::combatMeter);
		server.createContext(VERSION, AddonWebServer::version);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		log.info("Addon pages: served on port {}", PlayerBotConfig.ADDON_PORT);
	}

	/** The pages' level, to anyone: it says nothing of a player. */
	private static void version(HttpExchange exchange) throws IOException {
		try (exchange) {
			send(exchange, 200, "application/json", "{\"apiLevel\":" + API_LEVEL + "}");
		}
	}

	private static void combatMeter(HttpExchange exchange) throws IOException {
		try (exchange) {
			Optional<Player> viewer = viewerOf(exchange);
			String path = exchange.getRequestURI().getPath();
			if (viewer.isEmpty()) {
				log.info("Addon pages: no player matches {} from {}", exchange.getRequestURI(), remoteAddress(exchange));
				send(exchange, 403, "text/html", page("Combat Meter", "<p>The server does not recognise this character.</p>"));
			} else if (path.equals(COMBAT_METER + "/data")) {
				send(exchange, 200, "application/json", CombatMeter.json(viewer.get()));
			} else if (path.equals(COMBAT_METER + "/reset")) {
				CombatMeter.reset(viewer.get());
				send(exchange, 200, "application/json", CombatMeter.json(viewer.get()));
			} else {
				// the page itself, loaded at each opening of the window: what a tester's empty window is checked against
				log.info("Addon pages: combat meter for {} from {}", viewer.get().getName(), remoteAddress(exchange));
				send(exchange, 200, "text/html", CombatMeterPage.html());
			}
		}
	}

	/** The character the request names, when it is a player online whose game connection comes from where the request does. */
	private static Optional<Player> viewerOf(HttpExchange exchange) {
		String name = parameter(exchange, "name");
		if (name == null || name.isEmpty())
			return Optional.empty();
		Player player = World.getInstance().getPlayer(name);
		if (player == null || player.isBot())
			return Optional.empty();
		AionConnection connection = player.getClientConnection();
		return connection != null && sameMachine(connection.getIP(), remoteAddress(exchange)) ? Optional.of(player) : Optional.empty();
	}

	/**
	 * Whether two addresses are one machine: the same address, or two of the server's own. A player on the server's machine reaches the game by its
	 * network address and the page by the loopback one.
	 */
	private static boolean sameMachine(String gameAddress, String pageAddress) {
		if (gameAddress.equals(pageAddress))
			return true;
		try {
			return isThisMachine(InetAddress.getByName(gameAddress)) && isThisMachine(InetAddress.getByName(pageAddress));
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean isThisMachine(InetAddress address) throws SocketException {
		return address.isLoopbackAddress() || address.isAnyLocalAddress() || NetworkInterface.getByInetAddress(address) != null;
	}

	private static String parameter(HttpExchange exchange, String wanted) {
		String query = exchange.getRequestURI().getRawQuery();
		if (query == null)
			return null;
		for (String pair : query.split("&")) {
			int equals = pair.indexOf('=');
			if (equals > 0 && pair.substring(0, equals).equals(wanted))
				return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
		}
		return null;
	}

	private static String remoteAddress(HttpExchange exchange) {
		return exchange.getRemoteAddress().getAddress().getHostAddress();
	}

	private static String page(String title, String content) {
		return """
			<!doctype html>
			<html><head><meta charset="utf-8"><title>%s</title>
			<style>body{margin:8px;background:#10131c;color:#e8dcc0;font:13px Arial,sans-serif}</style></head>
			<body>%s</body></html>
			""".formatted(escape(title), content);
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
