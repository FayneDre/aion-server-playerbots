package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * reaches a capital. Only what a player could come by goes on a shelf — reachable, of the keeper's faction, tradeable, no better than the level's
 * ceiling — so a store never shows what the world itself does not hand out. See docs/bot-stores-plan.md.
 */
public final class BotStoreStock {

	/** What a store sells, which also gives it its name. */
	public enum Theme {
		GEAR, ACCESSORIES,
		/**
		 * Manastones of the band and the upgrade items of {@link BotStoreGoods#UPGRADES} the keeper's level allows. One theme rather than two: a store of
		 * enchantment stones alone held one line, which is what two such stores showed on 20 October.
		 */
		STONES,
		/** Coins and medals, a theme of its own by the guide's wish (24 October), stocked by the keeper level each asks for. */
		COINS
	}

	/** The themes before coins came, in the order a keeper's id was read against them. */
	private static final Theme[] FIRST_THEMES = { Theme.GEAR, Theme.ACCESSORIES, Theme.STONES };

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
	/** How far from the keeper's level the pieces it sells may be. */
	private static final int LEVEL_SPREAD = 5;
	/** Green, blue and gold, out of a hundred, before the level's ceiling cuts the top off. */
	private static final int[] QUALITY_WEIGHTS = { 70, 25, 5 };
	private static final ItemQuality[] QUALITIES = { ItemQuality.RARE, ItemQuality.LEGEND, ItemQuality.UNIQUE };

	/** What a theme may stock for a faction, a class and a level, worked out once: the item table is read through for each. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();

	private BotStoreStock() {
	}

	/**
	 * @return The keeper's theme, the same every day. A quarter of the keepers keep coins, read off another hash, and the rest keep the theme they had
	 *         before: reading the id against four themes instead of three would have handed nearly every keeper a new one, with a bag of the old
	 *         one's goods nothing would sell.
	 */
	public static Theme themeOf(int keeperId) {
		if (Math.floorMod(keeperId * 0x1B873593, 4) == 0)
			return Theme.COINS;
		return FIRST_THEMES[Math.floorMod(keeperId * 0x45D9F3B, FIRST_THEMES.length)];
	}

	/** @return The class a gear store is stocked for, the same every day. */
	static PlayerClass classOf(int keeperId) {
		return STOCKED_CLASSES.get(Math.floorMod(keeperId * 0x27D4EB2D, STOCKED_CLASSES.size()));
	}

	/** @return The store's name, as players see it over the keeper's head. */
	public static String nameOf(Player keeper) {
		int low = Math.max(1, keeper.getLevel() - LEVEL_SPREAD), high = keeper.getLevel() + LEVEL_SPREAD;
		return switch (themeOf(keeper.getObjectId())) {
			case GEAR -> "Équipement " + CLASS_NAMES.get(classOf(keeper.getObjectId())) + " " + low + "-" + high;
			case ACCESSORIES -> "Bijoux " + low + "-" + high;
			case STONES -> "Pierres " + Math.max(1, keeper.getLevel() - 2 * LEVEL_SPREAD) + "-" + keeper.getLevel();
			case COINS -> "Pièces et médailles";
		};
	}

	/** @return How the price of a piece is worked out. */
	public static BotStorePricing.Kind kindOf(ItemTemplate template) {
		return switch (template.getItemGroup()) {
			case MANASTONE -> BotStorePricing.Kind.MANASTONE;
			case ENCHANTMENT -> BotStorePricing.Kind.ENCHANTMENT;
			default -> BotStorePricing.Kind.GEAR;
		};
	}

	/**
	 * Puts a fresh stock of the keeper's theme into its bag.
	 *
	 * @param lines How many lines to stock at most; fewer when the bag or the data has less room.
	 * @return The ids of the templates stocked, one per line.
	 */
	public static List<Integer> stock(Player keeper, int lines) {
		Theme theme = themeOf(keeper.getObjectId());
		List<ItemTemplate> pool = candidatesFor(keeper, theme);
		List<Integer> stocked = new ArrayList<>();
		if (pool.isEmpty())
			return stocked;
		// the rare goods, by about one store in ten: a stocking that draws none of them leaves them out of the pool altogether
		if (Rnd.get(0, 9) != 0)
			pool = pool.stream().filter(template -> BotStoreGoods.of(template) == null || !BotStoreGoods.of(template).rare()).toList();
		if (pool.isEmpty())
			return stocked;
		int room = Math.min(lines, keeper.getInventory().getFreeSlots());
		for (int attempt = 0; stocked.size() < room && attempt < room * 4; attempt++) {
			ItemTemplate template = switch (theme) {
				case STONES -> drawStone(pool);
				case COINS -> Rnd.get(pool);
				default -> draw(pool, BotOutfitter.bestQualityFor(keeper.getLevel()));
			};
			if (template == null || stocked.contains(template.getTemplateId()))
				continue;
			BotStoreGoods.Good good = BotStoreGoods.of(template);
			if (good != null && stocked.stream().anyMatch(good.ids()::contains))
				continue; // the other side's copy of a coin the faction filter could not tell apart
			long count = good != null ? Rnd.get(good.minCount(), good.maxCount()) : switch (kindOf(template)) {
				case GEAR -> 1;
				case MANASTONE -> Rnd.get(5, 20);
				case ENCHANTMENT -> Rnd.get(10, 50);
			};
			if (ItemService.addItem(keeper, template.getTemplateId(), count) < count)
				stocked.add(template.getTemplateId());
		}
		return stocked;
	}

