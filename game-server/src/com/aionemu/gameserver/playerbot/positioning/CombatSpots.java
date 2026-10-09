package com.aionemu.gameserver.playerbot.positioning;

import java.util.ArrayList;
import java.util.List;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Where a bot should stand to fight an enemy, worked out on a flat plane from numbers alone.
 * <p>
 * Pure on purpose, as {@code TemplarPlaybook.decide} is: the rule is a function of a {@link Situation}, so the figures in it can be argued over in a test
 * rather than in a play session, and nothing here reads the world. Height is not its business. The spot comes back at the enemy's own height and the
 * caller puts it on the ground the navmesh knows of.
 * <p>
 * One mechanism for every role. Candidate points are laid on a ring round the enemy, the ones that break the role's rule or stand in another monster's
 * notice are dropped, and the nearest of what is left to where the bot already is wins. A role that finds nothing on its first ring tries a nearer one.
 */
public final class CombatSpots {

	/** Points tried on each ring, one every 15 degrees. */
	private static final int SAMPLES = 24;
	/**
	 * How far from straight behind the enemy a melee bot may stand, in degrees. Stricter than the engine's own back arc of 90 either side on purpose: a
	 * cone of 240 degrees, the commonest an enemy states, leaves only 60 either side of directly behind it free.
	 */
	static final float BEHIND_WINDOW_DEGREES = 45;
	/** How far from directly opposite the group the tank may stand, in degrees. */
	static final float FRONT_WINDOW_DEGREES = 45;
	/** How far from the enemy a ranged bot stands when it can: three quarters of the enemy area attacks that are circles are smaller than this. */
	static final float AREA_SAFE_RADIUS = 15;
	/** How much of a skill's reach a ranged bot keeps in hand, so that the cast does not fail on a step the enemy takes. */
	static final float REACH_MARGIN = 2;
	/** Nearest a ranged bot comes before it gives up looking for a safer ring. */
	static final float MIN_STANDOFF = 8;
	/** Nearest a healer comes: it prefers to be out of the area attacks, but not at the price of a member out of reach. */
	static final float MIN_HEALER_STANDOFF = 4;
	/** Nearest to the enemy a healer may be and still count as placed. */
	static final float HEALER_PLACED_STANDOFF = 6;
	/** How much of its heal's reach a healer may use up before it counts as too far from somebody: less than {@link #REACH_MARGIN} keeps in hand. */
	static final float HEALER_PLACED_MARGIN = 1;
	/** How much nearer each further ring is. */
	static final float RING_STEP = 3;
	/** A melee bot stands at this share of its weapon's range, so that a small step by the enemy does not take it out of reach. */
	static final float CONTACT_SHARE = 0.75f;
	/** Never nearer than this to the enemy's own position, which is also where it is standing. */
	static final float MIN_CONTACT = 1f;
	/** The group centre closer than this to the enemy says nothing about which side the group is on. */
	private static final float NO_DIRECTION = 0.5f;

	private CombatSpots() {
	}

	/**
	 * Everything the choice looks at.
	 *
	 * @param enemy The enemy being fought, which the bot stands round.
	 * @param facingX Where the enemy faces, as a unit vector, which in a fight is towards the tank, since it turns to whoever it is hitting.
	 * @param groupCentre The middle of the rest of the group, tank left out. What the tank stands on the far side of.
	 * @param members Every group member the healer has to reach.
	 * @param from Where the bot stands now, which is the tie-break: the nearest acceptable spot wins, so a bot does not walk further than it must.
	 * @param botLevel The bot's level, for the ten level rule in {@link AggroZone}.
	 * @param zones What the other monsters notice, which no spot may fall inside.
	 */
	public record Situation(Vector3f enemy, float facingX, float facingY, Vector3f groupCentre, List<Vector3f> members, Vector3f from, int botLevel,
		List<AggroZone> zones) {
	}

