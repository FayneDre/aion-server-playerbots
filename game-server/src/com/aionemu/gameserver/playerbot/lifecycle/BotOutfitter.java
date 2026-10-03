package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.item.WeaponStats;
import com.aionemu.gameserver.playerbot.economy.BotEquipManager;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.skillengine.condition.WeaponCondition;
import com.aionemu.gameserver.skillengine.effect.WeaponDualEffect;
import com.aionemu.gameserver.skillengine.model.SkillLearnTemplate;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * Dresses a freshly created bot in gear that suits its class and level.
 * <p>
 * Until this existed, populating a map cloned one template character, so every inhabitant was the same class wearing the same three training pieces
 * whatever level it had been given. A population that all looks alike and fights in starting gear is scenery that fools nobody.
 */
public class BotOutfitter {

	/**
	 * The places worth filling, main hand first. Rings and earrings come in pairs and are left out: jewellery is not what makes a character look
	 * equipped.
	 * <p>
	 * The off hand is filled last and only after the main hand, because what may go in it is decided by what is already held: a shield for a class
	 * with shield mastery, a second weapon for one trained to wield two, and nothing at all for the rest. Leaving it out entirely is what sent
	 * templars out without a shield and assassins holding one dagger.
	 */
	private static final ItemSlot[] DRESSED_SLOTS = { ItemSlot.MAIN_HAND, ItemSlot.TORSO, ItemSlot.PANTS, ItemSlot.GLOVES, ItemSlot.BOOTS,
		ItemSlot.SHOULDER, ItemSlot.SUB_HAND };
	/** Nothing fancier than this: a village of people in heroic armour reads as a costume party, not a village. */
	private static final ItemQuality BEST_QUALITY = ItemQuality.RARE;

	/** Candidates per class, level and slot are the same for every bot of that kind, and the item list is long enough to be worth remembering. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();
	/** What each class is built to hold, worked out once from its skill book. */
	private static final Map<String, Set<ItemGroup>> weaponsByClass = new ConcurrentHashMap<>();

	private BotOutfitter() {
	}

	/**
	 * Puts a set of gear on a bot.
	 * <p>
	 * Each piece is created already identified and already bound to its wearer, which is what lets it simply be equipped: unidentified is unwearable,
	 * and a soul bound piece that is not yet bound opens a confirmation window instead of going on. A bot could answer both, but it would spend its
	 * first ten minutes of existence reading and binding rather than living.
	 *
	 * @return How many pieces it ended up wearing.
	 */
	public static int dress(Player bot) {
		int worn = 0;
		for (ItemSlot slot : DRESSED_SLOTS) {
			if (fill(bot, slot))
				worn++;
		}
		// Not a warning, and it took a screenshot of a console full of red to notice. Below level four there is barely any gear made for players at
		// all, so this fires for perfectly ordinary characters — who are then wearing the training kit every character is created in, exactly like a
		// real beginner. Nothing is wrong and nothing needs doing. It is worth a word only because an empty wardrobe at level twenty would not be.
		if (worn == 0)
			LoggerFactory.getLogger(BotOutfitter.class).debug("Nothing made for players fits a {} of level {}, so it keeps its starting kit",
				bot.getPlayerClass(), bot.getLevel());
		return worn;
	}

	/**
	 * Fills one place, trying the candidates in turn until one actually goes on.
	 * <p>
	 * Picking a single piece and hoping was the whole of the weapon problem. Only the engine knows whether a character may hold a thing — the
	 * masteries are the part no filter here can honestly reproduce — so one refusal left the hand empty, and a bot with an empty hand cannot use the
	 * skills that need a weapon either. The complaint reads as "bots spawn with no weapon, or refuse to equip one"; it is one refusal, never retried.
	 * <p>
	 * A refused piece is taken straight back out of the bag. Left in it, it is dead weight the character carries for the rest of its life, and
	 * {@code BotEquipManager} picks it up and is refused it again on every pass.
	 *
	 * @return true if the place ended up filled.
	 */
	private static boolean fill(Player bot, ItemSlot slot) {
		List<ItemTemplate> wearable = candidates.computeIfAbsent(key(bot, slot), _ -> gatherFor(bot, slot));
		if (wearable.isEmpty())
			return false;
		// started at a random point rather than at the top, so a crowd is not in uniform, and then gone round in order so the best of what fits is
		// still reached
		int start = Rnd.get(0, wearable.size() - 1);
		for (int attempt = 0; attempt < wearable.size(); attempt++) {
			ItemTemplate template = wearable.get((start + attempt) % wearable.size());
			// The off hand is the one place where what belongs there depends on what is already held, so the piece is asked where it would actually
			// go and dropped if the answer is not here. Without it a templar's second sword is offered for the shield hand, the engine quietly moves
			// it to the main hand — it does that itself, with no message — and the better weapon already drawn is replaced by the spare.
			if (slot == ItemSlot.SUB_HAND && BotEquipManager.slotFor(bot, template) != slot.getSlotIdMask())
				continue;
			Item item = ItemFactory.newItem(template.getTemplateId());
			item.setTuneCount(Math.max(0, item.getTuneCount())); // identified: nobody hands out an unread piece as a uniform
			item.setSoulBound(true);
			if (bot.getInventory().add(item) == null)
				return false; // no room, and the next candidate would find none either
			// one slot, never the template's mask of every slot it could go in: a one handed weapon reads as "either hand", and a two slot mask is
			// refused outright. That is why bots were created with armour and empty hands
			if (BotEquipManager.wear(bot, item.getObjectId(), template))
				return true;
			bot.getInventory().delete(item);
		}
		return false;
	}

