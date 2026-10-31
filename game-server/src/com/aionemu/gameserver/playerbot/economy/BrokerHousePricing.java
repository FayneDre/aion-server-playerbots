package com.aionemu.gameserver.playerbot.economy;

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

	private BrokerHousePricing() {
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
		if (material)
			return materialMarket(BotStorePricing.npcBuysAt(template.getPrice()), template.getItemQuality());
		return BotStorePricing.fairPrice(template.getPrice(), template.getItemQuality());
	}

	/** @return The lowest the house asks: the bottom of the guide's range, else the stores' floor over what an npc pays. 0 if no price is known. */
	public static long floor(ItemTemplate template) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return Math.max(1, range.low());
		return template.getPrice() <= 0 ? 0 : BotStorePricing.floor(template.getPrice());
	}

	/** @return The top of the guide's range for a template that has one, else 0 (no ceiling on the ask). */
	public static long ceiling(ItemTemplate template) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		return range == null ? 0 : range.high();
	}

	/** @return The top of the known range of one: the guide's high, else the most the house itself would ask. 0 if no price is known. */
	public static long top(ItemTemplate template, boolean material) {
		BotStorePricing.Range range = BotStoreGoods.rangeOf(template);
		if (range != null)
			return range.high();
		long market = market(template, material);
		return market <= 0 ? 0 : maxAsk(market);
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
