package com.aionemu.gameserver.playerbot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The route lane's rule: of the searches queued for one bot, only the latest runs, and other bots are never affected. */
class LatestPerKeyTest {

	@Test
	void aNewerJobForTheSameKeyReplacesTheOneStillWaiting() {
		LatestPerKey lane = new LatestPerKey();
		Object first = lane.issue(7), second = lane.issue(7), third = lane.issue(7);
		assertEquals(1, lane.waiting(), "one bot, one search waiting");

		assertFalse(lane.claim(7, first));
		assertFalse(lane.claim(7, second));
		assertTrue(lane.claim(7, third), "the latest runs");
		assertEquals(2, lane.skipped());
		assertEquals(0, lane.waiting());
	}

	@Test
	void otherKeysAreUntouched() {
		LatestPerKey lane = new LatestPerKey();
		Object a = lane.issue(1), b = lane.issue(2);
		lane.issue(1);
		assertTrue(lane.claim(2, b), "bot 2's search is not bot 1's business");
		assertFalse(lane.claim(1, a));
		assertEquals(1, lane.waiting());
	}

	@Test
	void aJobAskedForAgainAfterItRanIsNotSkipped() {
		LatestPerKey lane = new LatestPerKey();
		assertTrue(lane.claim(3, lane.issue(3)));
		assertTrue(lane.claim(3, lane.issue(3)), "a bot that asks again once its search has run asks for a new one, which runs");
		assertEquals(0, lane.skipped());
	}

	@Test
	void aWithdrawnTicketLeavesANewerOneInPlace() {
		LatestPerKey lane = new LatestPerKey();
		Object old = lane.issue(5), newer = lane.issue(5);
		lane.withdraw(5, old);
		assertTrue(lane.claim(5, newer));
	}
}
