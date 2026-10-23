package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.trade.TradePSItem;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.TownServices;
import com.aionemu.gameserver.services.PrivateStoreService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Bots' private stores: where each keeper's stall stands, what is on its shelf and since when, and the opening and closing of the engine's store.
 * <p>
 * The engine's store is a player's, so everything a player's client does around it is done here: walking to a spot, putting the goods out, naming the
 * store, and closing it before going anywhere. Leaving the world does not close a store in the engine, which a real player's logout never needs, so a
 * bot's departure closes it here. See docs/bot-stores-plan.md.
 */
public final class BotStores {

	private static final Logger log = LoggerFactory.getLogger(BotStores.class);

	/** The most stores open on one map: about 40 characters standing in sight is what kept a tester's client over 40 fps on 20 October. */
	private static final int STORES_PER_MAP = 40;
	/** The engine's limit on a store's lines. */
	private static final int MAX_LINES = 10;
	/** How many lines a store is stocked up to whenever it opens. */
	private static final int STOCKED_LINES = 8;
	/**
	 * How often a store is renewed during a shift, and how much later a keeper of its own may come round to it: the tester asked for stores renewed
	 * regularly, and every keeper on the hour would be every store of a market closing in the same second.
	 */
	private static final long RENEW_EVERY_MILLIS = 60 * 60_000L, RENEW_SPREAD_MILLIS = 30 * 60_000L;
	/**
	 * How long a piece stays on a shelf unsold before a renewal takes it away for good, which is what keeps a store from showing the same goods all day
	 * and what bounds the generated goods: a keeper puts out eight lines an hour at most, and what nobody bought goes back to nothing.
	 */
	private static final long STALE_MILLIS = 2 * 60 * 60_000L;
	/**
	 * How far round a broker or a warehouse keeper a stall may be on a map with no markets of its own, and how far from the next stall it must be, in
	 * metres. The spacing was 3, and a tester found Pandaemonium's Market Street packed shoulder to shoulder on 23 October.
	 */
	private static final float STALL_SPREAD = 15, STALL_SPACING = 6;
	/**
	 * How far each stall of a market's grid is moved off its grid point, at most, on each axis, in metres, and how near two stalls may then stand.
	 * On the bare grid the keepers of Market Street stood in rows and columns, which no crowd of players does (23 October).
	 */
	private static final float STALL_JITTER = 2, STALL_NEAREST = 4.5f;
	/** Every stall each market holds, worked out once per map and market. */
	private static final Map<String, List<Vector3f>> stallsOfMarket = new ConcurrentHashMap<>();

	/** What a keeper has on its shelf: each piece's object id and when it was stocked, and when the store was last renewed. Kept in memory. */
	private static final class Shelf {
		final Map<Integer, Long> stockedAt = new ConcurrentHashMap<>();
		volatile long renewedAt;
	}

	private static final Map<Integer, Shelf> shelves = new ConcurrentHashMap<>();
	/** The stall each keeper holds, by map, so two keepers never stand in one spot. */
	private static final Map<Integer, Map<Integer, Vector3f>> stallsByMap = new ConcurrentHashMap<>();

	private BotStores() {
	}

	/** Where a town's stores gather, as players put theirs, and what share of its keepers each place takes. */
	record Market(float x, float y, float z, float spread, int weight) {
	}

	/**
	 * The market places a tester stood at with {@code //bot note} on 20 October, and the shares asked for: about even between the street from the Exalted
	 * Path to the Elyos Square, the entrance of the Hall of Prosperity and the hall itself, a little more at the entrance. Stalls round the brokers and the
	 * warehouses put nearly every store of Sanctum in one hall. A map with no entry here still uses its brokers and warehouses.
	 */
	private static final Map<Integer, List<Market>> MARKETS = Map.of(110010000, List.of(
		new Market(1357, 1512, 569, 18, 10), // the entrance of the Exalted Path
		new Market(1400, 1513, 573, 18, 10), // half way along the street
		new Market(1451, 1511, 573, 18, 10), // the entrance of the Elyos Square
		new Market(1471, 1442, 573, 22, 40), // in front of the Hall of Prosperity
		new Market(1403, 1412, 573, 20, 30), // in the Hall of Prosperity
		// round the middle of the Elyos Square, whose very centre is blocked: added on 23 October, when stalls 6 m apart left seven keepers with no room
		new Market(1529, 1512, 566, 20, 10)),
		// Pandaemonium, from a tester's notes of 23 October: stalls round its brokers and warehouses put 80 of them in one hall and 35 in two others
		120010000, List.of(
		new Market(1609, 1400, 193, 20, 10), // on the Vifrost Bridge
		new Market(1393, 1401, 208, 20, 10), // in Market Street
		new Market(1300, 1415, 208, 20, 10), // on Pandaemonium Plaza
		new Market(1323, 1481, 210, 20, 10))); // by the Temple of Gold

