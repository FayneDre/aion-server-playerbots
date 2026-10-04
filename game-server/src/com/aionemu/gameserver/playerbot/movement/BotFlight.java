package com.aionemu.gameserver.playerbot.movement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.templates.zone.ZoneClassName;
import com.aionemu.gameserver.model.templates.zone.ZoneType;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.zone.ZoneInstance;

/**
 * Takes a bot off the ground, holds it there, and brings it back down.
 * <p>
 * The first milestone of flight and deliberately the whole of it: straight up over the spot it stood on, a few seconds in the air, straight back down.
 * No navigation and no decision making, because the two things worth getting right here are worth getting right on their own — that the bot comes down
 * under power, and that it never takes off without the flight points to finish.
 * <p>
 * <b>Why it has a stepping loop of its own.</b> {@link BotMoveController#moveToDestination} interpolates x and y and then replaces z with a geo probe
 * two metres either side of the interpolated height, so a bot that handed its climb to the mover would be snapped back to the ground on the first
 * step. Teaching the mover to fly is the next milestone; see {@code docs/flight-plan.md}.
 * <p>
 * <b>And why coming down is the hard half.</b> Falling is entirely client side: {@code CM_MOVE} is what sets {@code MovementMask.FALL} and calls
 * {@code updateFalling}, and no server code moves anything downwards. A bot that runs out of flight points at height does not fall — it hangs there,
 * unable to take off again, for ever. So the descent is flown, its cost is reserved before the take-off, and if the engine ends the flight from under
 * this class anyway, the bot is still walked down to the ground it left.
 */
public class BotFlight {

	private static final Logger log = LoggerFactory.getLogger(BotFlight.class);

	/** Flights in progress, by object id. A bot is in here from its take-off until it is back on the ground. */
	private static final Map<Integer, BotFlight> inTheAir = new ConcurrentHashMap<>();

	/** High enough to be unmistakable from the ground, and well under any fly zone's ceiling. */
	private static final float DEFAULT_HEIGHT = 25;
	/** As high as this command will take a bot, whatever is asked for. A milestone's worth of height, not a journey's. */
	private static final float MAX_HEIGHT = 60;
	/** How long the bot holds its height before coming down, which is what makes one command a whole cycle nobody has to finish by hand. */
	private static final long HOVER_MILLIS = 4000;
	private static final long STEP_MILLIS = 100;
	/**
	 * How long the take-off animation is given before the body starts moving.
	 * <p>
	 * Leaving the ground is an animation like standing up or drawing a weapon, and this module has known since its first week that the server has to
	 * leave room for one: a real player's client refuses input until the animation it is playing has finished, which is what produces the pauses an
	 * onlooker sees, and a bot has no client to produce them. Starting the climb inside the take-off was the whole of it — the body set off, the
	 * animation reclaimed it, and it arrived at the top only when the leg ended. The number is empirical, as every animation number here is, and is
	 * the one standing up and drawing a weapon already use. See {@code docs/engine-traps.md}.
	 */
	private static final long TAKE_OFF_MILLIS = 1200;
	/**
	 * How often a climb is told to the clients, in milliseconds.
	 * <p>
	 * A flight packet carries a velocity, and a velocity is one second of travel: the clients fly the body to where it says and stop there. So this
	 * has to be comfortably inside a second, or the body arrives and waits for the next packet — which from the ground is a climb that sets off,
	 * stops, and jumps. Ten times a second is the other end of it and is worse: that many announcements of a change of direction never get going at
	 * all. Half a second leaves each one superseded at the half way point of what it bought.
	 */
	private static final long TELL_CLIENTS_MILLIS = 500;
	/** Near enough to a target height to call it reached; a step at flight speed covers rather more than this. */
	private static final float ARRIVED = 0.5f;
	/**
	 * Flight points held back over and above the climb, the hover and the descent.
	 * <p>
	 * Not a safety habit but a necessity: the drain is a task of its own ticking once a second, so the arithmetic below cannot be exact to better than
	 * a point either way — and running out of flight at two metres costs exactly what running out at fifty does.
	 */
	private static final int FP_MARGIN = 5;
	/** How close to a fly zone's ceiling the bot may climb. The zone is a volume, and leaving it in flight is audited and ends the flight. */
	private static final float CEILING_CLEARANCE = 3;
	/** How fast a bot that has lost its flight comes down. Faster than it flies, because nothing it does up there is any use to it now. */
	private static final float POWERLESS_SPEED = 12;

