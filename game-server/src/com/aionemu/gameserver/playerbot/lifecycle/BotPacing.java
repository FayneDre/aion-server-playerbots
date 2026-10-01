package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger log = LoggerFactory.getLogger(BotPacing.class);

	/**
	 * Percent of the ordinary hunting rate a bot earns, so that crossing a region takes the best part of a day of play rather than half an hour.
	 * <p>
	 * It was a quarter, guessed, and twice I tried to check it against the database and got nothing usable — the save sweep runs every five minutes,
	 * so a five minute window reads two saves or none. Measured properly, by logging each level at the moment it lands: <b>Yrieon crossed level seven
	 * to eight in four minutes flat at a quarter rate</b>. That band costs 30972 experience, so a bot was earning 7743 a minute and running the eight
	 * levels of Poeta in half an hour. The guess was out by a factor of twenty five.
	 * <p>
	 * At one percent the same band takes an hour and forty minutes and the region takes thirteen hours, which is the figure the population model is
	 * written against: nineteen inhabitants over a thirteen hour stay need one and a half arrivals an hour, a newcomer every forty minutes.
	 * <p>
	 * <b>One is the floor.</b> {@code StatSetFunction} carries an int, and zero is not a slower pace but no experience at all. Anything slower than
	 * thirteen hours a region has to come from somewhere else — the director retiring a bot out of its region rather than the bot earning less.
	 */
	private static final int HUNTING_XP_PERCENT = 1;

	/** How often each bot reports what it earned. A minute, so the figure reads directly as experience per minute. */
	private static final long EXP_REPORT_MILLIS = 60000;

	/**
	 * Every stat an experience reward passes through, because a pace that covers one of them is not a pace.
	 * <p>
	 * {@code Rates} has five, each reading its own stat, and only the solo one was set. A bot that kills anything while in a group is rewarded
	 * through {@code XP_GROUP_HUNTING} and {@code BOOST_GROUP_HUNTING_XP_RATE} — untouched, so at the full rate, which is the one case where a bot is
	 * most likely to be killing quickly. Quest, gathering and crafting are not routes a bot takes today and are set anyway: the rule is that a bot
	 * advances at this pace, not that it advances at this pace when it happens to be hunting alone.
	 */
	private static final StatEnum[] PACED_RATES = { StatEnum.BOOST_HUNTING_XP_RATE, StatEnum.BOOST_GROUP_HUNTING_XP_RATE,
		StatEnum.BOOST_QUEST_XP_RATE, StatEnum.BOOST_GATHERING_XP_RATE, StatEnum.BOOST_CRAFTING_XP_RATE };

	private final Player bot;
	/** Whether a player made this bot for themselves. Fixed for the character's life, so it is read once rather than on every tick. */
	private final boolean owned;
	/** The level last reported, so a gain is noticed the second it happens rather than guessed at from the database later. */
	private int reportedLevel;
	/** The experience seen last tick, so a gain is caught as it lands rather than inferred from a level crossed much later. */
	private long lastExp = -1;
	private long gainedThisMinute, biggestGain;
	private int gainsThisMinute;
	private long reportDue;
	/** When this bot was built, so a level reads as a time since it started rather than a wall clock to subtract by hand. */
	private final long startedAt = System.currentTimeMillis();

	public BotPacing(Player bot, boolean owned) {
		this.bot = bot;
		this.owned = owned;
		this.reportedLevel = bot.getLevel();
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
		reportLevel(companion);
		reportExperience(companion);
	}

	/**
	 * Says how much experience a bot actually earned, as it earns it.
	 * <p>
	 * A level crossing was the only measure available, and it is a poor one: it mixes the overflow carried from the previous level, the moment of the
	 * last save, and the rate itself into one number. Measured that way a bot at one percent crossed a 30972 point band in three and a half minutes,
	 * which is the full rate — but the stat read 1%, it was applied once and never cleared, and all five experience channels go through it. Something
	 * grants experience outside them, and the shape of the gains says which: many tiny ones mean the rate is applied and the bot simply kills a great
	 * deal; ordinary ones mean the rate misses kills; a single large one means a lump from somewhere else entirely.
	 * <p>
	 * So it reports the total, the count and the largest, which no single figure could have separated. The same move settled the navmesh and the
	 * shop trips: one number covering two questions answers neither.
	 */
	private void reportExperience(boolean companion) {
		long exp = bot.getCommonData().getExp();
		long now = System.currentTimeMillis();
		if (lastExp < 0) {
			lastExp = exp;
			reportDue = now + EXP_REPORT_MILLIS;
			return;
		}
		if (exp > lastExp) {
			long gain = exp - lastExp;
			gainedThisMinute += gain;
			gainsThisMinute++;
			biggestGain = Math.max(biggestGain, gain);
		}
		lastExp = exp;
		if (now < reportDue)
			return;
		reportDue = now + EXP_REPORT_MILLIS;
		if (gainsThisMinute > 0)
			log.info("Bot {} earned {} xp in {} gain(s), biggest {}, at {}% (level {}, {} to go)", bot.getName(), gainedThisMinute, gainsThisMinute,
				biggestGain, companion ? "100" : effectiveRate(), bot.getLevel(), bot.getCommonData().getExpNeed());
		gainedThisMinute = 0;
		gainsThisMinute = 0;
		biggestGain = 0;
	}

	/**
	 * Says when a bot gains a level, and under which of the two regimes.
	 * <p>
	 * It exists because the pace cannot be set without being measured, and two attempts to measure it from the database were both wrong: the save
	 * sweep runs every five minutes, so a five minute window reads either two saves or none, and a level seen in the character list says nothing
	 * about when it was reached. A line at the moment it happens settles both the pace and how long a band actually takes to cross.
	 */
	private void reportLevel(boolean companion) {
		if (bot.getLevel() == reportedLevel)
			return;
		log.info("Bot {} reached level {} from {} after {} min in the world, {} at {}% xp", bot.getName(), bot.getLevel(), reportedLevel,
			(System.currentTimeMillis() - startedAt) / 60000, companion ? "a companion" : "the world's", effectiveRate());
		reportedLevel = bot.getLevel();
	}

	/** Takes the character's experience rate down to the bot rate, or gives it back, doing nothing when it is already where it should be. */
	private void pace(boolean wanted) {
		if (wanted == isPaced())
			return;
		if (wanted)
			bot.getGameStats().addEffect(this, Stream.of(PACED_RATES).map(rate -> new StatSetFunction(rate, HUNTING_XP_PERCENT)).toList());
		else
			bot.getGameStats().endEffect(this);
		log.info("Bot {} is now {} and its xp rates read {}%", bot.getName(), wanted ? "paced" : "at a player's pace", effectiveRate());
	}

	/**
	 * @return Whether the brake is on, asked of the stat rather than of a flag.
	 *         <p>
	 *         This used to be a boolean the method set after applying the effect, which made it a note of what had been <i>intended</i>: anything that
	 *         cleared the stat container would have left the note saying "applied" and the brake would never have gone back on, silently and for that
	 *         character's whole life. Nothing clears it today — a level change rebuilds the class template without touching added functions — but the
	 *         failure would be invisible, and an invisible failure in this exact area has already cost an afternoon.
	 *         <p>
	 *         Reading it back instead removes the cached state altogether: the stat is the state, so there is nothing left to go stale. It costs one
	 *         map lookup and a list of one per tick, against the known list scans and ray casts the same tick already does.
	 *         <p>
	 *         Relies on {@link #HUNTING_XP_PERCENT} differing from the 100 that means the ordinary rate, which is the whole point of it.
	 */
	private boolean isPaced() {
		return Math.round(bot.getGameStats().getStat(PACED_RATES[0], 100).getCurrent()) == HUNTING_XP_PERCENT;
	}

	/**
	 * @return What the character's experience rate actually is, read back out of the stat container.
	 *         <p>
	 *         Asked rather than assumed, because the first version of this log printed the constant it had just tried to set, which would have read
	 *         the same whether the effect landed or was dropped on the floor. A number that cannot be wrong is not a measurement.
	 */
	private String effectiveRate() {
		return Stream.of(PACED_RATES).map(rate -> String.valueOf(Math.round(bot.getGameStats().getStat(rate, 100).getCurrent()))).distinct()
			.reduce((a, b) -> a + "/" + b).orElse("?");
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
