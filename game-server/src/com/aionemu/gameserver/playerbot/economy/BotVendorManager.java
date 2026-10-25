package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.DialogAction;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import java.util.Set;

import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import java.util.HashSet;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.templates.goods.GoodsList;
import com.aionemu.gameserver.model.templates.item.actions.AbstractItemAction;
import com.aionemu.gameserver.model.templates.item.actions.ItemActions;
import com.aionemu.gameserver.model.templates.item.actions.SkillUseAction;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate.TradeTab;
import com.aionemu.gameserver.model.templates.tradelist.TradeNpcType;
import com.aionemu.gameserver.model.trade.TradeItem;
import com.aionemu.gameserver.playerbot.combat.BotPotionManager;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.model.trade.TradeList;
import com.aionemu.gameserver.services.TradeService;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Turns a bag full of drops into kinah.
 * <p>
 * Vendors are found by asking the map for its living shop keepers, not by reading the spawn data: a shop is in town and a bot farms in the fields,
 * so it has to know where to walk before it can see anything — but a spawn coordinate is where an npc was <i>placed</i>, and many of them walk away
 * from it. Measured in game, bots reached the recorded spot and found nobody there 39 times out of 41.
 * <p>
 * Selling goes through {@code TradeService.performSellToShop}, the engine's own sale. It did not at first: that path gates on
 * {@code PlayerRestrictions.canTrade}, which asked {@code isOnline()} and so refused every bot, and its rules were copied here instead. Once
 * {@code isOnline()} came to mean "is present" rather than "has a socket", the copy had nothing left to justify it and was deleted.
 */
public class BotVendorManager {

	/**
	 * How close the bot must stand. Inside the engine's own limit ({@code TradeService} refuses past 10 m) with room to spare, and no tighter: at 5 m
	 * a keeper behind a stall counter was out of reach of the nearest walkable ground, and two bots circled Pandaemonium's Vidarini 6.6 m away for ten
	 * minutes, stepping aside and asking for a route twice a second.
	 */
	public static final float TRADE_RANGE = 8f;
	/** How far above or below a bot a shop may be and still be gone to: a flight link climbs 200 m, and Reshanta's layers are 1200 m apart. */
	private static final float SHOP_HEIGHT_REACH = 300f;
	/**
	 * How far a shop may be for a trip to it, as the crow flies, in metres. There was no bound: on 23 October 193 of 205 failed trips were to shops
	 * two and three kilometres off, every search for them giving up on its 1.5 s budget, twice a trip (the look, then the walk), on the four threads
	 * every bot's long routes share. Nobody walks across a map to sell; a bot further than this keeps its bag until it is nearer a town.
	 */
	private static final float SHOP_TRIP_RANGE = 800f;

	/**
	 * Shops whose trips gave up for several bots, by map and the spot a trip goes to, with the bots that gave up and when. A search that gives up is no
	 * proof, so one bot's failure bars a shop for that bot alone; but a Sanctum shop on ground nothing joins was asked for 39 times in 18 minutes on
	 * 23 October, each search running out of its budget after four seconds and more. Three bots defeated by the same spot bar it for everybody a while.
	 */
	private static final Map<Integer, Map<Vector3f, Map<Integer, Long>>> tripsGivenUp = new ConcurrentHashMap<>();
	/** How many different bots must give up on a shop for it to be left alone by all. */
	private static final int BOTS_GIVEN_UP = 3;
	/** How long a give-up is remembered, and so how long a shop given up by enough bots is left alone, in milliseconds. */
	private static final long GIVEN_UP_MILLIS = 30 * 60_000L;

	/** Notes that this bot's trip to a shop's spot was given up, so that a spot defeating several bots is left alone by all for a while. */
	public static void tripGivenUp(Player bot, Vector3f spot) {
		tripsGivenUp.computeIfAbsent(bot.getWorldId(), id -> new ConcurrentHashMap<>())
			.computeIfAbsent(spot, s -> new ConcurrentHashMap<>()).put(bot.getObjectId(), System.currentTimeMillis());
	}

