package com.aionemu.gameserver.playerbot.ai;

import com.aionemu.gameserver.playerbot.world.TownServices.Service;

/**
 * What a resident of a town does with each spell of a few minutes of the day.
 * <p>
 * The occupations of the countryside, drawn at random for a few minutes each, were what a town had too, and a town showed it: a third of a capital's
 * day was farming, which finds nothing to hunt in a city and stands still, and the rest was spells of three to ten minutes, so nobody was ever found
 * at the same place twice. Measured on 20 October over two and a half hours, 196 residents of Sanctum and Pandaemonium drew farming 1543 times.
 * <p>
 * A spell is decided from the resident's id and the time of day alone, so it is the same every day: the one who sits on the steps at nine sits there
 * again the next morning, and the one who keeps a broker's hours keeps them. Habits are what make a crowd read as people rather than as traffic.
 * Nothing here hunts.
 * <p>
 * Each resident's spells start at a moment of its own. Quarters of an hour on the clock were tried first: everybody set off at the quarter, walked for a
 * minute and stood for fourteen, and a tester in Sanctum saw nobody move at all, which the count agreed with: 0 to 2 of 117 residents moving between
 * two quarters. Short spells staggered across residents keep somebody on their way at every moment.
 */
final class TownRoutine {

	/** How long one spell lasts, in seconds. */
	static final int SPELL_SECONDS = 5 * 60;
	private static final int DAY_SECONDS = 24 * 60 * 60;

	/** What a resident is doing for the spell. */
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
	 * The spell's plan.
	 *
	 * @param service Where the errand is to, for {@link Activity#ERRAND}; null otherwise.
	 * @param seed Which one of the town's places or services of that kind, so the same resident goes to the same one.
	 */
	record Spell(Activity activity, Service service, int seed) {
	}

	/**
	 * How much a resident leans towards each activity, in {@link Activity} order. Was 30, 15, 30, 25: with the stores the 44 keepers of a capital stand
	 * at their stalls all their shift, and half the rest stood or sat at home, so a tester found the capitals stiller than before them (23 October).
	 * More errands and walks, fewer people standing about.
	 */
	private static final int[] ACTIVITY_WEIGHTS = { 20, 10, 35, 35 };
	/** How often each service is gone to, in {@link Service} order: the shops and the broker most, the soul healer least. */
	private static final int[] SERVICE_WEIGHTS = { 25, 20, 15, 15, 10, 8, 7 };

	private TownRoutine() {
	}

	/**
	 * @param objectId The resident's id, which is what makes its habits and the moments it sets off its own.
	 * @param secondOfDay The second of the server's day.
	 * @return What the resident does in the spell that second falls in.
	 */
	static Spell at(int objectId, int secondOfDay) {
		int spell = Math.floorMod(secondOfDay - phaseOf(objectId), DAY_SECONDS) / SPELL_SECONDS;
		int hash = mix(objectId * 31 + spell);
		Activity activity = Activity.values()[pick(ACTIVITY_WEIGHTS, Math.floorMod(hash, 1000))];
		int seed = mix(objectId) >>> 1; // the same service or place of a kind each time it is gone to, whatever the spell
		Service service = activity == Activity.ERRAND ? Service.values()[pick(SERVICE_WEIGHTS, Math.floorMod(hash / 1000, 1000))] : null;
		return new Spell(activity, service, seed);
	}

	/** @return How many seconds are left of the resident's spell that second falls in, at least one. */
	static int secondsLeft(int objectId, int secondOfDay) {
		return SPELL_SECONDS - Math.floorMod(secondOfDay - phaseOf(objectId), SPELL_SECONDS);
	}

	/** The lowest level a resident keeps a store from: below it a character has little to sell anybody would want. */
	static final int STORE_LEVEL = 20;
	/** How many residents in ten keep a store, the same ones every day. */
	private static final int MERCHANTS_IN_TEN = 8;
	/**
	 * How many of a town's merchants are on shift at once: the 40 stores a map holds, and a few more for those that cannot reach their stall.
	 * <p>
	 * A count and not a share. Each merchant had a shift of its own, drawn from its id, and how many stores a town held was chance multiplied by how many
	 * residents it had: about 16 in Sanctum and 21 in Pandaemonium with shifts of 6 to 10 hours, and still 26 against 40 with longer ones, while
	 * Pandaemonium, with a third more residents, was full (23 October).
	 */
	static final int ON_SHIFT = 44;

	/** @return true for a resident who keeps a store, the same ones every day. */
	static boolean isMerchant(int objectId) {
		return Math.floorMod(mix(objectId ^ 0x1B873593), 10) < MERCHANTS_IN_TEN;
	}

	/** @return Where a merchant stands in its town's rota: the town's merchants are sorted on this, so neighbouring ids are not neighbours on it. */
	static int rotaOrder(int objectId) {
		return mix(objectId ^ 0x68E31DA4);
	}

	/**
	 * @param rank Where the merchant stands among its town's merchants sorted by {@link #rotaOrder}.
	 * @param merchants How many merchants the town has.
	 * @return true while the merchant is on shift: a window of {@link #ON_SHIFT} merchants that goes round the rota once a day, so that a town has
	 *         the same number of stores open at every hour, and each merchant keeps its store for a stretch of the day as long as the town has few
	 *         merchants for its stores: about 14 hours in a town of 77, all day in a town of 44 or fewer.
	 */
	static boolean onRota(int rank, int merchants, int secondOfDay) {
		if (merchants <= ON_SHIFT)
			return true;
		return Math.floorMod(rank - firstOnShift(merchants, secondOfDay), merchants) < ON_SHIFT;
	}

	/** @return Where in a town's rota the window of merchants on shift starts at this second of the day. */
	static int firstOnShift(int merchants, int secondOfDay) {
		return (int) ((long) secondOfDay * merchants / DAY_SECONDS);
	}

	/** @return When in a spell's length this resident's spells start, so that residents do not all set off at once. */
	private static int phaseOf(int objectId) {
		return Math.floorMod(mix(objectId ^ 0x5BD1E995), SPELL_SECONDS);
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

	/** A bit mixer, so neighbouring ids and neighbouring spells do not lean the same way. */
	private static int mix(int value) {
		int h = value * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return h & Integer.MAX_VALUE;
	}
}
