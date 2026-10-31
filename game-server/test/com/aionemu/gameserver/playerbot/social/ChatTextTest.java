package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ChatTextTest {

	/** Every kind of line the code draws, which each language's file must hold. */
	private static final Set<String> SECTIONS = Set.of("sell.opening", "sell.closing", "advert", "group.opening", "group.closing", "role.tank", "role.healer", "role.support",
		"role.damage", "greeting.morning", "greeting.afternoon", "greeting.evening", "greeting.night", "hunting", "progress", "chatter", "level.question", "level.answer",
		"who.question", "who.answer", "hunt.question", "hunt.answer", "shout.advert", "shout.call");

	@Test
	void theFrenchFileHoldsEverySectionTheCodeDraws() {
		ChatText french = ChatText.of("fr");

		assertEquals(SECTIONS, french.names());
		for (String section : SECTIONS)
			assertFalse(french.section(section).isEmpty(), section);
	}

	@Test
	void everyLineOfTheFrenchFileFormatsWithTheArgumentsItsCodeGives() {
		ChatText french = ChatText.of("fr");
		// places and creatures are text, the levels and the percent are numbers: a line must format with one of the shapes its code gives it
		Object[][] shapes = { { "Morheim", "Morheim", "Morheim" }, { 12, 14, 16 }, { "Morheim", 12, 14 } };
		for (String section : SECTIONS) {
			for (String line : french.section(section)) {
				boolean formats = false;
				for (Object[] shape : shapes) {
					try {
						line.formatted(shape);
						formats = true;
						break;
					} catch (java.util.IllegalFormatException e) {
						// another shape
					}
				}
				assertTrue(formats, "[" + section + "] " + line);
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
