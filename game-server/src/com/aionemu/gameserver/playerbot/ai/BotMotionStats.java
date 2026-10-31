package com.aionemu.gameserver.playerbot.ai;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * Counts the moves that make a bot look like one, from where it stands on each decision tick: a heading that turns back on itself and a move of under a metre between two ticks.
 * <p>
 * Backlog point 5: nothing said how often a bot reverses or shuffles, so the sources could not be ranked. Sampled once a second, so a reversal faster than that is not seen; one
 * that goes on is. Counted apart for a bot in a fight (kiting, closing in, which is meant) and one that is not (a step aside, a route re-aimed, a turn on the spot, which is what
 * the tester sees). One line a minute at info: how many bots moved, the reversals per moving bot-minute for each kind, and the bots with most of them. Read by
 * {@code tools/measure.sh}'s report ("--- motion").
 */
final class BotMotionStats {

	private static final Logger log = LoggerFactory.getLogger(BotMotionStats.class);

	/** A move shorter than this between two ticks is not a move. */
	private static final float MOVED = 0.7f;
	/** A turn of more than this many degrees from the last move to this one is a reversal. */
	private static final double REVERSAL_DEGREES = 120;
	private static final long REPORT_MILLIS = 60_000;
	private static final int TOP = 3;

	private static final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
	private static final AtomicLong nextReport = new AtomicLong(System.currentTimeMillis() + REPORT_MILLIS);
	private static final Object window = new Object();
	private static long movingTicks, reversalsFighting, reversalsIdle;
	private static final Map<String, Integer> idleByBot = new ConcurrentHashMap<>();

	private static final class Track {
		float x, y, headingX, headingY;
		boolean hasHeading;
	}

	private BotMotionStats() {
	}

	/** Called on each decision tick of a bot. */
	static void sample(Player bot, boolean fighting) {
		Track track = tracks.computeIfAbsent(bot.getObjectId(), id -> new Track());
		float dx = bot.getX() - track.x, dy = bot.getY() - track.y;
		track.x = bot.getX();
		track.y = bot.getY();
		double length = Math.hypot(dx, dy);
		if (length >= MOVED && length < 20) { // a longer one is a teleport or a landing, not a step
			synchronized (window) {
				movingTicks++;
				if (track.hasHeading) {
					double cosine = (dx * track.headingX + dy * track.headingY) / (length * Math.hypot(track.headingX, track.headingY));
					if (cosine < Math.cos(Math.toRadians(REVERSAL_DEGREES))) {
						if (fighting)
							reversalsFighting++;
						else {
							reversalsIdle++;
							idleByBot.merge(bot.getName(), 1, Integer::sum);
						}
					}
				}
			}
			track.headingX = dx;
			track.headingY = dy;
			track.hasHeading = true;
		} else if (length >= 20) {
			track.hasHeading = false;
		}
		long now = System.currentTimeMillis();
		long due = nextReport.get();
		if (now >= due && nextReport.compareAndSet(due, now + REPORT_MILLIS))
			report();
	}

	static void forget(Player bot) {
		tracks.remove(bot.getObjectId());
	}

	private static void report() {
		long moving, fighting, idle;
		String top;
		synchronized (window) {
			moving = movingTicks;
			fighting = reversalsFighting;
			idle = reversalsIdle;
			top = idleByBot.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).limit(TOP)
				.map(entry -> entry.getKey() + " " + entry.getValue()).reduce((a, b) -> a + ", " + b).orElse("-");
			movingTicks = 0;
			reversalsFighting = 0;
			reversalsIdle = 0;
			idleByBot.clear();
		}
		if (moving > 0)
			log.info("Motion: {} moving bot-seconds in the minute, {} reversals in a fight, {} out of one ({} per 100 moving seconds), most out of a fight: {}", moving, fighting, idle,
				String.format(java.util.Locale.ROOT, "%.1f", 100.0 * idle / moving), top);
	}
}
