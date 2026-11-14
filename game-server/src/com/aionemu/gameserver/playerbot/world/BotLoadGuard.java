package com.aionemu.gameserver.playerbot.world;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.playerbot.BotScheduler;

/**
 * Holds the wakes of the world's bots while the server is short of memory or of route planning, and lets them go again once it is well clear.
 * <p>
 * The density settings say how many bots the world should hold; nothing said how many this machine can. A percent set too high used to fill the heap
 * until the server fell over, and before the heap, on 5 November at full density on sixteen cores, it was route planning that gave out: searches
 * queued for ten minutes and the bots stood still. A player's machine has less of both. So the director asks this before it wakes anybody, and a
 * world that has reached what the machine holds stops growing instead of breaking. Departures go on as before: a guard that also held them would keep
 * a server at its limit.
 * <p>
 * Each of the two signals holds above one mark and releases below a lower one, so a server sitting at the limit does not wake and stop every half
 * minute:
 * <ul>
 * <li><b>The heap</b> is read as the old generation <b>after a collection</b> ({@link MemoryPoolMXBean#getCollectionUsage}), not its raw usage,
 * which counts the garbage not yet collected and climbs to nearly full between collections on a server that is fine. Measured under G1 on Java 25:
 * the figure stays at 0 until the old generation passes the point where G1 starts its concurrent cycles (about 45 % of the heap), and from there
 * follows the live set within a few percent (218 MB read for 210 MB kept, at 85 %). A 0 is read as unknown and changes nothing, which is right
 * at that level.</li>
 * <li><b>Route planning</b> is read as the longest a search waited for a thread since the last review. Its length would say less: the queue holds
 * one search per bot at most, so a long queue on a big world is ordinary, while a bot that waits a minute takes its search for lost.</li>
 * </ul>
 * The marks are properties (gameserver.playerbot.guard.*), measured rather than chosen.
 */
public final class BotLoadGuard {

	private static final Logger log = LoggerFactory.getLogger(BotLoadGuard.class);

	/** What holds the wakes. */
	public enum Cause {
		HEAP, ROUTES
	}

	/** Where each signal holds and where it lets go: a percent of the heap, and milliseconds a route search waited. */
	record Limits(int heapHold, int heapRelease, long routesHold, long routesRelease) {
	}

	/** One look at the server: the old generation after the last collection in percent of the heap (-1 when unknown), and the longest route wait. */
	public record Reading(int heapPercent, long routeWaitMillis) {
	}

	private static final BotLoadGuard INSTANCE = new BotLoadGuard();

	private final MemoryPoolMXBean oldGeneration = findOldGeneration();
	/** Written by the review, on the lifecycle lane; read by //bot pool from anywhere. */
	private volatile Set<Cause> holding = EnumSet.noneOf(Cause.class);
	private volatile Reading last = new Reading(-1, 0);

	private BotLoadGuard() {
	}

	public static BotLoadGuard getInstance() {
		return INSTANCE;
	}

	/**
	 * The decision, kept apart from the server so it can be tested: each cause held from its hold mark up, kept until its release mark, and an
	 * unknown heap leaving the heap as it was.
	 *
	 * @return The causes that hold after this reading.
	 */
	static Set<Cause> next(Set<Cause> holding, Reading reading, Limits limits) {
		Set<Cause> next = holding.isEmpty() ? EnumSet.noneOf(Cause.class) : EnumSet.copyOf(holding);
		if (reading.heapPercent() >= 0) {
			if (reading.heapPercent() >= limits.heapHold())
				next.add(Cause.HEAP);
			else if (reading.heapPercent() < limits.heapRelease())
				next.remove(Cause.HEAP);
		}
		if (reading.routeWaitMillis() >= limits.routesHold())
			next.add(Cause.ROUTES);
		else if (reading.routeWaitMillis() < limits.routesRelease())
			next.remove(Cause.ROUTES);
		return next;
	}

