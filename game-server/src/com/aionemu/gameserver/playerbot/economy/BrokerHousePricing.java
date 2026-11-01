package com.aionemu.gameserver.playerbot.economy;

import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;

/**
 * What the auction house asks, and what the buyer counts as in range (docs/capitals/plans/auction-house-plan.md, decisions 5 and 6).
 * <p>
 * One market price per template: the middle of the guide's range where there is one, else the stores' fair price of the template, with the materials
 * and recipes (whose template price is an npc's, the same for a common ore and a rare one) lifted by a factor of their quality. The house asks that
 * price with a spread of 15% either way and a premium of 5%, never under a floor: at or slightly above the market, so players who undercut it sell first.
 * The buyer's idea of "in range" is the top of that same spread, so what the house sells at and what it buys at come from one rule.
 */
public final class BrokerHousePricing {

	/** How far either way a listing's price is drawn from the market price. */
	static final double SPREAD = 0.15;
	/** The house asks a little over the market, never far under it. */
	static final double PREMIUM = 1.05;

	/** What the tester asked of the prices on 1 November: gear over level 60 ten times dearer, stigmas a hundred times, the mythic pieces never under 200 million. */
	private static final int HIGH_LEVEL = 60;
	private static final int HIGH_LEVEL_FACTOR = 10;
	private static final int STIGMA_FACTOR = 100;
	/** A mythic weapon asks 500 million to 1 billion, any other mythic piece (armour, accessories) 200 to 400 million, as the tester wrote. */
	private static final long MYTHIC_WEAPON_LOW = 500_000_000L, MYTHIC_WEAPON_HIGH = 1_000_000_000L;
	private static final long MYTHIC_GEAR_LOW = 200_000_000L, MYTHIC_GEAR_HIGH = 400_000_000L;
	/** A material between players: never under 1000 kinah a piece, never over 100 000, the quality and the npc value placing it between. */
	private static final long MATERIAL_MINIMUM = 1_000;
	private static final long MATERIAL_MAXIMUM = 100_000;
	/** Food of a high level asks what making it is worth: about 50 000 a piece from level 60, 100 000 from level 70. */
	private static final long FOOD_FROM_60 = 50_000;
	private static final long FOOD_FROM_70 = 100_000;
	/** A potion that no npc sells is worth at least twice the points it restores. */
	private static final int POTION_POINT_FACTOR = 2;

	private BrokerHousePricing() {
	}

	private static boolean isGear(ItemTemplate template) {
		return template.isWeapon() || template.isArmor();
	}

	/** @return true for a material between players, not the design of a craft, whose price is not capped. */
	private static boolean isLooseMaterial(ItemTemplate template, boolean material) {
		return material && !BrokerHouseCatalog.isDesign(template);
	}

	/** @return How many times the data's price is scaled up for the piece: ten over level 60, a hundred for a stigma, else one. */
	private static long scaleOf(ItemTemplate template) {
		if (template.isStigma())
			return STIGMA_FACTOR;
		return isGear(template) && template.getLevel() > HIGH_LEVEL ? HIGH_LEVEL_FACTOR : 1;
	}

	/** @return The least a price may be for the template, as the tester's list says; 0 if it names none. */
	private static long minimumOf(ItemTemplate template, boolean material) {
		long least = 0;
		long[] mythic = mythicRange(template);
		if (mythic != null)
			least = Math.max(least, mythic[0]);
		if (isLooseMaterial(template, material))
			least = Math.max(least, MATERIAL_MINIMUM);
		if (BrokerHouseCatalog.isShownIn(template, BrokerItemMask.CONSUMABLES_POTION))
			least = Math.max(least, BrokerHouseCatalog.potionPoints(template) * POTION_POINT_FACTOR);
		if (BrokerHouseCatalog.isShownIn(template, BrokerItemMask.CONSUMABLES_FOOD))
			least = Math.max(least, template.getLevel() >= 70 ? FOOD_FROM_70 : template.getLevel() >= 60 ? FOOD_FROM_60 : 0);
		return least;
	}

	/** @return The low and the high of a mythic piece of gear, null for anything else. */
	private static long[] mythicRange(ItemTemplate template) {
		if (!isGear(template) || template.getItemQuality() != ItemQuality.MYTHIC)
			return null;
		return template.isWeapon() ? new long[] { MYTHIC_WEAPON_LOW, MYTHIC_WEAPON_HIGH } : new long[] { MYTHIC_GEAR_LOW, MYTHIC_GEAR_HIGH };
	}

