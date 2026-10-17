package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
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
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldMap;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.services.TribeRelationService;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.world.BotPlaces;

/**
 * Serves generated maps to the bots that need them.
 * <p>
 * Opening a map reads its tile directory and nothing else, so it is instant. Tiles are then read as bots walk into them, which keeps a camp down to
 * a handful of tiles instead of the 117 MB a whole map would cost.
 */
public class NavmeshService {

	private static final Logger log = LoggerFactory.getLogger(NavmeshService.class);

	private final Map<Integer, Navmesh> open = new ConcurrentHashMap<>();
	/** Maps we already know have no file, so a bot walking there does not retry on every step. */
	private final Set<Integer> missing = ConcurrentHashMap.newKeySet();
	/** Per link, whether it comes down on siege ground; see {@link #mayLand}. */
	private final Map<GlideLink, Boolean> siegeLandings = new ConcurrentHashMap<>();
	/** How near a creature the bot cannot fight a landing may be, and the walk is kept from going, in metres. */
	private static final float DANGER_RADIUS = 40;
	/** How far above or below the bot such a creature still counts for the walk: the avoidance is drawn on the plan, and Reshanta's layers are stacked. */
	private static final float DANGER_LAYER = 150;
	/** What a metre near one costs the walk, as a multiple of its length. */
	private static final float DANGER_COST = 15;

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
		List<Vector3f> dangers = dangersFor(bot);
		Avoidance around = avoid;
		if (!dangers.isEmpty()) {
			float botZ = bot.getZ();
			around = (x, y) -> {
				for (Vector3f danger : dangers) {
					if (Math.abs(danger.z - botZ) < DANGER_LAYER && PositionUtil.getDistance(x, y, danger.x, danger.y) < DANGER_RADIUS)
						return Math.max(DANGER_COST, avoid.extra(x, y));
				}
				return avoid.extra(x, y);
			};
		}
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, bot.getX(), bot.getY(), bot.getZ(), goalX, goalY, goalZ, around, new GlideRoutes.AirTest() {

			@Override
			public boolean isClear(GlideLink link) {
				return canGlide && isLinkClear(bot, link);
			}

			@Override
			public boolean mayLand(GlideLink link) {
				return NavmeshService.this.mayLand(bot, link) && dangers.stream()
					.noneMatch(danger -> PositionUtil.getDistance(danger.x, danger.y, danger.z, link.toX(), link.toY(), link.toZ()) < DANGER_RADIUS);
			}
		});
		if (route.isEmpty() && route.gaveUp())
			log.debug("Path search for {} gave up short of {} {}", bot.getName(), goalX, goalY);
		return route;
	}

	/**
	 * @return Where the creatures a bot cannot fight stand right now, on a map flown everywhere: alive, aggressive towards it and more than a fight's
	 *         level gap above it. Asked of the living world rather than of the spawn data, because the worst of them are not in it: a squad of level 40
	 *         flew onto Reshanta's upper layer beside dread remnants of level 65, Balaur elites a fortress puts there, and died to a man.
	 */
	private static List<Vector3f> dangersFor(Player bot) {
		WorldMap map = World.getInstance().getWorldMap(bot.getWorldId());
		if (map == null || !map.isFlightAllowed() || bot.getPosition() == null || bot.getPosition().getWorldMapInstance() == null)
			return List.of();
		List<Vector3f> found = new ArrayList<>();
		bot.getPosition().getWorldMapInstance().forEachNpc(npc -> {
			if (npc.isSpawned() && !npc.isDead() && npc.getLevel() > bot.getLevel() + BotTargetSelector.MAX_LEVEL_GAP && bot.isEnemy(npc)
				&& TribeRelationService.isAggressive(npc, bot))
				found.add(new Vector3f(npc.getX(), npc.getY(), npc.getZ()));
		});
		return found;
	}

	/**
	 * @return false for a link that comes down inside a fortress's zone, or on ground only the other faction's people live on: a crossing
	 *         into either is a fight the bot did not choose, refused until wild PvP is a decision rather than an accident. The siege half is remembered
	 *         per link, since the chains ask it of every link they look at.
	 */
	private boolean mayLand(Player bot, GlideLink link) {
		if (siegeLandings.computeIfAbsent(link, l -> BotPlaces.isSiegeGround(bot.getWorldId(), l.toX(), l.toY(), l.toZ())))
			return false;
		Race owner = BotPlaces.ownerOfGround(bot.getWorldId(), link.toRegion());
		return owner == null || owner == bot.getRace();
	}

	/** @return Whether a link can be taken now: a crossing flown at one of its heights, or a glide down a clear line. A refusal is logged at debug, the one way to see which link of a plan was turned down. */
	private static boolean isLinkClear(Player bot, GlideLink link) {
		boolean clear = link.flight() ? BotFlight.crossing(bot, new Vector3f(link.fromX(), link.fromY(), link.fromZ()), new Vector3f(link.toX(), link.toY(), link.toZ())) != null
			: isAirClear(bot, link);
		if (!clear && log.isDebugEnabled())
			log.debug("Bot {} finds the air blocked on the {} {} {} {} -> {} {} {}", bot.getName(), link.flight() ? "crossing" : "glide", Math.round(link.fromX()),
				Math.round(link.fromY()), Math.round(link.fromZ()), Math.round(link.toX()), Math.round(link.toY()), Math.round(link.toZ()));
		return clear;
	}

	/**
	 * @return Whether the air between the two ends of a glide holds nothing solid, asked of the game's own geometry. The links were found from the floors alone, which cannot say whether
	 *         a wall stands between an edge and the ground below it.
	 */
	private static boolean isAirClear(Player bot, GlideLink link) {
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
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ);
		logIfSlow(worldId, began, fromX, fromY, fromZ, toX, toY, toZ, route);
		return !route.isEmpty();
	}

	/** A search that took longer than this is logged with its ends, which is what finding out why takes ({@code NavmeshTool <map> path}). */
	private static final long SLOW_SEARCH_MILLIS = 1000;

	private static void logIfSlow(int worldId, long began, float fromX, float fromY, float fromZ, float toX, float toY, float toZ, BotPathFinder.Route route) {
		long took = System.currentTimeMillis() - began;
		if (took >= SLOW_SEARCH_MILLIS)
			log.info("Path search on map {} took {} ms ({}): NavmeshTool {} path {} {} {} {} {} {}", worldId, took,
				!route.isEmpty() ? "found" : route.gaveUp() ? "gave up" : "no way", worldId, String.format("%.1f", fromX), String.format("%.1f", fromY),
				String.format("%.1f", fromZ), String.format("%.1f", toX), String.format("%.1f", toY), String.format("%.1f", toZ));
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
		BotPathFinder.Route route = BotPathFinder.findPath(mesh, fromX, fromY, fromZ, toX, toY, toZ);
		logIfSlow(worldId, began, fromX, fromY, fromZ, toX, toY, toZ, route);
		if (!route.isEmpty())
			return Reach.YES;
		return route.gaveUp() ? Reach.UNKNOWN : Reach.NO;
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
		// a short hop on a map glided down can still become a search for a chain of glides, which may take seconds: that is not for the tick thread
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), goalX, goalY) <= BotPathFinder.LONG_DISTANCE && !mesh.hasGlides()) {
			whenReady.accept(findRoute(bot, goalX, goalY, goalZ, avoid));
			return;
		}
		// answered even when the search found nothing: "no way through" is an answer the caller waits for, and swallowing it left bots standing
		// still for ever waiting for a plan that was never coming
		BotScheduler.getInstance().planRoute(() -> whenReady.accept(findRoute(bot, goalX, goalY, goalZ, avoid)));
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
