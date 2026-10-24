package com.aionemu.gameserver.playerbot.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.InstanceCooltime;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.trade.TradePSItem;
import com.aionemu.gameserver.network.chatserver.ChatServer;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.economy.BotStoreStock;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/**
 * What bots say in the chat channels: Region, Trade and Find Group. See docs/chat-channels-plan.md.
 * <p>
 * A bot has no client and so no chat session: the chat server says its line for it ({@code SM_CS_BOT_MESSAGE}), in the channel of the bot's own race,
 * which a player of the other race never reads.
 */
public final class BotChat {

	/** The channels a bot speaks in, by the type the client names them with. */
	public enum Channel {
		REGION("public"), TRADE("trade"), FIND_GROUP("partyFind");

		private final String type;

		Channel(String type) {
			this.type = type;
		}
	}

	private static final Logger log = LoggerFactory.getLogger(BotChat.class);

	/** What a Find Group request carries in place of a map: the channel is one a race, whatever this says. */
	private static final String FIND_GROUP_META = "PF";
	/** How often the channels are looked at, in milliseconds. */
	public static final long REVIEW_MILLIS = 5000;
	/** How long between two Trade lines on a map for a faction, in seconds: about what forty keepers quiet for twenty minutes can keep up. */
	private static final int TRADE_SHORTEST = 20, TRADE_LONGEST = 40;
	/** How long a bot that has spoken in a channel keeps quiet, and how long one thing is not offered again on a map, in milliseconds. */
	private static final long QUIET_MILLIS = 20 * 60_000L, REPEAT_MILLIS = 30 * 60_000L;
	/** How long between two Find Group lines for a faction, in seconds. */
	private static final int GROUP_SHORTEST = 60, GROUP_LONGEST = 120;
	/** The lowest level that looks for a group: under it a character is still in the starting valleys. */
	private static final int GROUP_LEVEL = 20;
	/** How far over an instance's entry level a speaker may be, where the data gives no top: the band its monsters are of. */
	private static final int INSTANCE_BAND = 10;
	/** Six-member instances that are no PvE dungeon: tests, arenas, warfronts, legion barracks, and maps the data leaves unnamed. */
	private static final Pattern NOT_A_DUNGEON = Pattern.compile("(?i).*(test|tag match|arena|training grounds|dredgion|legion's|idgel dome|ophidan bridge).*");
	/** Of a hundred Trade lines, how many offer one piece of a store; the rest call out the store itself. */
	private static final int SELL_SHARE = 70;

	/**
	 * A PvE instance for a group of six, and the levels whose characters it suits.
	 *
	 * @param side The faction that can enter it, or {@code PC_ALL} for both.
	 */
	record Dungeon(int worldId, String name, int minLevel, int maxLevel, Race side) {

		boolean suits(Player bot) {
			return (side == Race.PC_ALL || side == bot.getRace()) && bot.getLevel() >= minLevel && bot.getLevel() <= maxLevel;
		}
	}

	/** Every PvE group instance of the data, read on first use. */
	private static volatile List<Dungeon> dungeons;
	/** When each faction may read its next Find Group line. */
	private static final Map<Race, Long> nextGroup = new ConcurrentHashMap<>();
	/** When each map and faction may read its next Trade line, by {@link #key}. */
	private static final Map<String, Long> nextTrade = new ConcurrentHashMap<>();
	/** When each bot last spoke in a channel. */
	private static final Map<Integer, Long> spokeAt = new ConcurrentHashMap<>();
	/** When each thing was last offered on a map for a faction: an item template, or a store by its keeper, after {@link #key}. */
	private static final Map<String, Long> offeredAt = new ConcurrentHashMap<>();

	private BotChat() {
	}

	/**
	 * @return The identifier a client in the bot's place would ask the channel for: for Region and Trade the map's client name, {@code DC1} for
	 *         Pandaemonium, as read in game on 24 October. The digit the log shows after it, {@code @public_DC11.1.AION.KOR}, is the game server's id
	 *         behind an invisible separator, not a channel number: {@code @job_Assassin1.1.AION.KOR} has it too.
	 */
	static String metaOf(Channel channel, Player bot) {
		if (channel == Channel.FIND_GROUP)
			return FIND_GROUP_META;
		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(bot.getWorldId());
		return map.getCName();
	}

	/**
	 * Has the bot say the line in the channel of its race. Nothing happens when the chat server is down or nobody reads the channel.
	 *
	 * @return false when the chat server is not connected.
	 */
	static boolean say(Player bot, Channel channel, String text) {
		if (!ChatServer.getInstance().isUp())
			return false;
		ChatServer.getInstance().sendBotMessage(channel.type, bot.getRace().getRaceId(), metaOf(channel, bot), bot.getObjectId(), bot.getName(), text);
		return true;
	}

