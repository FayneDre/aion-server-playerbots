package com.aionemu.gameserver.playerbot.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * What the module remembers about a bot outside the bot itself, in static maps keyed by an id, and what happens to it when the bot leaves the world.
 * <p>
 * Read from the sources, because the danger is the map nobody thought about: a new one added for a feature, keyed by the bot, cleared by nothing, so a
 * bot woken from the pool hours later starts its new life with its old one's memory. Every such map is listed below with what becomes of it, and a
 * map that is not listed fails the test until somebody decides. Constants (names in capitals) are data, not memory, and are left out.
 */
class BotMemoryTest {

	private static final Path SOURCES = Path.of("src/com/aionemu/gameserver/playerbot");

	/** Forgotten by the bot's own {@code forget}, which {@link PlayerBotLeaveWorldService} calls: checked against the method's body. */
	private static final List<String> FORGOTTEN = List.of("BotSkillManager.castAttempts", "SorcererPlaybook.draining", "SorcererPlaybook.aetherFlames",
		"SorcererPlaybook.aimedSince", "BotTrades.gifts", "BotRequests.lastDuel", "BotTransports.watches", "BotInstances.waitingSince");

	/** Everything else keyed by an id, and why it is not forgotten there. */
	private static final Map<String, String> NOT_FORGOTTEN_THERE = Map.ofEntries(
		// cleared by the AI when it is told DESPAWNED, which leaveWorld does first
		Map.entry("BotConvalescence.claimed", "released by PlayerBotAI.handleDespawned"),
		Map.entry("BotTargetRegistry.owners", "released by PlayerBotAI.cancelCombat on despawn"),
		Map.entry("BotFlight.inTheAir", "a flight ends when its bot is despawned"),
		// a life of their own, longer than one stay in the world
		Map.entry("BotRecruiter.recruits", "a recruit is sent home, and dropped, by the recruiter"),
		Map.entry("BotVisitors.visits", "swept by BotVisitors once the visitor is out of the world"),
		// the character's, kept across sleeps on purpose
		Map.entry("BotDay.unreachableUntil", "a merchant's stall found unreachable stays so for its time"),
		Map.entry("BotChat.spokeAt", "a character just heard in a channel stays quiet, asleep or not"),
		Map.entry("BotSpeech.spokeAt", "a character just heard shouting stays quiet, asleep or not"),
		Map.entry("BotStores.shelves", "a keeper's stock lasts the day, across its shifts"),
		// keyed by something that is not a bot
		Map.entry("BotDay.rotas", "by map"), Map.entry("BotVisitors.lastWatched", "by map"), Map.entry("BotStores.stallsByMap", "by map"),
		Map.entry("BotVendorManager.tripsGivenUp", "by map"), Map.entry("BotSpeech.nextAdvert", "by map"), Map.entry("BotSpeech.nextCall", "by map"),
		Map.entry("BotSkillManager.targetHealth", "by target"), Map.entry("BotSkillManager.cleanseTries", "by creature and effect"),
		Map.entry("BotSquads.squads", "by team"), Map.entry("LeaderGlides.watches", "by the leader watched, not by the follower"),
		Map.entry("BotPlaces.placesByMap", "by map"), Map.entry("BotPlaces.bandByMap", "by map"), Map.entry("BotPlaces.levelsByMap", "by map"),
		Map.entry("BotPlaces.homesByMap", "by map"), Map.entry("BotPlaces.obelisksByMap", "by map"), Map.entry("BotPlaces.ownersByMap", "by map"),
		Map.entry("BotPlaces.visitSpots", "by map"), Map.entry("BotPlaces.unvisitedUntil", "by map"),
		Map.entry("BotPresence.establishmentByMap", "by map"), Map.entry("BotPresence.civicByMap", "by map"),
		Map.entry("BotPresence.fieldByMap", "by map"), Map.entry("BotPresence.busyFieldByMap", "by map"), Map.entry("BotPresence.townByMap", "by map"),
		Map.entry("StrandedPlaces.byMap", "by map"), Map.entry("TownServices.byMap", "by map"));

	/** A static field whose type is a map or a set keyed by Integer or Long; the first name, then the others declared on the same line. */
	private static final Pattern FIELD = Pattern
		.compile("static (?:final )?(?:Map|Set|ConcurrentMap|ConcurrentHashMap)<(?:Integer|Long)[,>][^(=;]*?\\s(\\w+)\\s*[=;](.*)");
	private static final Pattern MORE_ON_THE_LINE = Pattern.compile(",\\s*(\\w+)\\s*=\\s*new ");

	@Test
	void everyMapKeyedByAnIdIsAccountedFor() throws IOException {
		Map<String, Path> found = mapsKeyedById();
		List<String> unknown = new ArrayList<>();
		for (String field : found.keySet())
			if (!FORGOTTEN.contains(field) && !NOT_FORGOTTEN_THERE.containsKey(field))
				unknown.add(field);
		assertEquals(List.of(), unknown, "A static map keyed by an id that nobody has classified: if it is keyed by a bot, forget it in a forget(Player) "
			+ "that PlayerBotLeaveWorldService calls and add it to FORGOTTEN; otherwise add it to NOT_FORGOTTEN_THERE with the reason");
		List<String> gone = new ArrayList<>(FORGOTTEN);
		gone.addAll(NOT_FORGOTTEN_THERE.keySet());
		gone.removeIf(found::containsKey);
		assertEquals(List.of(), gone, "Listed here but no longer in the sources: take them off the lists");
	}

	@Test
	void everyForgottenMapIsClearedByItsClassForget() throws IOException {
		Map<String, Path> found = mapsKeyedById();
		for (String field : FORGOTTEN) {
			Path file = found.get(field);
			if (file == null)
				fail(field + " is not in the sources any more");
			String name = field.substring(field.indexOf('.') + 1);
			String body = forgetBody(Files.readString(file).replace("
", "
")); // a checkout with CRLF endings is as good as one without
			assertTrue(body != null && body.contains(name), field + " is listed as forgotten, but its class's forget(Player) does not mention it");
		}
	}

	private static Map<String, Path> mapsKeyedById() throws IOException {
		Map<String, Path> found = new TreeMap<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String className = file.getFileName().toString().replace(".java", "");
				for (String line : Files.readAllLines(file)) {
					Matcher field = FIELD.matcher(line);
					if (!field.find())
						continue;
					List<String> names = new ArrayList<>(List.of(field.group(1)));
					Matcher more = MORE_ON_THE_LINE.matcher(field.group(2));
					while (more.find())
						names.add(more.group(1));
					for (String name : names)
						if (!name.matches("[A-Z0-9_]+"))
							found.put(className + "." + name, file);
				}
			}
		}
		return found;
	}

	/** @return The body of the class's {@code forget(Player ...)}, up to the closing brace at its own indent, or null when it has none. */
	private static String forgetBody(String source) {
		Matcher start = Pattern.compile("\n(\t+)[^\n]*void forget\\(Player [^\n]*\\{\n").matcher(source);
		if (!start.find())
			return null;
		int end = source.indexOf("\n" + start.group(1) + "}", start.end());
		return end < 0 ? null : source.substring(start.end(), end);
	}
}
