package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AITemplate;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotRestManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Brain of a player bot. The engine fires creature events on whatever AI is attached to a creature, so a bot receives ATTACK, MOVE_ARRIVED and
 * ATTACK_COMPLETE for free, unlike the NpcAI-typed handlers in {@code ai/handler} which cannot be reused here.
 */
public class PlayerBotAI extends AITemplate<Player> {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	private static final int THINK_INTERVAL_MILLIS = 1000;
	/** A chase is abandoned after this long, whatever the reason it is not getting anywhere. */
	private static final long MAX_CHASE_MILLIS = 15000;
	/** How far from its anchor a bot may be dragged before it breaks off and comes back. */
	private static final float LEASH_DISTANCE = 40f;
	/** A chased target is only given a new route once it has moved this far, instead of on every tick. */
	private static final float RETARGET_STEP = 3f;
	/**
	 * Minimum delay between two routes towards the same chased target. Each new route makes clients restart their interpolation, so re-routing on
	 * every attack tick turns a run into a stutter.
	 */
	private static final long CHASE_REROUTE_INTERVAL = 600;
	/** How long a target the bot could not reach is left alone, so it does not pick the same unreachable one again right away. */
	private static final long UNREACHABLE_MILLIS = 10000;
	/** How long the bot leaves alone whatever killed it, so a lost fight is not restarted on a loop. */
	private static final long KILLER_AVOIDED_MILLIS = 120000;
	/** Health below which the bot waits to regenerate instead of looking for a fight. */
	private static final int MIN_ENGAGE_HP_PERCENT = 90;
	/** Close enough to the anchor to count as home, so the bot does not fidget over a metre. */
	private static final float ANCHOR_TOLERANCE = 5f;
	/** Roughly the time a player spends looking at the resurrection window. */
	private static final int REVIVE_DELAY_MILLIS = 10000;

	/** Guards the scheduled tasks against concurrent starts and stops, since events and ticks run on different pool threads. */
	private final Object combatLock = new Object();
	private ScheduledFuture<?> attackTask;
	private ScheduledFuture<?> thinkTask;
	/** Non null between death and resurrection, which also marks the death as already handled. */
	private ScheduledFuture<?> reviveTask;
	private volatile boolean autonomous = true;

	/** Where the bot belongs: it fights around this point and returns to it rather than following a target across the map. */
	private volatile float anchorX, anchorY, anchorZ;
	private volatile long chaseStartTime;
	private volatile long lastChaseRoute;
	/** Targets not to pick for a while: either out of reach, or having just killed the bot. */
	private final Map<Integer, Long> ignoredTargets = new ConcurrentHashMap<>();

