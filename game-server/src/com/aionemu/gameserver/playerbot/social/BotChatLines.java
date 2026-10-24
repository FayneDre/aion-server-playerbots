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

	private static final List<String> MORNING = List.of("Bonjour tout le monde", "bonjour %s", "Salut %s, bonne journée à tous", "Bon matin à tous",
		"'jour", "Bonjour, bon jeu à tous");
	private static final List<String> AFTERNOON = List.of("Salut %s", "salut tout le monde", "Bon aprem à tous", "Yo %s", "Coucou", "slt");
	private static final List<String> EVENING = List.of("Bonsoir %s", "bonsoir tout le monde", "Bonne soirée à tous", "bsr", "Salut les gens, bonne soirée",
		"Bonsoir, bon jeu");
	private static final List<String> NIGHT = List.of("Encore du monde debout sur %s ?", "Bonne nuit à ceux qui vont dormir", "Les couche-tard de %s, salut",
		"Calme cette nuit sur %s", "Allez une dernière heure et dodo");
	private static final List<String> HUNTING = List.of("Je farm les %s vers %s", "Ça respawn bien les %s vers %s", "Les %s de %s tapent fort",
		"Gros farm de %s vers %s", "Encore des %s vers %s...", "Qui veut m'aider avec les %s vers %s ?");
	private static final List<String> PROGRESS = List.of("Plus que %d%% avant le %d", "%d%% pour monter %d, ça vient", "Encore %d%% et je passe %d !",
		"Allez, %d%% avant le niveau %d");
	private static final List<String> CHATTER = List.of("Bonne chasse à tous", "Bon courage à ceux qui farment", "J'aime bien %s",
		"Bon jeu à tous sur %s", "Ça farm dur sur %s", "J'adore cette zone", "Quelqu'un a de la musique à conseiller ?",
		"Pause café et je reviens", "Le respawn est long par ici", "gg à ceux qui ont up aujourd'hui");
	private static final List<String> LEVEL_QUESTIONS = List.of("C'est quel niveau vers %s ?", "Les mobs de %s sont de quel lvl ?", "qqn sait le lvl vers %s ?",
		"%s c'est pour quel niveau ?");
	private static final List<String> LEVEL_ANSWERS = List.of("Vers %s c'est du %d-%d", "%2$d-%3$d vers %1$s", "du %2$d-%3$d", "j'y suis, c'est du %2$d-%3$d",
		"%s ? lvl %d à %d");
	private static final List<String> WHO_QUESTIONS = List.of("Qqn vers %s ?", "Il y a du monde vers %s ?", "quelqu'un à %s ?", "Qui est vers %s ?");
	private static final List<String> WHO_ANSWERS = List.of("Moi, je suis vers %s", "Oui, j'y suis", "ouais moi", "Je suis à %s", "présent vers %s");
	private static final List<String> HUNT_QUESTIONS = List.of("Ça farm quoi vers %s ?", "Il y a quoi à taper vers %s ?", "qqn sait ce qu'il y a vers %s ?");
	private static final List<String> HUNT_ANSWERS = List.of("Des %s", "des %s surtout", "Je tape des %s là", "Plein de %s", "%s, pas mal d'xp");

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
