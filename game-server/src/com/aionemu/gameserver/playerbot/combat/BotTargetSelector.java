package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Picks what a bot should fight on its own.
 */
public class BotTargetSelector {

	private BotTargetSelector() {
	}

	/**
	 * Finds the closest hostile npc the bot could actually hit. Bots cannot move yet, so anything out of attack range is ignored rather than
	 * approached. Widen this to a search radius once navigation exists.
	 */
	public static Creature findTarget(Player bot) {
		float attackRange = bot.getGameStats().getAttackRange().getCurrent() / 1000f;
		List<Npc> candidates = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (isAttackable(bot, npc, attackRange))
				candidates.add(npc);
		});
		return candidates.stream().min(Comparator.comparingDouble(npc -> PositionUtil.getDistance(bot, npc))).orElse(null);
	}

	private static boolean isAttackable(Player bot, Npc npc, float attackRange) {
		if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc))
			return false;
		return PositionUtil.isInAttackRange(bot, npc, attackRange) && GeoService.getInstance().canSee(bot, npc);
	}
}
