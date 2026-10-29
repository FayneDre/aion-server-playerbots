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
}
