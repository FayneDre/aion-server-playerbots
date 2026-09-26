package com.aionemu.gameserver.playerbot.movement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.movement.PlayerMoveController;
import java.util.List;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.playerbot.combat.BotRestManager;
import com.aionemu.gameserver.playerbot.movement.BotGeoHelper.Detour;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Moves a bot towards a destination, walking around obstacles in the way.
 * <p>
 * {@link com.aionemu.gameserver.controllers.movement.PlayableMoveController} already implements the right interpolation, but gates it behind a
 * private {@code isControlled()} that only allows server driven movement under fear or confuse. This subclass overrides the two public methods that
 * consult it. Registration goes to {@link MoveTaskManager} rather than PlayerMoveTaskManager, because only the former reports arrival and updates
 * zones.
 * <p>
 * There is no pathfinding in this engine, so routing is reactive: walk as far towards the goal as the ground allows, sidestep when something blocks
 * the way, and give up when that stops making headway. The last part is not optional, since reactive steering always loses in concave geometry.
 */
public class BotMoveController extends PlayerMoveController {

	private static final Logger log = LoggerFactory.getLogger(BotMoveController.class);

	private static final float ARRIVE_OFFSET = 0.5f;
	/**
	 * The ground is sampled on every move tick. Sampling less often makes the bot follow a straight line between two heights and then jump to the
	 * real one, which reads as a stutter on sloped ground.
	 */
	private static final long GEO_Z_UPDATE_INTERVAL = 200;
	/** How much closer to the goal the bot must get for the route to count as progressing. */
	private static final float PROGRESS_STEP = 1.0f;
	/** Without that progress, the route is abandoned rather than letting the bot grind against an obstacle forever. */
	private static final long PROGRESS_TIMEOUT = 5000;
	/** A new destination this close to the previous one continues the same journey, typically a target that moved a little. */
	private static final float SAME_JOURNEY_TOLERANCE = 10f;

	private long nextGeoZUpdate;
	/** Where the bot is ultimately going. */
	private volatile float finalX, finalY, finalZ;
	/** The point currently being walked to: a waypoint of the planned route, or the destination itself. */
	private volatile float goalX, goalY, goalZ;
	/** Waypoints from the navmesh, empty when walking without a plan. */
	private volatile List<Vector3f> route = List.of();
	private int routeIndex;
	/** A route that arrived from a planning thread, waiting for the next leg to adopt it. */
	private volatile List<Vector3f> pendingRoute;
	private volatile boolean hasGoal;
	/** Side the current detour passes obstacles on, kept so successive legs go around the same way instead of oscillating. */
	private int detourSide;
	private double closestToGoal;
	private long lastProgressTime;
	private volatile boolean blocked;

	public BotMoveController(Player owner) {
		super(owner);
	}

	/**
	 * Walks towards the given point, going around obstacles in the way. Long routes are covered in successive legs.
	 *
	 * @return false if the bot is walled in, in which case it does not move at all.
	 */
	public boolean moveToPoint(float x, float y, float z) {
		BotRestManager.standUp(owner); // a seated bot would slide across the ground
		// CM_MOVE does this for a real player: without it the bot stays blinking and untargetable for the full protection minute
		if (owner.isProtectionActive())
			owner.getController().stopProtectionActiveTask();
		// a chased target is re-routed to every second or so: that is the same journey continuing, not a new one, and resetting the progress
		// tracking on each update would disable the anti stuck safeguard for exactly the case that needs it most
		boolean sameJourney = hasGoal && PositionUtil.getDistance(finalX, finalY, x, y) < SAME_JOURNEY_TOLERANCE;
		finalX = x;
		finalY = y;
		finalZ = z;
		// a planned route knows about walls and low obstacles the probes cannot see; without one the bot walks straight at the goal as before
		if (!sameJourney || route.isEmpty()) {
			route = List.of();
			routeIndex = 0;
			// a long journey is planned on another thread, so the bot sets off straight away and adopts the route when it arrives; a short one
			// plans on this very call, in which case the result must be adopted right here or the first leg walks blind for nothing
			NavmeshService.getInstance().planRoute(owner, x, y, z, planned -> offerRoute(planned, x, y));
			if (pendingRoute != null) {
				route = pendingRoute;
				pendingRoute = null;
			}
		}
		hasGoal = true;
		aimAtNextWaypoint();
		if (!sameJourney) {
			blocked = false;
			detourSide = 0;
			closestToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
			lastProgressTime = System.currentTimeMillis();
		}
		return startNextLeg();
	}

