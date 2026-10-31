package com.aionemu.gameserver.playerbot.social;

import java.util.List;
import java.util.random.RandomGenerator;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.playerbot.economy.BotStoreStock;

/**
 * The words of the bots' channel lines, kept apart from who says them and when ({@link BotChat}) so that they can be read and tested on their own.
 * <p>
 * A line is an opening, a content and a closing, each drawn from its own list, so that one kind of line has many forms: a channel that says the
 * same sentence twice reads as bots at once. The lists are in {@code bot_chat/chat_<language>.txt} ({@link ChatText}), in the language of
 * {@code gameserver.playerbot.chat.language}, with the abbreviations players type. An item is named by a link the client draws in its own
 * language ({@code [item:id]}), which is also what a player shift-clicking an item into the chat sends.
 * <p>
 * Every method has a form that takes the words of a language, which the tests use, and one that reads the setting.
 */
final class BotChatLines {

	/** One opening of a group call in so many asks the whole instance as a question ("Anyone running X?") instead. */
	private static final int ASK_ONE_IN = 7;
	/** One sale in so many with a stack states the price of one. */
	private static final int PER_UNIT_ONE_IN = 4;

	private BotChatLines() {
	}

	/** @return The words in the language the options file names, French when nothing does (a test, a server that has not read its options). */
	static ChatText text() {
		ChatLanguage language = PlayerBotConfig.CHAT_LANGUAGE;
		return ChatText.of((language == null ? ChatLanguage.FR : language).code());
	}

	/** @return A greeting for the hour of the day: morning from 5, afternoon from 12, evening from 18, night from 23. */
	static String greeting(RandomGenerator random, int hour, String map) {
		return greeting(text(), random, hour, map);
	}

	static String greeting(ChatText text, RandomGenerator random, int hour, String map) {
		String section = hour >= 5 && hour < 12 ? "greeting.morning" : hour >= 12 && hour < 18 ? "greeting.afternoon" : hour >= 18 && hour < 23 ? "greeting.evening"
			: "greeting.night";
		return pick(random, text.section(section)).formatted(map);
	}

	/** @return What the speaker is fighting, and where. */
	static String hunting(RandomGenerator random, String creature, String place) {
		return pick(random, text().section("hunting")).formatted(creature, place);
	}

	/** @return How far the speaker is from its next level, in whole percent. */
	static String progress(RandomGenerator random, int percentLeft, int nextLevel) {
		return pick(random, text().section("progress")).formatted(percentLeft, nextLevel);
	}

	/** @return A line about nothing in particular that is true anywhere: an opinion, a wish, a pause. */
	static String chatter(RandomGenerator random, String map) {
		return pick(random, text().section("chatter")).formatted(map);
	}

	static String levelQuestion(RandomGenerator random, String place) {
		return pick(random, text().section("level.question")).formatted(place);
	}

	/** @return The answer of a bot standing at the place, with the levels of what lives round it. */
	static String levelAnswer(RandomGenerator random, String place, int low, int high) {
		return pick(random, text().section("level.answer")).formatted(place, low, high);
	}

	static String whoQuestion(RandomGenerator random, String place) {
		return pick(random, text().section("who.question")).formatted(place);
	}

	/** @return The answer of a bot standing at the place. */
	static String whoAnswer(RandomGenerator random, String place) {
		return pick(random, text().section("who.answer")).formatted(place);
	}

	static String huntQuestion(RandomGenerator random, String place) {
		return pick(random, text().section("hunt.question")).formatted(place);
	}

	/** @return The answer of a bot fighting there, naming what it fights. */
	static String huntAnswer(RandomGenerator random, String creature) {
		return pick(random, text().section("hunt.answer")).formatted(creature);
	}

	/** @return A place name as players say it, from a zone's data name: {@code BASFELT_VILLAGE_220030000} is Basfelt Village. */
	static String placeName(String zoneName) {
		String[] words = zoneName.replaceFirst("_\\d+$", "").toLowerCase().split("_");
		StringBuilder name = new StringBuilder();
		for (String word : words) {
			if (!name.isEmpty())
				name.append(' ');
			name.append(word.equals("of") || word.equals("the") ? word : capitalised(word));
		}
		return name.toString();
	}

	/** @return A class as the speaker's language says it, in lower case: a French client's name for French, the data's own for English. */
	static String className(ChatText text, PlayerClass playerClass) {
		return text.language().equals("fr") ? BotStoreStock.frenchName(playerClass) : playerClass.name().toLowerCase().replace('_', ' ');
	}

	/** @return A class as the language of the options file says it, in lower case. */
	static String className(PlayerClass playerClass) {
		return className(text(), playerClass);
	}

