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
 * @param padding The two bodies' radii added together. The engine measures the range from edge to edge, not from centre to centre, so a creature is
 *          noticed that much further out than the range alone says: for a large monster that is a few metres, which is the difference between standing
 *          just outside the notice and just inside it.
 */
public record AggroZone(float x, float y, float facingX, float facingY, float range, float angle, int level, boolean guard, float padding) {

	/** A zone measured from centre to centre, which is how the figures in the tests are made up. */
	public AggroZone(float x, float y, float facingX, float facingY, float range, float angle, int level, boolean guard) {
		this(x, y, facingX, facingY, range, angle, level, guard, 0f);
	}

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
		double centres = Math.hypot(px - x, py - y);
		double distance = Math.max(0, centres - padding);
		if (distance > range)
			return false;
		if (angle >= 360 || distance <= shortRange())
			return true;
		// outside the short radius it is only noticed inside the cone: within half the angle either side of where the monster faces
		double cosine = ((px - x) * facingX + (py - y) * facingY) / centres;
		return cosine >= Math.cos(Math.toRadians(angle / 2));
	}
}
