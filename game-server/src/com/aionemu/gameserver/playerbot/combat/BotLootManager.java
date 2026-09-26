package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.drop.DropItem;
import com.aionemu.gameserver.model.gameobjects.DropNpc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemId;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.services.drop.DropRegistrationService;
import com.aionemu.gameserver.services.drop.DropService;

/**
 * Picks up what a bot killed.
 * <p>
 * A real client opens the corpse ({@code CM_START_LOOT}) and then asks for each line ({@code CM_LOOT_ITEM}). Bots only send the second half:
 * {@code requestDropItem} does not require an open drop list, and opening one cancels the corpse decay task, which would leave corpses lying around
 * forever if a bot ever failed to close it.
 */
public class BotLootManager {

	/** How close the bot has to be, since only the client enforces looting range and a bot must not vacuum corpses from afar. */
	public static final float LOOT_RANGE = 4f;
	/** Above this much of the bag used, the bot stops picking up ordinary loot and keeps the remaining room for what matters. */
	private static final float BAG_SELECTIVE_THRESHOLD = 0.7f;

	private BotLootManager() {
	}

	/**
	 * @return true if that corpse still holds something this bot is entitled to.
	 */
	public static boolean hasLootFor(Player bot, int npcObjectId) {
		DropNpc dropNpc = DropRegistrationService.getInstance().getDropRegistrationMap().get(npcObjectId);
		if (dropNpc == null || !dropNpc.isAllowedToLoot(bot))
			return false;
		Set<DropItem> dropItems = DropRegistrationService.getInstance().getCurrentDropMap().get(npcObjectId);
		return dropItems != null && !dropItems.isEmpty();
	}

	/**
	 * Takes what the bot is entitled to on that corpse, skipping vendor fodder once its bag is filling up.
	 *
	 * @return The number of lines taken.
	 */
	public static int lootAll(Player bot, int npcObjectId) {
		Set<DropItem> dropItems = DropRegistrationService.getInstance().getCurrentDropMap().get(npcObjectId);
		if (dropItems == null)
			return 0;

		List<DropItem> lines = new ArrayList<>();
		synchronized (dropItems) { // looting removes from this set, so copy the lines before touching them
			lines.addAll(dropItems);
		}
		boolean bagFillingUp = isBagFillingUp(bot);
		int looted = 0;
		for (DropItem line : lines) {
			if (bagFillingUp && !isWorthKeeping(line))
				continue;
			DropService.getInstance().requestDropItem(bot, npcObjectId, line.getIndex());
			looted++;
		}
		return looted;
	}

	private static boolean isBagFillingUp(Player bot) {
		Storage inventory = bot.getInventory();
		return inventory.size() >= inventory.getLimit() * BAG_SELECTIVE_THRESHOLD;
	}

	/**
	 * What a bot with a filling bag still bothers to pick up: money, quest items, and anything above ordinary quality. The rest is vendor fodder
	 * that would fill the last free slots for nothing, since a bot cannot go and sell it yet.
	 */
	private static boolean isWorthKeeping(DropItem dropItem) {
		int itemId = dropItem.getDropTemplate().getItemId();
		if (itemId == ItemId.KINAH)
			return true;
		ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if (template == null)
			return false;
		return template.getItemGroup() == ItemGroup.QUEST || template.getItemQuality().getQualityId() >= ItemQuality.RARE.getQualityId();
	}
}
