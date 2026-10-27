package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * What one bot remembers of its fight from one tick to the next, for the class playbooks. A playbook is shared by every bot of its class, so what one bot
 * has been doing cannot live in it; it lived in {@code PlayerBotAI}, which made every playbook that needed it depend on the AI that calls it. The AI owns
 * this now and hands it over through {@link Holder}, and a playbook asks {@link #of} without knowing what an AI is.
 * <p>
 * Every field but the health record is volatile, as it was in the AI: they are written by the decision tick and read by it, and the engine's events
 * reach a bot on other threads.
 */
public final class BotCombatMemory {

	/** What owns a bot's combat memory: its AI. */
	public interface Holder {

		BotCombatMemory combatMemory();
	}

	/**
	 * When the bot was last seen stunned, knocked about, spun or held in the air. The engine says whether a bot is controlled now and never when it
	 * last was, which is what a class that answers it afterwards needs.
	 */
	private volatile long lastControlledAt;
	/**
	 * Whether the bot has begun healing itself back to full after a critical moment and is to keep at it. A rule that says "until full" has to remember that
	 * it began, since the health it began at is not the health it is at a cast later.
	 */
	private volatile boolean mendingToFull;
	/** What the members of the group have lost over the last half minute, for the class that rations its heals by it. Touched by the decision tick only. */
	private final HealthRecord healthRecord = new HealthRecord();
	/** Since when a grouped ranger has held its fire because its target turned on it, or 0, and until when it will not start again. */
	private volatile long holdingFireSince;
	private volatile long holdFireRearmAt;
	/** The enemy the bot has already spent its opening handicap on, so that the opener is once per enemy and not once per tick. */
	private volatile int openedOn;

	/** @return The bot's combat memory, or null for a character that is not driven by a bot's AI. */
	public static BotCombatMemory of(Player bot) {
		return bot.getAi() instanceof Holder holder ? holder.combatMemory() : null;
	}

	/** @return When the bot was last seen crowd controlled, in milliseconds, or 0 if it has not been. */
	public long lastControlledAt() {
		return lastControlledAt;
	}

	public void setLastControlledAt(long at) {
		this.lastControlledAt = at;
	}

	/** @return The record of how much health each member of the group has lost lately, which a healer fills as it reads the group and asks about. */
	public HealthRecord healthRecord() {
		return healthRecord;
	}

	/** @return true if the bot is in the middle of healing itself back to full. See {@link #mendingToFull}. */
	public boolean isMendingToFull() {
		return mendingToFull;
	}

	public void setMendingToFull(boolean mendingToFull) {
		this.mendingToFull = mendingToFull;
	}

	/** @return Since when the bot has been holding its fire, in milliseconds, or 0 when it is not. */
	public long holdingFireSince() {
		return holdingFireSince;
	}

	/** @return The time before which it will not begin to hold its fire again. */
	public long holdFireRearmAt() {
		return holdFireRearmAt;
	}

	public void setHoldingFire(long since, long rearmAt) {
		this.holdingFireSince = since;
		this.holdFireRearmAt = rearmAt;
	}

	/** @return The object id of the enemy the opening handicap was cast on, or 0. */
	public int openedOn() {
		return openedOn;
	}

	public void setOpenedOn(int objectId) {
		this.openedOn = objectId;
	}
}
