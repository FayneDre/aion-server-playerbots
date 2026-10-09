package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Which enemy gets the skull. The rest of marking needs a world; this is the one decision in it that can be argued about on paper.
 */
class BotMarksTest {

	@Test
	void marksTheEnemyWithTheLeastHealthInAbsoluteTerms() {
		// the second has the smaller bar but the larger number: the first dies first, which is what the skull is for
		assertEquals(10, BotMarks.weakestOf(Map.of(10, 800L, 20, 5000L, 30, 1200L)));
	}

	@Test
	void tiesGoToTheLowerIdSoEveryBotAsksTheSameQuestionAndGetsTheSameAnswer() {
		assertEquals(7, BotMarks.weakestOf(Map.of(9, 500L, 7, 500L, 8, 500L)));
	}

	@Test
	void nobodyToMarkIsZero() {
		assertEquals(0, BotMarks.weakestOf(Map.of()));
	}
}
