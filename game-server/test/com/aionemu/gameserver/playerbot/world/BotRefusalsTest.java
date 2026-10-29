package com.aionemu.gameserver.playerbot.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

class BotRefusalsTest {

	private static final Vector3f GOAL = new Vector3f(1300, 1520, 200);

	@Test
	void aProofHoldsForABotStandingAboutWhereItWasProved() {
		BotRefusals.remember(9001, new Vector3f(1000, 1000, 100), GOAL, false);
		assertTrue(BotRefusals.isRefused(9001, new Vector3f(1010, 1005, 102), GOAL));
	}

	@Test
	void itDoesNotHoldFromAnotherFloorOrFarAway() {
		BotRefusals.remember(9002, new Vector3f(1000, 1000, 100), GOAL, false);
		assertFalse(BotRefusals.isRefused(9002, new Vector3f(1000, 1000, 112), GOAL), "a town's floors lie above one another");
		assertFalse(BotRefusals.isRefused(9002, new Vector3f(1100, 1000, 100), GOAL));
	}

	@Test
	void itIsAboutThatGoalOnly() {
		BotRefusals.remember(9003, new Vector3f(1000, 1000, 100), GOAL, false);
		assertFalse(BotRefusals.isRefused(9003, new Vector3f(1000, 1000, 100), new Vector3f(1310, 1520, 200)));
	}

	@Test
	void itIsAboutThatMapOnly() {
		BotRefusals.remember(9004, new Vector3f(1000, 1000, 100), GOAL, false);
		assertFalse(BotRefusals.isRefused(9005, new Vector3f(1000, 1000, 100), GOAL));
	}

	@Test
	void aGoalCutOffHoldsWhereverTheBotStands() {
		BotRefusals.remember(9006, new Vector3f(1000, 1000, 100), GOAL, true);
		assertTrue(BotRefusals.isRefused(9006, new Vector3f(2500, 300, 50), GOAL));
	}

	@Test
	void aGoalRefusedFromThreeDifferentPlacesIsRefusedFromAnywhere() {
		BotRefusals.remember(9007, new Vector3f(1000, 1000, 100), GOAL, false);
		BotRefusals.remember(9007, new Vector3f(1200, 1000, 100), GOAL, false);
		assertFalse(BotRefusals.isRefused(9007, new Vector3f(2000, 300, 50), GOAL), "two places say little");
		BotRefusals.remember(9007, new Vector3f(1000, 1300, 100), GOAL, false);
		assertTrue(BotRefusals.isRefused(9007, new Vector3f(2000, 300, 50), GOAL));
	}

	@Test
	void theSameGroundCountsOnce() {
		BotRefusals.remember(9008, new Vector3f(1000, 1000, 100), GOAL, false);
		BotRefusals.remember(9008, new Vector3f(1005, 1000, 100), GOAL, false);
		BotRefusals.remember(9008, new Vector3f(1000, 1008, 100), GOAL, false);
		assertFalse(BotRefusals.isRefused(9008, new Vector3f(2000, 300, 50), GOAL));
	}
}