	/**
	 * How far forward the climb carries, as a share of how far up it goes.
	 * <p>
	 * A bot used to rise straight up, and nothing in this game ever does: a player gains height by flying forward and up. The movement packet says
	 * the same thing, since what it carries is a velocity — and a purely vertical one has no horizontal component at all for a client to draw.
	 * Reported from in game as a bot that stayed on the ground for the whole climb and then appeared at the top of it.
	 * <p>
	 * A slant is also the shape of the work after this one: reaching a terrace is a hop up and across, never a lift.
	 */
	private static final float FORWARD_SHARE = 0.6f;
	/**
	 * The most time one step may account for, in seconds, however long it actually waited for its turn.
	 * <p>
	 * The loop paces itself by the clock rather than by its own period, which is right — a step that arrives late has to cover the ground it missed,
	 * or the flight runs slow. But the bot pool is shared with every other bot on the server, and a step delayed by seconds would then cover the whole
	 * climb in one move: the body stands on the ground, and then it is at the top. Which is exactly what was reported from in game.
	 * <p>
	 * So lateness beyond this is written off rather than made up. A starved flight is slower than it should be; it is never a jump, and the warning
	 * below says when it happened instead of leaving it to be guessed at from a video.
	 */
	private static final float LONGEST_STEP_SECONDS = 0.3f;

	private enum Phase {
		TAKING_OFF,
		CLIMBING,
		HOVERING,
		DESCENDING
	}

	private final Player bot;
	/** Where the bot took off from, which is where it lands: the descent returns to it, so it comes down onto ground it was just standing on. */
	private final float groundX, groundY, groundLevel;
	/** The top of the climb, ahead as well as above. See {@link #FORWARD_SHARE}. */
	private final float apexX, apexY, apexZ;
	private Phase phase = Phase.TAKING_OFF;
	private final long takenOffAt = System.currentTimeMillis();
	private long hoverUntil;
	private long lastStep = System.currentTimeMillis();
	/** The height the current leg was announced to the clients for, so a new leg is told apart from one already under way. */
	private float legTarget = Float.NaN;
	private long lastTold;
	/** How many times the climb told the clients where the body was, and the longest it ever went without telling them. */
	private int tells;
	private long worstTellGap;
	/** True once the flight has ended without this class asking, in which case the bot is brought down with no flight state at all. */
	private boolean powerless;
	private ScheduledFuture<?> task;

	private BotFlight(Player bot, float apexX, float apexY, float apexZ) {
		this.bot = bot;
		this.groundX = bot.getX();
		this.groundY = bot.getY();
		this.groundLevel = bot.getZ();
		this.apexX = apexX;
		this.apexY = apexY;
		this.apexZ = apexZ;
	}

	/**
	 * Flies a bot up, holds it there, and lands it again.
	 *
	 * @param height How far above its own feet to climb, or zero for the default.
	 * @return What to tell whoever asked, whether it worked or not.
	 */
	public static String takeOff(Player bot, float height) {
		if (inTheAir.containsKey(bot.getObjectId()))
			return bot.getName() + " is already in the air";

		float wanted = height > 0 ? Math.min(height, MAX_HEIGHT) : DEFAULT_HEIGHT;
		float ceiling = ceilingFor(bot, bot.getZ() + wanted);
		double angle = Math.toRadians(PositionUtil.convertHeadingToAngle(bot.getHeading()));
		float forward = (ceiling - bot.getZ()) * FORWARD_SHARE;
		float apexX = bot.getX() + (float) Math.cos(angle) * forward;
		float apexY = bot.getY() + (float) Math.sin(angle) * forward;
		String refusal = whyNot(bot, ceiling);
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

		BotFlight flight = new BotFlight(bot, apexX, apexY, ceiling);
		inTheAir.put(bot.getObjectId(), flight);
		// The engine's pool, not the bots' own. Moving a body is not thinking, and the bot pool is deliberately held to half the processors and runs
		// eighty decision ticks a second behind whatever is queued ahead of them -- measured here as a first step that waited 7.8 seconds for its turn
		// while the wings were already out. Walking has never been on that pool either: it rides the engine's MoveTaskManager.
		flight.task = ThreadPoolManager.getInstance().scheduleAtFixedRate(flight::step, STEP_MILLIS, STEP_MILLIS);
		int fp = bot.getLifeStats().getCurrentFp();
		// The speed is logged because every flight fault so far has come down to it and it can be read back from nowhere else: 9 m/s is a daeva
		// flying, 6 is one running, 1.5 is one walking, and the three come from different branches of the same method.
		log.info("Bot {} takes off from z {} for {} m up and {} m forward at {} m/s with {} fp (flying={}, state={})", bot.getName(), bot.getZ(),
			ceiling - bot.getZ(), forward, speedOf(bot), fp, bot.isInFlyingState(), bot.isInState(CreatureState.FLYING));
		return bot.getName() + " takes off for " + Math.round(ceiling - bot.getZ()) + " m with " + fp + " fp";
	}

