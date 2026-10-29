package com.aionemu.gameserver.playerbot.world;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * The journeys the planner has proved shut, remembered for every bot: a bot refused the way to a place tells the others standing about where it stood.
 * <p>
 * Each bot used to learn it alone, with a search of one to four seconds of a route thread. On 29 October 192 of the 464 day plans given up in half an
 * hour were 68 goals refused more than once, by different bots. Only a proof is kept: a search that merely ran out of budget says as much about the
 * bot's luck as about the place, and keeping it for good made no difference to the numbers when it was tried.
 * <p>
 * A "no way" holds for a start on the same ground, which is taken as within {@value #SAME_GROUND_METRES} m across and {@value #SAME_FLOOR_METRES} m in
 * height of where it was proved, since a town's floors lie above one another and the stairs may be the way from one and not from the other. A goal cut
 * off is the goal's doing, whoever asks, and is kept for a shorter time, since the last stretch is judged from a guide that depends on the start.
 * A goal refused from {@value #REFUSED_FROM_THIS_MANY_PLACES} places that are not the same ground is taken for one nobody reaches: on 29 October one spot
 * before a keeper's counter in Pandaemonium was refused from thirty places across the town, and the proof of each held only for the bot that asked.
 */
public final class BotRefusals {

	private static final float SAME_GROUND_METRES = 30, SAME_FLOOR_METRES = 5, SAME_GOAL_METRES = 3;
	private static final long NO_WAY_MILLIS = 20 * 60_000L, CUT_OFF_MILLIS = 10 * 60_000L;
	private static final int MOST_PER_MAP = 2000, REFUSED_FROM_THIS_MANY_PLACES = 3;

	private record Refusal(Vector3f from, Vector3f goal, boolean goalsDoing, long until) {
	}

	private static final Map<Integer, List<Refusal>> byMap = new ConcurrentHashMap<>();

	private BotRefusals() {
	}

	/**
	 * @param goalCutOff true for a goal proved cut off from the ground round it, false for a proof that nothing walks from the start to the goal
	 */
	public static void remember(int worldId, Vector3f from, Vector3f goal, boolean goalCutOff) {
		long now = System.currentTimeMillis();
		List<Refusal> refusals = byMap.computeIfAbsent(worldId, id -> new CopyOnWriteArrayList<>());
		refusals.removeIf(known -> known.until <= now);
		if (refusals.size() >= MOST_PER_MAP || isRefused(worldId, from, goal))
			return;
		refusals.add(new Refusal(from, goal, goalCutOff, now + (goalCutOff ? CUT_OFF_MILLIS : NO_WAY_MILLIS)));
	}

	/** @return true if a bot standing where {@code from} is has been proved unable to reach {@code goal}, by itself or by another standing near it. */
	public static boolean isRefused(int worldId, Vector3f from, Vector3f goal) {
		List<Refusal> refusals = byMap.get(worldId);
		if (refusals == null)
			return false;
		long now = System.currentTimeMillis();
		int places = 0;
		for (Refusal known : refusals) {
			if (known.until <= now || PositionUtil.getDistance(known.goal.getX(), known.goal.getY(), known.goal.getZ(), goal.getX(), goal.getY(), goal.getZ()) > SAME_GOAL_METRES)
				continue;
			// each entry is a start no earlier one covered, so the entries for a goal are the different places it was refused from
			places++;
			if (known.goalsDoing || PositionUtil.getDistance(known.from.getX(), known.from.getY(), from.getX(), from.getY()) <= SAME_GROUND_METRES
				&& Math.abs(known.from.getZ() - from.getZ()) <= SAME_FLOOR_METRES)
				return true;
		}
		return places >= REFUSED_FROM_THIS_MANY_PLACES;
	}
}