	/**
	 * @param reach How far the bot can hit from: its weapon's range for melee, and for a ranged class or a healer the reach of its skills.
	 * @return The spot, at the enemy's own height, or null when the role has no rule or no acceptable spot exists, in which case the bot stays where it is.
	 */
	public static Vector3f spotFor(BotPosition role, Situation situation, float reach) {
		if (role == BotPosition.NONE)
			return null;
		Vector3f best = null;
		double bestDistance = Double.MAX_VALUE;
		for (float radius : radii(role, reach)) {
			for (int i = 0; i < SAMPLES; i++) {
				double angle = 2 * Math.PI * i / SAMPLES;
				double dx = Math.cos(angle), dy = Math.sin(angle);
				if (!allowsDirection(role, situation, dx, dy))
					continue;
				float px = (float) (situation.enemy().getX() + dx * radius), py = (float) (situation.enemy().getY() + dy * radius);
				if (!isAcceptable(role, situation, reach, px, py))
					continue;
				double distance = Math.hypot(px - situation.from().getX(), py - situation.from().getY());
				if (distance < bestDistance) {
					bestDistance = distance;
					best = new Vector3f(px, py, situation.enemy().getZ());
				}
			}
			if (best != null)
				return best; // the first ring that has anything is the one wanted: a nearer one is only a fallback
		}
		return null;
	}

	/**
	 * @return true if a healer standing where the situation says it does is where a healer belongs: in the half behind the enemy, out of melee, and within
	 *         reach of every member. Looser than the spot it would be sent to, so that a healer that has arrived is always placed and one that has drifted
	 *         a little is not walked back for it.
	 */
	public static boolean isHealerPlaced(Situation s, float reach) {
		if (reach <= 0 || s.members().isEmpty())
			return true; // nothing it could heal, or nobody to heal: there is no place to be wrong about
		double dx = s.from().getX() - s.enemy().getX(), dy = s.from().getY() - s.enemy().getY();
		if (Math.hypot(dx, dy) < HEALER_PLACED_STANDOFF || dx * s.facingX() + dy * s.facingY() > 0)
			return false;
		for (Vector3f member : s.members()) {
			if (Math.hypot(s.from().getX() - member.getX(), s.from().getY() - member.getY()) > reach - HEALER_PLACED_MARGIN)
				return false;
		}
		return true;
	}

	/** @return The rings to try, in the order to try them. */
	static List<Float> radii(BotPosition role, float reach) {
		List<Float> radii = new ArrayList<>();
		switch (role) {
			case FRONT, BEHIND -> radii.add(Math.max(MIN_CONTACT, reach * CONTACT_SHARE));
			case RANGED -> addStandoffRadii(radii, reach, MIN_STANDOFF);
			case HEALER -> addStandoffRadii(radii, reach, MIN_HEALER_STANDOFF);
			case NONE -> {
			}
		}
		return radii;
	}

	private static void addStandoffRadii(List<Float> radii, float reach, float floor) {
		float radius = Math.min(AREA_SAFE_RADIUS, reach - REACH_MARGIN);
		if (radius < floor) {
			if (radius > 0)
				radii.add(radius); // a short reach cannot be improved on; standing further than it is no standing at all
			return;
		}
		for (; radius >= floor; radius -= RING_STEP)
			radii.add(radius);
	}

	/** @return true if a point in this direction from the enemy is on the side of it that the role stands on. */
	private static boolean allowsDirection(BotPosition role, Situation s, double dx, double dy) {
		switch (role) {
			case BEHIND: {
				double length = Math.hypot(s.facingX(), s.facingY());
				if (length == 0)
					return false; // nothing says which way is behind
				return (-dx * s.facingX() - dy * s.facingY()) / length >= Math.cos(Math.toRadians(BEHIND_WINDOW_DEGREES));
			}
			case FRONT: {
				double gx = s.groupCentre().getX() - s.enemy().getX(), gy = s.groupCentre().getY() - s.enemy().getY();
				double length = Math.hypot(gx, gy);
				if (length < NO_DIRECTION)
					return true;
				return (-dx * gx - dy * gy) / length >= Math.cos(Math.toRadians(FRONT_WINDOW_DEGREES));
			}
			case RANGED, HEALER:
				// the half away from the enemy's face, which is the half away from the tank: out of its cleave
				return dx * s.facingX() + dy * s.facingY() <= 0;
			default:
				return false;
		}
	}

	private static boolean isAcceptable(BotPosition role, Situation s, float reach, float px, float py) {
		for (AggroZone zone : s.zones()) {
			if (zone.notices(px, py, s.botLevel()))
				return false;
		}
		if (role == BotPosition.HEALER) {
			if (s.members().isEmpty())
				return false;
			for (Vector3f member : s.members()) {
				if (Math.hypot(px - member.getX(), py - member.getY()) > reach - REACH_MARGIN)
					return false;
			}
		}
		return true;
	}
}
