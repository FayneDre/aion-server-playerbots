package com.aionemu.gameserver.playerbot.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AITemplate;
import com.aionemu.gameserver.controllers.attack.AggroTarget;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.BindPointPosition;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotLootManager;
import com.aionemu.gameserver.playerbot.combat.BotPotionManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetRegistry;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.lifecycle.BotPacing;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Brain of a player bot. The engine fires creature events on whatever AI is attached to a creature, so a bot receives ATTACK, MOVE_ARRIVED and
 * ATTACK_COMPLETE for free, unlike the NpcAI-typed handlers in {@code ai/handler} which cannot be reused here.
 */
public class PlayerBotAI extends AITemplate<Player> {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	private static final int THINK_INTERVAL_MILLIS = 1000;
	/** A chase is abandoned after this long, whatever the reason it is not getting anywhere. */
	private static final long MAX_CHASE_MILLIS = 15000;
	/**
	 * How far from its anchor a bot may be dragged before it breaks off and comes back. It is the camp radius on purpose: the bot roams that far to
	 * find fights, so a shorter leash would make it abandon the very mobs it just walked to.
	 */
	private static final float LEASH_DISTANCE = BotTargetSelector.HOME_RADIUS;
	/** A chased target is only given a new route once it has moved this far, instead of on every tick. */
	private static final float RETARGET_STEP = 3f;
	/**
	 * Minimum delay between two routes towards the same chased target. Each new route makes clients restart their interpolation, so re-routing on
	 * every attack tick turns a run into a stutter.
	 */
	private static final long CHASE_REROUTE_INTERVAL = 600;
	/**
	 * How long the bot waits after getting up before it swings, so the two do not arrive as one frame of the client's work. How long each posture
	 * itself takes to draw is {@link BotPosture}'s business; this is the fight's own sequencing, which is why it stays here.
	 */
	private static final long DRAW_AFTER_STAND_MILLIS = 1500;
	/** How long the weapon stays drawn after a fight, so the bot does not sheathe it between two mobs of the same pull. */
	private static final long SHEATHE_DELAY_MILLIS = 8000;
	/** How long a target the bot could not reach is left alone, so it does not pick the same unreachable one again right away. */
	private static final long UNREACHABLE_MILLIS = 10000;
	/** How long the bot leaves alone whatever killed it, so a lost fight is not restarted on a loop. */
	private static final long KILLER_AVOIDED_MILLIS = 120000;
	/**
	 * How long a bot may be in a fight without the attack loop running before the fight is abandoned.
	 * <p>
	 * The decision tick does nothing while {@link #isAttacking()}, so a lost attack task left a bot standing beside a mob for ever: seen in game
	 * after an hour, three bots of nineteen frozen that way and the count rising, with no exception in the log and every pool thread idle. Whatever
	 * loses the task, this notices and lets the bot decide again — and says so, so the cause stays visible instead of being papered over.
	 */
	private static final long STALLED_FIGHT_MILLIS = 15000;
	/**
	 * How long a fight may run without the bot landing anything before it is given up.
	 * <p>
	 * A different failure from the one above, and invisible to it: the attack loop is alive and ticking, and every tick does nothing. The loop only
	 * swings through {@code BotAttackManager.autoAttack}, which requires line of sight, so a target standing in reach behind a rock, a root or the
	 * lip of a slope is attacked for ever at no cost to itself -- while it answers, which is how it was seen: a bot at four metres, facing the thing
	 * killing it, never swinging. {@link #STALLED_FIGHT_MILLIS} cannot catch this because it measures the gap between ticks, and the ticks are fine.
	 * <p>
	 * Longer than {@link #MAX_CHASE_MILLIS}, because a bot crossing open ground to something far away has landed nothing yet either, and that is not
	 * a stuck fight but a fight that has not started.
	 */
	private static final long FRUITLESS_FIGHT_MILLIS = 20000;
	/**
	 * Longest a swing may be scheduled ahead. No weapon in the game is slower than this, so a larger figure is a stat gone wrong rather than a slow
	 * weapon, and scheduling it would park the bot for as long as it says.
	 */
	private static final int MAX_ATTACK_DELAY_MILLIS = 5000;
	/**
	 * Health below which a bot that is outnumbered breaks off and leaves.
	 * <p>
	 * Being outmatched on level needs no threshold — that fight is lost at full health and the bot runs at once — but being set upon by two things
	 * its own size is a fight it may well win, and leaving at the first scratch would mean never finishing one.
	 */
	private static final int RETREAT_HP_PERCENT = 40;
	/** How long whatever drove the bot off is left alone afterwards, so it does not turn round and walk back into it. */
	private static final long RETREAT_AVOIDED_MILLIS = 30000;
	/** Health below which the bot waits to regenerate instead of looking for a fight. */
	private static final int MIN_ENGAGE_HP_PERCENT = 90;
	/**
	 * Mana below which the bot sits down rather than starting another fight.
	 * <p>
	 * Nothing used to look at mana at all, so a bot at full health and an empty bar fought on for ever with its auto attack alone — and never sat, so
	 * it never recovered, because sitting is what pays the eightfold regeneration rate. Seen across whole regions: casters permanently out of mana,
	 * which also made every measurement of their damage a measurement of a character with its kit switched off.
	 * <p>
	 * Above {@code BotSkillManager.MANA_RESERVE_PERCENT} on purpose. The reserve is the point where the bot stops spending on skills; if the bot were
	 * allowed to stand up at or below it, it would rise from its rest already unable to cast and sit straight back down.
	 */
	private static final int MIN_ENGAGE_MP_PERCENT = 35;
	/** Close enough to the anchor to count as home, so the bot does not fidget over a metre. */
	/** The penalty skill a player carries after dying, under which a bot will not start a fight. */
	private static final int SOUL_SICKNESS_SKILL = 8291;
	/** How little life a target needs for the bot to stop looking after itself and simply end the fight. */
	private static final int FINISH_IT_PERCENT = 20;
	private static final float ANCHOR_TOLERANCE = 5f;
	/**
	 * How far from the place it shares a bot stands.
	 * <p>
	 * Every bot of a camp was given the same point to walk to and the same five metre tolerance for having arrived, so they settled on top of one
	 * another: seen in game as a pile of characters with a ring of corpses around it, which reads as a spawner rather than as people working a
	 * ground. Kept inside {@link #ANCHOR_TOLERANCE}, so a bot standing on its own spot is still at its anchor and nothing that asks that question
	 * has to learn a second one.
	 */
	private static final float SPREAD_RADIUS = 4f;
	/** How close a bot gets to its own spot before it stops. Tight, because the spot is already its own and not worth haggling over. */
	private static final float SPOT_TOLERANCE = 1.5f;
	/** How near its place in the formation a follower settles. Small, because that place is already set apart from the leader. */
	private static final float FOLLOW_DISTANCE = 2f;
	/** Roughly the time a player spends looking at the resurrection window. */
	private static final int REVIVE_DELAY_MILLIS = 10000;
	/**
	 * How far from its bind point a bot gets before it is willing to sit down.
	 * <p>
	 * An obelisk is the one spot in a region that every death in it leads back to, and a bot comes back from one at a quarter of its health with
	 * ninety to climb to — minutes of resting, spent precisely where the next corpse will appear. Ten of them seated on the same steps is not a
	 * population, it is a queue, and it is what a screenshot of Morheim showed. Wide enough to clear the plinth and whatever stands on it.
	 */
	private static final float OBELISK_CLEARANCE = 60f;