	/** Brings a bot down now rather than when its hover is over. */
	public static String land(Player bot) {
		BotFlight flight = inTheAir.get(bot.getObjectId());
		if (flight == null)
			return bot.getName() + " is not in the air";
		flight.phase = Phase.DESCENDING;
		return bot.getName() + " is coming down";
	}

	/** @return true while the bot is off the ground under this class, which is the one thing its decision tick must not act during. */
	public static boolean isInTheAir(Player bot) {
		return inTheAir.containsKey(bot.getObjectId());
	}

	/**
	 * Every reason a take-off would not work, asked here rather than left to the engine.
	 * <p>
	 * {@code FlyController.startFly} refuses for six different reasons and reports each one as a system message to the player's client — which a bot
	 * does not have, so to a bot every refusal is the same silent {@code false}. A caller that cannot tell them apart retries the one that will never
	 * succeed, which is how the buff loop happened. See {@code docs/engine-traps.md}.
	 *
	 * @param ceiling The height the climb would actually stop at, which is what the flight points are reserved against.
	 */
	private static String whyNot(Player bot, float ceiling) {
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
		if (ceiling - bot.getZ() < 1)
			return bot.getName() + " has no room above it inside its fly zone";

		int needed = fpFor(bot, ceiling - bot.getZ());
		int have = bot.getLifeStats().getCurrentFp();
		if (have < needed)
			return bot.getName() + " has " + have + " of the " + needed + " fp that the climb, the hover and the landing would cost";
		return null;
	}

	/**
	 * @return What the whole cycle costs in flight points — climb, hover and descent together, plus the margin.
	 *         <p>
	 *         The drain is 1 fp/s inside a fly zone and 2 outside it ({@code PlayerLifeStats.triggerFpReduce}), so a bot that climbed out through a
	 *         zone's ceiling would also be paying double. One more reason never to reach it.
	 */
	private static int fpFor(Player bot, float height) {
		float speed = Math.max(bot.getGameStats().getMovementSpeedFloat(), 1);
		float seconds = 2 * height / speed + (HOVER_MILLIS + TAKE_OFF_MILLIS) / 1000f;
		int drain = bot.isInsideZoneType(ZoneType.FLY) && !bot.isInsideZoneType(ZoneType.NO_FLY) ? 1 : 2;
		return (int) Math.ceil(seconds * drain) + FP_MARGIN;
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
		float seconds = (now - lastStep) / 1000f;
		lastStep = now;
		if (seconds > LONGEST_STEP_SECONDS) {
			log.warn("Bot {}'s flight waited {} s for its turn on the bot pool, so that much of its climb is paced rather than covered",
				bot.getName(), seconds);
			seconds = LONGEST_STEP_SECONDS;
		}

		if (!bot.isSpawned() || bot.isDead()) {
			finish("it left the world or died in the air");
			return;
		}
		if (!powerless && !bot.isFlying()) {
			// the engine ended it from under us: flight points gone, a zone left, an effect landed. Nothing falls server side, so the bot would hang
			// where it is for ever unless this brings it down.
			log.info("Bot {} lost its flight at z {} with {} fp, and is being brought down", bot.getName(), bot.getZ(),
				bot.getLifeStats().getCurrentFp());
			powerless = true;
			phase = Phase.DESCENDING;
		}
		if (phase != Phase.DESCENDING && phase != Phase.TAKING_OFF && bot.getLifeStats().getCurrentFp() <= FP_MARGIN) {
			log.info("Bot {} turns back at z {} with {} fp left", bot.getName(), bot.getZ(), bot.getLifeStats().getCurrentFp());
			phase = Phase.DESCENDING;
		}

		switch (phase) {
			case TAKING_OFF -> {
				// nothing at all: the wings are coming out, and anything started inside that animation is swallowed by it
				if (now - takenOffAt >= TAKE_OFF_MILLIS)
					phase = Phase.CLIMBING;
			}
			case CLIMBING -> {
				if (flyTo(apexX, apexY, apexZ, seconds)) {
					phase = Phase.HOVERING;
					hoverUntil = now + HOVER_MILLIS;
					legTarget = Float.NaN;
					if (bot.getMoveController() instanceof BotMoveController mover)
						mover.endFlightLeg();
				}
			}
			case HOVERING -> {
				if (now >= hoverUntil)
					phase = Phase.DESCENDING;
			}
			case DESCENDING -> {
				if (flyTo(groundX, groundY, groundLevel, seconds))
					finish(null);
			}
		}
	}

