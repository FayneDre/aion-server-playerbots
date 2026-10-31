package com.aionemu.gameserver.playerbot.social;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The words of the bots' chat in one language, read from {@code bot_chat/chat_<language>.txt} in the jar (the files are {@code game-server/data/bot_chat}, packaged as resources
 * because a deploy copies the jar and the handlers and nothing else of {@code data/}).
 * <p>
 * A file is a list of sections: {@code [name]} on a line, then one thing to say a line. {@code %s} and the like are filled in by the code that draws the line, and
 * {@code <empty>} is the alternative of saying nothing there. A line that starts with {@code #} is a comment. A line written twice is twice as likely: that is how the weights are.
 */
final class ChatText {

	/** What a line holds when the alternative is to say nothing. */
	static final String EMPTY_MARK = "<empty>";

	private static final Map<String, ChatText> loaded = new ConcurrentHashMap<>();

	private final String language;
	private final Map<String, List<String>> sections;

	private ChatText(String language, Map<String, List<String>> sections) {
		this.language = language;
		this.sections = sections;
	}

	/** @return the words in this language ({@code fr}, {@code en}), read once. */
	static ChatText of(String language) {
		return loaded.computeIfAbsent(language.toLowerCase(), ChatText::read);
	}

	private static ChatText read(String language) {
		String resource = "/bot_chat/chat_" + language + ".txt";
		try (InputStream stream = ChatText.class.getResourceAsStream(resource)) {
			if (stream == null)
				throw new IllegalStateException("No " + resource + " in the jar: the bots' words of language " + language + " are missing");
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				return parse(language, reader.lines().toList());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** @return the sections of a file's lines, in the order they are written. */
	static ChatText parse(String language, List<String> fileLines) {
		Map<String, List<String>> sections = new LinkedHashMap<>();
		List<String> current = null;
		for (String raw : fileLines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			if (line.startsWith("[") && line.endsWith("]")) {
				current = new ArrayList<>();
				if (sections.put(line.substring(1, line.length() - 1), current) != null)
					throw new IllegalStateException("Section " + line + " is written twice in the " + language + " chat file");
				continue;
			}
			if (current == null)
				throw new IllegalStateException("A line before any [section] in the " + language + " chat file: " + line);
			current.add(line.equals(EMPTY_MARK) ? "" : line);
		}
		sections.replaceAll((name, lines) -> List.copyOf(lines));
		return new ChatText(language, sections);
	}

	/** @return the lines of a section, which is never empty. */
	List<String> section(String name) {
		List<String> lines = sections.get(name);
		if (lines == null || lines.isEmpty())
			throw new IllegalStateException("The " + language + " chat file has no section [" + name + "]");
		return lines;
	}

	String language() {
		return language;
	}

	Set<String> names() {
		return sections.keySet();
	}
}
