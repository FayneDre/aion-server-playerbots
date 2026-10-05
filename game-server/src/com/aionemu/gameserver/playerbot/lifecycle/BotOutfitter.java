package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
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
import com.aionemu.gameserver.playerbot.economy.BotGearRefiner;
import com.aionemu.gameserver.playerbot.economy.BotGearSources;
import com.aionemu.gameserver.playerbot.economy.BotStatWeights;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.skillengine.effect.WeaponDualEffect;

/**
 * Dresses a freshly created bot in gear that suits its class and level.
 * <p>
 * Until this existed, populating a map cloned one template character, so every inhabitant was the same class wearing the same three training pieces
 * whatever level it had been given. A population that all looks alike and fights in starting gear is scenery that fools nobody.
 */
public class BotOutfitter {

	/**
	 * The places worth filling, main hand first.
	 * <p>
	 * The off hand is filled last and only after the main hand, because what may go in it is decided by what is already held: a shield for a class
	 * with shield mastery, a second weapon for one trained to wield two, and nothing at all for the rest. Leaving it out entirely is what sent
	 * templars out without a shield and assassins holding one dagger.
	 */
	private static final ItemSlot[] DRESSED_SLOTS = { ItemSlot.MAIN_HAND, ItemSlot.TORSO, ItemSlot.PANTS, ItemSlot.GLOVES, ItemSlot.BOOTS,
		ItemSlot.SHOULDER, ItemSlot.SUB_HAND };
	/**
	 * The seven accessory places, which used to be dismissed here as "not what makes a character look equipped" — true of how it looks and wrong
	 * about everything else. <b>Accessories are the game's main source of magic resistance</b>, so a character with all seven empty takes magical
	 * damage like nothing else in the world does, and they carry crit, accuracy and HP besides.
	 * <p>
	 * Nothing filled them before because nothing could: every accessory group needs no mastery, and the test that told player gear from npc costume
	 * demanded one. See {@code BotGearSources}.
	 * <p>
	 * The pairs are listed left before right and fill correctly in that order, because {@code BotEquipManager.slotFor} hands back the first free
	 * finger or ear rather than the one named — so the second ring finds the second finger by itself.
	 */
	private static final ItemSlot[] ACCESSORY_SLOTS = { ItemSlot.NECKLACE, ItemSlot.EARRINGS_LEFT, ItemSlot.EARRINGS_RIGHT, ItemSlot.RING_LEFT,
		ItemSlot.RING_RIGHT, ItemSlot.WAIST, ItemSlot.HELMET };
	/**
	 * Below this a character is not expected to be wearing jewellery, and the game barely offers any: the earliest rings and earrings in the table
	 * are level 21. A bot still puts on an accessory it loots before then — this governs only what it is handed at creation.
	 */
	private static final int ACCESSORIES_FROM_LEVEL = 20;
	/**
	 * @return The best quality a character of this level is allowed to be handed.
	 *         <p>
	 *         This was {@code RARE} in a constant, on the grounds that a village of people in heroic armour reads as a costume party. True of a
	 *         village of beginners and false of everybody else: <b>a character past the middle twenties that is still in green is undergeared</b>,
	 *         not modest. Heroic armour becomes ordinary at 26, when the bronze coin gear — "Elite Rank 7" — comes within reach of anyone who does
	 *         the content, and fabled does the same at 36 with the silver coin gear. Those are the two steps the game itself is built around, and
	 *         they are where the stats a bot badly needs first appear: movement speed on boots, flight speed on a torso, attack speed on a weapon.
	 *         <p>
	 *         Still a ceiling and not a floor. What a bot actually ends up in is whatever the ranking finds, which below these levels is mostly green
	 *         anyway because little else exists.
	 */
	private static ItemQuality bestQualityFor(int level) {
		if (level >= 36)
			return ItemQuality.UNIQUE;
		if (level >= 26)
			return ItemQuality.LEGEND;
		return ItemQuality.RARE;
	}