	/**
	 * Says the next Find Group line of each faction and the next Trade line of each map and faction that are due one and have a player of that faction
	 * to read them. Runs every {@link #REVIEW_MILLIS}.
	 * <p>
	 * Every line is true of its speaker: a bot with its store open names a piece on its own shelf at the price its store asks now, or the store
	 * itself, read from the store at the moment it is said, so a piece sold a minute ago is not offered.
	 */
	public static void review() {
		if (!BotSpeech.isEnabled() || !ChatServer.getInstance().isUp())
			return;
		long now = System.currentTimeMillis();
		offeredAt.values().removeIf(at -> now - at >= REPEAT_MILLIS);
		Map<String, List<Player>> keepers = new HashMap<>();
		Map<String, Boolean> read = new HashMap<>();
		Map<Race, List<Player>> seekers = new HashMap<>();
		for (Player player : World.getInstance().getAllPlayers()) {
			if (!player.isSpawned())
				continue;
			String key = key(player.getWorldId(), player.getRace());
			if (!player.isBot()) {
				read.put(key, true);
				read.put(player.getRace().name(), true);
			} else if (isKeeper(player, now)) {
				keepers.computeIfAbsent(key, k -> new ArrayList<>()).add(player);
			} else if (isSeeker(player, now)) {
				seekers.computeIfAbsent(player.getRace(), k -> new ArrayList<>()).add(player);
			}
		}
		for (Map.Entry<Race, List<Player>> entry : seekers.entrySet()) {
			Race race = entry.getKey();
			if (read.containsKey(race.name()) && now >= nextGroup.getOrDefault(race, 0L))
				lookForGroup(entry.getValue(), race, now);
		}
		for (Map.Entry<String, List<Player>> entry : keepers.entrySet()) {
			String key = entry.getKey();
			if (!read.containsKey(key) || now < nextTrade.getOrDefault(key, 0L))
				continue;
			List<Player> speakers = entry.getValue();
			Player keeper = speakers.get(ThreadLocalRandom.current().nextInt(speakers.size()));
			String line = tradeLine(keeper, key, now);
			if (line == null)
				continue;
			spokeAt.put(keeper.getObjectId(), now);
			nextTrade.put(key, now + ThreadLocalRandom.current().nextInt(TRADE_SHORTEST, TRADE_LONGEST + 1) * 1000L);
			say(keeper, Channel.TRADE, line);
			log.info("Bot {} says in trade: {}", keeper.getName(), line);
		}
	}

	/** Has one of the seekers look for a group for an instance its level suits and that was not asked for lately. */
	private static void lookForGroup(List<Player> seekers, Race race, long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Player seeker = seekers.get(random.nextInt(seekers.size()));
		List<Dungeon> suited = dungeons().stream()
			.filter(dungeon -> dungeon.suits(seeker) && now - offeredAt.getOrDefault(race + "/dungeon " + dungeon.worldId(), 0L) >= REPEAT_MILLIS).toList();
		if (suited.isEmpty())
			return;
		Dungeon dungeon = suited.get(random.nextInt(suited.size()));
		String line = BotChatLines.lookingForGroup(random, BotStoreStock.frenchName(seeker.getPlayerClass()), seeker.getLevel(), BotRole.of(seeker),
			dungeon.name());
		offeredAt.put(race + "/dungeon " + dungeon.worldId(), now);
		spokeAt.put(seeker.getObjectId(), now);
		nextGroup.put(race, now + random.nextInt(GROUP_SHORTEST, GROUP_LONGEST + 1) * 1000L);
		say(seeker, Channel.FIND_GROUP, line);
		log.info("Bot {} says in find group: {}", seeker.getName(), line);
	}

	/** @return true for a free bot of a level to look for a group, out of any group, store and instance, quiet long enough. */
	private static boolean isSeeker(Player bot, long now) {
		return bot.getAi() instanceof PlayerBotAI ai && !ai.isOwned() && bot.getLevel() >= GROUP_LEVEL && !bot.isInTeam() && bot.getStore() == null
			&& !bot.isDead() && !bot.isInInstance() && now - spokeAt.getOrDefault(bot.getObjectId(), 0L) >= QUIET_MILLIS;
	}

