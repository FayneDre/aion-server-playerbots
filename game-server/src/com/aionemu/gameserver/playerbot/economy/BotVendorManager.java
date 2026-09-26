package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.DialogAction;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.services.RepurchaseService;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemDeleteType;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemUpdateType;
import com.aionemu.gameserver.services.player.PlayerLimitService;
import com.aionemu.gameserver.services.trade.PricesService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Turns a bag full of drops into kinah.
 * <p>
 * Vendors are found in the spawn data rather than in what the bot can see: a shop is in town and a bot farms in the fields, so it has to know where
 * to walk before it can see anything.
 * <p>
 * Selling does not go through {@code TradeService.performSellToShop}: it gates on {@code PlayerRestrictions.canTrade}, which rejects anything that
 * is not {@code isOnline()} — true of every real connection, never true of a bot. Rather than patch a restriction 79 call sites rely on, the small
 * amount of business logic for a plain, template-less sale is redone here.
 */
public class BotVendorManager {

	/** How close the bot must stand, matching what the client enforces for talking to an npc. */
	public static final float TRADE_RANGE = 5f;
	/** Bag fill above which a bot stops farming and goes to sell. */
	public static final float BAG_FULL_THRESHOLD = 0.9f;

	/** Vendor positions per map, worked out once from the spawn data. */
	private static final Map<Integer, List<Vector3f>> vendorsByMap = new ConcurrentHashMap<>();

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
			if (isJunk(item))
				return true;
		return false;
	}

	/**
	 * @return Where the nearest shop stands, or null if that map has none.
	 */
	public static Vector3f findVendor(Player bot) {
		List<Vector3f> vendors = vendorsByMap.computeIfAbsent(bot.getWorldId(), BotVendorManager::locateVendors);
		return vendors.stream().min(Comparator.comparingDouble(spot -> PositionUtil.getDistance(bot.getX(), bot.getY(), spot.x, spot.y)))
			.orElse(null);
	}

	private static List<Vector3f> locateVendors(int worldId) {
		List<Vector3f> vendors = new ArrayList<>();
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null || !template.supportsAction(DialogAction.SELL))
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates())
				vendors.add(new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
		}
		return vendors;
	}

	/** @return The shop the bot is standing next to, or null. */
	public static Npc findVendorNearby(Player bot) {
		Npc[] found = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (found[0] == null && npc.canBuy() && PositionUtil.getDistance(bot, npc) <= TRADE_RANGE)
				found[0] = npc;
		});
		return found[0];
	}

	/**
	 * Sells everything the bot has no use for: ordinary quality, not a quest item, not equipped, and sellable at all.
	 * <p>
	 * Mirrors the template-less branch of {@code TradeService.performSellToShop} (price, sell limit, repurchase list, kinah), the one a general
	 * vendor takes because it accepts anything sellable rather than a fixed goods list.
	 *
	 * @return How many stacks were sold.
	 */
	public static int sellJunk(Player bot, Npc vendor) {
		if (bot.isDead())
			return 0;

		Storage inventory = bot.getInventory();
		List<Item> sold = new ArrayList<>();
		long kinahReward = 0;
		for (Item item : inventory.getItems()) {
			if (!isJunk(item))
				continue;

			long count = item.getItemCount();
			long sellReward = PricesService.getSellReward(item.getItemTemplate().getPrice(), PricesService.getVendorSellModifier());
			count = PlayerLimitService.updateSellLimit(bot, sellReward, count);
			if (count == 0)
				continue;

			long realReward = sellReward * count;
			Item repurchaseItem;
			if (item.getItemCount() - count == 0) {
				inventory.delete(item, ItemDeleteType.SELL);
				repurchaseItem = item;
			} else {
				repurchaseItem = ItemFactory.newItem(item.getItemId(), count);
				inventory.decreaseItemCount(item, count);
			}
			kinahReward += realReward;
			repurchaseItem.setRepurchasePrice(realReward);
			sold.add(repurchaseItem);
		}
		if (sold.isEmpty())
			return 0;

		RepurchaseService.getInstance().addRepurchaseItems(bot, sold);
		inventory.increaseKinah(kinahReward, ItemUpdateType.INC_KINAH_SELL);
		return sold.size();
	}

	private static boolean isJunk(Item item) {
		if (item.isEquipped() || !item.isSellable())
			return false;
		if (item.getItemTemplate().getItemGroup() == ItemGroup.QUEST)
			return false;
		return item.getItemTemplate().getItemQuality().getQualityId() < ItemQuality.RARE.getQualityId();
	}
}
