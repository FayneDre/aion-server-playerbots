package com.aionemu.gameserver.playerbot.economy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.BrokerItem;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.services.BrokerService;
import com.aionemu.gameserver.services.player.PlayerService;

/**
 * The house buys the listings of players priced within the known range (docs/capitals/plans/auction-house-plan.md, decision 6).
 * <p>
 * A listing is judged on its price alone: a house's listing never, one with no known price never, one over the top of the range plus the tolerance
 * never, any other (a bargain far under the range too) yes. A listing found in range is bought a random 1 to 5 minutes later, not at once and not after
 * hours; a pass buys a bounded number, and three daily ceilings in kinah (in all, per seller, per kind of item) hold the faucet. Every purchase is one log
 * line. The item is destroyed ({@link BrokerService#buyForHouse}), never relisted.
 */
public final class BrokerHouseBuyer {

	private static final Logger log = LoggerFactory.getLogger(BrokerHouseBuyer.class);

	private final Random random = new Random();
	private final Function<ItemTemplate, BrokerItemMask> categories;
	/** When each listing in range is due to be bought, by the listing's id; swept of what is no longer in range or no longer there on every pass. */
	private final Map<Integer, Long> due = new HashMap<>();
	private final BrokerHouseLedger ledger = new BrokerHouseLedger(PlayerBotConfig.BROKER_BUYER_DAILY_KINAH, PlayerBotConfig.BROKER_BUYER_DAILY_KINAH_PER_PLAYER,
		PlayerBotConfig.BROKER_BUYER_DAILY_KINAH_PER_ITEM);

	public BrokerHouseBuyer(Function<ItemTemplate, BrokerItemMask> categories) {
		this.categories = categories;
	}

	/**
	 * @return true if a player's listing may be bought at all: not a house's, and its price for one within the known range plus the tolerance. Whether it
	 *         is bought is not a chance roll.
	 */
	static boolean eligible(boolean sellerIsHouse, long unitPrice, long top, int tolerancePercent) {
		return !sellerIsHouse && BrokerHousePricing.inRange(unitPrice, top, tolerancePercent);
	}

	/** @return Milliseconds to wait before buying a listing found in range: a draw between the two bounds (in minutes, swapped if given the wrong way). */
	static long delayMillis(Random random, int minMinutes, int maxMinutes) {
		int low = Math.max(0, Math.min(minMinutes, maxMinutes)), high = Math.max(low, Math.max(minMinutes, maxMinutes));
		return (long) ((low + random.nextDouble() * (high - low)) * 60_000L);
	}

	/** One pass over both brokers. @return The number of listings bought. */
	public synchronized int pass(long now, LocalDate today) {
		int bought = 0;
		Map<Integer, Long> stillThere = new HashMap<>();
		int limit = Math.max(0, PlayerBotConfig.BROKER_BUYER_MAX_PER_PASS);
		for (Race race : new Race[] { Race.ELYOS, Race.ASMODIANS }) {
			for (BrokerItem listing : BrokerService.getInstance().getListings(race)) {
				if (listing.isSold() || listing.isCanceled() || listing.getItem() == null)
					continue;
				ItemTemplate template = listing.getItem().getItemTemplate();
				BrokerItemMask category = categories.apply(template);
				long top = BrokerHousePricing.top(template, category == BrokerItemMask.CRAFT);
				if (!eligible(BotRoster.isHouse(listing.getSellerId()), listing.getPrice(), top, PlayerBotConfig.BROKER_BUYER_TOLERANCE_PERCENT))
					continue;
				long dueAt = due.computeIfAbsent(listing.getItemUniqueId(),
					id -> now + delayMillis(random, PlayerBotConfig.BROKER_BUYER_MIN_DELAY_MINUTES, PlayerBotConfig.BROKER_BUYER_MAX_DELAY_MINUTES));
				stillThere.put(listing.getItemUniqueId(), dueAt);
				if (dueAt > now || bought >= limit)
					continue;
				long cost = listing.getPrice() * listing.getItemCount();
				if (!ledger.allows(today, listing.getSellerId(), listing.getItemId(), cost))
					continue;
				String seller = PlayerService.getPlayerName(listing.getSellerId());
				if (BrokerService.getInstance().buyForHouse(listing)) {
					ledger.record(today, listing.getSellerId(), listing.getItemId(), cost);
					stillThere.remove(listing.getItemUniqueId());
					bought++;
					log.info("Broker house bought {} x{} (item {}) from {} at {} each, {} kinah in all", template.getName(), listing.getItemCount(), listing.getItemId(),
						seller, listing.getPrice(), cost);
				}
			}
		}
		due.clear();
		due.putAll(stillThere);
		return bought;
	}
}
