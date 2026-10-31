package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.services.item.ItemService;

/**
 * What a bot's store holds: a theme of its keeper's, and stock generated at the keeper's level into its bag.
 * <p>
 * Generated, by decision of 20 October: a town resident does not hunt and a field bot sells its drops where it hunts, so nothing a bot really loots
 * reaches a capital. Only what a player could come by goes on a shelf — reachable, of the keeper's faction, tradeable — so a store never shows what the
 * world itself does not hand out. See docs/capitals/history/bot-stores-plan.md and docs/capitals/history/bot-store-goods-plan.md.
 * <p>
 * Two kinds of keeper, as a price guide described live servers on 24 October: the <b>dedicated</b> one sells one category over a wide spread of
 * levels, the <b>opportunist</b> a little of everything round its own level, as a player sells what a session left in the bag.
 */
public final class BotStoreStock {

	/** What a store sells, which also gives it its name. */
	public enum Theme {
		GEAR, ACCESSORIES,
		/**
		 * Manastones and the upgrade items of {@link BotStoreGoods#UPGRADES}. One theme rather than two: a store of enchantment stones alone held one
		 * line, which is what two such stores showed on 20 October.
		 */
		STONES,
		/** Coins and medals, a theme of its own by the guide's wish (24 October), stocked by the keeper level each asks for. */
		COINS,
		/** Cooked food, the stat boosts a player eats before a hunt: the tester of 29 October held it more important than gear. */
		FOOD,
		/** The scrolls of speed (attack, running, flight, casting), the five minute ones, the hour ones and the thirty minute scrolls of the events. */
		SCROLLS,
		/** The opportunist: a few lines of each of the others, round its own level. */
		MIXED
	}

	/**
	 * How the keepers are shared out among the themes, in sixtieths (31 October, the tester's wish and his answer on the shares): food a fifth, scrolls, stones and opportunists a sixth each,
	 * and coins, gear and jewels a tenth each. Read off a hash of the keeper's id, the same every day.
	 */
	private static final Theme[] SHARES = shares(Map.of(Theme.FOOD, 12, Theme.SCROLLS, 10, Theme.STONES, 10, Theme.MIXED, 10, Theme.COINS, 6, Theme.GEAR, 6, Theme.ACCESSORIES, 6));
	/** How an opportunist's lines are shared out, out of twelve: stones, food and scrolls most, then coins and jewels, and one piece of gear. */
	private static final Map<Theme, Integer> MIXED_WEIGHTS = Map.of(Theme.STONES, 3, Theme.COINS, 2, Theme.ACCESSORIES, 2, Theme.GEAR, 1, Theme.FOOD, 2, Theme.SCROLLS, 2);
	/** A keeper under this level sells no stones unless it is one of the wide ones: the stones of 60 and 70 are all a store of stones sells, and it cannot stock them. */
	private static final int STONES_FROM_LEVEL = 50;
	/** The stones a store of stones sells when it is not a wide one: levels 60 and 70 only. */
	private static final int HIGH_STONES_FROM = 60;
	/** One stones store in so many is wide, selling stones of every level from 10 to its keeper's plus ten (the tester asked for one or two a market). */
	private static final int WIDE_ONE_IN = 4;
	/** How many lines of the event scrolls a store holds at most. */
	private static final int MOST_EVENT_LINES = 2;
	/** How far under the keeper's level the food a dedicated food store sells goes, and how far over. */
	private static final int FOOD_UNDER = 25, FOOD_OVER = 5;

	private static Theme[] shares(Map<Theme, Integer> weights) {
		List<Theme> list = new ArrayList<>();
		for (Theme theme : List.of(Theme.FOOD, Theme.SCROLLS, Theme.STONES, Theme.MIXED, Theme.COINS, Theme.GEAR, Theme.ACCESSORIES)) {
			for (int i = 0; i < weights.get(theme); i++)
				list.add(theme);
		}
		return list.toArray(Theme[]::new);
	}

