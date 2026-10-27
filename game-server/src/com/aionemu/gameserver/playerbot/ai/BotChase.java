package com.aionemu.gameserver.playerbot.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.positioning.BotPosition;
import com.aionemu.gameserver.playerbot.positioning.BotStations;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Getting to a fight and being where one should be in it: chasing a target out of reach within the leash, the bot's own spot by its anchor, and,
 * in a group, the place its class fights from. Taken out of {@link PlayerBotAI} on 27 October 2026, unchanged; the anchor itself stays in the AI,
 * which reads it everywhere.
 */
final class BotChase {

	private static final Logger log = LoggerFactory.getLogger(BotChase.class);

	private final PlayerBotAI ai;

	/** How long a walk to a fight position is left alone before the place is worked out again. Longer than a chase: the place moves only when the enemy does. */
	private static final long STATION_REROUTE_MILLIS = 1500;
	/** How often the log may say that a healer has no place to stand. */
	private static final long NO_PLACE_LOG_MILLIS = 30_000;
	/** A chase is abandoned after this long, whatever the reason it is not getting anywhere. */
	private static final long MAX_CHASE_MILLIS = 15000;
	/**
	 * Minimum delay between two routes towards the same chased target. Each new route makes clients restart their interpolation, so re-routing on
	 * every attack tick turns a run into a stutter.
	 */
	private static final long CHASE_REROUTE_INTERVAL = 600;
	/**
	 * How far from its anchor a bot may be dragged before it breaks off and comes back. It is the camp radius on purpose: the bot roams that far to
	 * find fights, so a shorter leash would make it abandon the very mobs it just walked to.
	 */
	private static final float LEASH_DISTANCE = BotTargetSelector.HOME_RADIUS;
	/**
	 * How far from the place it shares a bot stands.
	 * <p>
	 * Every bot of a camp was given the same point to walk to and the same five metre tolerance for having arrived, so they settled on top of one
	 * another: seen in game as a pile of characters with a ring of corpses around it, which reads as a spawner rather than as people working a
	 * ground. Kept inside {@link #ANCHOR_TOLERANCE}, so a bot standing on its own spot is still at its anchor and nothing that asks that question
	 * has to learn a second one.
	 */
	private static final float SPREAD_RADIUS = 4f;

	/** This bot's chosen place to stand, and the anchor it was chosen for. Kept because choosing it asks the mesh, and the answer only changes with the anchor. */
	private volatile Vector3f standingSpot;
	private volatile float spotForX, spotForY;
	/** When the current chase began, 0 when the bot is not chasing; and when it was last given a route. */
	private volatile long chaseStartTime;
	private volatile long lastChaseRoute;
	/** When the bot last set out for its place in a fight, so that a route under way is not replaced on every tick. */
	private volatile long lastStationRoute;
	/** When the log last said that the healer had no place to stand. */
	private volatile long lastNoPlaceLog;

	BotChase(PlayerBotAI ai) {
		this.ai = ai;
	}

	/** @return true while the bot is chasing a target out of its reach. */
	boolean isChasing() {
		return chaseStartTime != 0;
	}