	/** @return true if the last route was abandoned because the bot could not find a way through. */
	public boolean isBlocked() {
		return blocked;
	}

	/** @return true if the bot is already on its way to that point, so a moving target does not need a new route on every tick. */
	public boolean isHeadingTo(float x, float y, float tolerance) {
		return hasGoal && PositionUtil.getDistance(finalX, finalY, x, y) < tolerance;
	}

	/**
	 * Called on arrival: continues towards the goal if it is further than the leg just walked, otherwise ends the movement.
	 *
	 * @return true if the bot keeps moving.
	 */
	public boolean continueToGoal() {
		List<Vector3f> planned = pendingRoute;
		if (planned != null) {
			pendingRoute = null;
			route = planned;
			routeIndex = 0;
			aimAtNextWaypoint();
		}
		if (hasGoal && reachedWaypoint() && aimAtNextWaypoint()) {
			closestToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
			lastProgressTime = System.currentTimeMillis();
		}
		if (hasGoal && startNextLeg())
			return true;
		stop();
		return false;
	}

	/**
	 * Takes a route planned elsewhere, if the bot is still going where it was asked to. Stored rather than applied: the movement thread picks it up
	 * at the end of the current leg, which keeps a background thread from rewriting the destination mid-stride.
	 */
	private void offerRoute(List<Vector3f> planned, float forX, float forY) {
		if (planned.isEmpty() || !hasGoal || forX != finalX || forY != finalY)
			return;
		pendingRoute = planned;
		log.info("Bot {} has a route of {} waypoints to {}", owner.getName(), planned.size(), String.format("%.1f %.1f", forX, forY));
	}