	/**
	 * @param className The speaker's class as {@link #className} gives it.
	 * @return A line looking for a group for an instance: the class, the level and the role are the speaker's own.
	 */
	static String lookingForGroup(RandomGenerator random, String className, int level, BotRole role, String instance) {
		return lookingForGroup(text(), random, className, level, role, instance);
	}

	static String lookingForGroup(ChatText text, RandomGenerator random, String className, int level, BotRole role, String instance) {
		String who = (random.nextBoolean() ? capitalised(className) : className) + " " + level;
		String roleWord = pick(random, text.section("role." + role.name().toLowerCase()));
		String line;
		if (random.nextInt(ASK_ONE_IN) == 0) {
			line = pick(random, text.section("group.ask")).formatted(who, roleWord, instance, level);
		} else {
			String opening = pick(random, text.section("group.opening"));
			String content = pick(random, text.section("group.form")).formatted(who, roleWord, instance, level);
			line = opening.isEmpty() ? capitalised(content) : opening + " " + content;
		}
		String closing = pick(random, text.section("group.closing"));
		return closing.isEmpty() ? line : line + (random.nextBoolean() ? ", " : " ") + closing;
	}

	/**
	 * @param town Where the store stands, as the line names it.
	 * @return A line offering one line of a store: the item, how many when more than one, and the price the store asks for one.
	 */
	static String sell(RandomGenerator random, int itemId, long count, long price, String town) {
		return sell(text(), random, itemId, count, price, town);
	}

	static String sell(ChatText text, RandomGenerator random, int itemId, long count, long price, String town) {
		StringBuilder line = new StringBuilder();
		String opening = pick(random, text.section("sell.opening"));
		if (!opening.isEmpty())
			line.append(opening).append(' ');
		line.append("[item:").append(itemId).append(']');
		if (count > 1)
			line.append(random.nextBoolean() ? " x" + count : " (" + count + ")");
		String price1 = kinah(text, price);
		boolean perUnit = count > 1 && random.nextInt(PER_UNIT_ONE_IN) == 0;
		line.append(' ').append(pick(random, text.section(perUnit ? "sell.price.each" : "sell.price")).formatted(price1));
		String closing = pick(random, text.section("sell.closing"));
		if (!closing.isEmpty())
			line.append(random.nextBoolean() ? ", " : " ").append(closing.formatted(town));
		return line.toString();
	}

	/** @return A line calling out a store by the name its window shows. */
	static String advert(RandomGenerator random, String storeName, String town) {
		return pick(random, text().section("advert")).formatted(storeName, town);
	}

	/** @return The line {@code //bot chat} has a bot say to try a channel. */
	static String testLine(String channel, String speaker) {
		return text().section("test.line").get(0).formatted(channel, speaker);
	}

	/** @return A price as players write it: kinah under a thousand, then {@code 25k}, {@code 1,5M}, {@code 75M}, with the language's decimal mark. */
	static String kinah(long price) {
		return kinah(text(), price);
	}

	static String kinah(ChatText text, long price) {
		if (price < 1_000)
			return price + " " + text.section("kinah.unit").get(0);
		long thousands = Math.round(price / 1_000.0);
		if (thousands < 1_000)
			return thousands + "k";
		long tenths = Math.round(price / 100_000.0);
		return tenths % 10 == 0 ? tenths / 10 + "M" : tenths / 10 + text.section("kinah.decimal").get(0) + tenths % 10 + "M";
	}

/** What a joke says: {@code reply} is null for a line to the room, and what another bot answers a few seconds later for an exchange. */
record Banter(String first, String reply) {
}

/** In a hundred jokes, how many are an exchange between two bots. */
private static final int EXCHANGE_SHARE = 40;
/** Between the two halves of an exchange in the file. */
static final String EXCHANGE_SEPARATOR = " || ";

/** @return A joke for the room, to a bot or between two; true of the game or of playing, and never about a player. */
static Banter banter(RandomGenerator random, String map) {
	return banter(text(), random, map);
}

static Banter banter(ChatText text, RandomGenerator random, String map) {
	boolean exchange = random.nextInt(100) < EXCHANGE_SHARE;
	String line = pick(random, text.section(exchange ? "banter.exchange" : "banter.solo")).formatted(map);
	if (!exchange)
		return new Banter(line, null);
	int cut = line.indexOf(EXCHANGE_SEPARATOR);
	return new Banter(line.substring(0, cut).strip(), line.substring(cut + EXCHANGE_SEPARATOR.length()).strip());
}

static String capitalised(String word) {
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	private static String pick(RandomGenerator random, List<String> choices) {
		return choices.get(random.nextInt(choices.size()));
	}
}
