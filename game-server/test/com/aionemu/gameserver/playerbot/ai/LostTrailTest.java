package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * A bot is lost when its searches run out of budget for several different destinations from one spot, and only then: the same destination asked again,
 * a bot that walked on, or give-ups spread over too long a time are no evidence against where it stands.
 */
class LostTrailTest {

	private static final Vector3f HERE = new Vector3f(100, 100, 50);

	private static Vector3f goal(int index) {
		return new Vector3f(500 + index * 50, 500, 50);
	}

	@Test
	void fourDifferentDestinationsFromOneSpotAreLost() {
		LostTrail trail = new LostTrail();
		for (int i = 0; i < LostTrail.DESTINATIONS - 1; i++)
			assertFalse(trail.gaveUp(HERE, goal(i), i * 1000L));
		assertTrue(trail.gaveUp(HERE, goal(LostTrail.DESTINATIONS - 1), 10_000L));
		assertFalse(trail.gaveUp(HERE, goal(0), 11_000L), "the count starts over once it has said so");
	}

	@Test
	void theSameDestinationAskedAgainIsNotLost() {
		LostTrail trail = new LostTrail();
		for (int i = 0; i < 20; i++)
			assertFalse(trail.gaveUp(HERE, new Vector3f(500 + i % 3, 500, 50), i * 1000L), "a far hunting ground refused over and over");
	}

	@Test
	void aBotThatWalkedOnStartsAgain() {
		LostTrail trail = new LostTrail();
		for (int i = 0; i < LostTrail.DESTINATIONS - 1; i++)
			trail.gaveUp(HERE, goal(i), i * 1000L);
		Vector3f elsewhere = new Vector3f(HERE.getX() + LostTrail.SAME_SPOT + 1, HERE.getY(), HERE.getZ());
		assertFalse(trail.gaveUp(elsewhere, goal(10), 5_000L));
	}

	@Test
	void giveUpsSpreadOverTooLongAreNotLost() {
		LostTrail trail = new LostTrail();
		for (int i = 0; i < LostTrail.DESTINATIONS - 1; i++)
			trail.gaveUp(HERE, goal(i), i * 1000L);
		assertFalse(trail.gaveUp(HERE, goal(10), LostTrail.WINDOW_MILLIS + 10_000L));
	}

	@Test
	void aRefusalWithoutADestinationCountsForNothing() {
		LostTrail trail = new LostTrail();
		for (int i = 0; i < 10; i++)
			assertFalse(trail.gaveUp(HERE, null, i * 1000L));
	}
}