	private static String key(Player bot, ItemSlot slot) {
		return bot.getPlayerClass() + "/" + bot.getRace() + "/" + bot.getLevel() + "/" + slot;
	}

	/**
	 * Reads the item data for everything a character of this kind could wear in that place.
	 * <p>
	 * Only the restrictions that the engine will enforce anyway are applied — class, race, required level, quality — because the alternative is a
	 * second copy of its rules that drifts from it. What is left is sorted by level so the best of what fits comes first, and the top few are kept.
	 */
	private static List<ItemTemplate> gatherFor(Player bot, ItemSlot slot) {
		List<ItemTemplate> found = new ArrayList<>();
		long slotMask = slot.getSlotIdMask();
		for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
			if ((template.getItemSlot() & slotMask) == 0 || !(template.isWeapon() || template.isArmor()))
				continue;
			if (template.getItemQuality().getQualityId() > BEST_QUALITY.getQualityId())
				continue;
			if (!template.isClassSpecific(bot.getPlayerClass()))
				continue;
			if (template.getRace() != Race.PC_ALL && template.getRace() != bot.getRace())
				continue;
			int required = template.getRequiredLevel(bot.getPlayerClass());
			// No floor on how old a piece may be, only a ceiling on how new. Once npc costume is excluded there is little enough left at the lowest
			// levels that insisting on a close match left whole classes with nothing at all, and something slightly behind is what a real character
			// wears anyway. The sort below still prefers the closest to its level.
			if (required < 1 || required > bot.getLevel())
				continue;
			// Gear a player could be handed. Guard equipment and test pieces ask level one of every class and name no armour type, so neither the
			// level filter nor the engine itself turns them down — which is how a village of level four characters came to be wearing "NPC Veteran
			// Guard Chain Shoes Cleric" of level 65, and a songweaver chain. Which armour types this class may wear is left to the engine: it checks
			// the mastery on equipping, and asking here instead refused every caster its weapon, since a mage is taught its spellbook only when it
			// becomes a sorcerer.
			if (!BotEquipManager.isPlayerGear(template))
				continue;
			// its own level as well as the level it asks for: the two are not the same question, and only one of them is what the piece is worth
			if (template.getLevel() > bot.getLevel())
				continue;
			// Armour the class is already trained for. Without this the sort below would put leather at the top of a mage's list, the engine would
			// refuse every piece of it, and the mage would end up with nothing. Weapons are deliberately not filtered this way: a caster is taught
			// its own weapon only on becoming an advanced class, and asking here left every mage unarmed.
			if (template.isArmor() && !isTrainedFor(bot, template))
				continue;
			// The off hand holds one of two things and which one is settled by the class, not by what happens to score highest. There are far more
			// weapons in the game than shields, so a shortlist of eight for this slot came out all weapons, every one of them was then dropped
			// because a templar cannot wield two, and the templar went out with a bare left hand — which is exactly what was reported.
			if (slot == ItemSlot.SUB_HAND && template.isWeapon() && !WeaponDualEffect.hasDualWieldEffect(bot))
				continue;
			found.add(template);
		}
		// Heaviest armour first, then newest. A class may legally wear anything lighter than its own — a scout is taught cloth proficiency alongside
		// leather, so the engine accepts a robe on it and says nothing — and sorting on level alone therefore dressed a scout in a robe whenever the
		// robe happened to be a level newer. Nobody plays that way: you wear the heaviest your class allows, and only fall back when nothing fits.
		Comparator<ItemTemplate> bestFirst = Comparator.comparingInt(BotOutfitter::armourWeight)
			.thenComparingInt(template -> weaponWorth(bot, template))
			.thenComparingInt(ItemTemplate::getLevel)
			.reversed();
		// Ahead of all of it, what the character can actually hold today. Weapons are not filtered on mastery above, and deliberately so — a caster
		// is taught its own weapon only on becoming an advanced class, and refusing the rest left every mage unarmed — but "not filtered out" was
		// taken to mean "as good as any", so a shortlist of eight could be eight weapons the engine refuses one after another. Sorted rather than
		// filtered: what it will be taught later stays on the list, below what it can hold now.
		// Then the weapon the class's own skill book asks for, ahead of raw numbers. A ranger that takes two swords because they out-damage a bow
		// cannot use a single one of its 210 bow skills — it is the "no weapon, no skills" failure again, wearing a weapon. Reported as "I thought
		// there were no rangers, but they have two swords instead of a bow".
		Set<ItemGroup> trade = weaponsOfTrade(bot);
		found.sort(Comparator.comparing((ItemTemplate template) -> !isTrainedFor(bot, template))
			.thenComparing(template -> !(template.isWeapon() && trade.contains(template.getItemGroup())))
			.thenComparing(bestFirst));
		return found.subList(0, Math.min(8, found.size()));
	}

	/**
	 * @return The weapons this class is actually built around, or an empty set when its skills do not say.
	 *         <p>
	 *         Read from the class's own skill book rather than authored. A skill may state what it must be held to be used, and the statement is
	 *         worth exactly as much as it is narrow: a condition naming one weapon says what the class is, one naming all thirteen says nothing. So
	 *         each skill contributes one share split between the weapons it names, and what survives at half the best score is the class's trade.
	 *         <p>
	 *         Measured over every class: a ranger scores 210 on bow against 10 on sword, a songweaver 307 on harp, a gunner 208 on gun. A templar
	 *         comes out 43 / 35 / 35 across greatsword, mace and sword, which is a class that genuinely uses all three — so nothing is excluded for
	 *         it, and the ranking below decides as before. The rule only ever bites where the data is emphatic, which is exactly where bots were
	 *         picking the wrong thing. Casters state no weapon condition at all and are untouched.
	 */
	private static Set<ItemGroup> weaponsOfTrade(Player bot) {
		return weaponsByClass.computeIfAbsent(bot.getPlayerClass() + "/" + bot.getRace(), _ -> gatherWeaponsOfTrade(bot));
	}

	private static Set<ItemGroup> gatherWeaponsOfTrade(Player bot) {
		Map<ItemGroup, Double> score = new EnumMap<>(ItemGroup.class);
		for (int level = 1; level <= DataManager.PLAYER_EXPERIENCE_TABLE.getMaxLevel(); level++) {
			for (SkillLearnTemplate learn : DataManager.SKILL_TREE_DATA.getTemplatesFor(bot.getPlayerClass(), level, bot.getRace())) {
				SkillTemplate skill = DataManager.SKILL_DATA.getSkillTemplate(learn.getSkillId());
				WeaponCondition condition = skill == null ? null : skill.getWeaponCondition();
				if (condition == null || condition.getItemGroups() == null || condition.getItemGroups().isEmpty())
					continue;
				double share = 1d / condition.getItemGroups().size();
				for (ItemGroup group : condition.getItemGroups())
					score.merge(group, share, Double::sum);
			}
		}
		// The whole class book, not what it has learned so far: a ranger of ten is a ranger, and the weapon it is going to live by is the same one.
		double best = score.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
		if (best <= 0)
			return Set.of();
		Set<ItemGroup> trade = EnumSet.noneOf(ItemGroup.class);
		for (Map.Entry<ItemGroup, Double> entry : score.entrySet()) {
			if (entry.getValue() >= best / 2)
				trade.add(entry.getKey());
		}
		return trade;
	}

	/**
	 * @return true if the bot already holds a mastery that lets it wear this piece, which is the same question
	 *         {@code Equipment.checkAvailableEquipSkills} asks when it refuses one. A group that needs no mastery is open to everyone.
	 */
	private static boolean isTrainedFor(Player bot, ItemTemplate template) {
		Set<Integer> mastery = DataManager.SKILL_DATA.getMasterySkills(template.getItemGroup());
		if (mastery.isEmpty())
			return true;
		for (int skillId : mastery) {
			if (bot.getSkillList().isSkillPresent(skillId))
				return true;
		}
		return false;
	}

	/**
	 * @return How protective a piece's armour type is, so that the heaviest a class can wear is the one it gets. Weapons and anything without a type
	 *         all score the same, which leaves them sorted by level as before.
	 */
	/**
	 * @return What a weapon is worth to this character, or 0 for anything that is not one — which leaves armour sorted as it was.
	 *         <p>
	 *         Weapons were ranked on item level alone, so a gladiator took a dagger one level newer than the sword beside it and went to war with it.
	 *         Legal, because the game lets a gladiator hold a dagger, and wrong, because nobody plays that way. The data states what a weapon is
	 *         worth and it is not its level: damage per swing, how many swings it lands and how fast it swings for a class that hits, and the magical
	 *         boost for a class that casts. The same argument as ranking skills by their stated power rather than by the order they were learned in.
	 */
	private static int weaponWorth(Player bot, ItemTemplate template) {
		if (!template.isWeapon())
			return 0;
		WeaponStats stats = template.getWeaponStats();
		if (stats == null)
			return 0;
		if (!bot.getPlayerClass().isPhysicalClass())
			return stats.getBoostMagicalSkill();
		int speed = Math.max(1, stats.getAttackSpeed()); // milliseconds per swing; a zero here would be a stat gone wrong, not a fast weapon
		return Math.round(stats.getMeanDamage() * Math.max(1, stats.getHitCount()) * 1000f / speed);
	}

	private static int armourWeight(ItemTemplate template) {
		return switch (template.getItemGroup().getItemSubType()) {
			case PLATE -> 4;
			case CHAIN -> 3;
			case LEATHER -> 2;
			case ROBE -> 1;
			default -> 0;
		};
	}
}
