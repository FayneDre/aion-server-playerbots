package com.aionemu.gameserver.playerbot.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.templates.zone.ZoneClassName;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.templates.zone.ZoneType;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.world.zone.ZoneInstance;

/**
 * Flies a bot: up and back for the look of it, or across to somewhere no walker can reach.
 * <p>
 * <b>It decides the phases and moves nothing.</b> The body is flown by {@link BotMoveController}, on the movement tick the engine already runs for
 * every moving creature: this hands over a leg, waits for it to be reached, and hands over the next. It used to step the body itself, in a loop of its
 * own, and that loop then had its first step wait 7.8 seconds behind eighty bots thinking. Moving a body is movement; see {@code docs/flight-plan.md}.
 * <p>
 * <b>And coming down is the hard half.</b> Falling is entirely client side: {@code CM_MOVE} is what sets {@code MovementMask.FALL} and calls
 * {@code updateFalling}, and no server code moves anything downwards. A bot that runs out of flight points at height does not fall — it hangs there,
 * unable to take off again, for ever. So every descent is flown, its cost is reserved before the take-off, and a flight that has to end early comes
 * straight down onto the ground beneath wherever the body has got to.
 */
public class BotFlight {

	private static final Logger log = LoggerFactory.getLogger(BotFlight.class);

	/** Flights in progress, by object id. A bot is in here from its take-off until it is back on the ground. */
	private static final Map<Integer, BotFlight> inTheAir = new ConcurrentHashMap<>();

	/** High enough to be unmistakable from the ground, and well under any fly zone's ceiling. */
	private static final float DEFAULT_HEIGHT = 25;
	/** As high as a climb will take a bot, whatever is asked for. */
	private static final float MAX_HEIGHT = 60;
	/** How long the up-and-back cycle holds its height, which is what makes one command a whole flight nobody has to finish by hand. */
	private static final long HOVER_MILLIS = 4000;
	/** How far a journey may go. Flight is for a terrace over a wall, not for crossing a region, which is what the director's teleport is for. */
	private static final float MAX_JOURNEY = 300;
	/** The shortest journey worth leaving the ground for. Under it, walking is the answer and a refusal to walk was about something else. */
	static final float MIN_JOURNEY = 10;
	/** How far above the landing spot a journey aims, so the last of it is a short drop onto the ground rather than a dive at it. */
	private static final float LANDING_CLEARANCE = 3;
	/** How far the one being followed must move before the bot is pointed somewhere new, so a leg is not re-announced every tick. */
	private static final float RETARGET = 4;
	/** How far behind and to the side the bot flies, so it is following rather than occupying the same piece of sky. */
	private static final float ESCORT_SPACING = 4;
	/**
	 * How many flight points an escort keeps for itself.
	 * <p>
	 * More than the margin a planned flight needs, because this one has no plan: nobody knows how long the player being followed means to stay up, and
	 * the bot has to be able to break off and land under its own power at any moment. When these run out it comes down where it is, which is what a
	 * player out of flight time does too.
	 */
	private static final int ESCORT_RESERVE = 15;
	/**
	 * What share of its flight points a bot must have back before it decides to fly again of its own accord.
	 * <p>
	 * Not the same question as whether it can afford this particular flight. A bot that has just come down out of fuel can afford a ten metre hop on
	 * the fumes, and it was taking one -- landing, finding that the only way to its group was through the air again, and going back up on what it had
	 * left. Flight points come back at half a point a second, so this is about a minute on the ground, which is also what it looks like: somebody who
	 * has just landed, getting their breath back.
	 * <p>
	 * Only for the flights nobody asked for. A command is an instruction and is obeyed with whatever the bot has.
	 */
	private static final float REFUELLED = 0.5f;
	/** How often the phases are looked at. It decides and does not move, so it runs at the movement tick's own period rather than faster. */
	private static final long STEP_MILLIS = 200;
	/**
	 * How long the take-off animation is given before the body starts moving.
	 * <p>
	 * Leaving the ground is an animation like standing up or drawing a weapon, and this module has known since its first week that the server has to
	 * leave room for one: a real player's client refuses input until the animation it is playing has finished, and a bot has no client to produce
	 * those pauses. Starting a climb inside the take-off was the whole of one evening's faults — the body set off, the animation reclaimed it, and it
	 * arrived at the top only when the leg ended. Empirical, and the number standing up and drawing a weapon already use.
	 */
	private static final long TAKE_OFF_MILLIS = 1200;
	/**
	 * Flight points held back over and above the flight itself.
	 * <p>
	 * Not a safety habit but a necessity: the drain is a task of its own ticking once a second, so the arithmetic cannot be exact to better than a
	 * point either way — and running out of flight at two metres costs exactly what running out at fifty does.
	 */
	private static final int FP_MARGIN = 5;
	/** How close to a fly zone's ceiling the bot may climb. The zone is a volume, and leaving it in flight is audited and ends the flight. */
	private static final float CEILING_CLEARANCE = 3;
	/** How fast a bot that has lost its flight comes down. Faster than it flies, because nothing it does up there is any use to it now. */
	private static final float POWERLESS_SPEED = 12;
	/** How far below itself a flight looks for the ground when it has to come down. Taller than any outdoor map's own range of heights. */
	private static final float MAX_DROP = 500;
	/**
	 * How far a body may move between two of the flight's own ticks before the flight concludes it was not the one that moved it.
	 * <p>
	 * At flight speed a tick covers about two metres and a starved one three. Anything beyond this is somebody else's doing — a teleport, a summon,
	 * an administrator — and carrying on would fly a leg that was planned for a place the body is no longer anywhere near.
	 */
	private static final float TELEPORTED = 40;
	/** What {@code FlyController.FLY_REUSE_TIME} is, which is private there and has to be known here to say how long is left of it. */
	private static final long FLY_REUSE_MILLIS = 10000;
	/**
	 * How long a leg may make no headway before it is handed to the mover again, in milliseconds.
	 * <p>
	 * The flight depends on being ticked, and the one thing that goes wrong with that is the tick quietly dropping the body: {@code MoveTaskManager}
	 * asks after every step whether the destination is reached and removes anything that says yes, telling nobody. So instead of trusting it, the
	 * flight watches the one thing it can see for itself — whether the body is still moving — and asks again when it is not.
	 */
	private static final long NO_HEADWAY_MILLIS = 1500;
	/** How far the body must get in that time for the leg to count as being flown. */
	private static final float HEADWAY = 0.5f;
	/**
	 * How far forward a climb carries, as a share of how far up it goes.
	 * <p>
	 * A bot used to rise straight up, and nothing in this game ever does: a player gains height by flying forward and up. The movement packet says the
	 * same thing, since what it carries is a velocity — and a purely vertical one has no horizontal component at all for a client to draw.
	 * <p>
	 * <b>It is also what sets how fast the body gains height.</b> A body flies at one speed, so the slant divides it between forward and up: half a
	 * metre forward per metre up spends most of 9 m/s on the climb, which reads from the ground as a bot shot into the sky rather than a player taking
	 * off. Three metres forward for every two up puts the climb at about 5 m/s, which is what a daeva gaining height looks like.
	 */
	private static final float FORWARD_SHARE = 1.5f;