	/** Guards the scheduled tasks against concurrent starts and stops, since events and ticks run on different pool threads. */
	private final Object combatLock = new Object();
	private ScheduledFuture<?> attackTask;
	/**
	 * Which attack loop is the current one. A tick whose generation has been superseded retires instead of scheduling its successor.
	 * <p>
	 * Without it, anything that restarted the loop while a tick was already running forked it in two: {@code cancel(false)} does not stop a task that
	 * has begun, so the old tick reached its end, saw a task set, and scheduled a third. Two loops then struck at once for the same bot.
	 */
	private long attackGeneration;
	/** When the attack loop last ran, so the decision tick can tell a fight in progress from one that has silently stopped. */
	private volatile long lastAttackTick;
	/** When the bot last actually did something to its target -- a swing sent or a skill cast, not a tick that decided to do neither. */
	private volatile long lastBlow;
	private ScheduledFuture<?> thinkTask;
	/** Non null between death and resurrection, which also marks the death as already handled. */
	private ScheduledFuture<?> reviveTask;
	/**
	 * True while a decision tick is actually running, as opposed to merely scheduled.
	 * <p>
	 * The tick holds {@code combatLock} at its two ends and not in the middle, where the deciding happens — deliberately, since a decision reaches
	 * most of the module and holding a lock across it would serialise every bot in the world. So "is this bot busy right now" cannot be read from the
	 * task, and the population director needs exactly that question answered before it takes the character out of the world.
	 */
	private boolean ticking;
	/**
	 * True once the bot has agreed to leave the world, and never false again: the object is discarded immediately afterwards.
	 * <p>
	 * It is what makes {@link #tryRetire()} a decision rather than a request. A departure that merely cancelled the tasks would still race the tick
	 * that had already begun, and {@code cancel(false)} does not stop one — the same trap the attack generation was added for.
	 */
	private boolean retiring;
	private volatile boolean autonomous = true;

	/** Where the bot belongs: it fights around this point and returns to it rather than following a target across the map. */
	private volatile float anchorX, anchorY, anchorZ;
	/** This bot's chosen place to stand, and the anchor it was chosen for. Kept because choosing it asks the mesh, and the answer only changes with the anchor. */
	private volatile Vector3f standingSpot;
	private volatile float spotForX, spotForY;
	private volatile long chaseStartTime;
	private volatile long lastChaseRoute;
	/**
	 * Whether this character is somebody's own rather than one the world made to fill a region. Read once: it is a fact about the character and does
	 * not change while it is in the world.
	 * <p>
	 * An owned bot is its owner's, and the population model keeps its hands off it — it is not paced, not capped, not rehoused and not counted.
	 */
	private final boolean owned;
	/** How fast the bot is allowed to advance, and how far, which depends on whether it is anybody's companion at this moment. */
	private final BotPacing pacing;
	/** What the bot's body is doing, and the time the client needs to draw a change of it. */
	private final BotPosture posture;
	/** What a resident does with its day. Adventurers pass straight through it and hunt. */
	private final BotDay day;
	/** The chores between fights: the corpse, the bag, the shop. */
	private final BotErrands errands;
	/** When the last fight ended, 0 once the weapon has been put away. */
	private volatile long combatEndedAt;
	/** Targets not to pick for a while: either out of reach, or having just killed the bot. */
	private final Map<Integer, Long> ignoredTargets = new ConcurrentHashMap<>();
	/** How many refusals in a row mean the bot is not blocked from somewhere but wedged where it stands. */
	private static final int STUCK_REFUSALS = 12;
	/** Refusals in a row, reset by any movement that starts. */
	private int refusedMoves;
	/** An operator asked for a sale, so the full bag test is waived until one actually happens. */
	/**
	 * Whether this fight's opening has been tried. One attempt per fight and no more: an opening burst that will not go off now is on cooldown, which
	 * is itself the answer, and retrying it every tick would spend it halfway through the fight where most of it is wasted.
	 */
	private volatile boolean openingSpent;
	/** Whether this fight's approach has been tried. Separate from the opening, so hiding does not cost the bot its burst as well. */
	private volatile boolean approachSpent;
	/**
	 * Set when sent somewhere by command rather than by its own decision, so the tick that runs a second later does not immediately hijack the trip
	 * for whatever mob happens to be findable along the way. Self defence still overrides it: this only holds off the bot picking its own fights.
	 * <p>
	 * Never read directly — {@link #isRunningErrand()} pairs it with the move controller, which is what actually knows whether the bot is still on
	 * its way. A flag cleared by hand instead would have to be cleared on every way a journey can end, and missing one leaves a bot standing still
	 * for good.
	 */
	private volatile boolean manualErrand;

	public PlayerBotAI(Player owner) {
		super(owner);
		this.owned = BotRoster.isSomebodysOwn(owner.getName());
		this.pacing = new BotPacing(owner, owned);
		this.pacing.reconcile(); // before the first tick, so a bot cannot earn a second's experience at the wrong rate on its way in
		this.posture = new BotPosture(owner);
		this.day = new BotDay(this);
		this.errands = new BotErrands(this);
	}

	BotPosture posture() {
		return posture;
	}

	/**
	 * @return true if this character is somebody's own, which is what exempts it from everything the population model does to its own inhabitants.
	 *         <p>
	 *         Public because the director has to tell a region's inhabitants from the companions standing among them, and it counts them every half
	 *         minute — the answer is settled once as the character loads rather than asked of the database each time.
	 */
	public boolean isOwned() {
		return owned;
	}

	/**
	 * @return The bot's own move controller, or null if it has none. Installed as the bot enters the world, so null means it is not in the world —
	 *         which every journey has to allow for anyway.
	 */
	BotMoveController moves() {
		return getOwner().getMoveController() instanceof BotMoveController moveController ? moveController : null;
	}

	/** @return The point the bot fights around and comes back to. */
	Vector3f anchor() {
		return new Vector3f(anchorX, anchorY, anchorZ);
	}

