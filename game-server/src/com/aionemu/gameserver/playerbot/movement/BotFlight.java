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
import com.aionemu.gameserver.world.zone.ZoneInstance;

/**
 * Takes a bot off the ground, holds it there, and brings it back down.
 * <p>
 * The first milestone of flight and deliberately the whole of it: straight up over the spot it stood on, a few seconds in the air, straight back down.
 * No navigation and no decision making, because the two things worth getting right here are worth getting right on their own — that the bot comes down
 * under power, and that it never takes off without the flight points to finish.
 * <p>
 * <b>It decides the phases and moves nothing.</b> The body is flown by {@link BotMoveController}, on the movement tick the engine already runs for
 * every moving creature: this asks for a leg, waits for it to be reached, and asks for the next. It used to step the body itself, in a loop of its
 * own, because the mover replaced z with the height of the ground underneath — and that loop then had its first step wait 7.8 seconds behind eighty
 * bots thinking. Moving a body is movement; see {@code docs/flight-plan.md}.
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
	/**
	 * How long a leg may make no headway before it is handed to the mover again, in milliseconds.
	 * <p>
	 * The flight asks for a leg and the movement tick flies it, which means the flight depends on being ticked — and the one thing that went wrong
	 * when it started depending on that was the tick quietly dropping the body: {@code MoveTaskManager} asks after every step whether the destination
	 * is reached and removes anything that says yes. Nothing is ever told that it was dropped. So instead of trusting it, the flight watches the one
	 * thing it can see for itself, which is whether the body is still moving, and asks again when it is not. A body that stops being flown also stops
	 * telling the clients where it is, and they go on drawing it along the last velocity they were given — 329 m up, in the case that found this.
	 */
	private static final long NO_HEADWAY_MILLIS = 1500;
	/** How far the body must get in that time for the leg to count as being flown. */
	private static final float HEADWAY = 0.5f;

	/** How often the phases are looked at. It decides and does not move, so it runs at the movement tick's own period rather than faster. */
	private static final long STEP_MILLIS = 200;
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
	 * <p>
	 * <b>And it is what sets how fast the body gains height.</b> A body flies at one speed, so the slant divides it between forward and up: half a
	 * metre forward per metre up spends most of 9 m/s on the climb, which reads from the ground as a bot shooting into the sky rather than a player
	 * taking off. Three metres forward for every two up puts the climb at about 5 m/s, which is what a daeva gaining height actually looks like.
	 */
	private static final float FORWARD_SHARE = 1.5f;

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
	/** The leg the mover was last given, kept so an interrupted one can be handed back rather than lost. */
	private float legX, legY, legZ;
	/** Where the body was when it last made headway, and when that was. */
	private float headwayX, headwayY, headwayZ;
	private long headwayAt;
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
		if (!bot.isSpawned() || bot.isDead()) {
			finish("it left the world or died in the air");
			return;
		}
		if (!(bot.getMoveController() instanceof BotMoveController mover)) {
			finish("it has no bot move controller to fly it");
			return;
		}
		if (!powerless && !bot.isFlying()) {
			// the engine ended it from under us: flight points gone, a zone left, an effect landed. Nothing falls server side, so the bot would hang
			// where it is for ever unless this brings it down.
			log.info("Bot {} lost its flight at z {} with {} fp, and is being brought down", bot.getName(), bot.getZ(),
				bot.getLifeStats().getCurrentFp());
			powerless = true;
			phase = Phase.DESCENDING;
			flyTo(mover, groundX, groundY, groundLevel);
		}
		if (phase == Phase.CLIMBING && bot.getLifeStats().getCurrentFp() <= FP_MARGIN) {
			log.info("Bot {} turns back at z {} with {} fp left", bot.getName(), bot.getZ(), bot.getLifeStats().getCurrentFp());
			phase = Phase.DESCENDING;
			flyTo(mover, groundX, groundY, groundLevel);
		}
		// a leg the engine dropped -- a stun, a teleport, anything that calls abortMove -- is simply asked for again, from wherever the body now is
		boolean flyingALeg = phase == Phase.CLIMBING || phase == Phase.DESCENDING;
		if (flyingALeg && !mover.isFlying() && !mover.isFlightLegDone())
			flyTo(mover, legX, legY, legZ);
		else if (flyingALeg && !mover.isFlightLegDone()) {
			if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), headwayX, headwayY, headwayZ) >= HEADWAY) {
				headwayX = bot.getX();
				headwayY = bot.getY();
				headwayZ = bot.getZ();
				headwayAt = now;
			} else if (now - headwayAt >= NO_HEADWAY_MILLIS) {
				log.warn("Bot {}'s flight has not moved for {} ms at z {}, so its leg is handed over again", bot.getName(), now - headwayAt,
					bot.getZ());
				flyTo(mover, legX, legY, legZ);
			}
		}

		switch (phase) {
			case TAKING_OFF -> {
				// nothing at all: the wings are coming out, and anything started inside that animation is swallowed by it
				if (now - takenOffAt >= TAKE_OFF_MILLIS) {
					phase = Phase.CLIMBING;
					flyTo(mover, apexX, apexY, apexZ);
				}
			}
			case CLIMBING -> {
				if (mover.isFlightLegDone()) {
					phase = Phase.HOVERING;
					hoverUntil = now + HOVER_MILLIS;
					mover.endFlight(); // hold still up there, rather than leave every client interpolating past the top
				}
			}
			case HOVERING -> {
				if (now >= hoverUntil) {
					phase = Phase.DESCENDING;
					flyTo(mover, groundX, groundY, groundLevel);
				}
			}
			case DESCENDING -> {
				if (mover.isFlightLegDone())
					finish(null);
			}
		}
	}

	/** Hands a leg to the mover and remembers it, so one dropped by an interruption can be asked for again. */
	private void flyTo(BotMoveController mover, float x, float y, float z) {
		legX = x;
		legY = y;
		legZ = z;
		headwayX = bot.getX();
		headwayY = bot.getY();
		headwayZ = bot.getZ();
		headwayAt = System.currentTimeMillis();
		mover.flyLegTo(x, y, z, powerless ? POWERLESS_SPEED : speedOf(bot));
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
			// The climb is the half the clients cannot draw by themselves, so how regularly it was told to them is the one number that says whether
			// a bad looking flight was this server's doing. Every packet is meant to be superseded at the half way point of the second it buys: a
			// worst gap near TELL_CLIENTS_MILLIS is a flight that was told properly, and anything approaching a second is one that was not.
			log.info("Bot {} lands at z {} with {} fp left after {} s{}", bot.getName(), bot.getZ(), bot.getLifeStats().getCurrentFp(),
				(System.currentTimeMillis() - takenOffAt) / 1000f, powerless ? ", having lost its flight on the way" : "");
	}
}
