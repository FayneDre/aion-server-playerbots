package com.aionemu.gameserver.playerbot.economy;

import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.services.trade.PricesService;

/**
 * What a bot's store asks for a piece.
 * <p>
 * The data's only price is the template's, which an npc sells at and buys back at a fifth of, and which is the same for a green piece and a gold one
 * of the same level. So for gear the template gives the scale and the floor, the quality gives the worth, and the keeper's temperament and the time
 * the piece has waited move it from there, as written in docs/bot-stores-plan.md on 20 October. Stones, coins and medals are priced from a broker's
 * range instead ({@link Range}, docs/bot-store-goods-plan.md).
 */
public final class BotStorePricing {

	/**
	 * A live broker's range for goods a price guide named. Stones and coins are priced from these rather than from their templates, whose prices mean
	 * nothing: a template price of 950 put an L1 enchantment stone at a few kinah, where a broker sold it at 20 000.
	 */
	public record Range(long low, long high) {

		/** @return The range halved as many times. */
		Range halved(int times) {
			return new Range(low >> times, high >> times);
		}
	}

	/** The floor, as a share of what an npc pays: under it a player buys from the bot and sells to an npc at a profit. */
	private static final double FLOOR_OVER_BUYBACK = 1.25;
	/** How far either way a keeper's temperament moves its prices. */
	private static final double TEMPERAMENT_SPREAD = 0.15;
	/** What a piece loses for each full hour it has waited unsold. */
	private static final double UNSOLD_HOURLY = 0.9;

	private BotStorePricing() {
	}

	/** @return What an npc pays for an item of that template price. */
	static long npcBuysAt(long templatePrice) {
		return templatePrice * PricesService.getVendorSellModifier() / 100;
	}

	/** @return The lowest a store may ask, at least one kinah. */
	static long floor(long templatePrice) {
		return Math.max(1, Math.round(npcBuysAt(templatePrice) * FLOOR_OVER_BUYBACK));
	}

	/** @return How much a quality is worth over what an npc pays, the same scale for every level. */
	static double qualityFactor(ItemQuality quality) {
		return switch (quality) {
			case JUNK, COMMON -> 1.3;
			case RARE -> 1.6;
			case LEGEND -> 2.5;
			case UNIQUE -> 4;
			default -> 6; // epic and above
		};
	}

	/** @return The price a piece of gear is worth before anybody's temperament or any waiting. */
	static long fairPrice(long templatePrice, ItemQuality quality) {
		return Math.max(Math.round(npcBuysAt(templatePrice) * qualityFactor(quality)), floor(templatePrice));
	}

	/** @return How much dearer or cheaper than fair this keeper sells, between 0.85 and 1.15, the same every day. */
	static double temperament(int keeperId) {
		int h = keeperId * 0x2545F491;
		h ^= h >>> 15;
		return 1 - TEMPERAMENT_SPREAD + 2 * TEMPERAMENT_SPREAD * Math.floorMod(h, 1001) / 1000.0;
	}

	/**
	 * @param hoursUnsold Full hours the piece has waited since it was stocked.
	 * @return What the keeper asks for one piece of gear.
	 */
	public static long price(long templatePrice, ItemQuality quality, int keeperId, long hoursUnsold) {
		double asked = fairPrice(templatePrice, quality) * temperament(keeperId) * Math.pow(UNSOLD_HOURLY, Math.max(0, hoursUnsold));
		return Math.max(floor(templatePrice), Math.round(asked));
	}

	/**
	 * What the keeper asks for goods priced by a broker's range: its temperament places it in the range, the cheapest keeper at the bottom and the
	 * dearest at the top, and an unsold line falls by the hour as any other but stops at the bottom of the range rather than at the npc floor.
	 *
	 * @param hoursUnsold Full hours the piece has waited since it was stocked.
	 * @return What the keeper asks for one of it.
	 */
	public static long price(Range range, int keeperId, long hoursUnsold) {
		double place = (temperament(keeperId) - (1 - TEMPERAMENT_SPREAD)) / (2 * TEMPERAMENT_SPREAD);
		double asked = (range.low() + (range.high() - range.low()) * place) * Math.pow(UNSOLD_HOURLY, Math.max(0, hoursUnsold));
		return Math.max(range.low(), Math.round(asked));
	}
}
