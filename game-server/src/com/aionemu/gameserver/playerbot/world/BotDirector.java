package com.aionemu.gameserver.playerbot.world;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.world.World;

/**
 * Keeps each region holding the people it should, which is more of them where somebody is playing.
 * <p>
 * Until now the population was decided once, by {@code //bot populate}, and never looked at again: a region held whatever had been created on it,
 * whether a player was there or not. That is enough to make a world that <i>contains</i> people and not enough to make one that feels inhabited,
 * because the countryside at its quiet density is thin by design — Poeta's ten occupied grounds are spread over 288000 square metres.
 * <p>
 * The director closes that loop. It reviews what each region holds against what {@link BotPresence#wanted} says it should, and the difference is the
 * work to do. A player on a map raises the countryside's density, so arriving somewhere makes it busier — which is the one change that alters what
 * the world feels like rather than what it contains.
 * <p>
 * <b>It currently only looks.</b> Acting on the difference means drawing bots from a pool of offline characters and putting them back, and the part
 * that has to be right before any of it runs is that nobody ever sees it happen: a character that materialises in front of a player is worse than an
 * empty field. Reporting the difference first is what lets the numbers be judged before anything moves.
 */
public class BotDirector {

	private static final Logger log = LoggerFactory.getLogger(BotDirector.class);

	/**
	 * How often the world is reviewed. Long on purpose: a region's population is not a thing that should twitch, and the surge only has to be in
	 * place by the time a player has walked somewhere, not by the time they have turned round.
	 */
	private static final long REVIEW_INTERVAL_MILLIS = 30000;

	private static final BotDirector INSTANCE = new BotDirector();

	private BotDirector() {
	}

	public static BotDirector getInstance() {
		return INSTANCE;
	}

	public void start() {
		BotScheduler.getInstance().scheduleAtFixedRate(this::review, REVIEW_INTERVAL_MILLIS, REVIEW_INTERVAL_MILLIS);
	}

	/**
	 * Counts what each region holds and says what it should hold.
	 * <p>
	 * Only regions that hold somebody are reviewed — a map with neither a bot nor a player on it is not a map anybody is experiencing, and reviewing
	 * every world every half minute to say nothing about most of them is noise in the log and work in the pool.
	 */
	private void review() {
		Map<Integer, Census> byMap = new HashMap<>();
		for (Player everyone : World.getInstance().getAllPlayers())
			byMap.computeIfAbsent(everyone.getWorldId(), id -> new Census()).add(everyone);

		for (Map.Entry<Integer, Census> entry : byMap.entrySet()) {
			int worldId = entry.getKey();
			Census census = entry.getValue();
			// A bot somebody owns is its owner's business and not a resident of anywhere, so it neither counts towards what the region holds nor
			// raises its density. Only a real player does the second, which is what "where somebody is playing" means.
			boolean busy = census.players > 0;
			int wanted = BotPresence.wanted(worldId, busy).size();
			int quiet = BotPresence.establishment(worldId).size();
			if (census.residents == wanted && !busy)
				continue; // the region is as it should be and nobody is there to notice either way
			log.info("Map {} holds {} resident(s) and {} player(s), wants {}{}", worldId, census.residents, census.players, wanted,
				busy ? " (" + quiet + " when quiet, " + signed(wanted - quiet) + " for the player)" : "");
		}
	}

	private static String signed(int difference) {
		return difference > 0 ? "+" + difference : String.valueOf(difference);
	}

	/** What a map holds: the world's own inhabitants, the real players, and the companions that are neither. */
	private static final class Census {

		private int residents;
		private int players;

		private void add(Player someone) {
			if (!someone.isBot())
				players++;
			else if (!(someone.getAi() instanceof PlayerBotAI ai) || !ai.isOwned())
				residents++;
		}
	}
}