	/** @return true if the piece is something the keeper's store would stock, which is what a shelf forgotten by a restart is rebuilt from. */
	public static boolean fitsTheme(Player keeper, ItemTemplate template) {
		return candidatesFor(keeper, themeOf(keeper.getObjectId())).contains(template);
	}

	/**
	 * @return A manastone or an upgrade item, even odds between the two, then an even draw within. Even rather than by quality, because most
	 *         manastones are common and a draw by quality kept landing on the one green among them; and by halves, because the upgrade items are eight
	 *         against hundreds of manastones and an even draw over all of them would almost never put one out.
	 */
	private static ItemTemplate drawStone(List<ItemTemplate> pool) {
		List<ItemTemplate> upgrades = pool.stream().filter(template -> BotStoreGoods.of(template) != null).toList();
		if (upgrades.isEmpty() || upgrades.size() == pool.size())
			return Rnd.get(pool);
		if (Rnd.get(0, 1) == 0)
			return Rnd.get(upgrades);
		return Rnd.get(pool.stream().filter(template -> BotStoreGoods.of(template) == null).toList());
	}

	/** @return A template of a drawn quality, falling back to the qualities under it when none is stocked; null when the pool has none at all. */
	private static ItemTemplate draw(List<ItemTemplate> pool, ItemQuality ceiling) {
		int roll = Rnd.get(0, 99), quality = 0;
		for (int sum = QUALITY_WEIGHTS[0]; roll >= sum && quality + 1 < QUALITIES.length; sum += QUALITY_WEIGHTS[++quality])
			;
		for (; quality >= 0; quality--) {
			ItemQuality wanted = QUALITIES[quality];
			if (wanted.getQualityId() > ceiling.getQualityId())
				continue;
			List<ItemTemplate> ofQuality = pool.stream().filter(template -> template.getItemQuality() == wanted).toList();
			if (!ofQuality.isEmpty())
				return Rnd.get(ofQuality);
		}
		return pool.stream().filter(template -> template.getItemQuality().getQualityId() <= ceiling.getQualityId()).findAny().orElse(null);
	}

	private static List<ItemTemplate> candidatesFor(Player keeper, Theme theme) {
		Race race = keeper.getRace();
		int level = keeper.getLevel();
		PlayerClass forClass = classOf(keeper.getObjectId());
		String key = theme + "/" + race + "/" + level + "/" + (theme == Theme.GEAR ? forClass : "");
		return candidates.computeIfAbsent(key, k -> {
			List<ItemTemplate> found = new ArrayList<>();
			for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
				if (!template.isTradeable() || !BotGearFit.isObtainable(template) || template.getItemQuality().getQualityId() < ItemQuality.COMMON.getQualityId())
					continue;
				if (template.getRace() != Race.PC_ALL && template.getRace() != race)
					continue;
				Race faction = BotGearSources.factionOf(template);
				if (faction.isAsmoOrEly() && faction != race)
					continue;
				if (fits(template, theme, forClass, level))
					found.add(template);
			}
			return theme == Theme.GEAR ? heaviestArmourOnly(found, BotGearFit.heaviestLearnedBy(forClass, race, level)) : List.copyOf(found);
		});
	}

	private static boolean fits(ItemTemplate template, Theme theme, PlayerClass forClass, int level) {
		ItemGroup group = template.getItemGroup();
		return switch (theme) {
			case GEAR -> (template.isWeapon() && BotGearFit.weaponsOfTrade(forClass).contains(group)
				|| template.isArmor() && !ACCESSORY_GROUPS.contains(group) && template.isClassSpecific(forClass)
					&& (!BotGearFit.isShield(template) || BotGearFit.carriesAShield(forClass)))
				&& Math.abs(template.getLevel() - level) <= LEVEL_SPREAD && BotEquipManager.isPlayerGear(template);
			case ACCESSORIES -> ACCESSORY_GROUPS.contains(group) && Math.abs(template.getLevel() - level) <= LEVEL_SPREAD
				&& BotEquipManager.isPlayerGear(template);
			case STONES -> group == ItemGroup.MANASTONE && template.getLevel() <= level && template.getLevel() >= level - 2 * LEVEL_SPREAD
				|| BotStoreGoods.isUpgradeFor(template, level);
			case COINS -> BotStoreGoods.isCoinFor(template, level);
		};
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
