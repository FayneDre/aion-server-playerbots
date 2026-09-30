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
import com.aionemu.gameserver.playerbot.economy.BotEquipManager;
import com.aionemu.gameserver.services.item.ItemFactory;

/**
 * Dresses a freshly created bot in gear that suits its class and level.
 * <p>
 * Until this existed, populating a map cloned one template character, so every inhabitant was the same class wearing the same three training pieces
 * whatever level it had been given. A population that all looks alike and fights in starting gear is scenery that fools nobody.
 */
public class BotOutfitter {

	/** The places worth filling. Rings and earrings come in pairs and are left out: jewellery is not what makes a character look equipped. */
	private static final ItemSlot[] DRESSED_SLOTS = { ItemSlot.MAIN_HAND, ItemSlot.TORSO, ItemSlot.PANTS, ItemSlot.GLOVES, ItemSlot.BOOTS,
		ItemSlot.SHOULDER };
	/** Nothing fancier than this: a village of people in heroic armour reads as a costume party, not a village. */
	private static final ItemQuality BEST_QUALITY = ItemQuality.RARE;

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
			ItemTemplate template = pick(bot, slot);
			if (template == null)
				continue;
			Item item = ItemFactory.newItem(template.getTemplateId());
			item.setTuneCount(Math.max(0, item.getTuneCount())); // identified: nobody hands out an unread piece as a uniform
			item.setSoulBound(true);
			if (bot.getInventory().add(item) == null)
				continue;
			// one slot, never the template's mask of every slot it could go in: a one handed weapon reads as "either hand", and a two slot mask is
			// refused outright. That is why bots were created with armour and empty hands
			if (BotEquipManager.wear(bot, item.getObjectId(), template))
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

	/** @return One of the better pieces this character could wear in that place, chosen at random among them so a crowd is not in uniform. */
	private static ItemTemplate pick(Player bot, ItemSlot slot) {
		List<ItemTemplate> wearable = candidates.computeIfAbsent(key(bot, slot), _ -> gatherFor(bot, slot));
		return wearable.isEmpty() ? null : wearable.get(Rnd.get(0, wearable.size() - 1));
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
			found.add(template);
		}
		// Heaviest armour first, then newest. A class may legally wear anything lighter than its own — a scout is taught cloth proficiency alongside
		// leather, so the engine accepts a robe on it and says nothing — and sorting on level alone therefore dressed a scout in a robe whenever the
		// robe happened to be a level newer. Nobody plays that way: you wear the heaviest your class allows, and only fall back when nothing fits.
		found.sort(Comparator.comparingInt(BotOutfitter::armourWeight).thenComparingInt(ItemTemplate::getLevel).reversed());
		return found.subList(0, Math.min(8, found.size()));
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
