package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * A pull from range on the player's order ({@code //bot pull}): the group's tank goes within reach of the monster the player targets, strikes it from
 * as far as one of its skills allows, a taunt first, and walks back to where the player stood when they asked, the monster after it. The rest of the
 * group holds until the monster is there, so that the fight happens where the group stands rather than where the monster did.
 * <p>
 * Starting a fight stays the player's ({@code PlayerBotAI.serveTheGroup}): this is the player starting one, through the tank. Nothing pulls on its own.
 */
public final class BotPull {

	private static final Logger log = LoggerFactory.getLogger(BotPull.class);

	/** How far from the tank a monster may be to be pulled: further and the walk out is a journey, and the monster is nobody's fight yet. */
	static final float MAX_PULL_DISTANCE = 50f;
	/** How near the spot the tank stops walking back: there it turns and fights. */
	static final float BACK_AT_THE_SPOT = 3f;
	/** How near the spot the monster must be before the others join in. */
	static final float BROUGHT_WITHIN = 10f;
	/** How long a pull may last, from the order to the tank back at the spot, before it is given up and the fight is fought wherever it stands. */
	static final long GIVE_UP_AFTER_MILLIS = 30_000;
	/** How far the place a walk is aimed at may drift before the walk is planned again: a plan every tick turns the bot a little each time. */
	private static final float RETARGET_STEP = 2f;

	/** The pulls under way, by the object id of the tank that pulls. */
	private static final Map<Integer, Pull> PULLS = new ConcurrentHashMap<>();

	/** One pull: whom, back to where, since when, and whether the monster has been struck yet. */
	static final class Pull {

		final Creature target;
		final float x, y, z;
		final int mapId, instanceId;
		final long startedAt;
		volatile boolean struck;

		Pull(Creature target, Player spot, long startedAt) {
			this.target = target;
			this.x = spot.getX();
			this.y = spot.getY();
			this.z = spot.getZ();
			this.mapId = spot.getWorldId();
			this.instanceId = spot.getInstanceId();
			this.startedAt = startedAt;
		}
	}

	private BotPull() {
	}

	/**
	 * The player's order: the monster they target, pulled by their group's tank to where they stand.
	 *
	 * @return The line to answer the player with.
	 */
	public static String order(Player player) {
		TemporaryPlayerTeam<?> team = player.getCurrentTeam();
		if (team == null)
			return "You are in no group: a pull is the group tank's.";
		Player tank = BotRole.groupTank(team);
		if (tank == null || !tank.isBot())
			return tank == null ? "Your group has no tank standing." : "Your group's tank is " + tank.getName() + ", who is not a bot.";
		if (!(player.getTarget() instanceof Npc target) || target.isDead() || !tank.isEnemy(target))
			return "Target the monster to pull.";
		String refused = refusal(tank, target, player);
		if (refused != null)
			return refused;
		PULLS.put(tank.getObjectId(), new Pull(target, player, System.currentTimeMillis()));
		log.debug("{} pulls {} back to {} on {}'s order", tank.getName(), target.getName(), player.getPosition(), player.getName());
		return tank.getName() + " pulls " + target.getName() + ".";
	}

	/** @return Why the tank cannot pull that monster to that player, or null when it can. */
	private static String refusal(Player tank, Creature target, Player player) {
		if (tank.getWorldId() != target.getWorldId() || tank.getInstanceId() != target.getInstanceId() || player.getWorldId() != tank.getWorldId())
			return tank.getName() + " is not where you are.";
		if (PositionUtil.getDistance(tank, target) > MAX_PULL_DISTANCE)
			return target.getName() + " is too far from " + tank.getName() + " to pull.";
		if (tank.isDead())
			return tank.getName() + " is dead.";
		return null;
	}

