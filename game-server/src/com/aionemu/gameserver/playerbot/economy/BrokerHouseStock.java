package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;

/**
 * What the auction house may list: the candidate templates by category and quality, the level curve they are drawn by and the size of a stack.
 * The filters are those of the stores, plus the broker's own: tradeable, in a category the client's broker can show, no quest item, no remnant
 * enchantment stone, no item that expires, a price known.
 */
public final class BrokerHouseStock {

	/** The client's top level categories, in the order of the census (docs/capitals/plans/auction-house-plan.md). */
	public static final List<BrokerItemMask> CATEGORIES = List.of(BrokerItemMask.WEAPON, BrokerItemMask.ARMOR, BrokerItemMask.ACCESSORY,
		BrokerItemMask.SKILL_RELATED, BrokerItemMask.HOME_DECOR, BrokerItemMask.FURNITURE, BrokerItemMask.CRAFT, BrokerItemMask.CONSUMABLES,
		BrokerItemMask.OTHER);

	/** Weight of an item level in the draw: the server's population is at 20 to 55, so those levels are listed most. */
	static int levelWeight(int level) {
		if (level < 10)
			return 1;
		if (level < 20)
			return 2;
		if (level <= 55)
			return 6;
		if (level <= 65)
			return 3;
		return 1;
	}

	private static final int MAX_LEVEL_WEIGHT = 6;
	private static final int DRAW_ATTEMPTS = 24;

	/** One race's candidates: the templates of a category and quality. */
	public record Pool(Map<BrokerItemMask, Map<ItemQuality, List<ItemTemplate>>> templates) {

		/** @return true if the category has at least one candidate of the quality. */
		public boolean has(BrokerItemMask category, ItemQuality quality) {
			return !templates.getOrDefault(category, Map.of()).getOrDefault(quality, List.of()).isEmpty();
		}

		/** @return true if the category has a candidate of any quality. */
		public boolean has(BrokerItemMask category) {
			return templates.containsKey(category) && !templates.get(category).isEmpty();
		}

		public List<ItemTemplate> of(BrokerItemMask category, ItemQuality quality) {
			return templates.getOrDefault(category, Map.of()).getOrDefault(quality, List.of());
		}
	}

	private BrokerHouseStock() {
	}

	/** @return The category of the client's broker the template is shown in, or null if it is shown in none. */
	public static BrokerItemMask categoryOf(ItemTemplate template) {
		Item probe = new Item(0, template);
		for (BrokerItemMask category : CATEGORIES) {
			if (category.isMatches(probe))
				return category;
		}
		return null;
	}

	/**
	 * @return true if the house may list the template for that race: tradeable, no quest item, no remnant stone, no expiry, not blacklisted, usable by
	 *         the race, and priced (a guide range or a template price).
	 */
	static boolean isCandidate(ItemTemplate template, Race race, Set<Integer> blacklist) {
		if (!template.isTradeable() || template.isKinah() || template.getItemGroup() == ItemGroup.QUEST || template.getExpireTime() > 0 || !isPlayerGoods(template))
			return false;
		if (blacklist.contains(template.getTemplateId()) || BotStoreGoods.isRemnantStone(template))
			return false;
		Race usable = template.getRace();
		if (usable != null && usable != Race.PC_ALL && usable != race)
			return false;
		return BotStoreGoods.rangeOf(template) != null || template.getPrice() > 0;
	}

	/** Words that mark a template nobody plays with: a test, a practice or dummy piece, a sample, a debug item. */
	private static final java.util.regex.Pattern NOT_FOR_PLAYERS = java.util.regex.Pattern.compile("\\b(test|practice|dummy|sample|debug|temp|temporary|event|gm)\\b",
		java.util.regex.Pattern.CASE_INSENSITIVE);
	/** The price the data gives a weapon or an armour piece nobody sells or buys: a placeholder, not a price. */
	private static final int PLACEHOLDER_PRICE = 5;

	/**
	 * @return false for what the item data holds and a player never meets on the market: the monsters' own gear (named {@code NPC ...}, its client name {@code npc_...}), the cards, tickets, titles
	 *         and event goods of the cash shop and the events (a name that opens with a bracket, {@code [Event]}, {@code [Stamp]}), test and practice pieces, and gear priced at the data's
	 *         placeholder. Seen on 31 October in the broker: three NPC swords and a practice sword among the first of 63.
	 */
	static boolean isPlayerGoods(ItemTemplate template) {
		String name = template.getName();
		if (name == null || name.isBlank() || name.startsWith("[") || name.startsWith("NPC ") || NOT_FOR_PLAYERS.matcher(name).find())
			return false;
		String clientName = template.getCName();
		if (clientName != null && clientName.regionMatches(true, 0, "npc_", 0, 4))
			return false;
		return !(template.isWeapon() || template.isArmor()) || template.getPrice() > PLACEHOLDER_PRICE;
	}

	/** Reads every item template once for the race. Costs a pass over the item data, so the caller keeps the result. */
	public static Pool poolFor(Race race, Set<Integer> blacklist) {
		Map<BrokerItemMask, Map<ItemQuality, List<ItemTemplate>>> found = new EnumMap<>(BrokerItemMask.class);
		for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
			if (!isCandidate(template, race, blacklist))
				continue;
			BrokerItemMask category = categoryOf(template);
			if (category != null)
				found.computeIfAbsent(category, c -> new EnumMap<>(ItemQuality.class)).computeIfAbsent(template.getItemQuality(), q -> new ArrayList<>())
					.add(template);
		}
		return new Pool(found);
	}

	/** @return One template of the list, drawn by the level curve: a draw is kept in proportion to its level's weight, the last one after a few misses. */
	static ItemTemplate draw(List<ItemTemplate> candidates, Random random) {
		ItemTemplate drawn = null;
		for (int attempt = 0; attempt < DRAW_ATTEMPTS; attempt++) {
			drawn = candidates.get(random.nextInt(candidates.size()));
			if (random.nextInt(MAX_LEVEL_WEIGHT) < levelWeight(drawn.getLevel()))
				return drawn;
		}
		return drawn;
	}

	/**
	 * @return How many to list at once. Gear and anything that does not stack is one; goods that stack are a few, and the crafting materials and
	 *         consumables a heap, skewed towards the small ones.
	 */
	static long stackSize(ItemTemplate template, BrokerItemMask category, Random random) {
		if (!template.isStackable() || template.getMaxStackCount() <= 1)
			return 1;
		long cap = template.getMaxStackCount();
		boolean heap = category == BrokerItemMask.CRAFT || category == BrokerItemMask.CONSUMABLES;
		long most = Math.min(cap, heap ? 100 : 10);
		double skewed = random.nextDouble() * random.nextDouble();
		return Math.max(1, Math.min(most, 1 + Math.round(skewed * (most - 1))));
	}
}
