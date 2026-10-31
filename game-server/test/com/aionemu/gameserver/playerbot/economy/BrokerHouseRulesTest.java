package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/** The pure rules of the auction house (docs/capitals/plans/auction-house-plan.md): shares, price rule, buyer rules and ceilings, no server needed. */
class BrokerHouseRulesTest {

	private static ItemTemplate stackable, single, questItem, nonTradeable;

	@BeforeAll
	static void templates() throws Exception {
		String xml = "<item_templates>"
			+ "<item_template id=\"1\" name=\"Ore\" level=\"30\" price=\"100\" max_stack_count=\"999\" mask=\"2\"/>"
			+ "<item_template id=\"2\" name=\"Sword\" level=\"30\" price=\"1000\" mask=\"2\"/>"
			+ "<item_template id=\"3\" name=\"Letter\" level=\"1\" price=\"10\" mask=\"2\" item_group=\"QUEST\"/>"
			+ "<item_template id=\"4\" name=\"Bound\" level=\"1\" price=\"10\" mask=\"0\"/>"
			+ "</item_templates>";
		ItemData data = JAXBUtil.deserialize(new StringReader(xml), ItemData.class);
		stackable = data.getItemTemplate(1);
		single = data.getItemTemplate(2);
		questItem = data.getItemTemplate(3);
		nonTradeable = data.getItemTemplate(4);
	}

	// what a player never meets on the market

	private static ItemTemplate named(int id, String name, String cName, int price) throws Exception {
		String xml = "<item_templates><item_template id=\"" + id + "\" name=\"" + name + "\" level=\"20\" price=\"" + price + "\" mask=\"2\""
			+ (cName == null ? "" : " cName=\"" + cName + "\"") + "/></item_templates>";
		return JAXBUtil.deserialize(new StringReader(xml), ItemData.class).getItemTemplate(id);
	}

	@Test
	void theMonstersGearTheTestPiecesAndTheEventAndShopGoodsAreNotPlayerGoods() throws Exception {
		assertFalse(BrokerHouseStock.isPlayerGoods(named(10, "NPC Asmodian Abyss Sword", "npc_sword_mpabyss_01", 5)));
		assertFalse(BrokerHouseStock.isPlayerGoods(named(11, "Gold Sword", "npc_sword_n_r_01", 9000)), "the client name says it is a monster's");
		assertFalse(BrokerHouseStock.isPlayerGoods(named(12, "Practice Sword", "sword_d_n_c_01a", 5)));
		assertFalse(BrokerHouseStock.isPlayerGoods(named(13, "[Event] Greater Courage", null, 100)));
		assertFalse(BrokerHouseStock.isPlayerGoods(named(14, "[Stamp] Cat", null, 100)));
		assertFalse(BrokerHouseStock.isPlayerGoods(named(15, "Test Stone", null, 100)));
		assertTrue(BrokerHouseStock.isPlayerGoods(named(16, "Manku's Sword", "sword_n_r2_22a", 60850)));
		assertTrue(BrokerHouseStock.isPlayerGoods(named(17, "Ore", null, 5)), "a cheap material is a material, only gear priced at the placeholder is not a price");
	}

	// shares

	@Test
	void sharesAreParsedAndBadOnesLeftOut() {
		Map<BrokerItemMask, Integer> shares = BrokerHouseShares.parse("weapon:10; ARMOR:30;NOPE:5;CRAFT:x;OTHER:-1;", BrokerItemMask.class);
		assertEquals(Map.of(BrokerItemMask.WEAPON, 10, BrokerItemMask.ARMOR, 30), shares);
	}

	@Test
	void aSplitAddsUpToTheTotalExactly() {
		Map<BrokerItemMask, Integer> shares = new EnumMap<>(BrokerItemMask.class);
		shares.put(BrokerItemMask.WEAPON, 12);
		shares.put(BrokerItemMask.ARMOR, 16);
		shares.put(BrokerItemMask.CRAFT, 22);
		shares.put(BrokerItemMask.CONSUMABLES, 30);
		for (int total : new int[] { 1, 7, 100, 6000, 12001 }) {
			Map<BrokerItemMask, Integer> parts = BrokerHouseShares.split(total, shares, BrokerItemMask.class);
			assertEquals(total, parts.values().stream().mapToInt(Integer::intValue).sum());
		}
		Map<BrokerItemMask, Integer> parts = BrokerHouseShares.split(8000, shares, BrokerItemMask.class);
		assertEquals(1200, parts.get(BrokerItemMask.WEAPON));
		assertEquals(3000, parts.get(BrokerItemMask.CONSUMABLES));
	}