	/**
	 * Runs the tank's part of a pull, on its decision tick: out within reach, the strike, the walk back.
	 *
	 * @return true while the pull is under way, in which case nothing else is decided for the tank this tick.
	 */
	static boolean drive(Player tank, PlayerBotAI ai) {
		Pull pull = PULLS.get(tank.getObjectId());
		if (pull == null)
			return false;
		if (isOver(tank, pull, System.currentTimeMillis())) {
			PULLS.remove(tank.getObjectId(), pull);
			return false;
		}
		if (!(tank.getMoveController() instanceof BotMoveController mover))
			return false;
		Creature target = pull.target;
		if (!pull.struck && target.getAggroList().isHating(tank))
			pull.struck = true; // it came of its own accord, or a blow before the order did it
		Step step = step(pull.struck, PositionUtil.getDistance(tank, target), BotSkillManager.pullReach(tank),
			PositionUtil.getDistance(tank.getX(), tank.getY(), pull.x, pull.y));
		switch (step) {
			case APPROACH -> {
				if (ai.isAttacking())
					ai.cancelCombat();
				if (!mover.isInMove() || !mover.isHeadingTo(target.getX(), target.getY(), RETARGET_STEP))
					ai.tryMoveTo(mover, target.getX(), target.getY(), target.getZ());
			}
			case STRIKE -> {
				if (BotSkillManager.tryPull(tank, target))
					pull.struck = true;
				else if (!ai.isAttacking() && PositionUtil.getDistance(tank, target) <= BotSkillManager.weaponReach(tank))
					ai.startAttacking(target); // nothing ready that reaches: a blow, which the walk back then breaks off
				else if (!ai.isAttacking())
					ai.stopMoving(); // in reach of a skill that is a moment from ready
			}
			case RETURN -> {
				if (ai.isAttacking())
					ai.cancelCombat(); // a blow struck on the way back would stop the walk where the monster caught up
				if (!mover.isInMove() || !mover.isHeadingTo(pull.x, pull.y, RETARGET_STEP))
					ai.tryMoveTo(mover, pull.x, pull.y, pull.z);
			}
			case DONE -> {
				PULLS.remove(tank.getObjectId(), pull);
				ai.stopMoving();
				return false; // back: the fight is the tank's as usual, the monster at its heels
			}
		}
		return true;
	}

	/** What the tank does next in a pull. */
	enum Step {
		/** Walks towards the monster, still out of reach. */
		APPROACH,
		/** Strikes it from where it stands. */
		STRIKE,
		/** Walks back to the spot, the monster after it. */
		RETURN,
		/** Back at the spot: the pull is over. */
		DONE
	}

	/**
	 * @param reach How far the tank can strike from now ({@link BotSkillManager#pullReach}): its weapon's at the least, so a tank with nothing ready
	 *          walks up and hits it.
	 * @return The next step, from whether the monster has been struck and the distances to it and to the spot.
	 */
	static Step step(boolean struck, double toTarget, float reach, double toSpot) {
		if (!struck)
			return toTarget <= reach ? Step.STRIKE : Step.APPROACH;
		return toSpot <= BACK_AT_THE_SPOT ? Step.DONE : Step.RETURN;
	}

	/** @return true if the pull has nothing left to do: the monster gone or dead, the tank dead, on another map or out of time. */
	static boolean isOver(Player tank, Pull pull, long now) {
		Creature target = pull.target;
		return target.isDead() || !target.isSpawned() || tank.isDead() || tank.getWorldId() != pull.mapId || tank.getInstanceId() != pull.instanceId
			|| now - pull.startedAt > GIVE_UP_AFTER_MILLIS;
	}

	/**
	 * @return true if the member must leave that enemy alone for now: it is the one its group's tank is pulling, and not yet brought within reach of
	 *         where the group stands. Joining it there would be the fight the pull is meant to move.
	 */
	static boolean holdsBack(Player member, Creature enemy) {
		Player tank = BotRole.groupTank(member.getCurrentTeam());
		if (tank == null || tank.equals(member))
			return false;
		Pull pull = PULLS.get(tank.getObjectId());
		if (pull == null || !pull.target.equals(enemy) || isOver(tank, pull, System.currentTimeMillis()))
			return false;
		return PositionUtil.getDistance(enemy.getX(), enemy.getY(), pull.x, pull.y) > BROUGHT_WITHIN;
	}

	/** Forgets the tank's pull, as it leaves the world. */
	public static void forget(Player tank) {
		PULLS.remove(tank.getObjectId());
	}
}
