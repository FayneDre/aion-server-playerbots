package com.aionemu.gameserver.playerbot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Threads that belong to the bots, so they can never starve the world.
 * <p>
 * Every bot tick used to go through {@code ThreadPoolManager}, whose scheduled pool holds one thread per processor and is shared with everything else
 * the server schedules: respawns, effect expiry, sieges, periodic saves. That is fine at forty five bots, which cost 0.73% of a sixteen core machine
 * all told. It is not fine at a thousand, where a burst of decision ticks would sit in the same queue as the engine's own work and delay it — and a
 * respawn that arrives late is a bug in the world, caused by something that is only meant to be watching it.
 * <p>
 * So the bots get their own pool and a queue of their own to be late in. If they fall behind, bots stutter and nothing else does, which is the right
 * way round: they are scenery, and the world is not.
 * <p>
 * Sized below the processor count on purpose. These threads are almost always idle — a decision tick is short — and leaving processors for the engine
 * and for the real players' packet handling matters more than draining the bot queue as fast as possible.
 * <p>
 * Two lanes, because the work comes in two lengths. A decision tick is microseconds; planning a route across a map is a search that can take orders
 * of magnitude longer, which is why it was put on the engine's long-running pool in the first place. Running both in one lane would have route
 * searches blocking ticks, and running routes on the engine's pool — which is what still happened after the ticks moved — leaves the heaviest thing
 * bots do competing with the engine's own long tasks. So they get a lane each.
 */
public class BotScheduler {

	private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
	/** Share of the processors the bots may use for their ticks, rounded down, never fewer than two. */
	private static final float PROCESSOR_SHARE = 0.5f;

	private static final BotScheduler INSTANCE = new BotScheduler();

	private final ScheduledExecutorService pool;
	private final ExecutorService routePool;

	private BotScheduler() {
		int threads = Math.max(2, (int) (Runtime.getRuntime().availableProcessors() * PROCESSOR_SHARE));
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, threadsNamed("PlayerBot"));
		// A cancelled tick is removed from the queue rather than left in it as a tombstone. Bots cancel constantly — every fight that ends, every
		// journey abandoned — and without this the queue grows with the number of cancellations instead of the number of pending ticks.
		executor.setRemoveOnCancelPolicy(true);
		pool = executor;
		int routeThreads = Math.max(1, threads / 2);
		routePool = Executors.newFixedThreadPool(routeThreads, threadsNamed("PlayerBotRoute"));
		log.info("Bot scheduler running on {} tick thread(s) and {} route thread(s)", threads, routeThreads);
	}

	private static ThreadFactory threadsNamed(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return r -> {
			Thread thread = Executors.defaultThreadFactory().newThread(r);
			thread.setName(prefix + "-" + counter.incrementAndGet());
			// Below normal: when the machine is saturated the engine's work goes first. A late bot tick is invisible, a late engine task is a bug.
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			thread.setDaemon(true);
			return thread;
		};
	}

	public static BotScheduler getInstance() {
		return INSTANCE;
	}

	/**
	 * @return The scheduled tick, or null once the pool is shutting down — which happens on the way out of the process, where a bot having no next
	 *         tick is exactly right.
	 */
	public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
		try {
			return pool.schedule(reporting(task), delayMillis, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			return null;
		}
	}

	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delayMillis, long periodMillis) {
		try {
			return pool.scheduleAtFixedRate(reporting(task), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			return null;
		}
	}

	public void execute(Runnable task) {
		try {
			pool.execute(reporting(task));
		} catch (RejectedExecutionException e) {
			// shutting down, and whatever this was is no longer worth doing
		}
	}

	/**
	 * Wraps a task so that failing leaves a trace instead of a silence.
	 * <p>
	 * An executor drops whatever a task throws on the floor, and a repeating one cancels itself outright — so a single exception took one bot out of
	 * the world with nothing in the log to say which, or that anything had happened at all. Every task the module runs goes through here, so the
	 * answer is written once rather than remembered at each of the call sites.
	 * <p>
	 * It does not keep a task alive: a self-rescheduling tick has to see to that itself, in a finally. What it guarantees is that the failure is
	 * reported and that no other bot's work is affected by it.
	 */
	private static Runnable reporting(Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (RuntimeException | Error e) {
				log.error("A bot task failed", e);
			}
		};
	}

	/** Runs a route search, which belongs in its own lane: it is the longest thing a bot does and must block neither ticks nor the engine. */
	public void planRoute(Runnable search) {
		try {
			routePool.execute(reporting(search));
		} catch (RejectedExecutionException e) {
			// shutting down; the caller is a bot that will not be walking anywhere
		}
	}

	/** @return How many bot tasks are waiting, which is the number to watch when bots start looking sluggish. */
	public int queued() {
		return pool instanceof ScheduledThreadPoolExecutor executor ? executor.getQueue().size() : 0;
	}

	public void shutdown() {
		pool.shutdownNow();
		routePool.shutdownNow();
	}
}
