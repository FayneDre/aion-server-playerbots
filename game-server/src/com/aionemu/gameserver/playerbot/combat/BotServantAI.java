package com.aionemu.gameserver.playerbot.combat;

import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Summon;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.NpcSkillEntry;
import com.aionemu.gameserver.model.skill.NpcSkillList;
import com.aionemu.gameserver.model.summons.UnsummonType;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.movement.BotServantMoveController;
import com.aionemu.gameserver.services.summons.SummonsService;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Drives a bot's servant: it follows its master, defends it, and fights what it fights.
 * <p>
 * Nothing on the server does any of this. A summon's movement and its orders all belong to the master's client — {@code CM_SUMMON_MOVE},
 * {@code CM_SUMMON_ATTACK}, {@code CM_SUMMON_CASTSPELL} — and {@code VisibleObjectSpawner} only starts an ai for siege weapons, so a bot's spirit was
 * called up and then stood where it appeared until it was released. A spirit master without its spirit is not a weaker spirit master: it is a cloth
 * caster with no damage.
 * <p>
 * A tick rather than an {@code ai/handler} class, for two reasons already settled in this project: every handler there is typed {@code NpcAI} and a
 * {@link Summon} extends {@code Creature} directly, and the engine never starts a summon's ai anyway, so there would be nothing to hook into.
 * <p>
 * <b>Only ever a bot's.</b> A real player's pet is driven entirely by their client, and a second hand on the same object shows as rubber banding, so
 * every entry point here is gated on the master being a bot.
 */
