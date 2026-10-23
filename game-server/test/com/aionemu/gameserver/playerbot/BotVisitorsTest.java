package com.aionemu.gameserver.playerbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which levels visit a capital. See {@code docs/capital-life-plan.md}.
 */
class BotVisitorsTest {

	@Test
	void bandsOfFiveLevelsFromTen() {
		assertEquals(0, BotVisitors.bandOf(10));
		assertEquals(0, BotVisitors.bandOf(14));
		assertEquals(1, BotVisitors.bandOf(15));
		assertEquals(8, BotVisitors.bandOf(50));
	}

	@Test
	void theBandTheTownHoldsFewestOfIsDrawnFrom() {
		// Pandaemonium on 23 October: many under 30, many from 45 to 49, few from 30 to 44, few of 50 and over
		int[] inTown = new int[] { 20, 10, 15, 26, 2, 1, 3, 66, 10, 8 };
		assertEquals(5, BotVisitors.thinnest(inTown, List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)));
		assertEquals(9, BotVisitors.thinnest(inTown, List.of(7, 8, 9)), "only the bands that have a sleeper are drawn from");
	}

	@Test
	void aTieGoesToTheBandListedFirst() {
		int[] inTown = new int[] { 3, 3, 3 };
		assertEquals(2, BotVisitors.thinnest(inTown, List.of(2, 0, 1)), "the caller shuffles the bands, which makes it a draw");
	}
}
