package com.aionemu.gameserver.playerbot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which of the jobs queued for one key is the one still wanted: the latest. A job takes a ticket when it is queued and claims it when its turn
 * comes; a newer ticket for the same key makes the older one's claim fail, so the older job is skipped. Used by the route lane, where a bot has
 * at most one search worth running ({@link BotScheduler#planRoute}).
 */
final class LatestPerKey {

	private final Map<Integer, Object> latest = new ConcurrentHashMap<>();
	private final AtomicLong skipped = new AtomicLong();

	/** @return The ticket of a job just queued for this key, which replaces the ticket of any job still waiting for it. */
	Object issue(int key) {
		Object ticket = new Object();
		latest.put(key, ticket);
		return ticket;
	}

	/** @return true if this job is still the latest for its key and is to run now; false, and counted, if a newer one replaced it. */
	boolean claim(int key, Object ticket) {
		if (latest.remove(key, ticket))
			return true;
		skipped.incrementAndGet();
		return false;
	}

	/** Withdraws a ticket whose job will never run, leaving a newer one in place. */
	void withdraw(int key, Object ticket) {
		latest.remove(key, ticket);
	}

	/** @return How many keys have a job waiting. */
	int waiting() {
		return latest.size();
	}

	/** @return How many jobs were skipped since the start for a newer one. */
	long skipped() {
		return skipped.get();
	}
}