public class BotServantAI implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(BotServantAI.class);

	/** Twice a second. Faster than the bot's own decision tick, because the servant's whole job is to not be left behind. */
	private static final long TICK_MILLIS = 500;
	/** How far the servant may drift from its master before it walks after it. About where a player's pet sits. */
	private static final float FOLLOW_DISTANCE = 5f;
	/** Close enough to stop walking. Smaller than {@link #FOLLOW_DISTANCE}, so arriving does not immediately count as drifting again. */
	private static final float ARRIVED_DISTANCE = 3f;
	/**
	 * Where the engine itself gives up on a servant — {@code FollowSummonTaskAI} releases one further than this from its master. Kept rather than
	 * inherited, since that task is not used here: it fires move events at an ai that is never started.
	 */
	private static final float LEASH_DISTANCE = 50f;
	/** Beyond this from its master, a fight is not the servant's business however much it would like it to be. */
	private static final float ENGAGE_DISTANCE = 25f;
	/** Slack on the swing timer. {@code SummonController.attackTarget} audits anything faster than the weapon allows, as a speed hack. */
	private static final long SWING_MARGIN_MILLIS = 100;
	/** How long the servant waits between two attempts at a skill, so a spell on cooldown is not asked for twice a second. */
	private static final long CAST_INTERVAL_MILLIS = 3000;

	private final Summon servant;
	private final Player master;
	private long nextSwing;
	private long nextCast;

	private BotServantAI(Summon servant, Player master) {
		this.servant = servant;
		this.master = master;
	}

	/**
	 * Puts a bot's servant under server control, and keeps it there.
	 * <p>
	 * Idempotent, and asked on the bot's tick rather than at the moment of summoning, because the servant does not exist yet when the cast is made —
	 * the effect creates it when the cast ends.
	 */
	public static void take(Player bot) {
		Summon servant = bot.getSummon();
		if (servant == null || !bot.isBot() || bot.getController().hasScheduledTask(TaskId.SUMMON_FOLLOW))
			return;
		// The engine's own controller for a summon has an empty move method, so the servant is given one that walks. Through setMoveController,
		// which is one of the two core seams this module already owns.
		if (!(servant.getMoveController() instanceof BotServantMoveController))
			servant.setMoveController(new BotServantMoveController(servant));
		ScheduledFuture<?> tick = BotScheduler.getInstance().scheduleAtFixedRate(new BotServantAI(servant, bot), TICK_MILLIS, TICK_MILLIS);
		bot.getController().addTask(TaskId.SUMMON_FOLLOW, tick);
		log.debug("Servant of {} is now driven by the server", bot.getName());
	}

	@Override
	public void run() {
		try {
			if (isGone())
				return;
			if (hasFallenBehind())
				return;
			Creature enemy = defend();
			if (enemy == null)
				enemy = assist();
			if (enemy == null)
				return;
			engage(enemy);
		} catch (Exception e) {
			log.error("Servant of " + master.getName() + " failed its tick", e);
		}
	}

	/** @return true if there is nothing left to drive, in which case the tick takes itself off. */
	private boolean isGone() {
		if (servant.isSpawned() && !servant.isDead() && master.isSpawned() && !master.isDead() && master.getSummon() == servant)
			return false;
		master.getController().cancelTask(TaskId.SUMMON_FOLLOW);
		return true;
	}

	/**
	 * Walks the servant back to its master, and releases it where that is hopeless.
	 *
	 * @return true if the servant is on its way, in which case it does nothing else this tick. Following outranks fighting on purpose: a servant that
	 *         stops for whatever it passes is one that gets left behind and released, which is the failure the leash exists to catch.
	 */
	private boolean hasFallenBehind() {
		double distance = PositionUtil.getDistance(servant, master);
		if (distance > LEASH_DISTANCE) {
			SummonsService.release(servant, UnsummonType.DISTANCE);
			return true;
		}
		if (!(servant.getMoveController() instanceof BotServantMoveController moveController))
			return false;
		if (distance > FOLLOW_DISTANCE) {
			moveController.headFor(master.getX(), master.getY(), master.getZ());
			return true;
		}
		if (distance <= ARRIVED_DISTANCE)
			moveController.stop();
		return false;
	}

	/**
	 * @return Whatever has decided to kill the servant or its master, or null.
	 *         <p>
	 *         The aggro list and not {@code getTarget()}, a lesson this project has already paid for twice: a creature closing in, mid cast, or
	 *         briefly aimed elsewhere is going to kill you all the same, and says anything but your name while it does.
	 */
	private Creature defend() {
		Creature[] found = { null };
		servant.getKnownList().forEachNpc(npc -> {
			if (found[0] == null && !npc.isDead() && servant.isEnemy(npc)
				&& (npc.getAggroList().isHating(master) || npc.getAggroList().isHating(servant)))
				found[0] = npc;
		});
		return found[0];
	}

	/** @return What the master is fighting, if the servant can reach it. An intention, which is why it is asked after the facts. */
	private Creature assist() {
		if (!(master.getTarget() instanceof Creature target) || target.isDead() || !servant.isEnemy(target))
			return null;
		return PositionUtil.getDistance(servant, target) > ENGAGE_DISTANCE ? null : target;
	}

	/** Closes on the enemy, swings at its own weapon speed, and casts what it knows. */
	private void engage(Creature enemy) {
		servant.setTarget(enemy);
		long now = System.currentTimeMillis();
		if (cast(enemy, now))
			return;
		if (!PositionUtil.isInAttackRange(servant, enemy, servant.getGameStats().getAttackRange().getCurrent() / 1000f)) {
			if (servant.getMoveController() instanceof BotServantMoveController moveController)
				moveController.headFor(enemy.getX(), enemy.getY(), enemy.getZ());
			return;
		}
		if (servant.getMoveController() instanceof BotServantMoveController moveController)
			moveController.stop();
		if (now < nextSwing)
			return;
		// Paced by the servant's own attack speed rather than by the tick, because SummonController.attackTarget audits anything faster as a speed
		// hack -- and writes that to the server log and to every administrator's chat, which is how a few hundred bots turn a fight into a wall of
		// text. The same trap the stigma work hit from the other side.
		nextSwing = now + servant.getGameStats().getAttackSpeed().getCurrent() + SWING_MARGIN_MILLIS;
		servant.getController().attackTarget(enemy, 0, false);
	}

	/**
	 * Casts one of the servant's own skills.
	 * <p>
	 * Read from {@code npc_skills.xml} through {@code NPC_SKILL_DATA}, which is where a summon's skills are: the first entry in that file is npc
	 * 201010, a summon. Not {@code pet_skills.xml}, despite {@code SummonController.useSkill} checking against it — everything in there is a toy pet
	 * of the 833000 range, and none of the combat summons appears in it, so that path can never fire for a spirit.
	 *
	 * @return true if a cast started, in which case the servant is rooted for it and does nothing else.
	 */
	private boolean cast(Creature enemy, long now) {
		if (now < nextCast || servant.isCasting())
			return false;
		nextCast = now + CAST_INTERVAL_MILLIS;
		NpcSkillList known = DataManager.NPC_SKILL_DATA.getOrCreateNpcSkillList(servant.getObjectTemplate().getTemplateId());
		if (known.isEmpty())
			return false;
		NpcSkillEntry entry = known.getRandomSkill();
		// The data states when each skill is worth using -- a chance, a health threshold, a time into the fight -- and that statement is honoured
		// rather than replaced by a rule of our own, exactly as the bot's own skill choice honours the stated power.
		if (entry == null || !entry.isReady(servant.getLifeStats().getHpPercentage(), 0))
			return false;
		Skill skill = SkillEngine.getInstance().getSkill(servant, entry.getSkillId(), entry.getSkillLevel(), enemy);
		return skill != null && skill.useSkill();
	}
}
