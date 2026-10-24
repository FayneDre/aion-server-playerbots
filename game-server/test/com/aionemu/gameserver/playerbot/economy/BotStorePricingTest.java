package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.configs.main.PricesConfig;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.playerbot.economy.BotStorePricing.Kind;

/**
 * The prices of docs/bot-stores-plan.md: nothing under what an npc pays plus a quarter, quality worth what the plan says, a keeper a little dearer or
 * cheaper than another, and the unsold cheaper by the hour down to the floor.
 */
class BotStorePricingTest {

	/** A level 30 chestpiece's template price, the plan's own example. */
	private static final long CHESTPIECE = 518_000;

	static {
		PricesConfig.VENDOR_SELL_MODIFIER = 20; // the server's setting, which a test does not load
	}

	@Test
	void thePlansExampleChestpiece() {
		assertEquals(165_760, BotStorePricing.fairPrice(Kind.GEAR, CHESTPIECE, ItemQuality.RARE, 30));
		assertEquals(259_000, BotStorePricing.fairPrice(Kind.GEAR, CHESTPIECE, ItemQuality.LEGEND, 30));
		assertEquals(414_400, BotStorePricing.fairPrice(Kind.GEAR, CHESTPIECE, ItemQuality.UNIQUE, 30));
	}

	@Test
	void nothingSellsUnderWhatAnNpcPaysAndAQuarter() {
		long floor = BotStorePricing.floor(CHESTPIECE);
		assertEquals(129_500, floor);
		for (int keeper = 0; keeper < 500; keeper++)
			assertTrue(BotStorePricing.price(Kind.GEAR, CHESTPIECE, ItemQuality.COMMON, 30, keeper, 100) >= floor, "keeper " + keeper);
	}

	@Test
	void stonesArePricedByTheirLevel() {
		// a manastone's template price is about 1000: priced off it, a level 30 stone would sell for a few hundred kinah
		assertEquals(14_400, BotStorePricing.fairPrice(Kind.MANASTONE, 1_000, ItemQuality.RARE, 30));
		assertEquals(4_700, BotStorePricing.fairPrice(Kind.ENCHANTMENT, 470, ItemQuality.COMMON, 94));
	}

	@Test
	void aKeepersTemperamentIsItsOwnAndModest() {
		double lowest = 2, highest = 0;
		for (int keeper = 0; keeper < 1000; keeper++) {
			double temperament = BotStorePricing.temperament(keeper);
			assertEquals(temperament, BotStorePricing.temperament(keeper), "the same every day");
			lowest = Math.min(lowest, temperament);
			highest = Math.max(highest, temperament);
		}
		assertTrue(lowest >= 0.85 && lowest < 0.9, "lowest " + lowest);
		assertTrue(highest <= 1.15 && highest > 1.1, "highest " + highest);
	}

	@Test
	void theGuidesGoodsSellWithinTheBrokersRange() {
		BotStoreGoods.Good l4 = new BotStoreGoods.Good(java.util.List.of(166000194), 50, 3_000_000, 6_000_000, 2, 10, false);
		long cheapest = Long.MAX_VALUE, dearest = 0;
		for (int keeper = 0; keeper < 1000; keeper++) {
			long price = BotStorePricing.price(l4, keeper, 0);
			cheapest = Math.min(cheapest, price);
			dearest = Math.max(dearest, price);
		}
		assertTrue(cheapest >= 3_000_000 && cheapest < 3_100_000, "cheapest " + cheapest);
		assertTrue(dearest <= 6_000_000 && dearest > 5_900_000, "dearest " + dearest);
	}

	@Test
	void theGuidesGoodsStopFallingAtTheBottomOfTheRange() {
		BotStoreGoods.Good gold = new BotStoreGoods.Good(java.util.List.of(186000004), 31, 35_000, 50_000, 10, 50, false);
		long fresh = BotStorePricing.price(gold, 7, 0);
		assertEquals(Math.round(fresh * 0.9), BotStorePricing.price(gold, 7, 1), 1);
		assertEquals(35_000, BotStorePricing.price(gold, 7, 24), "a day unsold is the bottom of the range, not the npc floor");
	}

	@Test
	void theUnsoldGetCheaperByTheHour() {
		long fresh = BotStorePricing.price(Kind.GEAR, CHESTPIECE, ItemQuality.LEGEND, 30, 7, 0);
		long anHour = BotStorePricing.price(Kind.GEAR, CHESTPIECE, ItemQuality.LEGEND, 30, 7, 1);
		long aDay = BotStorePricing.price(Kind.GEAR, CHESTPIECE, ItemQuality.LEGEND, 30, 7, 24);
		assertEquals(Math.round(fresh * 0.9), anHour, 1);
		assertEquals(BotStorePricing.floor(CHESTPIECE), aDay, "a day unsold is down to the floor");
	}
}
