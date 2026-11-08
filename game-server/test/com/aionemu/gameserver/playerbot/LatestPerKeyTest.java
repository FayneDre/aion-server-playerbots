package com.aionemu.gameserver.playerbot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The route lane's rule: one place in the queue per bot, holding its newest search, and other bots never affected. */
class LatestPerKeyTest {

	@Test
	void aNewerJobTakesThePlaceOfTheOneWaitingAndIsTheOneThatRuns() {
		LatestPerKey lane = new LatestPerKey();
		List<String> ran = new ArrayList<>();
		assertTrue(lane.offer(7, () -> ran.add("first")), "the first job takes a place");
		assertFalse(lane.offer(7, () -> ran.add("second")));
		assertFalse(lane.offer(7, () -> ran.add("third")));
		assertEquals(1, lane.waiting(), "one bot, one place");

		lane.take(7).run();
		assertEquals(List.of("third"), ran, "the newest runs, once");
		assertNull(lane.take(7), "nothing is left for that place");
		assertEquals(2, lane.replaced());
	}

	@Test
	void otherKeysAreUntouched() {
		LatestPerKey lane = new LatestPerKey();
		List<String> ran = new ArrayList<>();
		lane.offer(1, () -> ran.add("a"));
		assertTrue(lane.offer(2, () -> ran.add("b")), "bot 2 takes a place of its own");
		lane.offer(1, () -> ran.add("a2"));
		lane.take(2).run();
		lane.take(1).run();
		assertEquals(List.of("b", "a2"), ran);
	}

	@Test
	void aJobOfferedAfterItsPlaceCameUpTakesANewPlace() {
		LatestPerKey lane = new LatestPerKey();
		assertTrue(lane.offer(3, () -> {}));
		lane.take(3);
		assertTrue(lane.offer(3, () -> {}), "a bot that asks again once its search has started asks for a new one, which needs a place");
		assertEquals(0, lane.replaced());
	}

	/**
	 * The fault this keeps away: a bot asking again every few seconds while the queue takes longer to come round. Its turn comes all the same, because a
	 * newer search never sends it back to the end of the queue.
	 */
	@Test
	void aBotThatKeepsAskingStillGetsItsTurn() {
		LatestPerKey lane = new LatestPerKey();
		Deque<Integer> queue = new ArrayDeque<>();
		List<Integer> served = new ArrayList<>();
		for (int bot = 1; bot <= 10; bot++) {
			int id = bot;
			if (lane.offer(id, () -> served.add(id)))
				queue.add(id);
		}
		// the queue serves one place per step while bot 10 asks again at every step
		while (!queue.isEmpty()) {
			if (lane.offer(10, () -> served.add(10)))
				queue.add(10);
			Runnable job = lane.take(queue.poll());
			if (job != null)
				job.run();
			if (served.contains(10))
				break;
		}
		assertTrue(served.contains(10), "bot 10 was served");
		assertEquals(10, served.size(), "and in its turn, after the nine before it");
	}

	/** The population guard reads how long the oldest place has waited, which a newer job for the same key does not reset. */
	@Test
	void theWaitIsTheKeysAndEndsWhenItsTurnComes() {
		LatestPerKey lane = new LatestPerKey();
		assertEquals(0, lane.longestWaiting(5_000), "nobody waits");
		lane.offer(1, () -> {}, 1_000);
		lane.offer(2, () -> {}, 3_000);
		lane.offer(1, () -> {}, 4_000); // a newer search for bot 1 keeps its place, and its wait
		assertEquals(4_000, lane.longestWaiting(5_000));
		lane.take(1);
		assertEquals(2_000, lane.longestWaiting(5_000), "bot 2 waits longest once bot 1's turn came");
		lane.take(2);
		assertEquals(0, lane.longestWaiting(5_000));
	}
}
