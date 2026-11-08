package com.aionemu.gameserver.playerbot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * Three lanes, because the work comes in three lengths. A decision tick is microseconds; planning a route across a map is a search that can take
 * orders of magnitude longer, which is why it was put on the engine's long-running pool in the first place. Running both in one lane would have route
 * searches blocking ticks, and running routes on the engine's pool — which is what still happened after the ticks moved — leaves the heaviest thing
 * bots do competing with the engine's own long tasks. So they get a lane each.
 * <p>
 * The third is for entering and leaving the world, and it is the slowest of the three by a wide margin: loading a character is some twenty blocking
 * database round trips — skills, inventory, titles, quests, mail — before any navmesh lookup. The population director does that in batches, so on the
 * tick lane a single review would hold a thread for seconds, and with the tick lane sized at half the processors that is half the bots not thinking.
 * One thread, on purpose: there is no gain in loading characters in parallel against one database, and a single lane serialises arrivals and
 * departures for free, so nothing has to lock to keep the same character from being woken twice.
 */
public class BotScheduler {

	private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
	/** Share of the processors the bots may use for their ticks, rounded down, never fewer than two. */
	private static final float PROCESSOR_SHARE = 0.5f;
	/** A route search that waited longer than this for a thread is logged. */
	private static final long SLOW_QUEUE_MILLIS = 1000;
	/** At most one line about the route queue in this many milliseconds. */
	private static final long QUEUE_REPORT_MILLIS = 10_000;

	private static final BotScheduler INSTANCE = new BotScheduler();

	private final ScheduledExecutorService pool;
	private final ThreadPoolExecutor routePool;
	private final ExecutorService lifecyclePool;
	/** The one search each bot has waiting for a route thread; see {@link #planRoute}. */
	private final LatestPerKey waitingRoutes = new LatestPerKey();
	/** When the route queue was last reported, read and written by route threads; a race only costs a line more or less. */
	private volatile long lastQueueReport;
	/** The longest a search waited for a route thread since the last report. */
	private final AtomicLong longestWait = new AtomicLong();
	/** The same since the population guard last asked, kept apart because the report resets its own when it writes. See {@link #takeLongestRouteWait}. */
	private final AtomicLong longestWaitForGuard = new AtomicLong();

	private BotScheduler() {
		int threads = Math.max(2, (int) (Runtime.getRuntime().availableProcessors() * PROCESSOR_SHARE));
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, threadsNamed("PlayerBot"));
		// A cancelled tick is removed from the queue rather than left in it as a tombstone. Bots cancel constantly — every fight that ends, every
		// journey abandoned — and without this the queue grows with the number of cancellations instead of the number of pending ticks.
		executor.setRemoveOnCancelPolicy(true);
		pool = executor;
		// Never fewer than two: one search that gives up holds its thread for seconds, and measured on 8 November at four processors the single
		// thread this made queued 98 bots behind such searches.
		int routeThreads = Math.max(2, threads / 2);
		routePool = new ThreadPoolExecutor(routeThreads, routeThreads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadsNamed("PlayerBotRoute"));
		lifecyclePool = Executors.newSingleThreadExecutor(threadsNamed("PlayerBotLifecycle"));
		log.info("Bot scheduler running on {} tick thread(s), {} route thread(s) and one lifecycle thread", threads, routeThreads);
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