	/**
	 * Moves the bot towards a point in the air, announcing the leg and then keeping the server side position in step with what the clients draw.
	 * <p>
	 * The leg is announced once, when it starts, and nothing is sent in between: the clients have the destination and draw their way to it at the
	 * body's own speed, which is the speed this moves it at. It is how a walking bot has always been drawn.
	 *
	 * @return true once the bot is there.
	 */
	private boolean flyTo(float x, float y, float z, float seconds) {
		float gapX = x - bot.getX(), gapY = y - bot.getY(), gapZ = z - bot.getZ();
		float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY + gapZ * gapZ);
		if (gap <= ARRIVED)
			return true;

		float speed = powerless ? POWERLESS_SPEED : speedOf(bot);
		boolean rising = gapZ > 0;
		boolean newLeg = legTarget != z;
		legTarget = z;
		long now = System.currentTimeMillis();
		if (!rising) {
			// coming down, a destination is enough: every client draws its own way there, and draws it perfectly
			if (newLeg && bot.getMoveController() instanceof BotMoveController mover)
				mover.beginFlightLeg(x, y, z);
		} else if ((newLeg || now - lastTold >= TELL_CLIENTS_MILLIS) && bot.getMoveController() instanceof BotMoveController mover) {
			if (!newLeg)
				worstTellGap = Math.max(worstTellGap, now - lastTold);
			lastTold = now;
			tells++;
			mover.beginFlightVelocity(x, y, z, speed); // a climb is driven, and each packet buys one second -- see beginFlightVelocity
		}
		float fraction = Math.min(speed * seconds / gap, 1);
		World.getInstance().updatePosition(bot, bot.getX() + gapX * fraction, bot.getY() + gapY * fraction, bot.getZ() + gapZ * fraction,
			bot.getHeading(), true);
		return false;
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
		inTheAir.remove(bot.getObjectId());
		if (bot.isFlying())
			bot.getFlyController().endFly(true);
		// the authoritative position last, after the landing: a client that is still interpolating a leg has to be told where the body actually came
		// to rest, and an onlooker who arrived mid flight has never been told at all
		if (bot.isSpawned() && bot.getMoveController() instanceof BotMoveController mover)
			mover.endFlightLeg();
		if (cutShort != null)
			log.info("Bot {}'s flight ended because {}", bot.getName(), cutShort);
		else
			// The climb is the half the clients cannot draw by themselves, so how regularly it was told to them is the one number that says whether
			// a bad looking flight was this server's doing. Every packet is meant to be superseded at the half way point of the second it buys: a
			// worst gap near TELL_CLIENTS_MILLIS is a flight that was told properly, and anything approaching a second is one that was not.
			log.info("Bot {} lands at z {} with {} fp left, having told the clients {} times, worst gap {} ms{}", bot.getName(), bot.getZ(),
				bot.getLifeStats().getCurrentFp(), tells, worstTellGap, powerless ? ", having lost its flight on the way" : "");
	}
}
