package com.aionemu.gameserver.playerbot.social;

import java.util.HashMap;
import java.util.Map;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;

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
	 * @return The enemy carrying the skull, or null when there is none worth following: no mark, a mark on something already dead, or on something
	 *         that is not fighting this group. The last matters because a mark on a monster nobody has engaged would send the whole group to pull it.
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
			if (npc.getObjectId() == markedId && BotGroupManager.isEngagedWithTheGroup(bot, npc))
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
		if (team == null || skulled(bot) != null)
			return;
		Map<Integer, Long> healthById = new HashMap<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (BotGroupManager.isEngagedWithTheGroup(bot, npc))
				healthById.put(npc.getObjectId(), npc.getLifeStats().getCurrentHp() * 1L);
		});
		int weakest = weakestOf(healthById);
		if (weakest != 0)
			team.updateBrand(SKULL, weakest);
		else if (team.getBrandedTarget(SKULL) != 0)
			team.updateBrand(SKULL, 0);
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