	/** Ends the chase in hand: the target is in reach, or given up, or the fight is over. */
	void stop() {
		chaseStartTime = 0;
	}
	/**
	 * Walks the bot to the place its class fights from, when it is not already there.
	 * <p>
	 * Only inside a group and only for the classes that stand behind or back: the tank and the healer have their own rules to come, and a bot alone
	 * walks to what it is fighting as it always did. A route already under way is left to finish for a moment, because the enemy takes a step every
	 * second and a bot re-aimed every time it does never gets anywhere.
	 *
	 * @return true while the bot is on its way, in which case this tick is spent. false when it is placed, has no spot to go to, or cannot get there:
	 *         the old way then decides.
	 */
	boolean takeStation(Creature target) {
		Player bot = ai.getOwner();
		boolean healer = BotStations.handlesHealer(bot);
		if (!PlayerBotConfig.POSITIONING.placesAgainst(target) || !(BotStations.handles(bot) || healer) || BotStations.isWellPlaced(bot, target) || BotStations.isHeldByEnemy(bot, target))
			return false;
		// a healer that is attacking with its spare mana still heals first when somebody is in danger, and a walk would take the tick the heal needs
		if (healer && BotGroupManager.mostHurtMember(bot, PlayerBotAI.HEALER_DANGER_PERCENT, true) != null)
			return false;
		if (!(bot.getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (moveController.isInMove() && now - lastStationRoute < STATION_REROUTE_MILLIS)
			return true;
		Vector3f spot = BotStations.spotFor(bot, target);
		// Already standing on it and still not placed: the spot passed the line of sight as asked from the enemy, and the bot's own look disagrees. Walking
		// to where it stands would spend every tick here; comesIntoSight takes it on from this step.
		if (spot == null || PositionUtil.getDistance(bot.getX(), bot.getY(), spot.getX(), spot.getY()) <= PlayerBotAI.SPOT_TOLERANCE)
			return false;
		lastStationRoute = now;
		moveController.avoid(BotStations.avoidanceFor(bot, target));
		return ai.tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
	}

	/** @return true while the healer is on its way to its place, or has just set out; false when there is no place to go or no way there. */
	boolean walkToHealerStation(Creature fight) {
		Player bot = ai.getOwner();
		if (!(bot.getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (moveController.isInMove() && now - lastStationRoute < STATION_REROUTE_MILLIS)
			return true;
		Vector3f spot = BotStations.spotFor(bot, fight);
		if (spot == null) {
			// said once in a while, since it is asked every second of a fight: with the reason, because "has no place" is a map with no mesh, a group too spread out for the heal's reach,
			// or an enemy with its back to a wall, and three different things to do about it
			if (now - lastNoPlaceLog > NO_PLACE_LOG_MILLIS) {
				lastNoPlaceLog = now;
				log.info("Healer {} has no place to stand against {} at {} {} {} (mesh on this map: {}, heal reach {} m)", bot.getName(), fight.getName(), Math.round(fight.getX()),
					Math.round(fight.getY()), Math.round(fight.getZ()), NavmeshService.getInstance().hasMesh(bot.getWorldId()), Math.round(BotSkillManager.healReach(bot)));
			}
			return false;
		}
		lastStationRoute = now;
		moveController.avoid(BotStations.avoidanceFor(bot, fight));
		return ai.tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
	}

	/**
	 * Walks towards a target that is within the bot's reach but out of its sight, along the mesh's route, which goes round what is in the way.
	 * <p>
	 * Reach was the only question asked, and a target below a stair or round a pillar is in reach: a ranged bot stood there with every cast refused for
	 * want of a line of sight, landed nothing, and gave the fight up after twenty seconds (reported on 19 October). The walk is asked again on every
	 * tick, so it stops on the first step from which the target can be seen. A group's healer is left where it is, which is its whole rule.
	 *
	 * @return true while the bot is on its way.
	 */
	boolean comesIntoSight(Creature target) {
		Player bot = ai.getOwner();
		if (PlayerBotAI.isGroupHealer(bot) || PositionUtil.getDistance(bot, target) > Math.max(BotSkillManager.reach(bot), bot.getGameStats().getAttackRange().getCurrent() / 1000f)
			|| GeoService.getInstance().canSee(bot, target) || !(bot.getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (moveController.isInMove() && now - lastStationRoute < STATION_REROUTE_MILLIS)
			return true;
		lastStationRoute = now;
		return ai.tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	/** @return true if this is a ranged class standing where it should against the target, which then waits for something to cast rather than close in. */
	boolean holdsStation(Creature target) {
		Player bot = ai.getOwner();
		return PlayerBotConfig.POSITIONING.placesAgainst(target) && BotStations.handles(bot) && BotPosition.of(bot) == BotPosition.RANGED && BotStations.isWellPlaced(bot, target);
	}

	/**
	 * @return Where a chase is aimed: the target itself, except for a tank in a group, which closes in on the side of the enemy away from the group. That is
	 *         the one moment it chooses a side: once in reach it stays, because moving a tank round an enemy in the middle of a fight is what the guide ruled out.
	 */
	private Vector3f approachPoint(Creature target) {
		Player bot = ai.getOwner();
		if (PlayerBotConfig.POSITIONING.placesAgainst(target) && BotStations.handlesTank(bot)) {
			Vector3f spot = BotStations.spotFor(bot, target);
			if (spot != null)
				return spot;
		}
		return new Vector3f(target.getX(), target.getY(), target.getZ());
	}

	/**
	 * Walks towards a target that is out of weapon reach.
	 * <p>
	 * Every bound here exists because reactive steering cannot guarantee it will ever arrive: the leash keeps a fleeing target from dragging the bot
	 * across the map, the timeout ends chases that make no progress, and the move controller reports geometry it could not get through.
	 *
	 * @return false if the bot should give this target up.
	 */
	boolean after(Creature target) {
		if (!(ai.getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (chaseStartTime == 0)
			chaseStartTime = now;
		else if (now - chaseStartTime > MAX_CHASE_MILLIS)
			return false;
		if (isOffLeash(target))
			return false;
		if (ai.posture().isAnimating())
			return true; // on its feet, or drawing its weapon; walking now is what makes it slide

		if (moveController.isInMove() && now - lastChaseRoute < CHASE_REROUTE_INTERVAL)
			return true;
		Vector3f goal = approachPoint(target);
		if (moveController.isInMove() && moveController.isHeadingTo(goal.getX(), goal.getY(), PlayerBotAI.RETARGET_STEP))
			return true; // already on its way, and the goal has not moved enough to be worth a new route
		lastChaseRoute = now;
		if (PlayerBotConfig.POSITIONING.placesAgainst(target) && BotStations.handlesTank(ai.getOwner()))
			moveController.avoid(BotStations.avoidanceFor(ai.getOwner(), target));
		return ai.tryMoveTo(moveController, goal.getX(), goal.getY(), goal.getZ());
	}

	/** @return true if a target is further from the bot's anchor than it follows anything, which is a fight it would break off as soon as it chased. */
	boolean isOffLeash(Creature target) {
		// never something that is hitting the bot: on a walk the anchor is the walk's end, and a naga sorcerer casting from beyond it was left alone
		// while it went on casting, which is how most of the deaths of Reshanta's upper layer began (21 of 26 in half an hour were a fight the bot did
		// not pick)
		return PositionUtil.getDistance(ai.anchorX(), ai.anchorY(), target.getX(), target.getY()) > LEASH_DISTANCE && !ai.getOwner().equals(target.getTarget());
	}

	/**
	 * @return This bot's own place at the ground it shares, a fixed offset from the anchor.
	 *         <p>
	 *         Derived from the object id rather than drawn at random, because a spot drawn afresh would move every time the bot came back and the
	 *         camp would shuffle itself whenever anybody returned to it. The same bot therefore stands in the same place all its life, which is what
	 *         somebody working a patch of ground looks like.
	 *         <p>
	 *         The height is the anchor's: the offset puts the spot a few metres away on ground the move controller samples as it walks, so it ends
	 *         up on the real surface whatever this says.
	 */
	Vector3f standingSpot() {
		Vector3f known = standingSpot;
		if (known != null && spotForX == ai.anchorX() && spotForY == ai.anchorY())
			return known;
		int id = ai.getOwner().getObjectId();
		double angle = Math.toRadians(id % 360);
		// Two independent digits of the id, so bots with neighbouring ids do not end up on the same ray out of the centre.
		float radius = SPREAD_RADIUS * (0.4f + 0.6f * (id / 360 % 10) / 9f);
		float x = ai.anchorX() + (float) (Math.cos(angle) * radius), y = ai.anchorY() + (float) (Math.sin(angle) * radius);
		Vector3f chosen = reachableSpot(x, y);
		standingSpot = chosen;
		spotForX = ai.anchorX();
		spotForY = ai.anchorY();
		return chosen;
	}

	/**
	 * @return The offered spot once the mesh agrees a body stands there and can walk to it from the anchor, or the anchor itself.
	 *         <p>
	 *         Geometry alone was choosing these, and in the open that is fine — four metres from a camp fire is grass. Inside a fortress it is a
	 *         pillar, an arcade or the inside of a wall, and because the offset is derived from the object id the same bot was sent to the same
	 *         impossible spot for the whole of its life: refused, refused again, and finally teleported home by the anti-stuck rescue, which is what
	 *         a fortress full of bots freezing and blinking away actually was.
	 */
	private Vector3f reachableSpot(float x, float y) {
		Vector3f anchor = new Vector3f(ai.anchorX(), ai.anchorY(), ai.anchorZ());
		NavmeshService navmesh = NavmeshService.getInstance();
		Vector3f ground = navmesh.groundNear(ai.getOwner().getWorldId(), x, y, ai.anchorZ());
		if (ground == null)
			return anchor; // no mesh for this map, or nothing walkable within reach of the offset
		// Standable is not reachable: the far side of a wall is good ground that joins nothing, and a bot posted on one spends its life asking for a
		// way out. The same question BotPlaces already asks when it scatters villagers around a village centre.
		return navmesh.canReachNearby(ai.getOwner().getWorldId(), ai.anchorX(), ai.anchorY(), ai.anchorZ(), ground.getX(), ground.getY(), ground.getZ()) ? ground : anchor;
	}
}
