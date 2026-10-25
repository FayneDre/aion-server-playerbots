package com.aionemu.gameserver.playerbot.ai;

import java.util.ArrayList;
import java.util.List;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * The destinations a bot's searches ran out of budget for, from one spot, kept to tell a bot that is lost from one that is unlucky.
 * <p>
 * One search out of budget says nothing about the spot, which is why those refusals count towards no rescue. Every one of them from the same spot does:
 * on 25 October a bot 600 m from its village in Cygnea gave up on 21 journeys in a few minutes, a new search every five seconds, and nothing ever brought
 * it back. The same destination asked again does not count twice: six residents of Altgard asking for one hunting ground 1.8 km away were rescued 14 times
 * in half an hour on 24 October for being refused it, and they were not lost.
 */
final class LostTrail {

	/** How many different destinations must give up from one spot within {@link #WINDOW_MILLIS} for the bot to be lost. */
	static final int DESTINATIONS = 4;
	static final long WINDOW_MILLIS = 5 * 60_000L;
	/** How far a bot may have moved between two give-ups for both to count as made from the same spot, in metres. */
	static final float SAME_SPOT = 3;
	/** How far apart two destinations must be to count as different ones, in metres. */
	static final float SAME_DESTINATION = 10;

	/** Where the give-ups being counted were asked from, null when none is, and since when. */
	private Vector3f from;
	private long since;
	private final List<Vector3f> goals = new ArrayList<>();

	/**
	 * Counts a destination given up for want of budget.
	 *
	 * @param goal Null when the refusal did not say where it was going, which counts towards nothing.
	 * @return true once enough different destinations have been given up from this spot lately, the count then starting over.
	 */
	boolean gaveUp(Vector3f here, Vector3f goal, long now) {
		if (from == null || now - since > WINDOW_MILLIS
			|| PositionUtil.getDistance(from.getX(), from.getY(), from.getZ(), here.getX(), here.getY(), here.getZ()) > SAME_SPOT) {
			from = here;
			since = now;
			goals.clear();
		}
		if (goal != null && goals.stream().noneMatch(known -> PositionUtil.getDistance(known.getX(), known.getY(), goal.getX(), goal.getY()) < SAME_DESTINATION))
			goals.add(goal);
		if (goals.size() < DESTINATIONS)
			return false;
		from = null;
		goals.clear();
		return true;
	}
}
