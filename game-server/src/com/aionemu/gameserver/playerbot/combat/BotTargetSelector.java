package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Picks what a bot should fight on its own.
 */
public class BotTargetSelector {

	/** How far a bot is willing to walk to engage something. */
	public static final float CHASE_RADIUS = 25f;
	/** Height difference above which a target is treated as being on another level (a cliff, a roof, the floor below) and left alone. */
	private static final float MAX_Z_DELTA = 8f;

	private BotTargetSelector() {
	}

	/**
	 * Finds the closest hostile npc worth engaging, within walking distance rather than within weapon reach.
	 *
	 * @param isIgnored Targets the bot already failed to reach, so it does not immediately pick them again.
	 */
	public static Creature findTarget(Player bot, Predicate<Creature> isIgnored) {
		List<Npc> candidates = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (isAttackable(bot, npc) && !isIgnored.test(npc))
				candidates.add(npc);
		});
		return candidates.stream().min(Comparator.comparingDouble(npc -> PositionUtil.getDistance(bot, npc))).orElse(null);
	}

	private static boolean isAttackable(Player bot, Npc npc) {
		if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc))
			return false;
		if (Math.abs(bot.getZ() - npc.getZ()) > MAX_Z_DELTA)
			return false;
		// line of sight is not just a targeting rule here: without it the bot would walk towards things behind walls it cannot reach
		return PositionUtil.getDistance(bot, npc) <= CHASE_RADIUS && GeoService.getInstance().canSee(bot, npc);
	}
}
