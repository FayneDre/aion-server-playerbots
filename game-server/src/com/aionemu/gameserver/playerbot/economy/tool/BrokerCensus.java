package com.aionemu.gameserver.playerbot.economy.tool;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.playerbot.economy.BotStoreGoods;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Read-only census for the auction house plan (milestone 0): how many tradeable item templates the broker could hold, by the client's top level category and
 * by quality, and for how many of them a price is known. Reads the item file alone, no server, no database:
 * {@code java -cp <classes and libraries> com.aionemu.gameserver.playerbot.economy.tool.BrokerCensus [path to item_templates.xml]}.
 * <p>
 * A price is <i>guide</i> when {@link BotStoreGoods#rangeOf} has a range (named goods and manastones), <i>template</i> when the template has a price of its own
 * (what the stores' gear pricing starts from), and <i>none</i> when it has neither, which is what a house would have to guess.
 */
public final class BrokerCensus {

	private static final List<BrokerItemMask> CATEGORIES = List.of(BrokerItemMask.WEAPON, BrokerItemMask.ARMOR, BrokerItemMask.ACCESSORY,
		BrokerItemMask.SKILL_RELATED, BrokerItemMask.HOME_DECOR, BrokerItemMask.FURNITURE, BrokerItemMask.CRAFT, BrokerItemMask.CONSUMABLES,
		BrokerItemMask.OTHER);

	/** Templates of one category and quality, by the price known for them. */
	private static final class Count {
		int total, guide, template, none;

		void add(PriceSource source) {
			total++;
			switch (source) {
				case GUIDE -> guide++;
				case TEMPLATE -> template++;
				case NONE -> none++;
			}
		}
	}

	private enum PriceSource {
		GUIDE, TEMPLATE, NONE
	}

	private BrokerCensus() {
	}

	public static void main(String[] args) {
		File file = new File(args.length > 0 ? args[0] : "data/static_data/items/item_templates.xml");
		ItemData data = JAXBUtil.deserialize(file, ItemData.class);
		Map<BrokerItemMask, Map<ItemQuality, Count>> counts = new EnumMap<>(BrokerItemMask.class);
		Map<BrokerItemMask, Count> totals = new EnumMap<>(BrokerItemMask.class);
		Count uncategorised = new Count(), untradeable = new Count();
		for (ItemTemplate template : data.getItemTemplates()) {
			if (!template.isTradeable()) {
				untradeable.add(PriceSource.NONE);
				continue;
			}
			BrokerItemMask category = categoryOf(template);
			PriceSource source = BotStoreGoods.rangeOf(template) != null ? PriceSource.GUIDE : template.getPrice() > 0 ? PriceSource.TEMPLATE : PriceSource.NONE;
			if (category == null) {
				uncategorised.add(source);
				continue;
			}
			counts.computeIfAbsent(category, c -> new EnumMap<>(ItemQuality.class)).computeIfAbsent(template.getItemQuality(), q -> new Count()).add(source);
			totals.computeIfAbsent(category, c -> new Count()).add(source);
		}
		System.out.println("category / quality: tradeable (guide range / template price / none)");
		Count all = new Count();
		for (BrokerItemMask category : CATEGORIES) {
			Count total = totals.getOrDefault(category, new Count());
			System.out.println(category + ": " + format(total));
			for (Map.Entry<ItemQuality, Count> quality : counts.getOrDefault(category, Map.of()).entrySet())
				System.out.println("  " + quality.getKey() + ": " + format(quality.getValue()));
			all.total += total.total;
			all.guide += total.guide;
			all.template += total.template;
			all.none += total.none;
		}
		System.out.println("all categories: " + format(all));
		System.out.println("tradeable but in no category: " + format(uncategorised));
		System.out.println("not tradeable: " + untradeable.total);
	}

	private static BrokerItemMask categoryOf(ItemTemplate template) {
		Item probe = new Item(0, template);
		for (BrokerItemMask category : CATEGORIES) {
			if (category.isMatches(probe))
				return category;
		}
		return null;
	}

	private static String format(Count count) {
		return count.total + " (" + count.guide + " / " + count.template + " / " + count.none + ")";
	}
}
