package com.aionemu.gameserver.playerbot.ai;

import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AITemplate;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Brain of a player bot. The engine fires creature events on whatever AI is attached to a creature, so a bot receives ATTACK, MOVE_ARRIVED and
 * ATTACK_COMPLETE for free, unlike the NpcAI-typed handlers in {@code ai/handler} which cannot be reused here.
 */
public class PlayerBotAI extends AITemplate<Player> {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	private static final int THINK_INTERVAL_MILLIS = 1000;

	/** Guards the scheduled tasks against concurrent starts and stops, since events and ticks run on different pool threads. */
	private final Object combatLock = new Object();
	private ScheduledFuture<?> attackTask;
	private ScheduledFuture<?> thinkTask;
	private volatile boolean autonomous = true;

	public PlayerBotAI(Player owner) {
		super(owner);
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
		if (autonomous && !isAttacking() && !getOwner().isDead() && getOwner().isSpawned()) {
			Creature target = BotTargetSelector.findTarget(getOwner());
			if (target != null)
				startAttacking(target);
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

		if (bot.getCastingSkill() == null && !BotSkillManager.tryCastSkill(bot, target))
			BotAttackManager.autoAttack(bot, target);

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

	@Override
	public boolean isDestinationReached() {
		// AITemplate returns false by default, which would prevent MoveTaskManager from ever firing MOVE_ARRIVED
		return !(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isArrived();
	}

	@Override
	protected void handleMoveArrived() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.continueToGoal())
			return; // the goal is further away, another leg was started
		log.info("Bot {} arrived at destination", getOwner().getName());
	}

	@Override
	protected void handleSpawned() {
		// without leaving AIState.CREATED, every event except (BEFORE_)SPAWNED is filtered out
		setStateIfNot(AIState.IDLE);
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
		cancelCombat();
		setStateIfNot(AIState.DIED);
		log.info("Bot {} died", getOwner().getName());
	}

	@Override
	protected void handleDespawned() {
		synchronized (combatLock) {
			if (thinkTask != null) {
				thinkTask.cancel(false);
				thinkTask = null;
			}
		}
		cancelCombat();
		setStateIfNot(AIState.DESPAWNED);
		log.info("Bot {} despawned", getOwner().getName());
	}
}