	/** Candidates per class, level and slot are the same for every bot of that kind, and the item list is long enough to be worth remembering. */
	private static final Map<String, List<ItemTemplate>> candidates = new ConcurrentHashMap<>();

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
		if (bot.getLevel() >= ACCESSORIES_FROM_LEVEL) {
			for (ItemSlot slot : ACCESSORY_SLOTS) {
				if (fill(bot, slot))
					worn++;
			}
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
	 * Brings a bot that already exists up to what it would be given if it were created today.
	 * <p>
	 * Dressing happens once, at creation, so every change here reaches new inhabitants only. A world settled before accessories existed would wear
	 * none for the rest of its life, and the pieces of the other faction that two faction bugs let through would stay on just as long — equipping
	 * only ever adds, and nothing in the engine takes a worn item off a character that is content with it.
	 * <p>
	 * The stripping comes first and the dressing after, in that order, because a place has to be empty before anything can be put in it.
	 *
	 * @return What was done, in words, for whoever typed the command.
	 */
	public static String regear(Player bot) {
		int stripped = stripOtherFactionGear(bot);
		int worn = dress(bot);
		// After the dressing, so the pieces it just put on are counted once rather than refined twice: equipping already brings a piece up to level.
		int refined = refineWornGear(bot);
		if (stripped == 0 && worn == 0 && refined == 0)
			return bot.getName() + " had nothing to change";
		// Dressing says this for itself whenever it puts something on, so it is only needed for a bot that was stripped and found nothing to replace
		// the piece with. Said unconditionally because the alternative is a condition that is wrong the day dressing stops announcing it.
		BotEquipManager.showAppearance(bot);
		return bot.getName() + " took off " + stripped + " piece(s) of the wrong faction, put on " + worn + " and improved " + refined;
	}

	/**
	 * Brings gear a bot has been wearing all along up to the enchantment and the stones its owner would have put into it.
	 * <p>
	 * Needed because both happen when a piece goes on, and a bot settled before any of this existed has worn the same torso since the day it was
	 * made. Stigmas are left alone, and that is not tidiness: a stigma stone stores <b>its skill level</b> in the same field, so enchanting one would
	 * silently change what the bot knows.
	 * <p>
	 * The socketing is rolled per piece and most pieces fail that roll below the level ceiling, so running this twice over the same bot fills a few
	 * more slots each time. That is a fair reading of a character that keeps buying stones, and it does stop: the engine refuses once a piece is full.
	 *
	 * @return How many pieces ended up better than they were.
	 */
	private static int refineWornGear(Player bot) {
		int refined = 0;
		for (Item worn : bot.getEquipment().getEquippedItemsWithoutStigma()) {
			boolean better = BotGearRefiner.refine(bot, worn);
			better |= BotGearRefiner.socket(bot, worn) > 0;
			if (better)
				refined++;
		}
		return refined;
	}

	/**
	 * Takes off, and throws away, anything worn that belongs to the other side.
	 * <p>
	 * Thrown away rather than kept, because a bot that keeps it carries it for the rest of its life and {@code BotEquipManager} offers it back on
	 * every pass. It is worth nothing to this character and nothing to any other: a bot only ever meets bots of its own faction.
	 */
	private static int stripOtherFactionGear(Player bot) {
		int stripped = 0;
		// Copied before walking it, because unequipping writes to the same map that lists it.
		for (Item worn : new ArrayList<>(bot.getEquipment().getEquippedItemsWithoutStigma())) {
			if (!BotGearSources.isOtherFaction(bot, worn.getItemTemplate()))
				continue;
			// false, not the default: the inventory is routinely full on a bot that has been farming, and refusing to take off a piece because there
			// is no room for it is exactly backwards when the piece is about to be destroyed anyway.
			if (bot.getEquipment().unEquipItem(worn.getObjectId(), false) == null)
				continue;
			BotEquipManager.discard(bot, worn);
			stripped++;
		}
		return stripped;
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
		// Never into a hand that is already full, and the off hand is the one that can be. A two handed weapon is stored under <b>both</b> hands, so
		// the shield pass found the off hand "occupied", {@code slotFor} answered with that very slot — it returns the taken one when none is free,
		// which is right when swapping a ring and wrong here — and {@code Equipment.getUnequipSlots} then took the polearm out to make room. A
		// gladiator ended up holding a shield and nothing else, and a cleric that had drawn a staff did too, while a chanter with a one handed mace
		// kept both. That is the whole of "gladiator with a shield instead of a two hander".
		if (bot.getEquipment().isSlotEquipped(slot.getSlotIdMask()))
			return false;
		List<ItemTemplate> wearable = candidates.computeIfAbsent(key(bot, slot), _ -> gatherFor(bot, slot));
		if (wearable.isEmpty())
			return false;
		// Started at a random point rather than at the top, so a crowd is not in uniform, and then gone round in order so the best of what fits is
		// still reached. <b>Random within the right answers only</b>: the list is sorted with what the character can actually hold first and the
		// weapon its own skill book asks for next, and entering it anywhere threw that away -- a ranger had seven chances in eight of starting past
		// the bows and going out with two swords, which is exactly what came back from in game. Variety belongs among equally good pieces, not
		// between a bow and the wrong weapon entirely.
		//
		// "Right answer" used to mean no more than "something the class may hold", which no accessory ever fails -- they need no mastery and are not
		// weapons -- so all eight candidates qualified and the pick among them was flat random. That was harmless while the eight differed only in
		// their model and ruinous the moment they differed in what they grant: the ranking would have been computed and then thrown away, and a
		// gladiator would have had one chance in eight of the ring it wants. The window is now the pieces that are genuinely equivalent, which is
		// where variety belonged all along -- and there are plenty of them, since the table carries each ring in an "a" and a "b" that differ in
		// nothing else.
		int rightAnswers = 0;
		while (rightAnswers < wearable.size() && isWhatItShouldHold(bot, wearable.get(rightAnswers))
			&& isAsGoodAs(bot, wearable.get(0), wearable.get(rightAnswers)))
			rightAnswers++;
		int start = Rnd.get(0, Math.max(1, rightAnswers) - 1);
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
			BotEquipManager.discard(bot, item);
		}
		return false;
	}

