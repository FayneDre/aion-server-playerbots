package com.aionemu.gameserver.playerbot.economy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aionemu.gameserver.model.stats.calc.functions.StatFunction;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;

/**
 * Which manastones a store sells and what a live broker sold them for, from a price guide's figures of 24 October. See docs/capitals/bot-store-goods-plan.md.
 * <p>
 * A stone is read by the stats it adds rather than by its name, since the names are the server's and not the client's the guide reads from. A single
 * stat is priced by how much players wanted it; two stats by how well the pair suits one class; an ancient stone by its quality, the best of them
 * by their value.
 */
public final class BotManastones {

	/** How much players wanted a stat, the guide's four classes. */
	enum StatClass {
		PREMIUM, CORE, NICHE, TRASH
	}

	private static final Map<StatEnum, StatClass> STAT_CLASSES = Map.ofEntries(
		Map.entry(StatEnum.PHYSICAL_CRITICAL, StatClass.PREMIUM), Map.entry(StatEnum.BOOST_MAGICAL_SKILL, StatClass.PREMIUM),
		Map.entry(StatEnum.PHYSICAL_ATTACK, StatClass.PREMIUM),
		Map.entry(StatEnum.MAXHP, StatClass.CORE), Map.entry(StatEnum.MAGICAL_ACCURACY, StatClass.CORE), Map.entry(StatEnum.PHYSICAL_ACCURACY, StatClass.CORE),
		Map.entry(StatEnum.MAGICAL_CRITICAL, StatClass.CORE),
		// the guide's defences, and with them the stats of the same kind it did not name: resistances, defence, healing, the PvP ratios
		Map.entry(StatEnum.PHYSICAL_CRITICAL_RESIST, StatClass.NICHE), Map.entry(StatEnum.BLOCK, StatClass.NICHE),
		Map.entry(StatEnum.MAGICAL_RESIST, StatClass.NICHE), Map.entry(StatEnum.MAGICAL_CRITICAL_RESIST, StatClass.NICHE),
		Map.entry(StatEnum.MAGIC_SKILL_BOOST_RESIST, StatClass.NICHE), Map.entry(StatEnum.PHYSICAL_DEFENSE, StatClass.NICHE),
		Map.entry(StatEnum.HEAL_BOOST, StatClass.NICHE), Map.entry(StatEnum.PVP_DEFEND_RATIO, StatClass.NICHE),
		Map.entry(StatEnum.PVP_DEFEND_RATIO_PHYSICAL, StatClass.NICHE), Map.entry(StatEnum.PVP_DEFEND_RATIO_MAGICAL, StatClass.NICHE),
		Map.entry(StatEnum.EVASION, StatClass.TRASH), Map.entry(StatEnum.PARRY, StatClass.TRASH), Map.entry(StatEnum.MAXMP, StatClass.TRASH),
		Map.entry(StatEnum.FLY_TIME, StatClass.TRASH));

	/** A single-stat stone of level 60 or 70, by its stat's class: the guide's broker ranges. */
	private static final Map<StatClass, BotStorePricing.Range> SINGLE = Map.of(StatClass.PREMIUM, new BotStorePricing.Range(1_500_000, 4_500_000),
		StatClass.CORE, new BotStorePricing.Range(300_000, 1_000_000), StatClass.NICHE, new BotStorePricing.Range(50_000, 200_000),
		StatClass.TRASH, new BotStorePricing.Range(10_000, 25_000));

