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
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.model.trade.TradeList;
import com.aionemu.gameserver.services.TradeService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Turns a bag full of drops into kinah.
 * <p>
 * Vendors are found in the spawn data rather than in what the bot can see: a shop is in town and a bot farms in the fields, so it has to know where
 * to walk before it can see anything. The actual selling then goes through the same service the client's own packet calls.
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
	 *
	 * @return How many stacks were sold.
	 */
	public static int sellJunk(Player bot, Npc vendor) {
		List<Item> junk = new ArrayList<>();
		for (Item item : bot.getInventory().getItems()) {
			if (isJunk(item))
				junk.add(item);
		}
		if (junk.isEmpty())
			return 0;

		TradeList tradeList = new TradeList(vendor.getObjectId());
		for (Item item : junk)
			tradeList.addItem(item.getObjectId(), item.getItemCount()); // a sell list is keyed by object id, not item id
		// a general vendor has no purchase template, which is what lets it take anything sellable
		return TradeService.performSellToShop(bot, tradeList, DataManager.TRADE_LIST_DATA.getPurchaseTemplate(vendor.getNpcId())) ? junk.size() : 0;
	}

	private static boolean isJunk(Item item) {
		if (item.isEquipped() || !item.isSellable())
			return false;
		if (item.getItemTemplate().getItemGroup() == ItemGroup.QUEST)
			return false;
		return item.getItemTemplate().getItemQuality().getQualityId() < ItemQuality.RARE.getQualityId();
	}
}
