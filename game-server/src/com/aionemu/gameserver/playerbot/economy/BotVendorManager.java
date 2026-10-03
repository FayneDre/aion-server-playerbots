package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.List;

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

	/** How close the bot must stand, matching what the client enforces for talking to an npc. */
	public static final float TRADE_RANGE = 5f;
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
	 * @return Where the nearest shop stands, or null if that map has none.
	 */
	public static Vector3f findVendor(Player bot, Predicate<Vector3f> isIgnored) {
		List<Vector3f> vendors = new ArrayList<>();
		bot.getPosition().getWorldMapInstance().forEachNpc(npc -> {
			NpcTemplate template = npc.getObjectTemplate();
			if (npc.isSpawned() && template != null && template.supportsAction(DialogAction.SELL))
				vendors.add(new Vector3f(npc.getX(), npc.getY(), npc.getZ()));
		});
		// The nearest one it can actually walk to, not simply the nearest. Sorted first and tested lazily, so the usual case asks the navmesh once;
		// without the test, a shop behind a wall or on another storey is chosen for ever because it is closest as the crow flies.
		return vendors.stream().filter(spot -> !isIgnored.test(spot))
			.sorted(Comparator.comparingDouble(spot -> PositionUtil.getDistance(bot.getX(), bot.getY(), spot.x, spot.y)))
			.filter(spot -> NavmeshService.getInstance().canReach(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ(), spot.x, spot.y, spot.z))
			.findFirst().orElse(null);
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
		TradeListTemplate goods = DataManager.TRADE_LIST_DATA.getTradeListTemplate(vendor.getNpcId());
		// Kinah is the only currency a bot has. An abyss or reward vendor would refuse it, and asking costs an audit line rather than a polite no.
		if (goods == null || goods.getTradeNpcType() != TradeNpcType.NORMAL)
			return 0;

		Set<Integer> onSale = new HashSet<>();
		for (TradeTab tab : goods.getTradeTablist()) {
			GoodsList list = DataManager.GOODSLIST_DATA.getGoodsListById(tab.getId());
			if (list != null && list.getItemIdList() != null)
				onSale.addAll(list.getItemIdList());
		}

		TradeList order = new TradeList();
		int kinds = 0;
		kinds += addToOrder(bot, order, onSale, BotPotionManager.RESTORES_MANA) ? 1 : 0;
		kinds += addToOrder(bot, order, onSale, BotPotionManager.RESTORES_HEALTH) ? 1 : 0;
		if (kinds == 0)
			return 0;
		// Asked for in full first, and then for one of each. The price is the engine's to work out -- it reads the vendor's own rate and the
		// server's modifier -- so rather than copy that sum here and watch it drift, the purse is tested by offering the order and seeing.
		if (TradeService.performBuyFromShop(vendor, bot, order))
			return kinds;
		TradeList smaller = new TradeList();
		for (TradeItem item : order.getTradeItems())
			smaller.addItem(item.getItemId(), 1);
		return TradeService.performBuyFromShop(vendor, bot, smaller) ? kinds : 0;
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
		if (item.getItemTemplate().getItemGroup() == ItemGroup.QUEST)
			return false;
		// Never what the bot drinks. Flasks are COMMON, sellable and not quest items, so every one of them read as junk: a character is born with a
		// hundred of each from player_initial_data, sold the lot on its first trip to a shop, bought twenty back at the counter it had just sold them
		// over, and did that again on the next trip. Half the bots on the server had none left. Buying flasks did not cause this -- selling them did,
		// since before the bot could buy, the till simply emptied and nothing refilled it -- but buying closed it into a circle, and paid the
		// difference between the two prices every time round.
		if (grants(item.getItemTemplate(), BotPotionManager.RESTORES_HEALTH) || grants(item.getItemTemplate(), BotPotionManager.RESTORES_MANA))
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
