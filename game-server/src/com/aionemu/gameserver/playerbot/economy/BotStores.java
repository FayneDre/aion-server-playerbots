package com.aionemu.gameserver.playerbot.economy;

import java.time.LocalDate;
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
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.trade.TradePSItem;
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
	/** Under this many lines left, a shift opens with a restock. */
	private static final int RESTOCK_UNDER = 4;
	/** How many lines a restock aims for. */
	private static final int STOCKED_LINES = 8;
	/** How many restocks a keeper gets in a day, which is what keeps generated goods from flooding a town. */
	private static final int RESTOCKS_PER_DAY = 2;
	/** How far round a broker or a warehouse keeper a stall may be, and how far from the next stall it must be, in metres. */
	private static final float STALL_SPREAD = 15, STALL_SPACING = 3;
	private static final int STALL_ATTEMPTS = 12;

	/** What a keeper has on its shelf: each piece's object id and when it was stocked. Kept in memory: a restart sells the leftovers to an npc as junk. */
	private static final class Shelf {
		final Map<Integer, Long> stockedAt = new ConcurrentHashMap<>();
		LocalDate day = LocalDate.now();
		int restocks;
	}

	private static final Map<Integer, Shelf> shelves = new ConcurrentHashMap<>();
	/** The stall each keeper holds, by map, so two keepers never stand in one spot. */
	private static final Map<Integer, Map<Integer, Vector3f>> stallsByMap = new ConcurrentHashMap<>();

	private BotStores() {
	}

	/**
	 * @param crowded The caller's own objection to a spot: somebody standing there.
	 * @return The keeper's stall, the same one while it holds it, or null when no spot round the map's brokers and warehouses is free.
	 */
	public static synchronized Vector3f stallFor(Player keeper, Predicate<Vector3f> crowded) {
		Map<Integer, Vector3f> stalls = stallsByMap.computeIfAbsent(keeper.getWorldId(), id -> new ConcurrentHashMap<>());
		Vector3f held = stalls.get(keeper.getObjectId());
		if (held != null)
			return held;
		List<Vector3f> sites = new ArrayList<>(TownServices.keepersOf(keeper, TownServices.Service.BROKER));
		sites.addAll(TownServices.keepersOf(keeper, TownServices.Service.WAREHOUSE));
		if (sites.isEmpty() || stalls.size() >= STORES_PER_MAP)
			return null;
		Vector3f site = sites.get(Math.floorMod(keeper.getObjectId(), sites.size()));
		Predicate<Vector3f> taken = spot -> crowded.test(spot) || stalls.values().stream()
			.anyMatch(other -> PositionUtil.getDistance(other.getX(), other.getY(), spot.getX(), spot.getY()) < STALL_SPACING);
		Vector3f stall = BotPlaces.spotAround(keeper.getWorldId(), site, STALL_SPREAD, STALL_ATTEMPTS, keeper.getObjectId(), taken);
		if (stall != null)
			stalls.put(keeper.getObjectId(), stall);
		return stall;
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
		List<Item> lines = linesOf(keeper, shelf);
		if (lines.size() < RESTOCK_UNDER && mayRestock(shelf)) {
			List<Integer> templates = BotStoreStock.stock(keeper, STOCKED_LINES - lines.size());
			shelf.restocks++;
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
		BotStorePricing.Kind kind = BotStoreStock.kindOf(BotStoreStock.themeOf(keeper.getObjectId()));
		long now = System.currentTimeMillis();
		TradePSItem[] goods = lines.stream().limit(MAX_LINES).map(item -> {
			ItemTemplate template = item.getItemTemplate();
			long hours = (now - shelf.stockedAt.get(item.getObjectId())) / 3_600_000L;
			long price = BotStorePricing.price(kind, template.getPrice(), template.getItemQuality(), template.getLevel(), keeper.getObjectId(), hours);
			return new TradePSItem(item.getObjectId(), item.getItemId(), item.getItemCount(), price);
		}).toArray(TradePSItem[]::new);
		PrivateStoreService.createStoreWithItems(keeper, goods);
		if (!keeper.hasStore()) {
			log.info("Bot {} could not open its store: the engine refused its {} line(s)", keeper.getName(), goods.length);
			return false;
		}
		PrivateStoreService.openPrivateStore(keeper, BotStoreStock.nameOf(keeper));
		log.info("Bot {} opens its store \"{}\" with {} line(s) at {}", keeper.getName(), BotStoreStock.nameOf(keeper), goods.length,
			String.format("%.1f %.1f %.1f", keeper.getX(), keeper.getY(), keeper.getZ()));
		return true;
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
			if (item == null || item.isEquipped())
				return true;
			lines.add(item);
			return false;
		});
		lines.sort((a, b) -> Long.compare(shelf.stockedAt.get(a.getObjectId()), shelf.stockedAt.get(b.getObjectId())));
		return lines;
	}

	private static boolean mayRestock(Shelf shelf) {
		LocalDate today = LocalDate.now();
		if (!today.equals(shelf.day)) {
			shelf.day = today;
			shelf.restocks = 0;
		}
		return shelf.restocks < RESTOCKS_PER_DAY;
	}
}
