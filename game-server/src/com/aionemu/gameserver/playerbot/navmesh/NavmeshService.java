package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.collision.CollisionIntention;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldMap;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Serves generated maps to the bots that need them.
 * <p>
 * Opening a map reads its tile directory and nothing else, so it is instant. Tiles are then read as bots walk into them, which keeps a camp down to
 * a handful of tiles instead of the 117 MB a whole map would cost.
 */
public class NavmeshService {

	private static final Logger log = LoggerFactory.getLogger(NavmeshService.class);

	/**
	 * What a journey asks of the world the mesh lies in, which navigation cannot answer from the mesh and must not answer by reaching up into the bot
	 * module: who is too strong to land beside, where a fortress is, whose ground a stretch is, whether a crossing can be flown, and the thread a long
	 * search runs on. {@code PlayerBotService.onStartUp} installs it before any bot plans anything; asked without one, a journey fails loudly rather
	 * than quietly planning as though the world held no danger.
	 */
	public interface Host {

		/** @return true for a creature the bot cannot fight: one of its enemies, aggressive towards it, beyond a fight's level gap above it. */
		boolean isTooStrongFor(Player bot, Npc npc);

		/** @return true if the spot is inside a fortress's zone. */
		boolean isSiegeGround(int worldId, float x, float y, float z);

		/** @return The side whose people alone live on that stretch of ground, or null when it is nobody's or both sides'. */
		Race ownerOfGround(int worldId, int region);

		/** @return true if the bot could fly from one end to the other. */
		boolean canCross(Player bot, Vector3f from, Vector3f to);

		/** Runs a long search on the thread meant for it. */
		void planRoute(Runnable search);
	}

	private static volatile Host host;

	public static void setHost(Host host) {
		NavmeshService.host = host;
	}

	private static Host host() {
		Host installed = host;
		if (installed == null)
			throw new IllegalStateException("NavmeshService has no host: PlayerBotService.onStartUp installs one before any bot plans a journey");
		return installed;
	}

	private final Map<Integer, Navmesh> open = new ConcurrentHashMap<>();
	/** Maps we already know have no file, so a bot walking there does not retry on every step. */
	private final Set<Integer> missing = ConcurrentHashMap.newKeySet();
	/** Per link, whether it comes down on siege ground; see {@link #mayLand}. */
	private final Map<GlideLink, Boolean> siegeLandings = new ConcurrentHashMap<>();
	/** How near a creature the bot cannot fight a landing may be, in metres. */
	private static final float DANGER_RADIUS = 40;
	/** How long the creatures a bot cannot fight are remembered for, per map, side and level. */
	private static final long DANGERS_KEPT_MILLIS = 5000;
	private final Map<String, Dangers> dangerCache = new ConcurrentHashMap<>();

	public static NavmeshService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Plans a route for the bot, opening its map on first use.
	 * <p>
	 * The whole {@link BotPathFinder.Route} and not its waypoints, because an empty one says two different things and the caller must tell them
	 * apart: a search that gave up is no evidence, while a search that finished having found nothing is proof that the goal cannot be walked to.
	 * Collapsing both into an empty list cost 211 refused journeys on one bot in twenty-five minutes -- it stood on a roof, was proved to have no
	 * way down, and walked at the goal regardless because the proof was thrown away here.
	 *
	 * @return The route. A map with no mesh reads as a search that gave up, since the absence of a mesh proves nothing either.
	 */
	public BotPathFinder.Route findRoute(Player bot, float goalX, float goalY, float goalZ) {
		return findRoute(bot, goalX, goalY, goalZ, Avoidance.NONE);
	}

