package com.aionemu.gameserver.playerbot.economy;

import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.ItemType;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.item.enums.ItemSubType;

/**
 * What a bot is allowed to end up wearing: gear a player could really come by, in the armour its class wears and with the weapon its class fights
 * with.
 * <p>
 * It exists because the two places that dress a bot — {@code BotOutfitter} at creation and {@code BotEquipManager} for what it loots afterwards —
 * were answering these questions apart, and the second one barely answered them at all. Everything reported from in game came through one of the
 * two: a cleric that looted a staff and dropped its mace, a templar in leather gloves, an event sword on a level six character, a three day mace on
 * a level forty-three one.
 */
public class BotGearFit {

	/**
	 * The five places armour has a type in the data. A helmet has one too, read from its model name ({@link #armourWeight}); a shield and every
	 * accessory have none.
	 */
	private static final long BODY_SLOTS = ItemSlot.TORSO.getSlotIdMask() | ItemSlot.PANTS.getSlotIdMask() | ItemSlot.GLOVES.getSlotIdMask()
		| ItemSlot.BOOTS.getSlotIdMask() | ItemSlot.SHOULDER.getSlotIdMask();

	/** One group per armour type, because a mastery covers the type and not the slot: whoever may wear a plate torso may wear plate boots. */
	private static final ItemGroup[] ARMOUR_TYPES = { ItemGroup.PL_TORSO, ItemGroup.CH_TORSO, ItemGroup.LT_TORSO, ItemGroup.RB_TORSO,
		ItemGroup.CL_TORSO };

	private BotGearFit() {
	}

	/**
	 * Wings a player of this server could come by, among those {@link #isObtainable} already lets through: not the cash shop's ({@code cash} in the
	 * model, a quest reward all the same), not a legion's, which its legion has to have earned, and not the pieces simply named "Test Item".
	 */
	static boolean isOrdinaryWing(ItemTemplate template) {
		if ("Test Item".equals(template.getName()))
			return false;
		if (template.getCName() == null)
			return true;
		for (String segment : template.getCName().split("_")) {
			if (segment.equalsIgnoreCase("cash") || segment.equalsIgnoreCase("legion") || segment.equalsIgnoreCase("shop"))
				return false;
		}
		return true;
	}

	/**
	 * @return Whose side a pair of wings is, by its model: {@code light} or {@code l} for the Elyos, {@code dark} or {@code d} for the Asmodians, and
	 *         the gear's own markers otherwise. Most wings name their side only that way, which the vendor lists, sold on both sides, do not settle.
	 */
	static Race wingSide(ItemTemplate template) {
		if (template.getCName() != null) {
			for (String segment : template.getCName().split("_")) {
				switch (segment.toLowerCase()) {
					case "light", "l", "n":
						return Race.ELYOS;
					case "dark", "d":
						return Race.ASMODIANS;
				}
			}
		}
		return Race.PC_ALL;
	}

	/**
	 * Tells gear a character could really be handed from gear that merely exists in the table.
	 * <p>
	 * Four kinds are turned away, and each of them was reported from in game before it was excluded here:
	 * <ul>
	 * <li><b>Npc costume and development leftovers</b>, which the model name says outright. The marker is not always the bare word: the level 30
	 * abyss grimoire a sorcerer of 43 was seen holding is {@code hidden_test6_book_e1_60b}, and the old test for a segment equal to {@code test} read
	 * {@code test6} as an ordinary word and let it through. Numbered now, which catches 52 more pieces.</li>
	 * <li><b>Event gear</b>, which exists for two weeks of the year on a live server and never at all on this one. The English name carries an
	 * {@code [Event]} tag and the model name an {@code event} segment; both are read, because neither is on all of them.</li>
	 * <li><b>Anything that expires.</b> 1890 pieces of gear carry an {@code expire_time}, and they are good ones — the three day fabled weapons a
	 * real character buys as a stopgap. A bot handed one is wearing something it could never have kept.</li>
	 * <li><b>Abyss gear</b>, which is bought with the points of realm fighting. A bot earns none. The pieces that ask for a rank are refused by the
	 * engine anyway, but plenty ask for no rank at all, which is how that grimoire was reachable in the first place.</li>
	 * </ul>
	 */
	public static boolean isObtainable(ItemTemplate template) {
		if (template.getExpireTime() > 0 || template.getItemType() == ItemType.ABYSS)
			return false;
		if (template.getName() != null && template.getName().regionMatches(true, 0, "[Event]", 0, 7))
			return false;
		if (template.getCName() == null)
			return true;
		for (String segment : template.getCName().split("_")) {
			if (isMarker(segment, "npc") || isMarker(segment, "test") || isMarker(segment, "event"))
				return false;
		}
		return true;
	}

