package com.aionemu.gameserver.playerbot.movement;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** When a trip that nothing forces into the air is worth flying. The rest of a flight needs a world and is checked in game. */
class BotFlightTest {

	@Test
	void aTripUnderTwentyFiveMetresIsWalked() {
		assertFalse(BotFlight.worthFlyingTo(24f, 0), "a hop of a few paces is a bot jumping up and coming down, not travel");
		assertTrue(BotFlight.worthFlyingTo(25f, 0), "twenty five is the first that counts");
	}

	@Test
	void aTripPastTheLongestFlightIsWalked() {
		assertTrue(BotFlight.worthFlyingTo(300f, 0));
		assertFalse(BotFlight.worthFlyingTo(301f, 0), "a flight is for the length of a district, not for crossing a region");
	}

	@Test
	void onlyAboutHalfTheQualifyingTripsAreFlown() {
		int flown = 0;
		for (int roll = 0; roll < 1000; roll++) {
			if (BotFlight.worthFlyingTo(120f, roll))
				flown++;
		}
		assertEquals(500, flown, "so that a map of residents does not all take off together");
	}

	@Test
	void aNegativeRollIsNotAnError() {
		assertDoesNotThrow(() -> BotFlight.worthFlyingTo(120f, -17));
	}

	@Test
	void theMapsThatHadADeathLevelKeepIt() {
		assertEquals(400, BotFlight.deathLevelOf(400437));
		assertEquals(560, BotFlight.deathLevelOf(404051));
		assertEquals(42, BotFlight.deathLevelOf(400840));
	}

	@Test
	void aMapWithNoDeathLevelHasNone() {
		assertEquals(0, BotFlight.deathLevelOf(400010000), "the Abyss never had one: its void is the bottom of the map");
		assertEquals(0, BotFlight.deathLevelOf(110010000));
	}
}
