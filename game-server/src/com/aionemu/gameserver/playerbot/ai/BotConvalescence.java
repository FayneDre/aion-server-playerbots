package com.aionemu.gameserver.playerbot.ai;

import java.util.function.Predicate;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.BindPointPosition;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Where a bot spends the soul sickness that follows a death.
 * <p>
 * The penalty takes a large bite out of every stat for minutes, and the bot comes back from the dead at an obelisk. Two things were tried and both
 * failed. Walking straight back to the farm ground put a crippled bot among the mobs that had just killed it, and it died again, in a loop. Waiting
 * where it stood fixed that and filled every obelisk in the region with seated bots. A player does neither: they step away from the plinth and wait
 * somewhere quiet.
 * <p>
 * So the bot gets a spot of its own, a fair way off the obelisk, on ground joined to it, with no hostile creature near enough to notice it and nobody
 * standing on it. The spot is fixed to the bot's id, so that a crowd of corpses coming back at once spreads out instead of queueing on the same few
 * metres. When the sickness is over the bot goes back to its anchor like any other.
 */
final class BotConvalescence {

	/** How far around the obelisk a spot may be. The nearest third is skipped by {@code BotPlaces.spotAround}, which keeps it off the plinth. */
	private static final float SPREAD = 45f;
	/** How many spots to try. Each costs a path search, and a spot is refused for mobs, for company and for being cut off. */
	private static final int ATTEMPTS = 16;
	/** How near a hostile creature a spot may be. Past the aggro range of nearly everything that walks the open world, sleeping or not. */
	private static final float MOB_CLEARANCE = 25f;
	/** How far from its obelisk a bot is still worth walking back to it. Past that the bot stays where it is rather than cross the country sick. */
	private static final float MAX_DISTANCE = 150f;

	private final Player bot;
	private final Predicate<Vector3f> crowded;
	private BindPointPosition chosenFor;
	private Vector3f spot;

	/**
	 * @param crowded Whether somebody is already standing at a spot, which the bot's day already knows how to answer.
	 */
	BotConvalescence(Player bot, Predicate<Vector3f> crowded) {
		this.bot = bot;
		this.crowded = crowded;
	}

	/**
	 * @return The bot's place to wait, or null when it has none: no obelisk on this map, one too far away, or no room worth the name around it.
	 */
	synchronized Vector3f spot() {
		BindPointPosition bind = bot.getBindPoint();
		if (bind == null || bind.getMapId() != bot.getWorldId())
			return null;
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bind.getX(), bind.getY()) > MAX_DISTANCE)
			return null;
		if (spot != null && chosenFor == bind && !hostileNear(spot))
			return spot;
		chosenFor = bind;
		Vector3f obelisk = new Vector3f(bind.getX(), bind.getY(), bind.getZ());
		spot = BotPlaces.spotAround(bot.getWorldId(), obelisk, SPREAD, ATTEMPTS, bot.getObjectId(), ground -> crowded.test(ground) || hostileNear(ground));
		return spot;
	}

	/** Forgets the spot, because the sickness is over and the next death should choose again among whatever has changed by then. */
	synchronized void forget() {
		spot = null;
		chosenFor = null;
	}

	private boolean hostileNear(Vector3f ground) {
		boolean[] found = { false };
		bot.getKnownList().forEachNpc(npc -> {
			if (!found[0] && isThreat(npc) && PositionUtil.getDistance(npc.getX(), npc.getY(), ground.getX(), ground.getY()) < MOB_CLEARANCE)
				found[0] = true;
		});
		return found[0];
	}

	private boolean isThreat(Npc npc) {
		return !npc.isDead() && bot.isEnemy(npc) && !BotTargetSelector.isScenery(npc);
	}
}
