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
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Brain of a player bot. The engine fires creature events on whatever AI is attached to a creature, so a bot receives ATTACK, MOVE_ARRIVED and
 * ATTACK_COMPLETE for free, unlike the NpcAI-typed handlers in {@code ai/handler} which cannot be reused here.
 */
public class PlayerBotAI extends AITemplate<Player> {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	/** Guards the attack task against concurrent starts and stops, since events and ticks run on different pool threads. */
	private final Object combatLock = new Object();
	private ScheduledFuture<?> attackTask;

	public PlayerBotAI(Player owner) {
		super(owner);
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
	protected void handleSpawned() {
		// without leaving AIState.CREATED, every event except (BEFORE_)SPAWNED is filtered out
		setStateIfNot(AIState.IDLE);
		log.info("Bot {} spawned", getOwner().getName());
	}

	@Override
	protected void handleAttack(Creature attacker) {
		log.info("Bot {} attacked by {}", getOwner().getName(), attacker.getName());
	}

	@Override
	protected void handleDied() {
		cancelCombat();
		setStateIfNot(AIState.DIED);
		log.info("Bot {} died", getOwner().getName());
	}

	@Override
	protected void handleDespawned() {
		cancelCombat();
		setStateIfNot(AIState.DESPAWNED);
		log.info("Bot {} despawned", getOwner().getName());
	}
}
