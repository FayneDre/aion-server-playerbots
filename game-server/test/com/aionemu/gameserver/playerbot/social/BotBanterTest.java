package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The jokes of Region: in both languages, in two halves when two bots tell them, and nothing a channel of players should not read. */
class BotBanterTest {

	/**
	 * What no line of any language may hold: slurs and wishes of death on a person, and the names of real persons. The tone is crude by decision (31 October) and aimed at the game and
	 * at the speaker, never at a real player, a group of people or a faith.
	 */
	private static final List<String> FORBIDDEN = List.of("nigger", "nigga", "faggot", "tapette", "pédé", "negre", "nègre", "bamboula", "youpin", "chinetoque", "sidaïque", "raghead",
		"kys", "kill yourself", "suicide", "va crever", "rape", "viol ", "hitler", "nazi", "trump", "macron", "allah", "jésus", "terroriste");

	@Test
	void everyExchangeHasTwoHalvesAndEverySoloLineNone() {
		for (String language : List.of("fr", "en")) {
			ChatText text = ChatText.of(language);
			for (String line : text.section("banter.exchange")) {
				int cut = line.indexOf(BotChatLines.EXCHANGE_SEPARATOR);
				assertTrue(cut > 0 && cut + BotChatLines.EXCHANGE_SEPARATOR.length() < line.length(), language + ": " + line);
				assertEquals(cut, line.lastIndexOf(BotChatLines.EXCHANGE_SEPARATOR), language + " a single separator: " + line);
			}
			for (String line : text.section("banter.solo"))
				assertFalse(line.contains(BotChatLines.EXCHANGE_SEPARATOR), language + ": " + line);
		}
	}

	@Test
	void noLineOfAnyLanguageHoldsAWordAChannelShouldNotRead() {
		for (String language : List.of("fr", "en")) {
			ChatText text = ChatText.of(language);
			for (String section : text.names()) {
				for (String line : text.section(section)) {
					String lower = line.toLowerCase();
					for (String word : FORBIDDEN)
						assertFalse(lower.contains(word), language + " [" + section + "] holds \"" + word + "\": " + line);
				}
			}
		}
	}

	@Test
	void aJokeIsToldWholeAndSometimesBetweenTwo() {
		for (String language : List.of("fr", "en")) {
			ChatText text = ChatText.of(language);
			Random random = new Random(23);
			Set<String> told = new HashSet<>();
			int exchanges = 0;
			for (int i = 0; i < 2000; i++) {
				BotChatLines.Banter banter = BotChatLines.banter(text, random, "Morheim");
				assertFalse(banter.first().isBlank(), language);
				assertFalse(banter.first().contains("%") && !banter.first().contains("%%") && banter.first().matches(".*%[sd].*"), banter.first());
				if (banter.reply() != null) {
					exchanges++;
					assertFalse(banter.reply().isBlank(), language + ": " + banter.first());
				}
				told.add(banter.first());
			}
			assertTrue(exchanges > 600 && exchanges < 1000, language + ": " + exchanges + " exchanges of 2000 jokes");
			assertTrue(told.size() > 40, language + ": " + told.size() + " different jokes only");
		}
	}
}