	/**
	 * @param crowded The caller's own objection to a spot: somebody standing there.
	 * @return The keeper's stall, the same one while it holds it, or null when no spot of the map's markets is free.
	 */
	public static synchronized Vector3f stallFor(Player keeper, Predicate<Vector3f> crowded) {
		Map<Integer, Vector3f> stalls = stallsByMap.computeIfAbsent(keeper.getWorldId(), id -> new ConcurrentHashMap<>());
		Vector3f held = stalls.get(keeper.getObjectId());
		if (held != null)
			return held;
		if (stalls.size() >= STORES_PER_MAP)
			return null;
		Predicate<Vector3f> taken = spot -> crowded.test(spot) || stalls.values().stream()
			.anyMatch(other -> PositionUtil.getDistance(other.getX(), other.getY(), spot.getX(), spot.getY()) < STALL_NEAREST);
		// the keeper's own market first, then the others: a full one sends it along the street rather than home for half an hour
		for (Market market : marketsFor(keeper)) {
			List<Vector3f> spots = stallsOf(keeper.getWorldId(), market);
			// from a spot of its own, the same every day, and on round the market
			int first = spots.isEmpty() ? 0 : Math.floorMod(keeper.getObjectId() * 0x9E3779B9, spots.size());
			for (int i = 0; i < spots.size(); i++) {
				Vector3f stall = spots.get((first + i) % spots.size());
				if (!taken.test(stall)) {
					stalls.put(keeper.getObjectId(), stall);
					return stall;
				}
			}
		}
		return null;
	}

	/**
	 * @return Every stall a market holds: a grid of spots {@link #STALL_SPACING} apart within its spread, each moved off its point by up to
	 *         {@link #STALL_JITTER}, on walkable ground joined to its middle.
	 *         <p>
	 *         A grid rather than spots drawn round the middle at each asking. Drawn spots landed on a wall, a stair or a passer-by often enough that a
	 *         keeper was turned away from a market with room left in it: Sanctum's Hall of Prosperity entrance, weighted for 40 stores, held 8 on
	 *         23 October, and the town 28. A grid says once, in the log, how many stores each market really holds.
	 */
	private static List<Vector3f> stallsOf(int worldId, Market market) {
		return stallsOfMarket.computeIfAbsent(worldId + "/" + market, key -> {
			NavmeshService navmesh = NavmeshService.getInstance();
			List<Vector3f> spots = new ArrayList<>();
			float spread = market.spread();
			for (float dx = -spread; dx <= spread; dx += STALL_SPACING) {
				for (float dy = -spread; dy <= spread; dy += STALL_SPACING) {
					if (dx * dx + dy * dy > spread * spread)
						continue;
					// moved off the grid point by an amount of its own, the same every day since it is drawn from the point
					int seed = Float.hashCode(market.x() + dx) * 31 + Float.hashCode(market.y() + dy);
					float jitterX = STALL_JITTER * (Math.floorMod(seed * 0x9E3779B9, 2001) / 1000f - 1);
					float jitterY = STALL_JITTER * (Math.floorMod(seed * 0x85EBCA6B, 2001) / 1000f - 1);
					Vector3f ground = navmesh.groundNear(worldId, market.x() + dx + jitterX, market.y() + dy + jitterY, market.z());
					if (ground == null || ground.getZ() - market.z() > BotPlaces.STANDS_ON_A_STRUCTURE)
						continue;
					// the mesh snaps a spot onto the nearest walkable cell, so two spots of the grid can land together
					if (spots.stream().anyMatch(other -> PositionUtil.getDistance(other.getX(), other.getY(), ground.getX(), ground.getY()) < STALL_NEAREST))
						continue;
					if (isAwayFromEdges(navmesh, worldId, ground)
						&& navmesh.canReach(worldId, ground.getX(), ground.getY(), ground.getZ(), market.x(), market.y(), market.z()))
						spots.add(ground);
				}
			}
			log.info("Market at {} {} on map {} holds {} stalls", Math.round(market.x()), Math.round(market.y()), worldId, spots.size());
			return List.copyOf(spots);
		});
	}

	/** How far round a stall the floor must carry on at its height, in metres, and how much higher or lower it may be there. */
	private static final float[] EDGE_RADII = { 1, 2 };
	private static final float EDGE_STEP = 1;