	/**
	 * Reads the server and decides, once a review of the director, on its lane. Says so in the log when the answer changes.
	 *
	 * @return true when the wakes are held.
	 */
	public boolean review() {
		Reading reading = new Reading(heapPercentAfterCollection(), BotScheduler.getInstance().takeLongestRouteWait());
		Limits limits = limits();
		Set<Cause> before = holding;
		Set<Cause> after = PlayerBotConfig.GUARD_ENABLE ? next(before, reading, limits) : EnumSet.noneOf(Cause.class);
		last = reading;
		holding = after;
		if (!after.equals(before)) {
			if (after.isEmpty())
				log.info("Population guard: wakes released ({})", describe(reading, limits));
			else
				log.info("Population guard: wakes held by {} ({})", after, describe(reading, limits));
		}
		return !after.isEmpty();
	}

	/**
	 * @return true when the heap is at the hold mark now, read at the moment a wake's turn comes. The review reads once and may queue many wakes;
	 *         this stops the ones after the heap filled. The heap only: a route wait is read over a review, and the review's verdict holds that.
	 */
	public boolean heapFullNow() {
		if (!PlayerBotConfig.GUARD_ENABLE)
			return false;
		int heap = heapPercentAfterCollection();
		return heap >= 0 && heap >= PlayerBotConfig.GUARD_HEAP_HOLD;
	}

	/** @return What holds the wakes, as the last review decided; empty while they go on. */
	public Set<Cause> causes() {
		return Collections.unmodifiableSet(holding);
	}

	/** @return What the last review read. */
	public Reading lastReading() {
		return last;
	}

	/** @return true while the wakes are held, as the last review decided. */
	public boolean holds() {
		return !holding.isEmpty();
	}

	/** @return The guard's state in one line, for the review's log line and //bot pool. */
	public String state() {
		Set<Cause> now = holding;
		String verdict = !PlayerBotConfig.GUARD_ENABLE ? "guard off" : now.isEmpty() ? "guard open" : "guard holds wakes (" + now + ")";
		return verdict + ": " + describe(last, limits());
	}

	private static String describe(Reading reading, Limits limits) {
		String heap = reading.heapPercent() < 0 ? "heap not yet collected" : "heap " + reading.heapPercent() + "% after a collection";
		return heap + " (holds at " + limits.heapHold() + ", releases under " + limits.heapRelease() + "), longest route wait "
			+ reading.routeWaitMillis() / 1000 + " s (holds at " + limits.routesHold() / 1000 + ", releases under " + limits.routesRelease() / 1000 + ")";
	}

	private static Limits limits() {
		return new Limits(PlayerBotConfig.GUARD_HEAP_HOLD, PlayerBotConfig.GUARD_HEAP_RELEASE, PlayerBotConfig.GUARD_ROUTES_HOLD * 1000L,
			PlayerBotConfig.GUARD_ROUTES_RELEASE * 1000L);
	}

	/**
	 * @return The old generation after its last collection, in percent of what it may grow to; -1 before the first collection or without one.
	 *         Never above what the old generation holds now: the figure after a collection only moves when a collection reaches the old
	 *         generation, and one left high after a large drop would hold the wakes for ever. On the probe a drop from 91 % was read at 3 % at once,
	 *         so this is a safety rather than a fix; what the generation holds now, garbage and all, is never less than what is live in it, so the
	 *         smaller of the two is still a figure for the live set.
	 */
	private int heapPercentAfterCollection() {
		if (oldGeneration == null)
			return -1;
		MemoryUsage after = oldGeneration.getCollectionUsage();
		if (after == null || after.getUsed() == 0)
			return -1;
		long max = after.getMax() > 0 ? after.getMax() : Runtime.getRuntime().maxMemory();
		long used = Math.min(after.getUsed(), oldGeneration.getUsage().getUsed());
		return (int) (used * 100 / max);
	}

	/** @return The heap pool long-lived objects end up in ("G1 Old Gen", "Tenured Gen", "PS Old Gen"...), or null when the collector names none. */
	private static MemoryPoolMXBean findOldGeneration() {
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
				&& (pool.getName().contains("Old") || pool.getName().contains("Tenured")))
				return pool;
		}
		log.warn("Population guard: no old generation among the heap pools, the heap is not watched");
		return null;
	}
}
