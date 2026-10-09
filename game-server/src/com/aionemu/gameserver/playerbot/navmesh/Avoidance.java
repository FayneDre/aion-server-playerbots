package com.aionemu.gameserver.playerbot.navmesh;

/**
 * Ground a route should keep out of where it can, and how much it minds.
 * <p>
 * Not a wall: the search may still cross it when there is no other way, at a price. That is the difference from a cell the mesh marks unwalkable, and the
 * reason this is a cost and not a hole: a bot with nowhere else to go must still go, and one that is told to avoid something it cannot get round would
 * otherwise be told there is no route at all.
 */
@FunctionalInterface
public interface Avoidance {

	/** Nothing to avoid, which is every route that is not somebody keeping clear of a monster. */
	Avoidance NONE = (x, y) -> 0f;

	/**
	 * @return What walking over this point costs in addition to its length, as a multiple of it: 0 for ground that does not matter, 15 for ground that is
	 *         worth a fifteen times longer walk to avoid.
	 */
	float extra(float x, float y);
}