	/**
	 * @return true when the floor round a spot carries on at its height in every direction: not at the edge of a deck or a bridge.
	 *         <p>
	 *         Railings are not in the mesh, the drop behind them is: a keeper on Pandaemonium's Vifrost Bridge opened its store standing in the rail,
	 *         the floor 8.5 m down a metre away (23 October).
	 */
	private static boolean isAwayFromEdges(NavmeshService navmesh, int worldId, Vector3f spot) {
		for (float radius : EDGE_RADII) {
			for (int k = 0; k < 8; k++) {
				double angle = Math.PI * k / 4;
				float x = spot.getX() + (float) Math.cos(angle) * radius, y = spot.getY() + (float) Math.sin(angle) * radius;
				boolean level = false;
				for (Vector3f floor : navmesh.floorsAt(worldId, x, y, spot.getZ()))
					level |= Math.abs(floor.getZ() - spot.getZ()) <= EDGE_STEP;
				if (!level)
					return false;
			}
		}
		return true;
	}

	/** @return The markets the keeper tries, its own first, chosen by its id with each market's weight, the same every day. */
	private static List<Market> marketsFor(Player keeper) {
		List<Market> markets = MARKETS.get(keeper.getWorldId());
		if (markets == null)
			markets = aroundTheCounters(keeper);
		if (markets.isEmpty())
			return markets;
		int total = markets.stream().mapToInt(Market::weight).sum();
		int roll = Math.floorMod(keeper.getObjectId() * 0x2C1B3C6D, total);
		int own = 0;
		for (; own < markets.size() - 1 && roll >= markets.get(own).weight(); own++)
			roll -= markets.get(own).weight();
		List<Market> ordered = new ArrayList<>(markets.size());
		for (int i = 0; i < markets.size(); i++)
			ordered.add(markets.get((own + i) % markets.size()));
		return ordered;
	}

	/**
	 * @return The map's brokers and warehouse keepers as markets of equal weight, for a town nobody has said where its stores gather: the floor in front of
	 *         each counter, not the keeper's feet. Pandaemonium's stand in pockets behind theirs, and a stall looked for round their feet was refused 27
	 *         times of 31 on 20 October, no spot free or no way to it, as shop trips were on 18 October.
	 */
	private static List<Market> aroundTheCounters(Player keeper) {
		List<Vector3f> keepers = new ArrayList<>(TownServices.keepersOf(keeper, TownServices.Service.BROKER));
		keepers.addAll(TownServices.keepersOf(keeper, TownServices.Service.WAREHOUSE));
		List<Market> markets = new ArrayList<>(keepers.size());
		for (Vector3f stands : keepers) {
			Vector3f front = BotVendorManager.frontOf(keeper.getWorldId(), stands);
			Vector3f site = front != null ? front : stands;
			markets.add(new Market(site.getX(), site.getY(), site.getZ(), STALL_SPREAD, 1));
		}
		return markets;
	}

	/** Gives up the keeper's stall, for another keeper to take. */
	public static void release(Player keeper) {
		Map<Integer, Vector3f> stalls = stallsByMap.get(keeper.getWorldId());
		if (stalls != null)
			stalls.remove(keeper.getObjectId());
	}

