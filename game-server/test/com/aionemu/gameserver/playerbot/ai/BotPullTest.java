package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.ai.BotPull.Step;

/**
 * The steps of a pull on the player's order, decided without a world.
 */
class BotPullTest {

	private static final float REACH = 15f;

	@Test
	void outOfReachTheTankWalksUpThenStrikes() {
		assertEquals(Step.APPROACH, BotPull.step(false, 30, REACH, 30));
		assertEquals(Step.STRIKE, BotPull.step(false, REACH, REACH, 15), "at its skill's reach, from where it stands");
	}

	@Test
	void struckItWalksBackAndStopsAtTheSpot() {
		assertEquals(Step.RETURN, BotPull.step(true, 2, REACH, 20), "whatever the monster's distance: it follows");
		assertEquals(Step.DONE, BotPull.step(true, 2, REACH, BotPull.BACK_AT_THE_SPOT), "back, the fight is its own again");
	}

	@Test
	void aMonsterStruckWhereTheGroupStandsIsDoneAtOnce() {
		assertEquals(Step.DONE, BotPull.step(true, 10, REACH, 1));
	}
}