	@Test
	void aSplitOfNothingIsEmpty() {
		assertTrue(BrokerHouseShares.split(0, Map.of(BrokerItemMask.WEAPON, 1), BrokerItemMask.class).isEmpty());
		assertTrue(BrokerHouseShares.split(10, Map.of(), BrokerItemMask.class).isEmpty());
	}

	@Test
	void missingIsWhatIsLackingAndNeverNegative() {
		Map<BrokerItemMask, Integer> targets = Map.of(BrokerItemMask.WEAPON, 10, BrokerItemMask.ARMOR, 5, BrokerItemMask.CRAFT, 3);
		Map<BrokerItemMask, Integer> held = Map.of(BrokerItemMask.WEAPON, 4, BrokerItemMask.ARMOR, 9);
		Map<BrokerItemMask, Integer> lacking = BrokerHouseShares.missing(targets, held, BrokerItemMask.class);
		assertEquals(Map.of(BrokerItemMask.WEAPON, 6, BrokerItemMask.CRAFT, 3), lacking);
	}

	@Test
	void aDrawKeepsToTheAllowedKeysAndTheirWeights() {
		Map<ItemQuality, Integer> weights = Map.of(ItemQuality.COMMON, 90, ItemQuality.RARE, 10, ItemQuality.EPIC, 1000);
		Random random = new Random(1);
		int common = 0;
		for (int i = 0; i < 10_000; i++) {
			ItemQuality drawn = BrokerHouseShares.draw(weights, q -> q != ItemQuality.EPIC, random);
			assertNotEquals(ItemQuality.EPIC, drawn);
			if (drawn == ItemQuality.COMMON)
				common++;
		}
		assertTrue(common > 8700 && common < 9300, "common drawn " + common);
		assertNull(BrokerHouseShares.draw(weights, q -> false, random));
	}

	// the level curve and stacks

	@Test
	void theLevelCurveFavoursTheServersPopulation() {
		assertTrue(BrokerHouseStock.levelWeight(30) > BrokerHouseStock.levelWeight(5));
		assertTrue(BrokerHouseStock.levelWeight(30) > BrokerHouseStock.levelWeight(60));
		assertTrue(BrokerHouseStock.levelWeight(60) > BrokerHouseStock.levelWeight(80));
		assertEquals(BrokerHouseStock.levelWeight(20), BrokerHouseStock.levelWeight(55));
	}

	@Test
	void onlyTradeableListableTemplatesAreCandidates() {
		assertTrue(BrokerHouseStock.isCandidate(single, Race.ELYOS, Set.of()));
		assertFalse(BrokerHouseStock.isCandidate(single, Race.ELYOS, Set.of(2)), "blacklisted");
		assertFalse(BrokerHouseStock.isCandidate(nonTradeable, Race.ELYOS, Set.of()));
		assertFalse(BrokerHouseStock.isCandidate(questItem, Race.ELYOS, Set.of()), "quest item");
	}

	@Test
	void aStackIsOneForGearAndWithinTheLimitsForGoods() {
		Random random = new Random(7);
		for (int i = 0; i < 2000; i++) {
			assertEquals(1, BrokerHouseStock.stackSize(single, BrokerItemMask.WEAPON, random));
			long heap = BrokerHouseStock.stackSize(stackable, BrokerItemMask.CRAFT, random);
			assertTrue(heap >= 1 && heap <= 100, "heap " + heap);
			long few = BrokerHouseStock.stackSize(stackable, BrokerItemMask.OTHER, random);
			assertTrue(few >= 1 && few <= 10, "few " + few);
		}
	}

	// prices

