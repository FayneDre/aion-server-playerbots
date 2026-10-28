package com.aionemu.gameserver.playerbot.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.economy.BotStoreStock;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/**
 * What bots say aloud in the capitals: a store keeper calling out its store, a resident asking for a group. See {@code docs/capitals/history/capital-life-plan.md}.
 * <p>
 * By shout, which the players round the speaker hear; the channels are {@link BotChat}'s. Cheap and seen at once, and noisy if overdone, so a
 * capital has one line every few minutes of each kind, never from the same bot twice in half an hour, and none while no player is there to hear it.
 */
public final class BotSpeech {

	private static final Logger log = LoggerFactory.getLogger(BotSpeech.class);

	/** The capitals that are spoken in. */
	private static final List<Integer> CAPITALS = List.of(110010000, 120010000);
	/** How often the capitals are looked at, in milliseconds. */
	public static final long REVIEW_MILLIS = 5000;
	/** How long between two store adverts in a capital, in seconds, and between two calls for a group. */
	private static final int ADVERT_SHORTEST = 120, ADVERT_LONGEST = 240, CALL_SHORTEST = 180, CALL_LONGEST = 360;
	/** How long a bot that has spoken keeps quiet, in milliseconds. */
	private static final long QUIET_MILLIS = 30 * 60_000L;
	/** How near a real player a speaker must stand to be heard, in metres: a shout reaches the clients that know the speaker, about 95 m round it. */
	private static final float HEARD_WITHIN = 80;
	/** The lowest level that asks for a group: under it a character is still in the starting valleys. */
	private static final int CALL_LEVEL = 20;

	/** A store's name goes in each, as the store window shows it. */
	private static final String[] ADVERTS = { "Vends %s, passez voir ma boutique !", "%s : de bons prix, venez jeter un œil !",
		"Boutique ouverte : %s. Les invendus baissent chaque heure !", "%s, le stock vient d'être renouvelé !" };
	/** The speaker's class and level go in each. Honest: a bot accepts any player's invitation ({@link BotRequests}). */
	private static final String[] CALLS = { "%s %d cherche un groupe, invitez-moi !", "%s niveau %d dispo pour grouper, invitez-moi !",
		"Qui monte un groupe ? %s %d partant.", "%s %d cherche des compagnons, invitation bienvenue !" };

	/** Whether bots speak at all: {@code //bot speech off} silences them until the next restart or {@code on}. */
	private static volatile boolean enabled = true;
	/** When each capital may hear its next advert, and its next call for a group. */
	private static final Map<Integer, Long> nextAdvert = new ConcurrentHashMap<>(), nextCall = new ConcurrentHashMap<>();
	/** When each bot last spoke. */
	private static final Map<Integer, Long> spokeAt = new ConcurrentHashMap<>();

	private BotSpeech() {
	}

	/** Says the next line of each capital that is due one and has a player to hear it. Runs every {@link #REVIEW_MILLIS}. */
	public static void review() {
		if (!enabled)
			return;
		long now = System.currentTimeMillis();
		for (int worldId : CAPITALS) {
			List<Player> listeners = new ArrayList<>(), bots = new ArrayList<>();
			for (Player player : World.getInstance().getAllPlayers()) {
				if (player.getWorldId() == worldId && player.isSpawned())
					(player.isBot() ? bots : listeners).add(player);
			}
			if (listeners.isEmpty())
				continue;
			if (now >= nextAdvert.getOrDefault(worldId, 0L)) {
				Player keeper = speaker(bots, listeners, now, bot -> bot.getStore() != null && !bot.getStore().getStoreMessage().isEmpty());
				if (keeper != null) {
					say(keeper, String.format(ADVERTS[Rnd.get(0, ADVERTS.length - 1)], keeper.getStore().getStoreMessage()), now);
					nextAdvert.put(worldId, now + Rnd.get(ADVERT_SHORTEST, ADVERT_LONGEST) * 1000L);
				}
			}
			if (now >= nextCall.getOrDefault(worldId, 0L)) {
				Player resident = speaker(bots, listeners, now,
					bot -> bot.getStore() == null && bot.getLevel() >= CALL_LEVEL && !bot.isInTeam() && !bot.isDead());
				if (resident != null) {
					say(resident, String.format(CALLS[Rnd.get(0, CALLS.length - 1)], capitalised(BotStoreStock.frenchName(resident.getPlayerClass())),
						resident.getLevel()), now);
					nextCall.put(worldId, now + Rnd.get(CALL_SHORTEST, CALL_LONGEST) * 1000L);
				}
			}
		}
	}

	/** @return A bot of the world's own, near a real player, quiet long enough, that fits; drawn at random, or null when none does. */
	private static Player speaker(List<Player> bots, List<Player> listeners, long now, Predicate<Player> fits) {
		List<Player> candidates = new ArrayList<>();
		for (Player bot : bots) {
			if (!(bot.getAi() instanceof PlayerBotAI ai) || ai.isOwned() || now - spokeAt.getOrDefault(bot.getObjectId(), 0L) < QUIET_MILLIS
				|| !fits.test(bot))
				continue;
			if (listeners.stream().anyMatch(player -> PositionUtil.getDistance(bot, player) <= HEARD_WITHIN))
				candidates.add(bot);
		}
		return candidates.isEmpty() ? null : candidates.get(Rnd.get(0, candidates.size() - 1));
	}

	private static void say(Player bot, String line, long now) {
		spokeAt.put(bot.getObjectId(), now);
		PacketSendUtility.broadcastPacket(bot, new SM_MESSAGE(bot, line, ChatType.SHOUT), false);
		log.info("Bot {} shouts: {}", bot.getName(), line);
	}

	private static String capitalised(String word) {
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	/** @return Whether bots speak at all, by shout or in the channels ({@link BotChat}). */
	static boolean isEnabled() {
		return enabled;
	}

	/** @return What {@code //bot speech} answers, having switched speech on or off when asked to. */
	public static String command(String setting) {
		if (setting != null) {
			switch (setting.toLowerCase()) {
				case "on" -> enabled = true;
				case "off" -> enabled = false;
				default -> {
					return "Usage: //bot speech [on|off]";
				}
			}
		}
		return enabled ? "Bots speak: shouts in the capitals, and lines in the chat channels." : "Bots are silent, by shout and in the channels.";
	}
}
