package com.aionemu.gameserver.playerbot.navmesh;

/**
 * A way down that is not a slope: from the edge of one stretch of ground to a lower one, which a body takes by jumping off and gliding.
 * <p>
 * One way only. What is below is reached from above and never the other way round, so the planner follows a link from {@code fromRegion} to {@code toRegion} and does not
 * know the reverse. A flight link ({@link FlightLinks}) is the same edge flown under power, in either direction and across open air, which is why it
 * shares the type: the planner chains both alike, and only the body that follows the route needs to know which it is. The regions are the ids {@link Heightfield#regions} gave when the file was written, which are the ones the coarse grid carries.
 */
public record GlideLink(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, int fromRegion, int toRegion, boolean flight) {

	public GlideLink(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, int fromRegion, int toRegion) {
		this(fromX, fromY, fromZ, toX, toY, toZ, fromRegion, toRegion, false);
	}
}