	private enum Phase {
		TAKING_OFF,
		FLYING,
		HOVERING
	}

	private final Player bot;
	/** Who the bot is following through the air, or null for a flight that knows where it is going before it sets off. */
	private final Player escorted;
	/** The legs in order, each a point in the air except the last, which is the ground the bot lands on. */
	private final List<Vector3f> legs;
	/** After which leg the bot holds its height, or -1 for a journey, which does not stop on the way. */
	private final int hoverAfter;
	private int leg;
	private Phase phase = Phase.TAKING_OFF;
	private final long takenOffAt = System.currentTimeMillis();
	private long hoverUntil;
	/** The map the flight started on, so one that ends somewhere else is recognised rather than flown. */
	private final int worldId;
	/** Where the body was at the previous tick, for telling the flight's own movement from somebody else's. */
	private float wasAtX, wasAtY, wasAtZ;
	/** Where the body was when it last made headway, and when that was. */
	private float headwayX, headwayY, headwayZ;
	private long headwayAt;
	/** True once the flight has ended without this class asking, in which case the bot is brought down with no flight state at all. */
	private boolean powerless;
	/**
	 * True once the flight has given up on the rest of itself and is only coming down.
	 * <p>
	 * It exists because deciding to land is a decision, and an escort re-decides everything every tick: it was finding itself short of flight points,
	 * ordering a descent, and then finding itself short again a fifth of a second later and ordering another one from where the body still was. The
	 * log wrote the same line at the same height eleven times and the bot never moved — a decision taken again is a decision never acted on.
	 */
	private boolean landing;
	private ScheduledFuture<?> task;

