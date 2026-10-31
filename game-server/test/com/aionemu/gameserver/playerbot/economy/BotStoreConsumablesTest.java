package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.economy.BotStoreStock.Theme;

/** The shares of the stores' themes, and what the goods lists of the scrolls hold; what the item data says of the ids is BotStoreGoodsDataTest's. */
class BotStoreConsumablesTest {

	@Test
	void theThemesAreSharedAsTheTesterAnsweredOverALargeMarket() {
		Map<Theme, Integer> counts = new HashMap<>();
		int keepers = 60_000;
		for (int id = 1; id <= keepers; id++)
			counts.merge(BotStoreStock.themeOf(id * 7919), 1, Integer::sum);
		assertShare(counts, Theme.FOOD, 0.20);
		assertShare(counts, Theme.SCROLLS, 1.0 / 6);
		assertShare(counts, Theme.STONES, 1.0 / 6);
		assertShare(counts, Theme.MIXED, 1.0 / 6);
		assertShare(counts, Theme.COINS, 0.10);
		assertShare(counts, Theme.GEAR, 0.10);
		assertShare(counts, Theme.ACCESSORIES, 0.10);
	}

	private static void assertShare(Map<Theme, Integer> counts, Theme theme, double expected) {
		double share = counts.getOrDefault(theme, 0) / 60_000.0;
		assertEquals(expected, share, 0.03, theme + " " + share);
	}

	@Test
	void aboutOneStonesKeeperInFourIsWide() {
		int wide = 0;
		for (int id = 1; id <= 40_000; id++) {
			if (BotStoreStock.isWide(id * 7919))
				wide++;
		}
		assertEquals(0.25, wide / 40_000.0, 0.03);
	}

	@Test
	void everyScrollOfTheGuideIsDistinctAndPricedUpward() {
		List<Integer> ids = BotStoreGoods.SCROLLS.stream().map(good -> good.ids().getFirst()).toList();
		assertEquals(ids.size(), ids.stream().distinct().count());
		for (BotStoreGoods.Good good : BotStoreGoods.SCROLLS) {
			assertTrue(good.low() > 0 && good.low() <= good.high(), good.ids().toString());
			assertTrue(good.minCount() >= 1 && good.minCount() <= good.maxCount(), good.ids().toString());
		}
		for (BotStoreGoods.Good good : BotStoreGoods.EVENT_SCROLLS)
			assertFalse(ids.contains(good.ids().getFirst()), "an event scroll is not a plain one: " + good.ids());
	}

	@Test
	void theRareGoodsAreTheThreeTheTesterNamed() {
		assertEquals(List.of(166020000, 166500002, 166030005), BotStoreGoods.rareIds());
	}
}
