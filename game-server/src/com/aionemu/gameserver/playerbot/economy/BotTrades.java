package com.aionemu.gameserver.playerbot.economy;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.trade.Exchange;
import com.aionemu.gameserver.model.trade.ExchangeItem;
import com.aionemu.gameserver.services.ExchangeService;

/**
 * A companion's side of a trade with its owner, once {@code BotRequests} has accepted it.
 * <p>
 * A trade window has two steps on each side, lock and then confirm, and the trade goes through when both sides have confirmed. The bot follows its
 * owner: it locks once they have locked and confirms once they have confirmed, so the owner sees both sides before anything changes hands. It puts
 * nothing in by itself; what it hands over is what its owner names with {@code //bot give} while the window is open, before they lock.
 * <p>
 * What it is given goes to its bag, and a companion never dresses from its bag on its own: what is there is its owner's, kept for other characters
 * ({@code BotErrands.dressUp}). A piece handed over in a trade is the exception, since giving it is the owner saying "wear this": the bot puts it on
 * if it beats what it wears.
 */
public final class BotTrades {

	private static final Logger log = LoggerFactory.getLogger(BotTrades.class);

	/** How long a piece received in a trade stays one the bot may put on: long enough to finish a fight first, short enough to mean that trade. */
	private static final long GIFT_MILLIS = 2 * 60_000;

	/** Per bot, the templates of the pieces its owner has just handed it, and until when they count as handed. */
	private static final Map<Integer, Gifts> gifts = new ConcurrentHashMap<>();

	private record Gifts(Set<Integer> templateIds, long until) {
	}

	private BotTrades() {
	}

	/**
	 * Moves the bot's side of an open trade on by one step, or puts on what the last one gave it.
	 *
	 * @param fighting whether the bot is in a fight, when nothing is put on
	 */
	public static void tick(Player bot, boolean fighting) {
		ExchangeService service = ExchangeService.getInstance();
		Exchange theirs = service.getCurrentParnterExchange(bot);
		if (theirs == null) {
			if (!fighting)
				wearGifts(bot);
			return;
		}
		Exchange mine = service.getCurrentParnterExchange(theirs.getActiveplayer());
		if (mine == null)
			return;
		if (theirs.isLocked() && !mine.isLocked()) {
			service.lockExchange(bot);
		} else if (theirs.isConfirmed() && mine.isLocked() && !mine.isConfirmed()) {
			// read before confirming: confirming second is what carries the trade out, and the window is gone after it
			Set<Integer> given = new HashSet<>();
			for (ExchangeItem item : theirs.getItems().values())
				given.add(item.getItem().getItemTemplate().getTemplateId());
			if (!given.isEmpty())
				gifts.put(bot.getObjectId(), new Gifts(given, System.currentTimeMillis() + GIFT_MILLIS));
			log.info("Bot {} confirms its trade with {}: {} piece(s) and {} kinah given, {} piece(s) and {} kinah received", bot.getName(),
				theirs.getActiveplayer().getName(), mine.getItems().size(), mine.getKinahCount(), theirs.getItems().size(), theirs.getKinahCount());
			service.confirmExchange(bot);
		}
	}

	private static void wearGifts(Player bot) {
		Gifts given = gifts.get(bot.getObjectId());
		if (given == null)
			return;
		if (System.currentTimeMillis() > given.until() || !BotEquipManager.wearGiven(bot, given.templateIds()))
			gifts.remove(bot.getObjectId());
	}

	/**
	 * Puts what the owner names on the bot's side of the trade they have open with it: {@code all} for everything it carries but wears, {@code kinah}
	 * and an amount, or part of an item's name.
	 *
	 * @return What was done, for the chat window.
	 */
	public static String give(Player bot, Player owner, List<String> what) {
		ExchangeService service = ExchangeService.getInstance();
		Exchange theirs = service.getCurrentParnterExchange(bot);
		if (theirs == null || !theirs.getActiveplayer().equals(owner))
			return bot.getName() + " has no trade open with you: open one first, then name what it gives before you lock";
		Exchange mine = service.getCurrentParnterExchange(owner);
		if (mine == null || mine.isLocked())
			return bot.getName() + " has already locked its side: cancel the trade and open it again";
		if (what.isEmpty())
			return "Usage: //bot give <name> all | kinah <amount> | <part of an item's name>";
		if (what.get(0).equalsIgnoreCase("kinah")) {
			long amount;
			try {
				amount = what.size() > 1 ? Long.parseLong(what.get(1)) : bot.getInventory().getKinah();
			} catch (NumberFormatException e) {
				return "Not an amount: " + what.get(1);
			}
			long before = mine.getKinahCount();
			service.addKinah(bot, amount);
			return bot.getName() + " puts in " + (mine.getKinahCount() - before) + " kinah";
		}
		boolean all = what.size() == 1 && what.get(0).equalsIgnoreCase("all");
		String name = String.join(" ", what).toLowerCase(Locale.ROOT);
		int before = mine.getItems().size();
		for (Item item : bot.getInventory().getItems()) {
			if (mine.isExchangeListFull())
				break;
			if (item.isEquipped() || item.getItemTemplate().isKinah())
				continue;
			if (all || item.getItemTemplate().getName().toLowerCase(Locale.ROOT).contains(name))
				service.addItem(bot, item.getObjectId(), item.getItemCount()); // the engine's own checks: what cannot be traded is left where it is
		}
		int added = mine.getItems().size() - before;
		if (added == 0)
			return bot.getName() + " has nothing " + (all ? "" : "called \"" + name + "\" ") + "it can trade";
		return bot.getName() + " puts in " + added + " piece(s)" + (mine.isExchangeListFull() ? ", and the window is full" : "");
	}

	/** Forgets what is kept about a bot leaving the world. */
	public static void forget(Player bot) {
		gifts.remove(bot.getObjectId());
	}
}