	private BotFlight(Player bot, List<Vector3f> legs, int hoverAfter, Player escorted) {
		this.bot = bot;
		this.worldId = bot.getWorldId();
		this.wasAtX = bot.getX();
		this.wasAtY = bot.getY();
		this.wasAtZ = bot.getZ();
		this.legs = legs;
		this.hoverAfter = hoverAfter;
		this.escorted = escorted;
	}

	/**
	 * Flies a bot up, holds it there, and lands it again where it stood.
	 *
	 * @param height How far above its own feet to climb, or zero for the default.
	 * @return What to tell whoever asked, whether it worked or not.
	 */
	public static String takeOff(Player bot, float height) {
		float wanted = height > 0 ? Math.min(height, MAX_HEIGHT) : DEFAULT_HEIGHT;
		float ceiling = ceilingFor(bot, bot.getZ() + wanted);
		double angle = Math.toRadians(PositionUtil.convertHeadingToAngle(bot.getHeading()));
		float forward = (ceiling - bot.getZ()) * FORWARD_SHARE;
		List<Vector3f> legs = new ArrayList<>();
		legs.add(new Vector3f(bot.getX() + (float) Math.cos(angle) * forward, bot.getY() + (float) Math.sin(angle) * forward, ceiling));
		legs.add(new Vector3f(bot.getX(), bot.getY(), bot.getZ()));
		return begin(bot, legs, 0, null, Math.round(ceiling - bot.getZ()) + " m up and back");
	}

