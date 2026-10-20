package com.aionemu.gameserver.playerbot.economy;

import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.services.trade.PricesService;

/**
 * What a bot's store asks for a piece.
 * <p>
 * The data's only price is the template's, which an npc sells at and buys back at a fifth of, and which is the same for a green piece and a gold one
 * of the same level. So the template gives the scale and the floor, the quality gives the worth, and the keeper's temperament and the time the piece has
 * waited move it from there. The numbers are the ones written in docs/bot-stores-plan.md, decided on 20 October.
 */
public final class BotStorePricing {

	/** What a piece is, as far as its price goes: gear is priced off its template, stones off their level, since their template prices mean nothing. */
	public enum Kind {
		GEAR, MANASTONE, ENCHANTMENT
	}

	/** The floor, as a share of what an npc pays: under it a player buys from the bot and sells to an npc at a profit. */
	private static final double FLOOR_OVER_BUYBACK = 1.25;
	/** A manastone's worth per level of the stone, and an enchantment stone's per grade, before quality. A manastone's template price is about 1000. */
	private static final long MANASTONE_PER_LEVEL = 300, ENCHANTMENT_PER_GRADE = 50;
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

	/** @return The price a piece is worth before anybody's temperament or any waiting. */
	static long fairPrice(Kind kind, long templatePrice, ItemQuality quality, int level) {
		long fair = switch (kind) {
			case GEAR -> Math.round(npcBuysAt(templatePrice) * qualityFactor(quality));
			case MANASTONE -> Math.round(MANASTONE_PER_LEVEL * level * qualityFactor(quality));
			case ENCHANTMENT -> ENCHANTMENT_PER_GRADE * level;
		};
		return Math.max(fair, floor(templatePrice));
	}

	/** @return How much dearer or cheaper than fair this keeper sells, between 0.85 and 1.15, the same every day. */
	static double temperament(int keeperId) {
		int h = keeperId * 0x2545F491;
		h ^= h >>> 15;
		return 1 - TEMPERAMENT_SPREAD + 2 * TEMPERAMENT_SPREAD * Math.floorMod(h, 1001) / 1000.0;
	}

	/**
	 * @param hoursUnsold Full hours the piece has waited since it was stocked.
	 * @return What the keeper asks for one of it.
	 */
	public static long price(Kind kind, long templatePrice, ItemQuality quality, int level, int keeperId, long hoursUnsold) {
		double asked = fairPrice(kind, templatePrice, quality, level) * temperament(keeperId) * Math.pow(UNSOLD_HOURLY, Math.max(0, hoursUnsold));
		return Math.max(floor(templatePrice), Math.round(asked));
	}
}