	/** The classes a gear store is stocked for, with the name a French client shows for each. */
	private static final Map<PlayerClass, String> CLASS_NAMES = Map.ofEntries(Map.entry(PlayerClass.GLADIATOR, "gladiateur"),
		Map.entry(PlayerClass.TEMPLAR, "templier"), Map.entry(PlayerClass.ASSASSIN, "assassin"), Map.entry(PlayerClass.RANGER, "rôdeur"),
		Map.entry(PlayerClass.SORCERER, "sorcier"), Map.entry(PlayerClass.SPIRIT_MASTER, "spiritualiste"), Map.entry(PlayerClass.CLERIC, "clerc"),
		Map.entry(PlayerClass.CHANTER, "aède"), Map.entry(PlayerClass.GUNNER, "pistolero"), Map.entry(PlayerClass.BARD, "barde"),
		Map.entry(PlayerClass.RIDER, "éthertech"));
	private static final List<PlayerClass> STOCKED_CLASSES = List.copyOf(new java.util.TreeSet<>(CLASS_NAMES.keySet()));

	/** @return The class's name as a French client shows it, for what a bot says; the enum's own name for a starting class, which no store sells for. */
	public static String frenchName(PlayerClass playerClass) {
		return CLASS_NAMES.getOrDefault(playerClass, playerClass.name().toLowerCase());
	}
	private static final Set<ItemGroup> ACCESSORY_GROUPS = Set.of(ItemGroup.EARRING, ItemGroup.RING, ItemGroup.NECKLACE, ItemGroup.BELT);
	/** How far over the keeper's level the gear it sells may be, and how far under it for an opportunist. */
	private static final int LEVEL_SPREAD = 5;
	/** How far under the keeper's level a dedicated keeper's gear goes: its wide choice. */
	private static final int DEDICATED_GEAR_UNDER = 20;
	/** How far over the keeper's level its manastones go, the guide's table (a level 30 stone from a keeper of 20), and under it for an opportunist. */
	private static final int STONE_SPREAD = 10;
	/** How far under the keeper's level an opportunist's coins and medals go: the dedicated one sells every value its level allows. */
	private static final int MIXED_COINS_UNDER = 20;
	/** The highest manastone level in the data. */
	private static final int TOP_STONE_LEVEL = 70;
	/** Gear and jewellery are blue at least, by the guide's rule; eternal from level 60. Weights out of a hundred, before the level cuts the top off. */
	private static final int[] QUALITY_WEIGHTS = { 70, 25, 5 };
	private static final ItemQuality[] QUALITIES = { ItemQuality.LEGEND, ItemQuality.UNIQUE, ItemQuality.EPIC };
	private static final int ETERNAL_FROM_LEVEL = 60;
	/** How a stones store's lines are shared out: upgrade items, single-stat manastones, two-stat ones, ancient ones. */
	private static final int[] STONE_WEIGHTS = { 4, 3, 2, 1 };

	/** What a theme may stock for a faction, a class and a level, worked out once: the item table is read through for each. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();

	private BotStoreStock() {
	}

	/**
	 * @return The theme a keeper's id draws, the same every day, by the shares of {@link #SHARES}. Shares moved on 31 October with the food and the scrolls, so every keeper may find
	 *         itself with a new theme and a bag of the old one's goods: what the bag holds that the new theme does not sell is taken off the shelf by a restock, as before.
	 */
	public static Theme themeOf(int keeperId) {
		return SHARES[Math.floorMod(keeperId * 0x2F0B3A49, SHARES.length)];
	}

	/**
	 * @return The keeper's theme: its id's, except that a stones keeper under level {@value #STONES_FROM_LEVEL} that is not a wide one sells scrolls or food instead, since the stones of
	 *         60 and 70 it would be left with are out of its reach.
	 */
	public static Theme themeOf(Player keeper) {
		Theme theme = themeOf(keeper.getObjectId());
		if (theme == Theme.STONES && keeper.getLevel() < STONES_FROM_LEVEL && !isWide(keeper.getObjectId()))
			return Math.floorMod(keeper.getObjectId() * 0x1B873593, 2) == 0 ? Theme.FOOD : Theme.SCROLLS;
		return theme;
	}

	/** @return true for a stones keeper that sells stones of every level, one in {@value #WIDE_ONE_IN}, the same every day. */
	static boolean isWide(int keeperId) {
		return Math.floorMod(keeperId * 0x5BD1E995, WIDE_ONE_IN) == 0;
	}

	/** @return The class a gear store is stocked for, the same every day. */
	static PlayerClass classOf(int keeperId) {
		return STOCKED_CLASSES.get(Math.floorMod(keeperId * 0x27D4EB2D, STOCKED_CLASSES.size()));
	}