	private boolean reachedWaypoint() {
		return PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY) < ARRIVE_OFFSET;
	}

	/**
	 * Points the leg machinery at the next waypoint of the planned route, or straight at the destination when there is no route left.
	 *
	 * @return true if it moved on to a new waypoint.
	 */
	private boolean aimAtNextWaypoint() {
		while (routeIndex < route.size()) {
			Vector3f waypoint = route.get(routeIndex++);
			// the first waypoint is the bot's own cell, and a route may pass close to where it already stands
			if (PositionUtil.getDistance(owner.getX(), owner.getY(), waypoint.getX(), waypoint.getY()) < ARRIVE_OFFSET)
				continue;
			goalX = waypoint.getX();
			goalY = waypoint.getY();
			goalZ = waypoint.getZ();
			return true;
		}
		boolean changed = goalX != finalX || goalY != finalY;
		goalX = finalX;
		goalY = finalY;
		goalZ = finalZ;
		return changed;
	}

	private boolean startNextLeg() {
		if (reachedWaypoint() && routeIndex >= route.size()) {
			hasGoal = false;
			return false;
		}
		Vector3f reachable = BotGeoHelper.reachablePointToward(owner, goalX, goalY, goalZ);
		if (BotGeoHelper.isWorthMovingTo(owner, reachable)) {
			moveToReachablePoint(reachable.getX(), reachable.getY(), reachable.getZ());
			return true;
		}
		Detour detour = BotGeoHelper.detourPointToward(owner, goalX, goalY, goalZ, detourSide);
		if (detour == null) {
			log.info("Bot {} is walled in and gives up moving", owner.getName());
			blocked = true;
			hasGoal = false;
			return false;
		}
		detourSide = detour.side();
		moveToReachablePoint(detour.point().getX(), detour.point().getY(), detour.point().getZ());
		return true;
	}

	private void moveToReachablePoint(float x, float y, float z) {
		boolean destinationChanged = x != getTargetX2() || y != getTargetY2() || z != getTargetZ2();
		setNewDirection(x, y, z, PositionUtil.getHeadingTowards(owner.getX(), owner.getY(), x, y));
		if (!started.get()) {
			startMovingToDestination();
		} else {
			// MoveTaskManager removes the bot on arrival, so re-register (idempotent) instead of assuming we are still ticked
			MoveTaskManager.getInstance().addCreature(owner);
			if (destinationChanged) // clients extrapolate between packets, so only resend when the destination actually moved
				PacketSendUtility.broadcastToSightedPlayers(owner, new SM_MOVE(owner));
		}
	}

	/**
	 * Ends the current movement and tells clients the authoritative position, so they stop extrapolating.
	 */
	public void stop() {
		hasGoal = false;
		route = List.of();
		pendingRoute = null;
		routeIndex = 0;
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false))
			setAndSendStopMove(owner);
	}

	public boolean isArrived() {
		return PositionUtil.getDistance(owner.getX(), owner.getY(), getTargetX2(), getTargetY2()) < ARRIVE_OFFSET;
	}

	@Override
	public void startMovingToDestination() {
		updateLastMove();
		if (!owner.canPerformMove() || !started.compareAndSet(false, true))
			return;

		owner.unsetState(CreatureState.WALK_MODE);
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_EMOTION(owner, EmotionType.RUN));
		setAndSendStartMove(owner);
		MoveTaskManager.getInstance().addCreature(owner);
	}

	@Override
	public void moveToDestination() {
		if (!owner.canPerformMove()) {
			if (started.compareAndSet(true, false))
				setAndSendStopMove(owner);
			updateLastMove();
			return;
		}

		float x = owner.getX();
		float y = owner.getY();
		float z = owner.getZ();
		float distance = (float) PositionUtil.getDistance(x, y, z, getTargetX2(), getTargetY2(), getTargetZ2());
		if (distance < 0.01f) {
			updateLastMove();
			return;
		}

		float speed = StatFunctions.adjustStatByMovementModifier(owner, StatEnum.SPEED, owner.getGameStats().getMovementSpeedFloat());
		long millisElapsed = System.currentTimeMillis() - getLastMoveUpdate();
		float distancePassed = Math.min(speed * millisElapsed / 1000f, distance);
		float fraction = distancePassed / distance;

		float newX = (getTargetX2() - x) * fraction + x;
		float newY = (getTargetY2() - y) * fraction + y;
		World.getInstance().updatePosition(owner, newX, newY, groundZ(newX, newY, (getTargetZ2() - z) * fraction + z), heading, false);
		updateLastMove();
		checkProgress();
	}

	/**
	 * Abandons the route once the bot stops getting closer to its goal. Sidestepping a single obstacle recovers on its own, but a dead end or a U
	 * shaped corridor makes reactive steering bounce between the same two detours indefinitely, which this is the only defence against.
	 */
	private void checkProgress() {
		if (!hasGoal)
			return;
		double distanceToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
		long now = System.currentTimeMillis();
		if (distanceToGoal < closestToGoal - PROGRESS_STEP) {
			closestToGoal = distanceToGoal;
			lastProgressTime = now;
		} else if (now - lastProgressTime > PROGRESS_TIMEOUT) {
			log.info("Bot {} makes no headway towards its goal and gives up moving", owner.getName());
			blocked = true;
			stop();
		}
	}

	/**
	 * Snaps the interpolated height to the ground, so the bot follows slopes instead of sliding along a straight line through them. Most maps have no
	 * heightmap, in which case the interpolated value is kept rather than dropping the bot to zero.
	 */
	private float groundZ(float x, float y, float interpolatedZ) {
		long now = System.currentTimeMillis();
		if (now < nextGeoZUpdate)
			return interpolatedZ;
		nextGeoZUpdate = now + GEO_Z_UPDATE_INTERVAL;

		float geoZ = GeoService.getInstance().getZ(owner.getWorldId(), x, y, interpolatedZ + 2, interpolatedZ - 2, owner.getInstanceId());
		return Float.isNaN(geoZ) ? interpolatedZ : geoZ;
	}

	@Override
	public void abortMove() {
		// engine code calls abortMove blindly (stun, teleport, despawn), so removal must be idempotent
		MoveTaskManager.getInstance().removeCreature(owner);
		super.abortMove();
	}
}
