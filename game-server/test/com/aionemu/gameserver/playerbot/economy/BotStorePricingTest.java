package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.configs.main.PricesConfig;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.playerbot.economy.BotStorePricing.Range;

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
		assertEquals(165_760, BotStorePricing.fairPrice(CHESTPIECE, ItemQuality.RARE));
		assertEquals(259_000, BotStorePricing.fairPrice(CHESTPIECE, ItemQuality.LEGEND));
		assertEquals(414_400, BotStorePricing.fairPrice(CHESTPIECE, ItemQuality.UNIQUE));
	}

	@Test
	void nothingSellsUnderWhatAnNpcPaysAndAQuarter() {
		long floor = BotStorePricing.floor(CHESTPIECE);
		assertEquals(129_500, floor);
		for (int keeper = 0; keeper < 500; keeper++)
			assertTrue(BotStorePricing.price(CHESTPIECE, ItemQuality.COMMON, keeper, 100) >= floor, "keeper " + keeper);
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
		Range l4 = new Range(3_000_000, 6_000_000);
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
		Range gold = new Range(35_000, 50_000);
		long fresh = BotStorePricing.price(gold, 7, 0);
		assertEquals(Math.round(fresh * 0.9), BotStorePricing.price(gold, 7, 1), 1);
		assertEquals(35_000, BotStorePricing.price(gold, 7, 24), "a day unsold is the bottom of the range, not the npc floor");
	}

	@Test
	void theUnsoldGetCheaperByTheHour() {
		long fresh = BotStorePricing.price(CHESTPIECE, ItemQuality.LEGEND, 7, 0);
		long anHour = BotStorePricing.price(CHESTPIECE, ItemQuality.LEGEND, 7, 1);
		long aDay = BotStorePricing.price(CHESTPIECE, ItemQuality.LEGEND, 7, 24);
		assertEquals(Math.round(fresh * 0.9), anHour, 1);
		assertEquals(BotStorePricing.floor(CHESTPIECE), aDay, "a day unsold is down to the floor");
	}
}