	/** @return true for the marker itself and for the numbered forms of it — {@code test}, {@code test6}, {@code npc2}. */
	private static boolean isMarker(String segment, String marker) {
		if (segment.length() < marker.length() || !segment.regionMatches(true, 0, marker, 0, marker.length()))
			return false;
		for (int i = marker.length(); i < segment.length(); i++) {
			if (!Character.isDigit(segment.charAt(i)))
				return false;
		}
		return true;
	}

	/**
	 * @return true if this is armour of the kind the class actually wears, or something that has no kind at all — a hat any class wears, an
	 *         accessory. A shield only for a class that carries one.
	 *         <p>
	 *         The heaviest it is trained for, and nothing lighter. A class may legally wear every type below its own — a templar is taught cloth and
	 *         leather proficiency on the way to plate, and the engine accepts a robe on it without a word — which is how a templar of 37 came to be
	 *         standing in leather gloves. Nobody plays that way: the one armour the game lets a class choose freely is the one it never should.
	 *         <p>
	 *         It follows the character up rather than naming a type per class, so a templar below the level that teaches plate is held to chain,
	 *         which is what it can wear that day.
	 */
	public static boolean isRightArmour(Player bot, ItemTemplate template) {
		if (isShield(template))
			return carriesAShield(bot.getPlayerClass());
		if (!isBodyArmour(template) && (template.getItemGroup() != ItemGroup.HEAD || armourWeight(template) == 0))
			return true;
		int heaviest = heaviestTrained(bot);
		// A piece in one of the generic groups — ItemSubType.ALL_ARMOR — declares no type, weighs nothing here and so never matches. That is the
		// intent: the generic groups are where npc costume and test pieces sit, and almost no gear a player reaches is in one.
		return heaviest == 0 || armourWeight(template) == heaviest;
	}

