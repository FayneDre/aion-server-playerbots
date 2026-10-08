package com.aionemu.gameserver.playerbot.economy;

import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.ItemType;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;

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

	/** The five places armour has a type that can be the wrong one. A helmet, a shield and every accessory are left out: they have none. */
	private static final long BODY_SLOTS = ItemSlot.TORSO.getSlotIdMask() | ItemSlot.PANTS.getSlotIdMask() | ItemSlot.GLOVES.getSlotIdMask()
		| ItemSlot.BOOTS.getSlotIdMask() | ItemSlot.SHOULDER.getSlotIdMask();

	/** One group per armour type, because a mastery covers the type and not the slot: whoever may wear a plate torso may wear plate boots. */
	private static final ItemGroup[] ARMOUR_TYPES = { ItemGroup.PL_TORSO, ItemGroup.CH_TORSO, ItemGroup.LT_TORSO, ItemGroup.RB_TORSO,
		ItemGroup.CL_TORSO };

	private BotGearFit() {
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
	 * @return true if this is armour of the kind the class actually wears, or something that has no kind at all — a shield, a helmet, an accessory.
	 *         <p>
	 *         The heaviest it is trained for, and nothing lighter. A class may legally wear every type below its own — a templar is taught cloth and
	 *         leather proficiency on the way to plate, and the engine accepts a robe on it without a word — which is how a templar of 37 came to be
	 *         standing in leather gloves. Nobody plays that way: the one armour the game lets a class choose freely is the one it never should.
	 *         <p>
	 *         It follows the character up rather than naming a type per class, so a templar below the level that teaches plate is held to chain,
	 *         which is what it can wear that day.
	 */
	public static boolean isRightArmour(Player bot, ItemTemplate template) {
		if (!isBodyArmour(template))
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
		return weightOf(template.getItemGroup());
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
