package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ChatTextTest {

	private static final Object[] NONE = {};
	private static final Object[] PLACE = { "Morheim" };
	private static final Object[] GROUP = { "Tank 33", "tank", "Dark Poeta", 33 };

	/** Every kind of line the code draws, with the arguments it draws it with: each language's file must hold all of them and every line must format with its own. */
	private static final Map<String, Object[]> SHAPES = Map.ofEntries(Map.entry("sell.opening", NONE), Map.entry("sell.closing", PLACE),
		Map.entry("advert", new Object[] { "Store", "Sanctum" }), Map.entry("group.opening", NONE), Map.entry("group.closing", NONE), Map.entry("role.tank", NONE),
		Map.entry("role.healer", NONE), Map.entry("role.support", NONE), Map.entry("role.damage", NONE), Map.entry("greeting.morning", PLACE),
		Map.entry("greeting.afternoon", PLACE), Map.entry("greeting.evening", PLACE), Map.entry("greeting.night", PLACE),
		Map.entry("hunting", new Object[] { "wolf", "Morheim" }), Map.entry("progress", new Object[] { 40, 12 }), Map.entry("chatter", PLACE),
		Map.entry("level.question", PLACE), Map.entry("level.answer", new Object[] { "Morheim", 10, 14 }), Map.entry("who.question", PLACE),
		Map.entry("who.answer", PLACE), Map.entry("hunt.question", PLACE), Map.entry("hunt.answer", new Object[] { "wolf" }), Map.entry("group.ask", GROUP),
		Map.entry("group.form", GROUP), Map.entry("sell.price", new Object[] { "25k" }), Map.entry("sell.price.each", new Object[] { "25k" }),
		Map.entry("kinah.unit", NONE), Map.entry("kinah.decimal", NONE), Map.entry("test.line", new Object[] { "region", "Bob" }),
		Map.entry("shout.advert", new Object[] { "Store" }), Map.entry("shout.call", new Object[] { "Cleric", 33 }));

	/** A placeholder the code did not fill in. */
	private static final Pattern LEFT = Pattern.compile("%(\\d\\$)?[sdn]");

	@Test
	void everyLanguageHoldsEverySectionTheCodeDraws() {
		for (String language : List.of("fr", "en"))
			assertEquals(SHAPES.keySet(), ChatText.of(language).names(), language);
	}

	@Test
	void everyLineOfEveryLanguageFormatsWithTheArgumentsItsCodeGivesIt() {
		for (String language : List.of("fr", "en")) {
			ChatText text = ChatText.of(language);
			for (Map.Entry<String, Object[]> section : SHAPES.entrySet()) {
				for (String line : text.section(section.getKey())) {
					String said;
					try {
						said = line.formatted(section.getValue());
					} catch (java.util.IllegalFormatException e) {
						throw new AssertionError(language + " [" + section.getKey() + "] " + line + ": " + e);
					}
					assertFalse(LEFT.matcher(said).find(), language + " [" + section.getKey() + "] a placeholder left: " + said);
				}
			}
		}
	}

	@Test
	void noLineIsLongerThanTheChatWindowTakes() {
		for (String language : List.of("fr", "en")) {
			for (String section : SHAPES.keySet()) {
				for (String line : ChatText.of(language).section(section))
					assertTrue(line.length() <= 110, language + " [" + section + "] " + line.length() + " characters: " + line);
			}
		}
	}

	@Test
	void aCommentAndABlankLineAreSkippedAndTheEmptyMarkIsTheEmptyAlternative() {
		ChatText text = ChatText.parse("xx", List.of("# a comment", "", "[a]", "one", "<empty>", "  two  ", "", "[b]", "three"));

		assertEquals(List.of("one", "", "two"), text.section("a"));
		assertEquals(List.of("three"), text.section("b"));
	}

	@Test
	void aSectionWrittenTwiceIsAnError() {
		assertThrows(IllegalStateException.class, () -> ChatText.parse("xx", List.of("[a]", "one", "[a]", "two")));
	}

	@Test
	void aLineBeforeAnySectionIsAnError() {
		assertThrows(IllegalStateException.class, () -> ChatText.parse("xx", List.of("one", "[a]", "two")));
	}

	@Test
	void aSectionThatDoesNotExistIsSaidSo() {
		ChatText text = ChatText.parse("xx", List.of("[a]", "one"));

		assertThrows(IllegalStateException.class, () -> text.section("b"));
	}
}
