package com.aionemu.gameserver.playerbot.social;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The words of the bots' channel lines, kept apart from who says them and when ({@link BotChat}) so that they can be read and tested on their own.
 * <p>
 * A line is an opening, a content and a closing, each drawn from its own list, so that one kind of line has many forms: a channel that says the
 * same sentence twice reads as bots at once. French, with the abbreviations players type. An item is named by a link the client draws in its own
 * language ({@code [item:id]}), which is also what a player shift-clicking an item into the chat sends.
 */
final class BotChatLines {

	/** The words, in French until the language is a setting; see {@link ChatText}. */
	private static final ChatText TEXT = ChatText.of("fr");

	private static final List<String> SELL_OPENINGS = TEXT.section("sell.opening");
	private static final List<String> SELL_CLOSINGS = TEXT.section("sell.closing");
	private static final List<String> ADVERTS = TEXT.section("advert");

	private static final List<String> GROUP_OPENINGS = TEXT.section("group.opening");
	private static final List<String> GROUP_CLOSINGS = TEXT.section("group.closing");
	private static final Map<BotRole, List<String>> ROLE_WORDS = Map.of(BotRole.TANK, TEXT.section("role.tank"),
		BotRole.HEALER, TEXT.section("role.healer"), BotRole.SUPPORT, TEXT.section("role.support"),
		BotRole.DAMAGE, TEXT.section("role.damage"));

	private static final List<String> MORNING = TEXT.section("greeting.morning");
	private static final List<String> AFTERNOON = TEXT.section("greeting.afternoon");
	private static final List<String> EVENING = TEXT.section("greeting.evening");
	private static final List<String> NIGHT = TEXT.section("greeting.night");
	private static final List<String> HUNTING = TEXT.section("hunting");
	private static final List<String> PROGRESS = TEXT.section("progress");
	private static final List<String> CHATTER = TEXT.section("chatter");
	private static final List<String> LEVEL_QUESTIONS = TEXT.section("level.question");
	private static final List<String> LEVEL_ANSWERS = TEXT.section("level.answer");
	private static final List<String> WHO_QUESTIONS = TEXT.section("who.question");
	private static final List<String> WHO_ANSWERS = TEXT.section("who.answer");
	private static final List<String> HUNT_QUESTIONS = TEXT.section("hunt.question");
	private static final List<String> HUNT_ANSWERS = TEXT.section("hunt.answer");

	private BotChatLines() {
	}

	/** @return A greeting for the hour of the day: morning from 5, afternoon from 12, evening from 18, night from 23. */
	static String greeting(RandomGenerator random, int hour, String map) {
		List<String> lines = hour >= 5 && hour < 12 ? MORNING : hour >= 12 && hour < 18 ? AFTERNOON : hour >= 18 && hour < 23 ? EVENING : NIGHT;
		return pick(random, lines).formatted(map);
	}

	/** @return What the speaker is fighting, and where. */
	static String hunting(RandomGenerator random, String creature, String place) {
		return pick(random, HUNTING).formatted(creature, place);
	}

	/** @return How far the speaker is from its next level, in whole percent. */
	static String progress(RandomGenerator random, int percentLeft, int nextLevel) {
		return pick(random, PROGRESS).formatted(percentLeft, nextLevel);
	}

	/** @return A line about nothing in particular that is true anywhere: an opinion, a wish, a pause. */
	static String chatter(RandomGenerator random, String map) {
		return pick(random, CHATTER).formatted(map);
	}

	static String levelQuestion(RandomGenerator random, String place) {
		return pick(random, LEVEL_QUESTIONS).formatted(place);
	}

	/** @return The answer of a bot standing at the place, with the levels of what lives round it. */
	static String levelAnswer(RandomGenerator random, String place, int low, int high) {
		return pick(random, LEVEL_ANSWERS).formatted(place, low, high);
	}

	static String whoQuestion(RandomGenerator random, String place) {
		return pick(random, WHO_QUESTIONS).formatted(place);
	}

	/** @return The answer of a bot standing at the place. */
	static String whoAnswer(RandomGenerator random, String place) {
		return pick(random, WHO_ANSWERS).formatted(place);
	}

	static String huntQuestion(RandomGenerator random, String place) {
		return pick(random, HUNT_QUESTIONS).formatted(place);
	}

	/** @return The answer of a bot fighting there, naming what it fights. */
	static String huntAnswer(RandomGenerator random, String creature) {
		return pick(random, HUNT_ANSWERS).formatted(creature);
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

	/**
	 * @param className The speaker's class as a French client names it, in lower case.
	 * @return A line looking for a group for an instance: the class, the level and the role are the speaker's own.
	 */
	static String lookingForGroup(RandomGenerator random, String className, int level, BotRole role, String instance) {
		String who = (random.nextBoolean() ? capitalised(className) : className) + " " + level;
		String roleWord = pick(random, ROLE_WORDS.get(role));
		String opening = pick(random, GROUP_OPENINGS);
		String content = switch (random.nextInt(4)) {
			case 0 -> who + " " + roleWord + " pour " + instance;
			case 1 -> instance + ", " + who + " " + roleWord;
			case 2 -> who + " (" + roleWord + ") " + instance;
			default -> roleWord + " " + level + " pour " + instance;
		};
		String line = opening.isEmpty() ? capitalised(content) : opening.equals("Qui monte") ? "Qui monte " + instance + " ? " + who + " " + roleWord
			: opening + " " + content;
		String closing = pick(random, GROUP_CLOSINGS);
		return closing.isEmpty() ? line : line + (random.nextBoolean() ? ", " : " ") + closing;
	}

	/**
	 * @param town Where the store stands, as the line names it.
	 * @return A line offering one line of a store: the item, how many when more than one, and the price the store asks for one.
	 */
	static String sell(RandomGenerator random, int itemId, long count, long price, String town) {
		StringBuilder line = new StringBuilder();
		String opening = pick(random, SELL_OPENINGS);
		if (!opening.isEmpty())
			line.append(opening).append(' ');
		line.append("[item:").append(itemId).append(']');
		if (count > 1)
			line.append(random.nextBoolean() ? " x" + count : " (" + count + ")");
		String price1 = kinah(price);
		line.append(switch (random.nextInt(count > 1 ? 4 : 3)) {
			case 0 -> " " + price1;
			case 1 -> " à " + price1;
			case 2 -> " - " + price1;
			default -> " " + price1 + "/u";
		});
		String closing = pick(random, SELL_CLOSINGS);
		if (!closing.isEmpty())
			line.append(random.nextBoolean() ? ", " : " ").append(closing.formatted(town));
		return line.toString();
	}

	/** @return A line calling out a store by the name its window shows. */
	static String advert(RandomGenerator random, String storeName, String town) {
		return pick(random, ADVERTS).formatted(storeName, town);
	}

	/** @return A price as players write it: kinah under a thousand, then {@code 25k}, {@code 1,5M}, {@code 75M}. */
	static String kinah(long price) {
		if (price < 1_000)
			return price + " kinah";
		long thousands = Math.round(price / 1_000.0);
		if (thousands < 1_000)
			return thousands + "k";
		long tenths = Math.round(price / 100_000.0);
		return tenths % 10 == 0 ? tenths / 10 + "M" : tenths / 10 + "," + tenths % 10 + "M";
	}

	private static String capitalised(String word) {
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	private static String pick(RandomGenerator random, List<String> choices) {
		return choices.get(random.nextInt(choices.size()));
	}
}
