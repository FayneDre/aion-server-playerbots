package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The arrays a walking search runs on: a queue that hands cells out in exactly the order {@link PriorityQueue} did, ties included, so a search expands
 * the same cells as before; and costs and predecessors that survive the table growing under them.
 */
class SearchSpaceTest {

	private record Entry(long key, float priority) {
	}

	@Test
	void theQueueHandsOutWhatPriorityQueueDid() {
		Random random = new Random(42);
		SearchSpace space = new SearchSpace();
		PriorityQueue<Entry> reference = new PriorityQueue<>((a, b) -> Float.compare(a.priority(), b.priority()));
		List<Long> ours = new ArrayList<>(), theirs = new ArrayList<>();
		for (int round = 0; round < 20_000; round++) {
			// coarse priorities, so that ties are frequent: the order they are broken in is what has to match
			if (reference.isEmpty() || random.nextInt(3) > 0) {
				long key = random.nextInt(1_000_000);
				float priority = random.nextInt(50);
				space.push(key, priority);
				reference.add(new Entry(key, priority));
			} else {
				ours.add(space.pop());
				theirs.add(reference.poll().key());
			}
		}
		while (!reference.isEmpty()) {
			ours.add(space.pop());
			theirs.add(reference.poll().key());
		}
		assertTrue(space.isQueueEmpty());
		assertEquals(theirs, ours);
	}

	@Test
	void costsAndPredecessorsSurviveTheTableGrowing() {
		SearchSpace space = new SearchSpace();
		for (long key = 0; key < 100_000; key++)
			space.reach(key * 257, key, key * 257 - 1);
		space.reach(257 * 10, 1.5f, 7); // a better way to a cell already reached
		for (long key = 0; key < 100_000; key++) {
			if (key == 10)
				continue;
			assertEquals(key, space.cost(key * 257), "cost of " + key);
			assertEquals(key * 257 - 1, space.parent(key * 257), "predecessor of " + key);
		}
		assertEquals(1.5f, space.cost(2570));
		assertEquals(7, space.parent(2570));
		assertTrue(Float.isNaN(space.cost(3)), "a cell never reached");
	}
}
