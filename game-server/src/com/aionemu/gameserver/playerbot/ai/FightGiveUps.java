package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a bot has given up on in its fights, kept so the next decision does not pick it straight back: targets left alone for a while, the monsters
 * it could not reach, and how many it gave up on in a row.
 * <p>
 * A target is ignored for one of four reasons, each with its own time, decided where it happens: it could not be reached, it was beyond the camp,
 * a fight with it landed nothing, or the bot broke off from it (or it killed the bot). Only a chase that failed is counted, since it is the one that
 * says something about the ground.
 */
final class FightGiveUps {

	/** How long a target the bot could not reach is left alone, so it does not pick the same unreachable one again right away. */
	static final long UNREACHABLE_MILLIS = 10000;
	/** How long a target given up on a second time, then a third and after, is left alone. */
	static final long UNREACHABLE_AGAIN_MILLIS = 120000, UNREACHABLE_FOR_GOOD_MILLIS = 600000;
	/** Give-ups in a row, with no blow landed between them, after which the bot goes back to its camp instead of picking the next monster beside it. */
	static final int GIVE_UPS_BEFORE_CAMP = 3;
	/** How many monsters' failures are remembered before the memory is started again: a monster respawns under a new id, so old ones only take room. */
	static final int MAX_FAILED_CHASES_KEPT = 256;

	/** Targets not to pick until the time given, by object id. */
	private final Map<Integer, Long> ignoredUntil = new ConcurrentHashMap<>();
	/** How many times each monster was given up on as out of reach, so the next time it is left alone for longer. */
	private final Map<Integer, Integer> failedChases = new ConcurrentHashMap<>();
	/** Give-ups since the bot last landed a blow. */
	private volatile int giveUpsInARow;

	/** Leaves a target alone until the time given, whatever was decided about it before. */
	void ignore(int objectId, long until) {
		ignoredUntil.put(objectId, until);
	}

	/**
	 * @return true while the target is left alone. An entry past its time is dropped by the read that finds it so, unless it was renewed meanwhile:
	 *         a bot breaking off from a monster on one thread must not see the decision undone by a read of the old one on another.
	 */
	boolean isIgnored(int objectId, long now) {
		Long until = ignoredUntil.get(objectId);
		if (until == null)
			return false;
		if (until > now)
			return true;
		ignoredUntil.remove(objectId, until);
		return false;
	}

	/**
	 * Counts a chase that could not reach its target, and leaves the target alone for longer each time the same one is given up on: ten seconds was
	 * shorter than a round of three of them.
	 *
	 * @return true on the {@value #GIVE_UPS_BEFORE_CAMP}th in a row without a blow landed: the ground here is the problem, not the monster, and the
	 *         camp is where its ground is good. The count then starts over.
	 */
	boolean chaseFailed(int objectId, long now) {
		if (failedChases.size() > MAX_FAILED_CHASES_KEPT)
			failedChases.clear();
		int failures = failedChases.merge(objectId, 1, Integer::sum);
		ignore(objectId, now + unreachableFor(failures));
		if (++giveUpsInARow < GIVE_UPS_BEFORE_CAMP)
			return false;
		giveUpsInARow = 0;
		return true;
	}

	/** A blow landed: the give-ups no longer run in a row. */
	void landed() {
		giveUpsInARow = 0;
	}

	/** @return How long a monster given up on for the n-th time is left alone. */
	static long unreachableFor(int failures) {
		return failures <= 1 ? UNREACHABLE_MILLIS : failures == 2 ? UNREACHABLE_AGAIN_MILLIS : UNREACHABLE_FOR_GOOD_MILLIS;
	}
}
