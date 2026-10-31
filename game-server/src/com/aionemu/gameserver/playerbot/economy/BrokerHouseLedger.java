package com.aionemu.gameserver.playerbot.economy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * What the house's buyer has spent today: in all, on each seller and on each kind of item. The buyer is a faucet of kinah, so these ceilings are what keep a
 * hoard sold for a fortune, or one player's repeated sales, from draining it. A day is the server's calendar day; the sums start again with the next.
 */
public final class BrokerHouseLedger {

	private final long dailyTotal, dailyPerSeller, dailyPerItem;
	private LocalDate day;
	private long total;
	private final Map<Integer, Long> bySeller = new HashMap<>();
	private final Map<Integer, Long> byItem = new HashMap<>();

	public BrokerHouseLedger(long dailyTotal, long dailyPerSeller, long dailyPerItem) {
		this.dailyTotal = dailyTotal;
		this.dailyPerSeller = dailyPerSeller;
		this.dailyPerItem = dailyPerItem;
	}

	/** @return true if buying for that many kinah from that seller, of that kind of item, stays under all three ceilings today. */
	public synchronized boolean allows(LocalDate today, int sellerId, int itemId, long cost) {
		rollTo(today);
		return total + cost <= dailyTotal && bySeller.getOrDefault(sellerId, 0L) + cost <= dailyPerSeller
			&& byItem.getOrDefault(itemId, 0L) + cost <= dailyPerItem;
	}

	/** Records a purchase. */
	public synchronized void record(LocalDate today, int sellerId, int itemId, long cost) {
		rollTo(today);
		total += cost;
		bySeller.merge(sellerId, cost, Long::sum);
		byItem.merge(itemId, cost, Long::sum);
	}

	/** @return Kinah spent today in all. */
	public synchronized long spentToday(LocalDate today) {
		rollTo(today);
		return total;
	}

	private void rollTo(LocalDate today) {
		if (!today.equals(day)) {
			day = today;
			total = 0;
			bySeller.clear();
			byItem.clear();
		}
	}
}
