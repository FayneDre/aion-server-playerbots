package com.aionemu.gameserver.playerbot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One place in a queue per key, holding the latest job offered for it. The first job offered for a key takes a place; a newer one offered before
 * that place comes up replaces the job in it rather than taking a place of its own, so the key keeps its turn and the newest job is the one that
 * runs. Used by the route lane, where a bot has at most one search worth running ({@link BotScheduler#planRoute}).
 * <p>
 * Keeping the turn is the point. A newer job sent to the back of the queue instead would never run for a key that keeps asking more often than the
 * queue takes to come round, which is exactly a bot that asks again after a few seconds of waiting.
 */
final class LatestPerKey {

	private final Map<Integer, Runnable> latest = new ConcurrentHashMap<>();
	private final AtomicLong replaced = new AtomicLong();

	/**
	 * Offers a job for this key.
	 *
	 * @return true if the key had no job waiting, so the caller queues one {@link #take} for it; false if the job took the place of one waiting.
	 */
	boolean offer(int key, Runnable job) {
		if (latest.put(key, job) == null)
			return true;
		replaced.incrementAndGet();
		return false;
	}

	/** @return The latest job offered for this key, which is no longer waiting, or null if there is none. */
	Runnable take(int key) {
		return latest.remove(key);
	}

	/** @return How many keys have a job waiting. */
	int waiting() {
		return latest.size();
	}

	/** @return How many jobs were replaced by a newer one before their turn, since the start. */
	long replaced() {
		return replaced.get();
	}
}