	/**
	 * Puts the keeper's goods out and opens its store where it stands, restocking first when its shelf runs low.
	 *
	 * @return true if the store is open.
	 */
	public static boolean open(Player keeper) {
		if (keeper.hasStore())
			return true;
		Shelf shelf = shelves.computeIfAbsent(keeper.getObjectId(), id -> new Shelf());
		if (shelf.stockedAt.isEmpty()) {
			// A shelf is kept in memory, and a restart forgets it while the goods stay in the bag. Left there, they were neither sold nor sellable: on
			// 20 October, after a day of restarts, four Sanctum keepers had bags too full to restock and opened nothing. What fits the theme goes back
			// on the shelf, as freshly stocked.
			long now = System.currentTimeMillis();
			for (Item item : keeper.getInventory().getItems()) {
				if (!item.isEquipped() && BotStoreStock.fitsTheme(keeper, item.getItemTemplate()))
					shelf.stockedAt.put(item.getObjectId(), now);
			}
		}
		List<Item> lines = linesOf(keeper, shelf);
		if (lines.size() < STOCKED_LINES) {
			List<Integer> templates = BotStoreStock.stock(keeper, STOCKED_LINES - lines.size());
			long now = System.currentTimeMillis();
			// what the restock put in the bag is whatever of its templates the shelf did not already hold
			for (Item item : keeper.getInventory().getItems()) {
				if (!item.isEquipped() && templates.contains(item.getItemId()))
					shelf.stockedAt.putIfAbsent(item.getObjectId(), now);
			}
			lines = linesOf(keeper, shelf);
		}
		if (lines.isEmpty())
			return false;
		long now = System.currentTimeMillis();
		TradePSItem[] goods = lines.stream().limit(MAX_LINES).map(item -> {
			ItemTemplate template = item.getItemTemplate();
			long hours = (now - shelf.stockedAt.get(item.getObjectId())) / 3_600_000L;
			long price = BotStorePricing.price(BotStoreStock.kindOf(template), template.getPrice(), template.getItemQuality(), template.getLevel(), keeper.getObjectId(), hours);
			return new TradePSItem(item.getObjectId(), item.getItemId(), item.getItemCount(), price);
		}).toArray(TradePSItem[]::new);
		PrivateStoreService.createStoreWithItems(keeper, goods);
		if (!keeper.hasStore()) {
			// the engine says why only to a client; the states it asks about are said here instead
			log.info("Bot {} could not open its store: the engine refused its {} line(s) (moving {}, weapon out {}, seated {})", keeper.getName(),
				goods.length, keeper.getMoveController().isInMove(), keeper.isInAttackMode(), keeper.isInState(CreatureState.RESTING));
			return false;
		}
		PrivateStoreService.openPrivateStore(keeper, BotStoreStock.nameOf(keeper));
		shelf.renewedAt = now;
		log.info("Bot {} opens its store \"{}\" with {} line(s) at {}", keeper.getName(), BotStoreStock.nameOf(keeper), goods.length,
			String.format("%.1f %.1f %.1f", keeper.getX(), keeper.getY(), keeper.getZ()));
		return true;
	}

	/**
	 * Renews an open store when its time comes: shuts it, takes away what has waited unsold for two hours, stocks up again and opens it where it stands.
	 * Called on every tick of a keeper at its stall, and does nothing most of them.
	 */
	public static void renewIfDue(Player keeper) {
		Shelf shelf = shelves.get(keeper.getObjectId());
		long now = System.currentTimeMillis();
		if (shelf == null || !keeper.hasStore() || now - shelf.renewedAt < RENEW_EVERY_MILLIS + Math.floorMod(keeper.getObjectId() * 0x3C6EF372L, RENEW_SPREAD_MILLIS))
			return;
		PrivateStoreService.closePrivateStore(keeper); // the stall is kept: the keeper does not move
		int taken = 0;
		for (Item item : linesOf(keeper, shelf)) {
			if (now - shelf.stockedAt.get(item.getObjectId()) >= STALE_MILLIS) {
				shelf.stockedAt.remove(item.getObjectId());
				keeper.getInventory().delete(item);
				taken++;
			}
		}
		log.info("Bot {} renews its store, {} unsold line(s) taken away", keeper.getName(), taken);
		open(keeper); // refused, the store stays shut and the next tick finds the keeper at its stall with no store, as at the start of a shift
	}

	/** Closes the keeper's store, if it has one open, and gives up its stall. */
	public static void close(Player keeper) {
		release(keeper);
		if (!keeper.hasStore())
			return;
		int left = keeper.getStore().getSoldItems().size();
		PrivateStoreService.closePrivateStore(keeper);
		log.info("Bot {} closes its store with {} line(s) left", keeper.getName(), left);
	}

	/** @return true if the piece is on the keeper's shelf, which is neither worn nor sold to an npc. */
	public static boolean isOnShelf(Player keeper, Item item) {
		Shelf shelf = shelves.get(keeper.getObjectId());
		return shelf != null && shelf.stockedAt.containsKey(item.getObjectId());
	}

	/** @return The pieces of the shelf still in the keeper's bag, oldest first, the ones it no longer holds forgotten. */
	private static List<Item> linesOf(Player keeper, Shelf shelf) {
		List<Item> lines = new ArrayList<>();
		shelf.stockedAt.keySet().removeIf(objectId -> {
			Item item = keeper.getInventory().getItemByObjId(objectId);
			// the engine's own test, which refuses the whole store for one piece that fails it
			if (item == null || item.isEquipped() || item.getPackCount() <= 0 && !item.isTradeable())
				return true;
			lines.add(item);
			return false;
		});
		lines.sort((a, b) -> Long.compare(shelf.stockedAt.get(a.getObjectId()), shelf.stockedAt.get(b.getObjectId())));
		return lines;
	}
}
