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
		GEAR, ACCESSORIES, MANASTONES, ENCHANTMENT
	}

	/** The classes a gear store is stocked for, with the name a French client shows for each. */
	private static final Map<PlayerClass, String> CLASS_NAMES = Map.ofEntries(Map.entry(PlayerClass.GLADIATOR, "gladiateur"),
		Map.entry(PlayerClass.TEMPLAR, "templier"), Map.entry(PlayerClass.ASSASSIN, "assassin"), Map.entry(PlayerClass.RANGER, "rôdeur"),
		Map.entry(PlayerClass.SORCERER, "sorcier"), Map.entry(PlayerClass.SPIRIT_MASTER, "spiritualiste"), Map.entry(PlayerClass.CLERIC, "clerc"),
		Map.entry(PlayerClass.CHANTER, "aède"), Map.entry(PlayerClass.GUNNER, "pistolero"), Map.entry(PlayerClass.BARD, "barde"),
		Map.entry(PlayerClass.RIDER, "éthertech"));
	private static final List<PlayerClass> STOCKED_CLASSES = List.copyOf(new java.util.TreeSet<>(CLASS_NAMES.keySet()));
	private static final Set<ItemGroup> ACCESSORY_GROUPS = Set.of(ItemGroup.EARRING, ItemGroup.RING, ItemGroup.NECKLACE, ItemGroup.BELT);
	/** How far from the keeper's level the pieces it sells may be. */
	private static final int LEVEL_SPREAD = 5;
	/** How many grades above the keeper's level the enchantment stones it sells may go: a stone works best at or above the piece's level. */
	private static final int ENCHANTMENT_GRADES = 30;
	/** Green, blue and gold, out of a hundred, before the level's ceiling cuts the top off. */
	private static final int[] QUALITY_WEIGHTS = { 70, 25, 5 };
	private static final ItemQuality[] QUALITIES = { ItemQuality.RARE, ItemQuality.LEGEND, ItemQuality.UNIQUE };

	/** What a theme may stock for a faction, a class and a level, worked out once: the item table is read through for each. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();

	private BotStoreStock() {
	}

	/** @return The keeper's theme, the same every day. */
	public static Theme themeOf(int keeperId) {
		return Theme.values()[Math.floorMod(keeperId * 0x45D9F3B, Theme.values().length)];
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
			case MANASTONES -> "Pierres de mana " + Math.max(1, keeper.getLevel() - 2 * LEVEL_SPREAD) + "-" + keeper.getLevel();
			case ENCHANTMENT -> "Pierres d'enchantement";
		};
	}

	/** @return How the price of what this theme sells is worked out. */
	public static BotStorePricing.Kind kindOf(Theme theme) {
		return switch (theme) {
			case GEAR, ACCESSORIES -> BotStorePricing.Kind.GEAR;
			case MANASTONES -> BotStorePricing.Kind.MANASTONE;
			case ENCHANTMENT -> BotStorePricing.Kind.ENCHANTMENT;
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
		int room = Math.min(lines, keeper.getInventory().getFreeSlots());
		for (int attempt = 0; stocked.size() < room && attempt < room * 4; attempt++) {
			ItemTemplate template = draw(pool, BotOutfitter.bestQualityFor(keeper.getLevel()));
			if (template == null || stocked.contains(template.getTemplateId()))
				continue;
			long count = switch (theme) {
				case GEAR, ACCESSORIES -> 1;
				case MANASTONES -> Rnd.get(5, 20);
				case ENCHANTMENT -> Rnd.get(10, 50);
			};
			if (ItemService.addItem(keeper, template.getTemplateId(), count) < count)
				stocked.add(template.getTemplateId());
		}
		return stocked;
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
			return theme == Theme.GEAR ? heaviestArmourOnly(found) : List.copyOf(found);
		});
	}

	private static boolean fits(ItemTemplate template, Theme theme, PlayerClass forClass, int level) {
		ItemGroup group = template.getItemGroup();
		return switch (theme) {
			case GEAR -> (template.isWeapon() && BotGearFit.weaponsOfTrade(forClass).contains(group)
				|| template.isArmor() && !ACCESSORY_GROUPS.contains(group) && template.isClassSpecific(forClass))
				&& Math.abs(template.getLevel() - level) <= LEVEL_SPREAD && BotEquipManager.isPlayerGear(template);
			case ACCESSORIES -> ACCESSORY_GROUPS.contains(group) && Math.abs(template.getLevel() - level) <= LEVEL_SPREAD
				&& BotEquipManager.isPlayerGear(template);
			case MANASTONES -> group == ItemGroup.MANASTONE && template.getLevel() <= level && template.getLevel() >= level - 2 * LEVEL_SPREAD;
			case ENCHANTMENT -> group == ItemGroup.ENCHANTMENT && template.getLevel() >= level && template.getLevel() <= level + ENCHANTMENT_GRADES
				&& template.getName() != null && template.getName().endsWith("Enchantment Stone");
		};
	}

	/**
	 * @return The weapons, and of the armour only the heaviest kind there is: a class may legally wear everything lighter than its own, and a store of
	 *         plate for templars with robes in it reads as a mistake, for the same reason {@code BotGearFit.isRightArmour} gives.
	 */
	private static List<ItemTemplate> heaviestArmourOnly(List<ItemTemplate> found) {
		int heaviest = found.stream().filter(ItemTemplate::isArmor).mapToInt(BotGearFit::armourWeight).max().orElse(0);
		return found.stream().filter(template -> !template.isArmor() || BotGearFit.armourWeight(template) == heaviest || BotGearFit.armourWeight(template) == 0)
			.toList();
	}
}