	/** @return Every PvE instance for a group of six, with the levels it suits: its entry level up to its entry top, or ten over the entry. */
	static List<Dungeon> dungeons() {
		List<Dungeon> found = dungeons;
		if (found == null) {
			List<Dungeon> read = new ArrayList<>();
			for (InstanceCooltime entry : DataManager.INSTANCE_COOLTIME_DATA.getInstanceCooltimes().values()) {
				WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(entry.getWorldId());
				if (map == null || map.getName() == null || map.getName().isBlank() || NOT_A_DUNGEON.matcher(map.getName()).matches())
					continue;
				Race side = sideOf(entry.getWorldId());
				boolean elyos = side != Race.ASMODIANS;
				int members = elyos ? entry.getMaxMemberLight() : entry.getMaxMemberDark();
				int min = elyos ? entry.getEnterMinLevelLight() : entry.getEnterMinLevelDark();
				int max = elyos ? entry.getEnterMaxLevelLight() : entry.getEnterMaxLevelDark();
				if (members == 6 && min > 1)
					read.add(new Dungeon(entry.getWorldId(), map.getName(), min, max > 0 ? max : min + INSTANCE_BAND, side));
			}
			dungeons = found = List.copyOf(read);
		}
		return found;
	}

	/**
	 * @return The faction an instance belongs to, by its id: the 31 maps are the Elyos' own instances, the 32 the Asmodians' (Indratu Fortress and
	 *         Draupnir Cave, Theobomos Lab and Adma Stronghold), the rest are shared. Nothing else in the data says so.
	 */
	static Race sideOf(int worldId) {
		return switch (worldId / 10_000_000) {
			case 31 -> Race.ELYOS;
			case 32 -> Race.ASMODIANS;
			default -> Race.PC_ALL;
		};
	}

	/** @return true for a bot keeping its store open now, quiet long enough: a player's own companion never speaks for itself. */
	private static boolean isKeeper(Player bot, long now) {
		return bot.getAi() instanceof PlayerBotAI ai && !ai.isOwned() && bot.getStore() != null && !bot.getStore().getSoldItems().isEmpty()
			&& now - spokeAt.getOrDefault(bot.getObjectId(), 0L) >= QUIET_MILLIS;
	}

	/** @return A line about the keeper's store: one of its pieces not offered on this map lately, or the store itself; null when both were. */
	private static String tradeLine(Player keeper, String key, long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String town = DataManager.WORLD_MAPS_DATA.getTemplate(keeper.getWorldId()).getName();
		if (random.nextInt(100) < SELL_SHARE) {
			List<TradePSItem> fresh = keeper.getStore().getSoldItems().values().stream()
				.filter(item -> now - offeredAt.getOrDefault(key + "/item " + item.getItemId(), 0L) >= REPEAT_MILLIS).toList();
			if (!fresh.isEmpty()) {
				TradePSItem item = fresh.get(random.nextInt(fresh.size()));
				offeredAt.put(key + "/item " + item.getItemId(), now);
				return BotChatLines.sell(random, item.getItemId(), item.getCount(), item.getPrice(), town);
			}
		}
		String storeKey = key + "/store " + keeper.getObjectId();
		if (now - offeredAt.getOrDefault(storeKey, 0L) < REPEAT_MILLIS)
			return null;
		offeredAt.put(storeKey, now);
		return BotChatLines.advert(random, keeper.getStore().getStoreMessage(), town);
	}

	private static String key(int worldId, Race race) {
		return worldId + "/" + race;
	}

	/**
	 * @return What {@code //bot chat} answers, having had a bot say a line in the channel: the caller's target if it is a bot of the caller's race on
	 *         its map, the nearest such bot otherwise.
	 */
	public static String command(Player admin, String channelName, String text) {
		Channel channel = switch (channelName == null ? "" : channelName.toLowerCase()) {
			case "region" -> Channel.REGION;
			case "trade" -> Channel.TRADE;
			case "group" -> Channel.FIND_GROUP;
			default -> null;
		};
		if (channel == null)
			return "Usage: //bot chat <region|trade|group> [text]";
		Predicate<Player> maySpeak = player -> player.isBot() && player.isSpawned() && player.getRace() == admin.getRace()
			&& player.getWorldId() == admin.getWorldId();
		Player speaker = admin.getTarget() instanceof Player target && maySpeak.test(target) ? target
			: World.getInstance().getAllPlayers().stream().filter(maySpeak).min(Comparator.comparingDouble(player -> PositionUtil.getDistance(player, admin)))
				.orElse(null);
		if (speaker == null)
			return "No bot of your faction on this map.";
		String line = text != null ? text : "Test du canal " + channelName.toLowerCase() + ", " + speaker.getName() + " au rapport.";
		if (!say(speaker, channel, line))
			return "The chat server is not connected: set gameserver.chatserver.enable and the chat password.";
		return speaker.getName() + " says in " + channelName.toLowerCase() + " (" + metaOf(channel, speaker) + "): " + line;
	}
}
