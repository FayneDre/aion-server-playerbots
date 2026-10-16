package com.aionemu.gameserver.playerbot.positioning;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;

/**
 * When grouped bots take the places their classes fight from, rather than walking straight at the enemy.
 * <p>
 * Placing every bot against every monster was reported as not efficient at all: each walk round an enemy is time not spent hitting it, and a walk that
 * crosses another pack's notice pulls it. An ordinary monster or an elite dies before any of that pays off; a boss lasts long enough for its cleaves and
 * its facing to matter. So the default asks the target.
 */
public enum PositioningMode {

	/** Placed against a boss, as {@link Npc#isBoss()} has it (a hero or legendary rating); straight at anything else. */
	AUTO,
	/** Never placed: melee walks into contact by the shortest way, ranged classes and healers close only to their reach. */
	STANDARD,
	/** Always placed: the tank on the side away from the group, melee behind the enemy, ranged back from it, the healer within reach of everybody. */
	SPREAD;

	/** @return true if bots fighting this enemy take their places. */
	public boolean placesAgainst(Creature enemy) {
		return switch (this) {
			case STANDARD -> false;
			case SPREAD -> true;
			case AUTO -> enemy instanceof Npc npc && npc.isBoss();
		};
	}
}
