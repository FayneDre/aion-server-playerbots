package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.List;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.stats.calc.StatOwner;
import com.aionemu.gameserver.model.stats.calc.functions.StatSetFunction;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.world.BotPlaces;

/**
 * How fast a bot advances, which is the one lever that decides whether a region stays inhabited.
 * <p>
 * Bots level, and that is the point: a resident that never advances is not a character. But the drift upwards is mechanical, not bad luck — measured
 * on this server, five bots went from their starting levels to thirteen in a single day, farming grey creatures in a valley built for levels one to
 * eight. Left at a player's pace, every low region empties itself.
 * <p>
 * The arithmetic that governs it is simple enough to design against: <b>the population of a level band is its arrival rate times how long a
 * character dwells in it.</b> Nineteen inhabitants in Poeta with a twenty hour dwell needs about one arrival an hour. Dwell time is therefore the
 * control knob, and this is where it is turned.
 * <p>
 * No core patch is needed for it. {@code Rates.XP_HUNTING} already multiplies by the {@code BOOST_HUNTING_XP_RATE} stat, where 100 means the ordinary
 * rate, so a stat function set on the character does the whole job — the same door {@code EnchantEffect} uses. A bot therefore earns experience
 * through every rule a player does, only more slowly.
 * <p>
 * One per bot, because both brakes have to be taken off and put back on while the character is playing, and knowing whether they are currently on is
 * per character. It is its own {@link StatOwner} for the same reason.
 */
public class BotPacing implements StatOwner {

	/**
	 * Percent of the ordinary hunting rate a bot earns. A quarter, so crossing a band takes the best part of a day of play rather than an hour or
	 * two: slow enough that a region keeps the inhabitants it was given, fast enough that a bot visibly has a career.
	 * <p>
	 * This is the number to change when a population drifts. Lower it and the low regions hold; raise it and the higher bands fill faster.
	 */
	private static final int HUNTING_XP_PERCENT = 25;

	private final Player bot;
	/** Whether a player made this bot for themselves. Fixed for the character's life, so it is read once rather than on every tick. */
	private final boolean owned;
	private boolean paced;

	public BotPacing(Player bot, boolean owned) {
		this.bot = bot;
		this.owned = owned;
	}

	/**
	 * Puts both brakes where the bot's current situation says they belong.
	 * <p>
	 * Called from the decision tick rather than from the events that change the answer. Joining and leaving a group happens down half a dozen paths —
	 * an invitation accepted, a kick, the leader disconnecting, the group dissolving, {@link BotGroupManager#leaveIfLeaderless} — and subscribing to
	 * all of them is how one comes to be missed and a bot stays braked for the rest of its life. Comparing what should be true against what is true
	 * cannot miss a path, and costs two comparisons a second.
	 */
	public void reconcile() {
		// Somebody's companion: a bot a player made, or one they have invited into their group for as long as they keep it there. A companion that
		// cannot keep up with the player it belongs to is not a companion, so neither brake applies to it.
		boolean companion = owned || BotGroupManager.hasPlayerMember(bot);
		pace(!companion);
		holdAtRegionCeiling(!companion);
	}

	/** Takes the character's experience rate down to the bot rate, or gives it back, doing nothing when it is already where it should be. */
	private void pace(boolean wanted) {
		if (wanted == paced)
			return;
		if (wanted)
			bot.getGameStats().addEffect(this, List.of(new StatSetFunction(StatEnum.BOOST_HUNTING_XP_RATE, HUNTING_XP_PERCENT)));
		else
			bot.getGameStats().endEffect(this);
		paced = wanted;
	}

	/**
	 * Stops a bot at the top of what its region has to offer.
	 * <p>
	 * Slowing the climb is not the same as bounding it, and only the first was in place: a bot born in a valley of ones to eights still reached the
	 * top of it and kept going, with nothing to move it on and nothing to stop it. Measured in game within the hour — a character of seven beating
	 * creatures worth two in the middle of a beginners' village.
	 * <p>
	 * A ceiling is the honest answer <b>until bots can change region</b>. What should happen is that the population director finds it somewhere its
	 * new level belongs, or lets it go and draws a replacement from the pool; what must not happen meanwhile is a region quietly filling with
	 * characters that have outgrown it.
	 * <p>
	 * It is lifted for a companion, because it is a limit of the population model and a companion is not part of that model. A player's own bot that
	 * froze at the top of the valley it was born in would be useless at exactly the point it started to matter, and the limit would arrive with
	 * nothing in game to explain it.
	 */
	private void holdAtRegionCeiling(boolean applies) {
		boolean finished = applies && bot.getLevel() >= BotPlaces.topLevelOf(bot.getWorldId());
		if (bot.getCommonData().getNoExp() != finished)
			bot.getCommonData().setNoExp(finished);
	}
}