	/**
	 * The pairs the guide put in its first tier, the ones a class builds round. The guide's source said 80 to 150 million, 30 to 70 and 10 to 25 for
	 * the three tiers; the guide found that a little dear, and a notch under was agreed on 24 October.
	 */
	private static final List<Set<StatEnum>> TIER_ONE = List.of(Set.of(StatEnum.PHYSICAL_ATTACK, StatEnum.PHYSICAL_CRITICAL),
		Set.of(StatEnum.BOOST_MAGICAL_SKILL, StatEnum.MAGICAL_ACCURACY), Set.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.MAGICAL_ACCURACY));
	private static final BotStorePricing.Range TIER_ONE_RANGE = new BotStorePricing.Range(60_000_000, 110_000_000),
		TIER_TWO_RANGE = new BotStorePricing.Range(25_000_000, 55_000_000), TIER_THREE_RANGE = new BotStorePricing.Range(8_000_000, 20_000_000);

	/** An ancient stone by its quality: the guide gave the bottoms (300k, 500k, 1M, 2 to 20M); the tops are ours, the bottom doubled. */
	private static final Map<ItemQuality, BotStorePricing.Range> ANCIENT = Map.of(ItemQuality.RARE, new BotStorePricing.Range(300_000, 600_000),
		ItemQuality.LEGEND, new BotStorePricing.Range(500_000, 1_000_000), ItemQuality.UNIQUE, new BotStorePricing.Range(1_000_000, 2_000_000),
		ItemQuality.EPIC, new BotStorePricing.Range(2_000_000, 10_000_000));
	/** The ancient stones the guide put at 10 million at least: Crit Strike +24, Attack +8, Magic Boost +32, HP +105, and anything over them. */
	private static final Map<StatEnum, Integer> BEST_ANCIENT = Map.of(StatEnum.PHYSICAL_CRITICAL, 24, StatEnum.PHYSICAL_ATTACK, 8,
		StatEnum.BOOST_MAGICAL_SKILL, 32, StatEnum.MAXHP, 105);
	private static final BotStorePricing.Range BEST_ANCIENT_RANGE = new BotStorePricing.Range(10_000_000, 20_000_000);

	/** The level the guide's ranges are for, and under which they halve every ten levels: a level 50 stone at half, 30 an eighth. */
	private static final int FULL_PRICE_LEVEL = 60;
	/** Ancient stones, all of level 70, are sold by keepers of this level and over only. */
	static final int ANCIENT_FROM_LEVEL = 60;

	private BotManastones() {
	}

	/** @return true for a manastone a store sells: a green one of one stat, a blue one of two, or an ancient one. Common ones nobody sold. */
	static boolean isSold(ItemTemplate template) {
		if (template.getItemGroup() == ItemGroup.SPECIAL_MANASTONE)
			return template.getModifiers() != null && template.getModifiers().size() == 1;
		if (template.getItemGroup() != ItemGroup.MANASTONE || template.getModifiers() == null)
			return false;
		int stats = template.getModifiers().size();
		return stats == 1 && template.getItemQuality() == ItemQuality.RARE || stats == 2 && template.getItemQuality() == ItemQuality.LEGEND;
	}

	/** @return The class of a stat, or null for one the guide's classes do not place. */
	static StatClass classOf(StatEnum stat) {
		return STAT_CLASSES.get(stat);
	}

	/** @return The broker's range for a manastone a store sells, or null for anything else. */
	static BotStorePricing.Range rangeOf(ItemTemplate template) {
		if (!isSold(template))
			return null;
		List<StatFunction> stats = template.getModifiers();
		if (template.getItemGroup() == ItemGroup.SPECIAL_MANASTONE) {
			StatFunction stat = stats.getFirst();
			Integer best = BEST_ANCIENT.get(stat.getName());
			return best != null && stat.getValue() >= best ? BEST_ANCIENT_RANGE : ANCIENT.get(template.getItemQuality());
		}
		BotStorePricing.Range range;
		if (stats.size() == 1) {
			StatClass statClass = classOf(stats.getFirst().getName());
			if (statClass == null)
				return null;
			range = SINGLE.get(statClass);
		} else {
			range = pairRange(stats.get(0).getName(), stats.get(1).getName());
		}
		return range.halved(Math.max(0, (FULL_PRICE_LEVEL - template.getLevel() + 9) / 10));
	}

	/** @return A two-stat stone's tier: a pair a class builds round, one of its stats with health, or anything else. */
	static BotStorePricing.Range pairRange(StatEnum first, StatEnum second) {
		Set<StatEnum> pair = first == second ? Set.of(first) : Set.of(first, second);
		if (TIER_ONE.contains(pair))
			return TIER_ONE_RANGE;
		if (pair.contains(StatEnum.MAXHP) && (classOf(first) == StatClass.PREMIUM || classOf(second) == StatClass.PREMIUM))
			return TIER_TWO_RANGE;
		return TIER_THREE_RANGE;
	}
}
