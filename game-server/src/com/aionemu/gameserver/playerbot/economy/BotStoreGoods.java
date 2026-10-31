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

	/**
	 * The scrolls of speed players buy: Courage (attack speed), Running, Raging Wind (flight speed) and Awakening (casting speed), Lesser at keeper level 10, plain at 20, Greater
	 * at 30, and the movement speed scroll of level 15. These are the five minute ones a player can craft, tradeable in the data. The prices are a first guess, a few thousand
	 * kinah a scroll and more for the greater, to be read off the tester's prices; a stack of 5 to 30.
	 */
	static final List<Good> SCROLLS = List.of(
		new Good(List.of(164000033), 15, 1_000, 3_000, 5, 30, false), // Movement Speed Scroll
		new Good(List.of(164000071), 10, 1_500, 4_000, 5, 30, false), // Lesser Courage
		new Good(List.of(164000072), 20, 4_000, 10_000, 5, 30, false), // Courage
		new Good(List.of(164000073), 30, 10_000, 25_000, 5, 30, false), // Greater Courage
		new Good(List.of(164000074), 10, 1_500, 4_000, 5, 30, false), // Lesser Running
		new Good(List.of(164000075), 20, 4_000, 10_000, 5, 30, false), // Running
		new Good(List.of(164000076), 30, 10_000, 25_000, 5, 30, false), // Greater Running
		new Good(List.of(164000077), 10, 1_500, 4_000, 5, 30, false), // Lesser Raging Wind
		new Good(List.of(164000078), 20, 4_000, 10_000, 5, 30, false), // Raging Wind
		new Good(List.of(164000079), 30, 10_000, 25_000, 5, 30, false), // Greater Raging Wind
		new Good(List.of(164000132), 10, 1_500, 4_000, 5, 30, false), // Lesser Awakening
		new Good(List.of(164000133), 20, 4_000, 10_000, 5, 30, false), // Awakening
		new Good(List.of(164000134), 30, 10_000, 25_000, 5, 30, false), // Greater Awakening
		// the hour long ones of the cash shop, which the data lets be traded: twelve times the greater five minute scroll
		new Good(List.of(164002111), 30, 120_000, 300_000, 3, 10, false), // Greater Running (1 hour)
		new Good(List.of(164002112), 30, 120_000, 300_000, 3, 10, false), // Greater Courage (1 hour)
		new Good(List.of(164002113), 30, 120_000, 300_000, 3, 10, false)); // Greater Awakening (1 hour)

	/**
	 * The thirty minute scrolls of the events, which the data does not let be traded and a store refuses ({@code !isTradeable}): a bot's store holds them all the same, by the
	 * tester's request of 29 October (the permission is in {@code PrivateStoreService.validateItem}, for a bot owner and for these ids only). Six times the five minute scroll's
	 * price, stacks of 5 to 20.
	 */
	static final List<Good> EVENT_SCROLLS = List.of(
		new Good(List.of(164002002), 10, 9_000, 24_000, 5, 20, false), // [Event] Lesser Courage
		new Good(List.of(164002003), 10, 9_000, 24_000, 5, 20, false), // [Event] Lesser Running
		new Good(List.of(164002004), 10, 9_000, 24_000, 5, 20, false), // [Event] Lesser Raging Wind
		new Good(List.of(164002058), 20, 24_000, 60_000, 5, 20, false), // [Event] Courage
		new Good(List.of(164002059), 20, 24_000, 60_000, 5, 20, false), // [Event] Running
		new Good(List.of(164002056), 20, 24_000, 60_000, 5, 20, false), // [Event] Awakening
		new Good(List.of(164002010), 30, 60_000, 150_000, 5, 20, false), // [Event] Greater Courage
		new Good(List.of(164002011), 30, 60_000, 150_000, 5, 20, false), // [Event] Greater Running
		new Good(List.of(164002012), 30, 60_000, 150_000, 5, 20, false), // [Event] Greater Raging Wind
		new Good(List.of(164002057), 30, 60_000, 150_000, 5, 20, false)); // [Event] Greater Awakening

	private static final Map<Integer, Good> UPGRADE_IDS = byId(UPGRADES), COIN_IDS = byId(COINS), SCROLL_IDS = byId(SCROLLS), EVENT_SCROLL_IDS = byId(EVENT_SCROLLS);

	private BotStoreGoods() {
	}

	private static Map<Integer, Good> byId(List<Good> goods) {
		return goods.stream().flatMap(good -> good.ids().stream().map(id -> Map.entry(id, good)))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** @return The guide's entry for the template, or null for goods the guide did not price. */
	public static Good of(ItemTemplate template) {
		Good good = UPGRADE_IDS.get(template.getTemplateId());
		if (good == null)
			good = COIN_IDS.get(template.getTemplateId());
		if (good == null)
			good = SCROLL_IDS.get(template.getTemplateId());
		return good != null ? good : EVENT_SCROLL_IDS.get(template.getTemplateId());
	}


	/** @return The templates of the rare goods, the evolution stone, the holy serum and the All-Powerful stone: the ones a market always has in a store. */
	static List<Integer> rareIds() {
		return UPGRADES.stream().filter(Good::rare).map(good -> good.ids().getFirst()).toList();
	}

	/** @return true for one of the rare goods. */
	static boolean isRare(ItemTemplate template) {
		Good good = UPGRADE_IDS.get(template.getTemplateId());
		return good != null && good.rare();
	}

	/** @return true for a scroll of speed, a five minute one or an hour one, that a keeper of that level stocks. */
	static boolean isScrollFor(ItemTemplate template, int level) {
		Good good = SCROLL_IDS.get(template.getTemplateId());
		return good != null && good.fromLevel() <= level;
	}

	/** @return true for one of the thirty minute event scrolls. */
	static boolean isEventScroll(ItemTemplate template) {
		return EVENT_SCROLL_IDS.containsKey(template.getTemplateId());
	}

	/** @return true for an event scroll that a keeper of that level stocks. */
	static boolean isEventScrollFor(ItemTemplate template, int level) {
		Good good = EVENT_SCROLL_IDS.get(template.getTemplateId());
		return good != null && good.fromLevel() <= level;
	}

	/**
	 * @return true if a bot's store may hold the piece: it is tradeable, as the engine asks of any store, or it is one of the event scrolls the tester asked bots to sell. Only a
	 *         bot's store asks this; the engine's own test for a player is unchanged.
	 */
	public static boolean isSellableByABot(ItemTemplate template) {
		return template.isTradeable() || EVENT_SCROLL_IDS.containsKey(template.getTemplateId());
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