	/** @return true while enough bots have recently given up on a shop's spot for every bot to leave it alone. */
	static boolean isGivenUpByMany(int worldId, Vector3f spot) {
		Map<Vector3f, Map<Integer, Long>> byMap = tripsGivenUp.get(worldId);
		Map<Integer, Long> bots = byMap == null ? null : byMap.get(spot);
		if (bots == null)
			return false;
		long now = System.currentTimeMillis();
		bots.values().removeIf(at -> now - at > GIVEN_UP_MILLIS);
		return bots.size() >= BOTS_GIVEN_UP;
	}
	/** Bag fill above which a bot stops farming and goes to sell. */
	public static final float BAG_FULL_THRESHOLD = 0.9f;
	/**
	 * How many flasks of each kind a bot keeps. Enough to matter over a stretch of fighting, few enough that a bot is not carrying a shop: the trip
	 * is worth making for the walk it saves, not for the kinah it spends.
	 */
	private static final int POTIONS_CARRIED = 20;
	/**
	 * Below this a bot does not shop for flasks. The first levels are fought against things that cannot kill a character at full health, the purse is
	 * small, and a newborn bot walking to town before it has fought anything is not what a starter valley should look like.
	 */
	private static final int POTION_BUYING_LEVEL = 10;

	/** Vendor positions per map, worked out once from the spawn data. */

	private BotVendorManager() {
	}

	public static boolean hasFullBag(Player bot) {
		return bot.getInventory().size() >= bot.getInventory().getLimit() * BAG_FULL_THRESHOLD;
	}

	/**
	 * @return true if the bag holds at least one item {@link #sellJunk} would actually sell. A full bag is not enough on its own to justify a trip:
	 *         one full of equipped gear, quest items or anything rare never empties, and would otherwise send the bot walking forever for nothing.
	 */
	public static boolean hasJunk(Player bot) {
		for (Item item : bot.getInventory().getItems())
			if (isJunk(bot, item))
				return true;
		return false;
	}

	/**
	 * How many shops one look tests against the mesh before giving up for this tick.
	 * <p>
	 * Because each test is a full path search, and the dearest search of all is the one that comes back with nothing: it has to exhaust the whole
	 * reachable region before it can say so, which the budget caps at seconds rather than milliseconds. Testing every shop on the map was therefore
	 * not a sweep of fifteen cheap questions but of fifteen dear ones, run on the bot's own thread. Measured after {@code //bot populate 0} on
	 * Eltnen — 102 new residents, 15 shops, most of them in towns no countryside post can walk to — at four and a half cores doing nothing else.
	 * <p>
	 * A cap alone would hide a shop the bot could have reached, so it works with the caller's memory: the refusals this look proves are written down,
	 * and the next one spends its three tests on shops that have not been tried. The map is still explored in full, a few shops at a time.
	 */
	private static final int VENDORS_PROBED = 3;

