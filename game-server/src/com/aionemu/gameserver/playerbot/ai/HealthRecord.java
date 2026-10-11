package com.aionemu.gameserver.playerbot.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * How much health each member of a group has lost over the last half minute, which is what a healer needs to tell a tank that is being worn down from one that
 * took a single blow, and which no single reading of the health bar says.
 * <p>
 * One reading a second per member, kept in a ring of thirty. The loss is the sum of the drops between neighbouring readings, so it counts damage and not what
 * the healer put back: a member healed as fast as it is hit shows a loss of everything it took, which is the measure Acquittal and Brilliant Protection ask for
 * ("loses 200 % of its health in 30 s"). Heals are not known to the record, so a rise is simply not counted, and the figure is a lower bound when heals and
 * hits land inside the same second.
 * <p>
 * Not thread safe, and does not need to be: it belongs to one bot's decision tick.
 */
public final class HealthRecord {

	/** How many seconds are kept. */
	public static final int WINDOW_SECONDS = 30;
	private static final long SECOND_MILLIS = 1000;

	private final Map<Integer, Ring> rings = new HashMap<>();

	private static final class Ring {

		final int[] drops = new int[WINDOW_SECONDS];
		long lastSampleAt = Long.MIN_VALUE;
		int lastPercent = -1;
		int next;

		void add(long now, int percent) {
			int drop = lastPercent >= 0 ? Math.max(0, lastPercent - percent) : 0;
			// a gap longer than one second means seconds with no reading, and they were seconds without a drop that is known of: the ring moves past them
			long seconds = lastSampleAt == Long.MIN_VALUE ? 1 : Math.min(WINDOW_SECONDS, Math.max(1, (now - lastSampleAt) / SECOND_MILLIS));
			for (long i = 1; i < seconds; i++) {
				drops[next] = 0;
				next = (next + 1) % WINDOW_SECONDS;
			}
			drops[next] = drop;
			next = (next + 1) % WINDOW_SECONDS;
			lastSampleAt = now;
			lastPercent = percent;
		}

		int total() {
			int sum = 0;
			for (int drop : drops)
				sum += drop;
			return sum;
		}
	}

	/**
	 * Notes a member's health, at most once a second: a call that comes sooner than that after the last one for the same member is ignored, so that a caller on a
	 * faster tick does not fill the ring with its own rate.
	 */
	public void sample(long nowMillis, int memberId, int hpPercent) {
		Ring ring = rings.computeIfAbsent(memberId, id -> new Ring());
		if (ring.lastSampleAt != Long.MIN_VALUE && nowMillis - ring.lastSampleAt < SECOND_MILLIS)
			return;
		ring.add(nowMillis, hpPercent);
	}

	/** @return The percent of its health the member has lost over the last {@link #WINDOW_SECONDS} seconds, as the sum of drops; 0 for a member never seen. */
	public int lossOverWindow(int memberId) {
		Ring ring = rings.get(memberId);
		return ring == null ? 0 : ring.total();
	}

	/** @return How many of the members whose ids are given have lost at least this many percent over the window. */
	public int membersWhoLostAtLeast(Iterable<Integer> memberIds, int percent) {
		int count = 0;
		for (int id : memberIds) {
			if (lossOverWindow(id) >= percent)
				count++;
		}
		return count;
	}

	/** Forgets everybody, for a bot that has left its group or changed it: the readings of the old one say nothing about the new. */
	public void clear() {
		rings.clear();
	}
}