	private static boolean isMaterial(ItemTemplate template) {
		return BrokerHouseStock.categoryOf(template) == BrokerItemMask.CRAFT;
	}

	/**
	 * How much an npc's price of a material or a recipe is worth between players, by quality. A first guess, to be corrected from the broker window:
	 * the template price is the same for a common ore and a rare one, which no market is.
	 */
	static double materialFactor(ItemQuality quality) {
		return switch (quality) {
			case JUNK, COMMON -> 2.0;
			case RARE -> 2.5;
			case LEGEND -> 3.5;
			case UNIQUE -> 5.0;
			default -> 7.0;
		};
	}

	/** @return What a material or a recipe of that npc buy-back value is worth between players, at least one kinah. */
	static long materialMarket(long npcBuyValue, ItemQuality quality) {
		return Math.max(1, Math.round(npcBuyValue * materialFactor(quality)));
	}

	/**
	 * @param material True for the crafting category (materials and designs), whose template price is an npc's.
	 * @return The market price of one, or 0 when nothing is known of it: no guide range and no template price.
	 */
	public static long market(ItemTemplate template, boolean material) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return Math.max(1, (range.low() + range.high()) / 2);
		if (template.getPrice() <= 0)
			return 0;
		long market = material ? materialMarket(BotStorePricing.npcBuysAt(template.getPrice()), template.getItemQuality())
			: BotStorePricing.fairPrice(template.getPrice(), template.getItemQuality());
		market = Math.max(market * scaleOf(template), minimumOf(template, material));
		long[] mythic = mythicRange(template);
		if (mythic != null)
			market = (mythic[0] + mythic[1]) / 2;
		return isLooseMaterial(template, material) ? Math.min(market, MATERIAL_MAXIMUM) : market;
	}

	/** @return The lowest the house asks: the bottom of the guide's range, else the stores' floor over what an npc pays. 0 if no price is known. */
	public static long floor(ItemTemplate template) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return Math.max(1, range.low());
		if (template.getPrice() <= 0)
			return 0;
		boolean material = isMaterial(template);
		long floor = Math.max(BotStorePricing.floor(template.getPrice()) * scaleOf(template), minimumOf(template, material));
		return isLooseMaterial(template, material) ? Math.min(floor, MATERIAL_MAXIMUM) : floor;
	}

	/** @return The top of the guide's range for a template that has one, else 0 (no ceiling on the ask). */
	public static long ceiling(ItemTemplate template) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return range.high();
		long[] mythic = mythicRange(template);
		if (mythic != null)
			return mythic[1];
		return isLooseMaterial(template, isMaterial(template)) ? MATERIAL_MAXIMUM : 0;
	}

	/** @return The top of the known range of one: the guide's high, else the most the house itself would ask. 0 if no price is known. */
	public static long top(ItemTemplate template, boolean material) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return range.high();
		long market = market(template, material);
		if (market <= 0)
			return 0;
		long ceiling = ceiling(template);
		return ceiling > 0 ? Math.min(maxAsk(market), Math.max(ceiling, floor(template))) : maxAsk(market);
	}

	/** @return The most the house asks for a market price: the top of the spread with the premium. */
	static long maxAsk(long market) {
		return Math.round(market * (1 + SPREAD) * PREMIUM);
	}

	/**
	 * @param market The market price of one.
	 * @param floor The lowest ask.
	 * @param ceiling The highest ask, or 0 for none (a guide range's top is one).
	 * @param unit A number from 0 to 1 that places the listing in its spread (a random draw, never a constant).
	 * @return What the house asks for one: between 0.85 and 1.15 of the market, a premium over it, between the floor and the ceiling.
	 */
	public static long ask(long market, long floor, long ceiling, double unit) {
		double asked = market * (1 - SPREAD + 2 * SPREAD * unit) * PREMIUM;
		long price = Math.max(Math.max(1, floor), Math.round(asked));
		return ceiling > 0 ? Math.min(price, Math.max(ceiling, floor)) : price;
	}

	/** @return true if a listing's price for one is within the known range: a known top (more than 0), and the price at most that plus the tolerance. */
	public static boolean inRange(long unitPrice, long top, int tolerancePercent) {
		return top > 0 && unitPrice <= top * (100 + Math.max(0, tolerancePercent)) / 100;
	}
}
