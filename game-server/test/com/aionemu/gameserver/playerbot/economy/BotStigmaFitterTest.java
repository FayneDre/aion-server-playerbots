package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How a class's list of stigmas orders the stones. The list is the guide's for the templar; the order is the whole of what the fitter takes from it.
 */
class BotStigmaFitterTest {

	private static final List<String> WANTED = List.of("KN_HIGHPROVOKE", "KN_REFLECTSHIELD", "KN_THUNDERBLADE");

	@Test
	void aStoneIsRankedByItsPlaceOnTheList() {
		assertEquals(0, BotStigmaFitter.rankOf(WANTED, List.of("KN_HIGHPROVOKE")));
		assertEquals(2, BotStigmaFitter.rankOf(WANTED, List.of("KN_THUNDERBLADE")));
	}

	@Test
	void aStoneThatIsNotOnTheListComesAfterEveryoneWhoIs() {
		assertEquals(WANTED.size(), BotStigmaFitter.rankOf(WANTED, List.of("KN_ABYSALJUDGEMENT")));
		assertTrue(BotStigmaFitter.rankOf(WANTED, List.of("SOMETHING_ELSE")) > BotStigmaFitter.rankOf(WANTED, List.of("KN_THUNDERBLADE")));
	}

	@Test
	void aStoneGrantingSeveralSkillsTakesTheBestPlaceAnyOfThemHas() {
		assertEquals(1, BotStigmaFitter.rankOf(WANTED, List.of("SOMETHING_ELSE", "KN_REFLECTSHIELD", "KN_THUNDERBLADE")));
	}

	@Test
	void aClassWithNoListRanksEveryStoneTheSame() {
		assertEquals(0, BotStigmaFitter.rankOf(List.of(), List.of("KN_HIGHPROVOKE")));
		assertEquals(0, BotStigmaFitter.rankOf(List.of(), List.of()));
	}
}
