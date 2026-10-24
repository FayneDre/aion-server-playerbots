package com.aionemu.gameserver.playerbot.movement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.movement.GlideFlag;
import com.aionemu.gameserver.controllers.movement.MovementMask;
import com.aionemu.gameserver.controllers.movement.PlayerMoveController;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.gameobjects.state.CreatureVisualState;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_PLAYER_STATE;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.playerbot.combat.BotRestManager;
import com.aionemu.gameserver.playerbot.movement.BotGeoHelper.Detour;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.navmesh.Avoidance;
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
	/** How far from where it boarded a body may stand when its ride arrives and still be carried: further, something else moved it. */
	private static final float RIDE_BOARDING_DRIFT = 5;
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
	/**
	 * How far the destination may drift and the journey still be the one a route is being walked or planned for: a leader walking on, a target moving
	 * about. Within it, the route in hand is walked on while the next is planned, a plan still on its way is waited for rather than asked again, and a
	 * plan that comes back for where the destination was a moment ago is taken.
	 * <p>
	 * Without it, a group following its leader through Nochsana stood still: on a map with glides every plan is answered on another thread, the leader
	 * moved more than two metres between ticks, so each tick threw the route away, stopped the bot to wait, and discarded the answer when it came for
	 * not matching to the centimetre. A sorcerer went back and forth between two points five metres apart for sixteen seconds while its leader walked
	 * fifty.
	 */
	private static final float FOLLOW_DRIFT = 8f;
	/** How many waypoints of a route arriving mid-walk are looked at to find where the bot has got to along it. */
	private static final int RESUME_LOOKAHEAD = 4;

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
	/** The indexes of {@link #route}, and of {@link #pendingRoute}, whose waypoint is reached by gliding down from the one before. */
	private volatile Set<Integer> routeGlides = Set.of(), pendingGlides = Set.of();
	/** The indexes of {@link #route}, and of {@link #pendingRoute}, whose waypoint is reached by flying across from the one before: an island's edge to another's. */
	private volatile Set<Integer> routeFlights = Set.of(), pendingFlights = Set.of();
	/** The indexes of {@link #route}, and of {@link #pendingRoute}, whose waypoint is reached by a lift or a boat from the one before, with how long it takes. */
	private volatile Map<Integer, Integer> routeRides = Map.of(), pendingRides = Map.of();
	/** How long the ride to the waypoint aimed at takes, in seconds; 0 when the waypoint is not a ride's landing. */
	private volatile int aimRideSeconds;
	/** Until when the body is on a lift or a boat, in milliseconds: it is not walked anywhere, and arrives when the time is up. */
	private volatile long ridingUntil;
	/** Whether the point being gone to is a glide's landing: the leg is flown, wings out, and not walked off the edge. */
	private volatile boolean aimIsGlide;
	/** Whether the waypoint aimed at is the landing of a crossing by air, which {@link BotFlight} flies. */
	private volatile boolean aimIsFlight;
	/**
	 * True while this controller glides the body down to a landing. Walked as any other leg, a glide went off the edge and down through the air at a run, no wings:
	 * the bots of the Ardus Shrine camp, in Verteron, jumped into the void that way to go and farm below it.
	 */
	private volatile boolean gliding;
	/** How fast a glide is flown, in metres a second along its slant: a little under a body's flight, since nothing pushes it but the fall. */
	private static final float GLIDE_SPEED = 8f;
	/**
	 * How many metres a glide goes forward for each metre it comes down. A link is found from the edge to the first ground below within ten metres, so
	 * Ardus Shrine's was 85 m down for 14 m out: flown as a line, the body dropped along the cliff face and was seen to vanish. Short of that ratio, the
	 * glide circles down over its landing, as a player does.
	 */
	private static final float GLIDE_RATIO = 2.5f;
	private static final float GLIDE_TURN_RADIUS = 8f;
	private static final int GLIDE_LEGS_PER_TURN = 12;
	/** The points still to be flown before the landing, when the glide circles down to it. */
	private final Deque<Vector3f> glideTurns = new ConcurrentLinkedDeque<>();
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
	/**
	 * Whether a search asked for is still queued or running, answered or not. While it is, the same journey never asks again: past
	 * {@link #PLAN_PATIENCE} it did, every five seconds for every waiting bot, and once the four route threads fell behind the queue grew faster
	 * than they emptied it. Visitors to Pandaemonium waited over four minutes for routes asked for on 23 October, standing where they had arrived.
	 */
	private volatile boolean planInFlight;
	/** When the plan being awaited was asked for: past {@link #PLAN_PATIENCE} it is taken for lost and asked again. */
	private volatile long planAskedAt;
	/**
	 * How long a plan asked for is waited on before the same journey asks again. Asking on every call instead, because the route not yet in hand is
	 * an empty one, was 16794 routes in half an hour from 800 bots: the queue it filled is what kept the next bot waiting, and a waiting bot used to
	 * step aside blind -- 13422 of 14689 steps aside.
	 */
	private static final long PLAN_PATIENCE = 5000;
	/**
	 * How long a search still queued or running is waited on before it is taken for lost and asked again, in milliseconds: one that threw never
	 * answers, and a bot waiting on it for good would stand there for good.
	 */
	private static final long PLAN_LOST = 60_000;
	/** A destination found to be as near as the ground goes, where the bot stood when it was, and until when asking again is answered at once. */
	private volatile float nearestForX = Float.NaN, nearestForY, nearestAtX, nearestAtY;
	private volatile long nearestUntil;
	/** How long "this is as near as it gets" holds for the same destination asked for from the same place. */
	private static final long NEAREST_MEMORY = 10000;
	/** The last destination the planner answered "no route" for, kept so the refusal can be given synchronously to whoever asks again. */
	private volatile float noRouteX, noRouteY;
	private volatile boolean hasNoRoute;
	/** The last destination abandoned for lack of headway, and until when asking for it again is refused rather than tried afresh. */
	private volatile float noHeadwayX, noHeadwayY;
	private volatile long noHeadwayUntil;
	/**
	 * Whether the refusal the bot is under rests only on searches that ran out of budget, rather than on a proof: a search that finished empty, a body
	 * walled in or making no headway. The journey is given up either way; only a proof says anything about the spot the bot stands on, and counting
	 * the others as proofs is what rescued bots that were not stuck: six residents of Altgard, given a hunting ground 1.8 km away whose searches
	 * gave up, were judged to stand on ground that "leads nowhere" and teleported home 14 times in half an hour (24 October).
	 */
	private volatile boolean refusalUnproven, noHeadwayUnproven;
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
	/** Ground the next journey keeps out of where it can. One shot: {@link #moveToPoint} takes it and puts {@link Avoidance#NONE} back. */
	private volatile Avoidance nextAvoidance = Avoidance.NONE;
	/** The ground the journey in hand keeps out of, for a plan asked for again partway along it. */
	private volatile Avoidance journeyAvoidance = Avoidance.NONE;
	/** Where the bot stood, and where it was going, when it last asked for a plan round something in its way; see {@link #planAroundObstacle}. */
	private float replannedAtX = Float.NaN, replannedAtY, replannedForX, replannedForY;

	public BotMoveController(Player owner) {
		super(owner);
	}

	/**
	 * Asks that the next {@link #moveToPoint} plan its route round this ground where there is a way round, which is what a bot taking its place in a
	 * fight does so as not to walk through a pack that has not noticed it yet. Applies to that one call and no other.
	 */
	public void avoid(Avoidance avoidance) {
		nextAvoidance = avoidance;
	}

	/**
	 * Walks towards the given point, going around obstacles in the way. Long routes are covered in successive legs.
	 *
	 * @return false if the bot is walled in, in which case it does not move at all.
	 */
	public boolean moveToPoint(float x, float y, float z) {
		// taken and cleared first, whatever happens below: it belongs to this call alone, and a leftover would keep the next journey out of ground it has no reason to
		Avoidance avoid = nextAvoidance;
		nextAvoidance = Avoidance.NONE;
		if (isRiding())
			return true; // on a lift or a boat: on its way, and nothing walks it off

		// Answering "no" to a destination already found to have no route, before doing anything else.
		// A long route is planned on another thread, so by the time the answer comes back this method has long since returned "on its way". The
		// caller therefore never hears the refusal, asks again on the next tick, and gets "on its way" again: two bots stood in a pocket of the map
		// asking for the same village sixty-six times in three minutes. Remembering the refusal is what turns it into an answer.
		if (hasNoRoute && PositionUtil.getDistance(noRouteX, noRouteY, x, y) < SAME_JOURNEY_TOLERANCE)
			return false; // under the refusal it was given, proven or not
		// A destination just abandoned for lack of headway is refused for a while, instead of being set out for again as though it were new.
		// Giving up calls stop(), which clears hasGoal, so the identical destination asked for on the next tick no longer counted as the same
		// journey: the blocked flag was wiped, the attempt reported a move, and the caller's anti-stuck counter went back to zero. A bot wedged two
		// metres from its goal rode that loop for five minutes without ever being rescued, because nothing above it was ever told anything was wrong.
		if (System.currentTimeMillis() < noHeadwayUntil && PositionUtil.getDistance(noHeadwayX, noHeadwayY, x, y) < SAME_JOURNEY_TOLERANCE) {
			refusalUnproven = noHeadwayUnproven;
			return false;
		}
		// as near as it gets, already found from here: standing still is the answer, and asking the planner again is the same answer twice a second
		if (System.currentTimeMillis() < nearestUntil && PositionUtil.getDistance(nearestForX, nearestForY, x, y) < 1
			&& PositionUtil.getDistance(nearestAtX, nearestAtY, owner.getX(), owner.getY()) < 1) {
			log.debug("Bot {} is already as near as it gets to {}", owner.getName(), describe(x, y, z));
			return true;
		}
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
		if (sameJourney && onDetour && !isArrived()) {
			log.debug("Bot {} keeps to its sidestep towards {}", owner.getName(), describe(x, y, z));
			return true;
		}
		// a planned route knows about walls and low obstacles the probes cannot see; without one the bot walks straight at the goal as before
		if (pendingRoute != null && PositionUtil.getDistance(plannedForX, plannedForY, x, y) <= FOLLOW_DRIFT && adoptPendingRoute()) {
			// the plan asked for on an earlier call has arrived: taking it is the whole point of having waited, and planning again here would throw
			// the answer away and start the wait over
			routeIndex = resumeIndex();
			aimAtNextWaypoint();
		} else if (isPlanComing(x, y) || !route.isEmpty() && PositionUtil.getDistance(plannedForX, plannedForY, x, y) <= REPLAN_DISTANCE) {
			if (log.isDebugEnabled())
				log.debug("Bot {} asks for no route to {}: {}", owner.getName(), describe(x, y, z),
					isPlanComing(x, y) ? "one is on its way" : "the one in hand leads there, at waypoint " + routeIndex + " of " + route.size());
			if (routeIndex >= route.size())
				aimAtNextWaypoint(); // keeping the route, but its waypoints are all behind us: the last stretch is walked at the destination itself
		} else {
			// said on the route's line: a bot handed the same route every second was the one fault the log could not explain without it
			String why = route.isEmpty() ? "" : ", the destination having moved";
			float fromX = owner.getX(), fromY = owner.getY();
			// The destination has only drifted: the route in hand still leads its way, and is walked on until the next one comes. Thrown away, it left
			// the bot standing until the answer, and a leader who keeps walking keeps the answer from ever being the last one.
			boolean walkOn = !route.isEmpty() && routeIndex < route.size() && PositionUtil.getDistance(plannedForX, plannedForY, x, y) <= FOLLOW_DRIFT;
			if (!walkOn) {
				route = List.of();
				routeGlides = Set.of();
				routeFlights = Set.of();
				routeRides = Map.of();
				routeIndex = 0;
			}
			plannedForX = x;
			plannedForY = y;
			journeyAvoidance = avoid;
			planAskedAt = System.currentTimeMillis();
			awaitingPlan = true; // before asking, so a plan that resolves synchronously clears it again inside the call below
			// a long journey is planned on another thread, so the bot waits where it stands rather than setting off blind; a short one plans on this
			// very call, in which case the result must be adopted right here or the first leg walks for nothing
			planInFlight = NavmeshService.getInstance().hasMesh(owner.getWorldId()); // a map with no mesh is never answered, so nothing is awaited there
			NavmeshService.getInstance().planRoute(owner, x, y, z, avoid, planned -> {
				planInFlight = false;
				offerRoute(planned, x, y, fromX, fromY, why);
			});
			if (adoptPendingRoute()) {
				routeIndex = resumeIndex();
				aimAtNextWaypoint();
			} else if (!walkOn) {
				aimAtNextWaypoint(); // nothing in hand yet: the destination itself, until the plan comes
			} // walking on, the waypoint aimed at is still the right one
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
	 * @return true when the journey just refused was refused only because its searches ran out of budget: dropped, but no evidence that the bot stands
	 *         anywhere it cannot leave.
	 */
	public boolean isRefusalUnproven() {
		return refusalUnproven;
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
	 * the flight animation drawn rather than the gliding one; a glide adds the {@code GLIDE} bit to it to get the wings of a glide.
	 */
	private void tellFlightLeg(float x, float y, float z) {
		lastFlightTell = System.currentTimeMillis();
		if (flightRising || gliding) // a glide is driven too: told only where it ends, a client draws the body down the cliff face, clamped to it
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
		// A glide carries the GLIDE bit and its flag, as a gliding client's CM_MOVE does. Without it a client draws a body standing in the air: the bots
		// that copied a glide on 20 October were seen hanging in front of a cliff with no wings, gliding as far as the server knew.
		movementMask = (byte) (MovementMask.POSITION | MovementMask.MANUAL | (gliding ? MovementMask.GLIDE : 0));
		glideFlag = GlideFlag.NONE;
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

		if ((flightRising || gliding) && System.currentTimeMillis() - lastFlightTell >= FLIGHT_TELL_MILLIS)
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
		if (gliding) {
			Vector3f next = glideTurns.poll();
			if (next != null) {
				flyLegTo(next.getX(), next.getY(), next.getZ(), GLIDE_SPEED);
				return true;
			}
			land();
		}
		if (adoptPendingRoute()) {
			routeIndex = resumeIndex();
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
	private void offerRoute(BotPathFinder.Route answer, float forX, float forY, float fromX, float fromY, String why) {
		if (!hasGoal || PositionUtil.getDistance(forX, forY, finalX, finalY) > FOLLOW_DRIFT) {
			// an answer to a journey that is over, or to an older one; said at debug, since a route asked for and never heard of is otherwise a silence
			log.debug("Bot {} drops the route to {}: {}", owner.getName(), String.format("%.1f %.1f", forX, forY),
				hasGoal ? "now going to " + String.format("%.1f %.1f", finalX, finalY) : "no journey any more");
			return;
		}
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
				// A proof that nothing walks there is exactly the case flight was built for, and the only one it is tried on: the places it opens up
				// -- a terrace, a ledge, a rooftop -- are places the mesh correctly says have no way in on foot. A search that merely gave up proves
				// nothing and gets no flight, or a slow search would have bots taking off all over a map they could perfectly well have walked.
				// It refuses itself in every ordinary case (not a daeva, no fly zone, not enough flight points, too far), and then this is an
				// ordinary refused journey again.
				// and only for somewhere actually worth flying to: a proof is a proof at any distance, but a bot that cannot route to a spot under
				// its own feet has a problem that leaving the ground does not solve
				if (PositionUtil.getDistance(owner.getX(), owner.getY(), forX, forY) >= BotFlight.MIN_JOURNEY
					&& BotFlight.tryFlyTo(owner, new Vector3f(forX, forY, finalZ))) {
					hasGoal = false; // the flight owns the body now, and gives it back on the ground
					gaveUpCount = 0;
					return;
				}
				noRouteX = forX;
				noRouteY = forY;
				hasNoRoute = true;
				refusalUnproven = answer.gaveUp(); // a long journey whose search gave up is dropped all the same, and proves nothing
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
				refusalUnproven = noHeadwayUnproven = true;
				blocked = true;
				hasGoal = false;
			}
			return;
		}
		pendingGlides = answer.glides();
		pendingFlights = answer.flights();
		pendingRides = answer.rides();
		pendingRoute = planned;
		log.info("Bot {} has a route of {} waypoints to {} from {}{}", owner.getName(), planned.size(), String.format("%.1f %.1f", forX, forY),
			String.format("%.1f %.1f", fromX, fromY), why);
		if (!answer.glides().isEmpty()) {
			// logged so that a glide in game can be gone and watched
			Vector3f landing = planned.get(answer.glides().iterator().next());
			log.info("Bot {} plans {} glide(s), the first landing at {}", owner.getName(), answer.glides().size(),
				String.format("%.1f %.1f %.1f", landing.getX(), landing.getY(), landing.getZ()));
		}
		if (!answer.flights().isEmpty()) {
			Vector3f landing = planned.get(answer.flights().iterator().next());
			log.info("Bot {} plans {} crossing(s) by air, the first landing at {}", owner.getName(), answer.flights().size(),
				String.format("%.1f %.1f %.1f", landing.getX(), landing.getY(), landing.getZ()));
		}
		if (!answer.rides().isEmpty()) {
			Vector3f landing = planned.get(answer.rides().keySet().iterator().next());
			log.info("Bot {} plans {} ride(s), the first arriving at {}", owner.getName(), answer.rides().size(),
				String.format("%.1f %.1f %.1f", landing.getX(), landing.getY(), landing.getZ()));
		}
		// A bot that waited for this plan is standing still, so nothing will come back to pick it up: no leg is running, so continueToGoal is never
		// called, and the ai only asks again while the bot is on its way somewhere. Restarting the journey on a task thread keeps this one off the
		// movement state, which belongs to whoever calls moveToPoint.
		if (!isInMove())
			BotScheduler.getInstance().execute(() -> moveToPoint(finalX, finalY, finalZ));
	}

	/**
	 * Takes the route a planning thread left, if there is one. The field is read once: it is written from other threads, and reading it twice -- once to
	 * ask, once to take -- handed a bot a null route under 790 bots on 16 October.
	 *
	 * @return true if a route was taken.
	 */
	private boolean adoptPendingRoute() {
		List<Vector3f> planned = pendingRoute;
		if (planned == null)
			return false;
		pendingRoute = null;
		route = planned;
		routeGlides = pendingGlides;
		routeFlights = pendingFlights;
		routeRides = pendingRides;
		return true;
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
		// read once: the route is replaced from other threads (a plan arriving, a cast halting the walk), and a list read twice can be a short one the
		// second time -- an index out of bounds that cost a bot its decision tick in the log of 16 October
		List<Vector3f> current = route;
		Set<Integer> glides = routeGlides, flights = routeFlights;
		Map<Integer, Integer> rides = routeRides;
		// the index too: two threads stepping it at once walked it past the end of a two waypoint route
		int index = routeIndex;
		while (index < current.size()) {
			boolean glide = glides.contains(index), flight = flights.contains(index);
			Integer ride = rides.get(index);
			Vector3f waypoint = current.get(index++);
			routeIndex = index;
			// the first waypoint is the bot's own cell, and a route may pass close to where it already stands; a landing never is, however close
			// across, since it is metres below, and the top of a lift stands right over its foot
			if (!glide && !flight && ride == null && PositionUtil.getDistance(owner.getX(), owner.getY(), waypoint.getX(), waypoint.getY()) < ARRIVE_OFFSET)
				continue;
			aimRideSeconds = ride == null ? 0 : ride;
			goalX = waypoint.getX();
			goalY = waypoint.getY();
			goalZ = waypoint.getZ();
			onPlannedWaypoint = true;
			aimIsGlide = glide;
			aimIsFlight = flight;
			// Headway is measured to the waypoint aimed at, so a new one is a new baseline. Kept from the last, a route planned again partway -- a shop
			// keeper's spot moving with the bot, a chased target -- measured a waypoint 24 m off against the 2 m the bot had got to the old one, and
			// gave up five seconds later: the no headway the log kept naming at the Gelkmaros shops, on ground the mesh and the geometry both call open.
			// Not on a detour, where bouncing between two sidesteps is exactly what the measure exists to catch.
			if (!onDetour) {
				closestToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
				lastProgressTime = System.currentTimeMillis();
			}
			return true;
		}
		aimIsGlide = false;
		aimIsFlight = false;
		aimRideSeconds = 0;
		boolean changed = goalX != finalX || goalY != finalY;
		goalX = finalX;
		goalY = finalY;
		goalZ = finalZ;
		// the destination itself is whatever the caller asked for — a creature's live position, a spot in a town — and no waypoint the mesh vetted
		onPlannedWaypoint = false;
		return changed;
	}

	private boolean startNextLeg() {
		if (aimRideSeconds > 0)
			return rideAcross();
		if (aimIsFlight)
			return flyAcross();
		if (aimIsGlide && !gliding)
			return glideDown();
		if (gliding)
			return true; // the glide owns the leg until it lands, and continueToGoal takes it from there
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
			if (BotGeoHelper.isWorthMovingTo(owner, step) || isTheGoal(step)) {
				onDetour = false;
				moveToReachablePoint(step.getX(), step.getY(), step.getZ());
				return true;
			}
			// something spawned across the way: that genuinely is the reactive layer's problem, so fall through to it
		}
		Vector3f reachable = BotGeoHelper.reachablePointToward(owner, goalX, goalY, goalZ);
		if (BotGeoHelper.isWorthMovingTo(owner, reachable) || isTheGoal(reachable)) {
			onDetour = false; // the way ahead is open again, so nothing is being walked around any more
			moveToReachablePoint(reachable.getX(), reachable.getY(), reachable.getZ());
			return true;
		}
		if (!onPlannedWaypoint && planAroundObstacle()) {
			if (!hasGoal)
				return false; // the mesh proved there is no way, and said so
			if (awaitingPlan)
				return waitForPlan(); // a long plan, answered on another thread, which restarts the walk when it comes
			return startNextLeg();
		}
		// the way ahead is shut and the plan for this journey is on its way: a sidestep chosen now is chosen blind, and the plan will go round
		if (awaitingPlan && NavmeshService.getInstance().hasMesh(owner.getWorldId()))
			return waitForPlan();
		// The mesh planned from here and the way is still shut: the goal stands off the ground a body walks -- a keeper behind a counter, a creature on
		// a rock -- and this is as near as it gets. A sidestep only paced back and forth beside it, asking for a route twice a second: 2390 of 2951
		// steps aside in half an hour with 800 bots, one bot 945 times at the same stall.
		if (!onPlannedWaypoint && NavmeshService.getInstance().hasMesh(owner.getWorldId()) && isNearestFoundHere())
			return asNearAsItGets();
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
				return waitForPlan(); // the mesh is still working on this journey: what the probes see now is not the way it will be walked
			// with the position: the reactive probes are blind below a metre, so when they give up the only way to tell a real dead end from a
			// low obstacle they cannot see is to ask the mesh about this exact pair of points afterwards (NavmeshTool <mapId> path x1 y1 x2 y2)
			log.info("Bot {} is walled in at {} and gives up moving towards {}", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
				describe(goalX, goalY, goalZ));
			refusalUnproven = false;
			blocked = true;
			hasGoal = false;
			return false;
		}
		detourSide = detour.side();
		if (!onDetour) // once per obstacle, not once per tick: enough to measure how wide a sidestep really goes
			log.info("Bot {} steps aside at {} to {}, heading for {} ({})", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
				describe(detour.point().getX(), detour.point().getY(), detour.point().getZ()), describe(goalX, goalY, goalZ), legKind());
		onDetour = true;
		moveToReachablePoint(detour.point().getX(), detour.point().getY(), detour.point().getZ());
		return true;
	}

	/**
	 * Takes the lift or the boat the route reached: the waypoint aimed at is where it arrives, and the bot stands where it is boarded.
	 * <p>
	 * The client animates the ride and the geometry holds nothing of it, nor says when the boat sails, so the body is hidden as it boards, as if it had stepped
	 * aboard, and shown again at the other end once the ride's time is up. Waiting on the quay in sight and then vanishing read, in game on 20 October, as a
	 * character standing about for three minutes and then appearing out of nowhere. As after a flight, the journey is handed back as over and planned again
	 * from the arrival. Whatever moved the body meanwhile (a rescue, a death, a group's summons) cancels the ride, and the body is shown where that left it.
	 *
	 * @return true, the bot being on its way.
	 */
	private boolean rideAcross() {
		int seconds = aimRideSeconds;
		aimRideSeconds = 0;
		Vector3f arrival = new Vector3f(goalX, goalY, goalZ);
		float boardedX = owner.getX(), boardedY = owner.getY();
		int worldId = owner.getWorldId(), instanceId = owner.getInstanceId();
		ridingUntil = System.currentTimeMillis() + seconds * 1000L;
		hasGoal = false; // the ride has the body; the journey goes on from the arrival with a plan of its own
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false))
			setAndSendStopMove(owner);
		log.info("Bot {} boards a ride at {} and arrives at {} in {} s", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
			describe(arrival.getX(), arrival.getY(), arrival.getZ()), seconds);
		setRideHidden(true);
		BotScheduler.getInstance().schedule(() -> {
			ridingUntil = 0;
			// shown again before it is moved: hidden while it arrives, nobody at the arrival would be told it is there
			setRideHidden(false);
			if (owner.isDead() || !owner.isSpawned() || owner.getWorldId() != worldId || owner.getInstanceId() != instanceId
				|| PositionUtil.getDistance(owner.getX(), owner.getY(), boardedX, boardedY) > RIDE_BOARDING_DRIFT) {
				log.info("Bot {} is no longer where it boarded its ride, and stays where it is", owner.getName());
				return;
			}
			TeleportService.teleportTo(owner, worldId, instanceId, arrival.getX(), arrival.getY(), arrival.getZ());
		}, seconds * 1000L);
		return true;
	}

	/**
	 * Hides the body from every player but its group's, the way a hiding skill does, or shows it again. The deepest hide there is, so nobody's sight sees
	 * through it: a passenger is aboard, not crouching on the quay.
	 */
	private void setRideHidden(boolean hidden) {
		if (hidden)
			owner.setVisualState(CreatureVisualState.HIDE20);
		else if (owner.isInVisualState(CreatureVisualState.HIDE20))
			owner.unsetVisualState(CreatureVisualState.HIDE20);
		else
			return;
		if (owner.isSpawned())
			PacketSendUtility.broadcastPacket(owner, new SM_PLAYER_STATE(owner));
	}

	/** @return true while the body is on a lift or a boat: nothing walks it, and it arrives by itself. */
	public boolean isRiding() {
		return System.currentTimeMillis() < ridingUntil;
	}

	/**
	 * Flies the crossing the route reached the edge of: the waypoint aimed at is the landing on the other island, and the bot stands at the take-off.
	 * <p>
	 * The flight owns the body until it lands, and the journey is handed back as over: the next call for a route plans it again from the island the bot landed on,
	 * which is cheaper and surer than carrying the rest of a plan through a flight. Short of points, or inside the ten seconds between two take-offs, the bot waits
	 * where it stands with the route kept, and asks again. A crossing whose every height meets something is refused, and the planner, which asks the same
	 * question of a link before using it, will not choose it again from here.
	 *
	 * @return true if the bot took off or is waiting to.
	 */
	private boolean flyAcross() {
		Vector3f landing = new Vector3f(goalX, goalY, goalZ);
		if (BotFlight.mustWaitToCross(owner, landing))
			return waitForPlan();
		String refusal = BotFlight.flyCrossing(owner, landing, new Vector3f(finalX, finalY, finalZ));
		if (refusal == null) {
			hasGoal = false; // the flight has the body; the journey goes on from the landing with a plan of its own
			return true;
		}
		log.info("Bot {} does not fly across: {}", owner.getName(), refusal);
		refusalUnproven = false;
		blocked = true;
		hasGoal = false;
		return false;
	}

	/**
	 * Asks the mesh for a route round whatever stops a leg it did not plan: the last stretch to a creature that moved, or a stretch walked while a long
	 * plan is still being searched for. A sidestep chosen without the mesh is what walked bots into rails and through them; a route goes round.
	 * <p>
	 * Once per place and destination: a route ends on the walkable cell nearest the goal, and when that is still short of it, asking again from there
	 * gets the same answer for ever. The sidestep, vetted by the mesh, is what is left then.
	 *
	 * @return true if a route was asked for and the leg should be decided again: walked if it came at once, waited for otherwise, or refused.
	 */
	private boolean planAroundObstacle() {
		if (awaitingPlan || !NavmeshService.getInstance().hasMesh(owner.getWorldId()))
			return false;
		if (PositionUtil.getDistance(owner.getX(), owner.getY(), replannedAtX, replannedAtY) < REPLAN_DISTANCE
			&& PositionUtil.getDistance(finalX, finalY, replannedForX, replannedForY) < REPLAN_DISTANCE)
			return false;
		float x = finalX, y = finalY, z = finalZ;
		replannedAtX = owner.getX();
		replannedAtY = owner.getY();
		replannedForX = x;
		replannedForY = y;
		route = List.of();
		routeGlides = Set.of();
		routeFlights = Set.of();
		routeRides = Map.of();
		routeIndex = 0;
		plannedForX = x;
		plannedForY = y;
		planAskedAt = System.currentTimeMillis();
		awaitingPlan = true;
		float fromX = replannedAtX, fromY = replannedAtY;
		NavmeshService.getInstance().planRoute(owner, x, y, z, journeyAvoidance, planned -> offerRoute(planned, x, y, fromX, fromY, ", round an obstacle"));
		adoptPendingRoute();
		aimAtNextWaypoint();
		return true;
	}

	/**
	 * @return true if the point is the one being walked to. A step under a metre is taken for being blocked, which is right of a probe stopped short
	 *         and wrong of a goal that simply is that close: a waypoint 0.5 to 1 m away was walked round instead of to, and that was 129 of the 208
	 *         steps aside in the first five minutes after the mesh began vetting them.
	 */
	private boolean isTheGoal(Vector3f point) {
		return PositionUtil.getDistance(point.getX(), point.getY(), goalX, goalY) < 0.1f;
	}

	/** @return true if the plan asked for round what is in the way was asked for from about here, for about this destination. */
	private boolean isNearestFoundHere() {
		return PositionUtil.getDistance(owner.getX(), owner.getY(), replannedAtX, replannedAtY) < REPLAN_DISTANCE
			&& PositionUtil.getDistance(finalX, finalY, replannedForX, replannedForY) < REPLAN_DISTANCE;
	}

	/**
	 * Ends the journey where the bot stands, as an arrival rather than a refusal: nothing is wrong with the bot, its goal is simply off the ground.
	 *
	 * @return true, the bot having done what moving could do.
	 */
	private boolean asNearAsItGets() {
		// Twice from the same spot for the same goal is not an arrival but a refusal. Stopped as near as it gets, a bot sent to sell stood out of a keeper's
		// trade range for good, re-arriving every ten seconds: the errand never ended and nothing else was ever done, 22 bots frozen at shops for 40
		// minutes. Refused, the caller hears it and gives the goal up, as it does for a goal with no route.
		if (PositionUtil.getDistance(nearestForX, nearestForY, finalX, finalY) < 1 && PositionUtil.getDistance(nearestAtX, nearestAtY, owner.getX(), owner.getY()) < 1) {
			log.info("Bot {} is still as near as it gets to {} and gives it up", owner.getName(), String.format("%.1f %.1f", finalX, finalY));
			nearestForX = Float.NaN;
			refusalUnproven = noHeadwayUnproven = false;
			blocked = true;
			noHeadwayX = finalX;
			noHeadwayY = finalY;
			noHeadwayUntil = System.currentTimeMillis() + NO_HEADWAY_MEMORY;
			stop();
			return false;
		}
		nearestForX = finalX;
		nearestForY = finalY;
		nearestAtX = owner.getX();
		nearestAtY = owner.getY();
		nearestUntil = System.currentTimeMillis() + NEAREST_MEMORY;
		hasGoal = false;
		return waitForPlan();
	}

	/** @return true if a plan for about that destination was asked for recently enough to be still on its way. */
	private boolean isPlanComing(float x, float y) {
		long waited = System.currentTimeMillis() - planAskedAt;
		return awaitingPlan && (planInFlight && waited < PLAN_LOST || waited < PLAN_PATIENCE)
			&& PositionUtil.getDistance(plannedForX, plannedForY, x, y) <= FOLLOW_DRIFT;
	}

	/**
	 * @return The index of the waypoint to walk to next on a route just taken, from where the bot stands now rather than where it stood when the route
	 *         was asked for. A route starts at the bot's cell of that moment, and one that arrives while it walks on starts behind it: taken from its
	 *         start, it turned the bot round to walk back to where it had been, every time a plan came in. A waypoint is behind once the bot is nearer
	 *         the one after it than the waypoint itself is; a glide or a crossing by air is never skipped this way.
	 */
	private int resumeIndex() {
		List<Vector3f> current = route;
		Set<Integer> glides = routeGlides, flights = routeFlights;
		Map<Integer, Integer> rides = routeRides;
		int index = 0;
		while (index + 1 < current.size() && index < RESUME_LOOKAHEAD && !glides.contains(index) && !flights.contains(index) && !rides.containsKey(index)
			&& !glides.contains(index + 1) && !flights.contains(index + 1) && !rides.containsKey(index + 1)) {
			Vector3f here = current.get(index), next = current.get(index + 1);
			if (PositionUtil.getDistance(owner.getX(), owner.getY(), next.getX(), next.getY())
				> PositionUtil.getDistance(here.getX(), here.getY(), next.getX(), next.getY()))
				break;
			index++;
		}
		return index;
	}

	/**
	 * Stands still until the plan comes, keeping the journey. The body is stopped and said to be, which is what lets the plan restart the walk when it
	 * arrives ({@link #offerRoute} restarts only a body that is not moving) and lets the ai ask again meanwhile: a body left marked as moving after its
	 * leg ended was never asked about again by either.
	 *
	 * @return true, the bot being on its way.
	 */
	private boolean waitForPlan() {
		onDetour = false;
		lastProgressTime = System.currentTimeMillis(); // waiting for the planner is not failing to get anywhere
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false))
			setAndSendStopMove(owner);
		return true;
	}

	/** @return What kind of leg is being walked, for the log: which of them the sidesteps come from is what decides where to look next. */
	private String legKind() {
		if (onPlannedWaypoint)
			return "planned, something spawned in the way";
		if (!NavmeshService.getInstance().hasMesh(owner.getWorldId()))
			return "no mesh";
		if (awaitingPlan)
			return "awaiting a plan";
		return route.isEmpty() ? "no route" : "last stretch";
	}

	/**
	 * Opens the wings at the edge and flies the leg down to the landing, as a player's client does when it sends a move with the glide flag: the engine's own
	 * {@code switchToGliding} sets the states and the flight points, and the clients are told with the emote that starts a glide.
	 * <p>
	 * When the engine refuses, the bot waits at the edge and the next tick asks again: within ten seconds of a take off a glide is not allowed, which a player
	 * meets as the same wait. Only a daeva glides, and the planner gives no glide to anyone else.
	 *
	 * @return true, the bot being on its way either way.
	 */
	private boolean glideDown() {
		if (BotFlight.isInTheAir(owner))
			return true; // flying already, and a flight is not this controller's to turn into a glide
		if (!owner.getFlyController().switchToGliding())
			return true;
		gliding = true;
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_EMOTION(owner, EmotionType.START_GLIDE), true);
		glideTurns.clear();
		glideTurns.addAll(circlingDown(owner.getX(), owner.getY(), owner.getZ(), goalX, goalY, goalZ));
		log.info("Bot {} glides down from {} to {}{}", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()), describe(goalX, goalY, goalZ),
			glideTurns.isEmpty() ? "" : ", circling over it in " + glideTurns.size() + " legs");
		Vector3f first = glideTurns.poll();
		if (first != null)
			flyLegTo(first.getX(), first.getY(), first.getZ(), GLIDE_SPEED);
		else
			flyLegTo(goalX, goalY, goalZ, GLIDE_SPEED);
		return true;
	}

	/**
	 * Glides through the given points from where the body stands, the last of them the landing: the way a leader was seen to go through the air,
	 * followed by a member of its group. The walk to the edge is the caller's; this starts in the air.
	 *
	 * @return false if the body cannot glide now (already in the air, or the engine refuses: no wings yet, or a glide too soon after the last).
	 */
	public boolean glideAlong(List<Vector3f> path) {
		if (path.isEmpty() || gliding || BotFlight.isInTheAir(owner) || !owner.getFlyController().switchToGliding())
			return false;
		Vector3f landing = path.get(path.size() - 1);
		route = List.of();
		routeGlides = Set.of();
		routeFlights = Set.of();
		routeRides = Map.of();
		routeIndex = 0;
		// a plan asked for up on the edge is no plan for the ground below: taken on landing, it would walk the bot back towards the top
		pendingRoute = null;
		awaitingPlan = false;
		finalX = goalX = landing.getX();
		finalY = goalY = landing.getY();
		finalZ = goalZ = landing.getZ();
		hasGoal = true;
		gliding = true;
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_EMOTION(owner, EmotionType.START_GLIDE), true);
		glideTurns.clear();
		glideTurns.addAll(path);
		log.info("Bot {} glides after its leader from {} to {} through {} point(s)", owner.getName(), describe(owner.getX(), owner.getY(), owner.getZ()),
			describe(landing.getX(), landing.getY(), landing.getZ()), path.size());
		Vector3f first = glideTurns.poll();
		flyLegTo(first.getX(), first.getY(), first.getZ(), GLIDE_SPEED);
		return true;
	}

	/**
	 * @return The points of a glide that circles down to its landing, the landing last; empty when the line from the edge is already a glide, or when the
	 *         air around the landing is not clear either way round, in which case the glide is flown as a line.
	 *         <p>
	 *         The circle is beyond the landing, away from the edge, and passes through the landing: the body glides out from the edge to above the landing,
	 *         turns over the ground it lands on and comes back to it, a whole number of turns later.
	 */
	private List<Vector3f> circlingDown(float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		float runX = toX - fromX, runY = toY - fromY;
		float run = (float) Math.hypot(runX, runY), drop = fromZ - toZ;
		float circling = drop * GLIDE_RATIO - run;
		if (run < 0.5f || circling < GLIDE_TURN_RADIUS * 2)
			return List.of();
		float outX = runX / run, outY = runY / run;
		float centreX = toX + outX * GLIDE_TURN_RADIUS, centreY = toY + outY * GLIDE_TURN_RADIUS;
		double startAngle = Math.atan2(toY - centreY, toX - centreX);
		int turns = Math.max(1, Math.round(circling / (float) (2 * Math.PI * GLIDE_TURN_RADIUS)));
		int legs = turns * GLIDE_LEGS_PER_TURN;
		float aboveLanding = fromZ - run / GLIDE_RATIO;
		for (int side : new int[] { 1, -1 }) {
			List<Vector3f> points = new ArrayList<>(legs + 1);
			points.add(new Vector3f(toX, toY, aboveLanding));
			for (int leg = 1; leg <= legs; leg++) {
				double angle = startAngle + side * 2 * Math.PI * leg / GLIDE_LEGS_PER_TURN;
				float z = aboveLanding - (aboveLanding - toZ) * leg / legs;
				points.add(leg == legs ? new Vector3f(toX, toY, toZ)
					: new Vector3f(centreX + (float) Math.cos(angle) * GLIDE_TURN_RADIUS, centreY + (float) Math.sin(angle) * GLIDE_TURN_RADIUS, z));
			}
			if (isAirClear(new Vector3f(fromX, fromY, fromZ), points))
				return points;
		}
		return List.of();
	}

	/** @return true if nothing solid stands on the way through these points, asked of the game's geometry at a body's chest, the last metre forgiven. */
	private boolean isAirClear(Vector3f from, List<Vector3f> points) {
		Vector3f last = from;
		for (Vector3f point : points) {
			Vector3f met = GeoService.getInstance().getClosestCollision(owner, last.getX(), last.getY(), last.getZ() + 1.5f, point.getX(), point.getY(),
				point.getZ() + 1.5f, false, CollisionIntention.DEFAULT_COLLISIONS.getId(), IgnoreProperties.ANY_RACE);
			if (met != null && PositionUtil.getDistance(met.getX(), met.getY(), met.getZ(), point.getX(), point.getY(), point.getZ() + 1.5f) >= 1f
				&& PositionUtil.getDistance(met.getX(), met.getY(), met.getZ(), last.getX(), last.getY(), last.getZ() + 1.5f) >= 1f)
				return false;
			last = point;
		}
		return true;
	}

	/** The glide has reached its landing: the wings are folded, as the engine does for a player who lands, and the body stands on the ground under it. */
	private void land() {
		gliding = false;
		aimIsGlide = false;
		aimIsFlight = false;
		aimRideSeconds = 0;
		flying = false;
		glideTurns.clear();
		// the time spent in the air is not time spent making no headway on the ground
		closestToGoal = PositionUtil.getDistance(owner.getX(), owner.getY(), goalX, goalY);
		lastProgressTime = System.currentTimeMillis();
		owner.getFlyController().onStopGliding();
		World.getInstance().updatePosition(owner, owner.getX(), owner.getY(), groundZ(owner.getX(), owner.getY(), owner.getZ()), heading, true);
	}

	/**
	 * Drops a glide the body will not finish, because it died on the way down. Nothing else ends one: {@link #stop} leaves a flight alone, so a bot killed
	 * in the air came back at its obelisk still flying, and would have flown on to what was left of its circle.
	 */
	public void abandonGlide() {
		if (!gliding)
			return;
		gliding = false;
		aimIsGlide = false;
		aimIsFlight = false;
		aimRideSeconds = 0;
		glideTurns.clear();
		endFlight();
	}

	/** @return true while this controller is gliding the body down a planned glide, which is a leg of the walk and not a flight of {@link BotFlight}'s. */
	public boolean isGliding() {
		return gliding;
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
	 * Ends the journey and forgets every refusal it holds, for a bot the rescue has just moved or let off its errand.
	 * <p>
	 * The refusals were earned from somewhere else, or against an errand now forgotten. Kept, they turned down whatever the bot asked for next without
	 * a single route being searched: one bot in the Sanctum was refused everything from its own front door, where the mesh plans routes in 20 ms,
	 * and was let off its errand every twelve seconds for the whole half hour.
	 */
	public void forgetRefusals() {
		stop();
		blocked = false;
		refusalUnproven = false;
		hasNoRoute = false;
		noHeadwayUntil = 0;
		nearestUntil = 0;
		nearestForX = Float.NaN;
		gaveUpCount = 0;
		onDetour = false;
	}

	/**
	 * Ends the current movement and tells clients the authoritative position, so they stop extrapolating.
	 */
	public void stop() {
		if (flying)
			return; // a flight ends through endFlight, and every other caller of this is a walking journey being given up
		hasGoal = false;
		route = List.of();
		routeGlides = Set.of();
		routeFlights = Set.of();
		routeRides = Map.of();
		pendingRoute = null;
		aimIsGlide = false;
		aimIsFlight = false;
		aimRideSeconds = 0;
		routeIndex = 0;
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false)) {
			setAndSendStopMove(owner);
			owner.getController().onStopMove();
		}
	}

	/**
	 * Ends the walk because a cast has just begun, without cancelling the cast.
	 * <p>
	 * {@link #stop} ends in the engine's {@code onStopMove}, which for a player cancels the skill being cast: a cast begun while walking was cancelled by the
	 * very stop meant to root it. A ranged class in a group, which walks to its place most of a fight, was seen moving and never landing a spell, and a healer
	 * following its group was seen casting heals that never healed anybody. A player's client stops before it casts; this is that stop, after the fact.
	 *
	 * @return true if the body was walking and has been stopped.
	 */
	public boolean haltForCast() {
		if (flying)
			return false;
		hasGoal = false;
		route = List.of();
		routeGlides = Set.of();
		routeFlights = Set.of();
		routeRides = Map.of();
		pendingRoute = null;
		aimIsGlide = false;
		aimIsFlight = false;
		aimRideSeconds = 0;
		routeIndex = 0;
		MoveTaskManager.getInstance().removeCreature(owner);
		if (!started.compareAndSet(true, false))
			return false;
		setAndSendStopMove(owner);
		return true;
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
		// A body that is casting stands still, whoever set the walk going. A step taken under a cast trips its move listener and the engine fails the
		// cast as it ends; with the walk ended before every cast, steps still landed under one cast in nine in a squad, none of them seen starting
		// a walk -- the probe found the caster still and a few metres from its target each time. The walk resumes where it was once the cast is over.
		if (owner.isCasting()) {
			updateLastMove();
			lastProgressTime = System.currentTimeMillis(); // standing to cast is not being stuck: a four second spell and a step made "no headway" otherwise
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
			refusalUnproven = noHeadwayUnproven = false;
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