	/**
	 * Runs a route search, which belongs in its own lane: it is the longest thing a bot does and must block neither ticks nor the engine.
	 * <p>
	 * <b>One search waits per bot.</b> A bot whose search has waited a minute takes it for lost and asks again, and one whose destination moved asks
	 * for the new one; both used to join the queue behind the old search, which still ran, for an answer nobody wanted. Once the queue was slower than
	 * that minute it fed itself: on 5 November, with 1894 bots awake, it grew to 25 000 searches and a wait of ten minutes, and the bots stood still
	 * waiting for paths. So a newer search for the same bot takes the place of one still waiting, <b>keeping its turn</b>: when that turn comes the
	 * newest is run, and the older never answers, which costs nothing, since the bot is waiting on the newer. Sent to the back instead, it would never
	 * run for a bot that asks again every few seconds while the queue takes longer than that. A search already running is left to finish. The queue
	 * then holds at most one search per bot, whatever the load.
	 */
	public void planRoute(int botId, Runnable search) {
		if (!waitingRoutes.offer(botId, search))
			return; // this bot already has a place in the queue, which will run this search
		long queued = System.currentTimeMillis();
		try {
			routePool.execute(reporting(() -> {
				Runnable latest = waitingRoutes.take(botId);
				if (latest == null)
					return;
				// how long a search waited for a thread, which the search's own timing cannot see: logged to tell a queue that falls behind from searches
				// that are slow by themselves
				long now = System.currentTimeMillis();
				long longest = longestWait.accumulateAndGet(now - queued, Math::max);
				longestWaitForGuard.accumulateAndGet(now - queued, Math::max);
				// one line every so often, with the longest wait since the last, rather than one per slow search: at full density that was 12 000
				// lines in ten minutes
				if (longest >= SLOW_QUEUE_MILLIS && now - lastQueueReport >= QUEUE_REPORT_MILLIS) {
					lastQueueReport = now;
					longestWait.set(0);
					log.info("Route search waited {} ms for a route thread, {} bot(s) still waiting, {} search(es) replaced by a newer one so far", longest,
						waitingRoutes.waiting(), waitingRoutes.replaced());
				}
				latest.run();
			}));
		} catch (RejectedExecutionException e) {
			// shutting down; the caller is a bot that will not be walking anywhere
			waitingRoutes.take(botId);
		}
	}

	/**
	 * @return The longest a route search waited for a thread since the last call, which starts the next count, or the wait of the search still
	 *         waiting longest if that is more: a queue that stops moving would otherwise only be seen when its oldest search finally ran, as late as
	 *         the wait itself. For the population guard ({@code BotLoadGuard}), once a review.
	 */
	public long takeLongestRouteWait() {
		return Math.max(longestWaitForGuard.getAndSet(0), waitingRoutes.longestWaiting(System.currentTimeMillis()));
	}

	/** @return How many bots have a route search waiting for a thread, how many run, and how many were replaced by a newer one since the start. */
	public String routeQueue() {
		return waitingRoutes.waiting() + " bot(s) waiting for a route, " + routePool.getActiveCount() + " search(es) running, " + waitingRoutes.replaced()
			+ " replaced by a newer one";
	}

	/**
	 * Brings a bot into the world or takes it out of it, in the lane that may block.
	 * <p>
	 * Every arrival and departure goes through here rather than being run wherever it was decided, which is what keeps a batch of them off the tick
	 * lane. Because the lane is one thread, tasks run in the order they were handed over: a bot cannot be woken while it is still leaving.
	 */
	public void enterOrLeaveWorld(Runnable task) {
		try {
			lifecyclePool.execute(reporting(task));
		} catch (RejectedExecutionException e) {
			// shutting down, and the world is being emptied anyway
		}
	}

	/** @return How many bot tasks are waiting, which is the number to watch when bots start looking sluggish. */
	public int queued() {
		return pool instanceof ScheduledThreadPoolExecutor executor ? executor.getQueue().size() : 0;
	}

	/**
	 * Closes the arrivals and departures lane and waits briefly for the one in progress, which has to happen <b>before</b> the world is emptied on the
	 * way out: emptying walks the set of bots in the world and then clears it, so a bot that arrives between those two steps is left behind — in the
	 * world, with nobody holding a reference to it, saved by nothing.
	 */
	public void stopEnteringAndLeaving() {
		lifecyclePool.shutdown();
		try {
			if (!lifecyclePool.awaitTermination(5, TimeUnit.SECONDS))
				log.warn("A bot was still entering or leaving the world after 5s; carrying on without it");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void shutdown() {
		pool.shutdownNow();
		routePool.shutdownNow();
		lifecyclePool.shutdownNow();
	}
}