	/** @return The store's name, as players see it over the keeper's head. */
	public static String nameOf(Player keeper) {
		int level = keeper.getLevel();
		String gearLevels = Math.max(1, level - DEDICATED_GEAR_UNDER) + "-" + (level + LEVEL_SPREAD);
		return switch (themeOf(keeper)) {
			case GEAR -> "Équipement " + CLASS_NAMES.get(classOf(keeper.getObjectId())) + " " + gearLevels;
			case ACCESSORIES -> "Bijoux " + gearLevels;
			case STONES -> isWide(keeper.getObjectId()) ? "Pierres 10-" + Math.min(TOP_STONE_LEVEL, (level + STONE_SPREAD) / 10 * 10) : "Pierres 60-70";
			case COINS -> "Pièces et médailles";
			case FOOD -> "Cuisine " + Math.max(1, level - FOOD_UNDER) + "-" + (level + FOOD_OVER);
			case SCROLLS -> "Parchemins de vitesse";
			case MIXED -> "Bric-à-brac " + Math.max(1, level - LEVEL_SPREAD) + "-" + (level + LEVEL_SPREAD);
		};
	}

	/**
	 * Puts a fresh stock of the keeper's theme into its bag.
	 *
	 * @param lines How many lines to stock at most; fewer when the bag or the data has less room.
	 * @return The ids of the templates stocked, one per line.
	 */
	public static List<Integer> stock(Player keeper, int lines) {
		Theme theme = themeOf(keeper);
		boolean dedicated = theme != Theme.MIXED;
		// the rare goods, by about one store in ten: a stocking that draws none of them leaves them out of every pool
		Predicate<ItemTemplate> allowed = Rnd.get(0, 9) == 0 ? template -> true
			: template -> BotStoreGoods.of(template) == null || !BotStoreGoods.of(template).rare();
		Map<Theme, List<ItemTemplate>> pools = new java.util.EnumMap<>(Theme.class);
		for (Theme part : dedicated ? List.of(theme) : MIXED_WEIGHTS.keySet()) {
			List<ItemTemplate> pool = candidatesFor(keeper, part, dedicated).stream().filter(allowed).toList();
			if (!pool.isEmpty())
				pools.put(part, pool);
		}
		List<Integer> stocked = new ArrayList<>();
		if (pools.isEmpty())
			return stocked;
		int room = Math.min(lines, keeper.getInventory().getFreeSlots());
		int eventLines = 0;
		for (int attempt = 0; stocked.size() < room && attempt < room * 4; attempt++) {
			Theme part = dedicated ? theme : drawPart(pools.keySet());
			List<ItemTemplate> pool = pools.get(part);
			ItemTemplate template = switch (part) {
				case STONES -> drawStone(pool);
				case COINS, FOOD, SCROLLS -> Rnd.get(pool);
				default -> draw(pool);
			};
			if (template == null || stocked.contains(template.getTemplateId()))
				continue;
			if (BotStoreGoods.isEventScroll(template)) {
				if (eventLines >= MOST_EVENT_LINES)
					continue; // two lines of the thirty minute scrolls a store, no more: the tester's rule for them
				eventLines++;
			}
			long count = countOf(template);
			if (!dedicated)
				count = Math.max(1, count / 4); // what a session left in the bag, not a merchant's stock: the guide's five silver medals
			if (ItemService.addItem(keeper, template.getTemplateId(), count) < count)
				stocked.add(template.getTemplateId());
		}
		return stocked;
	}

	/** @return How many of a piece go on one line: stacks of the guide's goods as players sell them, one of a rare stone or of a piece of gear. */
	private static long countOf(ItemTemplate template) {
		BotStoreGoods.Good good = BotStoreGoods.of(template);
		if (good != null)
			return Rnd.get(good.minCount(), good.maxCount());
		if (isFood(template))
			return Rnd.get(10, 50);
		if (template.getItemGroup() == ItemGroup.SPECIAL_MANASTONE)
			return Rnd.get(1, 2);
		if (template.getItemGroup() == ItemGroup.MANASTONE)
			return template.getModifiers().size() == 1 ? Rnd.get(5, 20) : Rnd.get(1, 3);
		return 1;
	}

	/** @return true if the piece is something the keeper's store would stock, which is what a shelf forgotten by a restart is rebuilt from. */
	public static boolean fitsTheme(Player keeper, ItemTemplate template) {
		Theme theme = themeOf(keeper);
		if (theme != Theme.MIXED)
			return candidatesFor(keeper, theme, true).contains(template);
		return MIXED_WEIGHTS.keySet().stream().anyMatch(part -> candidatesFor(keeper, part, false).contains(template));
	}

