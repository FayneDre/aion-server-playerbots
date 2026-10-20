package com.aionemu.gameserver.playerbot.ai;

import com.aionemu.gameserver.playerbot.world.TownServices.Service;

/**
 * What a resident of a town does with each quarter of an hour of the day.
 * <p>
 * The occupations of the countryside, drawn at random for a few minutes each, were what a town had too, and a town showed it: a third of a capital's
 * day was farming, which finds nothing to hunt in a city and stands still, and the rest was spells of three to ten minutes, so nobody was ever found
 * at the same place twice. Measured on 20 October over two and a half hours, 196 residents of Sanctum and Pandaemonium drew farming 1543 times.
 * <p>
 * A quarter is decided from the resident's id and the hour alone, so it is the same every day: the one who sits on the steps at nine sits there again
 * the next morning, and the one who keeps a broker's hours keeps them. Habits are what make a crowd read as people rather than as traffic. Nothing here
 * hunts.
 */
final class TownRoutine {

	/** How long one quarter lasts, in minutes. */
	static final int QUARTER_MINUTES = 15;

	/** What a resident is doing for the quarter. */
	enum Activity {
		/** Standing about at its own spot where it lives. */
		HOME,
		/** Sitting down at its own spot where it lives. */
		SIT,
		/** At one of the town's services: a shop, the broker, the warehouse, a crafting master. */
		ERRAND,
		/** Gone to another part of the town. */
		VISIT
	}

	/**
	 * The quarter's plan.
	 *
	 * @param service Where the errand is to, for {@link Activity#ERRAND}; null otherwise.
	 * @param seed Which one of the town's places or services of that kind, so the same resident goes to the same one.
	 */
	record Quarter(Activity activity, Service service, int seed) {
	}

	/** How much a resident leans towards each activity, in {@link Activity} order: a town is mostly people standing about and going on errands. */
	private static final int[] ACTIVITY_WEIGHTS = { 35, 20, 30, 15 };
	/** How often each service is gone to, in {@link Service} order: the shops and the broker most, the soul healer least. */
	private static final int[] SERVICE_WEIGHTS = { 25, 20, 15, 15, 10, 8, 7 };

	private TownRoutine() {
	}

	/**
	 * @param objectId The resident's id, which is what makes its habits its own.
	 * @param minuteOfDay The minute of the server's day, 0 to 1439.
	 * @return What the resident does in the quarter that minute falls in.
	 */
	static Quarter at(int objectId, int minuteOfDay) {
		int quarter = Math.floorMod(minuteOfDay, 24 * 60) / QUARTER_MINUTES;
		int hash = mix(objectId * 31 + quarter);
		Activity activity = Activity.values()[pick(ACTIVITY_WEIGHTS, Math.floorMod(hash, 1000))];
		int seed = mix(objectId) >>> 1; // the same service or place of a kind each time it is gone to, whatever the quarter
		Service service = activity == Activity.ERRAND ? Service.values()[pick(SERVICE_WEIGHTS, Math.floorMod(hash / 1000, 1000))] : null;
		return new Quarter(activity, service, seed);
	}

	/** @return How many minutes are left of the quarter that minute falls in, at least one. */
	static int minutesLeft(int minuteOfDay) {
		return QUARTER_MINUTES - Math.floorMod(minuteOfDay, QUARTER_MINUTES);
	}

	/** @return The index whose share of the weights a roll of 0 to 999 falls in. */
	private static int pick(int[] weights, int roll) {
		int total = 0;
		for (int weight : weights)
			total += weight;
		int at = roll * total / 1000;
		for (int i = 0; i < weights.length; i++) {
			at -= weights[i];
			if (at < 0)
				return i;
		}
		return weights.length - 1;
	}

	/** A bit mixer, so neighbouring ids and neighbouring quarters do not lean the same way. */
	private static int mix(int value) {
		int h = value * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return h & Integer.MAX_VALUE;
	}
}