	/**
	 * @param forFlasks Whether the trip is for flasks alone, so only a keeper who sells one this bot may drink will do.
	 * @param isIgnored The caller's own memory: shops it has already been defeated by, and does not want tested again.
	 * @param whenUnreachable Told of each shop the mesh <b>proves</b> this bot cannot walk to, for the caller to remember. Never told of a search
	 *          that merely gave up — that is not evidence, and storing it would bar a shop for a slow answer rather than for a wall.
	 * @return Where the nearest usable shop stands, or null when none of the ones tested will serve. Null does not mean the map has none left: it
	 *         means this look found nothing, and the caller is expected to wait before asking again.
	 */
	public static Vector3f findVendor(Player bot, boolean forFlasks, Predicate<Vector3f> isIgnored, Consumer<Vector3f> whenUnreachable) {
		List<Vector3f> vendors = new ArrayList<>();
		bot.getPosition().getWorldMapInstance().forEachNpc(npc -> {
			NpcTemplate template = npc.getObjectTemplate();
			if (npc.isSpawned() && template != null && template.supportsAction(DialogAction.SELL) && (!forFlasks || sellsFlasksFor(bot, npc)))
				vendors.add(new Vector3f(npc.getX(), npc.getY(), npc.getZ()));
		});
		// The nearest one it can actually walk to, not simply the nearest: without the test, a shop behind a wall or on another storey is chosen for
		// ever because it is closest as the crow flies. Nearest first, so the cheap case — a bot in town, with a shop it can reach in front of it —
		// still asks the mesh once.
		vendors.removeIf(isIgnored);
		// a shop on another layer is not a shop: Reshanta's upper layer has none outside its fortresses, and its residents set out for Teminon's,
		// 1200 m below, and were refused, 210 of the 304 refused routes of 22 bots in half an hour. Nothing joins the layers that a route could find.
		vendors.removeIf(spot -> Math.abs(spot.z - bot.getZ()) > SHOP_HEIGHT_REACH);
		vendors.removeIf(spot -> PositionUtil.getDistance(bot.getX(), bot.getY(), spot.x, spot.y) > SHOP_TRIP_RANGE);
		vendors.sort(Comparator.comparingDouble(spot -> PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), spot.x, spot.y, spot.z)));
		int probed = 0;
		for (Vector3f spot : vendors) {
			if (probed++ == VENDORS_PROBED)
				break;
			// The floor in front of the keeper, not its feet. A keeper stands behind its counter, on the strip the mesh keeps a body's width from it,
			// where no route ends: a search for its feet could neither find one nor prove there was none, and ran out of its budget instead. 726 of the
			// 1017 searches over a second in 45 minutes on 18 October were that, on the bots' own threads, every shop trip. The nearest floor was not
			// enough either: in Sanctum it is the 94 cells behind the counter, a pocket, and the search for it gave up the same way.
			Vector3f front = frontOf(bot.getWorldId(), spot);
			Vector3f goal = front != null ? front : spot;
			if (isGivenUpByMany(bot.getWorldId(), goal)) {
				probed--; // not a search paid for, so not one of the few this look may make
				continue; // and not one to pay for again: several bots ran out of time on it lately
			}
			// anything but a proof is worth walking to: a mesh that gave up, or a map with no mesh at all, has said nothing against this shop, and
			// the walk itself is the cheaper way to find out. A refusal there is caught by the mover and remembered with its own, shorter patience.
			// And the trip goes to the front too. Sent to the keeper's feet instead, 243 trips of 268 failed in 23 minutes, against 91 of 122.
			if (NavmeshService.getInstance().isNotProvedOutOfReach(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ(), goal.x, goal.y,
				goal.z))
				return goal;
			// a front on another floor proves nothing against the shop: the keeper's feet are asked as they always were
			if (goal != spot && NavmeshService.getInstance().isNotProvedOutOfReach(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ(), spot.x, spot.y,
				spot.z))
				return spot;
			whenUnreachable.accept(spot);
		}
		return null;
	}

	/** Where round a keeper to look for its front, nearest first and all inside trading reach. */
	private static final float[] FRONT_RADII = { 0, 1.5f, 3, 4.5f, 6 };
	private static final int FRONT_DIRECTIONS = 8;
	/** How many floors may be measured for being a pocket, each a fill of a few thousand cells at most. */
	private static final int FRONT_FILLS = 12;

	/**
	 * @return Floor within trading reach of a keeper that is not a pocket, nearest first, or null when none was found or the map has no mesh. No route
	 *         is searched: a pocket is told by how much ground it has, which closes in a few thousand cells, see {@link NavmeshService#isPocket}.
	 */
	public static Vector3f frontOf(int worldId, Vector3f keeper) {
		NavmeshService mesh = NavmeshService.getInstance();
		int fills = 0;
		for (float radius : FRONT_RADII) {
			for (int k = 0; k < (radius == 0 ? 1 : FRONT_DIRECTIONS); k++) {
				double angle = Math.PI * 2 * k / FRONT_DIRECTIONS;
				float x = keeper.x + (float) Math.cos(angle) * radius, y = keeper.y + (float) Math.sin(angle) * radius;
				for (Vector3f floor : mesh.floorsAt(worldId, x, y, keeper.z)) {
					if (PositionUtil.getDistance(floor.x, floor.y, floor.z, keeper.x, keeper.y, keeper.z) >= TRADE_RANGE - 1)
						break; // nearest first: the rest are further off still
					if (fills++ >= FRONT_FILLS)
						return null;
					if (!mesh.isPocket(worldId, floor.x, floor.y, floor.z))
						return floor;
				}
			}
		}
		return null;
	}

	/** @return The shop the bot is standing next to, or null. */
	public static Npc findVendorNearby(Player bot) {
		Npc vendor = findKnownVendor(bot);
		return vendor != null && isWithinTradeRange(bot, vendor) ? vendor : null;
	}

	/**
	 * @return The nearest shop keeper the bot can see, however far, or null. Worth more than the spawn point it was found from: that point is where
	 *         an npc was placed, not where it stands, and a bot that only ever walks to coordinates can end up beside a shop it never notices.
	 */
	public static Npc findKnownVendor(Player bot) {
		Npc[] nearest = { null };
		double[] nearestDistance = { Double.MAX_VALUE };
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.canBuy())
				return;
			double distance = PositionUtil.getDistance(bot, npc);
			if (distance < nearestDistance[0]) {
				nearestDistance[0] = distance;
				nearest[0] = npc;
			}
		});
		return nearest[0];
	}

	/** @return The shop keeper the bot can see that stands nearest a spot and within {@code radius} of it, or null. */
	public static Npc findKnownVendorNear(Player bot, Vector3f spot, float radius) {
		Npc[] nearest = { null };
		double[] nearestDistance = { radius };
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.canBuy())
				return;
			double distance = PositionUtil.getDistance(spot.x, spot.y, spot.z, npc.getX(), npc.getY(), npc.getZ());
			if (distance <= nearestDistance[0]) {
				nearestDistance[0] = distance;
				nearest[0] = npc;
			}
		});
		return nearest[0];
	}

	public static boolean isWithinTradeRange(Player bot, Npc vendor) {
		return PositionUtil.getDistance(bot, vendor) <= TRADE_RANGE;
	}

	/**
	 * Sells everything the bot has no use for: ordinary quality, not a quest item, not equipped, and sellable at all.
	 * <p>
	 * Handed to the engine as one sale, which takes the template-less branch a general vendor takes: it accepts anything sellable rather than a fixed
	 * goods list, and works out the price, the sell limit, the repurchase list and the kinah itself.
	 *
	 * @return How many stacks were sold.
	 */
	public static int sellJunk(Player bot, Npc vendor) {
		if (bot.isDead())
			return 0;

		// The engine's own sale, the one CM_BUY_ITEM performs. This used to be a copy of it — the price, the sell limit, the repurchase list and the
		// kinah, all redone here — because TradeService gates on PlayerRestrictions.canTrade, which asked isOnline() and so refused every bot. Since
		// isOnline() came to mean "is present" rather than "has a socket", the engine's path works, and a copy of its rules can only drift from them.
		TradeList sale = new TradeList();
		int count = 0;
		for (Item item : bot.getInventory().getItems()) {
			if (!isJunk(bot, item))
				continue;
			sale.addItem(item.getObjectId(), item.getItemCount());
			count++;
		}
		return count > 0 && TradeService.performSellToShop(bot, sale, null) ? count : 0;
	}

	/**
	 * @return true if the bot is low enough on flasks to be worth a walk. Half the stock rather than none of it, so the trip is made before the bag
	 *         is empty and not after the fight that emptied it.
	 */
	public static boolean needsPotions(Player bot) {
		return bot.getLevel() >= POTION_BUYING_LEVEL
			&& (held(bot, BotPotionManager.RESTORES_MANA) * 2 < POTIONS_CARRIED
				|| held(bot, BotPotionManager.RESTORES_HEALTH) * 2 < POTIONS_CARRIED);
	}

	private static long held(Player bot, EffectType... effects) {
		long count = 0;
		for (Item item : bot.getInventory().getItems()) {
			if (grants(item.getItemTemplate(), effects))
				count += item.getItemCount();
		}
		return count;
	}

	/**
	 * Buys the flasks the bot will need before the next trip.
	 * <p>
	 * The other half of a transaction that only ever had one. {@code BotPotionManager} has been able to drink since it was written, and almost never
	 * did: a bot could only use what it had picked up, and the creatures it fights drop next to no flasks. So the two potion lines in the combat
	 * chain were dead for nearly every bot, which is half of why casters were found permanently out of mana — the other half being that nothing made
	 * them sit down.
	 * <p>
	 * Everything needed was already here. Bots have carried a purse since stigmas had to be paid for, and they already walk to a vendor and stand in
	 * front of it to sell. Only the return leg was missing.
	 *
	 * @return How many stacks were bought.
	 */
	public static int buyPotions(Player bot, Npc vendor) {
		if (bot.isDead() || !vendor.canSell())
			return 0;
		Set<Integer> onSale = onSaleFor(vendor.getNpcId());
		if (onSale.isEmpty())
			return 0;

		TradeList order = new TradeList();
		int kinds = 0;
		kinds += addToOrder(bot, order, onSale, BotPotionManager.RESTORES_MANA) ? 1 : 0;
		kinds += addToOrder(bot, order, onSale, BotPotionManager.RESTORES_HEALTH) ? 1 : 0;
		int bought = 0;
		if (kinds > 0) {
			// Asked for in full first, and then for one of each. The price is the engine's to work out -- it reads the vendor's own rate and the
			// server's modifier -- so rather than copy that sum here and watch it drift, the purse is tested by offering the order and seeing.
			if (TradeService.performBuyFromShop(vendor, bot, order))
				bought = kinds;
			else {
				TradeList smaller = new TradeList();
				for (TradeItem item : order.getTradeItems())
					smaller.addItem(item.getItemId(), 1);
				bought = TradeService.performBuyFromShop(vendor, bot, smaller) ? kinds : 0;
			}
		}
		bought += buySerums(bot, vendor, onSale); // after the flasks, which matter more and are paid for first
		TradeList shards = new TradeList(); // an order of their own, for the reason the serums have one
		if (BotPowerShards.addToOrder(bot, shards, onSale) && TradeService.performBuyFromShop(vendor, bot, shards))
			bought++;
		return bought;
	}

	/** @return What a keeper sells for kinah, or nothing when it does not take kinah at all. */
	private static Set<Integer> onSaleFor(int npcId) {
		TradeListTemplate goods = DataManager.TRADE_LIST_DATA.getTradeListTemplate(npcId);
		// Kinah is the only currency a bot has. An abyss or reward vendor would refuse it, and asking costs an audit line rather than a polite no.
		if (goods == null || goods.getTradeNpcType() != TradeNpcType.NORMAL)
			return Set.of();
		Set<Integer> onSale = new HashSet<>();
		for (TradeTab tab : goods.getTradeTablist()) {
			GoodsList list = DataManager.GOODSLIST_DATA.getGoodsListById(tab.getId());
			if (list != null && list.getItemIdList() != null)
				onSale.addAll(list.getItemIdList());
		}
		return onSale;
	}

	/**
	 * @return true if the keeper sells a life or mana flask this bot may drink. A trip made for flasks alone goes to such a keeper: sent to the nearest
	 *         one of any kind, a Sanctum resident walked to a keeper with no flask on its shelf nine times in an hour and a half, bought nothing each
	 *         time, and was back as soon as the wait that follows a fruitless trip ran out, on 20 October.
	 */
	private static boolean sellsFlasksFor(Player bot, Npc vendor) {
		for (int itemId : onSaleFor(vendor.getNpcId())) {
			ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
			if (template != null && template.getLevel() <= bot.getLevel()
				&& (grants(template, BotPotionManager.RESTORES_HEALTH) || grants(template, BotPotionManager.RESTORES_MANA)))
				return true;
		}
		return false;
	}

	/**
	 * Wind serums when the shelf has them. No walk is made for them, since few shops stock any, but a daeva at a counter that does takes some: without
	 * them a bot following a player in the air came down on its last points while the player drank and flew on.
	 * <p>
	 * An order of its own, after the flasks: in the same one, a purse short of the serums' price failed the whole order and the fallback bought a
	 * single flask of each kind.
	 *
	 * @return 1 if serums were bought, 0 otherwise.
	 */
	private static int buySerums(Player bot, Npc vendor, Set<Integer> onSale) {
		TradeList order = new TradeList();
		if (!bot.getCommonData().isDaeva() || !addToOrder(bot, order, onSale, BotPotionManager.RESTORES_FLIGHT))
			return 0;
		return TradeService.performBuyFromShop(vendor, bot, order) ? 1 : 0;
	}

	/**
	 * Puts one kind of flask on the order, if the vendor has one the bot may drink and the bot is short of it.
	 * <p>
	 * The strongest it is allowed to use, by the level the item itself states. A bot buying the cheapest flask on the shelf would be back at the
	 * vendor every few fights, and the walk is the expensive part of the errand, not the kinah.
	 */
	private static boolean addToOrder(Player bot, TradeList order, Set<Integer> onSale, EffectType... effects) {
		ItemTemplate best = null;
		for (int itemId : onSale) {
			ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
			if (template == null || !grants(template, effects) || template.getLevel() > bot.getLevel())
				continue;
			if (best == null || template.getLevel() > best.getLevel())
				best = template;
		}
		if (best == null)
			return false;
		long wanted = POTIONS_CARRIED - held(bot, effects);
		if (wanted <= 0)
			return false;
		order.addItem(best.getTemplateId(), wanted);
		return true;
	}

	/** Reads what an item does the same way {@code BotPotionManager} does when it decides to drink one: through the skill its use action casts. */
	private static boolean grants(ItemTemplate template, EffectType... effects) {
		ItemActions actions = template.getActions();
		if (actions == null)
			return false;
		for (AbstractItemAction action : actions.getItemActions()) {
			if (!(action instanceof SkillUseAction skillUse))
				continue;
			SkillTemplate skill = DataManager.SKILL_DATA.getSkillTemplate(skillUse.getSkillId());
			if (skill != null && skill.hasAnyEffect(effects))
				return true;
		}
		return false;
	}

	private static boolean isJunk(Player bot, Item item) {
		if (item.isEquipped() || !item.isSellable())
			return false;
		// a merchant's stock between two shifts: gear for another class reads as junk, and it went to an npc for a fifth of its worth
		if (BotStores.isOnShelf(bot, item))
			return false;
		if (item.getItemTemplate().getItemGroup() == ItemGroup.QUEST)
			return false;
		// Never what the bot drinks. Flasks are COMMON, sellable and not quest items, so every one of them read as junk: a character is born with a
		// hundred of each from player_initial_data, sold the lot on its first trip to a shop, bought twenty back at the counter it had just sold them
		// over, and did that again on the next trip. Half the bots on the server had none left. Buying flasks did not cause this -- selling them did,
		// since before the bot could buy, the till simply emptied and nothing refilled it -- but buying closed it into a circle, and paid the
		// difference between the two prices every time round.
		if (grants(item.getItemTemplate(), BotPotionManager.RESTORES_HEALTH) || grants(item.getItemTemplate(), BotPotionManager.RESTORES_MANA)
			|| grants(item.getItemTemplate(), BotPotionManager.RESTORES_FLIGHT))
			return false;
		if (isForSomeoneElse(bot, item))
			return true; // however fine it is, it will never be worn by this character
		return item.getItemTemplate().getItemQuality().getQualityId() < ItemQuality.RARE.getQualityId();
	}

	/**
	 * Gear this character can never wear, whatever its quality: the wrong class, the wrong race, the wrong gender.
	 * <p>
	 * Quality alone decided what to sell, and everything above ordinary was kept — so a green breastplate looted by a priest was neither worn nor
	 * sold, and sat in the bag for good. Since a full bag is what sends a bot shopping, one such piece bought it an endless round of trips that sold
	 * a handful of greys and changed nothing.
	 * <p>
	 * Only the restrictions that never lift are read. The level and the mastery skills are deliberately left out: a bot grows into both, and a piece
	 * it cannot wear today is worth carrying to the day it can.
	 */
	private static boolean isForSomeoneElse(Player bot, Item item) {
		ItemTemplate template = item.getItemTemplate();
		if (template.getItemSlot() == 0 || !(template.isWeapon() || template.isArmor()))
			return false;
		if (!template.isClassSpecific(bot.getPlayerClass()))
			return true;
		if (BotEquipManager.willNeverMaster(bot, template))
			return true;
		if (template.getRace() != Race.PC_ALL && template.getRace() != bot.getRace())
			return true;
		Gender permitted = template.getUseLimits().getGenderPermitted();
		return permitted != null && permitted != bot.getGender();
	}


}