	/**
	 * @return Whether two pieces are worth the same to this character, and so may be chosen between for looks alone. Level and what it grants, which
	 *         are the two things the ranking ends on; the armour type and the weapon kind are already settled by the caller's other question.
	 */
	private static boolean isAsGoodAs(Player bot, ItemTemplate best, ItemTemplate candidate) {
		return candidate.getLevel() == best.getLevel()
			&& Math.abs(BotStatWeights.score(bot, candidate) - BotStatWeights.score(bot, best)) < 0.01f;
	}

	/**
	 * @return Whether this is one of the pieces the shortlist put first: something the character holds the mastery for, and — for a weapon — the one
	 *         its class is meant to fight with. It is the same pair of questions {@code gatherFor} sorts by, asked again so that the randomness below
	 *         can be confined to the answers that tie.
	 */
	private static boolean isWhatItShouldHold(Player bot, ItemTemplate template) {
		return isTrainedFor(bot, template) && (!template.isWeapon() || weaponsOfTrade(bot).contains(template.getItemGroup()));
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
			if (template.getItemQuality().getQualityId() > bestQualityFor(bot.getLevel()).getQualityId())
				continue;
			if (!template.isClassSpecific(bot.getPlayerClass()))
				continue;
			if (template.getRace() != Race.PC_ALL && template.getRace() != bot.getRace())
				continue;
			if (BotGearSources.isOtherFaction(bot, template))
				continue;
			// No floor on how old a piece may be, only a ceiling on how new. Once npc costume is excluded there is little enough left at the lowest
			// levels that insisting on a close match left whole classes with nothing at all, and something slightly behind is what a real character
			// wears anyway. The sort below still prefers the closest to its level.
			int required = template.getRequiredLevel(bot.getPlayerClass());
			if (required < 1 || required > bot.getLevel())
				continue;
			// A quest reward is out of reach until the quest can be taken, whatever the piece itself asks for.
			if (BotGearSources.reachableFrom(template) > bot.getLevel())
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
		//
		// What the piece actually grants comes last, below the level and not above it, and that ordering is the whole of the judgement. A piece's
		// level is what says how much of everything it carries, so a level 25 ring with crit on it really is worse than a level 40 ring without —
		// ranking on the stats alone would pick the 25. Below the level, though, it decides nearly every choice there is: gear arrives in level steps,
		// so a shortlist is mostly pieces of one level that differ only in what they give, and that is exactly the question "which ring does a
		// gladiator want" asks.
		Comparator<ItemTemplate> bestFirst = Comparator.comparingInt(BotOutfitter::armourWeight)
			.thenComparingInt(template -> weaponWorth(bot, template))
			.thenComparingInt(ItemTemplate::getLevel)
			.thenComparingDouble(template -> BotStatWeights.score(bot, template))
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
	 * @return The weapon a class is meant to fight with.
	 *         <p>
	 *         Authored, and this is the second place in the module where that is the right answer — {@code BotRole} is the first, for the same
	 *         reason. The data says what a class <b>may</b> hold and never what it <b>should</b>: a gladiator is allowed a dagger, a chanter a mace,
	 *         a cleric a staff, and the engine accepts all three without a word. Which of them is the real one is knowledge about playing the game,
	 *         and it exists nowhere in any file.
	 *         <p>
	 *         It was derived first, by weighing how narrowly each skill names a weapon, and the derivation was wrong where it mattered: it gave the
	 *         chanter a mace, could not separate a gladiator's polearm from a greatsword, and had no way at all to know that a gunner's second pistol
	 *         is unreachable here. This list comes from somebody who plays these classes.
	 *         <p>
	 *         A preference and not a filter: it sorts ahead of the ranking, below what the character is actually trained for, so a class too low to
	 *         have earned its own weapon still gets something rather than nothing.
	 */
	private static Set<ItemGroup> weaponsOfTrade(Player bot) {
		return switch (bot.getPlayerClass()) {
			case GLADIATOR -> Set.of(ItemGroup.POLEARM); // everything else is a heavy loss of damage, dual wielding included
			case TEMPLAR -> Set.of(ItemGroup.SWORD); // one hand, because the shield is the other half of the class
			case RANGER -> Set.of(ItemGroup.BOW);
			case ASSASSIN -> Set.of(ItemGroup.DAGGER, ItemGroup.SWORD); // the one class here that can truly hold two
			case SORCERER, SPIRIT_MASTER, MAGE -> Set.of(ItemGroup.ORB, ItemGroup.SPELLBOOK);
			case CLERIC, PRIEST -> Set.of(ItemGroup.MACE); // mace and shield outlives a staff, and surviving is the job
			case CHANTER -> Set.of(ItemGroup.STAFF);
			case BARD, ARTIST -> Set.of(ItemGroup.HARP); // no choice at all
			// Two pistols is how this class is played and it cannot be done here: only the assassin carries the dual wield effect in this build, so
			// the engine quietly moves a second gun back to the main hand. The cannon is the other thing it is meant to hold, and it works.
			case GUNNER -> Set.of(ItemGroup.CANNON);
			case RIDER -> Set.of(ItemGroup.KEYBLADE); // the key is what the class climbs into its machine with
			case ENGINEER -> Set.of(ItemGroup.GUN);
			case WARRIOR -> Set.of(ItemGroup.SWORD, ItemGroup.MACE);
			case SCOUT -> Set.of(ItemGroup.DAGGER, ItemGroup.SWORD);
		};
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
