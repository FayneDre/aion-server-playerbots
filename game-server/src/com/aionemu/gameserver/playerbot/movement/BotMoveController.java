package com.aionemu.gameserver.playerbot.movement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.movement.MovementMask;
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
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.navmesh.BotPathFinder;
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
	/**
	 * How long a destination abandoned for lack of headway is refused. Long enough that the caller's refusals accumulate towards its rescue rather
	 * than being reset by a fresh attempt every few seconds, short enough that ground blocked by a passing creature is tried again soon.
	 */
	private static final long NO_HEADWAY_MEMORY = 30000;
	/**
	 * How many give ups in a row on one destination are taken for a refusal. Three rather than one, because a single give up really does prove
	 * nothing and the reactive layer crosses open ground perfectly well without a plan; three in a row on the same spot is not bad luck.
	 */
	private static final int GIVE_UPS_BEFORE_REFUSING = 3;
	/** How long a destination given up on that often is left alone. Longer than {@link #NO_HEADWAY_MEMORY}: three searches is dearer evidence. */
	private static final long GAVE_UP_MEMORY = 300000;
	/** A new destination this close to the previous one continues the same journey, typically a target that moved a little. */
	private static final float SAME_JOURNEY_TOLERANCE = 10f;
	/**
	 * How far the destination must drift from what the route in hand was planned for before that route is thrown away.
	 * <p>
	 * Planning afresh on every call looks harmless and is not: the two ways round an obstacle usually cost within a few metres of each other, so
	 * successive plans pick opposite sides and the bot walks back and forth between them. A plan is kept until it no longer leads where the bot is
	 * going, which is what makes its walk look decided rather than hesitant.
	 */
	private static final float REPLAN_DISTANCE = 2f;

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
	/** Whether the point being walked to came from the navmesh, and so needs no second opinion on the ground between here and there. */
	private volatile boolean onPlannedWaypoint;
	/**
	 * Whether the leg being walked is a sidestep around an obstacle rather than a step towards the goal. A sidestep only makes sense walked to its
	 * end, so this is what protects it from being reconsidered halfway.
	 */
	private volatile boolean onDetour;
	/** Where the route in hand was planned to, which is what says whether it still leads anywhere useful. */
	private volatile float plannedForX, plannedForY;
	/**
	 * Whether a route for the journey in hand has been asked for and not yet answered. A long route is planned on another thread, so for a moment the
	 * bot has a destination and no plan, and what the reactive probes see in that moment is a wall — which is how a bot came to abandon a journey the
	 * navmesh was in the middle of solving for it.
	 */
	private volatile boolean awaitingPlan;
	/** The last destination the planner answered "no route" for, kept so the refusal can be given synchronously to whoever asks again. */
	private volatile float noRouteX, noRouteY;
	private volatile boolean hasNoRoute;
	/** The last destination abandoned for lack of headway, and until when asking for it again is refused rather than tried afresh. */
	private volatile float noHeadwayX, noHeadwayY;
	private volatile long noHeadwayUntil;
	/** The destination the planner has been giving up on, and how many times in a row, so that a repeated give up can be read as the refusal it is. */
	private volatile float gaveUpX, gaveUpY;
	private volatile int gaveUpCount;
	private volatile boolean hasGoal;
	/**
	 * How often a climbing body is told where it is, in milliseconds.
	 * <p>
	 * A flight packet carries a velocity, and a velocity is one second of travel: the clients fly the body to where it says and stop there. So this
	 * has to be comfortably inside a second, or the body arrives and waits — which from the ground is a climb that sets off, stops and jumps. Ten
	 * times a second is the other end of it and is worse: that many announcements of a change of direction never get going at all.
	 */
	private static final long FLIGHT_TELL_MILLIS = 500;
	/**
	 * The most time one flight step may account for, in seconds, however long its tick waited.
	 * <p>
	 * Movement is paced by the clock rather than by its own period, so a late step covers the ground it missed. On the ground that is invisible; in
	 * the air a step delayed by seconds covers the whole climb in one move, and the body is simply at the top. Lateness beyond this is written off: a
	 * starved flight is slower than it should be, and never a jump.
	 */
	private static final float LONGEST_FLIGHT_STEP_SECONDS = 0.3f;
	/** Near enough to the end of a flight leg to call it reached. */
	private static final float FLIGHT_ARRIVED = 0.5f;

	/** True while the body is flying a leg, which is what tells {@link #moveToDestination} to leave the ground out of it. */
	private volatile boolean flying;
	/** True once the current flight leg has been reached, for the flight to move on to its next phase. */
	private volatile boolean flightLegDone;
	/** How fast the current flight leg is flown, which is not always the body's own speed: a flight that has failed comes down faster. */
	private volatile float flightSpeed;
	/** Whether this leg goes up, which decides how it is told to the clients -- see {@link #beginFlightVelocity}. */
	private volatile boolean flightRising;
	private long lastFlightTell;
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
		// Answering "no" to a destination already found to have no route, before doing anything else.
		// A long route is planned on another thread, so by the time the answer comes back this method has long since returned "on its way". The
		// caller therefore never hears the refusal, asks again on the next tick, and gets "on its way" again: two bots stood in a pocket of the map
		// asking for the same village sixty-six times in three minutes. Remembering the refusal is what turns it into an answer.
		if (hasNoRoute && PositionUtil.getDistance(noRouteX, noRouteY, x, y) < SAME_JOURNEY_TOLERANCE)
			return false;
		// A destination just abandoned for lack of headway is refused for a while, instead of being set out for again as though it were new.
		// Giving up calls stop(), which clears hasGoal, so the identical destination asked for on the next tick no longer counted as the same
		// journey: the blocked flag was wiped, the attempt reported a move, and the caller's anti-stuck counter went back to zero. A bot wedged two
		// metres from its goal rode that loop for five minutes without ever being rescued, because nothing above it was ever told anything was wrong.
		if (System.currentTimeMillis() < noHeadwayUntil && PositionUtil.getDistance(noHeadwayX, noHeadwayY, x, y) < SAME_JOURNEY_TOLERANCE)
			return false;
		hasNoRoute = false; // somewhere else is being asked for, so the last refusal says nothing about it
		BotRestManager.standUp(owner); // a seated bot would slide across the ground
		// CM_MOVE does this for a real player: without it the bot stays blinking and untargetable for the full protection minute
		if (owner.isProtectionActive())
			owner.getController().stopProtectionActiveTask();
		// a chased target is re-routed to every second or so: that is the same journey continuing, not a new one, and resetting the progress
		// tracking on each update would disable the anti stuck safeguard for exactly the case that needs it most. This is the only thing that
		// distance decides now: two points ten metres apart can still be on opposite sides of a fence, so closeness alone never excuses skipping a
		// fresh plan, only planning is cheap enough that it never needed to be skipped for its own sake.
		boolean sameJourney = hasGoal && PositionUtil.getDistance(finalX, finalY, x, y) < SAME_JOURNEY_TOLERANCE;
		finalX = x;
		finalY = y;
		finalZ = z;
		// set before planning: offerRoute discards its result as stale once hasGoal no longer matches this call, and a plan that resolves
		// synchronously (any short distance) runs inside planRoute below, before this method would otherwise have set it
		hasGoal = true;
		// A sidestep is a commitment. Re-deciding one because the destination shifted a little — which is exactly what following a walking leader
		// does, every tick — restarts it from a position already off to one side, and the fresh deviation is measured from a bearing that has itself
		// rotated. The deviations then compound and the bot arcs further and further out for an obstacle it had already cleared. Solo movement never
		// showed this because it waits for the leg to end before looking again, by which time the direct way is usually open.
		if (sameJourney && onDetour && !isArrived())
			return true;
		// a planned route knows about walls and low obstacles the probes cannot see; without one the bot walks straight at the goal as before
		if (pendingRoute != null && plannedForX == x && plannedForY == y) {
			// the plan asked for on an earlier call has arrived: taking it is the whole point of having waited, and planning again here would throw
			// the answer away and start the wait over
			route = pendingRoute;
			pendingRoute = null;
			routeIndex = 0;
			aimAtNextWaypoint();
		} else if (route.isEmpty() || PositionUtil.getDistance(plannedForX, plannedForY, x, y) > REPLAN_DISTANCE) {
			route = List.of();
			routeIndex = 0;
			plannedForX = x;
			plannedForY = y;
			awaitingPlan = true; // before asking, so a plan that resolves synchronously clears it again inside the call below
			// a long journey is planned on another thread, so the bot waits where it stands rather than setting off blind; a short one plans on this
			// very call, in which case the result must be adopted right here or the first leg walks for nothing
			NavmeshService.getInstance().planRoute(owner, x, y, z, planned -> offerRoute(planned, x, y));
			if (pendingRoute != null) {
				route = pendingRoute;
				pendingRoute = null;
			}
			aimAtNextWaypoint();
		} else if (routeIndex >= route.size()) {
			// keeping the route, but its waypoints are all behind us: the last stretch is walked at the destination itself, which may have shifted
			aimAtNextWaypoint();
		}
		// A short journey is planned inside the call above, so offerRoute has already run and may have proved there is no way -- it says so by
		// clearing hasGoal. Falling through from here would hand the goal to the reactive layer, which walks straight at it, and the reset just
		// below would wipe the very flags that recorded the refusal. The bot then alternates between being proved wrong and stepping aside, which
		// is how one spent twenty-five minutes on a roof: every sidestep counted as a move, so the anti-stuck rescue was never reached.
		if (!hasGoal)
			return false;
		if (!sameJourney) {
			blocked = false;
			detourSide = 0;
			onDetour = false;
			closestToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
			lastProgressTime = System.currentTimeMillis();
		}
		return startNextLeg();
	}

	private static String describe(float x, float y, float z) {
		return String.format("%.1f %.1f %.1f", x, y, z);
	}

	/** @return true if the last route was abandoned because the bot could not find a way through. */
	public boolean isBlocked() {
		return blocked;
	}

	/**
	 * Flies the body to a point in the air, on the tick the engine already runs for every moving creature.
	 * <p>
	 * This is what the flight itself used to do in a loop of its own, and the move was the whole of milestone two: moving a body is movement, it
	 * belongs on the movement tick, and the loop that did it elsewhere had its first step wait 7.8 seconds behind eighty bots thinking.
	 *
	 * @param speed How fast to fly it, which is the body's own speed except when a failed flight is being brought down.
	 */
	public void flyLegTo(float x, float y, float z, float speed) {
		flying = true;
		flightLegDone = false;
		flightSpeed = speed;
		flightRising = z > owner.getZ() + FLIGHT_ARRIVED;
		tellFlightLeg(x, y, z);
		updateLastMove();
		MoveTaskManager.getInstance().addCreature(owner);
	}

	/** @return true once the leg handed to {@link #flyLegTo} has been reached. */
	public boolean isFlightLegDone() {
		return flightLegDone;
	}

	/** @return true while this controller is flying the body, which is false again the moment anything interrupts it. */
	public boolean isFlying() {
		return flying;
	}

	/** Ends the flight: the body stops where it is, and the clients are told so rather than left interpolating past it. */
	public void endFlight() {
		flying = false;
		MoveTaskManager.getInstance().removeCreature(owner);
		setAndSendStopMove(owner);
	}

	/**
	 * Tells the clients about the leg, in the form each half of it needs.
	 * <p>
	 * <b>Coming down, a destination is enough</b> — {@link #setAndSendStartMove}, the same announcement every walking journey makes, and each client
	 * draws its own way there. Clients clamp a body they are drawing to the ground, and on a descent that clamp is simply the truth.
	 * <p>
	 * <b>Going up, the body has to be driven</b>, because no client lifts one on its own: given a destination overhead it walks the body there along
	 * the ground, and the target window reads "Altitude =" for the whole climb. So the server says the position itself, in the player form —
	 * {@code POSITION | MANUAL} with a velocity, which is what {@code SM_PLAYER_INFO} builds when it has only a destination
	 * ({@code normalize(target - position) * movementSpeed}) and what {@code CM_MOVE} reads coming the other way. It is also the only form that gets
	 * the flight animation drawn rather than the gliding one.
	 */
	private void tellFlightLeg(float x, float y, float z) {
		lastFlightTell = System.currentTimeMillis();
		if (flightRising)
			beginFlightVelocity(x, y, z, flightSpeed);
		else
			beginFlightLeg(x, y, z);
	}

	private void beginFlightLeg(float x, float y, float z) {
		// heading towards the destination, so the body faces where it is going -- a flight leg is a slant, and a slant has a direction
		setNewDirection(x, y, z, PositionUtil.getHeadingTowards(owner.getX(), owner.getY(), x, y));
		setAndSendStartMove(owner);
	}

	/**
	 * <b>The vector is one second of travel, which is what sets the rate these go out at.</b> A client reaches {@code position + vector} and stops
	 * there, exactly as the server does with {@code setNewDirection(x + vectorX, ...)} when it receives one. See {@link #FLIGHT_TELL_MILLIS}.
	 */
	private void beginFlightVelocity(float x, float y, float z, float speed) {
		float gapX = x - owner.getX(), gapY = y - owner.getY(), gapZ = z - owner.getZ();
		float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY + gapZ * gapZ);
		if (gap < 0.01f)
			return;
		// Never past the end of the leg. The vector is both the speed and a second of travel, so a full one sent when less than a second of climb
		// remains points every client at a spot beyond the top: they fly through the apex, and the packet that ends the leg pulls them back down to
		// it. Shortened, the last packets of a climb also slow it, which is what arriving somewhere looks like.
		float carry = Math.min(speed, gap);
		vectorX = gapX / gap * carry;
		vectorY = gapY / gap * carry;
		vectorZ = gapZ / gap * carry;
		setNewDirection(x, y, z, PositionUtil.getHeadingTowards(owner.getX(), owner.getY(), x, y));
		movementMask = (byte) (MovementMask.POSITION | MovementMask.MANUAL);
		setInMove(true);
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_MOVE(owner));
	}

	/**
	 * One step of a flight leg: the same interpolation walking does, in three dimensions and without the ground.
	 * <p>
	 * {@link #groundZ} is deliberately absent. It is what makes a walking bot follow a slope, and it is exactly what would pin a flying one to the
	 * terrain -- the fault milestone one worked around by keeping its own loop.
	 */
	private void flyOneStep() {
		float x = owner.getX(), y = owner.getY(), z = owner.getZ();
		float gapX = getTargetX2() - x, gapY = getTargetY2() - y, gapZ = getTargetZ2() - z;
		float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY + gapZ * gapZ);
		if (gap <= FLIGHT_ARRIVED) {
			flightLegDone = true; // the same test MoveTaskManager will apply a moment later through isArrived, so the two cannot disagree
			updateLastMove();
			return;
		}

		float seconds = Math.min((System.currentTimeMillis() - getLastMoveUpdate()) / 1000f, LONGEST_FLIGHT_STEP_SECONDS);
		float fraction = Math.min(flightSpeed * seconds / gap, 1);
		World.getInstance().updatePosition(owner, x + gapX * fraction, y + gapY * fraction, z + gapZ * fraction, heading, true);
		updateLastMove();
		owner.getController().onMove();
		// The step that reaches the target is the one that finishes the leg, and it has to say so itself: MoveTaskManager asks isArrived right after
		// this and drops the body from the movement tick the moment it says yes, so there is no next step to notice. Left to the next step, every leg
		// ended a second and a half late -- the watchdog in BotFlight fired twice a flight, at the apex and again on the ground.
		// Asked of what is left *after* the move rather than of the move being a whole one, because the two have to agree with isArrived exactly: a
		// step that stopped 47 cm short satisfied the removal and not the flag, and the leg hung there until the watchdog handed it over again.
		if (gap * (1 - fraction) <= FLIGHT_ARRIVED)
			flightLegDone = true;

		if (flightRising && System.currentTimeMillis() - lastFlightTell >= FLIGHT_TELL_MILLIS)
			tellFlightLeg(getTargetX2(), getTargetY2(), getTargetZ2());
	}

	/**
	 * @return true while the bot still has somewhere to be. Goes false the moment a journey ends, however it ends — arrived, abandoned for lack of
	 *         progress, walled in, stopped, or interrupted — which makes it the one honest answer to "is this bot still on its way".
	 */
	public boolean isTravelling() {
		return hasGoal;
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
	private void offerRoute(BotPathFinder.Route answer, float forX, float forY) {
		if (!hasGoal || forX != finalX || forY != finalY)
			return; // an answer to a journey that is over, or to an older one
		awaitingPlan = false; // answered, whether or not it found a way: either way the bot stops waiting
		List<Vector3f> planned = answer.waypoints();
		if (planned.isEmpty()) {
			log.info("Bot {} has no route to {}{}", owner.getName(), String.format("%.1f %.1f", forX, forY),
				answer.gaveUp() ? " (the search gave up)" : "");
			// "No route" ends the journey rather than leaving the bot walking at it. The reactive layer can cross a field and step round a rock; it
			// cannot find its way across a region the mesh says is not joined to this one. Left to walk anyway, the bot never arrives and never
			// reports a failure either, so the ai keeps choosing the same destination — one villager in a pocket of the map asked for the same
			// village sixty times in three minutes, and nothing above ever heard that it could not be had.
			//
			// A search that gave up is not that: it ran out of budget and proved nothing, so the bot still sets off, and distance is the only guide
			// left as to whether that is reasonable. A search that finished empty is proof, and proof holds at any distance. That distinction was
			// missing, and it is what kept a bot on a roof walking at a goal eighty metres below it for the whole twenty-five minutes the server was
			// up: the way down had been disproved, over sixty-five metres, and the proof was discarded for being a short journey.
			if (!answer.gaveUp() || PositionUtil.getDistance(owner.getX(), owner.getY(), forX, forY) > BotPathFinder.LONG_DISTANCE) {
				noRouteX = forX;
				noRouteY = forY;
				hasNoRoute = true;
				blocked = true;
				hasGoal = false;
				gaveUpCount = 0;
				return;
			}
			// One give up proves nothing, and the bot is right to set off on the reactive layer. The same give up over and over is evidence of
			// another kind: not that the ground is unjoined, but that nothing here is ever going to answer, and walking at it meanwhile is what
			// puts a bot up the rock wall round Verteron. It climbs, wedges, is rescued home to the brazier, picks the same spot again -- because
			// the only thing that ever recorded a refusal was a proof, and no proof was coming.
			if (PositionUtil.getDistance(gaveUpX, gaveUpY, forX, forY) < SAME_JOURNEY_TOLERANCE) {
				gaveUpCount++;
			} else {
				gaveUpX = forX;
				gaveUpY = forY;
				gaveUpCount = 1;
			}
			if (gaveUpCount >= GIVE_UPS_BEFORE_REFUSING) {
				log.info("Bot {} gave up on {} {} times running and leaves it alone", owner.getName(),
					String.format("%.1f %.1f", forX, forY), gaveUpCount);
				// Timed rather than permanent, like the headway refusal it borrows: a search gives up for want of budget, and the budget it needed
				// depends on where the bot was standing when it asked. Somewhere else, later, the same place may well answer.
				noHeadwayX = forX;
				noHeadwayY = forY;
				noHeadwayUntil = System.currentTimeMillis() + GAVE_UP_MEMORY;
				gaveUpCount = 0;
				blocked = true;
				hasGoal = false;
			}
			return;
		}
		pendingRoute = planned;
		log.info("Bot {} has a route of {} waypoints to {}", owner.getName(), planned.size(), String.format("%.1f %.1f", forX, forY));
		// A bot that waited for this plan is standing still, so nothing will come back to pick it up: no leg is running, so continueToGoal is never
		// called, and the ai only asks again while the bot is on its way somewhere. Restarting the journey on a task thread keeps this one off the
		// movement state, which belongs to whoever calls moveToPoint.
		if (!isInMove())
			BotScheduler.getInstance().execute(() -> moveToPoint(finalX, finalY, finalZ));
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
			onPlannedWaypoint = true;
			return true;
		}
		boolean changed = goalX != finalX || goalY != finalY;
		goalX = finalX;
		goalY = finalY;
		goalZ = finalZ;
		// the destination itself is whatever the caller asked for — a creature's live position, a spot in a town — and no waypoint the mesh vetted
		onPlannedWaypoint = false;
		return changed;
	}

	private boolean startNextLeg() {
		if (reachedWaypoint() && routeIndex >= route.size()) {
			hasGoal = false;
			return false;
		}
		// A planned waypoint is already proven: the search only ever stepped between walkable cells, and string pulling only kept this segment
		// because a straight walk down it stays on eroded ground the whole way. Re-deriving that from raycasts raised a metre off the ground can
		// only make it worse — they miss anything shorter than that, so the bot walks into it, and they flag what the route already went around,
		// so the bot leaves a good plan to sidestep into geometry nobody planned for.
		if (onPlannedWaypoint) {
			Vector3f step = BotGeoHelper.clearOfSpawnedObstacles(owner, goalX, goalY, goalZ);
			if (BotGeoHelper.isWorthMovingTo(owner, step)) {
				onDetour = false;
				moveToReachablePoint(step.getX(), step.getY(), step.getZ());
				return true;
			}
			// something spawned across the way: that genuinely is the reactive layer's problem, so fall through to it
		}
		Vector3f reachable = BotGeoHelper.reachablePointToward(owner, goalX, goalY, goalZ);
		if (BotGeoHelper.isWorthMovingTo(owner, reachable)) {
			onDetour = false; // the way ahead is open again, so nothing is being walked around any more
			moveToReachablePoint(reachable.getX(), reachable.getY(), reachable.getZ());
			return true;
		}
		Detour detour = BotGeoHelper.detourPointToward(owner, goalX, goalY, goalZ, detourSide);
		if (detour == null) {
			if (onPlannedWaypoint) {
				// The probes see no way out at all, which is what being wedged inside geometry looks like from the inside. The mesh still says
				// this waypoint is ground a body fits on, and the server drives the bot's position, so walking the plan is what frees it. Without
				// this, any disagreement between the two leaves a bot standing still for good.
				onDetour = false;
				moveToReachablePoint(goalX, goalY, goalZ);
				return true;
			}
			if (awaitingPlan)
				return true; // the mesh is still working on this journey: what the probes see now is not the way it will be walked
			// with the position: the reactive probes are blind below a metre, so when they give up the only way to tell a real dead end from a
			// low obstacle they cannot see is to ask the mesh about this exact pair of points afterwards (NavmeshTool <mapId> path x1 y1 x2 y2)
			log.info("Bot {} is walled in at {} and gives up moving towards {}", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
				describe(goalX, goalY, goalZ));
			blocked = true;
			hasGoal = false;
			return false;
		}
		detourSide = detour.side();
		if (!onDetour) // once per obstacle, not once per tick: enough to measure how wide a sidestep really goes
			log.info("Bot {} steps aside at {} to {}, heading for {}", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
				describe(detour.point().getX(), detour.point().getY(), detour.point().getZ()), describe(goalX, goalY, goalZ));
		onDetour = true;
		moveToReachablePoint(detour.point().getX(), detour.point().getY(), detour.point().getZ());
		return true;
	}

	private void moveToReachablePoint(float x, float y, float z) {
		if (BotFlight.isInTheAir(owner)) {
			// Two things writing one body's position is the oldest fault in this module, and in the air it is also the most visible: this one pins z
			// to the ground on every tick while the flight pushes it up, so the clients are told two different places a second apart.
			log.warn("Bot {} was asked to walk to {} {} while it is flying, and is not", owner.getName(), x, y);
			return;
		}
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
		if (flying)
			return; // a flight ends through endFlight, and every other caller of this is a walking journey being given up
		hasGoal = false;
		route = List.of();
		pendingRoute = null;
		routeIndex = 0;
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false)) {
			setAndSendStopMove(owner);
			owner.getController().onStopMove();
		}
	}

	/**
	 * @return Whether the body has reached what it was last sent towards.
	 *         <p>
	 *         <b>In three dimensions while flying, and only then.</b> On foot the height is the ground's business and asking about it would have a bot
	 *         on a slope believe it is still travelling; in the air it is the whole point. The two metre tolerance is also wrong for a flight leg,
	 *         which ends where it ends.
	 *         <p>
	 *         What reads this is not only the caller you would expect: {@code MoveTaskManager} asks after every tick, through
	 *         {@code PlayerBotAI.isDestinationReached}, and <b>removes the creature from the moving list when the answer is yes</b>. So a flat answer
	 *         about a climb is not a cosmetic error — a bot slanting up to its apex reached the apex's x and y while still 25 m below it, was dropped
	 *         from the movement tick there, and hung in the air until its flight points ran out. Having stopped being ticked it also stopped telling
	 *         the clients where it was, and every one of them carried on drawing it upwards along the last velocity it had been given: 329 m up, by
	 *         the target window, while the body sat still.
	 */
	public boolean isArrived() {
		if (flying)
			return PositionUtil.getDistance(owner.getX(), owner.getY(), owner.getZ(), getTargetX2(), getTargetY2(), getTargetZ2()) <= FLIGHT_ARRIVED;
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
		owner.getController().onStartMove();
	}

	@Override
	public void moveToDestination() {
		if (flying) {
			flyOneStep();
			return;
		}
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
		// The known list is refreshed as the bot walks, which is what makes an aggressive monster notice somebody walking past: an npc re-checks its
		// aggro when a creature ENTERS its known list, and never again, so a list fixed at the moment the bot spawned had every monster deciding
		// once, from hundreds of metres away, that this one was too far to care about.
		// This is what a real player does and what a bot did not: CM_MOVE goes through the four argument overload, where the flag is true, while the
		// engine's own npcs pass false and have their lists refreshed by their walk manager instead. A bot had neither, and walked through aggressive
		// camps untouched — reported from in game, after an earlier attempt to fix it with onMove(), which notifies this creature's own observers and
		// its own ai, and nothing around it.
		World.getInstance().updatePosition(owner, newX, newY, groundZ(newX, newY, (getTargetZ2() - z) * fraction + z), heading, true);
		updateLastMove();
		owner.getController().onMove();
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
			log.info("Bot {} makes no headway from {} towards {} and gives up moving", owner.getName(),
				describe(owner.getX(), owner.getY(), owner.getZ()), describe(goalX, goalY, goalZ));
			blocked = true;
			noHeadwayX = finalX;
			noHeadwayY = finalY;
			noHeadwayUntil = now + NO_HEADWAY_MEMORY;
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
		flying = false; // and a flight interrupted this way is noticed by BotFlight, which flies the body down rather than leaving it up there
		MoveTaskManager.getInstance().removeCreature(owner);
		super.abortMove();
	}
}
