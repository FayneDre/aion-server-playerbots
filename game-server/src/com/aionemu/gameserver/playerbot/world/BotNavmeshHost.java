package com.aionemu.gameserver.playerbot.world;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.services.TribeRelationService;

/**
 * The world as navigation asks about it, answered by the classes that know it. Each answer is the one {@link NavmeshService} used to ask for itself
 * before 27 October 2026, moved here so that navigation depends on nothing above it.
 */
public final class BotNavmeshHost implements NavmeshService.Host {

	@Override
	public boolean isTooStrongFor(Player bot, Npc npc) {
		return npc.getLevel() > bot.getLevel() + BotTargetSelector.MAX_LEVEL_GAP && bot.isEnemy(npc) && TribeRelationService.isAggressive(npc, bot);
	}

	@Override
	public boolean isSiegeGround(int worldId, float x, float y, float z) {
		return BotPlaces.isSiegeGround(worldId, x, y, z);
	}

	@Override
	public Race ownerOfGround(int worldId, int region) {
		return BotPlaces.ownerOfGround(worldId, region);
	}

	@Override
	public boolean canCross(Player bot, Vector3f from, Vector3f to) {
		return BotFlight.crossing(bot, from, to) != null;
	}

	@Override
	public void planRoute(Runnable search) {
		BotScheduler.getInstance().planRoute(search);
	}
}