	@Test
	void anAskStaysInItsSpreadAboveTheFloor() {
		long market = 10_000;
		long low = BrokerHousePricing.ask(market, 1, 0, 0), high = BrokerHousePricing.ask(market, 1, 0, 1);
		assertEquals(Math.round(market * 0.85 * 1.05), low);
		assertEquals(Math.round(market * 1.15 * 1.05), high);
		assertEquals(BrokerHousePricing.maxAsk(market), high);
		assertEquals(9_000, BrokerHousePricing.ask(market, 9_000, 0, 0), "never under the floor");
		assertEquals(12_000, BrokerHousePricing.ask(market, 1, 12_000, 1.0) , "the ceiling holds when it is under the top of the spread");
		assertTrue(BrokerHousePricing.ask(market, 1, 0, 0.5) >= market, "the middle of the spread is at or over the market");
	}

	@Test
	void aFloorOutranksACeilingThatIsUnderIt() {
		assertEquals(500, BrokerHousePricing.ask(100, 500, 300, 0.5));
	}

	@Test
	void materialsAreWorthMoreThanTheirNpcPriceAndMoreWhenBetter() {
		long common = BrokerHousePricing.materialMarket(100, ItemQuality.COMMON), legend = BrokerHousePricing.materialMarket(100, ItemQuality.LEGEND);
		assertTrue(common > 100);
		assertTrue(legend > common);
		assertEquals(1, BrokerHousePricing.materialMarket(0, ItemQuality.COMMON));
	}

	@Test
	void noKnownPriceMeansNoRange() {
		assertFalse(BrokerHousePricing.inRange(1, 0, 5));
		assertTrue(BrokerHousePricing.inRange(100, 100, 5));
		assertTrue(BrokerHousePricing.inRange(105, 100, 5), "the tolerance");
		assertFalse(BrokerHousePricing.inRange(106, 100, 5));
		assertTrue(BrokerHousePricing.inRange(1, 100, 5), "a bargain is bought too");
	}

	// the buyer

	@Test
	void theBuyerNeverBuysAHousesListing() {
		assertFalse(BrokerHouseBuyer.eligible(true, 1, 100, 5));
		assertTrue(BrokerHouseBuyer.eligible(false, 100, 100, 5));
		assertFalse(BrokerHouseBuyer.eligible(false, 200, 100, 5), "over the range");
		assertFalse(BrokerHouseBuyer.eligible(false, 1, 0, 5), "no known price");
	}

	@Test
	void theDelayIsBetweenOneAndFiveMinutes() {
		Random random = new Random(3);
		for (int i = 0; i < 5000; i++) {
			long delay = BrokerHouseBuyer.delayMillis(random, 1, 5);
			assertTrue(delay >= 60_000 && delay <= 300_000, "delay " + delay);
		}
		assertEquals(120_000, BrokerHouseBuyer.delayMillis(random, 2, 2));
		long swapped = BrokerHouseBuyer.delayMillis(random, 5, 1);
		assertTrue(swapped >= 60_000 && swapped <= 300_000);
	}

	// ceilings

	@Test
	void theThreeCeilingsHoldAndTheDayRollsOver() {
		BrokerHouseLedger ledger = new BrokerHouseLedger(1000, 400, 300);
		LocalDate monday = LocalDate.of(2026, 11, 2);
		assertTrue(ledger.allows(monday, 1, 10, 300));
		ledger.record(monday, 1, 10, 300);
		assertFalse(ledger.allows(monday, 2, 10, 1), "per item kind");
		assertFalse(ledger.allows(monday, 1, 11, 101), "per seller");
		assertTrue(ledger.allows(monday, 1, 11, 100));
		ledger.record(monday, 1, 11, 100);
		ledger.record(monday, 2, 12, 300);
		ledger.record(monday, 3, 13, 300);
		assertFalse(ledger.allows(monday, 4, 14, 101), "in all (1000 - 1000 spent)");
		assertEquals(1000, ledger.spentToday(monday));
		assertTrue(ledger.allows(monday.plusDays(1), 1, 10, 300), "a new day");
		assertEquals(0, ledger.spentToday(monday.plusDays(1)));
	}

	@Test
	void blacklistIdsAreParsedLeniently() {
		assertEquals(Set.of(5, 7), BrokerHouse.parseIds(" 5, 7 ,,x"));
		assertEquals(Set.of(), BrokerHouse.parseIds(null));
	}
}