	/**
	 * Takes a bot up after somebody who is flying, and keeps it with them until they land or it runs out of flight.
	 * <p>
	 * The one piece of flight that was asked for from in game before any of it existed: a bot in your group stays on the ground while you fly, because
	 * following is a walking route to wherever you are, and wherever you are has no floor. It holds a place beside the player rather than their exact
	 * spot, and re-aims only when they have moved far enough to be worth a new leg.
	 * <p>
	 * It keeps more flight points back than a planned flight does, because this one has no plan: nobody knows how long the player means to stay up,
	 * and a bot that cannot break off and land under its own power is a bot left hanging in the sky.
	 *
	 * @return true if the bot is now flying after them.
	 */
	public static boolean tryEscort(Player bot, Player leader) {
		if (isInTheAir(bot) || !leader.isFlying() || !refuelled(bot))
			return false;
		float distance = (float) PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), leader.getX(), leader.getY(), leader.getZ());
		if (distance > MAX_JOURNEY)
			return false;
		Vector3f spot = beside(leader);
		List<Vector3f> legs = new ArrayList<>();
		legs.add(spot);
		if (bot.getLifeStats().getCurrentFp() < ESCORT_RESERVE + fpFor(bot, legs, false))
			return false;
		String answer = begin(bot, legs, -1, leader, "after " + leader.getName());
		boolean flying = isInTheAir(bot);
		if (!flying)
			log.debug("Bot {} is not following {} into the air: {}", bot.getName(), leader.getName(), answer);
		return flying;
	}

	/** @return Whether the bot has enough of its flight points back to be choosing to fly at all. See {@link #REFUELLED}. */
	private static boolean refuelled(Player bot) {
		return bot.getLifeStats().getCurrentFp() >= Math.max(ESCORT_RESERVE, bot.getLifeStats().getMaxFp() * REFUELLED);
	}

	/** @return A place beside the one being followed, a few metres off, so a group in the air is a group rather than a stack. */
	private static Vector3f beside(Player leader) {
		double angle = Math.toRadians(PositionUtil.convertHeadingToAngle(leader.getHeading()) + 180);
		return new Vector3f(leader.getX() + (float) Math.cos(angle) * ESCORT_SPACING, leader.getY() + (float) Math.sin(angle) * ESCORT_SPACING,
			leader.getZ());
	}

	/**
	 * Flies a bot somewhere walking has been <b>proved</b> not to reach, and says whether it is going.
	 * <p>
	 * For the journeys nobody typed: a resident of a terrace going to a shop, or coming home to one. Every refusal {@link #flyTo} can produce is a
	 * quiet no here — not a daeva, no fly zone, not enough flight points, too far, the path would leave the zone — because this is a fallback and
	 * there is always the ordinary answer of giving the errand up.
	 *
	 * @return true if the bot has taken off and the caller should let go of the body.
	 */
	public static boolean tryFlyTo(Player bot, Vector3f target) {
		if (!refuelled(bot))
			return false;
		String answer = flyTo(bot, target);
		boolean flying = isInTheAir(bot);
		if (!flying)
			log.debug("Bot {} is not flying to {} {}: {}", bot.getName(), target.getX(), target.getY(), answer);
		return flying;
	}

	/**
	 * Flies a bot to a spot it may well have no way of walking to, which is the whole point of the capability.
	 * <p>
	 * <b>Only when the air is clear in a straight line</b>, which is what flying to a terrace actually looks like: one slanted leg to just above the
	 * spot, then a short drop onto it. The geometry is asked first — {@code GeoService.getClosestCollision} along the line the body would fly, with
	 * the ground ignored, since a flight is not walking — and a blocked line is a refusal rather than a detour. There was a detour, over the top of
	 * whatever stood in the way, and it is gone: the engine casts a ray from the body's own position and from nowhere else, so the air along the legs
	 * of a detour cannot be tested before setting off. It was flown on faith, and bots flew into the citadel.
	 * <p>
	 * What it lands on is the mesh's answer rather than the coordinates asked for, so a target a metre above the floor or a hand's breadth inside a
	 * wall still puts the bot somewhere it can stand.
	 */
	public static String flyTo(Player bot, Vector3f target) {
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), target.getX(), target.getY(), target.getZ());
		if (ground == null)
			ground = target; // no mesh on this map: the spot asked for is the best answer anybody has
		float distance = (float) PositionUtil.getDistance(bot.getX(), bot.getY(), ground.getX(), ground.getY());
		// A journey of nothing is not a journey. Routes are refused for reasons that have nothing to do with distance -- a destination the bot is
		// already standing on is one of them -- and flying such a refusal has the bot leap into the air and land again, which is what a map full of
		// freshly woken residents looked like after a populate: they appeared flying.
		if (distance < MIN_JOURNEY)
			return bot.getName() + " is already there, " + Math.round(distance) + " m away";
		if (distance > MAX_JOURNEY)
			return bot.getName() + " is " + Math.round(distance) + " m away, and a flight is for a terrace over a wall, not for crossing a region";

		// Straight there or not at all. Flying over something was the other half of this and it is gone: the air along that path cannot be tested
		// from here -- the engine's ray casts from the body's own position and nowhere else -- so it was flown on faith, and in a citadel full of
		// towers the faith was misplaced. Reported from in game as bots that took off after a populate and flew into the buildings.
		Vector3f approach = new Vector3f(ground.getX(), ground.getY(), ground.getZ() + LANDING_CLEARANCE);
		if (!airIsClear(bot, approach))
			return bot.getName() + " has something in the way of " + Math.round(approach.getX()) + " " + Math.round(approach.getY()) + " "
				+ Math.round(approach.getZ()) + ", and a flight it cannot see the whole of is one it does not take";
		List<Vector3f> legs = new ArrayList<>();
		legs.add(approach);
		legs.add(ground);
		return begin(bot, legs, -1, null,
			"to " + Math.round(ground.getX()) + " " + Math.round(ground.getY()) + " " + Math.round(ground.getZ()) + ", " + Math.round(distance) + " m");
	}

	/** Brings a bot down where it is rather than at the end of its flight. */
	public static String land(Player bot) {
		BotFlight flight = inTheAir.get(bot.getObjectId());
		if (flight == null)
			return bot.getName() + " is not in the air";
		flight.comeDown("it was told to land");
		return bot.getName() + " is coming down";
	}

	/** @return true while the bot is off the ground under this class, which is the one thing its decision tick must not act during. */
	public static boolean isInTheAir(Player bot) {
		return inTheAir.containsKey(bot.getObjectId());
	}

	private static String begin(Player bot, List<Vector3f> legs, int hoverAfter, Player escorted, String what) {
		if (inTheAir.containsKey(bot.getObjectId()))
			return bot.getName() + " is already in the air";
		String refusal = whyNot(bot, legs, hoverAfter >= 0);
		if (refusal != null)
			return refusal;

		// the mover and the flight would otherwise be moving the same bot at once, and the mover is the one that pins z to the ground
		if (bot.getMoveController() instanceof BotMoveController mover)
			mover.stop();
		// Walking is a state, and how fast a body moves is read off its states: PlayerGameStats.getMovementSpeed quotes walk speed to anything still
		// marked WALK_MODE. Cleared here exactly where the walking path clears it, on setting off.
		bot.unsetState(CreatureState.WALK_MODE);
		// the checks above are what produce a reason; this one only says no, and says why to a client that is not there
		if (!bot.getFlyController().startFly(true, false))
			return bot.getName() + " was refused its take-off by the engine, for a reason only a client would have been told";

		BotFlight flight = new BotFlight(bot, legs, hoverAfter, escorted);
		inTheAir.put(bot.getObjectId(), flight);
		// The engine's pool, not the bots' own. Moving a body is not thinking, and the bot pool is held to half the processors and runs eighty
		// decision ticks behind whatever is queued ahead of them -- measured as a first step that waited 7.8 seconds with the wings already out.
		flight.task = ThreadPoolManager.getInstance().scheduleAtFixedRate(flight::step, STEP_MILLIS, STEP_MILLIS);
		int fp = bot.getLifeStats().getCurrentFp();
		log.info("Bot {} takes off {} at {} m/s with {} fp (flying={}, state={})", bot.getName(), what, speedOf(bot), fp, bot.isInFlyingState(),
			bot.isInState(CreatureState.FLYING));
		return bot.getName() + " takes off " + what + " with " + fp + " fp";
	}

	/**
	 * Every reason a flight would not work, asked here rather than left to the engine.
	 * <p>
	 * {@code FlyController.startFly} refuses for six different reasons and reports each one as a system message to the player's client — which a bot
	 * does not have, so to a bot every refusal is the same silent {@code false}. A caller that cannot tell them apart retries the one that will never
	 * succeed, which is how the buff loop happened. See {@code docs/engine-traps.md}.
	 */
	private static String whyNot(Player bot, List<Vector3f> legs, boolean hovers) {
		if (!bot.isSpawned())
			return bot.getName() + " is not in the world";
		if (bot.isDead())
			return bot.getName() + " is dead";
		if (bot.isFlying())
			return bot.getName() + " is already in a flight state";
		if (!bot.getCommonData().isDaeva())
			return bot.getName() + " is not a daeva yet, so it cannot fly at all";
		if (!bot.isInsideZoneType(ZoneType.FLY))
			return bot.getName() + " is not standing in a fly zone";
		if (bot.isInsideZoneType(ZoneType.NO_FLY))
			return bot.getName() + " is standing where flight is forbidden";
		if (bot.getEffectController().isAbnormalSet(AbnormalState.NOFLY))
			return bot.getName() + " is under an effect that forbids flight";
		if (bot.getTransformModel().cantFly())
			return bot.getName() + " is polymorphed into something that cannot fly";
		if (bot.isCasting() || bot.getAi() != null && bot.getAi().isInState(AIState.FIGHT))
			return bot.getName() + " is busy fighting";
		// Ten seconds between take-offs, and the engine does not merely refuse one inside them: FlyController.startFly writes the character into the
		// audit log as "possibly using fly cooldown hack". Asked here so the answer is a reason and the log stays about flight.
		long cooldown = bot.getFlyReuseTime() - System.currentTimeMillis();
		if (cooldown > 0)
			return bot.getName() + " took off " + (FLY_REUSE_MILLIS - cooldown) / 1000 + " s ago and cannot do it again yet";

		// Every point of the path has to be inside the fly zone, not only the two ends. A body that leaves one in flight has its flight ended by
		// PlayerController.onLeaveFlyArea and is written into the audit log as a suspected hack -- so a path that would leave is refused here rather
		// than flown and cut off halfway.
		for (Vector3f point : legs) {
			if (!insideAFlyZone(bot, point))
				return bot.getName() + " would leave its fly zone at " + Math.round(point.getX()) + " " + Math.round(point.getY()) + " "
					+ Math.round(point.getZ());
		}
		if (legs.get(0).getZ() - bot.getZ() < 1)
			return bot.getName() + " has no room above it inside its fly zone";

		int needed = fpFor(bot, legs, hovers);
		int have = bot.getLifeStats().getCurrentFp();
		if (have < needed)
			return bot.getName() + " has " + have + " of the " + needed + " fp that the flight and the landing back down would cost";
		return null;
	}

	/**
	 * @return What the whole flight costs in flight points, every leg and the hover together, plus the margin.
	 *         <p>
	 *         The drain is 1 fp/s inside a fly zone and 2 outside it ({@code PlayerLifeStats.triggerFpReduce}), so a bot that left its zone would also
	 *         be paying double. One more reason the path is refused above rather than flown and cut short.
	 */
	private static int fpFor(Player bot, List<Vector3f> legs, boolean hovers) {
		float length = 0;
		float fromX = bot.getX(), fromY = bot.getY(), fromZ = bot.getZ();
		for (Vector3f point : legs) {
			length += (float) PositionUtil.getDistance(fromX, fromY, fromZ, point.getX(), point.getY(), point.getZ());
			fromX = point.getX();
			fromY = point.getY();
			fromZ = point.getZ();
		}
		float seconds = length / speedOf(bot) + (TAKE_OFF_MILLIS + (hovers ? HOVER_MILLIS : 0)) / 1000f;
		int drain = bot.isInsideZoneType(ZoneType.FLY) && !bot.isInsideZoneType(ZoneType.NO_FLY) ? 1 : 2;
		return (int) Math.ceil(seconds * drain) + FP_MARGIN;
	}

	/**
	 * @return Whether the body could fly straight at a point without meeting anything.
	 *         <p>
	 *         Asked with the ground left out of it — the flag that would otherwise drop the far end of the ray onto the terrain, which is right for a
	 *         walker and wrong for a body that is going to be in the air the whole way. A clear ray comes back as the point that was asked about; a
	 *         blocked one comes back as where it was blocked.
	 */
	private static boolean airIsClear(Player bot, Vector3f to) {
		Vector3f met = GeoService.getInstance().getClosestCollision(bot, to.getX(), to.getY(), to.getZ(), false,
			CollisionIntention.DEFAULT_COLLISIONS.getId(), IgnoreProperties.ANY_RACE);
		return met == null || PositionUtil.getDistance(met.getX(), met.getY(), met.getZ(), to.getX(), to.getY(), to.getZ()) < 1;
	}

	/** @return Whether a point is inside one of the fly zones the bot is standing in, which is the only air it is allowed to fly through. */
	private static boolean insideAFlyZone(Player bot, Vector3f point) {
		for (ZoneInstance zone : bot.findZones()) {
			if (zone.getZoneTemplate().getZoneType() == ZoneClassName.FLY && zone.isInsideCoordinate(point.getX(), point.getY(), point.getZ()))
				return true;
		}
		return false;
	}

	/**
	 * @return The highest the bot may climb: the height asked for, or as far under its fly zone's ceiling as the clearance allows.
	 *         <p>
	 *         Read from the zone rather than guessed, because a zone is a volume and the volumes differ — {@code FLYINGZONESHAPE2_210020000} runs from
	 *         z 16 to 316 while {@code LF1_FZ_ILLUSIONARY_LAKE_210030000} stops at 260. Climbing out through the top ends the flight
	 *         ({@code PlayerController.onLeaveFlyArea}) and writes the bot into the audit log as a suspected hack.
	 */
	private static float ceilingFor(Player bot, float wanted) {
		float best = bot.getZ();
		for (ZoneInstance zone : bot.findZones()) {
			if (zone.getZoneTemplate().getZoneType() != ZoneClassName.FLY)
				continue;
			if (zone.isInsideCoordinate(bot.getX(), bot.getY(), wanted)) {
				best = Math.max(best, wanted);
				continue;
			}
			// a zone's top is not exposed, so it is bisected: the question the zone does answer is whether one point is inside it
			float inside = bot.getZ(), outside = wanted;
			for (int halving = 0; halving < 12 && outside - inside > 0.5f; halving++) {
				float middle = (inside + outside) / 2;
				if (zone.isInsideCoordinate(bot.getX(), bot.getY(), middle))
					inside = middle;
				else
					outside = middle;
			}
			best = Math.max(best, inside - CEILING_CLEARANCE);
		}
		return best;
	}

	private void step() {
		long now = System.currentTimeMillis();
		if (!bot.isSpawned() || bot.isDead()) {
			finish("it left the world or died in the air");
			return;
		}
		if (!(bot.getMoveController() instanceof BotMoveController mover)) {
			finish("it has no bot move controller to fly it");
			return;
		}
		if (bot.getWorldId() != worldId) {
			finish("it is on another map now"); // a teleport between maps, which no flight survives and none should try to
			return;
		}
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), wasAtX, wasAtY, wasAtZ) > TELEPORTED) {
			finish("something moved it " + Math.round(PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), wasAtX, wasAtY, wasAtZ))
				+ " m in one tick, which was not this flight");
			return;
		}
		wasAtX = bot.getX();
		wasAtY = bot.getY();
		wasAtZ = bot.getZ();

		if (!powerless && !bot.isFlying()) {
			// the engine ended it from under us: flight points gone, a zone left, an effect landed. Nothing falls server side, so the bot would hang
			// where it is for ever unless this brings it down.
			powerless = true;
			comeDown("it lost its flight with " + bot.getLifeStats().getCurrentFp() + " fp");
		} else if (bot.getEffectController().isAbnormalSet(AbnormalState.NOFLY)) {
			// The effect forbids flying and does not end a flight in progress, so a bot under it keeps its wings and its height until something else
			// decides otherwise. It comes down instead, which is what it would have to do anyway the moment anything interrupted it.
			comeDown("an effect forbids it to fly");
		} else if (bot.getTransformModel().cantFly()) {
			comeDown("it has been polymorphed into something that cannot fly");
		} else if (phase == Phase.FLYING && leg < legs.size() - 1 && bot.getLifeStats().getCurrentFp() <= FP_MARGIN) {
			comeDown("it was down to " + bot.getLifeStats().getCurrentFp() + " fp");
		}

		if (escorted != null && !landing && phase == Phase.FLYING && !followOn(mover))
			return;
		if (phase == Phase.FLYING)
			keepTheLegGoing(mover, now);

		switch (phase) {
			case TAKING_OFF -> {
				// nothing at all: the wings are coming out, and anything started inside that animation is swallowed by it
				if (now - takenOffAt >= TAKE_OFF_MILLIS) {
					phase = Phase.FLYING;
					flyLeg(mover);
				}
			}
			case FLYING -> {
				if (!mover.isFlightLegDone())
					return;
				if (escorted != null && !landing)
					return; // keeping station: the next leg comes from where the player goes, not from a list
				if (leg == hoverAfter) {
					phase = Phase.HOVERING;
					hoverUntil = now + HOVER_MILLIS;
					mover.endFlight(); // hold still up there, rather than leave every client interpolating past the top
				} else if (++leg >= legs.size())
					finish(null);
				else
					flyLeg(mover);
			}
			case HOVERING -> {
				if (now >= hoverUntil) {
					phase = Phase.FLYING;
					leg++;
					flyLeg(mover);
				}
			}
		}
	}

	/**
	 * Keeps the bot with the player it is following, and decides when to stop.
	 *
	 * @return false once the escort is over, in which case the flight is already coming down and this tick is done.
	 */
	private boolean followOn(BotMoveController mover) {
		if (!escorted.isSpawned() || !escorted.isFlying()) {
			comeDown(escorted.getName() + " is no longer in the air");
			return false;
		}
		if (bot.getLifeStats().getCurrentFp() <= ESCORT_RESERVE) {
			comeDown("it was down to " + bot.getLifeStats().getCurrentFp() + " fp and has to land under its own power");
			return false;
		}
		Vector3f spot = beside(escorted);
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), spot.getX(), spot.getY(), spot.getZ()) > MAX_JOURNEY) {
			comeDown(escorted.getName() + " is too far ahead to follow");
			return false;
		}
		if (!insideAFlyZone(bot, spot)) {
			// a body that leaves a fly zone in flight is dumped out of flight and written into the audit log, so the escort stops at the boundary
			comeDown("following " + escorted.getName() + " would take it out of its fly zone");
			return false;
		}
		Vector3f aimedAt = legs.get(leg);
		if (PositionUtil.getDistance(aimedAt.getX(), aimedAt.getY(), aimedAt.getZ(), spot.getX(), spot.getY(), spot.getZ()) > RETARGET) {
			legs.set(leg, spot);
			flyLeg(mover);
		}
		return true;
	}

	/**
	 * Watches the one thing the flight can see for itself: that the body is still being flown.
	 * <p>
	 * A leg the engine dropped — a stun, a teleport, anything that calls {@code abortMove}, or the movement tick deciding this body had arrived — is
	 * simply handed over again, from wherever the body now is. Nothing ever reports a removal, which is why this asks rather than trusts.
	 */
	private void keepTheLegGoing(BotMoveController mover, long now) {
		if (mover.isFlightLegDone())
			return;
		if (!mover.isFlying()) {
			flyLeg(mover);
			return;
		}
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), headwayX, headwayY, headwayZ) >= HEADWAY) {
			headwayX = bot.getX();
			headwayY = bot.getY();
			headwayZ = bot.getZ();
			headwayAt = now;
		} else if (now - headwayAt >= NO_HEADWAY_MILLIS) {
			log.warn("Bot {}'s flight has not moved for {} ms at z {}, so its leg is handed over again", bot.getName(), now - headwayAt, bot.getZ());
			flyLeg(mover);
		}
	}

	/**
	 * Gives up the rest of the journey and lands straight down, which is the only move left to a flight that has to end now.
	 * <p>
	 * Down <i>here</i> rather than back where it took off: a bot out of flight points has nothing to spend on going home, and the ground under the
	 * body is the one place a failing flight can always reach. The mesh is asked where that ground is, and the geometry answers for a map with no mesh.
	 */
	private void comeDown(String why) {
		if (landing)
			return; // already on its way down, and asking again would only start the descent over from wherever the body has fallen to
		landing = true;
		Vector3f ground = floorUnder(bot);
		log.info("Bot {} comes down from z {} to z {} because {}", bot.getName(), bot.getZ(), ground.getZ(), why);
		legs.subList(leg, legs.size()).clear();
		legs.add(ground);
		phase = Phase.FLYING;
		if (bot.getMoveController() instanceof BotMoveController mover)
			flyLeg(mover);
	}

	/**
	 * @return The ground under a body, however far under it that is.
	 *         <p>
	 *         The one question a flight that has to end must have answered, and the two obvious ways to ask it are both wrong at altitude.
	 *         {@code GeoService.getZ(worldId, x, y, z, instanceId)} looks two metres either side of the height it is given, and the mesh's
	 *         {@code groundNear} snaps within a radius of the point: a body 70 m up gets no answer from either, or worse, the answer that the ground
	 *         is where it already is. It then "lands" in mid air with its flight points intact, and whatever it does next it does from up there — in
	 *         the case that found this, by taking off again, because walking anywhere from the sky is proved impossible.
	 *         <p>
	 *         So the question is asked as a drop: from just above the body, down as far as a map is tall.
	 */
	private static Vector3f floorUnder(Player bot) {
		float floor = GeoService.getInstance().getZ(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ() + 1, bot.getZ() - MAX_DROP,
			bot.getInstanceId());
		if (!Float.isNaN(floor))
			return new Vector3f(bot.getX(), bot.getY(), floor);
		Vector3f mesh = NavmeshService.getInstance().groundNear(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ());
		if (mesh != null)
			return mesh;
		log.warn("Bot {} has no ground under it at {} {} z {}, so it stays where it is", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
		return new Vector3f(bot.getX(), bot.getY(), bot.getZ());
	}

	/** Hands the current leg to the mover, and starts the headway watch over. */
	private void flyLeg(BotMoveController mover) {
		Vector3f point = legs.get(Math.min(leg, legs.size() - 1));
		headwayX = bot.getX();
		headwayY = bot.getY();
		headwayZ = bot.getZ();
		headwayAt = System.currentTimeMillis();
		mover.flyLegTo(point.getX(), point.getY(), point.getZ(), powerless ? POWERLESS_SPEED : speedOf(bot));
	}

	/**
	 * @return How fast the body moves, in metres a second.
	 *         <p>
	 *         Read raw rather than through {@code adjustStatByMovementModifier}, which the walking path uses. That modifier is chosen by the angle
	 *         between where the body faces and where it is going, and a flight is not a strafe.
	 */
	private static float speedOf(Player bot) {
		return Math.max(bot.getGameStats().getMovementSpeedFloat(), 1);
	}

	private void finish(String cutShort) {
		if (task != null)
			task.cancel(false);
		// the mover first, so the body is out of the movement tick before anything else can ask it to walk, and the clients are told where it came to
		// rest rather than left interpolating past it
		if (bot.getMoveController() instanceof BotMoveController mover)
			mover.endFlight();
		inTheAir.remove(bot.getObjectId());
		if (bot.isFlying())
			bot.getFlyController().endFly(true);
		if (cutShort != null)
			log.info("Bot {}'s flight ended because {}", bot.getName(), cutShort);
		else
			log.info("Bot {} lands at {} {} {} with {} fp left after {} s{}", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()), bot.getZ(),
				bot.getLifeStats().getCurrentFp(), (System.currentTimeMillis() - takenOffAt) / 1000f,
				powerless ? ", having lost its flight on the way" : "");
	}
}
