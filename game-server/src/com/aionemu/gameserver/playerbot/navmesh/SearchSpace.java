package com.aionemu.gameserver.playerbot.navmesh;

import java.util.Arrays;

/**
 * What one walking search holds while it runs: the cost and the predecessor of every cell it has reached, and the cells still to be expanded, in plain
 * arrays.
 * <p>
 * It replaces two {@code HashMap<Long, ...>} and a {@code PriorityQueue} of records, which held a node in some 150 to 200 bytes of boxed numbers and
 * entries; the same node is about 40 bytes here, with four searches side by side on a heap at 2.1 of its 2.5 GB. Measured on 24 October over the real
 * give-ups of Altgard and Inggison, it finds the same routes as the collections did, a fifth faster on Altgard. The budget of nodes was left where it
 * was: raising it found fewer routes, not more, since the searches that give up in game run out of time before they run out of nodes.
 * <p>
 * The queue is the binary heap of {@link java.util.PriorityQueue}, sifted the same way, so that ties are broken in the same order and a search expands the
 * same cells, in the same order, as it did on the collections.
 */
final class SearchSpace {

	private static final long FREE = -1; // a cell key is never negative
	private static final int INITIAL_CAPACITY = 1 << 12;

	private long[] keys = new long[INITIAL_CAPACITY];
	private float[] costs = new float[INITIAL_CAPACITY];
	private long[] parents = new long[INITIAL_CAPACITY];
	private int size;

	private long[] heapKeys = new long[INITIAL_CAPACITY];
	private float[] heapPriorities = new float[INITIAL_CAPACITY];
	private int heapSize;

	SearchSpace() {
		Arrays.fill(keys, FREE);
	}

	/** @return The cost the cell was reached at, or NaN when it has not been. */
	float cost(long key) {
		int slot = slotOf(key);
		return keys[slot] == key ? costs[slot] : Float.NaN;
	}

	/** @return The cell the given one was reached from. */
	long parent(long key) {
		return parents[slotOf(key)];
	}

	/** Records that the cell is reached at that cost, from that predecessor. */
	void reach(long key, float cost, long parent) {
		int slot = slotOf(key);
		if (keys[slot] != key) {
			if ((size + 1) * 2 > keys.length) {
				grow();
				slot = slotOf(key);
			}
			keys[slot] = key;
			size++;
		}
		costs[slot] = cost;
		parents[slot] = parent;
	}

	/** @return The slot holding the key, or the free slot it would go in. */
	private int slotOf(long key) {
		int mask = keys.length - 1;
		int slot = (int) mix(key) & mask;
		while (keys[slot] != FREE && keys[slot] != key)
			slot = (slot + 1) & mask;
		return slot;
	}

	private static long mix(long key) {
		key ^= key >>> 33;
		key *= 0xff51afd7ed558ccdL;
		key ^= key >>> 33;
		return key;
	}

	private void grow() {
		long[] oldKeys = keys, oldParents = parents;
		float[] oldCosts = costs;
		keys = new long[oldKeys.length * 2];
		costs = new float[keys.length];
		parents = new long[keys.length];
		Arrays.fill(keys, FREE);
		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] == FREE)
				continue;
			int slot = slotOf(oldKeys[i]);
			keys[slot] = oldKeys[i];
			costs[slot] = oldCosts[i];
			parents[slot] = oldParents[i];
		}
	}

	boolean isQueueEmpty() {
		return heapSize == 0;
	}

	/** Queues the cell for expansion at that priority, as {@code PriorityQueue.offer} does. */
	void push(long key, float priority) {
		if (heapSize == heapKeys.length) {
			heapKeys = Arrays.copyOf(heapKeys, heapSize * 2);
			heapPriorities = Arrays.copyOf(heapPriorities, heapSize * 2);
		}
		int k = heapSize++;
		while (k > 0) {
			int parent = (k - 1) >>> 1;
			if (Float.compare(priority, heapPriorities[parent]) >= 0)
				break;
			heapKeys[k] = heapKeys[parent];
			heapPriorities[k] = heapPriorities[parent];
			k = parent;
		}
		heapKeys[k] = key;
		heapPriorities[k] = priority;
	}

	/** @return The queued cell of lowest priority, taken out of the queue, as {@code PriorityQueue.poll} does. */
	long pop() {
		long result = heapKeys[0];
		int n = --heapSize;
		if (n > 0) {
			long key = heapKeys[n];
			float priority = heapPriorities[n];
			int k = 0, half = n >>> 1;
			while (k < half) {
				int child = (k << 1) + 1, right = child + 1;
				if (right < n && Float.compare(heapPriorities[child], heapPriorities[right]) > 0)
					child = right;
				if (Float.compare(priority, heapPriorities[child]) <= 0)
					break;
				heapKeys[k] = heapKeys[child];
				heapPriorities[k] = heapPriorities[child];
				k = child;
			}
			heapKeys[k] = key;
			heapPriorities[k] = priority;
		}
		return result;
	}
}
