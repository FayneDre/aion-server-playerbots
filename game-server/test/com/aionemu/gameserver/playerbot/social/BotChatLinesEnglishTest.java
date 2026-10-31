package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** What BotChatLinesTest asks of the French lines, asked of the English ones, with the marks of the language: a decimal point, "ea" for a price each. */
class BotChatLinesEnglishTest {

	private static final ChatText EN = ChatText.of("en");

	@Test
	void pricesAreWrittenWithADecimalPoint() {
		assertEquals("850 kinah", BotChatLines.kinah(EN, 850));
		assertEquals("25k", BotChatLines.kinah(EN, 25_000));
		assertEquals("1.5M", BotChatLines.kinah(EN, 1_500_000));
		assertEquals("4.5M", BotChatLines.kinah(EN, 4_460_000));
		assertEquals("75M", BotChatLines.kinah(EN, 75_000_000));
	}

	@Test
	void aSaleNamesTheItemAndItsPriceAndABotNeverBuys() {
		Random random = new Random(7);
		for (int i = 0; i < 500; i++) {
			String line = BotChatLines.sell(EN, random, 166000191, 1, 25_000, "Sanctum");
			assertTrue(line.contains("[item:166000191]") && line.contains("25k"), line);
			assertFalse(line.contains("%"), "a placeholder left in: " + line);
			assertFalse(line.toLowerCase().contains("wtb") || line.toLowerCase().contains("buying"), "bots never buy: " + line);
		}
	}

	@Test
	void aStackSaysHowManyAndSometimesThePriceEach() {
		Random random = new Random(11);
		boolean each = false;
		for (int i = 0; i < 500; i++) {
			String line = BotChatLines.sell(EN, random, 186000002, 30, 12_000, "Pandaemonium");
			assertTrue(line.contains("x30") || line.contains("(30)"), line);
			each |= line.contains("12k ea");
		}
		assertTrue(each);
	}

	@Test
	void aCallForAGroupSaysTheSpeakersOwnLevelRoleAndInstanceInEnglish() {
		Random random = new Random(13);
		Set<String> forms = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String line = BotChatLines.lookingForGroup(EN, random, "spirit master", 45, BotRole.SUPPORT, "Dark Poeta");
			assertTrue(line.contains("45") && line.contains("Dark Poeta"), line);
			assertTrue(line.matches("(?i).*(support|buff).*"), "its own role: " + line);
			assertFalse(line.matches("(?i).*\\b(tank|heal|healer|dps)\\b.*"), "no other role: " + line);
			assertFalse(line.matches("(?i).*\\b(pour|cherche|dispo pour)\\b.*"), "no French left: " + line);
			forms.add(line);
		}
		assertTrue(forms.size() > 80, forms.size() + " forms only");
	}

	@Test
	void aGreetingFitsTheHour() {
		Random random = new Random(17);
		for (int i = 0; i < 200; i++) {
			assertFalse(BotChatLines.greeting(EN, random, 9, "Altgard").toLowerCase().matches(".*(evening|night).*"), "a morning");
			assertFalse(BotChatLines.greeting(EN, random, 21, "Altgard").toLowerCase().matches(".*(morning|afternoon).*"), "an evening");
		}
	}

	@Test
	void aClassIsNamedByTheDataInEnglishAndByAFrenchClientInFrench() {
		assertEquals("spirit master", BotChatLines.className(EN, com.aionemu.gameserver.model.PlayerClass.SPIRIT_MASTER));
		assertNotEquals("gladiator", BotChatLines.className(ChatText.of("fr"), com.aionemu.gameserver.model.PlayerClass.GLADIATOR));
	}
}