	/** @return One of an opportunist's categories, by {@link #MIXED_WEIGHTS}, among those it has goods for. */
	private static Theme drawPart(Set<Theme> parts) {
		int roll = Rnd.get(0, parts.stream().mapToInt(MIXED_WEIGHTS::get).sum() - 1);
		for (Theme part : parts) {
			roll -= MIXED_WEIGHTS.get(part);
			if (roll < 0)
				return part;
		}
		return parts.iterator().next();
	}

	/**
	 * @return An upgrade item, a single-stat, a two-stat or an ancient manastone by {@link #STONE_WEIGHTS}, then an even draw within. By kind, because
	 *         the upgrade items are eight against hundreds of manastones and the two-stat stones, at tens of millions, outnumber the single ones six to
	 *         one: an even draw over all of them would almost never put an enchantment stone out and would fill the shelf with the dearest stones.
	 */
	private static ItemTemplate drawStone(List<ItemTemplate> pool) {
		List<List<ItemTemplate>> kinds = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		for (ItemTemplate template : pool) {
			int kind = BotStoreGoods.of(template) != null ? 0 : template.getItemGroup() == ItemGroup.SPECIAL_MANASTONE ? 3
				: template.getModifiers().size() == 1 ? 1 : 2;
			kinds.get(kind).add(template);
		}
		int total = 0;
		for (int kind = 0; kind < kinds.size(); kind++)
			total += kinds.get(kind).isEmpty() ? 0 : STONE_WEIGHTS[kind];
		int roll = Rnd.get(0, total - 1);
		for (int kind = 0; kind < kinds.size(); kind++) {
			if (kinds.get(kind).isEmpty())
				continue;
			roll -= STONE_WEIGHTS[kind];
			if (roll < 0)
				return Rnd.get(kinds.get(kind));
		}
		return null;
	}

	/** @return A piece of a drawn quality, falling back to the qualities under it when none is stocked; null when the pool has none at all. */
	private static ItemTemplate draw(List<ItemTemplate> pool) {
		int roll = Rnd.get(0, 99), quality = 0;
		for (int sum = QUALITY_WEIGHTS[0]; roll >= sum && quality + 1 < QUALITIES.length; sum += QUALITY_WEIGHTS[++quality])
			;
		for (; quality >= 0; quality--) {
			ItemQuality wanted = QUALITIES[quality];
			List<ItemTemplate> ofQuality = pool.stream().filter(template -> template.getItemQuality() == wanted).toList();
			if (!ofQuality.isEmpty())
				return Rnd.get(ofQuality);
		}
		return pool.isEmpty() ? null : Rnd.get(pool);
	}

