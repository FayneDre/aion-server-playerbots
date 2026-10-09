package com.aionemu.gameserver.playerbot.social;

import java.util.HashMap;
import java.util.Map;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.world.World;

/**
 * The skull: how a group agrees on one target without a leader having to click it.
 * <p>
 * The tank marks the enemy with the least health when the fight begins, the rest of the group follows the mark, and when the marked one dies the
 * tank marks the next. That is the guide's rule for a group, and the mark is the engine's own brand (the {@code /Brand} command), so a real player in
 * the group sees the same skull over the same monster.
 * <p>
 * Only the skull counts. Any other brand on the board is somebody else's business and does not stop the tank from placing it; a skull that is
 * already on a live enemy is never moved, whoever put it there — so a leader who marks something by hand is obeyed.
 */
public final class BotMarks {

	/** The skull: brand 14, which is the one the guide named. */
	public static final int SKULL = 14;

	private BotMarks() {
	}

	/**
	 * @return The enemy carrying the skull, or null when there is none to follow: no mark, or a mark on something dead, out of sight or not an enemy.
	 *         Whether it is fighting the group is deliberately not asked: the guide has a player mark an enemy even out of combat, to make it the main
	 *         target, and a group that would follow only marks on monsters already engaged could not be told to start.
	 */
	public static Creature skulled(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		int markedId = team.getBrandedTarget(SKULL);
		if (markedId == 0)
			return null;
		Npc[] found = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.getObjectId() == markedId && !npc.isDead() && npc.isSpawned() && bot.isEnemy(npc) && bot.canSee(npc))
				found[0] = npc;
		});
		return found[0];
	}

	/**
	 * Marks the enemy with the least health, unless the skull is already on a live one.
	 * <p>
	 * Called by the tank as it fights and as it waits for the next thing to fight, so the mark moves on the moment its target dies. With nobody left
	 * to mark, a skull that is still up over a corpse is taken down, because real players in the group are looking at it.
	 */
	public static void markWeakestIfNone(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null || skulled(bot) != null || isMarkedAndAlive(team))
			return;
		Creature weakest = weakestEngaged(bot);
		if (weakest != null)
			team.updateBrand(SKULL, weakest.getObjectId());
		else if (team.getBrandedTarget(SKULL) != 0)
			team.updateBrand(SKULL, 0);
	}

	/**
	 * @return true if the skull is on something that is still alive, wherever it is. The bot can only see part of the world, so a mark it cannot find may
	 *         be one a player put on something out of its sight, and that is not the bot's to take down or to replace.
	 */
	private static boolean isMarkedAndAlive(TemporaryPlayerTeam<?> team) {
		int markedId = team.getBrandedTarget(SKULL);
		return markedId != 0 && World.getInstance().findVisibleObject(markedId) instanceof Creature marked && !marked.isDead();
	}

	/**
	 * @return The enemy fighting this group that has the least health in absolute terms, or null when none is. The second of the guide's two rules for the
	 *         group's target, and the one the tank marks with the skull so that the first rule then carries it.
	 */
	public static Creature weakestEngaged(Player bot) {
		Map<Integer, Npc> byId = new HashMap<>();
		Map<Integer, Long> healthById = new HashMap<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (BotGroupManager.isEngagedWithTheGroup(bot, npc)) {
				byId.put(npc.getObjectId(), npc);
				healthById.put(npc.getObjectId(), npc.getLifeStats().getCurrentHp() * 1L);
			}
		});
		return byId.get(weakestOf(healthById));
	}

	/**
	 * @param healthById Current health of every enemy fighting the group, by object id.
	 * @return The one with the least, or 0 for none. Absolute health and not a percentage, as the guide says: the monster with the smallest number dies
	 *         first whatever fraction of its bar that is. Ties go to the lower id, so two bots asking the same question in the same tick agree.
	 */
	static int weakestOf(Map<Integer, Long> healthById) {
		int weakest = 0;
		long least = Long.MAX_VALUE;
		for (Map.Entry<Integer, Long> entry : healthById.entrySet()) {
			long health = entry.getValue();
			if (health < least || health == least && entry.getKey() < weakest) {
				least = health;
				weakest = entry.getKey();
			}
		}
		return weakest;
	}
}
