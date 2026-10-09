package com.aionemu.gameserver.playerbot.positioning;

/**
 * The ground a monster notices people on, as the engine works it out ({@code CreatureEventHandler.isInSeeRange} and {@code validateAggro}).
 * <p>
 * Read per monster and never assumed: of the 57 000 templates that have a range, 34 000 state no angle and see all round, 14 700 see a cone of 240
 * degrees, and 3 400 state 0 and never notice anyone. A bot that must not draw a new enemy has to ask each one.
 *
 * @param x Where the monster stands.
 * @param facingX Where it faces, as a unit vector. Ignored for a monster that sees all round.
 * @param range How far it notices, in metres. 0 means it never does.
 * @param angle The width of the cone it notices in, in degrees. 360 is all round, 0 means it never notices anyone.
 * @param level The monster's level, which decides whether a bot is far enough above it to be ignored.
 * @param guard Whether it is a guard, which notices anyone whatever their level.
 */
public record AggroZone(float x, float y, float facingX, float facingY, float range, float angle, int level, boolean guard) {

	/** A creature this many levels above a monster is beneath its notice, guards excepted. */
	static final int GREY_LEVEL_GAP = 10;

	/**
	 * @return How near a creature has to be to be noticed from any direction, which is half the range for a short one and 4 m otherwise. The engine
	 *         applies it outside the cone, so a monster that sees all round makes no use of it.
	 */
	public float shortRange() {
		return range < 8 ? range / 2 : 4;
	}

	/** @return true if a creature of this level standing there would be noticed by this monster. */
	public boolean notices(float px, float py, int creatureLevel) {
		if (range <= 0 || angle <= 0)
			return false;
		if (!guard && creatureLevel - level >= GREY_LEVEL_GAP)
			return false;
		double distance = Math.hypot(px - x, py - y);
		if (distance > range)
			return false;
		if (angle >= 360 || distance <= shortRange())
			return true;
		// outside the short radius it is only noticed inside the cone: within half the angle either side of where the monster faces
		double cosine = ((px - x) * facingX + (py - y) * facingY) / distance;
		return cosine >= Math.cos(Math.toRadians(angle / 2));
	}
}
