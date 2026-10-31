package com.aionemu.gameserver.playerbot.economy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.BrokerItem;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotCreationService;
import com.aionemu.gameserver.services.BrokerService;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * The auction house (docs/capitals/plans/auction-house-plan.md): a seller for each race's broker that is not a player, and a buyer of what players list.
 * <p>
 * The seller is a character per race that never logs in ({@link #sellerOf}); every pass settles what it sold and what expired, withdraws what the
 * blacklist now names, counts its listings per category of the client's broker and lists new ones up to each category's share of the size. An empty
 * house is filled in steps of half a minute over the minutes the property gives, so the start is not slowed. A listing is a fresh item from the share's
 * draw (category, quality, level curve), priced by {@link BrokerHousePricing}: an expired or sold listing is therefore replaced rather than the same
 * one listed again. All of it is off unless {@code gameserver.playerbot.broker.enable} is set.
 */
public final class BrokerHouse {

	private static final Logger log = LoggerFactory.getLogger(BrokerHouse.class);
	private static final long FILL_STEP_MILLIS = 30_000;
	/** The names a house character is given, tried in order for each race; the name generator's is the fallback. */
	private static final Map<Race, String> PREFERRED_NAMES = Map.of(Race.ELYOS, "BotSellerElyos", Race.ASMODIANS, "BotSellerAsmo");
	/** Listings below this many are not worth a memory figure. */
	private static final int MEASURABLE = 200;

	private static final BrokerHouse INSTANCE = new BrokerHouse();

	private final Random random = new Random();
	private final Map<Integer, BrokerItemMask> categoryByTemplate = new ConcurrentHashMap<>();
	private final Map<Race, BrokerHouseStock.Pool> pools = new EnumMap<>(Race.class);
	private final BrokerHouseBuyer buyer = new BrokerHouseBuyer(this::categoryOf);
	private Set<Integer> blacklist = Set.of();
	private boolean started;
	private boolean memoryMeasured;
	private long heapBeforeFill = -1;

	private BrokerHouse() {
	}

	public static BrokerHouse getInstance() {
		return INSTANCE;
	}

	/** Starts the seller and the buyer if the property says so. Called once at startup; does nothing when the house is off. */
	public synchronized void start() {
		if (started || !PlayerBotConfig.BROKER_ENABLE)
			return;
		started = true;
		blacklist = parseIds(PlayerBotConfig.BROKER_BLACKLIST);
		log.info("Broker house starts: {} listings a race, a pass every {} minute(s), filled over {} minute(s)", PlayerBotConfig.BROKER_SIZE,
			PlayerBotConfig.BROKER_INTERVAL_MINUTES, PlayerBotConfig.BROKER_FILL_MINUTES);
		ThreadPoolManager.getInstance().schedule(this::sellerPass, 60, TimeUnit.SECONDS);
		if (PlayerBotConfig.BROKER_BUYER_ENABLE)
			ThreadPoolManager.getInstance().scheduleAtFixedRate(this::buyerPass, 90_000, Math.max(5, PlayerBotConfig.BROKER_BUYER_INTERVAL_SECONDS) * 1000L);
	}

	static Set<Integer> parseIds(String text) {
		Set<Integer> ids = new HashSet<>();
		if (text != null)
			for (String part : text.split(",")) {
				try {
					if (!part.isBlank())
						ids.add(Integer.parseInt(part.trim()));
				} catch (NumberFormatException e) {
					log.warn("Ignoring '{}' in the broker blacklist: not a template id", part.trim());
				}
			}
		return ids;
	}

	private BrokerItemMask categoryOf(ItemTemplate template) {
		// computeIfAbsent cannot hold a null, and a template shown in no category is rare: it is looked up again
		BrokerItemMask known = categoryByTemplate.get(template.getTemplateId());
		if (known != null)
			return known;
		BrokerItemMask category = BrokerHouseStock.categoryOf(template);
		if (category != null)
			categoryByTemplate.put(template.getTemplateId(), category);
		return category;
	}

	private void buyerPass() {
		try {
			buyer.pass(System.currentTimeMillis(), LocalDate.now());
		} catch (RuntimeException e) {
			log.error("The broker house's buyer failed", e);
		}
	}

	private void sellerPass() {
		long wait = TimeUnit.MINUTES.toMillis(Math.max(1, PlayerBotConfig.BROKER_INTERVAL_MINUTES));
		try {
			if (heapBeforeFill < 0)
				heapBeforeFill = usedHeap();
			boolean unfinished = false;
			for (Race race : List.of(Race.ELYOS, Race.ASMODIANS))
				unfinished |= passFor(race);
			if (unfinished)
				wait = FILL_STEP_MILLIS;
			else
				measureMemory();
		} catch (RuntimeException e) {
			log.error("The broker house's seller failed", e);
		}
		ThreadPoolManager.getInstance().schedule(this::sellerPass, wait);
	}

	/** @return true if the race's house still lacks listings after this pass's share of the work. */
	private boolean passFor(Race race) {
		int sellerId = sellerOf(race);
		if (sellerId == 0)
			return false;
		BrokerService broker = BrokerService.getInstance();
		int settled = broker.settleHouse(sellerId);
		List<BrokerItem> own = new ArrayList<>();
		int withdrawn = 0;
		for (BrokerItem listing : broker.getListings(race)) {
			if (listing.getSellerId() != sellerId)
				continue;
			// withdrawn: what the blacklist names, and what a player never meets on the market (the monsters' gear, event and cash shop goods, test pieces) that an earlier pass listed
			boolean unwanted = blacklist.contains(listing.getItemId()) || listing.getItem() != null && !BrokerHouseStock.isPlayerGoods(listing.getItem().getItemTemplate());
			if (unwanted && broker.withdrawForHouse(listing))
				withdrawn++;
			else
				own.add(listing);
		}
		Map<BrokerItemMask, Integer> held = new EnumMap<>(BrokerItemMask.class);
		for (BrokerItem listing : own) {
			BrokerItemMask category = listing.getItem() == null ? null : categoryOf(listing.getItem().getItemTemplate());
			if (category != null)
				held.merge(category, 1, Integer::sum);
		}
		BrokerHouseStock.Pool pool = pools.computeIfAbsent(race, r -> BrokerHouseStock.poolFor(r, blacklist));
		Map<BrokerItemMask, Integer> shares = BrokerHouseShares.parse(PlayerBotConfig.BROKER_CATEGORY_SHARES, BrokerItemMask.class);
		shares.keySet().removeIf(category -> !BrokerHouseStock.CATEGORIES.contains(category) || !pool.has(category));
		Map<BrokerItemMask, Integer> targets = BrokerHouseShares.split(PlayerBotConfig.BROKER_SIZE, shares, BrokerItemMask.class);
		Map<BrokerItemMask, Integer> lacking = BrokerHouseShares.missing(targets, held, BrokerItemMask.class);
		int lack = lacking.values().stream().mapToInt(Integer::intValue).sum();
		boolean filling = own.size() < PlayerBotConfig.BROKER_SIZE / 2;
		int steps = Math.max(1, (int) (TimeUnit.MINUTES.toMillis(Math.max(1, PlayerBotConfig.BROKER_FILL_MINUTES)) / FILL_STEP_MILLIS));
		int budget = filling ? Math.max(1, PlayerBotConfig.BROKER_SIZE / steps) : lack;
		int listed = 0;
		Map<BrokerItemMask, Integer> remaining = new EnumMap<>(lacking);
		while (listed < budget && !remaining.isEmpty()) {
			for (BrokerItemMask category : new ArrayList<>(remaining.keySet())) {
				if (listed >= budget)
					break;
				if (listOne(race, sellerId, category, pool))
					listed++;
				if (remaining.merge(category, -1, Integer::sum) <= 0)
					remaining.remove(category);
			}
		}
		if (listed > 0 || settled > 0 || withdrawn > 0)
			log.info("Broker house {}: {} listed, {} settled, {} withdrawn, {} held of {} (still lacking {})", race, listed, settled, withdrawn,
				own.size() + listed, PlayerBotConfig.BROKER_SIZE, Math.max(0, lack - listed));
		// unfinished only while the pass makes headway: a category whose draws always fail (no price, no item) would otherwise keep the pass at the 30 s step for ever
		return lack - listed > 0 && listed > 0;
	}

	/** Lists one new item of the category, drawn by the quality shares and the level curve. @return false if nothing could be drawn or listed. */
	private boolean listOne(Race race, int sellerId, BrokerItemMask category, BrokerHouseStock.Pool pool) {
		Map<ItemQuality, Integer> qualities = BrokerHouseShares.parse(PlayerBotConfig.BROKER_QUALITY_SHARES, ItemQuality.class);
		ItemQuality quality = BrokerHouseShares.draw(qualities, q -> pool.has(category, q), random);
		if (quality == null) // no share names a quality this category has: any it has
			quality = java.util.Arrays.stream(ItemQuality.values()).filter(q -> pool.has(category, q)).findFirst().orElse(null);
		if (quality == null)
			return false;
		ItemTemplate template = BrokerHouseStock.draw(pool.of(category, quality), random);
		boolean material = category == BrokerItemMask.CRAFT;
		long market = BrokerHousePricing.market(template, material);
		if (market <= 0)
			return false;
		long count = BrokerHouseStock.stackSize(template, category, random);
		long price = BrokerHousePricing.ask(market, BrokerHousePricing.floor(template), BrokerHousePricing.ceiling(template), random.nextDouble());
		Item item = ItemFactory.newItem(template.getTemplateId(), count);
		if (item == null)
			return false;
		return BrokerService.getInstance().registerForHouse(race, sellerId, item, price, count > 1) != null;
	}

	/** Logs what one listing costs in memory, once, when the first fill is complete: the heap grew by about the listings' share of it. */
	private void measureMemory() {
		if (memoryMeasured)
			return;
		int listings = BrokerService.getInstance().getListings(Race.ELYOS).size() + BrokerService.getInstance().getListings(Race.ASMODIANS).size();
		if (listings < MEASURABLE || heapBeforeFill < 0)
			return;
		memoryMeasured = true;
		long after = usedHeap();
		log.info("Broker house memory: {} listings in both races, heap used {} MB before the first fill and {} MB after (about {} bytes a listing, other growth included)",
			listings, heapBeforeFill >> 20, after >> 20, Math.max(0, after - heapBeforeFill) / listings);
	}

	private static long usedHeap() {
		// no explicit collection: a stop-the-world pause on a server with players, for a figure the log line admits is rough. Read as it stands, twice in the house's life.
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	/**
	 * @return The object id of the race's house character, created on a reserved bot account the first time it is asked for and found again from the
	 *         recorded id after; 0 if it could not be made. The character is never logged in, and {@link BotRoster} refuses to log it in or delete it.
	 */
	private int sellerOf(Race race) {
		int id = BotRoster.houseId(race);
		if (id != 0 && PlayerDAO.getPlayerNameByObjId(id) != null)
			return id;
		try {
			String preferred = PREFERRED_NAMES.get(race);
			String name = preferred != null && !PlayerDAO.isNameUsed(preferred) ? preferred : PlayerBotCreationService.generateName();
			Player house = PlayerBotCreationService.create(name, PlayerClass.WARRIOR, 1, race);
			BotRoster.rememberHouse(race, house.getObjectId());
			log.info("Broker house character of {} created: {} ({})", race, name, house.getObjectId());
			return house.getObjectId();
		} catch (RuntimeException e) {
			log.error("Could not create the broker house character of " + race, e);
			return 0;
		}
	}
}
