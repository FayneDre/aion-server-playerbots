package com.aionemu.gameserver.playerbot.social;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;

/**
 * Answers the questions a player asks a bot: an invitation, a duel, a friend request, a trade.
 * <p>
 * The engine asks each of them the same way, a handler left on the bot's {@code ResponseRequester} under the question's message id and a window sent to
 * a client the bot does not have. The bot reads who asks ({@code requesterOf}) and answers on its decision tick. Who may ask what is decided by
 * {@link #accepts}, from a record and nothing else, which is what lets the rules be tested. A refusal is answered too, never left pending: a question
 * nobody answers stays on the bot and the engine then tells everybody after the first that the bot "is being asked a question".
 */
public final class BotRequests {

	private static final Logger log = LoggerFactory.getLogger(BotRequests.class);

	/** How far apart in level a bot and a player may be for the bot to take a duel: further than this it is not a fight but a beating, either way. */
	static final int DUEL_LEVEL_GAP = 10;
	/** How long a bot refuses a second duel from the same player after taking one, so that nobody farms it for the duel rewards. */
	static final long DUEL_REST_MILLIS = 3 * 60_000;

	/** The questions a bot answers, each under the message id the engine files it with. */
	public enum Kind {
		GROUP(SM_QUESTION_WINDOW.STR_PARTY_DO_YOU_ACCEPT_INVITATION),
		ALLIANCE(SM_QUESTION_WINDOW.STR_FORCE_DO_YOU_ACCEPT_INVITATION),
		DUEL(SM_QUESTION_WINDOW.STR_DUEL_DO_YOU_ACCEPT_REQUEST),
		FRIEND(SM_QUESTION_WINDOW.STR_BUDDYLIST_ADD_BUDDY_REQUEST),
		TRADE(SM_QUESTION_WINDOW.STR_EXCHANGE_DO_YOU_ACCEPT_EXCHANGE);

		private final int messageId;

		Kind(int messageId) {
			this.messageId = messageId;
		}

		public int messageId() {
			return messageId;
		}
	}

	/** Who asks, as far as the answer cares: the player the bot belongs to, any other player, or another bot. */
	public enum Asker {
		OWNER, PLAYER, BOT
	}

	/**
	 * What the answer depends on.
	 *
	 * @param companion whether the bot is somebody's own rather than one of the world's inhabitants
	 * @param busy dead, or in a fight
	 * @param levelGap the asker's level minus the bot's
	 * @param duelledLately whether the bot took a duel from this asker less than {@link #DUEL_REST_MILLIS} ago
	 */
	public record Situation(Kind kind, Asker asker, boolean companion, boolean busy, boolean inInstance, int levelGap, boolean duelledLately) {
	}

	/** What the bot is, read off its ai by the caller: the decision tick knows these, the social package does not. */
	public record Self(boolean companion, Predicate<Player> isOwner, boolean fighting) {
	}

	/** When each bot last took a duel, and from whom: bot object id to the asker's object id and the time. */
	private static final Map<Integer, long[]> lastDuel = new ConcurrentHashMap<>();

	private BotRequests() {
	}

	/** @return true if the bot says yes. */
	static boolean accepts(Situation situation) {
		return switch (situation.kind()) {
			// as before this class: whoever invites a bot gets it, which is what a recruited group and a squad are made of
			case GROUP, ALLIANCE -> true;
			// the engine has already refused the other faction, a blocked player and a full list; a bot does not ask one of its own
			case FRIEND -> situation.asker() != Asker.BOT;
			case DUEL -> situation.asker() != Asker.BOT && !situation.busy() && !situation.inInstance()
				&& Math.abs(situation.levelGap()) <= DUEL_LEVEL_GAP && !situation.duelledLately();
			// a companion's bag is its owner's, and an inhabitant's is nobody's to trade into or out of
			case TRADE -> situation.companion() && situation.asker() == Asker.OWNER && !situation.busy();
		};
	}

	/**
	 * Answers every question waiting on the bot.
	 *
	 * @return The player whose duel the bot has just taken, so the caller can open the fight, or null.
	 */
	public static Player answerPending(Player bot, Self self) {
		Player duelled = null;
		for (Kind kind : Kind.values()) {
			Creature requester = bot.getResponseRequester().requesterOf(kind.messageId());
			if (requester == null)
				continue;
			// every question a player asks is filed with the player as its requester; anything else is not one this knows how to weigh
			Player asker = requester instanceof Player player ? player : null;
			boolean yes = asker != null && accepts(situationOf(bot, self, kind, asker));
			if (!bot.getResponseRequester().respond(kind.messageId(), yes ? 1 : 0))
				continue; // answered meanwhile, by a command or a cancelled request
			log.info("Bot {} {} the {} of {}", bot.getName(), yes ? "accepts" : "refuses", kind.name().toLowerCase(), requester.getName());
			if (yes && kind == Kind.DUEL) {
				lastDuel.put(bot.getObjectId(), new long[] { asker.getObjectId(), System.currentTimeMillis() });
				duelled = asker;
			}
		}
		return duelled;
	}

	private static Situation situationOf(Player bot, Self self, Kind kind, Player asker) {
		Asker who = self.isOwner().test(asker) ? Asker.OWNER : asker.isBot() ? Asker.BOT : Asker.PLAYER;
		long[] duel = lastDuel.get(bot.getObjectId());
		boolean duelledLately = duel != null && duel[0] == asker.getObjectId() && System.currentTimeMillis() - duel[1] < DUEL_REST_MILLIS;
		// keeping a store is busy too: a keeper does not leave its stall for a duel or a trade, as a player behind a counter would not
		return new Situation(kind, who, self.companion(), bot.isDead() || self.fighting() || bot.hasStore(), bot.isInInstance(),
			asker.getLevel() - bot.getLevel(), duelledLately);
	}

	/** Forgets what is kept about a bot leaving the world. */
	public static void forget(Player bot) {
		lastDuel.remove(bot.getObjectId());
	}
}
