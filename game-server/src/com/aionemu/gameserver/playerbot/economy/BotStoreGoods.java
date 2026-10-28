package com.aionemu.gameserver.playerbot.economy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;

/**
 * The goods a price guide named, by id, with the keeper level each asks for and the range a live broker sold it in. See docs/capitals/history/bot-store-goods-plan.md.
 * <p>
 * By id because the server's data and the client name these things differently: the client's L1 to L5 Enchantment Stones are Alpha to Epsilon here,
 * its All-Powerful stone is the Omega one, and a name looked up from the guide found nothing, or found the wrong thing. The ids are the guide's own,
 * read off the client on 24 October.
 */
public final class BotStoreGoods {

	/**
	 * One kind of goods.
	 *
	 * @param ids The template, or the Elyos copy then the Asmodian one where the data keeps one for each side.
	 * @param fromLevel The lowest keeper level that stocks it.
	 * @param low The bottom of the broker's range, where an unsold line stops falling.
	 * @param high The top of the broker's range.
	 * @param rare Stocked by about one store in ten: goods a live broker showed now and then, not on every shelf.
	 */
	public record Good(List<Integer> ids, int fromLevel, long low, long high, int minCount, int maxCount, boolean rare) {

		BotStorePricing.Range range() {
			return new BotStorePricing.Range(low, high);
		}
	}

	/** The enchantment stones a 4.8 player obtains, the evolution stone and the accessories' serum: the stones store's upgrade items. */
	static final List<Good> UPGRADES = List.of(
		// the five stones by the gear they are for (rare level 20, heroic 40, fabled 55, eternal 60, mythic 65), stocked ten levels under it as the
		// guide's manastones are
		new Good(List.of(166000191), 20, 20_000, 50_000, 10, 50, false), // L1, Alpha in the data
		new Good(List.of(166000192), 30, 100_000, 250_000, 10, 50, false), // L2, Beta
		new Good(List.of(166000193), 45, 500_000, 1_500_000, 5, 20, false), // L3, Gamma
		new Good(List.of(166000194), 50, 3_000_000, 6_000_000, 2, 10, false), // L4, Delta
		new Good(List.of(166000195), 55, 15_000_000, 25_000_000, 1, 5, false), // L5, Epsilon
		new Good(List.of(166020000), 60, 75_000_000, 75_000_000, 1, 2, true), // All-Powerful, Omega in the data
		new Good(List.of(166500002), 60, 60_000_000, 120_000_000, 1, 2, true), // Evolution Stone, Amplification Stone in the data
		new Good(List.of(166030005), 60, 50_000_000, 90_000_000, 1, 2, true)); // Holy Upgrade Serum, Tempering Solution in the data

	/**
	 * The coins and medals store. Platinum, Mithril and Ceramium medals are left out by decision, not only because the engine refuses them in a store:
	 * the Ceramium medal is what the best PvP gear is bought with. Iron coins are worth nothing between players.
	 */
	static final List<Good> COINS = List.of(
		new Good(List.of(186000002, 186000007), 18, 10_000, 15_000, 10, 50, false), // Bronze Coin
		new Good(List.of(186000003, 186000008), 21, 15_000, 25_000, 10, 50, false), // Silver Coin
		new Good(List.of(186000004, 186000009), 31, 35_000, 50_000, 10, 50, false), // Gold Coin
		new Good(List.of(186000005, 186000010), 41, 60_000, 90_000, 10, 50, false), // Platinum Coin
		new Good(List.of(186000018, 186000019), 51, 120_000, 180_000, 10, 50, false), // Mithril Coin
		new Good(List.of(186000231, 186000232), 61, 90_000, 110_000, 10, 50, false), // Ceramium Coin, "about 100 000"
		new Good(List.of(186000031), 35, 10_000, 20_000, 10, 50, false), // Silver Medal
		new Good(List.of(186000030), 45, 40_000, 70_000, 10, 50, false)); // Gold Medal

	private static final Map<Integer, Good> UPGRADE_IDS = byId(UPGRADES), COIN_IDS = byId(COINS);

	private BotStoreGoods() {
	}

	private static Map<Integer, Good> byId(List<Good> goods) {
		return goods.stream().flatMap(good -> good.ids().stream().map(id -> Map.entry(id, good)))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** @return The guide's entry for the template, or null for goods the guide did not price. */
	public static Good of(ItemTemplate template) {
		Good good = UPGRADE_IDS.get(template.getTemplateId());
		return good != null ? good : COIN_IDS.get(template.getTemplateId());
	}

	/** @return The broker's range for goods the guide priced, a named good or a manastone, or null for gear, which is priced off its template. */
	public static BotStorePricing.Range rangeOf(ItemTemplate template) {
		Good good = of(template);
		return good != null ? good.range() : BotManastones.rangeOf(template);
	}

	/** @return true for an upgrade item a keeper of that level stocks. */
	static boolean isUpgradeFor(ItemTemplate template, int level) {
		Good good = UPGRADE_IDS.get(template.getTemplateId());
		return good != null && good.fromLevel() <= level;
	}

	/**
	 * @return true for a coin or medal a keeper of that level and side stocks. The side is chosen here rather than left to the store's faction
	 *         filter: the Asmodian copies say so in their model name ({@code coin_d_02}), the Elyos ones say nothing ({@code coin_02}), so an Elyos
	 *         coin no source names reads as anybody's and an Asmodian keeper could have sold it.
	 */
	static boolean isCoinFor(ItemTemplate template, int level, Race race) {
		Good good = COIN_IDS.get(template.getTemplateId());
		if (good == null || good.fromLevel() > level)
			return false;
		return good.ids().size() == 1 || good.ids().get(race == Race.ASMODIANS ? 1 : 0) == template.getTemplateId();
	}

	/**
	 * @return true for one of the 190-odd enchantment stones of older versions, {@code L1} to {@code L190}, which no 4.8 player obtains. The stones
	 *         store sold them by grade until 24 October, so a keeper's bag may still hold some.
	 */
	static boolean isRemnantStone(ItemTemplate template) {
		return template.getItemGroup() == ItemGroup.ENCHANTMENT && of(template) == null && template.getName() != null
			&& template.getName().endsWith("Enchantment Stone");
	}
}
