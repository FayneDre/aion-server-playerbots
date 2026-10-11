package com.aionemu.gameserver.playerbot.navmesh;

/**
 * A way down that is not a slope: from the edge of one stretch of ground to a lower one, which a body takes by jumping off and gliding.
 * <p>
 * One way only. What is below is reached from above and never the other way round, so the planner follows a link from {@code fromRegion} to {@code toRegion} and does not
 * know the reverse. The regions are the ids {@link Heightfield#regions} gave when the file was written, which are the ones the coarse grid carries.
 */
public record GlideLink(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, int fromRegion, int toRegion) {
}