	public PlayerBotAI(Player owner) {
		super(owner);
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

	/** Decision loop, kept separate from the framework's {@code think()} so nothing in the engine can trigger it unexpectedly. */
	private void botTick() {
		if (getOwner().isSpawned() && getOwner().isDead())
			handleDeath();
		else if (autonomous && !isAttacking() && getOwner().isSpawned()) {
			if (!isHealthyEnoughToFight())
				recover();
			else {
				Creature target = BotTargetSelector.findTarget(getOwner(), this::isIgnored);
				if (target != null)
					startAttacking(target);
				else
					returnToAnchor();
			}
		}
		synchronized (combatLock) {
			if (thinkTask != null) // still spawned
				scheduleBotTick();
		}
	}

	private void scheduleBotTick() {
		thinkTask = ThreadPoolManager.getInstance().schedule(this::botTick, THINK_INTERVAL_MILLIS);
	}

	public void startAttacking(Creature target) {
		BotRestManager.standUp(getOwner()); // canAttack() is false while resting
		if (getOwner().isProtectionActive()) // CM_ATTACK does this for a real player
			getOwner().getController().stopProtectionActiveTask();
		synchronized (combatLock) {
			stopAttackTask();
			getOwner().setTarget(target);
			setStateIfNot(AIState.FIGHT);
			BotAttackManager.enterAttackMode(getOwner(), target);
			scheduleAttackTick(0);
		}
		log.info("Bot {} starts attacking {}", getOwner().getName(), target.getName());
	}

	public void stopAttacking() {
		cancelCombat();
		setStateIfNot(AIState.IDLE);
	}

	private void cancelCombat() {
		synchronized (combatLock) {
			stopAttackTask();
			chaseStartTime = 0;
			stopMoving();
			BotAttackManager.leaveAttackMode(getOwner());
			getOwner().setTarget(null);
		}
	}

	public boolean isAttacking() {
		synchronized (combatLock) {
			return attackTask != null;
		}
	}

	private void attackTick() {
		Player bot = getOwner();
		Creature target = bot.getTarget() instanceof Creature creature ? creature : null;
		if (!BotAttackManager.canKeepFighting(bot, target)) {
			log.info("Bot {} stops attacking", bot.getName());
			stopAttacking();
			return;
		}

		if (bot.getCastingSkill() != null) {
			// casting roots a real player, so the bot neither moves nor starts another action until the cast is over
		} else if (BotAttackManager.isInAttackRange(bot, target)) {
			chaseStartTime = 0;
			stopMoving();
			if (!BotSkillManager.tryCastSkill(bot, target))
				BotAttackManager.autoAttack(bot, target);
		} else if (!chase(target)) {
			log.info("Bot {} cannot reach {} and gives up", bot.getName(), target.getName());
			ignoredTargets.put(target.getObjectId(), System.currentTimeMillis() + UNREACHABLE_MILLIS);
			stopAttacking();
			return;
		}

		synchronized (combatLock) {
			if (attackTask != null) // not stopped while we were attacking
				scheduleAttackTick(bot.getGameStats().getAttackSpeed().getCurrent());
		}
	}

	private void scheduleAttackTick(int delayMillis) {
		attackTask = ThreadPoolManager.getInstance().schedule(this::attackTick, delayMillis);
	}

	private void stopAttackTask() {
		if (attackTask != null) {
			attackTask.cancel(false);
			attackTask = null;
		}
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

		if (moveController.isInMove()
			&& (now - lastChaseRoute < CHASE_REROUTE_INTERVAL || moveController.isHeadingTo(target.getX(), target.getY(), RETARGET_STEP)))
			return true; // already on its way, and the target has not moved enough to be worth a new route
		lastChaseRoute = now;
		return moveController.moveToPoint(target.getX(), target.getY(), target.getZ());
	}

	private void stopMoving() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isInMove())
			moveController.stop();
	}

	/** Brings the bot back where it belongs once it has nothing to fight, so a chase does not slowly displace it. */
	private void returnToAnchor() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return;
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) > ANCHOR_TOLERANCE)
			moveController.moveToPoint(anchorX, anchorY, anchorZ);
	}

	/**
	 * Keeps the bot from starting a fight it is in no shape for — in particular right after resurrecting at 25% hp, next to whatever killed it.
	 * Only picking a fight is gated: it always defends itself, whatever its health.
	 */
	/**
	 * Sits down to heal where the bot stands, because resting recovers eight times faster than standing around. It recovers on the spot rather than
	 * walking home first: the fight is over, and a player sits down where it ended.
	 */
	private void recover() {
		if (!getOwner().getMoveController().isInMove())
			BotRestManager.sitDown(getOwner());
	}

	private boolean isHealthyEnoughToFight() {
		return getOwner().getLifeStats().getHpPercentage() >= MIN_ENGAGE_HP_PERCENT;
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
		if (moveController.continueToGoal())
			return; // the goal is further away, another leg was started
		if (!moveController.isBlocked()) // being blocked is logged where it is detected
			log.info("Bot {} arrived at destination", getOwner().getName());
	}

	@Override
	protected void handleMoveValidate() {
		// the attack tick only runs at weapon speed, far too slow to notice the target came into reach while running at it
		if (chaseStartTime != 0 && getOwner().getTarget() instanceof Creature target && BotAttackManager.isInAttackRange(getOwner(), target))
			stopMoving();
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
			reviveTask = ThreadPoolManager.getInstance().schedule(this::revive, REVIVE_DELAY_MILLIS);
		}
		Player bot = getOwner();
		// whatever it was fighting just won, so leave it alone for a while instead of walking straight back into it
		if (bot.getTarget() instanceof Creature killer)
			ignoredTargets.put(killer.getObjectId(), System.currentTimeMillis() + KILLER_AVOIDED_MILLIS);
		cancelCombat();
		setStateIfNot(AIState.DIED);
		log.info("Bot {} died at {} {} {} (anchor {} {} {})", bot.getName(), bot.getX(), bot.getY(), bot.getZ(), anchorX, anchorY, anchorZ);
	}

	/**
	 * Brings the bot back at its anchor, the way a player choosing to resurrect at an obelisk would: reduced hp and mp, soul sickness, and away from
	 * whatever killed it. Reviving on the spot would just feed it back to the same mob.
	 */
	private void revive() {
		Player bot = getOwner();
		try {
			if (!bot.isSpawned() || !bot.isDead())
				return;
			PlayerReviveService.revive(bot, 25, 25, true, 0);
			TeleportService.teleportTo(bot, bot.getWorldId(), anchorX, anchorY, anchorZ);
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
