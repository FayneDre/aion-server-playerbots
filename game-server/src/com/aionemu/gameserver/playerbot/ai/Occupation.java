package com.aionemu.gameserver.playerbot.ai;

import com.aionemu.commons.utils.Rnd;

/**
 * What a resident is doing with its day.
 * <p>
 * Farming was the whole of a bot's life, which is why a populated map read as a hunting ground rather than a place where people live. An occupation
 * is drawn with a duration and drawn again when it runs out, so the same map shows some bots working, some standing about and some on the road.
 */
public enum Occupation {

	/** Hunting in the surrounding country, which is what every bot did all the time. */
	FARMING(300, 900),
	/** Standing about in a settlement. Doing nothing is a thing people do, and it is most of what makes a village look inhabited. */
	LOITERING(180, 600),
	/** On the road between two settlements. Ends on arrival, or when the time runs out and the bot settles wherever it stands. */
	WANDERING(120, 360);

	/** How much less a bot living out in the country stands about, both in how often it does and for how long. */
	private static final int COUNTRY_IDLING = 4;

	private final int shortestSeconds, longestSeconds;

	Occupation(int shortestSeconds, int longestSeconds) {
		this.shortestSeconds = shortestSeconds;
		this.longestSeconds = longestSeconds;
	}

	/** @return How long this spell of it lasts, in milliseconds. */
	public long draw() {
		return Rnd.get(shortestSeconds, longestSeconds) * 1000L;
	}

	/**
	 * @param livesInASettlement Whether this is somewhere people gather. Out in the country a pause is a pause between fights, not an afternoon: the
	 *          same ten minute spell that suits a village square leaves a character standing alone in a field long enough to be noticed.
	 * @return How long this spell lasts, in milliseconds.
	 */
	public long draw(boolean livesInASettlement) {
		long span = draw();
		return this == LOITERING && !livesInASettlement ? span / COUNTRY_IDLING : span;
	}

	/**
	 * Picks what a bot does next, leaning on its temperament.
	 * <p>
	 * The temperament is fixed to the bot's own id, so one that likes the fields keeps going back to them and one that likes company is usually found
	 * in a village. Without that a population averages out: every bot does a third of everything, and none of them has a character.
	 *
	 * @param objectId The bot's object id, which is what makes its leaning its own.
	 */
	public static Occupation drawFor(int objectId, boolean livesInASettlement) {
		int[] weights = temperamentOf(objectId);
		if (!livesInASettlement) {
			// Standing about is something people do in the place where people are. Out in the fields it is not idling, it is loitering in a hedgerow,
			// and a third of the population doing it at any moment reads as a map full of characters who have forgotten what they came for. Rare
			// rather than never: somebody resting between fights is fine, and that is what is left of it.
			weights[0] += weights[1] - weights[1] / COUNTRY_IDLING;
			weights[1] /= COUNTRY_IDLING;
		}
		int roll = Rnd.get(0, weights[0] + weights[1] + weights[2] - 1);
		if (roll < weights[0])
			return FARMING;
		return roll < weights[0] + weights[1] ? LOITERING : WANDERING;
	}

	/**
	 * @return How much this bot leans towards farming, loitering and wandering. Every bot keeps a taste for all three — a villager who never once
	 *         leaves is as mechanical as one who never stops.
	 */
	private static int[] temperamentOf(int objectId) {
		int hash = Integer.hashCode(objectId * 0x9E3779B9);
		return new int[] { 20 + Math.floorMod(hash, 50), 20 + Math.floorMod(hash / 50, 50), 20 + Math.floorMod(hash / 2500, 50) };
	}
}