	/**
	 * @param dedicated true for a dedicated keeper's wide choice, false for an opportunist's goods round its level.
	 */
	private static List<ItemTemplate> candidatesFor(Player keeper, Theme theme, boolean dedicated) {
		Race race = keeper.getRace();
		int level = keeper.getLevel();
		PlayerClass forClass = classOf(keeper.getObjectId());
		boolean wide = isWide(keeper.getObjectId());
		String key = theme + "/" + dedicated + "/" + race + "/" + level + "/" + (theme == Theme.GEAR ? forClass : "") + (theme == Theme.STONES ? "/" + wide : "");
		return candidates.computeIfAbsent(key, k -> {
			Set<ItemTemplate> found = new LinkedHashSet<>();
			if (theme == Theme.SCROLLS) {
				// by id, the guide's own: the hour scrolls of the cash shop and the scrolls of the events do not pass the tests below (an expiry, an event in the model's name, no trade)
				for (BotStoreGoods.Good good : java.util.stream.Stream.concat(BotStoreGoods.SCROLLS.stream(), BotStoreGoods.EVENT_SCROLLS.stream()).toList()) {
					ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(good.ids().getFirst());
					if (template != null && (BotStoreGoods.isScrollFor(template, level) || BotStoreGoods.isEventScrollFor(template, level)))
						found.add(template);
				}
				return List.copyOf(found);
			}
			for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
				if (!template.isTradeable() || !BotGearFit.isObtainable(template) || template.getItemQuality().getQualityId() < ItemQuality.COMMON.getQualityId())
					continue;
				if (template.getRace() != Race.PC_ALL && template.getRace() != race)
					continue;
				Race faction = BotGearSources.factionOf(template);
				if (faction.isAsmoOrEly() && faction != race)
					continue;
				if (fits(template, theme, dedicated, wide, forClass, race, level))
					found.add(template);
			}
			List<ItemTemplate> list = List.copyOf(found);
			return theme == Theme.GEAR ? heaviestArmourOnly(list, BotGearFit.heaviestLearnedBy(forClass, race, level)) : list;
		});
	}

	private static boolean fits(ItemTemplate template, Theme theme, boolean dedicated, boolean wide, PlayerClass forClass, Race race, int level) {
		ItemGroup group = template.getItemGroup();
		return switch (theme) {
			case GEAR -> (template.isWeapon() && BotGearFit.weaponsOfTrade(forClass).contains(group)
				|| template.isArmor() && !ACCESSORY_GROUPS.contains(group) && template.isClassSpecific(forClass)
					&& (!BotGearFit.isShield(template) || BotGearFit.carriesAShield(forClass)))
				&& isGearFor(template, dedicated, level);
			case ACCESSORIES -> ACCESSORY_GROUPS.contains(group) && isGearFor(template, dedicated, level);
			case STONES -> BotStoreGoods.isUpgradeFor(template, level) || isStoneFor(template, dedicated, wide, level);
			case COINS -> BotStoreGoods.isCoinFor(template, level, race)
				&& (dedicated || BotStoreGoods.of(template).fromLevel() > level - MIXED_COINS_UNDER);
			case FOOD -> isFood(template) && template.getLevel() <= level + FOOD_OVER && template.getLevel() >= level - (dedicated ? FOOD_UNDER : FOOD_UNDER / 2);
			case SCROLLS -> false; // by id, see candidatesFor
			case MIXED -> false; // made of the others, see stock and fitsTheme
		};
	}

	/** @return true for a piece of player gear of the store's levels, blue at least and eternal only from level 60, as the guide asked. */
	private static boolean isGearFor(ItemTemplate template, boolean dedicated, int level) {
		int under = dedicated ? DEDICATED_GEAR_UNDER : LEVEL_SPREAD;
		ItemQuality ceiling = level >= ETERNAL_FROM_LEVEL ? ItemQuality.EPIC : ItemQuality.UNIQUE;
		return template.getLevel() >= level - under && template.getLevel() <= level + LEVEL_SPREAD && BotEquipManager.isPlayerGear(template)
			&& template.getItemQuality().getQualityId() >= ItemQuality.LEGEND.getQualityId()
			&& template.getItemQuality().getQualityId() <= ceiling.getQualityId();
	}

	/** @return true for a manastone of the store's levels: up to ten over the keeper's, ancient ones from level 60 only. */
	private static boolean isStoneFor(ItemTemplate template, boolean dedicated, boolean wide, int level) {
		if (!BotManastones.isSold(template) || BotManastones.rangeOf(template) == null)
			return false;
		if (template.getItemGroup() == ItemGroup.SPECIAL_MANASTONE)
			return level >= BotManastones.ANCIENT_FROM_LEVEL;
		if (dedicated && !wide && template.getLevel() < HIGH_STONES_FROM)
			return false; // a store of stones that is not a wide one sells the stones of 60 and 70 only
		return template.getLevel() <= level + STONE_SPREAD && (dedicated || template.getLevel() >= level - STONE_SPREAD);
	}

	/** @return true for a cooked food, the boosts a player eats: tradeable, of the food families of the data, no raw material and no gatherable. */
	static boolean isFood(ItemTemplate template) {
		String model = template.getCName();
		return model != null && model.startsWith("food_") && !model.startsWith("food_material") && template.getItemGroup() != ItemGroup.GATHERABLE && template.getItemGroup() != ItemGroup.GATHERABLE_BONUS;
	}

	/**
	 * @param heaviest The weight of the heaviest armour the class learns: the class's own, which is not the heaviest the store found.
	 * @return The weapons, and of the armour only the class's own kind: a class may legally wear everything lighter than its own, and a store of
	 *         plate for templars with robes in it reads as a mistake, for the same reason {@code BotGearFit.isRightArmour} gives.
	 */
	private static List<ItemTemplate> heaviestArmourOnly(List<ItemTemplate> found, int heaviest) {
		return found.stream().filter(template -> !template.isArmor() || BotGearFit.armourWeight(template) == heaviest || BotGearFit.armourWeight(template) == 0)
			.toList();
	}
}
