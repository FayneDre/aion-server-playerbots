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

	private static final List<String> SELL_OPENINGS = List.of("WTS", "wts", "Vends", "vends", "VDS", "Je vends", "À vendre :", "Dispo :", "");
	private static final List<String> SELL_CLOSINGS = List.of("", "", "mp", "MP", "pst", "mp moi", "passez à ma boutique", "en boutique à %s",
		"stand ouvert à %s", "dispo maintenant", "venez voir", "prix négociable en boutique", "premier arrivé premier servi");
	private static final List<String> ADVERTS = List.of("Boutique « %s » ouverte à %s, venez voir !", "%s : stand ouvert à %s, passez !",
		"Ouvert à %2$s : %1$s", "Boutique %s, les prix baissent chaque heure, à %s", "Stock renouvelé : %s, à %s", "Passez voir %s à %s, mp si besoin");

	private static final List<String> GROUP_OPENINGS = List.of("LFG", "lfg", "Cherche groupe", "cherche grp", "Dispo pour", "Qui monte", "");
	private static final List<String> GROUP_CLOSINGS = List.of("", "", "inv svp", "mp", "invitez-moi", "dispo de suite", "pst", "dispo toute la soirée",
		"j'connais le donjon");
	private static final Map<BotRole, List<String>> ROLE_WORDS = Map.of(BotRole.TANK, List.of("tank", "tank", "MT"),
		BotRole.HEALER, List.of("heal", "soigneur", "healer"), BotRole.SUPPORT, List.of("support", "buff", "soutien"),
		BotRole.DAMAGE, List.of("dps", "DD", "dégâts"));

	private BotChatLines() {
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
