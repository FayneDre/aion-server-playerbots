package com.aionemu.gameserver.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.broker.BrokerRace;
import com.aionemu.gameserver.model.gameobjects.BrokerItem;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The broker's doors for a house seller (the auction house plan, milestone 1), on the maps alone: no database, no server, no player. The instance methods
 * add the save tasks and the seller's notice to these steps.
 */
class BrokerHouseTest {

	private static final int HOUSE = 100, PLAYER = 200;
	private static final long HOUR = 3_600_000L;
	private static ItemTemplate template;

	@BeforeAll
	static void aTemplate() throws Exception {
		String xml = "<item_templates><item_template id=\"166000001\" name=\"Test stone\" level=\"1\" mask=\"64\" price=\"10\"/></item_templates>";
		template = JAXBUtil.deserialize(new StringReader(xml), ItemData.class).getItemTemplate(166000001);
	}

	private static Item item(int objId) {
		return new Item(objId, template, 5, false, 0);
	}

	private static BrokerItem sold(int objId, int sellerId) {
		BrokerItem entry = new BrokerItem(item(objId), 10, sellerId, false, BrokerRace.ELYOS);
		entry.removeItem();
		return entry;
	}

	/** A listing of that seller, expiring that many milliseconds from now (negative: already expired). */
	private static BrokerItem listing(int objId, int sellerId, long expiresIn) {
		long now = System.currentTimeMillis();
		return new BrokerItem(item(objId), template.getTemplateId(), objId, 5, null, 10, sellerId, BrokerRace.ELYOS, false, false,
			new Timestamp(now + expiresIn), new Timestamp(now), false);
	}

	@Test
	void aHouseListingTakesNoInventoryAndIsOwnedByTheBroker() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>();
		Item item = item(1);
		BrokerItem listing = BrokerService.listForHouse(active, BrokerRace.ELYOS, HOUSE, item, 50, true);
		assertSame(listing, active.get(1));
		assertEquals(HOUSE, listing.getSellerId());
		assertEquals(StorageType.BROKER.getId(), item.getItemLocation());
		assertEquals(PersistentState.NEW, listing.getPersistentState());
		assertTrue(BrokerService.isLive(active, listing));
	}

	@Test
	void thereIsNoLimitOfFifteenListings() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>();
		for (int i = 1; i <= 200; i++)
			BrokerService.listForHouse(active, BrokerRace.ELYOS, HOUSE, item(i), 50, false);
		assertEquals(200, active.size());
	}

	@Test
	void settlingDeletesTheHousesSoldAndExpiredEntriesAndNobodyElses() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>(), settled = new ConcurrentHashMap<>();
		BrokerItem soldOne = sold(1, HOUSE), soldOfAPlayer = sold(2, PLAYER);
		BrokerItem expired = listing(3, HOUSE, -HOUR), fresh = listing(4, HOUSE, 24 * HOUR), expiredOfAPlayer = listing(5, PLAYER, -HOUR);
		settled.put(1, soldOne);
		settled.put(2, soldOfAPlayer);
		for (BrokerItem entry : List.of(expired, fresh, expiredOfAPlayer))
			active.put(entry.getItemUniqueId(), entry);

		List<BrokerItem> deleted = BrokerService.collectHouseSettlement(active, settled, HOUSE, System.currentTimeMillis());

		assertEquals(2, deleted.size());
		assertTrue(deleted.containsAll(List.of(soldOne, expired)));
		assertEquals(Map.of(2, soldOfAPlayer), settled);
		assertEquals(Map.of(4, fresh, 5, expiredOfAPlayer), active);
		assertEquals(PersistentState.DELETED, expired.getPersistentState()); // loaded from the table, so there is a row to delete
		assertEquals(PersistentState.NOACTION, expired.getItem().getPersistentState()); // the test item was never stored: marked deleted, nothing to delete
		assertEquals(PersistentState.NOACTION, soldOne.getPersistentState()); // never saved: nothing to delete
	}

	@Test
	void settlingAlsoDeletesWhatTheEngineAlreadyReturnedToTheSettledList() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>(), settled = new ConcurrentHashMap<>();
		BrokerItem returned = listing(1, HOUSE, -HOUR);
		returned.setSettled(); // what checkExpiredItems does to an expired listing
		settled.put(1, returned);
		assertEquals(List.of(returned), BrokerService.collectHouseSettlement(active, settled, HOUSE, System.currentTimeMillis()));
		assertTrue(settled.isEmpty());
	}

	@Test
	void settlingASoldEntryLeavesTheBuyersItemAloneButDeletesAnExpiredListingsOwn() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>(), settled = new ConcurrentHashMap<>();
		BrokerItem soldEntry = sold(1, HOUSE), expired = listing(2, HOUSE, -HOUR);
		settled.put(1, soldEntry);
		active.put(2, expired);
		BrokerService.collectHouseSettlement(active, settled, HOUSE, System.currentTimeMillis());
		assertEquals(PersistentState.DELETED, soldEntry.getPersistentState());
		assertNotEquals(PersistentState.DELETED, soldEntry.getItem().getPersistentState(), "the item is in the buyer's inventory, it must not be deleted");
		assertEquals(PersistentState.DELETED, expired.getItem().getPersistentState());
	}

	@Test
	void aSecondSettlementFindsNothing() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>(), settled = new ConcurrentHashMap<>();
		settled.put(1, sold(1, HOUSE));
		assertEquals(1, BrokerService.collectHouseSettlement(active, settled, HOUSE, System.currentTimeMillis()).size());
		assertEquals(0, BrokerService.collectHouseSettlement(active, settled, HOUSE, System.currentTimeMillis()).size());
	}

	@Test
	void onlyALiveListingOfTheMapCanBeBoughtByTheHouse() {
		Map<Integer, BrokerItem> active = new ConcurrentHashMap<>();
		BrokerItem live = listing(1, PLAYER, 24 * HOUR), absent = listing(2, PLAYER, 24 * HOUR), alreadySold = listing(3, PLAYER, 24 * HOUR);
		active.put(1, live);
		active.put(3, alreadySold);
		alreadySold.removeItem();
		assertTrue(BrokerService.isLive(active, live));
		assertFalse(BrokerService.isLive(active, absent));
		assertFalse(BrokerService.isLive(active, alreadySold));
		assertFalse(BrokerService.isLive(null, live));
	}

	@Test
	void aBoughtListingIsASoldSettledEntryWhoseKinahTheSellerCollects() {
		BrokerItem live = listing(1, PLAYER, 24 * HOUR);
		live.removeItem(); // what putToSettled does first for any sale
		assertTrue(live.isSold() && live.isSettled());
		assertEquals(50, live.getPrice() * live.getItemCount());
	}
}
