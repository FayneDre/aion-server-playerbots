package com.aionemu.gameserver.playerbot.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AITemplate;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.BindPointPosition;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.BotRecruiter;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotLootManager;
import com.aionemu.gameserver.playerbot.combat.BotPotionManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetRegistry;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.combat.playbook.BotPlaybooks;
import com.aionemu.gameserver.playerbot.combat.playbook.ClassPlaybook;
import com.aionemu.gameserver.playerbot.economy.BotPowerShards;
import com.aionemu.gameserver.playerbot.economy.BotStores;
import com.aionemu.gameserver.playerbot.economy.BotTrades;
import com.aionemu.gameserver.playerbot.lifecycle.BotMapChange;
import com.aionemu.gameserver.playerbot.lifecycle.BotPacing;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotInstances;
import com.aionemu.gameserver.playerbot.social.BotRequests;
import com.aionemu.gameserver.playerbot.social.LeaderGlides;
import com.aionemu.gameserver.playerbot.social.BotSquads;
import com.aionemu.gameserver.playerbot.social.BotTransports;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.playerbot.positioning.BotPosition;
import com.aionemu.gameserver.playerbot.positioning.BotStations;
import com.aionemu.gameserver.playerbot.social.BotMarks;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.playerbot.world.BotLanding;
import com.aionemu.gameserver.playerbot.world.HarmfulGround;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
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
	/** How far behind its leader a grouped bot on foot has fallen before it reads a running scroll, in metres. */
	private static final float RUNNING_SCROLL_DISTANCE = 30;
	/** A chased target is only given a new route once it has moved this far, instead of on every tick. */
	private static final float RETARGET_STEP = 3f;
	/**
	 * Minimum delay between two routes towards the same chased target. Each new route makes clients restart their interpolation, so re-routing on
	 * every attack tick turns a run into a stutter.
	 */
	private static final long CHASE_REROUTE_INTERVAL = 600;
	/** How long a walk to a fight position is left alone before the place is worked out again. Longer than a chase: the place moves only when the enemy does. */
	private static final long STATION_REROUTE_MILLIS = 1500;
	/** Health of a group mate under which a healer heals before it walks to its place: somebody in danger is the job, and the walk can wait a cast. */
	private static final int HEALER_DANGER_PERCENT = 50;
	/** How often the log may say that a healer has no place to stand. */
	private static final long NO_PLACE_LOG_MILLIS = 30_000;
	/**
	 * How long the bot waits after getting up before it swings, so the two do not arrive as one frame of the client's work. How long each posture
	 * itself takes to draw is {@link BotPosture}'s business; this is the fight's own sequencing, which is why it stays here.
	 */
	private static final long DRAW_AFTER_STAND_MILLIS = 1500;
	/** How long the weapon stays drawn after a fight, so the bot does not sheathe it between two mobs of the same pull. */
	private static final long SHEATHE_DELAY_MILLIS = 8000;
	/**
	 * The same for a bot in a group, which goes from one pack to the next: putting the weapon away and drawing it again cost the tank its first
	 * seconds at every pack, reported by a tester as a templar dawdling between them. In an instance it is not put away at all.
	 */
	private static final long GROUP_SHEATHE_DELAY_MILLIS = 60_000;
	/** How long a target the bot could not reach is left alone, so it does not pick the same unreachable one again right away. */
	private static final long UNREACHABLE_MILLIS = 10000;
	/** How long a target given up on a second time, then a third and after, is left alone. */
	private static final long UNREACHABLE_AGAIN_MILLIS = 120000, UNREACHABLE_FOR_GOOD_MILLIS = 600000;
	/** Give-ups in a row, with no blow landed between them, after which the bot goes back to its camp instead of picking the next monster beside it. */
	private static final int GIVE_UPS_BEFORE_CAMP = 3;
	/** How many monsters' failures are remembered before the memory is started again: a monster respawns under a new id, so old ones only take room. */
	private static final int MAX_FAILED_CHASES_KEPT = 256;
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
	/** How many times a bot walks off ground that hurts it before it rests there anyway: a mesh's lava is only known by standing in it. */
	private static final int HARMFUL_GROUND_TRIES = 3;
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
	/** The penalty skill a player carries after dying, under which a bot will not start a fight. */
	private static final int SOUL_SICKNESS_SKILL = 8291;
	/** How little life a target needs for the bot to stop looking after itself and simply end the fight. */
	private static final int FINISH_IT_PERCENT = 20;
	/** Close enough to the anchor to count as home, so the bot does not fidget over a metre. */
	private static final float ANCHOR_TOLERANCE = 5f;
	/** How far from its group a bot back from the obelisk may be and still join it before resting: near enough for the group to cover the walk. */
	private static final float CATCH_UP_UNRESTED = 40f;
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
	/** How far in height from where its leader left the ground a member may stand and still be up there with it, ready to glide the same way. */
	private static final float GLIDE_SAME_LEVEL = 3f;
	/** How near where its leader left the ground a member must come before it glides: the leader's edge, give or take a stride. */
	private static final float TAKEOFF_REACHED = 2f;
	/** Roughly the time a player spends looking at the resurrection window. */
	private static final int REVIVE_DELAY_MILLIS = 10000;
	/**
	 * How long a bot dead in a group still waits for a resurrection once the group has stopped fighting. A cleric's or a chanter's raise takes some
	 * seconds to cast and is cast after the fight, and a player has to walk over first: ten seconds was never enough, and a tester could not raise a
	 * single bot on the spot.
	 */
	private static final long RESURRECTION_WAIT_MILLIS = 60_000;
	/** Longest a bot dead in a group waits, whatever the group does, so a mate who stays alive and never raises it does not keep it down for good. */
	private static final long LONGEST_RESURRECTION_WAIT_MILLIS = 600_000;
	/** How often a bot waiting for a resurrection looks again at whether its group is still fighting. */
	private static final long RESURRECTION_LOOK_MILLIS = 5_000;
	/** How long a player takes to click "accept" once a resurrection has been cast on them. */
	private static final int ACCEPT_RESURRECTION_DELAY_MILLIS = 2000;
	/** How far in height a revived bot's home may be from the obelisk and still be walked or flown back to: Reshanta's layers are 1200 m apart. */
	private static final float ANOTHER_LAYER = 300;
	/** How often a bot revived on another layer looks at whether it is well enough to be taken home, and how many times before it goes anyway. */
	private static final long TAKEN_HOME_DELAY_MILLIS = 5000;
	private static final int TAKEN_HOME_LOOKS = 120;
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
	/** A home handed over by {@link #moveHouse}, not yet taken up. */
	private volatile Vector3f newHome;
	/** When the bot last actually did something to its target -- a swing sent or a skill cast, not a tick that decided to do neither. */
	private volatile long lastBlow;
	/**
	 * When the bot was last seen stunned, knocked about, spun or held in the air. The engine says whether a bot is controlled now and never when it
	 * last was, which is what a class that answers it afterwards needs; a playbook is shared by every bot of its class, so the memory is kept here.
	 */
	private volatile long lastControlledAt;
	/**
	 * Whether the bot has begun healing itself back to full after a critical moment and is to keep at it. A rule that says "until full" has to remember that
	 * it began, since the health it began at is not the health it is at a cast later; kept here for the same reason as {@link #lastControlledAt}.
	 */
	private volatile boolean mendingToFull;
	/** What the members of the group have lost over the last half minute, for the class that rations its heals by it. Touched by the decision tick only. */
	private final HealthRecord healthRecord = new HealthRecord();
	/**
	 * Since when a grouped ranger has held its fire because its target turned on it, or 0, and until when it will not start again. A playbook is shared by
	 * every bot of its class, so what one bot has been doing is kept here, as for {@link #lastControlledAt}.
	 */
	private volatile long holdingFireSince;
	private volatile long holdFireRearmAt;
	/** The enemy the bot has already spent its opening handicap on, so that the opener is once per enemy and not once per tick. */
	private volatile int openedOn;
	private ScheduledFuture<?> thinkTask;
	/** Non null between death and resurrection, which also marks the death as already handled. */
	private ScheduledFuture<?> reviveTask;
	/** Whether the pending revival is the acceptance of a resurrection somebody cast, rather than the walk back from the obelisk. */
	private boolean resurrectionPending;
	/** When the bot last died, and when its group was last seen fighting since, for how long it waits to be raised. */
	private long diedAt, groupLastFoughtAt;
	/** The leader's last glide while it is fresh, seen on the last tick, and the one this bot has already gone down or given up on. */
	private volatile LeaderGlides.Glide leadersGlide, copiedGlide;
	/** Whether the last tick found the bot alive and out of the world, the state a teleport to another map leaves it in, see {@link #arriveIfStranded()}. */
	private boolean seenOutOfWorld;
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
	/** When the bot last set out for its place in a fight, so that a route under way is not replaced on every tick. */
	private volatile long lastStationRoute;
	/** When the log last said that the healer had no place to stand. */
	private volatile long lastNoPlaceLog;
	/**
	 * Whether this character is somebody's own rather than one the world made to fill a region. Read once: it is a fact about the character and does
	 * not change while it is in the world.
	 * <p>
	 * An owned bot is its owner's, and the population model keeps its hands off it — it is not paced, not capped, not rehoused and not counted.
	 */
	private final boolean owned;
	/** The object id of the player who made this bot with {@code //bot create}, 0 for one spawned from its owner's own account or for an inhabitant. */
	private final int ownerId;
	/** How fast the bot is allowed to advance, and how far, which depends on whether it is anybody's companion at this moment. */
	private final BotPacing pacing;
	/** What the bot's body is doing, and the time the client needs to draw a change of it. */
	private final BotPosture posture;
	/** What a resident does with its day. Adventurers pass straight through it and hunt. */
	private final BotDay day;
	/** The chores between fights: the corpse, the bag, the shop. */
	private final BotErrands errands;
	private final BotConvalescence convalescence;
	/** Set when the bot has just been put down by the engine, so the next tick asks whether it can walk away from there. */
	private volatile boolean checkLanding;
	/** When the last fight ended, 0 once the weapon has been put away. */
	private volatile long combatEndedAt;
	/** How many times running the bot has walked off ground that hurts it to rest, 0 once it rests on ground that does not. */
	private int harmfulGroundTries;
	/** Targets not to pick for a while: either out of reach, or having just killed the bot. */
	private final Map<Integer, Long> ignoredTargets = new ConcurrentHashMap<>();
	/** How many times each monster was given up on as out of reach, so the next time it is left alone for longer. */
	private final Map<Integer, Integer> failedChases = new ConcurrentHashMap<>();
	/** Give-ups since the bot last landed a blow. */
	private volatile int giveUpsInARow;
	/** How many refusals in a row mean the bot is not blocked from somewhere but wedged where it stands. */
	private static final int STUCK_REFUSALS = 12;
	/**
	 * Until when a bot brought in at the server's start waits before its day begins: shop trips, hunting grounds, errands. Every bot of the world
	 * spawned in the same few seconds and set off in the same few seconds, and the first eight minutes after a restart held three times the refused
	 * routes and slow searches of any eight minutes after (23 October). It still defends itself, loots and mends meanwhile.
	 */
	private volatile long settlesAt;
	/** How long after the server's start a spawn counts as part of the start, in seconds: the world is brought in at the end of a two minute start. */
	private static final int STARTUP_SECONDS = 360;
	/** How long a bot brought in at the start may wait before its day begins, at most, in seconds. */
	private static final int SETTLING_IN_SECONDS = 240;
	/** Refusals in a row, reset by any movement that starts. */
	private int refusedMoves;
	/**
	 * How many destinations in a row a bot may give up on from one spot before the spot is the fault rather than the destinations.
	 * <p>
	 * A count of its own, beside {@link #refusedMoves}, because that one never reaches its bound for a bot that is refused everything: a route is
	 * searched on another thread, the move is "on its way" until the search fails, and that answer resets the count. A gladiator walked onto a ledge
	 * under Pandaemonium's temple dome, 27 m above the floor, and gave up on every destination it drew for two days without being rescued once.
	 */
	private static final int ISOLATED_REFUSALS = 6;
	/** How far a bot may have moved between two give-ups for both to count as made from the same spot, in metres. */
	private static final float SAME_SPOT = 3;
	/** Where the give-ups being counted were made from, null when none is. */
	private Vector3f refusedFrom;
	/** Destinations given up on in a row from {@link #refusedFrom}. */
	private int refusedFromThere;
	/** The destinations given up for want of budget lately, which tell a bot that is lost. */
	private final LostTrail lostTrail = new LostTrail();
	/** The destination of the last move refused for searches out of budget, which the caller reports afterwards through {@link #destinationRefused}. */
	private Vector3f unprovenGoal;
	/**
	 * Whether this fight's opening has been tried. One attempt per fight and no more: an opening burst that will not go off now is on cooldown, which
	 * is itself the answer, and retrying it every tick would spend it halfway through the fight where most of it is wasted.
	 */
	private volatile boolean openingSpent;
	/** Whether this fight's approach has been tried. Separate from the opening, so hiding does not cost the bot its burst as well. */
	private volatile boolean approachSpent;
	/** Whether this fight has been opened, from afar or in reach. Past that a melee class walks in; see {@link #closesInRatherThanCasting}. */
	private volatile boolean pulledFromAfar;
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
		this.ownerId = owned ? BotRoster.ownerOf(owner.getName()) : 0;
		this.pacing = new BotPacing(owner, owned);
		this.pacing.reconcile(); // before the first tick, so a bot cannot earn a second's experience at the wrong rate on its way in
		this.posture = new BotPosture(owner);
		this.day = new BotDay(this);
		this.errands = new BotErrands(this);
		this.convalescence = new BotConvalescence(owner, day::isCrowded);
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
	 * @return true if the player is this bot's owner: the one who made it with {@code //bot create}, or any character of the account it was made on,
	 *         for a character of a real account spawned as a bot. An inhabitant's account is a reserved one nobody plays on, so it has no owner here.
	 */
	public boolean isOwnedBy(Player player) {
		if (!owned || player == null || player.equals(getOwner()))
			return false;
		if (ownerId != 0)
			return player.getObjectId() == ownerId;
		return player.getAccount() != null && getOwner().getAccount() != null && player.getAccount().getId() == getOwner().getAccount().getId();
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

	/** Makes this the bot's home, in the roster as well, and its anchor: for a bot brought back to where it lives after being somewhere else. */
	public void takeUpHome(Vector3f home) {
		day.moveTo(home);
	}

	/**
	 * Hands the bot a new home from another thread, the director's: taken up at the start of its next tick, on its own, since everything the day holds
	 * is read and written there.
	 */
	public void moveHouse(Vector3f home) {
		newHome = home;
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
		Player duelled = BotRequests.answerPending(getOwner(), new BotRequests.Self(owned, this::isOwnedBy, isAttacking()));
		if (duelled != null)
			startAttacking(duelled); // a duel accepted and then waited out reads as a bot that did not understand the question
		if (owned)
			BotTrades.tick(getOwner(), isAttacking());
		BotGroupManager.leaveIfLeaderless(getOwner());
		Player leader = BotGroupManager.leaderToFollow(getOwner());
		if (leader == null)
			return false;
		leadersGlide = LeaderGlides.observe(leader); // every tick, fight or not: a glide is seen while it happens or not at all
		// a leader on a flight transporter is not walked after: the anchor stays where the flight left from, and BotTransports brings the bot on arrival
		if (BotTransports.isLeaderInTransit(getOwner()))
			return true;
		// the bot's own place beside the leader, not the leader's feet: everything downstream already works off the anchor, so this is all "stand
		// with the group" needs to be
		Vector3f spot = BotGroupManager.formationSpot(getOwner(), leader);
		setAnchor(spot.getX(), spot.getY(), spot.getZ());
		// left behind on foot: a player reads a running scroll, and a bot that has one does too
		if (!leader.isFlying() && !BotFlight.isInTheAir(getOwner()) && PositionUtil.getDistance(getOwner(), leader) > RUNNING_SCROLL_DISTANCE)
			BotPotionManager.tryScroll(getOwner(), StatEnum.SPEED);
		return true;
	}

	/**
	 * Puts up one of the bot's own buffs or mantras, its class's first ({@code ClassPlaybook.prepare}) and then any it is missing: the upkeep that opens a
	 * fight.
	 * <p>
	 * Only when a fight is coming. It was done on every idle tick, so a bot walking round a village or to a shop cast a buff of a minute every minute for
	 * nobody, which is not something anybody playing does and was reported as strange. Buffs a class casts in the middle of a fight are its playbook's
	 * and are not this, nor is its care between fights ({@code tend}), which still runs on every tick.
	 *
	 * @return true if one was cast, in which case the bot is busy with it.
	 */
	private boolean buffForTheFight() {
		return BotPlaybooks.of(getOwner()).prepare(getOwner()) || BotSkillManager.tryBuffSelf(getOwner()) || BotSkillManager.tryChantMantra(getOwner());
	}

	/**
	 * Reads the scroll that speeds up how the class deals its damage, swinging for the classes that fight with a weapon and casting for the rest. In a
	 * group only, where a player keeps them up: alone, against whatever the field holds, it would spend fifty scrolls on rabbits.
	 *
	 * @return true if one was read.
	 */
	private boolean readAFightScroll() {
		return BotPotionManager.tryScroll(getOwner(), getOwner().getPlayerClass().isPhysicalClass() ? StatEnum.ATTACK_SPEED : StatEnum.BOOST_CASTING_TIME);
	}

	/** @return true if the bot's group has an enemy to fight, or its leader has one in its sights: the moment a member puts its buffs up. */
	private boolean isAFightComing() {
		if (BotGroupManager.targetToAssist(getOwner(), this::isIgnored) != null)
			return true;
		Player leader = BotGroupManager.leaderToFollow(getOwner());
		return leader != null && leader.getTarget() instanceof Creature target && !target.isDead() && getOwner().isEnemy(target);
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
		// the class's care between fights on every tick (raising a mate, a cleanse), its buffs only with a fight in sight
		if (BotPlaybooks.of(getOwner()).tend(getOwner()) || isAFightComing() && (buffForTheFight() || readAFightScroll()))
			return;
		// A healer takes its place before the casting that is not urgent: with a mate to heal or a buff to give on every tick, which is most of a fight, the walk was never
		// reached and the healer stood wherever the pull had left it. A mate in danger is healed first.
		if (healerTakesItsPlace())
			return;
		if (tendToTheGroup())
			return;
		// A healer in a group does not join the attack, and that is the rule rather than a limitation. Assisting means closing to weapon range, and a
		// cleric that walks into the mob is a cleric being hit, out of position and silent for the rest of the fight. Standing with the group and
		// keeping it alive is the whole of the job. It still defends itself: {@link #findAttacker} is checked ahead of all of this, so something that
		// comes for the healer is still answered.
		// The tank chooses what the group fights, so it marks before it looks for something to assist: with the mark up it assists its own choice
		if (BotRole.of(getOwner()) == BotRole.TANK)
			BotMarks.markWeakestIfNone(getOwner());
		boolean healer = BotRole.of(getOwner()) == BotRole.HEALER;
		Creature assisted = healer ? null : BotGroupManager.targetToAssist(getOwner(), this::isIgnored);
		if (assisted == null) {
			if (healer && tendTheFight())
				return;
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
		// the class that rations its heals does it itself, and the generic heal below is then not asked
		if (BotPlaybooks.of(getOwner()).heal(getOwner()))
			return true;
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
	 *         <p>
	 *         Except for a bot just back from the obelisk and a long way from its group: it rests first. It used to set off at a quarter of its health
	 *         with the death penalty, walk through the mobs between the obelisk and the group alone, and die on the way, which is what a player saw on
	 *         20 October: revived at 12:55:51, dead again 63 s later and halfway there.
	 */
	private boolean mustCatchUp(boolean following) {
		if (!following)
			return false;
		double behind = PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY);
		return behind > ANCHOR_TOLERANCE && (behind <= CATCH_UP_UNRESTED || !isRecoveringFromDeath());
	}

	/** Decision loop, kept separate from the framework's {@code think()} so nothing in the engine can trigger it unexpectedly. */
	private void botTick() {
		noteCrowdControl();
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
			if (bot.getMoveController() instanceof BotMoveController moveController && (moveController.isTravelling() || moveController.isRiding()))
				return false;
			// And never in the air. Despawning takes the body out of the world mid flight -- the flight notices, but a player watching sees a bot rise
			// and vanish, and the next review puts it back on the ground somewhere else. A journey is already refused here; a flight is a journey
			// whose ending matters more, since nothing brings a body down but the flight itself.
			if (BotFlight.isInTheAir(bot))
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
		if (arriveIfStranded())
			return;
		if (BotFlight.isInTheAir(getOwner())) {
			// Nothing else is decided for a body in the air, and nothing below this line runs either: rehousing one would teleport it. The one exception
			// is the group's fight, which a bot following its player through the air used to watch hovering beside them.
			fightFromTheAir();
			return;
		}
		Vector3f moving = newHome;
		if (moving != null) {
			newHome = null;
			takeUpHome(moving);
		}
		if (moves() != null && moves().isGliding() && !getOwner().isDead())
			return; // the same for a live body on a glide: a fight picked up on the way down would be walked to from the air. A dead one ends its glide below
		if (moves() != null && moves().isRiding() && !getOwner().isDead())
			return; // and on a lift or a boat, which puts the body down at the other end by itself
		sheathWhenCalm();
		abandonStalledFight();
		// A bot crosses its level, and joins and leaves a group, while it is playing rather than while it is loading, so both are settled here rather
		// than on the way in. Neither does anything until the answer actually changes.
		pacing.reconcile();
		// a recruit with no group goes home before anything else reads where it stands, the instance rule below included
		if (BotRecruiter.sendHomeIfReleased(getOwner()) || BotInstances.followThePlayer(getOwner()) || BotTransports.followThePlayer(getOwner())) {
			// the fight, the walk and the errand were all chosen on the map it has just left
			cancelCombat();
			day.forget();
			return;
		}
		boolean inSquad = BotSquads.isSquad(getOwner().getCurrentTeam());
		if (!inSquad) // a squad camps where it was formed, which is the point of forming it there
			day.moveOutIfOutgrown();
		boolean following = followTheGroup();
		if (getOwner().hasStore()) {
			// A keeper at its stall does nothing else: the engine refuses skills and items to a store's owner, and a body walked with its store open
			// drags its shelf through the town. Its questions are answered above, and a group it joins is followed only once the store is shut.
			if (!following && day.keepsStore()) {
				BotStores.renewIfDue(getOwner());
				return;
			}
			BotStores.close(getOwner());
		}
		if (checkLanding && getOwner().isSpawned() && !getOwner().isDead()) {
			checkLanding = false;
			stepOffTrappedGround();
		}
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
		} else if (following && followIntoTheAir()) {
			// up after the leader, and nothing else this tick: everything below is about ground the bot is no longer standing on
		} else if (autonomous && !following && retreatIfLosing()) {
			// Running from a fight it cannot win, which is the one thing a bot never did: it defended itself against anything, at any level, in any
			// number, until it died -- then resurrected, walked back to the home it still had beside the thing that killed it, and did it again.
			// Measured at the Tursin Outpost on Verteron, where survival at five minutes was nil.
		} else if (autonomous && !isAttacking() && getOwner().isSpawned()) {
			Creature attacker = findAttacker();
			if (attacker != null && following && healsBeforeDefending()) {
				// a mate in danger comes before the healer's own attacker: a cleric swinging at an add while the tank died was seen on 20 October
			} else if (attacker != null)
				startAttacking(attacker); // no health check: a bot being hit defends itself, it does not sit down
			else if (errands.collectLoot()) {
				// busy with a corpse, everything else can wait
			} else if (errands.dressUp()) {
				// dressing itself, which is five seconds of stillness the bot has to commit to rather than hope for
			} else if (!isHealthyEnoughToFight() && !mustCatchUp(following) && !mustHoldTheLine(following)) {
				// not the farm ground: that is where it died, and the penalty makes it die again. Except in a group, whose ground it is: the quiet spot is
				// chosen round the bot's home, which is nowhere near the group, so a member sent there would leave the people it is meant to be with
				if (isRecoveringFromDeath() && !following)
					convalesce();
				else if (!walkOffTheObelisk() && !healerTakesItsPlace()) // never sit down where it resurrected: that spot is shared by everyone who died in the region
					recover();
			} else if (following) {
				serveTheGroup(); // a member has no business picking its own fights, so the rest of this chain is not its to run
			} else if (BotSquads.leads(getOwner()) && !BotSquads.isReady(getOwner(), MIN_ENGAGE_HP_PERCENT, MIN_ENGAGE_MP_PERCENT)) {
				// A squad's leader pulls for the squad, so it waits for the squad, as a player leading a group waits for the healer's mana. Buffs and the
				// rest of its upkeep can wait with it; standing still is what lets the others catch up and sit down.
				stopMoving();
			} else if (System.currentTimeMillis() < settlesAt) {
				// still settling in after the server's start: see settlesAt
			} else if (!inSquad && errands.runVendorTrip()) {
				// bag is full and a shop is being walked to or worked, everything else waits
			} else if (isRunningErrand()) {
				// an operator sent it somewhere on purpose; let it arrive before it goes looking for its own fights
			} else if (day.sitsOutTheSpell()) {
				// a town resident sitting out its spell on the steps: the line below would get it to its feet every tick only to sit it down again
			} else if (!posture.standUp()) { // stand up one tick before acting, so the animation has played out by then
				// the servant and the robot are kept up ahead of anything: the class cannot fight without them, and they last. Then the class's care
				// between fights, which is mending and cleansing; its buffs wait for the fight, below
				if (!callUpServant() && !boardRobot() && !BotPlaybooks.of(getOwner()).tend(getOwner())
					&& (inSquad || isRecoveringFromDeath() || !day.pursue())) {
					// Starting a fight is the one thing the death penalty forbids, and it used to forbid everything: the bot sat where it stood for
					// as long as the sickness lasted, which on an obelisk is where every corpse in the region comes back. Buffing and the errands
					// above are things a player does on the way back from a death, so they are not gated here. The day's plan is: it was asked
					// first, so a sick bot set off for its hunting ground, the creatures there that attack on sight came for it, and it fought them
					// at a fraction of its strength, which is what was seen in Verteron on 18 October. It waits out the sickness first.
					if (isRecoveringFromDeath())
						convalesce(); // roaming would walk it to the nearest mob, and sitting on the obelisk is what filled them
					else {
						convalescence.forget(); // the sickness is over, so the claim on the spot is too
						// the camp's leash as well as what was given up: the nearest monster to a bot walking home from an obelisk is outside its camp, chase()
						// refuses it in the same millisecond, and one bot went round three of them 1345 times in forty minutes, logged as "cannot reach"
						Creature target = BotTargetSelector.findTarget(getOwner(), npc -> isIgnored(npc) || isOffLeash(npc));
						if (target == null)
							roam();
						else if (BotTargetRegistry.claim(target, getOwner())) { // another bot may have picked it in the same tick
							// the buffs now, with the fight chosen and before the first blow, one a tick: that is when a player puts them up
							if (buffForTheFight())
								stopMoving();
							else
								startAttacking(target);
						}
					}
				}
			}
		}
	}

	/**
	 * Spawns a bot that a teleport to another map has left out of the world, whoever teleported it.
	 * <p>
	 * {@link BotMapChange#arrive} is called after every teleport this module makes, but the engine makes its own: an instance that closes puts
	 * everyone inside at its exit, and an obelisk can be on another map. Each of those left a bot waiting for a client that does not exist, for good.
	 * A teleport on the same map takes the body out and puts it back within one call, so it is looked at on two ticks in a row: a bot is never out of
	 * the world for a second unless it is stranded.
	 *
	 * @return true if the bot was out of the world, in which case nothing else is decided this tick.
	 */
	private boolean arriveIfStranded() {
		Player bot = getOwner();
		if (bot.isSpawned() || bot.isDead()) {
			seenOutOfWorld = false;
			return false;
		}
		if (seenOutOfWorld) {
			seenOutOfWorld = false;
			log.info("Bot {} was left out of the world by a teleport to map {}", bot.getName(), bot.getWorldId());
			BotMapChange.arrive(bot);
		} else {
			seenOutOfWorld = true;
		}
		return true;
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
		pulledFromAfar = false;
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
		if (!isOwned()) // a companion's bag is its owner's, shards included
			BotPowerShards.switchOn(getOwner());
		log.info("Bot {} starts attacking {}", getOwner().getName(), target.getName());
	}

	public void stopAttacking() {
		cancelCombat();
		setStateIfNot(AIState.IDLE);
	}

	private void cancelCombat() {
		BotTargetRegistry.releaseAllOf(getOwner());
		BotFlight.engage(getOwner(), null); // back beside its leader, or down, for a bot that was fighting in the air
		synchronized (combatLock) {
			stopAttackTask();
			chaseStartTime = 0;
			stopMoving();
			// the weapon stays out for a moment: sheathing between two mobs of the same pull looks like a nervous tic, and a real player does not do it
			combatEndedAt = System.currentTimeMillis();
			getOwner().setTarget(null);
		}
		BotPowerShards.switchOff(getOwner());
	}

	/** @return When the bot was last seen crowd controlled, in milliseconds, or 0 if it has not been. */
	public long lastControlledAt() {
		return lastControlledAt;
	}

	/** @return The record of how much health each member of the group has lost lately, which a healer fills as it reads the group and asks about. */
	public HealthRecord healthRecord() {
		return healthRecord;
	}

	/** @return true if the bot is in the middle of healing itself back to full. See {@link #mendingToFull}. */
	public boolean isMendingToFull() {
		return mendingToFull;
	}

	public void setMendingToFull(boolean mendingToFull) {
		this.mendingToFull = mendingToFull;
	}

	/** @return Since when the bot has been holding its fire, in milliseconds, or 0 when it is not. */
	public long holdingFireSince() {
		return holdingFireSince;
	}

	/** @return The time before which it will not begin to hold its fire again. */
	public long holdFireRearmAt() {
		return holdFireRearmAt;
	}

	public void setHoldingFire(long since, long rearmAt) {
		this.holdingFireSince = since;
		this.holdFireRearmAt = rearmAt;
	}

	/** @return The object id of the enemy the opening handicap was cast on, or 0. */
	public int openedOn() {
		return openedOn;
	}

	public void setOpenedOn(int objectId) {
		this.openedOn = objectId;
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
		noteCrowdControl();
		Creature target = bot.getTarget() instanceof Creature creature ? creature : null;
		if (!BotAttackManager.canKeepFighting(bot, target)) {
			if (target != null && target.isDead() && BotLootManager.hasLootFor(bot, target.getObjectId()))
				errands.rememberCorpse(target.getObjectId()); // the decision tick walks over and picks it up
			log.info("Bot {} stops attacking", bot.getName());
			stopAttacking();
			return;
		}
		if (followTheMark(bot, target))
			return;
		if (healerHasNoSurplus(bot, target)) {
			stopAttacking(); // back to the decision tick, which heals, buffs and keeps its place
			return;
		}
		BotStations.sample(bot, target);

		// idempotent, and deferred to here so it never overlaps the stand up animation. Drawing is an animation in its own right, and the tick below
		// used to walk the bot off in the same instant it was sent: the slide the bot showed between getting up and setting off was those two frames
		// of the client's work arriving as one.
		posture.drawWeapon(target);
		if (BotFlight.isInTheAir(bot)) {
			fightInTheAir(bot, target); // no route, no station and no chase up there: the flight is what moves the body
			return;
		}
		if (bot.getCastingSkill() != null) {
			// casting roots a real player, so the bot neither moves nor starts another action until the cast is over
		} else if (takeStation(target)) {
			// walking to the place its class fights from, which is behind the enemy for melee and back from it for the rest; nothing is swung on the way
		} else if (comesIntoSight(target)) {
			// in reach and out of sight, behind a stair or a corner: every cast would be refused, so it walks round until it sees the target
		} else if (BotAttackManager.isInAttackRange(bot, target)) {
			chaseStartTime = 0;
			pulledFromAfar = true; // the fight is opened in reach, so a target that steps back is followed, not shot at
			stopMoving();
			// a class that never swings (a sorcerer's "Always Be Casting") waits for a spell rather than reaching for its orb
			if (useBestSkill(target) || BotPlaybooks.of(bot).swings(bot) && BotAttackManager.autoAttack(bot, target)) {
				lastBlow = System.currentTimeMillis();
				giveUpsInARow = 0;
			}
		} else if (target instanceof Player flier && flier.isFlying() && takeOffAfter(bot, flier)) {
			// an enemy player who took to the air: every walking rule below would give the fight up, since the air has no floor to route to
		} else if (closesInRatherThanCasting(target)) {
			// a melee class past its pull walks in: its weapon and its chains are where its damage is
		} else if (castFromAfar(target)) {
			pulledFromAfar = true;
			lastBlow = System.currentTimeMillis();
			giveUpsInARow = 0;
			// something in the bot's book reaches where its weapon does not, so there is nothing to close
		} else if (holdsStation(target)) {
			// a ranged class in its place with nothing in reach to cast. Walking up to the enemy would be the chase this whole rule exists to replace, and
			// the next tick would walk it straight back.
		} else if (BotRole.of(bot) == BotRole.HEALER && bot.getCurrentTeam() != null) {
			// Out of reach of anything it knows, and a healer does not walk closer: holding position beside the group is the whole of the job, and
			// the surplus damage it was spending is not worth leaving it for. It waits instead; the group's own fight brings the target into range,
			// and where it never does the stalled fight watchdog lets this one go.
		} else if (!chase(target)) {
			// told apart, because the two read the same in the log and are not the same fault: the second is the mesh's, the first is not
			if (isOffLeash(target)) {
				log.info("Bot {} leaves {} alone: it is beyond its camp", bot.getName(), target.getName());
				ignoredTargets.put(target.getObjectId(), System.currentTimeMillis() + UNREACHABLE_MILLIS);
				stopAttacking();
				return;
			}
			log.info("Bot {} cannot reach {} and gives up", bot.getName(), target.getName());
			// the same monster given up on again is left alone for longer each time: ten seconds was shorter than a round of three of them
			if (failedChases.size() > MAX_FAILED_CHASES_KEPT)
				failedChases.clear();
			int failures = failedChases.merge(target.getObjectId(), 1, Integer::sum);
			ignoredTargets.put(target.getObjectId(), System.currentTimeMillis() + unreachableFor(failures));
			stopAttacking();
			if (++giveUpsInARow >= GIVE_UPS_BEFORE_CAMP) {
				// three in a row without landing a blow: the ground here is the problem, not the monster, and the camp is where its ground is good
				giveUpsInARow = 0;
				log.info("Bot {} gave up on {} targets in a row and goes back to its camp", bot.getName(), GIVE_UPS_BEFORE_CAMP);
				returnToAnchor();
			}
			return;
		}

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
	private boolean takeStation(Creature target) {
		Player bot = getOwner();
		boolean healer = BotStations.handlesHealer(bot);
		if (!PlayerBotConfig.POSITIONING.placesAgainst(target) || !(BotStations.handles(bot) || healer) || BotStations.isWellPlaced(bot, target) || BotStations.isHeldByEnemy(bot, target))
			return false;
		// a healer that is attacking with its spare mana still heals first when somebody is in danger, and a walk would take the tick the heal needs
		if (healer && BotGroupManager.mostHurtMember(bot, HEALER_DANGER_PERCENT, true) != null)
			return false;
		if (!(bot.getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (moveController.isInMove() && now - lastStationRoute < STATION_REROUTE_MILLIS)
			return true;
		Vector3f spot = BotStations.spotFor(bot, target);
		// Already standing on it and still not placed: the spot passed the line of sight as asked from the enemy, and the bot's own look disagrees. Walking
		// to where it stands would spend every tick here; comesIntoSight takes it on from this step.
		if (spot == null || PositionUtil.getDistance(bot.getX(), bot.getY(), spot.getX(), spot.getY()) <= SPOT_TOLERANCE)
			return false;
		lastStationRoute = now;
		moveController.avoid(BotStations.avoidanceFor(bot, target));
		return tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
	}

	/**
	 * What a healer does on the decision tick while the group is fighting, once there is nobody to heal: get into place, then spend a surplus of mana on the
	 * enemy, then stand where it is.
	 * <p>
	 * A healer takes no part in the fight loop unless it joins, so its place is worked out here, in place of walking back to its slot beside the leader.
	 * Standing where it should is the answer too: it holds, and does not drift to the formation and back. Placed, and with more than
	 * {@value BotSkillManager#HEALER_SPARE_MANA_PERCENT} % of its mana, it attacks what the group is attacking: the surplus is the damage a healer is
	 * allowed, and the fight loop stops it again the moment the mana is below that.
	 *
	 * @return true if the tick is spent. false when there is no fight, or nothing to do about one: the formation decides.
	 */
	private boolean tendTheFight() {
		Player bot = getOwner();
		Creature fight = BotGroupManager.targetToAssist(bot);
		if (fight == null)
			return false;
		boolean positioned = PlayerBotConfig.POSITIONING.placesAgainst(fight) && BotStations.handlesHealer(bot);
		if (positioned) {
			BotStations.sample(bot, fight);
			if (!BotStations.isWellPlaced(bot, fight) && walkToHealerStation(fight))
				return true;
		}
		if (BotSkillManager.hasManaToSpare(bot)) {
			BotTargetRegistry.forceClaim(fight, bot);
			startAttacking(fight);
			return true;
		}
		return positioned && BotStations.isWellPlaced(bot, fight);
	}

	/**
	 * Walks a healer to its place when the group is fighting, it is not there, and nobody is in danger.
	 * <p>
	 * It was only ever tried after the healer had found nothing to cast, which in a fight is almost never: a hurt mate or a missing buff came first on every tick, an attacking healer was
	 * never looked at by the decision tick at all, and a healer short of mana sat down where it stood, in the cone of whatever the tank held. Reported in play as a healer that did
	 * not feel concerned by positioning.
	 *
	 * @return true if the tick is spent walking, false when there is nothing to do about the healer's place.
	 */
	private boolean healerTakesItsPlace() {
		Player bot = getOwner();
		if (!BotStations.handlesHealer(bot))
			return false;
		Creature fight = BotGroupManager.targetToAssist(bot);
		if (fight == null || !PlayerBotConfig.POSITIONING.placesAgainst(fight) || BotStations.isWellPlaced(bot, fight) || BotGroupManager.mostHurtMember(bot, HEALER_DANGER_PERCENT, true) != null)
			return false;
		return walkToHealerStation(fight);
	}

	/** @return true while the healer is on its way to its place, or has just set out; false when there is no place to go or no way there. */
	private boolean walkToHealerStation(Creature fight) {
		Player bot = getOwner();
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
		return tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
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
	private boolean comesIntoSight(Creature target) {
		Player bot = getOwner();
		if (isGroupHealer(bot) || PositionUtil.getDistance(bot, target) > Math.max(BotSkillManager.reach(bot), bot.getGameStats().getAttackRange().getCurrent() / 1000f)
			|| GeoService.getInstance().canSee(bot, target) || !(bot.getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (moveController.isInMove() && now - lastStationRoute < STATION_REROUTE_MILLIS)
			return true;
		lastStationRoute = now;
		return tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	/** @return true if this is a ranged class standing where it should against the target, which then waits for something to cast rather than close in. */
	private boolean holdsStation(Creature target) {
		Player bot = getOwner();
		return PlayerBotConfig.POSITIONING.placesAgainst(target) && BotStations.handles(bot) && BotPosition.of(bot) == BotPosition.RANGED && BotStations.isWellPlaced(bot, target);
	}

	/**
	 * @return true if this is a healer in a group, fighting something that is not after it, with no mana to spare. Mana is what a healer is for: below the
	 *         surplus it goes back to healing. A monster that is hitting the healer itself is another matter, and is fought whatever the mana.
	 */
	private static boolean healerHasNoSurplus(Player bot, Creature target) {
		return isGroupHealer(bot) && !BotSkillManager.hasManaToSpare(bot) && !bot.equals(target.getTarget());
	}

	private static boolean isGroupHealer(Player bot) {
		return BotRole.of(bot) == BotRole.HEALER && bot.getCurrentTeam() != null;
	}

	/** @return true if this is a group's healer with a mate in danger and it has just cast something on them. */
	private boolean healsBeforeDefending() {
		return isGroupHealer(getOwner()) && BotGroupManager.mostHurtMember(getOwner(), HEALER_DANGER_PERCENT, true) != null && tendToTheGroup();
	}

	/**
	 * Moves the bot onto the group's mark when it is fighting something else, so that a group fights one thing at a time.
	 * <p>
	 * The decision tick only picks a target for a bot that is not already fighting, so without this a mark placed after the pull reached nobody who had
	 * already started. A healer is left out: it does not join the attack at all. A mark the bot has already given up on is left alone for as long as
	 * that lasts, or it would turn back to it every tick.
	 *
	 * @return true if the bot has switched, in which case this tick is spent.
	 */
	private boolean followTheMark(Player bot, Creature target) {
		if (BotRole.of(bot) == BotRole.HEALER)
			return false;
		Creature marked = BotMarks.skulled(bot);
		if (marked == null || marked.equals(target) || isIgnored(marked))
			return false;
		startAttacking(marked);
		return true;
	}

	private void noteCrowdControl() {
		if (BotSkillManager.isCrowdControlled(getOwner()))
			lastControlledAt = System.currentTimeMillis();
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
	 * Walks a melee class in to its target instead of casting at it from where it stands, once the fight has been opened.
	 * <p>
	 * Anything in the book that reaches was cast before closing, which suits a caster and not a chanter: alone, it stood back and threw the few spells it
	 * has that reach, and seldom swung the staff its damage is in, as a tester reported. One cast from afar is kept, the pull, and so is casting at a
	 * target that runs or that cannot be walked to; everything else is done in reach.
	 *
	 * @return true if the bot set off towards the target.
	 */
	private boolean closesInRatherThanCasting(Creature target) {
		BotPosition position = BotPosition.of(getOwner());
		if (!pulledFromAfar || position != BotPosition.FRONT && position != BotPosition.BEHIND)
			return false;
		if (target.getEffectController().isAbnormalSet(AbnormalState.FEAR) || target.getAi().isInState(AIState.RETURNING))
			return false; // running away: what reaches is what catches it
		return chase(target);
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

	/**
	 * Picks the one thing the bot does with this moment of the fight, most urgent first.
	 * <p>
	 * The order is the whole of the bot's combat judgement, so it lives in one place rather than being restated wherever a skill might be cast. It
	 * reads as a set of priorities: survive, then keep the advantage the class was given, then deal damage. A cast costs a swing either way, which is
	 * why staying alive comes first and why nothing here is tried twice in the same tick.
	 *
	 * @return true if a skill went off, in which case the bot is busy and should not also swing.
	 */
	private boolean useBestSkill(Creature target) {
		Player bot = getOwner();
		// The class gets first refusal, and what it declines falls through to the order below. See docs/templar-plan.md for the templar's.
		if (BotPlaybooks.of(bot).act(bot, target) || BotPlaybooks.of(bot).heal(bot))
			return true;
		boolean healer = BotRole.of(bot) == BotRole.HEALER;
		// A healer heals before anything else, and that is the whole of its priority: every other thing it does, the damage it is allowed included, is what
		// it does when nobody needs mending. Itself first, since a dead healer heals nobody, then whoever is in the most danger.
		if (healer && (BotSkillManager.tryHealSelf(bot, BotSkillManager.HEAL_IN_COMBAT_PERCENT)
			|| BotSkillManager.tryHealAlly(bot, BotGroupManager.mostHurtMember(bot, BotSkillManager.HEAL_ALLY_PERCENT, true))))
			return true;
		boolean closing = !BotAttackManager.isInAttackRange(bot, target);
		if (closing && !approachSpent) {
			approachSpent = true;
			// only while there is still ground to cover: hiding on top of a target it is about to hit buys the bot nothing
			if (BotSkillManager.tryApproachUnseen(bot))
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
		}
		// A group mate's life outranks the bot's damage, but not the bot's own: a dead healer heals nobody. A healer has done this at the top of the method, ahead
		// of everything and outside the finishing rule: that rule is about the bot's own survival and says nothing about the tank, and a monster with a sliver
		// of health left is no reason to let the tank die under it, which is what a healer did every time the group's target got low.
		if (!healer && !isAlmostDead(target)
			&& BotSkillManager.tryHealAlly(bot, BotGroupManager.mostHurtMember(bot, BotSkillManager.HEAL_ALLY_PERCENT)))
			return true;
		// a leap covers ground the bot would otherwise walk, and those last metres on foot are where bots get stuck
		if (closing && BotSkillManager.tryGapCloser(bot, target))
			return true;
		return BotSkillManager.tryCastSkill(bot, target);
	}

	/**
	 * @return Where a chase is aimed: the target itself, except for a tank in a group, which closes in on the side of the enemy away from the group. That is
	 *         the one moment it chooses a side: once in reach it stays, because moving a tank round an enemy in the middle of a fight is what the guide ruled out.
	 */
	private Vector3f approachPoint(Creature target) {
		Player bot = getOwner();
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
	private boolean chase(Creature target) {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (chaseStartTime == 0)
			chaseStartTime = now;
		else if (now - chaseStartTime > MAX_CHASE_MILLIS)
			return false;
		if (isOffLeash(target))
			return false;
		if (posture.isAnimating())
			return true; // on its feet, or drawing its weapon; walking now is what makes it slide

		if (moveController.isInMove() && now - lastChaseRoute < CHASE_REROUTE_INTERVAL)
			return true;
		Vector3f goal = approachPoint(target);
		if (moveController.isInMove() && moveController.isHeadingTo(goal.getX(), goal.getY(), RETARGET_STEP))
			return true; // already on its way, and the goal has not moved enough to be worth a new route
		lastChaseRoute = now;
		if (PlayerBotConfig.POSITIONING.placesAgainst(target) && BotStations.handlesTank(getOwner()))
			moveController.avoid(BotStations.avoidanceFor(getOwner(), target));
		return tryMoveTo(moveController, goal.getX(), goal.getY(), goal.getZ());
	}

	/**
	 * Joins the group's fight from the air, for a bot following its leader up there: its mates' heals first, then whatever the group
	 * is fighting or whatever is fighting the bot. The flight then holds it within reach of the target ({@code BotFlight.engage}) and the attack loop
	 * swings and casts from there ({@link #fightInTheAir}). A healer stays beside its leader and heals.
	 */
	private void fightFromTheAir() {
		Player bot = getOwner();
		if (!BotFlight.isFollowingInTheAir(bot) || bot.isDead() || bot.isCasting() || isAttacking() || BotGroupManager.leaderToFollow(bot) == null)
			return;
		// Heals only, not the buffs of tendToTheGroup: a body flying beside its leader is always moving, and a buff with a cast time cast on the move is
		// cancelled and cast again on the next tick, for as long as the flight lasts. The buffs wait for the ground.
		boolean healer = BotRole.of(bot) == BotRole.HEALER;
		if (BotPlaybooks.of(bot).heal(bot)
			|| BotSkillManager.tryHealAlly(bot, BotGroupManager.mostHurtMember(bot, BotSkillManager.HEAL_ALLY_PERCENT, healer)))
			return;
		if (healer)
			return;
		Creature target = BotGroupManager.targetToAssist(bot, this::isIgnored);
		if (target == null)
			target = findAttacker();
		if (target == null)
			return;
		BotTargetRegistry.forceClaim(target, bot);
		startAttacking(target);
	}

	/**
	 * One beat of a fight from the air: what reaches is swung or cast, and the flight does the closing in, since there is no route to walk.
	 */
	private void fightInTheAir(Player bot, Creature target) {
		BotFlight.engage(bot, target);
		if (bot.getCastingSkill() != null)
			return; // the flight holds the body still for it
		boolean landed = BotAttackManager.isInAttackRange(bot, target) ? useBestSkill(target) || BotAttackManager.autoAttack(bot, target)
			: useBestSkill(target); // something that reaches from here, which the engine answers by casting it or not
		if (landed) {
			lastBlow = System.currentTimeMillis();
			giveUpsInARow = 0;
		}
	}

	/**
	 * An enemy player who has flown out of reach: shot at when something reaches from here, followed into the air when not. Only for a bot on its own,
	 * since one in a group follows its leader up and down, not its quarry.
	 *
	 * @return true if the beat was spent on it, either way.
	 */
	private boolean takeOffAfter(Player bot, Player flier) {
		if (BotGroupManager.leaderToFollow(bot) != null)
			return false;
		if (castFromAfar(flier)) {
			lastBlow = System.currentTimeMillis();
			giveUpsInARow = 0;
			return true;
		}
		return BotFlight.tryPursue(bot, flier);
	}

	/**
	 * Goes up after a leader who is flying, which is the one thing a grouped bot could not do.
	 * <p>
	 * Following is a walking route to wherever the leader is, and wherever a flying leader is has no floor under it: the bot stood on the ground
	 * beneath them, or walked at a goal the mesh refused. Reported from in game the day flight was started.
	 *
	 * @return true if the bot has taken off, in which case the flight owns it until it lands.
	 */
	private boolean followIntoTheAir() {
		Player leader = BotGroupManager.leaderToFollow(getOwner());
		// a flight transporter or a windstream is a flight too, and not one a bot can keep up with: BotTransports brings it on arrival
		return leader != null && leader.isFlying() && !leader.isUsingFlightTransporterOrWindstream() && BotFlight.tryEscort(getOwner(), leader);
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
			// camp is empty, or the only mobs left belong to someone else's patch
			if (!returnToAnchor())
				day.anchorRefused();
			return;
		}
		if (posture.standUp())
			return;
		tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

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
		if (followLeadersGlide(moveController))
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

	/**
	 * Goes down the way the leader just did: walks to where the leader left the ground, then glides through the points it was seen at in the air. Only
	 * from up there, while the leader is down below, and once per glide.
	 * <p>
	 * The walk alone could not do it. A glide crosses air no mesh joins, so a plan to a leader who had glided down was a long way round, a search that
	 * gave up, or a proof that there was no way; in Nochsana the group came in late or not at all.
	 *
	 * @return true while the bot is on its way to the edge or in the air, in which case nothing else moves it.
	 */
	private boolean followLeadersGlide(BotMoveController moveController) {
		LeaderGlides.Glide glide = leadersGlide;
		if (glide == null || glide == copiedGlide)
			return false;
		if (moveController.isGliding())
			return true;
		Player bot = getOwner();
		Vector3f takeoff = glide.takeoff();
		// still up where the leader jumped from, and the leader still down where it came: otherwise there is nothing to copy
		boolean above = Math.abs(bot.getZ() - takeoff.getZ()) <= GLIDE_SAME_LEVEL && bot.getZ() - glide.landing().getZ() >= LeaderGlides.MIN_DROP;
		Player leader = BotGroupManager.leaderToFollow(bot);
		if (!above || leader == null || leader.getZ() > takeoff.getZ() - LeaderGlides.MIN_DROP) {
			copiedGlide = glide; // not this bot's to copy, now or later
			return false;
		}
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), takeoff.getX(), takeoff.getY()) > TAKEOFF_REACHED) {
			if (moveController.isInMove() && moveController.isHeadingTo(takeoff.getX(), takeoff.getY(), RETARGET_STEP))
				return true;
			if (tryMoveTo(moveController, takeoff.getX(), takeoff.getY(), takeoff.getZ()))
				return true;
			copiedGlide = glide; // no walk to the edge: following on foot is all that is left
			return false;
		}
		copiedGlide = glide;
		stopMoving();
		return moveController.glideAlong(glide.path());
	}

	/** Brings the bot back where it belongs once it has nothing to fight, so a chase does not slowly displace it. */
	boolean returnToAnchor() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return true;
		Vector3f spot = standingSpot();
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), spot.getX(), spot.getY()) <= SPOT_TOLERANCE) {
			refusedFromThere = 0; // where it means to be, so whatever it was refused, the spot it stands on is not the fault
			return true;
		}
		return tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
	}

	/**
	 * Notes that the bot gave up on where it was going, and frees it once it has given up on everything from the spot it stands on.
	 * <p>
	 * Not at home: a bot standing in its own village and refused a hunting ground has an unreachable hunting ground, and lifting it home moves nothing.
	 */
	void destinationRefused() {
		Player bot = getOwner();
		Vector3f here = new Vector3f(bot.getX(), bot.getY(), bot.getZ());
		if (bot.getMoveController() instanceof BotMoveController moveController && moveController.isRefusalUnproven()) {
			// a far place whose searches gave up says nothing about this one (see BotMoveController.refusalUnproven), but every place doing so does
			Vector3f goal = unprovenGoal;
			unprovenGoal = null; // weighed once: a later refusal that set none must not count this one again
			// not isolated: a bot lost on open ground is no proof that the spot leads nowhere, and a player watching does not see it vanish
			if (lostTrail.gaveUp(here, goal, System.currentTimeMillis()))
				rescueFrom(here, "had its searches run out of budget for " + LostTrail.DESTINATIONS + " destinations", false);
			return;
		}
		if (refusedFrom == null || PositionUtil.getDistance(refusedFrom.getX(), refusedFrom.getY(), refusedFrom.getZ(), here.getX(), here.getY(),
			here.getZ()) > SAME_SPOT) {
			refusedFrom = here;
			refusedFromThere = 0;
		}
		if (++refusedFromThere < ISOLATED_REFUSALS)
			return;
		refusedFrom = null;
		refusedFromThere = 0;
		rescueFrom(here, "gave up on " + ISOLATED_REFUSALS + " destinations in a row", true);
	}

	/**
	 * Frees a bot whose spot is the fault, unless it is already at home, where the stranding is the home's business.
	 *
	 * @param isolated Whether the spot is proved to lead nowhere, which lifts the bot even in a player's sight; see {@link #freeItself}.
	 */
	private void rescueFrom(Vector3f here, String why, boolean isolated) {
		Player bot = getOwner();
		Vector3f home = day.home();
		if (home == null)
			return;
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), home.getX(), home.getY(), home.getZ());
		Vector3f doorstep = ground != null ? ground : home;
		if (PositionUtil.getDistance(here.getX(), here.getY(), here.getZ(), doorstep.getX(), doorstep.getY(), doorstep.getZ()) < STRANDED_AT_HOME)
			return;
		log.info("Bot {} {} from {} {} {}, so it is the spot that leads nowhere", bot.getName(), why, Math.round(here.getX()), Math.round(here.getY()),
			Math.round(here.getZ()));
		freeItself(isolated);
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
		return navmesh.canReachNearby(getOwner().getWorldId(), anchorX, anchorY, anchorZ, ground.getX(), ground.getY(), ground.getZ()) ? ground : anchor;
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
		if (moveController.isRefusalUnproven()) {
			unprovenGoal = new Vector3f(x, y, z); // weighed when the caller reports the refusal, see destinationRefused
			return false; // searches that ran out of budget: the journey is off, and nothing is known against the spot the bot stands on
		}
		if (++refusedMoves >= STUCK_REFUSALS)
			freeItself(false);
		return false;
	}

	/**
	 * How near its leader a grouped bot has to be for being unable to walk to count as waiting rather than being stuck.
	 * <p>
	 * Generous on purpose: a player who has flown off to a ledge is exactly the case this protects, and the distance to them says nothing about
	 * whether the bot is in trouble. Past it, the bot has been left behind rather than kept, and the ordinary rescue is right again.
	 */
	private static final float STAY_WITH_THE_GROUP = 200;
	/**
	 * How near its home a bot has to be stranded for the stranding to be evidence about the home, in metres.
	 * <p>
	 * A home has fifteen metres of scatter, so this is a little over it: near enough to be "where it lives", far enough not to miss a resident put
	 * down at the far edge of its own village.
	 */
	private static final float STRANDED_AT_HOME = 40;
	/** How near the ground a rescue would put a bot on it must stand for the rescue to be no move at all, in metres. */
	private static final float ALREADY_THERE = 5;
	/**
	 * How far a bot on ground cut off from where it lives may be stepped off it while a player watches: over a counter or a rail, off the plinth of
	 * an obelisk, not across a room. Five metres and pockets only until 24 October, when the floor round Altgard's obelisk, joined to nothing, kept a
	 * bot "doing something else" in front of a player 25 times in half an hour, the ground it needed 10 m away.
	 */
	private static final float STEP_OUT_IN_SIGHT = 12;

	/**
	 * Puts a bot that cannot move at all back where it lives.
	 * <p>
	 * However carefully a place is chosen, a bot walks on its own afterwards, and the reactive layer that steps round obstacles can walk it into one
	 * — behind a rock, under a root, onto a shelf. From inside, every destination is refused, including spots a pace away: three separate bots have
	 * now spent minutes asking for routes that do not exist from where they stand. There is no diagnosis to make at that point and nothing to walk
	 * out along, which is exactly why players are given an unstick command rather than advice.
	 *
	 * @param isolated Whether the bot has given up on everything from where it stands, rather than been refused a few moves: it is then lifted even
	 *          with a player looking, since a body standing in the air over a capital's crowd is a worse sight than one that blinks out of it.
	 */
	private void freeItself(boolean isolated) {
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

		// A body blinking across the ground in front of somebody is the fault itself, not its cure, so a bot a player can see is not lifted: it drops
		// its errand and picks another, and the rescue waits for nobody to be looking. Except in a player's group, where staying stuck is worse than
		// being seen: the player is waiting for it, and a member left behind is a member lost.
		if (!isolated && !isInAPlayersGroup(bot) && isSeenByAPlayer(bot)) {
			// Except off ground that leads nowhere, onto floor a step away. Doing something else never ends there, since every something else is a route
			// out: a bot shut behind a shop counter sat down and stood up every thirteen seconds in front of the player for as long as they stayed. A step
			// over the counter is less of a sight than that. Any ground cut off from the bot's anchor, not only a pocket: toWalkableGround answers only
			// for a bot that is trapped, and the floor round an obelisk joined to nothing is too large to be a pocket.
			Vector3f step = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), anchor(), bot.getObjectId(), false);
			if (step != null && PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), step.getX(), step.getY(), step.getZ()) <= STEP_OUT_IN_SIGHT) {
				log.info("Bot {} could not move at all from {} {}, on ground cut off from where it lives, and steps out onto {} {} {} in sight of a player", bot.getName(),
					Math.round(bot.getX()), Math.round(bot.getY()), Math.round(step.getX()), Math.round(step.getY()), Math.round(step.getZ()));
				forgetRefusals();
				TeleportService.teleportTo(bot, bot.getWorldId(), step.getX(), step.getY(), step.getZ());
				return;
			}
			log.info("Bot {} could not move at all from {} {}, but a player has it in sight, so it does something else rather than being lifted", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()));
			day.forget();
			forgetRefusals();
			return;
		}

		// A bot in somebody's group is where it is for a reason, and a lift home is the one thing that cannot be allowed to happen to it: the whole
		// point of following somebody is being with them. It waits instead -- for the player to land, to come back, or for its own flight points.
		// Reported from in game as a bot that followed into the air, landed, and was gone: not a flight fault at all, but the oldest rescue in the
		// module doing what it was written to do to a bot that had never been stuck.
		// In an instance at any distance: the player went on by a teleporter, a lever, a lift or a door their key opened, which a bot does not use, and
		// the ordinary rescue below would send it to its home's coordinates on the instance's own map.
		// A player's group at any distance as well: a player who has gone a long way off went by something the bot does not use, and home is the one
		// place it should not be put. On 20 October three members were rescued to their homes across Eltnen while their player was on a flight.
		Player leader = BotGroupManager.leaderToFollow(bot);
		if (leader != null && (bot.isInInstance() || isInAPlayersGroup(bot) && leader.getWorldId() == bot.getWorldId() || PositionUtil.getDistance(bot, leader) <= STAY_WITH_THE_GROUP)) {
			// Waiting is for a leader in the air. One standing on the ground can be walked to from somewhere, and a bot that cannot is in a pocket, which
			// waiting never ends: in the first squads a cleric and a ranger stood in one for minutes, 18 to 45 m from their leader, giving up every
			// target the group fought. It is put on the nearest floor joined to the leader's, which keeps it with the group as the wait meant to.
			if (!leader.isFlying()) {
				Vector3f out = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()),
					new Vector3f(leader.getX(), leader.getY(), leader.getZ()), bot.getObjectId(), false);
				if (out != null) {
					log.info("Bot {} could not move at {} {}, cut off from {}, and is put on walkable ground at {} {} {}{}", bot.getName(), Math.round(bot.getX()),
						Math.round(bot.getY()), leader.getName(), Math.round(out.getX()), Math.round(out.getY()), Math.round(out.getZ()), witnessed(bot));
					forgetRefusals(); // what was refused was refused from where it stood
					TeleportService.teleportTo(bot, bot.getWorldId(), out.getX(), out.getY(), out.getZ());
					return;
				}
			}
			if (!leader.isFlying()) {
				// No floor joined to the leader's near the bot: waiting would never end, so it goes to the leader, onto the ground under their feet
				Vector3f beside = NavmeshService.getInstance().groundNear(bot.getWorldId(), leader.getX(), leader.getY(), leader.getZ());
				float toX = beside != null ? beside.getX() : leader.getX(), toY = beside != null ? beside.getY() : leader.getY();
				float toZ = beside != null ? beside.getZ() : leader.getZ();
				log.info("Bot {} could not move at {} {}, cut off from {}, and is put beside them at {} {} {}{}", bot.getName(), Math.round(bot.getX()),
					Math.round(bot.getY()), leader.getName(), Math.round(toX), Math.round(toY), Math.round(toZ), witnessed(bot));
				forgetRefusals(); // what was refused was refused from where it stood
				TeleportService.teleportTo(bot, bot.getWorldId(), toX, toY, toZ);
				return;
			}
			// a leader in the air lands somewhere, and the bot follows it there; lifting it now would only put it under them
			log.info("Bot {} cannot move from {} {} but is {} m from {}, so it waits rather than being put back home", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()), Math.round(PositionUtil.getDistance(bot, leader)), leader.getName());
			return;
		}
		if (bot.isInInstance()) {
			// no leader here to be put beside, and its home is on another map: it waits where it is, and BotInstances takes it out
			log.info("Bot {} cannot move from {} {} in map {} and has no leader there, so it waits", bot.getName(), Math.round(bot.getX()),
				Math.round(bot.getY()), bot.getWorldId());
			forgetRefusals();
			return;
		}

		// Ground it can walk away from, close by, before anything that takes it across the map: a bot that cannot leave its pocket does not need its
		// home, it needs the floor beside it. Only when the mesh says the spot is cut off from its anchor or is inside geometry; one that is merely
		// wedged against a prop on connected ground is left to the rescue below.
		Vector3f near = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), anchor(), bot.getObjectId(),
			bot.getCommonData().isDaeva() && !bot.getTransformModel().cantFly());
		if (near != null) {
			log.info("Bot {} could not move at {} {} {}, which the map does not join to its anchor, and is put on walkable ground at {} {} {}{}", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()), Math.round(bot.getZ()), Math.round(near.getX()), Math.round(near.getY()), Math.round(near.getZ()),
				witnessed(bot));
			forgetRefusals(); // what was refused was refused from where it stood
			TeleportService.teleportTo(bot, bot.getWorldId(), near.getX(), near.getY(), near.getZ());
			return;
		}
		// A bot that can fly out of here is not stuck, and a lift home is the wrong answer to a problem it can solve itself. This is now the ordinary
		// way a flight ends somewhere with no way off it: an escort breaks off over a ledge, lands on it, finds every walking route refused -- and
		// goes home through the air it came by, once its flight points are back.
		if (BotFlight.tryFlyTo(bot, new Vector3f(x, y, z))) {
			log.info("Bot {} could not walk off {} {} and is flying home instead", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()));
			day.forget();
			return;
		}
		// Counted only when it happened at home, which is the one place a stranding says anything about. The count exists to recognise a home a bot
		// cannot walk out of -- without it, that rescue runs every thirteen seconds for as long as the server is up. Stranded somewhere else, the bot
		// went there: on a terrace it flew to, at the end of an errand, behind a rock. Rehousing it for that moves a resident across the map for
		// having gone somewhere, which is what an escort landing on a ledge made it do -- three rescues in under a minute, and its home 1200 m away.
		boolean atHome = PositionUtil.getDistance(bot.getX(), bot.getY(), home.getX(), home.getY()) < STRANDED_AT_HOME;
		day.forget(); // whatever it was doing was decided from a place it is no longer in
		Vector3f moved = atHome ? day.recordStranding() : null;
		if (moved != null) {
			x = moved.getX();
			y = moved.getY();
			z = moved.getZ();
		} else if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), x, y, z) < ALREADY_THERE) {
			// in three dimensions: a bot on a ledge straight above its front door is not at its front door
			// A lift onto the spot the bot stands on moves nothing and shows a body blinking: 136 of 403 rescues in half an hour were that. A bot standing
			// at its own front door asking for somewhere the planner cannot find is not stuck, its destination is, and forgetting it was the cure.
			log.info("Bot {} could not move at all from {} {}, where it lives, and is left there with its errand forgotten", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()));
			forgetRefusals();
			return;
		}
		log.info("Bot {} could not move at all from {} {} and is put back home{}{}", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()),
			atHome && moved == null ? ", which is where it already was" : "", witnessed(bot));
		forgetRefusals(); // what was refused was refused from where it stood
		TeleportService.teleportTo(bot, bot.getWorldId(), x, y, z);
		setAnchor(x, y, z);
	}

	private void forgetRefusals() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController)
			moveController.forgetRefusals();
	}

	/**
	 * @return A note for the log when a real player has the bot in sight, the same question {@link com.aionemu.gameserver.playerbot.PlayerBotService#sleep} asks before taking one away:
	 *         a teleport nobody sees is a fault in the log, one somebody sees is a fault in the game.
	 */
	private static String witnessed(Player bot) {
		return isSeenByAPlayer(bot) ? " (seen by a player)" : "";
	}

	/** @return true if a real player has the bot in sight, the same question {@link com.aionemu.gameserver.playerbot.PlayerBotService#sleep} asks. */
	private static boolean isSeenByAPlayer(Player bot) {
		return bot.getKnownList().streamPlayers().anyMatch(other -> !other.isBot());
	}

	/** @return true if the bot's group has a real player in it, as opposed to a squad of bots or no group at all. */
	private static boolean isInAPlayersGroup(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return team != null && team.getMembers().stream().anyMatch(member -> !member.isBot());
	}

	/**
	 * Puts the weapon away once there is nothing to point it at.
	 * <p>
	 * Asked of the state and not only of the last fight. It used to run solely in the seconds after combat ended, so a bot holding its weapon for any
	 * other reason held it for ever: the state belongs to the character rather than to this life, and a bot despawned mid fight came back to a town
	 * with it drawn.
	 */
	private void sheathWhenCalm() {
		if (isAttacking() || posture.isAnimating())
			return;
		boolean grouped = getOwner().getCurrentTeam() != null;
		if (grouped && getOwner().isInInstance())
			return; // a group in an instance is never more than a corridor from its next pack; sitting down to rest still puts the weapon away
		if (combatEndedAt != 0) {
			if (System.currentTimeMillis() - combatEndedAt < (grouped ? GROUP_SHEATHE_DELAY_MILLIS : SHEATHE_DELAY_MILLIS))
				return;
			combatEndedAt = 0;
		} else if (!getOwner().isInState(CreatureState.WEAPON_EQUIPPED)) {
			return;
		}
		posture.sheathe();
	}

	/**
	 * Sits down to heal where the bot stands, because resting recovers eight times faster than standing around. It recovers on the spot rather than
	 * walking home first: the fight is over, and a player sits down where it ended.
	 */
	private void recover() {
		if (getOwner().getMoveController().isInMove())
			return;
		if (leaveHarmfulGround())
			return;
		// The class's own answer to being hurt comes before sitting down. A chanter's Healing Light is its heal and the generic rule below has been told to
		// leave it alone, and a bot is under the engage threshold at nine tenths of its health, which is where the class's own rules would otherwise never be asked.
		if (BotPlaybooks.of(getOwner()).tend(getOwner()))
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
	 * Walks off lava, or any ground whose material hurts the bot, before it heals or sits down there, see {@link HarmfulGround}.
	 *
	 * @return true if the bot is getting up or on its way.
	 */
	private boolean leaveHarmfulGround() {
		Player bot = getOwner();
		if (!HarmfulGround.isUnder(bot)) {
			harmfulGroundTries = 0;
			return false;
		}
		if (harmfulGroundTries >= HARMFUL_GROUND_TRIES)
			return false;
		if (posture.standUp())
			return true;
		harmfulGroundTries++;
		Vector3f safe = HarmfulGround.safeGroundNear(bot);
		BotMoveController moves = moves();
		if (safe == null || moves == null || !tryMoveTo(moves, safe.getX(), safe.getY(), safe.getZ())) {
			log.info("Bot {} rests on ground that hurts it at {} {} {}: no ground that does not is in reach", bot.getName(), Math.round(bot.getX()),
				Math.round(bot.getY()), Math.round(bot.getZ()));
			harmfulGroundTries = HARMFUL_GROUND_TRIES;
			return false;
		}
		log.info("Bot {} stands on ground that hurts it at {} {} {} and walks off it to {} {} {} before resting", bot.getName(), Math.round(bot.getX()),
			Math.round(bot.getY()), Math.round(bot.getZ()), Math.round(safe.getX()), Math.round(safe.getY()), Math.round(safe.getZ()));
		return true;
	}

	/**
	 * Moves a bot that the engine has just put down on ground it cannot walk away from onto ground it can, see {@link BotLanding}.
	 * <p>
	 * The goal it is measured against is the bot's anchor, which resurrection has just moved home: ground that cannot reach the place the bot lives is
	 * ground it cannot go on living from.
	 */
	private void stepOffTrappedGround() {
		Player bot = getOwner();
		Vector3f ground = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), anchor(), bot.getObjectId(),
			bot.getCommonData().isDaeva() && !bot.getTransformModel().cantFly());
		if (ground == null)
			return;
		log.info("Bot {} came back at {} {} {}, where it cannot walk away from, and is put on walkable ground at {} {} {}{}", bot.getName(),
			Math.round(bot.getX()), Math.round(bot.getY()), Math.round(bot.getZ()), Math.round(ground.getX()), Math.round(ground.getY()),
			Math.round(ground.getZ()), witnessed(bot));
		stopMoving();
		TeleportService.teleportTo(bot, bot.getWorldId(), ground.getX(), ground.getY(), ground.getZ());
	}

	/**
	 * Waits out the death penalty at the bot's own quiet spot, see {@link BotConvalescence}. Heals there if it is hurt, and does nothing at all if it is
	 * not. A bot with no such spot rests where it stands, as it did before.
	 */
	private void convalesce() {
		Vector3f spot = convalescence.spot();
		if (spot == null) {
			if (!isHealthyEnoughToFight())
				recover();
			return;
		}
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return;
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), spot.getX(), spot.getY()) > SPOT_TOLERANCE) {
			if (!posture.standUp())
				tryMoveTo(moveController, spot.getX(), spot.getY(), spot.getZ());
		} else if (!isHealthyEnoughToFight())
			recover();
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

	/**
	 * Keeps the bot from starting a fight it is in no shape for — in particular right after resurrecting at 25% hp, next to whatever killed it.
	 * Only picking a fight is gated: it always defends itself, whatever its health.
	 */
	private boolean isHealthyEnoughToFight() {
		return getOwner().getLifeStats().getHpPercentage() >= MIN_ENGAGE_HP_PERCENT
			&& getOwner().getLifeStats().getMpPercentage() >= MIN_ENGAGE_MP_PERCENT && !BotPlaybooks.of(getOwner()).wantsToRest(getOwner());
	}

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
		if (day.keepsStore())
			return false; // a merchant on its shift has no use for one, and keepTheStall sends away one it already has
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
		if (day.keepsStore())
			return false; // a merchant on its shift stands at its stall on foot: the engine opens no store on a body in its robot

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
		// never in a group, as the rule always said and the code never asked: a squad's templar leader left at full health from the two attackers its
		// guide tells it to leave alone, and its five members died without a tank
		if (bot.isDead() || !bot.isSpawned() || isRecoveringFromDeath() || bot.getCurrentTeam() != null)
			return false;
		// Ignored ones do not count: they are what the bot has already decided to leave, and counting them again would re-decide the same retreat on
		// every tick -- seen in the log as one bot breaking off from the same two attackers four times in as many seconds. Once everything on it is
		// ignored the chain below walks it home, which is the retreat continuing rather than a new one.
		List<Creature> attackers = findAttackers().stream().filter(npc -> !isIgnored(npc)).toList();
		if (attackers.isEmpty())
			return false;
		boolean outmatched = attackers.stream().anyMatch(npc -> npc.getLevel() > bot.getLevel() + BotTargetSelector.MAX_LEVEL_GAP);
		// the class may hold or leave where the generic rule would not: a gladiator holds against two normal monsters and leaves an elite at any health
		ClassPlaybook.Outnumbered call = attackers.size() > 1 ? BotPlaybooks.of(bot).whenOutnumbered(bot, attackers) : ClassPlaybook.Outnumbered.GENERIC;
		boolean outnumbered = call == ClassPlaybook.Outnumbered.FLEE
			|| call == ClassPlaybook.Outnumbered.GENERIC && attackers.size() > 1 && bot.getLifeStats().getHpPercentage() < RETREAT_HP_PERCENT;
		// some classes are told by their guide to leave at a number of attackers whatever their health
		boolean fleeing = BotPlaybooks.of(bot).flees(bot, attackers.size());
		if (!outmatched && !outnumbered && !fleeing)
			return false;
		long until = System.currentTimeMillis() + RETREAT_AVOIDED_MILLIS;
		for (Creature attacker : attackers)
			ignoredTargets.put(attacker.getObjectId(), until);
		log.info("Bot {} breaks off from {} attacker(s) at {}% health and heads home", bot.getName(), attackers.size(),
			bot.getLifeStats().getHpPercentage());
		BotPlaybooks.of(bot).onRetreat(bot);
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

	/**
	 * @return An npc currently attacking the bot, or null. Being hit overrides every other consideration, including the health threshold that
	 *         normally sends the bot resting: sitting down under fire is both suicidal and absurd to watch.
	 */
	private Creature findAttacker() {
		Player bot = getOwner();
		boolean groupHealer = isGroupHealer(bot);
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
			// A group's healer is the exception, and counts only what is aimed at it, by the same rule the fight loop uses to let such a fight go
			// (healerHasNoSurplus). Read two ways, the add a cleric had rooted was hated enough to start a fight every tick and not aimed at it enough to
			// keep one, so the cleric started and dropped it every second for 18 s and cast no heal while the templar died (20 October, 13:11).
			if (attacker[0] == null && !npc.isDead() && !BotTargetSelector.isScenery(npc) && npc.getAggroList().isHating(bot) && !isIgnored(npc)
				&& (!groupHealer || bot.equals(npc.getTarget())))
				attacker[0] = npc;
		});
		return attacker[0];
	}

	/** @return How long a monster given up on for the n-th time is left alone. */
	static long unreachableFor(int failures) {
		return failures <= 1 ? UNREACHABLE_MILLIS : failures == 2 ? UNREACHABLE_AGAIN_MILLIS : UNREACHABLE_FOR_GOOD_MILLIS;
	}

	/** @return true if a target is further from the bot's anchor than it follows anything, which is a fight it would break off as soon as it chased. */
	private boolean isOffLeash(Creature target) {
		// never something that is hitting the bot: on a walk the anchor is the walk's end, and a naga sorcerer casting from beyond it was left alone
		// while it went on casting, which is how most of the deaths of Reshanta's upper layer began (21 of 26 in half an hour were a fight the bot did
		// not pick)
		return PositionUtil.getDistance(anchorX, anchorY, target.getX(), target.getY()) > LEASH_DISTANCE && !getOwner().equals(target.getTarget());
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
		if (moveController.isFlying() && !moveController.isGliding())
			return; // a flight leg that has been reached is the flight's business, and continueToGoal would hand the body to a walking route
		// a glide is a leg of the walk, and its landing goes on with the route like any other arrival
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
		// brought in with the whole world at startup: its day starts a while later, a moment of its own
		if (System.currentTimeMillis() / 1000 - GameServer.START_TIME_SECONDS < STARTUP_SECONDS)
			settlesAt = System.currentTimeMillis() + Rnd.get(0, SETTLING_IN_SECONDS) * 1000L;
		synchronized (combatLock) {
			scheduleBotTick();
		}
		log.info("Bot {} spawned", getOwner().getName());
	}

	/**
	 * Answers whoever has just got onto the bot's aggro list.
	 * <p>
	 * The event says hate, not hostility: {@code Effect.broadcastHate} puts the caster of a buff on its target's list as well, since a buff of the
	 * "indirect" kind makes hate. So a chanter's Protectorate's Prayer on a mate fired this, the mate turned on the chanter, found it was no enemy and
	 * dropped the fight, cancelling whatever walk it was on. Seen in the first squad's log, three mates at once on every prayer.
	 */
	@Override
	protected void handleAttack(Creature attacker) {
		if (autonomous && !isAttacking() && !attacker.equals(getOwner()) && getOwner().isEnemy(attacker)) {
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
			if (reviveTask != null) { // already dealt with, unless a resurrection has been cast on it since
				if (!resurrectionPending && getOwner().getResStatus()) {
					// the window a resurrection opens is clicked by the player it is cast on, so for a bot the decision tick clicks it: taking a resurrection
					// instead of the obelisk is what a player does when one is offered, and it is what a chanter's Light of Resurrection is for
					resurrectionPending = true;
					reviveTask.cancel(false);
					reviveTask = BotScheduler.getInstance().schedule(this::acceptResurrection, ACCEPT_RESURRECTION_DELAY_MILLIS);
				}
				return;
			}
			// nobody will ever click the resurrection window for a bot, so without this it lies dead forever
			diedAt = groupLastFoughtAt = System.currentTimeMillis();
			reviveTask = BotScheduler.getInstance().schedule(this::reviveUnlessRaised, REVIVE_DELAY_MILLIS);
		}
		Player bot = getOwner();
		// whatever it was fighting just won, so leave it alone for a while instead of walking straight back into it
		if (bot.getTarget() instanceof Creature killer)
			ignoredTargets.put(killer.getObjectId(), System.currentTimeMillis() + KILLER_AVOIDED_MILLIS);
		cancelCombat();
		if (bot.getMoveController() instanceof BotMoveController moveController)
			moveController.abandonGlide();
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
		bringBack(false);
	}

	/**
	 * Goes back to the obelisk, unless the group may still raise the bot where it lies: then it looks again in a moment.
	 * <p>
	 * A bot alone goes at once, as before. One in a group waits while the group is fighting and for {@value #RESURRECTION_WAIT_MILLIS} ms after, as a
	 * player waits for the cleric before releasing; it goes sooner when nobody in the group is left standing on its map to raise it.
	 */
	private void reviveUnlessRaised() {
		long wait = resurrectionWaitLeft();
		synchronized (combatLock) {
			// A resurrection taken in the meantime has replaced this task with its own, and a bot leaving the world has dropped it: either way this run is
			// stale, and going to the obelisk now would throw away the raise the tick has just accepted.
			if (reviveTask == null || resurrectionPending)
				return;
			if (wait > 0) {
				reviveTask = BotScheduler.getInstance().schedule(this::reviveUnlessRaised, Math.min(wait, RESURRECTION_LOOK_MILLIS));
				return;
			}
		}
		revive();
	}

	/** @return How much longer the bot waits to be raised, in milliseconds; 0 or less to go back now. */
	private long resurrectionWaitLeft() {
		Player bot = getOwner();
		if (!BotGroupManager.hasLivingMateOnTheMap(bot))
			return 0;
		long now = System.currentTimeMillis();
		if (BotGroupManager.isGroupFighting(bot))
			groupLastFoughtAt = now;
		return Math.min(groupLastFoughtAt + RESURRECTION_WAIT_MILLIS, diedAt + LONGEST_RESURRECTION_WAIT_MILLIS) - now;
	}

	/**
	 * Takes the resurrection somebody has cast on the bot, where it lies: no obelisk and no walk home, which is the whole of what a resurrection is for. It
	 * comes back on a third of its health with soul sickness, as the engine gives it to a player ({@code skillRevive}), and its anchor stays where it was,
	 * since it is still beside the group that raised it.
	 */
	private void acceptResurrection() {
		bringBack(true);
	}

	private void bringBack(boolean resurrected) {
		Player bot = getOwner();
		try {
			if (!bot.isSpawned() || !bot.isDead())
				return;
			// skillRevive writes the character into the audit log as a resurrection hack when nobody cast one, so it is asked first
			if (resurrected && bot.getResStatus()) {
				PlayerReviveService.skillRevive(bot);
				convalescence.forget();
				setStateIfNot(AIState.IDLE);
				log.info("Bot {} accepted a resurrection at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
				return;
			}
			if (bot.isInInstance()) {
				// the instance's entrance, as a player inside chooses it: the obelisk is on another map, and a bot sent there leaves its group for the
				// length of a walk back it cannot make, since only the player's portal leads in
				PlayerReviveService.instanceRevive(bot);
				BotMapChange.arrive(bot); // the instance had no entrance on record, and the revival fell back to the obelisk
				convalescence.forget();
				setStateIfNot(AIState.IDLE);
				log.info("Bot {} revived at the entrance of map {} at {} {} {}", bot.getName(), bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ());
				return;
			}
			PlayerReviveService.bindRevive(bot);
			BotMapChange.arrive(bot); // an obelisk on another map, which a death far from home can bring
			checkLanding = true; // looked at on the next tick, once the move to the obelisk has settled
			convalescence.forget(); // a new death, a new look at where is quiet
			Vector3f home = day.home();
			if (home != null)
				setAnchor(home.getX(), home.getY(), home.getZ());
			setStateIfNot(AIState.IDLE);
			log.info("Bot {} revived at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
			// Home on another layer of the map, which no walk and no flight joins to the obelisk: taken there as a player takes the base's teleporter.
			// Reshanta's upper layer has no obelisk of its own, and its residents came back in Teminon, 1300 m below, asked for routes home that do
			// not exist and were rescued a dozen seconds later: 47 refused routes and 22 of 23 rescues of its 22 residents in half an hour.
			// Not in a group, whose members come back to it: a squad member taken home was left hundreds of metres from a leader waiting for it.
			if (home != null && bot.getCurrentTeam() == null && Math.abs(home.getZ() - bot.getZ()) > ANOTHER_LAYER)
				BotScheduler.getInstance().schedule(() -> takeHomeWhenRecovered(home, TAKEN_HOME_LOOKS), TAKEN_HOME_DELAY_MILLIS);
		} finally {
			// cleared last, so the decision tick cannot mistake the bot for freshly dead while it is being brought back
			synchronized (combatLock) {
				reviveTask = null;
				resurrectionPending = false;
			}
		}
	}

	/**
	 * Takes a bot revived on another layer of the map home, once it has waited out its sickness and healed: taken there at once, still sick, a resident
	 * of Reshanta's upper layer died again at home within thirty seconds of every revival, five times running. Looked at again until then.
	 */
	private void takeHomeWhenRecovered(Vector3f home, int looksLeft) {
		Player bot = getOwner();
		if (!bot.isSpawned() || bot.isDead() || bot.getCurrentTeam() != null || Math.abs(home.getZ() - bot.getZ()) <= ANOTHER_LAYER)
			return;
		if ((isRecoveringFromDeath() || !isHealthyEnoughToFight()) && looksLeft > 0) {
			BotScheduler.getInstance().schedule(() -> takeHomeWhenRecovered(home, looksLeft - 1), TAKEN_HOME_DELAY_MILLIS);
			return;
		}
		log.info("Bot {} lives {} m above or below where it revived and is taken home", bot.getName(), Math.round(Math.abs(home.getZ() - bot.getZ())));
		TeleportService.teleportTo(bot, bot.getWorldId(), home.getX(), home.getY(), home.getZ());
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
		convalescence.forget(); // a bot that leaves the world mid sickness must not keep its ground reserved
		cancelCombat();
		setStateIfNot(AIState.DESPAWNED);
		log.info("Bot {} despawned", getOwner().getName());
	}
}