	/** @return true if this is the weapon the class is meant to fight with, or not a weapon at all. */
	public static boolean isRightWeapon(Player bot, ItemTemplate template) {
		return !template.isWeapon() || weaponsOfTrade(bot.getPlayerClass()).contains(template.getItemGroup());
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
	 */
	public static Set<ItemGroup> weaponsOfTrade(PlayerClass playerClass) {
		return switch (playerClass) {
			case GLADIATOR -> Set.of(ItemGroup.POLEARM); // everything else is a heavy loss of damage, dual wielding included
			case TEMPLAR -> Set.of(ItemGroup.SWORD); // one hand, because the shield is the other half of the class
			case RANGER -> Set.of(ItemGroup.BOW);
			case ASSASSIN -> Set.of(ItemGroup.DAGGER, ItemGroup.SWORD); // the one class here that can truly hold two
			case SORCERER, SPIRIT_MASTER, MAGE -> Set.of(ItemGroup.ORB, ItemGroup.SPELLBOOK);
			case CLERIC, PRIEST -> Set.of(ItemGroup.MACE); // mace and shield outlives a staff, and surviving is the job
			case CHANTER -> Set.of(ItemGroup.STAFF);
			case BARD, ARTIST -> Set.of(ItemGroup.HARP); // no choice at all
			// Two pistols, which is how the class is played. It held a cannon on the belief that only the assassin could hold two weapons in this
			// build, and the skill tree says otherwise: the gunner learns pistol mastery at 9 and P_EQUIP_DUAL (skill 55) at 10, as a scout does.
			case GUNNER -> Set.of(ItemGroup.GUN);
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
	public static boolean isTrainedFor(Player bot, ItemTemplate template) {
		return isTrainedFor(bot, template.getItemGroup());
	}

	private static boolean isTrainedFor(Player bot, ItemGroup group) {
		Set<Integer> mastery = DataManager.SKILL_DATA.getMasterySkills(group);
		if (mastery.isEmpty())
			return true;
		for (int skillId : mastery) {
			if (bot.getSkillList().isSkillPresent(skillId))
				return true;
		}
		return false;
	}

	/**
	 * @return How protective a piece's armour type is, so that the heaviest a class can wear is the one it gets. Weapons, accessories and the generic
	 *         groups that name no type all score 0, which leaves them ranked on their level alone.
	 *         <p>
	 *         Cloth and robe are two types here and not one: {@code CLOTHES} is the clothing every class is taught at level one, {@code ROBE} the
	 *         cloth armour a mage is taught on top of it. Cloth used to fall through to 0 and so weighed the same as a weapon, which is one of the
	 *         ways it reached the top of a list it had no business being in.
	 */
	public static int armourWeight(ItemTemplate template) {
		if (template.getItemGroup() == ItemGroup.HEAD)
			return headgearWeight(template);
		return weightOf(template.getItemGroup());
	}

	/**
	 * @return The armour type a helmet is made in, as a weight, or 0 for headgear made for nobody in particular.
	 *         <p>
	 *         Helmets share one group whatever they are made of, so the data gives them no type and every class may wear every one; the model name
	 *         does say, by its first segment, as the body pieces' does. Read from there because a store of gladiator gear was seen selling a cloth
	 *         headband and a leather hat, and a gladiator in either is dressed for another class.
	 */
	private static int headgearWeight(ItemTemplate template) {
		String model = template.getCName();
		if (model == null)
			return 0;
		return switch (model.substring(0, Math.max(0, model.indexOf('_')))) {
			case "pl" -> weightOf(ItemGroup.PL_TORSO);
			case "ch" -> weightOf(ItemGroup.CH_TORSO);
			case "lt" -> weightOf(ItemGroup.LT_TORSO);
			case "rb" -> weightOf(ItemGroup.RB_TORSO);
			case "cl" -> weightOf(ItemGroup.CL_TORSO);
			default -> 0; // "ac", the hats and headbands any class wears
		};
	}

	/** @return true for a shield, of whatever kind. */
	public static boolean isShield(ItemTemplate template) {
		return template.getItemGroup().getItemSubType() == ItemSubType.SHIELD || template.getItemGroup() == ItemGroup.CL_SHIELD;
	}

	/**
	 * @return true for a class that fights with a shield in its other hand. Authored for the reason {@link #weaponsOfTrade} gives: every other class
	 *         holds a weapon in two hands or two weapons, and a store of gladiator gear was seen selling two shields.
	 */
	public static boolean carriesAShield(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR, CLERIC, WARRIOR, PRIEST -> true;
			default -> false;
		};
	}

	private static int weightOf(ItemGroup group) {
		return switch (group.getItemSubType()) {
			case PLATE -> 5;
			case CHAIN -> 4;
			case LEATHER -> 3;
			case ROBE -> 2;
			case CLOTHES -> 1;
			default -> 0;
		};
	}

	/**
	 * @return The weight of the heaviest armour a class learns by that level, read from the skill tree, or 0 if it learns none.
	 *         <p>
	 *         {@link #isRightArmour} asks the same of a bot's own skills. A store is stocked for a class nobody in the room is, so it has no skill list to
	 *         read; the item data cannot answer either, since every piece declares every class. Stores were stocked with the heaviest armour among what
	 *         they found, which was plate for everyone: a store of spiritmaster gear sold nothing else.
	 */
	public static int heaviestLearnedBy(PlayerClass playerClass, Race race, int level) {
		int heaviest = 0;
		for (ItemGroup group : ARMOUR_TYPES) {
			for (int skillId : DataManager.SKILL_DATA.getMasterySkills(group)) {
				if (learns(playerClass, race, level, skillId) || !playerClass.isStartingClass() && learns(playerClass.getStartingClass(), race, level, skillId))
					heaviest = Math.max(heaviest, weightOf(group));
			}
		}
		return heaviest;
	}

	private static boolean learns(PlayerClass playerClass, Race race, int level, int skillId) {
		return DataManager.SKILL_TREE_DATA.getTemplatesForSkill(skillId, playerClass, race).stream().anyMatch(learnt -> learnt.getMinLevel() <= level);
	}

	/** @return The weight of the heaviest armour the bot is trained for today, or 0 if it is trained for none at all. */
	private static int heaviestTrained(Player bot) {
		int heaviest = 0;
		for (ItemGroup group : ARMOUR_TYPES) {
			if (isTrainedFor(bot, group))
				heaviest = Math.max(heaviest, weightOf(group));
		}
		return heaviest;
	}

	/** @return true if this goes in one of the five places where armour has a type, and nowhere else. */
	private static boolean isBodyArmour(ItemTemplate template) {
		long slot = template.getItemSlot();
		return template.isArmor() && (slot & BODY_SLOTS) != 0 && (slot & ~BODY_SLOTS) == 0;
	}
}
