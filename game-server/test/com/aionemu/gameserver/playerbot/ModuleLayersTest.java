package com.aionemu.gameserver.playerbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Which package of the bot module may know which. Read from the sources' imports, because a direction that is only a rule in a document comes back the
 * first time a fix is quicker written the wrong way round: the class playbooks reached into the AI that calls them nine times before 27 October 2026.
 * <p>
 * Each rule names a package, every package under it included, and a package it must not import. Imports only: a fully qualified name in the code would
 * pass, and is not how this code base is written. See {@code docs/architecture/module-structure-plan.md}.
 */
class ModuleLayersTest {

	private static final Path SOURCES = Path.of("src/com/aionemu/gameserver/playerbot");
	private static final String MODULE = "com.aionemu.gameserver.playerbot.";

	private record Rule(String from, String mustNotImport) {
	}

	private static final List<Rule> RULES = List.of(
		// the AI calls the playbooks and owns what they remember (BotCombatMemory): they never call back into it
		new Rule("combat", "ai"),
		// the offline toolchain uses the server's classes, never the other way round: nothing that runs in the game server imports it
		new Rule("ai", "navmesh.tool"), new Rule("combat", "navmesh.tool"), new Rule("economy", "navmesh.tool"), new Rule("lifecycle", "navmesh.tool"),
		new Rule("movement", "navmesh.tool"), new Rule("positioning", "navmesh.tool"), new Rule("social", "navmesh.tool"), new Rule("world", "navmesh.tool"),
		new Rule("navmesh", "navmesh.tool"));

	@Test
	void noPackageImportsWhatItMustNot() throws IOException {
		List<String> broken = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String pkg = SOURCES.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
				for (String line : Files.readAllLines(file)) {
					if (!line.startsWith("import " + MODULE))
						continue;
					String imported = line.substring(("import " + MODULE).length());
					for (Rule rule : RULES)
						if (isIn(pkg, rule.from()) && isIn(imported, rule.mustNotImport()))
							broken.add(SOURCES.relativize(file) + ": " + line.trim() + " (" + rule.from() + " must not import " + rule.mustNotImport() + ")");
				}
			}
		}
		assertEquals(List.of(), broken);
	}

	/** @return true if a package, or a class name under it, is the given package or one of its sub packages. */
	private static boolean isIn(String name, String pkg) {
		return name.equals(pkg) || name.startsWith(pkg + ".");
	}
}
