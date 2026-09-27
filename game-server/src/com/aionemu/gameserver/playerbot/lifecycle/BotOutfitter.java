package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.item.ItemFactory;

/**
 * Dresses a freshly created bot in gear that suits its class and level.
 * <p>
 * Until this existed, populating a map cloned one template character, so every inhabitant was the same class wearing the same three training pieces
 * whatever level it had been given. A population that all looks alike and fights in starting gear is scenery that fools nobody.
 */
public class BotOutfitter {

	/** The places worth filling. Rings and earrings come in pairs and are left out: jewellery is not what makes a character look equipped. */
	private static final ItemSlot[] DRESSED_SLOTS = { ItemSlot.MAIN_HAND, ItemSlot.TORSO, ItemSlot.PANTS, ItemSlot.GLOVES, ItemSlot.BOOTS,
		ItemSlot.SHOULDER };
	/** How far below its own level a piece may be. Gear is not made for every level, so an exact match is rarely on offer. */
	private static final int LEVEL_SLACK = 5;
	/** Nothing fancier than this: a village of people in heroic armour reads as a costume party, not a village. */
	private static final ItemQuality BEST_QUALITY = ItemQuality.RARE;

	/** Candidates per class, level and slot are the same for every bot of that kind, and the item list is long enough to be worth remembering. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();

	private BotOutfitter() {
	}

	/**
	 * Puts a set of gear on a bot.
	 * <p>
	 * Each piece is created already identified and already bound to its wearer, which is what lets it simply be equipped: unidentified is unwearable,
	 * and a soul bound piece that is not yet bound opens a confirmation window instead of going on. A bot could answer both, but it would spend its
	 * first ten minutes of existence reading and binding rather than living.
	 *
	 * @return How many pieces it ended up wearing.
	 */
	public static int dress(Player bot) {
		int worn = 0;
		for (ItemSlot slot : DRESSED_SLOTS) {
			ItemTemplate template = pick(bot, slot);
			if (template == null)
				continue;
			Item item = ItemFactory.newItem(template.getTemplateId());
			item.setTuneCount(Math.max(0, item.getTuneCount())); // identified: nobody hands out an unread piece as a uniform
			item.setSoulBound(true);
			if (bot.getInventory().add(item) == null)
				continue;
			if (bot.getEquipment().equipItem(item.getObjectId(), template.getItemSlot()) != null)
				worn++;
		}
		if (worn == 0)
			LoggerFactory.getLogger(BotOutfitter.class).warn("Found nothing for a {} of level {} to wear", bot.getPlayerClass(), bot.getLevel());
		return worn;
	}

	/** @return One of the better pieces this character could wear in that place, chosen at random among them so a crowd is not in uniform. */
	private static ItemTemplate pick(Player bot, ItemSlot slot) {
		List<ItemTemplate> wearable = candidates.computeIfAbsent(key(bot, slot), _ -> gatherFor(bot, slot));
		return wearable.isEmpty() ? null : wearable.get(Rnd.get(0, wearable.size() - 1));
	}

	private static String key(Player bot, ItemSlot slot) {
		return bot.getPlayerClass() + "/" + bot.getRace() + "/" + bot.getLevel() + "/" + slot;
	}

	/**
	 * Reads the item data for everything a character of this kind could wear in that place.
	 * <p>
	 * Only the restrictions that the engine will enforce anyway are applied — class, race, required level, quality — because the alternative is a
	 * second copy of its rules that drifts from it. What is left is sorted by level so the best of what fits comes first, and the top few are kept.
	 */
	private static List<ItemTemplate> gatherFor(Player bot, ItemSlot slot) {
		List<ItemTemplate> found = new ArrayList<>();
		long slotMask = slot.getSlotIdMask();
		for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
			if ((template.getItemSlot() & slotMask) == 0 || !(template.isWeapon() || template.isArmor()))
				continue;
			if (template.getItemQuality().getQualityId() > BEST_QUALITY.getQualityId())
				continue;
			if (!template.isClassSpecific(bot.getPlayerClass()))
				continue;
			if (template.getRace() != Race.PC_ALL && template.getRace() != bot.getRace())
				continue;
			int required = template.getRequiredLevel(bot.getPlayerClass());
			if (required < 1 || required > bot.getLevel() || required < bot.getLevel() - LEVEL_SLACK)
				continue;
			found.add(template);
		}
		found.sort(Comparator.comparingInt(ItemTemplate::getLevel).reversed());
		return found.subList(0, Math.min(8, found.size()));
	}
}
