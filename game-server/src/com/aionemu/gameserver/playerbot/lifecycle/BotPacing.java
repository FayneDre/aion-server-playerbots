package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.List;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.stats.calc.StatOwner;
import com.aionemu.gameserver.model.stats.calc.functions.StatSetFunction;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.playerbot.world.BotPlaces;

/**
 * How fast a bot advances, which is the one lever that decides whether a region stays inhabited.
 * <p>
 * Bots level, and that is the point: a resident that never advances is not a character. But the drift upwards is mechanical, not bad luck — measured
 * on this server, five bots went from their starting levels to thirteen in a single day, farming grey creatures in a valley built for levels one to
 * eight. Left at a player's pace, every low region empties itself.
 * <p>
 * The arithmetic that governs it is simple enough to design against: <b>the population of a level band is its arrival rate times how long a
 * character dwells in it.</b> Forty five inhabitants in Poeta with a twenty hour dwell needs 2.25 arrivals an hour. Dwell time is therefore the
 * control knob, and this is where it is turned.
 * <p>
 * No core patch is needed for it. {@code Rates.XP_HUNTING} already multiplies by the {@code BOOST_HUNTING_XP_RATE} stat, where 100 means the ordinary
 * rate, so a stat function set on the character does the whole job — the same door {@code EnchantEffect} uses. A bot therefore earns experience
 * through every rule a player does, only more slowly.
 */
public class BotPacing implements StatOwner {

	/**
	 * Percent of the ordinary hunting rate a bot earns. A quarter, so crossing a band takes the best part of a day of play rather than an hour or
	 * two: slow enough that a region keeps the inhabitants it was given, fast enough that a bot visibly has a career.
	 * <p>
	 * This is the number to change when a population drifts. Lower it and the low regions hold; raise it and the higher bands fill faster.
	 */
	private static final int HUNTING_XP_PERCENT = 25;

	private static final BotPacing INSTANCE = new BotPacing();

	private BotPacing() {
	}

	/**
	 * Slows a bot down as it enters the world.
	 * <p>
	 * Applied every time, because stat functions are not stored with the character — they are rebuilt on each load, exactly like the passive skill
	 * effects beside them.
	 */
	public static void apply(Player bot) {
		bot.getGameStats().addEffect(INSTANCE, List.of(new StatSetFunction(StatEnum.BOOST_HUNTING_XP_RATE, HUNTING_XP_PERCENT)));
		holdAtRegionCeiling(bot);
	}

	/**
	 * Stops a bot at the top of what its region has to offer.
	 * <p>
	 * Slowing the climb is not the same as bounding it, and only the first was in place: a bot born in a valley of ones to eights still reached the
	 * top of it and kept going, with nothing to move it on and nothing to stop it. Measured in game within the hour — a character of seven beating
	 * creatures worth two in the middle of a beginners' village.
	 * <p>
	 * A ceiling is the honest answer <b>until bots can change region</b>. What should happen is that the population director finds it somewhere its
	 * new level belongs; what must not happen meanwhile is a region quietly filling with characters that have outgrown it. The one map this runs on
	 * tops out at nine, so a Poeta bot has a career from one to nine and then keeps the valley company.
	 * <p>
	 * Called from the decision tick as well as on the way in, because a bot crosses its ceiling while it is playing, not while it is loading.
	 */
	public static void holdAtRegionCeiling(Player bot) {
		boolean finished = bot.getLevel() >= BotPlaces.topLevelOf(bot.getWorldId());
		if (bot.getCommonData().getNoExp() != finished)
			bot.getCommonData().setNoExp(finished);
	}
}