	/** @return true if the bot is near enough its anchor to count as arrived, rather than fidgeting over a metre. */
	boolean isAtAnchor() {
		return PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) <= ANCHOR_TOLERANCE;
	}

	/**
	 * Sets the point the bot fights around and returns to when a chase drags it too far.
	 */
	public void setAnchor(float x, float y, float z) {
		anchorX = x;
		anchorY = y;
		anchorZ = z;
	}

	public boolean isAutonomous() {
		return autonomous;
	}

	/**
	 * When autonomous, the bot engages hostiles within reach and fights back on its own. Turn it off to drive it purely by command.
	 */
	public void setAutonomous(boolean autonomous) {
		this.autonomous = autonomous;
		if (!autonomous)
			stopAttacking();
	}

	/**
	 * Sends the bot walking to a point on command, protected from the next decision tick picking a fight along the way instead. Self defence is
	 * exempt on purpose: a bot that ignored being hit while running an errand would be worse than one that took a short detour.
	 *
	 * @return false if the bot could not set off at all, in which case nothing is being protected.
	 */
	public boolean walkTo(float x, float y, float z) {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		manualErrand = tryMoveTo(moveController, x, y, z);
		return manualErrand;
	}

	/**
	 * @return true while the bot is still walking somewhere it was sent by command. Asks the move controller rather than trusting the flag on its
	 *         own, so an errand that ended in any way at all — arrival, an obstacle, a stop, death — ends here too.
	 */
	private boolean isRunningErrand() {
		if (!manualErrand)
			return false;
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isTravelling())
			return true;
		manualErrand = false;
		return false;
	}

	/**
	 * Joins whoever invites the bot, and keeps its anchor on its group leader.
	 * <p>
	 * Moving the anchor is the whole of "follow": every rule the bot already had — come back when there is nothing to do, break off a chase that
	 * leaves the area — is written against the anchor, so pointing that at the leader makes it travel with the group without a second set of movement
	 * rules to keep in step with the first.
	 *
	 * @return true while the bot has a leader to follow, which is also what makes it stop deciding things for itself.
	 */
	private boolean followTheGroup() {
		BotGroupManager.acceptPendingInvite(getOwner());
		BotGroupManager.leaveIfLeaderless(getOwner());
		Player leader = BotGroupManager.leaderToFollow(getOwner());
		if (leader == null)
			return false;
		// the bot's own place beside the leader, not the leader's feet: everything downstream already works off the anchor, so this is all "stand
		// with the group" needs to be
		Vector3f spot = BotGroupManager.formationSpot(getOwner(), leader);
		setAnchor(spot.getX(), spot.getY(), spot.getZ());
		return true;
	}

	/**
	 * What a bot does while it belongs to someone else's group: the leader's fight, or the leader's heels. Nothing else.
	 * <p>
	 * A member that pulls what it likes is worse than no member at all — it brings a second mob into a fight the group did not choose, and wanders
	 * off while it does. So the whole of the bot's own initiative is suspended here: no picking targets, no roaming the camp, no errands, no shop
	 * runs. Defending itself is not initiative and stays where it was, ahead of everything, in {@link #botTick()}.
	 */
	private void serveTheGroup() {
		if (posture.standUp()) // one tick before acting, so the animation has played out by then
			return;
		if (BotSkillManager.tryBuffSelf(getOwner()) || BotSkillManager.tryChantMantra(getOwner()))
			return;
		if (tendToTheGroup())
			return;
		// A healer in a group does not join the attack, and that is the rule rather than a limitation. Assisting means closing to weapon range, and a
		// cleric that walks into the mob is a cleric being hit, out of position and silent for the rest of the fight. Standing with the group and
		// keeping it alive is the whole of the job. It still defends itself: {@link #findAttacker} is checked ahead of all of this, so something that
		// comes for the healer is still answered.
		Creature assisted = BotRole.of(getOwner()) == BotRole.HEALER ? null : BotGroupManager.targetToAssist(getOwner());
		if (assisted == null) {
			// Deliberately no exception for a tank. "Engages first" means first into the fight the group has chosen, not free to choose one: a bot
			// that pulls brings a second mob into a fight nobody asked for, and a tank pulling is the worst version of it because the rest of the
			// group then assists it. Starting a fight stays the player's.
			followLeader(); // standing by is the default, not looking for something to do
			return;
		}
		BotTargetRegistry.forceClaim(assisted, getOwner()); // the group piles on together, which is the point of being one
		startAttacking(assisted);
	}

	/**
	 * Looks after the group mates: the most hurt one first, then whoever is missing a buff.
	 *
	 * @return true if a skill was cast, in which case the bot is busy with it.
	 */
	private boolean tendToTheGroup() {
		// A healer reads the danger as well as the damage; everyone else who happens to carry a heal reads the bar, as before. Giving every class
		// the anticipating rule would have a chanter break off mid swing because somebody took a scratch.
		boolean anticipate = BotRole.of(getOwner()) == BotRole.HEALER;
		Player hurt = BotGroupManager.mostHurtMember(getOwner(), BotSkillManager.HEAL_ALLY_PERCENT, anticipate);
		if (BotSkillManager.tryHealAlly(getOwner(), hurt))
			return true;
		for (Player member : BotGroupManager.membersToTendTo(getOwner())) {
			if (BotSkillManager.tryBuffAlly(getOwner(), member))
				return true;
		}
		return false;
	}

	/**
	 * @return true when the bot has fallen behind its group and should walk rather than sit down. Resting takes a bot out of the fight for as long as
	 *         it lasts, which is fine alone in an empty camp and not fine when the group has moved on without it.
	 */
	private boolean mustCatchUp(boolean following) {
		return following && PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) > ANCHOR_TOLERANCE;
	}

	/** Decision loop, kept separate from the framework's {@code think()} so nothing in the engine can trigger it unexpectedly. */
	private void botTick() {
		synchronized (combatLock) {
			if (retiring || thinkTask == null) // on its way out of the world, or already gone: nothing is decided from here on
				return;
			ticking = true;
		}
		try {
			decide();
		} catch (RuntimeException e) {
			// The tick reschedules itself, so an exception thrown anywhere in it used to stop that bot thinking for the rest of its life, silently and
			// one bot at a time. The decision chain reaches most of the module, so this is the one place where that can be made impossible.
			log.error("Bot " + getOwner().getName() + " failed a decision tick", e);
		} finally {
			synchronized (combatLock) {
				ticking = false;
				if (thinkTask != null && !retiring) // still spawned, and not leaving
					scheduleBotTick();
			}
		}
	}

	/**
	 * Asks the bot to leave the world, and answers whether this is a moment at which it may.
	 * <p>
	 * The population director decides <i>that</i> a region holds too many inhabitants; it must not decide <i>when</i> any one of them goes, because
	 * every reason not to is state only the bot holds. A character taken out mid-swing drops a monster's target in front of whoever was watching; one
	 * taken out dead is stored as a corpse and comes back as one; one taken out mid-journey leaves a tick that has already begun to walk an object
	 * that no longer exists, which the engine answers with "can't update position of despawned object" and a decision half made.
	 * <p>
	 * Refusing costs nothing: the director comes round again every half minute, and a bot that is busy now will not be in a minute. So every one of
	 * these is a plain no rather than something to wait for.
	 *
	 * @return true if the bot has just given up its future ticks, in which case the caller <b>must</b> take it out of the world — nothing else will
	 *         start it thinking again.
	 */
	public boolean tryRetire() {
		synchronized (combatLock) {
			if (retiring || ticking || attackTask != null || reviveTask != null)
				return false;
			Player bot = getOwner();
			if (bot.isDead() || getState() == AIState.DIED)
				return false;
			// A team mate that vanishes is a hole in somebody's party window, and the one bot in a group is worth more to a player than the shape of a
			// region's population.
			if (bot.isInGroup() || bot.isInAlliance())
				return false;
			if (bot.getMoveController() instanceof BotMoveController moveController && moveController.isTravelling())
				return false;
			retiring = true;
			if (thinkTask != null) {
				thinkTask.cancel(false);
				thinkTask = null;
			}
			return true;
		}
	}

	/** What the bot decides to do this second, in priority order. Called only by {@link #botTick()}, which is what keeps it running. */
	private void decide() {
		if (BotFlight.isInTheAir(getOwner()))
			return; // nothing is decided for a body in the air, and nothing above this line runs either: rehousing one would teleport it
		sheathWhenCalm();
		abandonStalledFight();
		// A bot crosses its level, and joins and leaves a group, while it is playing rather than while it is loading, so both are settled here rather
		// than on the way in. Neither does anything until the answer actually changes.
		pacing.reconcile();
		day.moveOutIfOutgrown();
		boolean following = followTheGroup();
		if (getOwner().isSpawned() && getOwner().isDead())
			handleDeath();
		else if (posture.isAnimating()) {
			// Nothing on top of an animation already playing. Deliberately not inside tryMoveTo, where it would have been one check for every kind of
			// journey at once: a refusal there means the geometry leads nowhere, and two callers give the errand up for good on one — a bot would have
			// abandoned the corpse it was walking to for the crime of having just stood up.
		} else if (getOwner().isCasting()) {
			// A cast roots a real player and their client refuses to start anything else until it is over. This tick comes round every second, and
			// the Bandage Heal every character knows incants for four, so each decision that landed inside one either started a competing cast or
			// walked off and cancelled it — five hundred times in three minutes across a populated map. The attack tick has held this line from the
			// start; the decision tick never did.
		} else if (autonomous && !following && retreatIfLosing()) {
			// Running from a fight it cannot win, which is the one thing a bot never did: it defended itself against anything, at any level, in any
			// number, until it died -- then resurrected, walked back to the home it still had beside the thing that killed it, and did it again.
			// Measured at the Tursin Outpost on Verteron, where survival at five minutes was nil.
		} else if (autonomous && !isAttacking() && getOwner().isSpawned()) {
			Creature attacker = findAttacker();
			if (attacker != null)
				startAttacking(attacker); // no health check: a bot being hit defends itself, it does not sit down
			else if (errands.collectLoot()) {
				// busy with a corpse, everything else can wait
			} else if (errands.dressUp()) {
				// dressing itself, which is five seconds of stillness the bot has to commit to rather than hope for
			} else if (!isHealthyEnoughToFight() && !mustCatchUp(following) && !mustHoldTheLine(following)) {
				if (!walkOffTheObelisk()) // never sit down where it resurrected: that spot is shared by everyone who died in the region
					recover();
			} else if (following) {
				serveTheGroup(); // a member has no business picking its own fights, so the rest of this chain is not its to run
			} else if (errands.runVendorTrip()) {
				// bag is full and a shop is being walked to or worked, everything else waits
			} else if (isRunningErrand()) {
				// an operator sent it somewhere on purpose; let it arrive before it goes looking for its own fights
			} else if (!posture.standUp()) { // stand up one tick before acting, so the animation has played out by then
				// buffs go up before a fight is picked, never during one, where the cast would cost a swing.
				// the servant before the buffs, because it is the one of them that is still there in ten minutes and the class cannot fight without it
				if (!callUpServant() && !boardRobot() && !BotSkillManager.tryBuffSelf(getOwner())
					&& !BotSkillManager.tryChantMantra(getOwner()) && !day.pursue()) {
					// Starting a fight is the one thing the death penalty forbids, and it used to forbid everything: the bot sat where it stood for
					// as long as the sickness lasted, which on an obelisk is where every corpse in the region comes back. Walking, going home,
					// buffing and the errands above are all things a player does on the way back from a death, so none of them is gated here.
					Creature target = isRecoveringFromDeath() ? null : BotTargetSelector.findTarget(getOwner(), this::isIgnored);
					if (target == null)
						roam();
					else if (BotTargetRegistry.claim(target, getOwner())) // another bot may have picked it in the same tick
						startAttacking(target);
				}
			}
		}
	}

	private void scheduleBotTick() {
		thinkTask = BotScheduler.getInstance().schedule(this::botTick, THINK_INTERVAL_MILLIS);
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check — for testing the trip without farming a bag full first. Part of the AI's public
	 * face because {@code //bot sell} asks for it.
	 *
	 * @return false if there is nothing worth selling, or the bot's map has no shop to walk to.
	 */
	public boolean forceSellTrip() {
		return errands.forceSellTrip();
	}

	public void startAttacking(Creature target) {
		combatEndedAt = 0; // fighting again, so the pending sheathe is off
		openingSpent = false;
		approachSpent = false;
		lastBlow = System.currentTimeMillis(); // a fight that has just started has landed nothing yet, and that is not a stalled one
		BotTargetRegistry.forceClaim(target, getOwner()); // retaliation and commands are not negotiable
		boolean gettingUp = posture.standUp(); // canAttack() is false while resting
		if (getOwner().isProtectionActive()) // CM_ATTACK does this for a real player
			getOwner().getController().stopProtectionActiveTask();
		synchronized (combatLock) {
			stopAttackTask();
			getOwner().setTarget(target);
			setStateIfNot(AIState.FIGHT);
			// drawing the weapon in the same breath as standing up leaves the client blending two poses, which shows as a floating character
			scheduleAttackTick(gettingUp ? (int) DRAW_AFTER_STAND_MILLIS : 0);
		}
		log.info("Bot {} starts attacking {}", getOwner().getName(), target.getName());
	}

	public void stopAttacking() {
		cancelCombat();
		setStateIfNot(AIState.IDLE);
	}

	private void cancelCombat() {
		BotTargetRegistry.releaseAllOf(getOwner());
		synchronized (combatLock) {
			stopAttackTask();
			chaseStartTime = 0;
			stopMoving();
			// the weapon stays out for a moment: sheathing between two mobs of the same pull looks like a nervous tic, and a real player does not do it
			combatEndedAt = System.currentTimeMillis();
			getOwner().setTarget(null);
		}
	}

	public boolean isAttacking() {
		synchronized (combatLock) {
			return attackTask != null;
		}
	}

	/**
	 * Lets go of a fight the attack loop has stopped running, so the bot can decide again instead of standing beside its target for ever.
	 */
	private void abandonStalledFight() {
		if (!isAttacking())
			return;
		long now = System.currentTimeMillis();
		if (now - lastAttackTick >= STALLED_FIGHT_MILLIS) {
			synchronized (combatLock) {
				// A swing still waiting its turn is a busy server, not a lost loop, and the two want opposite things done about them. Every
				// occurrence measured so far has been the second kind and not the first: seventeen bots at one and the same second, thirty three
				// seconds after a start, while the pool worked through a hundred wakes of some twenty database round trips each -- and in the run
				// before that, fifteen inside one second, likewise just after a start. Letting those fights go threw the fight away and blamed the
				// loop for the schedule, which is a cause invented rather than found.
				if (attackTask != null && !attackTask.isDone()) {
					log.debug("Bot {} has gone {} s without a swing at {}, which is queued behind a busy pool", getOwner().getName(),
						(now - lastAttackTick) / 1000, nameOfTarget());
					return;
				}
			}
			log.warn("Bot {} has been fighting {} for {} s and its attack loop has stopped; letting the fight go", getOwner().getName(),
				nameOfTarget(), (now - lastAttackTick) / 1000);
			stopAttacking();
			return;
		}
		if (now - lastBlow < FRUITLESS_FIGHT_MILLIS)
			return;
		// Left alone afterwards, like one that could not be reached: something about where this thing stands makes it unfightable from here, and the
		// bot has no way to tell what. Without that, the next decision tick picks the same target back out of the same list and the fight that
		// landed nothing starts again -- which is what standing still in front of a monster looks like from outside.
		Creature target = getOwner().getTarget() instanceof Creature creature ? creature : null;
		log.warn("Bot {} has been fighting {} for {} s without landing anything; letting the fight go", getOwner().getName(), nameOfTarget(),
			(now - lastBlow) / 1000);
		stopAttacking();
		if (target != null)
			ignoredTargets.put(target.getObjectId(), now + UNREACHABLE_MILLIS);
	}

	private String nameOfTarget() {
		return getOwner().getTarget() == null ? "nothing" : getOwner().getTarget().getName();
	}

	private void attackTick(long generation) {
		lastAttackTick = System.currentTimeMillis();
		try {
			fight();
		} finally {
			synchronized (combatLock) {
				// only the current loop schedules the next one, and only if nothing stopped it while this tick was running
				if (attackTask != null && generation == attackGeneration)
					scheduleAttackTick(getOwner().getGameStats().getAttackSpeed().getCurrent());
			}
		}
	}

	private void fight() {
		Player bot = getOwner();
		Creature target = bot.getTarget() instanceof Creature creature ? creature : null;
		if (!BotAttackManager.canKeepFighting(bot, target)) {
			if (target != null && target.isDead() && BotLootManager.hasLootFor(bot, target.getObjectId()))
				errands.rememberCorpse(target.getObjectId()); // the decision tick walks over and picks it up
			log.info("Bot {} stops attacking", bot.getName());
			stopAttacking();
			return;
		}

		// idempotent, and deferred to here so it never overlaps the stand up animation. Drawing is an animation in its own right, and the tick below
		// used to walk the bot off in the same instant it was sent: the slide the bot showed between getting up and setting off was those two frames
		// of the client's work arriving as one.
		posture.drawWeapon(target);
		if (bot.getCastingSkill() != null) {
			// casting roots a real player, so the bot neither moves nor starts another action until the cast is over
		} else if (BotAttackManager.isInAttackRange(bot, target)) {
			chaseStartTime = 0;
			stopMoving();
			if (useBestSkill(target) || BotAttackManager.autoAttack(bot, target))
				lastBlow = System.currentTimeMillis();
		} else if (castFromAfar(target)) {
			lastBlow = System.currentTimeMillis();
			// something in the bot's book reaches where its weapon does not, so there is nothing to close
		} else if (BotRole.of(bot) == BotRole.HEALER && bot.getCurrentTeam() != null) {
			// Out of reach of anything it knows, and a healer does not walk closer: holding position beside the group is the whole of the job, and
			// the surplus damage it was spending is not worth leaving it for. It waits instead; the group's own fight brings the target into range,
			// and where it never does the stalled fight watchdog lets this one go.
		} else if (!chase(target)) {
			log.info("Bot {} cannot reach {} and gives up", bot.getName(), target.getName());
			ignoredTargets.put(target.getObjectId(), System.currentTimeMillis() + UNREACHABLE_MILLIS);
			stopAttacking();
			return;
		}

	}

	private void scheduleAttackTick(int delayMillis) {
		int delay = Math.clamp(delayMillis, 0, MAX_ATTACK_DELAY_MILLIS);
		if (delay != delayMillis)
			log.warn("Bot {} asked to swing in {} ms, which is not a weapon speed; using {}", getOwner().getName(), delayMillis, delay);
		long generation = ++attackGeneration;
		attackTask = BotScheduler.getInstance().schedule(() -> attackTick(generation), delay);
		lastAttackTick = System.currentTimeMillis(); // a scheduled swing counts as alive, so the watchdog measures the gap and not the delay
	}

	private void stopAttackTask() {
		attackGeneration++; // whatever is running now is no longer the current loop and must not schedule a successor
		if (attackTask != null) {
			attackTask.cancel(false);
			attackTask = null;
		}
	}

	/**
	 * Attacks a target still out of weapon reach, when the bot knows something that reaches that far.
	 * <p>
	 * Without this a bot walks into melee before it will cast anything, which throws its class away: a priest closed to staff range to cast a spell
	 * good from twenty five metres. There is no need to work out which skills reach, since the engine checks each one's range as it is cast — trying
	 * is itself the question "does anything reach from here".
	 *
	 * @return true if a skill went off, in which case the bot holds its ground for the cast.
	 */
	private boolean castFromAfar(Creature target) {
		if (!useBestSkill(target))
			return false;
		chaseStartTime = 0;
		stopMoving(); // a cast roots a real player, and sliding through one is the animation fault we keep paying for elsewhere
		return true;
	}

	/**
	 * Picks the one thing the bot does with this moment of the fight, most urgent first.
	 * <p>
	 * The order is the whole of the bot's combat judgement, so it lives in one place rather than being restated wherever a skill might be cast. It
	 * reads as a set of priorities: survive, then keep the advantage the class was given, then deal damage. A cast costs a swing either way, which is
	 * why staying alive comes first and why nothing here is tried twice in the same tick.
	 *
	 * @return true if a skill went off, in which case the bot is busy and should not also swing.
	 */
	/**
	 * @return true while the bot is still carrying the penalty it got for dying.
	 *         <p>
	 *         It takes a large bite out of every stat, and picking a fight under it is how you die a second time — so that one thing waits, and
	 *         nothing else does. The first version waited out the whole of it doing nothing at all, which is how obelisks came to hold a dozen
	 *         seated bots apiece. Defending itself, going home, resting, running an errand and following a group are all untouched.
	 */
	private boolean isRecoveringFromDeath() {
		return getOwner().getEffectController().hasAbnormalEffect(SOUL_SICKNESS_SKILL);
	}

	/**
	 * @return true if the target is close enough to death that finishing it beats anything else the bot could do with the time.
	 */
	private static boolean isAlmostDead(Creature target) {
		return target.getLifeStats() != null && target.getLifeStats().getHpPercentage() <= FINISH_IT_PERCENT;
	}

	private boolean useBestSkill(Creature target) {
		Player bot = getOwner();
		boolean closing = !BotAttackManager.isInAttackRange(bot, target);
		if (closing && !approachSpent) {
			approachSpent = true;
			// only while there is still ground to cover: hiding on top of a target it is about to hit buys the bot nothing
			if (BotSkillManager.tryApproachUnseen(bot))
				return true;
		}
		// Holding the mob is the tank's whole job, and it is the one case where a skill must be preferred over a stronger one: a taunt that lands
		// keeps the mob off the cleric, and the damage the bot gave up for it is damage the group deals instead. Only in a group — a templar alone
		// taunting the thing already hitting it is a wasted cast — and only on something that is not already looking at the bot.
		if (bot.getCurrentTeam() != null && BotRole.of(bot) == BotRole.TANK) {
			if (!bot.equals(target.getAggroList().getTarget(AggroTarget.MOST_HATED)) && BotSkillManager.tryTaunt(bot, target))
				return true;
			// The one it is swinging at is only half the job. A monster that has got past the tank and is hitting somebody else is the half a group
			// actually feels, and the half that was missing: "le templier ne gère pas correctement l'aggro si plus de 1 mob".
			Creature loose = BotGroupManager.enemyLooseOnAMate(bot);
			if (loose != null && BotSkillManager.tryTaunt(bot, loose))
				return true;
		}
		if (!openingSpent) {
			openingSpent = true;
			// before the first blow, because a burst spent on a mob already dying is a burst thrown away — and self buffs need no range, so this works
			// while still closing in
			if (BotSkillManager.tryOpeningCooldown(bot))
				return true;
		}
		// Finish it. A target this close to death dies to the next blow or two, and every second spent mending instead is a second it spends hitting
		// back: a bot at low health healing in front of a mob with a sliver left heals, gets hit, heals again, and dies to something it could have
		// killed twice over. Ending the fight is the best defence available and no rule below can see that.
		if (!isAlmostDead(target)) {
			// the defensive ability comes before the heal: it is cheaper in mana and it stops damage instead of repairing it, which only works ahead
			if (BotSkillManager.tryDefensiveCooldown(bot) || BotSkillManager.tryHealSelf(bot, BotSkillManager.HEAL_IN_COMBAT_PERCENT))
				return true;
			// the flask is what a class with no heal of its own has instead, and what a healer reaches for when the heal is on cooldown
			if (BotPotionManager.tryHealingPotion(bot, BotSkillManager.HEAL_IN_COMBAT_PERCENT)
				|| BotPotionManager.tryManaPotion(bot, BotSkillManager.MANA_RESERVE_PERCENT))
				return true;
			// a group mate's life outranks the bot's damage, but not the bot's own: a dead healer heals nobody
			if (BotSkillManager.tryHealAlly(bot, BotGroupManager.mostHurtMember(bot, BotSkillManager.HEAL_ALLY_PERCENT)))
				return true;
		}
		// a leap covers ground the bot would otherwise walk, and those last metres on foot are where bots get stuck
		if (closing && BotSkillManager.tryGapCloser(bot, target))
			return true;
		return BotSkillManager.tryCastSkill(bot, target);
	}

	/**
	 * Walks towards a target that is out of weapon reach.
	 * <p>
	 * Every bound here exists because reactive steering cannot guarantee it will ever arrive: the leash keeps a fleeing target from dragging the bot
	 * across the map, the timeout ends chases that make no progress, and the move controller reports geometry it could not get through.
	 *
	 * @return false if the bot should give this target up.
	 */
	private boolean chase(Creature target) {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (chaseStartTime == 0)
			chaseStartTime = now;
		else if (now - chaseStartTime > MAX_CHASE_MILLIS)
			return false;
		if (PositionUtil.getDistance(anchorX, anchorY, target.getX(), target.getY()) > LEASH_DISTANCE)
			return false;
		if (posture.isAnimating())
			return true; // on its feet, or drawing its weapon; walking now is what makes it slide

		if (moveController.isInMove()
			&& (now - lastChaseRoute < CHASE_REROUTE_INTERVAL || moveController.isHeadingTo(target.getX(), target.getY(), RETARGET_STEP)))
			return true; // already on its way, and the target has not moved enough to be worth a new route
		lastChaseRoute = now;
		return tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	void stopMoving() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isInMove())
			moveController.stop();
	}

	/**
	 * Nothing to fight within reach, so go looking. The bot heads for the nearest mob still inside its camp, and only falls back to its anchor when
	 * the whole camp is clear. That is what turns a bot standing at a spawn point into one that works an area.
	 */
	private void roam() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return; // already on its way somewhere

		Creature target = BotTargetSelector.findTargetWithin(getOwner(), BotTargetSelector.HOME_RADIUS, this::isIgnored);
		if (target == null || PositionUtil.getDistance(anchorX, anchorY, target.getX(), target.getY()) > BotTargetSelector.HOME_RADIUS) {
			returnToAnchor(); // camp is empty, or the only mobs left belong to someone else's patch
			return;
		}
		if (posture.standUp())
			return;
		tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	/** Brings the bot back where it belongs once it has nothing to fight, so a chase does not slowly displace it. */
	/**
	 * Walks to the leader, re-aimed on every tick.
	 * <p>
	 * {@link #returnToAnchor()} waits for the current leg to end before looking again, which is right for a camp that does not move and wrong for a
	 * leader who is walking: the bot heads for where the leader stood a leg ago, so the longer the journey the further behind it arrives.
	 * {@code moveToPoint} is built to be called repeatedly — it keeps its plan unless the destination has shifted more than a couple of metres — so
	 * re-aiming every tick costs nothing and bounds the lag by that distance instead of by the length of a leg.
	 */
	private void followLeader() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return;
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) > FOLLOW_DISTANCE) {
			// already on its way there, and the place has not drifted enough to be worth a new plan. Without this the bot re-plans every single tick
			// while its leader runs, and every plan turns it a little differently, which is what the walk looks like from outside: restless.
			if (moveController.isInMove() && moveController.isHeadingTo(anchorX, anchorY, RETARGET_STEP))
				return;
			tryMoveTo(moveController, anchorX, anchorY, anchorZ);
			return;
		}
		// Arrived. The leg in progress is aimed at wherever the leader stood when it was issued, so letting it run walks the bot past a leader who
		// has turned round, and the next tick walks it back: the small pacing of a follower forever arriving where its leader no longer is. Stopping
		// is safe here and nothing hangs on it — the ai reads whether the bot is travelling from the move controller rather than from a flag of its
		// own, so there is no state left believing the journey is still on.
		stopMoving();
	}

	boolean returnToAnchor() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return true;
		Vector3f spot = standingSpot();
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), spot.getX(), spot.getY()) <= SPOT_TOLERANCE)
			return true;
		return tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
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
	private Vector3f standingSpot() {
		Vector3f known = standingSpot;
		if (known != null && spotForX == anchorX && spotForY == anchorY)
			return known;
		int id = getOwner().getObjectId();
		double angle = Math.toRadians(id % 360);
		// Two independent digits of the id, so bots with neighbouring ids do not end up on the same ray out of the centre.
		float radius = SPREAD_RADIUS * (0.4f + 0.6f * (id / 360 % 10) / 9f);
		float x = anchorX + (float) (Math.cos(angle) * radius), y = anchorY + (float) (Math.sin(angle) * radius);
		Vector3f chosen = reachableSpot(x, y);
		standingSpot = chosen;
		spotForX = anchorX;
		spotForY = anchorY;
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
		Vector3f anchor = new Vector3f(anchorX, anchorY, anchorZ);
		NavmeshService navmesh = NavmeshService.getInstance();
		Vector3f ground = navmesh.groundNear(getOwner().getWorldId(), x, y, anchorZ);
		if (ground == null)
			return anchor; // no mesh for this map, or nothing walkable within reach of the offset
		// Standable is not reachable: the far side of a wall is good ground that joins nothing, and a bot posted on one spends its life asking for a
		// way out. The same question BotPlaces already asks when it scatters villagers around a village centre.
		return navmesh.canReach(getOwner().getWorldId(), anchorX, anchorY, anchorZ, ground.getX(), ground.getY(), ground.getZ()) ? ground : anchor;
	}

	/**
	 * Every request this ai makes to move, so that refusals can be counted in one place.
	 * <p>
	 * Counting them inside one caller was not enough: a bot wedged behind a rock is refused whatever it asks for — a corpse to loot, a mob to reach,
	 * its own camp — and whichever of those it happened to ask for was the one path that did not count. One bot was refused sixty four times while
	 * the safety net saw twelve.
	 *
	 * @return true if the bot set off.
	 */
	boolean tryMoveTo(BotMoveController moveController, float x, float y, float z) {
		// A sidestep is not progress. moveToPoint answers "on its way" whenever the reactive layer found some direction to try, including the one it
		// has already tried and abandoned, so a bot wedged against scenery reported a move every tick and reset this counter every tick -- and the
		// rescue below, which exists for exactly that bot, was never reached. Asking the controller whether the journey is blocked is the honest
		// question: it says so only once it has given up, either walled in or making no headway, and it clears the moment a new journey is asked for.
		if (moveController.moveToPoint(x, y, z) && !moveController.isBlocked()) {
			refusedMoves = 0;
			return true;
		}
		if (++refusedMoves >= STUCK_REFUSALS)
			freeItself();
		return false;
	}

	/**
	 * Puts a bot that cannot move at all back where it lives.
	 * <p>
	 * However carefully a place is chosen, a bot walks on its own afterwards, and the reactive layer that steps round obstacles can walk it into one
	 * — behind a rock, under a root, onto a shelf. From inside, every destination is refused, including spots a pace away: three separate bots have
	 * now spent minutes asking for routes that do not exist from where they stand. There is no diagnosis to make at that point and nothing to walk
	 * out along, which is exactly why players are given an unstick command rather than advice.
	 */
	private void freeItself() {
		Player bot = getOwner();
		Vector3f home = day.home();
		refusedMoves = 0;
		if (home == null)
			return;
		// Onto ground the mesh agrees a body stands on, which is not the same as the recorded home. A home is a spot chosen from spawn data, and
		// sending a bot back to one that sits on a roof or inside a rock is how the rescue came to feed the fault it exists to cure: one bot was put
		// back home five times and was still stuck twenty-five minutes later, because every landing was as unwalkable as the place it left.
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), home.getX(), home.getY(), home.getZ());
		float x = ground != null ? ground.getX() : home.getX();
		float y = ground != null ? ground.getY() : home.getY();
		float z = ground != null ? ground.getZ() : home.getZ();
		log.info("Bot {} could not move at all from {} {} and is put back home", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()));
		day.forget(); // whatever it was doing was decided from a place it is no longer in
		// Counted, because landing a bot back at its home only helps while the home is somewhere it can walk out of. When it is not, this rescue runs
		// every thirteen seconds for as long as the server is up, and the bot needs a different home rather than another lift to the same one.
		Vector3f moved = day.recordStranding();
		if (moved != null) {
			x = moved.getX();
			y = moved.getY();
			z = moved.getZ();
		}
		TeleportService.teleportTo(bot, bot.getWorldId(), x, y, z);
		setAnchor(x, y, z);
	}

	/**
	 * Keeps the bot from starting a fight it is in no shape for — in particular right after resurrecting at 25% hp, next to whatever killed it.
	 * Only picking a fight is gated: it always defends itself, whatever its health.
	 */
	/**
	 * Sits down to heal where the bot stands, because resting recovers eight times faster than standing around. It recovers on the spot rather than
	 * walking home first: the fight is over, and a player sits down where it ended.
	 */
	/**
	 * Gets the bot up and remembers when, because moving it while the client is still playing the stand up animation makes it slide across the
	 * ground.
	 *
	 * @return true if it was resting and has just stood up, in which case the caller should not act yet.
	 */
	/** Puts the weapon away once the bot has really stopped fighting, rather than at the end of every single kill. */
	private void sheathWhenCalm() {
		if (combatEndedAt == 0 || isAttacking())
			return;
		if (System.currentTimeMillis() - combatEndedAt < SHEATHE_DELAY_MILLIS)
			return;
		combatEndedAt = 0;
		posture.sheathe();
	}

	private void recover() {
		if (getOwner().getMoveController().isInMove())
			return;
		// Healing is far quicker than resting, but it spends mana that resting would have restored alongside the health. So it is worth a cast only
		// when there is a real wound to close and the mana to spare; a scratch, or an empty mana bar, and sitting down wins on both counts.
		if (getOwner().getLifeStats().getMpPercentage() >= BotSkillManager.HEAL_MIN_MP_PERCENT
			&& BotSkillManager.tryHealSelf(getOwner(), BotSkillManager.HEAL_AFTER_COMBAT_PERCENT))
			return;
		if (combatEndedAt != 0) {
			// put the weapon away before sitting: sheathing later, while seated, plays a standing animation on a seated body
			combatEndedAt = 0;
			posture.sheathe(); // which claims the time it needs, so the tick that sits down does not land on it
			return;
		}
		posture.sitDown();
	}

	/**
	 * Walks a bot that has just resurrected off the obelisk before it settles down to heal.
	 * <p>
	 * The resting itself is right and stays: eight times the regeneration rate is the difference between a minute and ten. What was wrong is only
	 * <i>where</i>, and the reason it was wrong is that nothing in the chain distinguished the ground a bot fell on from the ground it comes back on.
	 * A player does the walk back; that walk is what empties the obelisk, and a bot refusing to make it is what filled one.
	 * <p>
	 * It only ever applies within {@value #OBELISK_CLEARANCE} metres of the bind point, so a bot hurt anywhere else sits down where it stands exactly
	 * as before, and a bot whose home happens to be the obelisk's own ground has nowhere to go and is left alone.
	 *
	 * @return true if the bot is busy getting clear, in which case it must not rest this tick.
	 */
	private boolean walkOffTheObelisk() {
		Player bot = getOwner();
		BindPointPosition bind = bot.getBindPoint();
		if (bind == null || bind.getMapId() != bot.getWorldId())
			return false;
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bind.getX(), bind.getY()) > OBELISK_CLEARANCE)
			return false;
		if (isAtAnchor())
			return false;
		if (posture.standUp())
			return true; // on its feet first, and the client is owed the tick it takes to draw that
		return returnToAnchor();
	}

	/**
	 * @return true when a tank must stay on its feet although it is hurt, because its group is in a fight.
	 *         <p>
	 *         Sitting down at ninety percent is right for a bot alone in a field and catastrophic for the one member holding the mob: the moment it
	 *         rests it stops being hit, the mob picks the cleric instead, and the group loses the fight it was winning. The health rule is kept for
	 *         every other role and for the same tank the moment the fight is over.
	 */
	private boolean mustHoldTheLine(boolean following) {
		return following && BotRole.of(getOwner()) == BotRole.TANK && BotGroupManager.targetToAssist(getOwner()) != null;
	}

	private boolean isHealthyEnoughToFight() {
		return getOwner().getLifeStats().getHpPercentage() >= MIN_ENGAGE_HP_PERCENT
			&& getOwner().getLifeStats().getMpPercentage() >= MIN_ENGAGE_MP_PERCENT;
	}

	/**
	 * @return An npc currently attacking the bot, or null. Being hit overrides every other consideration, including the health threshold that
	 *         normally sends the bot resting: sitting down under fire is both suicidal and absurd to watch.
	 */
	/**
	 * Calls the servant up, but only while the bot is standing still.
	 * <p>
	 * A summon has a cast time and a cast roots the caster, so one started mid-stride is cancelled by the next step. The decision tick comes round
	 * every second and the servant is the first thing it tries, so a spirit master that was walking anywhere started the cast, walked out of it, and
	 * started it again -- seen in game as a caster running across a field conjuring nothing, over and over.
	 * <p>
	 * It waits for a pause rather than stopping the bot where it stands, because a bot halted to cast is a bot that never gets anywhere if the cast
	 * cannot succeed for some other reason -- no mana, a cooldown, a skill it does not have yet. Pauses are not rare: every occupation ends in one.
	 *
	 * @return true if the bot is busy with the cast.
	 */
	private boolean callUpServant() {
		if (getOwner().getSummon() == null && getOwner().getMoveController() instanceof BotMoveController moveController
			&& moveController.isInMove())
			return false;
		return BotSkillManager.trySummonServant(getOwner());
	}

	/**
	 * Gets an aethertech into its robot, which is the one upkeep its whole class is built on — see {@code BotSkillManager.tryBoardRobot}.
	 * <p>
	 * Standing still for the same reason the servant is called that way: Embark states {@code move_casting allow="false"}, so one started mid-stride
	 * is thrown away. It is a toggle with no duration, so this fires once and the bot stays aboard; only the first fight after a spawn can begin
	 * without it.
	 */
	private boolean boardRobot() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isInMove())
			return false;
		return BotSkillManager.tryBoardRobot(getOwner());
	}

	/**
	 * Leaves a fight the bot is losing, and walks home.
	 * <p>
	 * Two ways to be losing, and they are judged differently on purpose:
	 * <ul>
	 * <li><b>Outmatched.</b> Something more than {@code MAX_LEVEL_GAP} above the bot is hitting it. That is the same figure which already decides
	 * which fights a bot may <i>start</i>, and the hole this closes is that the figure was never applied to the fights that start themselves — a
	 * level 12 bot walking within ten metres of a level 19 monster was attacked, and fought to the death with no rule anywhere to say otherwise. A
	 * fight not worth starting is not worth finishing, so this one goes at once, at whatever health.
	 * <li><b>Outnumbered.</b> More than one thing hating the bot at the same time. Winnable often enough to be worth trying, so it is left until the
	 * health is going. This is where density belongs: not as a property of ground, where it measured at one extra monster in the median, but as a
	 * fact about the fight actually happening.
	 * </ul>
	 * Leaving works because monsters leash: the bot does not have to outrun anything, only to stop being there. Whatever drove it off is left alone
	 * afterwards, or {@link #findAttacker} would pick it straight back up and the retreat would last one tick.
	 * <p>
	 * Never in a group. A member that runs is a hole in the party, which is the same reason {@link #mustHoldTheLine} keeps one from sitting down.
	 *
	 * @return true if the bot is leaving, in which case nothing else is decided this tick.
	 */
	private boolean retreatIfLosing() {
		Player bot = getOwner();
		if (bot.isDead() || !bot.isSpawned() || isRecoveringFromDeath())
			return false;
		// Ignored ones do not count: they are what the bot has already decided to leave, and counting them again would re-decide the same retreat on
		// every tick -- seen in the log as one bot breaking off from the same two attackers four times in as many seconds. Once everything on it is
		// ignored the chain below walks it home, which is the retreat continuing rather than a new one.
		List<Creature> attackers = findAttackers().stream().filter(npc -> !isIgnored(npc)).toList();
		if (attackers.isEmpty())
			return false;
		boolean outmatched = attackers.stream().anyMatch(npc -> npc.getLevel() > bot.getLevel() + BotTargetSelector.MAX_LEVEL_GAP);
		boolean outnumbered = attackers.size() > 1 && bot.getLifeStats().getHpPercentage() < RETREAT_HP_PERCENT;
		if (!outmatched && !outnumbered)
			return false;
		long until = System.currentTimeMillis() + RETREAT_AVOIDED_MILLIS;
		for (Creature attacker : attackers)
			ignoredTargets.put(attacker.getObjectId(), until);
		log.info("Bot {} breaks off from {} attacker(s) at {}% health and heads home", bot.getName(), attackers.size(),
			bot.getLifeStats().getHpPercentage());
		stopAttacking();
		returnToAnchor();
		return true;
	}

	/** @return Everything that has decided to kill this bot, which is how both halves of a lost fight are counted. */
	private List<Creature> findAttackers() {
		Player bot = getOwner();
		List<Creature> attackers = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.isDead() && !BotTargetSelector.isScenery(npc) && npc.getAggroList().isHating(bot))
				attackers.add(npc);
		});
		return attackers;
	}

	private Creature findAttacker() {
		Player bot = getOwner();
		Creature[] attacker = { null };
		bot.getKnownList().forEachNpc(npc -> {
			// The aggro list, not getTarget(). A creature that has decided to kill this bot is not necessarily swinging at it this instant: it is
			// closing in, or it is mid-cast, or it is briefly aimed at something else, and through all of that getTarget() says anything but the bot.
			// This project already learned it once, for a creature attacking a group member, and left the bot's own defence reading the wrong answer:
			// aggressive monsters walked up and hit a bot that stood there as though nothing were happening.
			// scenery is skipped here too, not only when picking a fight. A training dummy holds a grudge like anything else once it has been hit,
			// and a bot that answers it stands there swinging at furniture — which is precisely what one did, two minutes after resurrecting beside
			// one. Nothing that counts as scenery can hurt a bot, so there is nothing to defend against.
			// isIgnored as well, which it did not use to be: a bot that has just broken off is still hated by what it ran from, so without this the
			// very next tick turned it round and walked it back into the fight it had decided to leave.
			if (attacker[0] == null && !npc.isDead() && !BotTargetSelector.isScenery(npc) && npc.getAggroList().isHating(bot) && !isIgnored(npc))
				attacker[0] = npc;
		});
		return attacker[0];
	}

	private boolean isIgnored(Creature target) {
		Long until = ignoredTargets.get(target.getObjectId());
		if (until == null)
			return false;
		if (until > System.currentTimeMillis())
			return true;
		ignoredTargets.remove(target.getObjectId());
		return false;
	}

	@Override
	public boolean isDestinationReached() {
		// AITemplate returns false by default, which would prevent MoveTaskManager from ever firing MOVE_ARRIVED
		return !(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isArrived();
	}

	@Override
	protected void handleMoveArrived() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return;
		if (moveController.isFlying())
			return; // a flight leg that has been reached is the flight's business, and continueToGoal would hand the body to a walking route
		if (moveController.continueToGoal())
			return; // the goal is further away, another leg was started
		if (!moveController.isBlocked()) // being blocked is logged where it is detected
			log.info("Bot {} arrived at destination", getOwner().getName());
	}

	@Override
	protected void handleMoveValidate() {
		// the attack tick only runs at weapon speed, far too slow to notice the target came into reach while running at it
		if (chaseStartTime == 0 || !(getOwner().getTarget() instanceof Creature target) || !BotAttackManager.isInAttackRange(getOwner(), target))
			return;
		chaseStartTime = 0;
		stopMoving();
		// strike right away instead of standing next to the target until the scheduled tick comes round
		synchronized (combatLock) {
			if (attackTask != null) {
				stopAttackTask();
				scheduleAttackTick(0);
			}
		}
	}

	@Override
	protected void handleSpawned() {
		// without leaving AIState.CREATED, every event except (BEFORE_)SPAWNED is filtered out
		setStateIfNot(AIState.IDLE);
		setAnchor(getOwner().getX(), getOwner().getY(), getOwner().getZ());
		synchronized (combatLock) {
			scheduleBotTick();
		}
		log.info("Bot {} spawned", getOwner().getName());
	}

	@Override
	protected void handleAttack(Creature attacker) {
		if (autonomous && !isAttacking() && !attacker.equals(getOwner())) {
			log.info("Bot {} retaliates against {}", getOwner().getName(), attacker.getName());
			startAttacking(attacker);
		}
	}

	@Override
	protected void handleDied() {
		handleDeath();
	}

	/**
	 * Reacts to the bot being dead, at most once per death.
	 * <p>
	 * The engine only fires {@code AIEventType.DIED} from {@code NpcController}: {@code PlayerController.onDie} never notifies the AI, since real
	 * players have a dummy one. Rather than patch a core file upstream changes often, the decision tick notices the death itself.
	 */
	private void handleDeath() {
		synchronized (combatLock) {
			if (reviveTask != null) // already dealt with
				return;
			// nobody will ever click the resurrection window for a bot, so without this it lies dead forever
			reviveTask = BotScheduler.getInstance().schedule(this::revive, REVIVE_DELAY_MILLIS);
		}
		Player bot = getOwner();
		// whatever it was fighting just won, so leave it alone for a while instead of walking straight back into it
		if (bot.getTarget() instanceof Creature killer)
			ignoredTargets.put(killer.getObjectId(), System.currentTimeMillis() + KILLER_AVOIDED_MILLIS);
		cancelCombat();
		setStateIfNot(AIState.DIED);
		log.info("Bot {} died at {} {} {} (anchor {} {} {})", bot.getName(), bot.getX(), bot.getY(), bot.getZ(), anchorX, anchorY, anchorZ);
		day.recordDeath(); // a home that keeps killing its resident is a home the resident leaves, whatever the reason turns out to be
	}

	/**
	 * Brings the bot back the way a player does: at its obelisk, on a quarter of its health, with soul sickness.
	 * <p>
	 * {@code bindRevive} rather than a revive on the spot, and that is the whole of it. Coming back where it fell — or at its anchor, which is the
	 * patch of ground it was working — puts the bot back within reach of whatever just killed it, at a quarter health: it dies again, comes back
	 * again, and the loop only ends when something else wanders past. An obelisk is far away on purpose, and the walk home is the price a player
	 * pays too.
	 * <p>
	 * Its anchor is moved home as well, because the spot it was working is on the far side of that walk and it has no business resuming there from
	 * across the region.
	 */
	private void revive() {
		Player bot = getOwner();
		try {
			if (!bot.isSpawned() || !bot.isDead())
				return;
			PlayerReviveService.bindRevive(bot);
			Vector3f home = day.home();
			if (home != null)
				setAnchor(home.getX(), home.getY(), home.getZ());
			setStateIfNot(AIState.IDLE);
			log.info("Bot {} revived at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
		} finally {
			// cleared last, so the decision tick cannot mistake the bot for freshly dead while it is being brought back
			synchronized (combatLock) {
				reviveTask = null;
			}
		}
	}

	@Override
	protected void handleDespawned() {
		synchronized (combatLock) {
			if (thinkTask != null) {
				thinkTask.cancel(false);
				thinkTask = null;
			}
			if (reviveTask != null) {
				reviveTask.cancel(false);
				reviveTask = null;
			}
		}
		cancelCombat();
		setStateIfNot(AIState.DESPAWNED);
		log.info("Bot {} despawned", getOwner().getName());
	}
}
