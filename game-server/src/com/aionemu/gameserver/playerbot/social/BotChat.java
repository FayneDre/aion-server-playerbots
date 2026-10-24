package com.aionemu.gameserver.playerbot.social;

import java.time.LocalTime;
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
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.InstanceCooltime;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.templates.zone.ZoneClassName;
import com.aionemu.gameserver.model.templates.zone.ZoneTemplate;
import com.aionemu.gameserver.model.trade.TradePSItem;
import com.aionemu.gameserver.network.chatserver.ChatServer;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.economy.BotStoreStock;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.BotPresence;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.zone.ZoneInstance;

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
	/** How long between two Region lines on a map for a faction, in seconds: the same as the other channels, by decision. */
	private static final int REGION_SHORTEST = 20, REGION_LONGEST = 40;
	/** How long one subject is not taken up again on a map: a greeting, a place asked about. */
	private static final long SUBJECT_REPEAT_MILLIS = 10 * 60_000L;
	/** How long a bot takes to answer a question in Region, in seconds. */
	private static final int ANSWER_SHORTEST = 4, ANSWER_LONGEST = 15;
	/** The kinds of Region line, by weight out of a hundred: a greeting, what the speaker hunts, its progress, a question answered; chatter the rest. */
	private static final int GREETING = 15, HUNTING = 25, PROGRESS = 15, QUESTION = 30;
	/** How long between two Find Group lines for a faction, in seconds. */
	private static final int GROUP_SHORTEST = 20, GROUP_LONGEST = 40;
	/**
	 * How long one instance is not called for again by a faction, in milliseconds. Shorter than the other repeats: there are some forty instances, a
	 * level suits a handful, and at a line every half minute thirty minutes left most turns with nothing to say. Several players asking for the same
	 * dungeon in a row is what a Find Group channel looks like anyway.
	 */
	private static final long DUNGEON_REPEAT_MILLIS = 10 * 60_000L;
	/** The lowest level that looks for a group: under it a character is still in the starting valleys. */
	private static final int GROUP_LEVEL = 20;
	/** How far over an instance's entry level a speaker may be, where the data gives no top: the band its monsters are of. */
	private static final int INSTANCE_BAND = 10;
	/** Six-member instances that are no PvE dungeon: tests, arenas, warfronts, legion barracks, and maps the data leaves unnamed. */
	private static final Pattern NOT_A_DUNGEON = Pattern.compile("(?i).*(test|tag match|arena|training grounds|dredgion|legion's|idgel dome|ophidan bridge).*");
	/** The first word of a zone's working name, which carries a digit ({@code DF1A}): a place players name never does. */
	private static final Pattern WORKING_NAME = Pattern.compile(".*\\d.*");
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
	/** When each map and faction may read its next Region line, by {@link #key}. */
	private static final Map<String, Long> nextRegion = new ConcurrentHashMap<>();
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
	 * Says the next Find Group line of each faction, and the next Region and Trade lines of each map and faction, that are due one and have a player
	 * of that faction to read them. Runs every {@link #REVIEW_MILLIS}.
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
		Map<String, List<Player>> locals = new HashMap<>();
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
			if (player.isBot() && isLocal(player, now))
				locals.computeIfAbsent(key, k -> new ArrayList<>()).add(player);
		}
		for (Map.Entry<String, List<Player>> entry : locals.entrySet()) {
			String key = entry.getKey();
			if (read.containsKey(key) && now >= nextRegion.getOrDefault(key, 0L) && regionLine(entry.getValue(), key, now))
				nextRegion.put(key, now + ThreadLocalRandom.current().nextInt(REGION_SHORTEST, REGION_LONGEST + 1) * 1000L);
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

	/** @return true for a bot of the map's own that may speak in Region: alive, quiet long enough, and no player's companion. */
	private static boolean isLocal(Player bot, long now) {
		return bot.getAi() instanceof PlayerBotAI ai && !ai.isOwned() && !bot.isDead() && now - spokeAt.getOrDefault(bot.getObjectId(), 0L) >= QUIET_MILLIS;
	}

	/**
	 * Has one of the map's bots say something in Region: a greeting for the hour, what it is fighting and where, how far it is from its next level, a
	 * question another bot standing at the place answers a few seconds later, or chatter that is true anywhere. A kind with nobody to say it truly
	 * gives way to chatter.
	 *
	 * @return true, a line having been said.
	 */
	private static boolean regionLine(List<Player> bots, String key, long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String map = DataManager.WORLD_MAPS_DATA.getTemplate(bots.getFirst().getWorldId()).getName();
		int roll = random.nextInt(100);
		if (roll < GREETING) {
			if (fresh(key + "/greeting", now))
				return speak(bots.get(random.nextInt(bots.size())), BotChatLines.greeting(random, LocalTime.now().getHour(), map), now);
		} else if (roll < GREETING + HUNTING) {
			List<Player> hunters = bots.stream().filter(bot -> preyOf(bot) != null && placeOf(bot) != null).toList();
			if (!hunters.isEmpty()) {
				Player hunter = hunters.get(random.nextInt(hunters.size()));
				return speak(hunter, BotChatLines.hunting(random, preyOf(hunter).getName(), placeOf(hunter)), now);
			}
		} else if (roll < GREETING + HUNTING + PROGRESS) {
			List<Player> climbers = bots.stream().filter(bot -> bot.getLevel() < 65 && bot.getCommonData().getExpNeed() > 0).toList();
			if (!climbers.isEmpty()) {
				Player climber = climbers.get(random.nextInt(climbers.size()));
				long need = climber.getCommonData().getExpNeed(), shown = climber.getCommonData().getExpShown();
				int left = (int) Math.max(1, Math.min(99, 100 - shown * 100 / need));
				return speak(climber, BotChatLines.progress(random, left, climber.getLevel() + 1), now);
			}
		} else if (roll < GREETING + HUNTING + PROGRESS + QUESTION) {
			if (askAndAnswer(bots, key, now))
				return true;
		}
		return speak(bots.get(random.nextInt(bots.size())), BotChatLines.chatter(random, map), now);
	}

	/**
	 * Has one bot ask about a place and another, standing there, answer a few seconds later: the level of the ground it stands on, that it is there,
	 * or what it fights. The answer is only ever about where the answerer stands, so it is true when said.
	 *
	 * @return true if a question was asked.
	 */
	private static boolean askAndAnswer(List<Player> bots, String key, long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		List<Player> placed = bots.stream().filter(bot -> placeOf(bot) != null).toList();
		if (placed.isEmpty())
			return false;
		Player answerer = placed.get(random.nextInt(placed.size()));
		String place = placeOf(answerer);
		List<Player> askers = bots.stream().filter(bot -> bot != answerer && !place.equals(placeOf(bot))).toList();
		if (askers.isEmpty() || !fresh(key + "/place " + place, now))
			return false;
		Player asker = askers.get(random.nextInt(askers.size()));
		Npc prey = preyOf(answerer);
		String question, answer;
		int kind = random.nextInt(3);
		if (kind == 0 && !BotPresence.isTown(answerer.getWorldId())) {
			int level = BotPlaces.levelAround(answerer.getWorldId(), answerer.getX(), answerer.getY());
			question = BotChatLines.levelQuestion(random, place);
			answer = BotChatLines.levelAnswer(random, place, Math.max(1, level - 1), level + 2);
		} else if (kind == 1 && prey != null) {
			question = BotChatLines.huntQuestion(random, place);
			answer = BotChatLines.huntAnswer(random, prey.getName());
		} else {
			question = BotChatLines.whoQuestion(random, place);
			answer = BotChatLines.whoAnswer(random, place);
		}
		speak(asker, question, now);
		spokeAt.put(answerer.getObjectId(), now);
		ThreadPoolManager.getInstance().schedule(() -> {
			// still there to say it: an answer about a place the answerer has left would no longer be true
			if (answerer.isSpawned() && !answerer.isDead() && place.equals(placeOf(answerer))) {
				say(answerer, Channel.REGION, answer);
				log.info("Bot {} says in region: {}", answerer.getName(), answer);
			}
		}, random.nextInt(ANSWER_SHORTEST, ANSWER_LONGEST + 1) * 1000L);
		return true;
	}

	/** Says the line in Region and keeps the speaker quiet for a while. */
	private static boolean speak(Player bot, String line, long now) {
		spokeAt.put(bot.getObjectId(), now);
		say(bot, Channel.REGION, line);
		log.info("Bot {} says in region: {}", bot.getName(), line);
		return true;
	}

	/** @return true, and the subject marked as taken, if it was not taken up on this map lately. */
	private static boolean fresh(String subject, long now) {
		if (now - offeredAt.getOrDefault(subject, 0L) < SUBJECT_REPEAT_MILLIS)
			return false;
		offeredAt.put(subject, now);
		return true;
	}

	/** @return The monster the bot is fighting, or null. */
	private static Npc preyOf(Player bot) {
		return bot.getTarget() instanceof Npc npc && !npc.isDead() && bot.isEnemy(npc) && npc.getName() != null && !npc.getName().isBlank() ? npc : null;
	}

	/**
	 * @return The named place the bot stands in, as players say it ({@code Basfelt Village}), or null outside one. The data's places are its
	 *         {@code SUB} zones; those whose first word carries a digit ({@code DF1A_FZ_TOWN3}) are working names, not places.
	 */
	static String placeOf(Player bot) {
		for (ZoneInstance zone : bot.findZones()) {
			ZoneTemplate template = zone.getZoneTemplate();
			if (template.getZoneType() != ZoneClassName.SUB || template.getName() == null)
				continue;
			String name = template.getName().name();
			if (!WORKING_NAME.matcher(name.split("_")[0]).matches())
				return BotChatLines.placeName(name);
		}
		return null;
	}

	/** Has one of the seekers look for a group for an instance its level suits and that was not asked for lately. */
	private static void lookForGroup(List<Player> seekers, Race race, long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Player seeker = seekers.get(random.nextInt(seekers.size()));
		List<Dungeon> suited = dungeons().stream()
			.filter(dungeon -> dungeon.suits(seeker) && now - offeredAt.getOrDefault(race + "/dungeon " + dungeon.worldId(), 0L) >= DUNGEON_REPEAT_MILLIS)
			.toList();
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
