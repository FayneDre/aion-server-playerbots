package com.aionemu.gameserver.playerbot.combat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.world.World;

/**
 * Who is fighting what, among bots.
 * <p>
 * Reading {@code npc.getTarget()} is not enough to tell a mob is taken: it is only set once the mob retaliates, about a second after the first hit.
 * Two bots ticking within that window both see a free mob and both engage it. This registry closes the gap by having a bot claim its target before
 * attacking, which is an atomic operation.
 */
public class BotTargetRegistry {

	/** Target object id to the object id of the bot fighting it. */
	private static final Map<Integer, Integer> owners = new ConcurrentHashMap<>();

	private BotTargetRegistry() {
	}

	/**
	 * Tries to take the target for the bot, without stealing one another bot already has.
	 *
	 * @return false if another bot got there first, in which case this bot must pick something else.
	 */
	public static boolean claim(Creature target, Player bot) {
		Integer owner = owners.putIfAbsent(target.getObjectId(), bot.getObjectId());
		return owner == null || owner == bot.getObjectId();
	}

	/** Takes the target whatever another bot is doing, for fights the bot did not choose: retaliation and admin commands. */
	public static void forceClaim(Creature target, Player bot) {
		owners.put(target.getObjectId(), bot.getObjectId());
	}

	public static void releaseAllOf(Player bot) {
		owners.values().removeIf(owner -> owner == bot.getObjectId());
	}

	/**
	 * @return The bot fighting that target, or null if it is free or its claimer is gone.
	 */
	public static Player getOwner(Creature target) {
		Integer ownerId = owners.get(target.getObjectId());
		if (ownerId == null)
			return null;
		Player owner = World.getInstance().getPlayer(ownerId);
		if (owner == null) // despawned without releasing, which the leave path normally prevents
			owners.remove(target.getObjectId(), ownerId);
		return owner;
	}
}