	/** @see #findRoute(Player, float, float, float) */
	public BotPathFinder.Route findRoute(Player bot, float goalX, float goalY, float goalZ, Avoidance avoid) {
		Navmesh mesh = get(bot.getWorldId());
		if (mesh == null)
			return new BotPathFinder.Route(List.of(), true);

		// only a daeva has wings: anyone else is given no glide, and the planner answers as though the links were not there
		boolean canGlide = bot.getCommonData().isDaeva() && !bot.getTransformModel().cantFly();
		long began = System.currentTimeMillis();
		long cpuBegan = cpuTime();
		// read once: off the tick thread the bot walks on while the search runs, and the log must name the start the search was given
		float startX = bot.getX(), startY = bot.getY(), startZ = bot.getZ();
		Dangers dangers = dangersFor(bot);
		// landings only: kept away from on foot as well, even at three times the cost, the search gave up after seven seconds on a journey of 900 m it
		// planned in under one without them. The Abyss is dangerous to walk, and a bot meets what it meets; it does not fly down into it
		Map<GlideLink, Boolean> landings = new HashMap<>();
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, startX, startY, startZ, goalX, goalY, goalZ, avoid, new GlideRoutes.AirTest() {

			@Override
			public boolean isClear(GlideLink link) {
				return link.ride() || canGlide && isLinkClear(bot, link); // a lift carries anyone, wings or not
			}

			@Override
			public boolean mayLand(GlideLink link) {
				return landings.computeIfAbsent(link, l -> NavmeshService.this.mayLand(bot, l) && !dangers.isNear(l.toX(), l.toY(), l.toZ()));
			}
		});
		if (route.isEmpty() && !route.gaveUp() && !dangers.spots().isEmpty()) {
			// a proof that may be the creatures' doing rather than the ground's: how many stand round the goal tells the two apart
			long nearGoal = dangers.spots().stream().filter(danger -> PositionUtil.getDistance(danger.x, danger.y, danger.z, goalX, goalY, goalZ) < DANGER_RADIUS * 3).count();
			log.info("Path search for {} found no way to {} {}, keeping away from {} creature(s), {} of them within {} m of it", bot.getName(),
				Math.round(goalX), Math.round(goalY), dangers.spots().size(), nearGoal, Math.round(DANGER_RADIUS * 3));
		}
		if (route.isEmpty() && route.gaveUp()) {
			// in the log on a map flown everywhere, where the creatures kept away from are a cost the search pays and the one way to tell it was
			if (!dangers.spots().isEmpty())
				log.info("Path search for {} gave up short of {} {} after {} ms, keeping away from {} creature(s)", bot.getName(), Math.round(goalX),
					Math.round(goalY), System.currentTimeMillis() - began, dangers.spots().size());
			else
				log.debug("Path search for {} gave up short of {} {}", bot.getName(), goalX, goalY);
		}
		long took = System.currentTimeMillis() - began;
		if (took >= SLOW_SEARCH_MILLIS)
			log.info("Journey search for {} on map {} took {} ms ({}, {} ms of processor, on {}, wings {}, {} creature(s) kept away from, avoiding {}): NavmeshTool {} path {} {} {} {} {} {}",
				bot.getName(), bot.getWorldId(), took, !route.isEmpty() ? "found" : route.gaveUp() ? "gave up" : "no way", (cpuTime() - cpuBegan) / 1_000_000,
				Thread.currentThread().getName(), canGlide, dangers.spots().size(), avoid != Avoidance.NONE, bot.getWorldId(), String.format("%.1f", startX),
				String.format("%.1f", startY), String.format("%.1f", startZ), String.format("%.1f", goalX), String.format("%.1f", goalY), String.format("%.1f", goalZ));
		return route;
	}

	/**
	 * @return Where the creatures a bot cannot fight stand right now, on a map flown everywhere: alive, aggressive towards it and more than a fight's
	 *         level gap above it. Asked of the living world rather than of the spawn data, because the worst of them are not in it: a squad of level 40
	 *         flew onto Reshanta's upper layer beside dread remnants of level 65, Balaur elites a fortress puts there, and died to a man.
	 */
	private Dangers dangersFor(Player bot) {
		WorldMap map = World.getInstance().getWorldMap(bot.getWorldId());
		if (map == null || !map.isFlightAllowed() || bot.getPosition() == null || bot.getPosition().getWorldMapInstance() == null)
			return Dangers.NONE;
		// a walk over every npc of the map, asked by every route of every bot: remembered a few seconds per map, side and level, which is what it turns on
		String key = bot.getWorldId() + ":" + bot.getPosition().getInstanceId() + ":" + bot.getRace() + ":" + bot.getLevel();
		Dangers known = dangerCache.get(key);
		long now = System.currentTimeMillis();
		if (known != null && now - known.at() < DANGERS_KEPT_MILLIS)
			return known;
		List<Vector3f> found = new ArrayList<>();
		bot.getPosition().getWorldMapInstance().forEachNpc(npc -> {
			if (npc.isSpawned() && !npc.isDead() && host().isTooStrongFor(bot, npc))
				found.add(new Vector3f(npc.getX(), npc.getY(), npc.getZ()));
		});
		Dangers dangers = Dangers.of(now, found);
		dangerCache.put(key, dangers);
		return dangers;
	}

	/**
	 * The creatures a bot cannot fight, and the same filed by squares of {@link #DANGER_RADIUS}.
	 * <p>
	 * Every landing a plan looks at is asked whether one stands near it, and on Reshanta a list held 726 to 1875 of them: each landing walked the
	 * whole list, for every link the chains of a crossing went through. The squares leave the nine round the landing to look at.
	 */
	record Dangers(long at, List<Vector3f> spots, Map<Long, List<Vector3f>> bySquare) {

		static final Dangers NONE = new Dangers(0, List.of(), Map.of());

		static Dangers of(long at, List<Vector3f> spots) {
			Map<Long, List<Vector3f>> bySquare = new HashMap<>();
			for (Vector3f spot : spots)
				bySquare.computeIfAbsent(square(spot.getX(), spot.getY()), s -> new ArrayList<>()).add(spot);
			return new Dangers(at, List.copyOf(spots), bySquare);
		}

		/** @return true if one of the creatures stands within {@link #DANGER_RADIUS} of the spot. */
		boolean isNear(float x, float y, float z) {
			int squareX = (int) Math.floor(x / DANGER_RADIUS), squareY = (int) Math.floor(y / DANGER_RADIUS);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					List<Vector3f> near = bySquare.get(key(squareX + dx, squareY + dy));
					if (near != null && near.stream().anyMatch(danger -> PositionUtil.getDistance(danger.x, danger.y, danger.z, x, y, z) < DANGER_RADIUS))
						return true;
				}
			}
			return false;
		}

		private static long square(float x, float y) {
			return key((int) Math.floor(x / DANGER_RADIUS), (int) Math.floor(y / DANGER_RADIUS));
		}

		private static long key(int squareX, int squareY) {
			return (long) squareX << 32 | squareY & 0xFFFFFFFFL;
		}
	}

	/**
	 * @return false for a link that comes down inside a fortress's zone, or on ground only the other faction's people live on: a crossing
	 *         into either is a fight the bot did not choose, refused until wild PvP is a decision rather than an accident. The siege half is remembered
	 *         per link, since the chains ask it of every link they look at.
	 */
	private boolean mayLand(Player bot, GlideLink link) {
		if (siegeLandings.computeIfAbsent(link, l -> host().isSiegeGround(bot.getWorldId(), l.toX(), l.toY(), l.toZ())))
			return false;
		Race owner = host().ownerOfGround(bot.getWorldId(), link.toRegion());
		return owner == null || owner == bot.getRace();
	}

	/** @return Whether a link can be taken now: a crossing flown at one of its heights, or a glide down a clear line. A refusal is logged at debug, the one way to see which link of a plan was turned down. */
	private static boolean isLinkClear(Player bot, GlideLink link) {
		if (link.ride())
			return true; // carried, not flown: the air has nothing to say
		boolean clear = isAirClear(bot, link);
		if (!clear && log.isDebugEnabled())
			log.debug("Bot {} finds the air blocked on the {} {} {} {} -> {} {} {}", bot.getName(), link.flight() ? "crossing" : "glide", Math.round(link.fromX()),
				Math.round(link.fromY()), Math.round(link.fromZ()), Math.round(link.toX()), Math.round(link.toY()), Math.round(link.toZ()));
		return clear;
	}

	/**
	 * @return Whether the air between the two ends of a glide or a crossing holds nothing solid, asked of the game's own geometry. The links were found from the floors alone, which cannot
	 *         say whether a wall stands between an edge and the ground below it.
	 */
	private static boolean isAirClear(Player bot, GlideLink link) {
		// Kept a while per link and instance. The ray was cast again for every link of every chain each search tried, for every bot: on 25 October a
		// journey of Enshar found offline in 1.7 s with one glide gave up in game after five, asked by four bots up to seven times each. The air only
		// changes where a door opens or shuts, hence an instance in the key and an answer that expires.
		// A crossing of Reshanta was not kept at all, and it casts three rays at each height it tries.
		AirKey key = new AirKey(bot.getWorldId(), bot.getInstanceId(), link);
		long now = System.currentTimeMillis();
		AirAnswer known = airAnswers.get(key);
		if (known != null && known.until() > now)
			return known.clear();
		if (airAnswers.size() >= MAX_AIR_ANSWERS)
			airAnswers.clear(); // instances come and go, and their answers with them: a bound rather than a sweep
		boolean clear = link.flight()
			? host().canCross(bot, new Vector3f(link.fromX(), link.fromY(), link.fromZ()), new Vector3f(link.toX(), link.toY(), link.toZ()))
			: castAir(bot, link);
		airAnswers.put(key, new AirAnswer(clear, now + AIR_ANSWER_MILLIS));
		return clear;
	}

	private record AirKey(int worldId, int instanceId, GlideLink link) {
	}

	private record AirAnswer(boolean clear, long until) {
	}

	private static final Map<AirKey, AirAnswer> airAnswers = new ConcurrentHashMap<>();
	private static final long AIR_ANSWER_MILLIS = 10 * 60_000L;
	private static final int MAX_AIR_ANSWERS = 50_000; // some 100 bytes each

	private static boolean castAir(Player bot, GlideLink link) {
		// raised to a body's chest, and the first and last metre forgiven: the edge's own rail and the floor it lands on are what the body is leaving and reaching
		Vector3f met = GeoService.getInstance().getClosestCollision(bot, link.fromX(), link.fromY(), link.fromZ() + 1.5f, link.toX(), link.toY(), link.toZ() + 1.5f, false,
			CollisionIntention.DEFAULT_COLLISIONS.getId(), IgnoreProperties.ANY_RACE);
		return met == null || PositionUtil.getDistance(met.getX(), met.getY(), met.getZ(), link.toX(), link.toY(), link.toZ() + 1.5f) < 1.5f
			|| PositionUtil.getDistance(met.getX(), met.getY(), met.getZ(), link.fromX(), link.fromY(), link.fromZ() + 1.5f) < 1f;
	}

	/**
	 * Finds walkable ground near a spot, for putting a bot down on it.
	 *
	 * @return The nearest ground a body fits on, or null when the map has no mesh or nothing walkable is near enough.
	 */
	public Vector3f groundNear(int worldId, float x, float y, float z) {
		Navmesh mesh = get(worldId);
		return mesh == null ? null : BotPathFinder.nearestGround(mesh, x, y, z);
	}

	/**
	 * Asks the mesh how far the bot can walk straight towards a point, see {@link BotPathFinder#clearDistance}. This is what the short moves are vetted
	 * by: the geo probes are raised a metre off the ground and miss every rail, low wall and prop base the mesh was built around.
	 *
	 * @return The clear distance in metres, or NaN where the map has no mesh to ask.
	 */
	public float clearDistance(Player bot, float x, float y, float z) {
		Navmesh mesh = get(bot.getWorldId());
		return mesh == null ? Float.NaN
			: BotPathFinder.clearDistance(mesh, new Vector3f(bot.getX(), bot.getY(), bot.getZ()), new Vector3f(x, y, z));
	}

	/**
	 * Lists every floor a body can stand on at a spot, one above another, nearest to {@code z} first.
	 * <p>
	 * {@link #groundNear} answers one: the floor closest in height, which inside a building is always the floor the bot is already on. A way out of a
	 * pocket is usually on another level, which is exactly the floor that question never offers.
	 *
	 * @return The floors, empty where the map has no mesh or nothing can be stood on there.
	 */
	public java.util.List<Vector3f> floorsAt(int worldId, float x, float y, float z) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return java.util.List.of();
		int cellX = mesh.cellX(x), cellY = mesh.cellY(y);
		if (!mesh.contains(cellX, cellY))
			return java.util.List.of();
		java.util.List<Vector3f> floors = new java.util.ArrayList<>();
		for (int surface = 0; surface < mesh.surfaceCount(cellX, cellY); surface++) {
			if (mesh.isWalkable(cellX, cellY, surface))
				floors.add(new Vector3f(x, y, mesh.surfaceZ(cellX, cellY, surface)));
		}
		floors.sort(java.util.Comparator.comparingDouble(floor -> Math.abs(floor.getZ() - z)));
		return floors;
	}

	/**
	 * Asks whether one spot can be walked to from another.
	 * <p>
	 * Standable is not the same as reachable, and scattering villagers around a village centre is where the difference bites: a ledge, the far side
	 * of a wall or a hollow is perfectly good ground that happens to connect to nothing, and a resident put on one spends its life asking for a route
	 * out of it.
	 *
	 * @return true if a route exists, or if the map has no mesh to ask.
	 */
	public boolean canReach(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return true;
		long began = System.currentTimeMillis();
		long cpuBegan = cpuTime();
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ);
		logIfSlow(worldId, began, cpuBegan, fromX, fromY, fromZ, toX, toY, toZ, route);
		return !route.isEmpty();
	}

	/** How much longer than the straight line a walk to a nearby spot may be, and the metres added for the corner of a house or a fence. */
	private static final float NEARBY_DETOUR_FACTOR = 3, NEARBY_DETOUR_METRES = 20;

	/**
	 * Asks whether a spot near another is joined to it by a short walk: the question asked of a spot chosen round a place or an anchor.
	 * <p>
	 * {@link #canReach} answers it too, but proves a no only after the rough grid and the glides as well, seconds of search on a thread that runs other
	 * bots. A spot reachable only by a detour several times its distance, or by a glide, is not one to post a bot on, and is refused here at once.
	 *
	 * @return true if a walk of at most three times the distance, plus twenty metres, joins the two, or if the map has no mesh to ask.
	 */
	public boolean canReachNearby(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return true;
		float maxLength = (float) Math.hypot(toX - fromX, toY - fromY) * NEARBY_DETOUR_FACTOR + NEARBY_DETOUR_METRES;
		return BotPathFinder.isWithinWalk(mesh, fromX, fromY, fromZ, toX, toY, toZ, maxLength);
	}

	/** A search that took longer than this is logged with its ends, which is what finding out why takes ({@code NavmeshTool <map> path}). */
	private static final long SLOW_SEARCH_MILLIS = 1000;

	private static void logIfSlow(int worldId, long began, long cpuBegan, float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
		BotPathFinder.Route route) {
		long took = System.currentTimeMillis() - began;
		// the asker and the processor time are there because on 25 October nine slow searches in ten ran on a tick thread, holding up every bot on it,
		// and the line could not say who had asked; a processor time well under the wall time says the search waited for the machine, not for the ground
		if (took >= SLOW_SEARCH_MILLIS)
			log.info("Path search on map {} took {} ms ({}, {} ms of processor, asked by {}): NavmeshTool {} path {} {} {} {} {} {}", worldId, took,
				!route.isEmpty() ? "found" : route.gaveUp() ? "gave up" : "no way", (cpuTime() - cpuBegan) / 1_000_000, asker(), worldId,
				String.format("%.1f", fromX), String.format("%.1f", fromY), String.format("%.1f", fromZ), String.format("%.1f", toX), String.format("%.1f", toY),
				String.format("%.1f", toZ));
	}

	/** @return The processor time of the calling thread in nanoseconds, or 0 where the platform does not measure it. */
	static long cpuTime() {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : 0;
	}

	/** @return The first caller outside this class, as {@code Class.method:line}. Walked only for a search already slow, so its cost does not matter. */
	private static String asker() {
		return StackWalker.getInstance().walk(frames -> frames.filter(frame -> !frame.getClassName().equals(NavmeshService.class.getName())).findFirst()
			.map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber())
			.orElse("?"));
	}

	/** What a reachability question came back with, for a caller that means to remember the answer. */
	public enum Reach {
		/** A route exists. */
		YES,
		/** The search finished having found nothing, which is proof that the ground does not connect. */
		NO,
		/** The search ran out of budget, or the map has no mesh to ask. Says nothing either way. */
		UNKNOWN
	}

	/**
	 * Asks whether one spot can be walked to from another, keeping the distinction {@link #canReach} throws away.
	 * <p>
	 * It exists because {@code canReach} answers a boolean, so a search that gave up for want of budget is indistinguishable from one that proved
	 * there is no way through — and a caller that writes the answer down needs them apart. A proof holds for as long as the ground does not move and
	 * is worth keeping; a give-up holds for nothing and must not be stored, or one slow search bars a shop the bot could have walked to all along.
	 */
	public Reach reach(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return Reach.UNKNOWN; // a map with no mesh refuses nothing: its bots walk without a plan, which is not the same as having nowhere to go
		long began = System.currentTimeMillis();
		long cpuBegan = cpuTime();
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ);
		logIfSlow(worldId, began, cpuBegan, fromX, fromY, fromZ, toX, toY, toZ, route);
		if (!route.isEmpty())
			return Reach.YES;
		return route.gaveUp() ? Reach.UNKNOWN : Reach.NO;
	}

	/** How long a question that only looks for a proof against a place may search, on the thread that asked it. */
	private static final long PROOF_BUDGET_MILLIS = 300;

	/**
	 * @return false only when a short search proves no walk joins the two spots; one that does not finish in {@value #PROOF_BUDGET_MILLIS} ms proves
	 *         nothing, as a search out of budget never did.
	 *         <p>
	 *         For a caller that sends the bot anyway unless the place is proved out of reach. Asked with the full budgets instead, the shop trips and the
	 *         errands spent about 250 s in 30 minutes on 25 October holding up the bots' own threads, only to give up and send the bot all the same; the
	 *         journey itself is planned on a route thread, whose refusal the mover remembers.
	 */
	public boolean isNotProvedOutOfReach(int worldId, float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return true;
		long began = System.currentTimeMillis();
		long cpuBegan = cpuTime();
		BotPathFinder.Route route = BotPathFinder.within(PROOF_BUDGET_MILLIS, () -> BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ));
		logIfSlow(worldId, began, cpuBegan, fromX, fromY, fromZ, toX, toY, toZ, route);
		return !route.isEmpty() || route.gaveUp();
	}

	/**
	 * Plans a journey and hands the result back when it is ready.
	 * <p>
	 * Short journeys are planned on the spot, in a few tens of milliseconds. A long one takes over a second, which no movement thread can wait for,
	 * so it is planned on a pool thread while the bot walks straight at its goal. It picks the route up at the end of its current leg.
	 */
	public void planRoute(Player bot, float goalX, float goalY, float goalZ, Consumer<BotPathFinder.Route> whenReady) {
		planRoute(bot, goalX, goalY, goalZ, Avoidance.NONE, whenReady);
	}

	/** @param avoid Ground to keep out of where there is another way; see {@link Avoidance}. */
	public void planRoute(Player bot, float goalX, float goalY, float goalZ, Avoidance avoid, Consumer<BotPathFinder.Route> whenReady) {
		Navmesh mesh = get(bot.getWorldId());
		if (mesh == null)
			return;
		// a short hop on a map glided down can still become a search for a chain of glides, which may take seconds: that is not for the tick thread.
		// A map with lifts and boats alone keeps its short hops here: asked as hasGlides, Sanctum's rides sent every few steps of its 200 residents to the
		// four route threads, and on 20 October a bot waited over three minutes in that queue for a walk across a room
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), goalX, goalY) <= BotPathFinder.LONG_DISTANCE && !mesh.hasAirLinks()) {
			whenReady.accept(findRoute(bot, goalX, goalY, goalZ, avoid));
			return;
		}
		// answered even when the search found nothing: "no way through" is an answer the caller waits for, and swallowing it left bots standing
		// still for ever waiting for a plan that was never coming
		host().planRoute(() -> whenReady.accept(findRoute(bot, goalX, goalY, goalZ, avoid)));
	}

	/** @return true if the map has a generated mesh that opens, which is what says whether anything planned on it can be trusted. */
	public boolean hasMesh(int worldId) {
		return get(worldId) != null;
	}

	/** @return The map, opening it on first use, or null when it has no generated file. */
	private Navmesh get(int mapId) {
		if (missing.contains(mapId))
			return null;
		return open.computeIfAbsent(mapId, id -> {
			if (!Files.isRegularFile(Navmesh.fileOf(id))) {
				log.info("No navmesh for map {}, bots there will walk without a plan", id);
				missing.add(id);
				return null;
			}
			try {
				Navmesh mesh = Navmesh.open(id);
				log.info("Opened navmesh for map {}, {}x{} cells", id, mesh.width(), mesh.height());
				return mesh;
			} catch (IOException e) {
				log.error("Could not open the navmesh for map " + id, e);
				missing.add(id);
				return null;
			}
		});
	}

	/**
	 * @return true if a chain of glide links leads from a stretch of ground under one spot to a stretch under the other: the same cheap test as
	 *         sharing a stretch, for something with wings. Read off the links and the coarse grid alone, so no route is searched.
	 */
	public boolean glidesJoin(int worldId, float fromX, float fromY, float toX, float toY) {
		Navmesh mesh = get(worldId);
		if (mesh == null || !mesh.hasGlides())
			return false;
		Set<Integer> goals = new HashSet<>();
		for (int region : regionsAt(worldId, toX, toY))
			goals.add(region);
		Set<Integer> seen = new HashSet<>();
		ArrayDeque<Integer> pending = new ArrayDeque<>();
		for (int region : regionsAt(worldId, fromX, fromY)) {
			if (seen.add(region))
				pending.add(region);
		}
		while (!pending.isEmpty()) {
			int region = pending.poll();
			if (goals.contains(region))
				return true;
			for (GlideLink link : mesh.glidesFrom(region)) {
				if (seen.add(link.toRegion()))
					pending.add(link.toRegion());
			}
		}
		return false;
	}

	/**
	 * @return The stretches given, with every stretch a lift or a boat carries a body to from them and back: Sanctum's Protectors Hall and Outer Dock are
	 *         lived in only through these. Rides are written both ways, so what is reached is also left.
	 */
	public Set<Integer> joinedByRide(int worldId, Set<Integer> regions) {
		Set<Integer> joined = new HashSet<>(regions);
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return joined;
		ArrayDeque<Integer> pending = new ArrayDeque<>(regions);
		while (!pending.isEmpty()) {
			for (GlideLink link : mesh.glidesFrom(pending.poll())) {
				if (link.ride() && joined.add(link.toRegion()))
					pending.add(link.toRegion());
			}
		}
		return joined;
	}

	/**
	 * @return For each of the stretches given, in their order, the stretches a flight goes to from it and comes back from, itself included: the ground a
	 *         resident of it can live on, since it can both get there and get home. A stretch already inside an earlier one's set is skipped, so the sets
	 *         never overlap. Only the flight links count, which exist only on a map flown everywhere; anywhere else each set is its stretch alone.
	 * @param landing Whether a link's landing is somewhere to come down at all; a link it refuses is left out both ways.
	 */
	public List<Set<Integer>> joinedByFlight(int worldId, List<Integer> regions, Predicate<GlideLink> landing) {
		Navmesh mesh = get(worldId);
		Map<Integer, List<Integer>> forward = new HashMap<>(), backward = new HashMap<>();
		if (mesh != null) {
			for (List<GlideLink> links : mesh.allGlides()) {
				for (GlideLink link : links) {
					if (!link.flight() || !landing.test(link))
						continue;
					forward.computeIfAbsent(link.fromRegion(), from -> new ArrayList<>()).add(link.toRegion());
					backward.computeIfAbsent(link.toRegion(), to -> new ArrayList<>()).add(link.fromRegion());
				}
			}
		}
		List<Set<Integer>> sets = new ArrayList<>();
		Set<Integer> taken = new HashSet<>();
		for (int region : regions) {
			if (taken.contains(region))
				continue;
			Set<Integer> joined = reached(region, forward);
			joined.retainAll(reached(region, backward));
			taken.addAll(joined);
			sets.add(joined);
		}
		return sets;
	}

	private static Set<Integer> reached(int start, Map<Integer, List<Integer>> edges) {
		Set<Integer> seen = new HashSet<>(Set.of(start));
		ArrayDeque<Integer> pending = new ArrayDeque<>(seen);
		while (!pending.isEmpty()) {
			for (int next : edges.getOrDefault(pending.poll(), List.of())) {
				if (seen.add(next))
					pending.add(next);
			}
		}
		return seen;
	}

	/**
	 * @return The stretches of walkable ground passing under a spot, or an empty array where the map has no mesh or nothing can be stood on there.
	 *         <p>
	 *         A stretch is ground a body can actually walk across, so two spots that share one are joined and two that share none are not. Read off
	 *         the coarse grid, which the mesh keeps in memory always, so this costs an array lookup — no tile is read and no route is searched. It is
	 *         a 4 m grid, so sharing a stretch is necessary for two places to be joined rather than sufficient: it settles the gross cases and leaves
	 *         the near ones to {@link #canReach}.
	 */
	public int[] regionsAt(int worldId, float x, float y) {
		Navmesh mesh = get(worldId);
		if (mesh == null)
			return new int[0];
		int coarseX = mesh.cellX(x) / mesh.coarseFactor(), coarseY = mesh.cellY(y) / mesh.coarseFactor();
		int count = mesh.coarseRegionCount(coarseX, coarseY);
		int[] regions = new int[count];
		for (int index = 0; index < count; index++)
			regions[index] = mesh.coarseRegion(coarseX, coarseY, index);
		return regions;
	}

	/**
	 * How much ground a place must have joined to it to be somewhere a bot can live, in cells — 1000 m² at half metre cells, a square 32 m on a side.
	 * <p>
	 * Measured rather than chosen, because the first number tried was chosen and was wrong by an order of magnitude. A place's ground comes in two
	 * clearly separated sizes across the maps this was run on: 23 m², 79 m², 283 m² and 615 m² for the pockets — a shelf, a rooftop, the floor of a
	 * ravine — against 4560 m², 4664 m² and 12127 m² for the whole settled part of a small map, which two or three places share and which is perfectly
	 * liveable. A bound of 12500 m² read those three as pockets and emptied two maps completely. The gap between the two groups is wide, and 1000 m²
	 * sits in the middle of it.
	 * <p>
	 * A large island that is genuinely cut off is not this test's business; the coarse grid already names those, because a stretch that big votes for a
	 * region of its own.
	 */
	private static final int LIVEABLE_CELLS = 4_000;

	/**
	 * @return true when the ground under a spot has been proved to lead nowhere, false when it has not — including where the map has no mesh, since
	 *         absence of a mesh proves nothing.
	 *         <p>
	 *         Costs no route search: see {@link BotPathFinder#isPocket}, which also says why a route search is the wrong question here.
	 */
	public boolean isPocket(int worldId, float x, float y, float z) {
		Navmesh mesh = get(worldId);
		return mesh != null && BotPathFinder.isPocket(mesh, x, y, z, LIVEABLE_CELLS);
	}

	/** @return A human readable summary, for the admin command. */
	public String describe() {
		if (open.isEmpty())
			return "No navmesh open";
		StringBuilder sb = new StringBuilder("Open navmeshes:");
		open.forEach((mapId, mesh) -> sb.append(String.format("%n  map %d, %dx%d cells, %d tiles read, %d MB", mapId, mesh.width(), mesh.height(),
			mesh.loadedTiles(), mesh.memoryFootprint() / 1048576)));
		return sb.toString();
	}

	private static class SingletonHolder {

		private static final NavmeshService INSTANCE = new NavmeshService();
	}
}
