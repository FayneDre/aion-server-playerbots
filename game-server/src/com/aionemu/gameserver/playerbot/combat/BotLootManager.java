package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.aionemu.gameserver.model.drop.DropItem;
import com.aionemu.gameserver.model.gameobjects.DropNpc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
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
	 * Takes everything the bot is entitled to on that corpse.
	 *
	 * @return The number of lines taken.
	 */
	public static int lootAll(Player bot, int npcObjectId) {
		Set<DropItem> dropItems = DropRegistrationService.getInstance().getCurrentDropMap().get(npcObjectId);
		if (dropItems == null)
			return 0;

		List<Integer> indexes = new ArrayList<>();
		synchronized (dropItems) { // looting removes from this set, so collect the lines before touching them
			dropItems.forEach(dropItem -> indexes.add(dropItem.getIndex()));
		}
		int looted = 0;
		for (int index : indexes) {
			DropService.getInstance().requestDropItem(bot, npcObjectId, index);
			looted++;
		}
		return looted;
	}
}
