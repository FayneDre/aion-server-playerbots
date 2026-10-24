package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The bots' Trade lines: a price written as players write it, the item and its price always there and true, and enough forms that a channel does
 * not read as one sentence repeated.
 */
class BotChatLinesTest {

	@Test
	void pricesAreWrittenAsPlayersWriteThem() {
		assertEquals("850 kinah", BotChatLines.kinah(850));
		assertEquals("25k", BotChatLines.kinah(25_000));
		assertEquals("150k", BotChatLines.kinah(149_600));
		assertEquals("1M", BotChatLines.kinah(999_700), "not 1000k");
		assertEquals("1,5M", BotChatLines.kinah(1_500_000));
		assertEquals("75M", BotChatLines.kinah(75_000_000));
		assertEquals("4,5M", BotChatLines.kinah(4_460_000));
	}

	@Test
	void aSaleNamesTheItemAndItsPrice() {
		Random random = new Random(7);
		for (int i = 0; i < 500; i++) {
			String line = BotChatLines.sell(random, 166000191, 1, 25_000, "Sanctum");
			assertTrue(line.contains("[item:166000191]"), line);
			assertTrue(line.contains("25k"), line);
			assertFalse(line.contains("%"), "a placeholder left in: " + line);
			assertFalse(line.toLowerCase().contains("wtb") || line.toLowerCase().contains("achète"), "bots never buy: " + line);
		}
	}

	@Test
	void aStackSaysHowManyAndThePriceOfOne() {
		Random random = new Random(11);
		boolean perUnit = false;
		for (int i = 0; i < 500; i++) {
			String line = BotChatLines.sell(random, 186000002, 30, 12_000, "Pandaemonium");
			assertTrue(line.contains("x30") || line.contains("(30)"), line);
			perUnit |= line.contains("12k/u");
		}
		assertTrue(perUnit, "the price of one is sometimes said to be per unit");
	}

	@Test
	void oneSaleHasManyForms() {
		Random random = new Random(3);
		Set<String> forms = new HashSet<>();
		for (int i = 0; i < 2000; i++)
			forms.add(BotChatLines.sell(random, 166000191, 1, 25_000, "Sanctum"));
		assertTrue(forms.size() > 100, forms.size() + " forms only");
	}

	@Test
	void aCallForAGroupSaysTheSpeakersOwnLevelRoleAndInstance() {
		Random random = new Random(13);
		Set<String> forms = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String line = BotChatLines.lookingForGroup(random, "aède", 45, BotRole.SUPPORT, "Dark Poeta");
			assertTrue(line.contains("45") && line.contains("Dark Poeta"), line);
			assertTrue(line.matches("(?i).*(support|buff|soutien).*"), "its own role: " + line);
			assertFalse(line.matches("(?i).*\\b(tank|heal|soigneur|dps)\\b.*"), "no other role: " + line);
			forms.add(line);
		}
		assertTrue(forms.size() > 100, forms.size() + " forms only");
	}

	@Test
	void aPlaceIsNamedAsPlayersSayIt() {
		assertEquals("Basfelt Village", BotChatLines.placeName("BASFELT_VILLAGE_220030000"));
		assertEquals("Altar of Tiamat", BotChatLines.placeName("ALTAR_OF_TIAMAT_220030000"));
		assertEquals("Altgard Fortress Dungeon Entrance", BotChatLines.placeName("ALTGARD_FORTRESS_DUNGEON_ENTRANCE_220030000"));
	}

	@Test
	void aGreetingFitsTheHour() {
		Random random = new Random(17);
		for (int i = 0; i < 200; i++) {
			assertFalse(BotChatLines.greeting(random, 9, "Altgard").toLowerCase().matches(".*(bonsoir|bsr|nuit|soirée).*"), "a morning");
			assertFalse(BotChatLines.greeting(random, 21, "Altgard").toLowerCase().matches(".*(bonjour|matin|aprem).*"), "an evening");
		}
	}

	@Test
	void anAnswerAboutLevelsGivesTheBand() {
		Random random = new Random(19);
		for (int i = 0; i < 200; i++) {
			String answer = BotChatLines.levelAnswer(random, "Basfelt Village", 11, 14);
			assertTrue(answer.contains("11") && answer.contains("14"), answer);
			assertFalse(answer.contains("%"), answer);
		}
	}

	@Test
	void anAdvertNamesTheStoreAndTheTown() {
		Random random = new Random(5);
		for (int i = 0; i < 200; i++) {
			String line = BotChatLines.advert(random, "Pierres 10-50", "Sanctum");
			assertTrue(line.contains("Pierres 10-50") && line.contains("Sanctum"), line);
		}
	}
}
