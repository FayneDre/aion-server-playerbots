package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_PLAYER_APPEARANCE;
import com.aionemu.gameserver.services.item.ItemActionService;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * Keeps a bot wearing the best of what it owns.
 * <p>
 * Without this a bot that levels for hours fights in the gear it spawned with: it loots better weapons, walks past them in its own bag, and slowly
 * stops being able to kill anything its level.
 */
public class BotEquipManager {

	private BotEquipManager() {
	}

	/**
	 * Puts on one thing in the bag that beats what is worn in its place.
	 * <p>
	 * Whether the bot is <b>allowed</b> to wear it is never asked here. {@code Equipment.equipItem} already checks the abyss rank, the mastery
	 * skills, the class, the level, the race, the gender and the room left in the bag, and refuses with a message a bot cannot read anyway. Trying is
	 * the question, exactly as it is for skills, and it is the engine's answer rather than a second copy of its rules that would drift from it.
	 *
	 * @return true if something was equipped. One per call, so a bagful is put on over several seconds rather than in one frame.
	 */
	public static boolean equipUpgrades(Player bot) {
		if (isReading(bot))
			return true;
		// Every candidate is tried, not just the best one. The engine refuses plenty of them — a mace a priest has no mastery for, a piece for the
		// other race — and stopping at the first refusal would leave the bot staring at the same unusable item for ever while the wearable one two
		// places further down the bag went unnoticed.
		for (Item upgrade : upgrades(bot, true)) {
			if (wear(bot, upgrade.getObjectId(), upgrade.getItemTemplate())) {
				LoggerFactory.getLogger(BotEquipManager.class).info("Bot {} puts on {}", bot.getName(), upgrade.getItemTemplate().getName());
				return true;
			}
			// A piece that binds to its owner is not refused, it is *asked about*: equipItem opens a confirmation window and returns null, which to a
			// bot looks exactly like a refusal. That is why nothing was ever worn — every upgrade worth having is soul bound. Answering yes starts
			// five seconds of binding that end by equipping the piece itself, so there is nothing more to do here.
			if (bot.getResponseRequester().respond(SM_QUESTION_WINDOW.STR_SOUL_BOUND_ITEM_DO_YOU_WANT_SOUL_BOUND, 1)) {
				LoggerFactory.getLogger(BotEquipManager.class).info("Bot {} binds {} to itself", bot.getName(), upgrade.getItemTemplate().getName());
				return true;
			}
		}
		return false;
	}

	/**
	 * Puts one piece on, in one place, and lets everyone watching see it.
	 *
	 * @return true if it went on.
	 */
	public static boolean wear(Player bot, int itemObjectId, ItemTemplate template) {
		long slot = slotFor(bot, template);
		if (slot == 0 || bot.getEquipment().equipItem(itemObjectId, slot) == null)
			return false;
		// what CM_EQUIP_ITEM does after a successful equip. Without it the piece is worn as far as the server is concerned and invisible to every
		// client in range, which is how a village of bots came to be seen in the gear they were created with
		PacketSendUtility.broadcastPacket(bot, new SM_UPDATE_PLAYER_APPEARANCE(bot.getObjectId(), bot.getEquipment().getEquippedForAppearance()),
			true);
		return true;
	}

	/**
	 * Picks the one place a piece goes.
	 * <p>
	 * The template's slot is a <b>mask of every place the piece could go</b>, not a place: a one handed weapon reads as "either hand", a ring as
	 * "either finger", a power shard as "either side". Handing that mask to {@code Equipment.equipItem} is what the client never does — it sends the
	 * single slot the player dropped the item on — and the engine refuses a two slot mask outright unless the piece is a two handed weapon. It
	 * refuses it into the audit log, which is why dressing bots printed accusations of cheating into the chat, and why no bot ever held a weapon.
	 *
	 * @return The single slot to equip into, or 0 when there is nowhere this can go.
	 */
	public static long slotFor(Player bot, ItemTemplate template) {
		long mask = template.getItemSlot();
		if (mask == 0)
			return 0;
		if (template.isTwoHandWeapon())
			return mask; // it claims both hands, and the engine expects to be told exactly that
		// a weapon goes in the main hand. The off hand holds a second weapon, which needs a dual wield skill, and filling it by accident costs the
		// bot its shield
		if (template.isWeapon())
			return (mask & ItemSlot.MAIN_HAND.getSlotIdMask()) != 0 ? ItemSlot.MAIN_HAND.getSlotIdMask() : firstSlot(mask);
		long occupied = 0;
		for (ItemSlot slot : ItemSlot.getSlotsFor(mask)) {
			long id = slot.getSlotIdMask();
			if ((id & ItemSlot.MAIN_OFF_OR_SUB_OFF.getSlotIdMask()) != 0)
				continue; // the engine refuses these outright: they are filled by switching hands, never directly
			if (!bot.getEquipment().isSlotEquipped(id))
				return id; // a free ear, finger or side before one that is already taken
			if (occupied == 0)
				occupied = id;
		}
		return occupied;
	}

	/**
	 * Tells gear made for players from gear that merely exists.
	 * <p>
	 * The item table holds guard equipment and development leftovers — "NPC Veteran Guard Chain Shoes Cleric" at level 65, "Test Spaulders Level 35"
	 * — and they are dangerous precisely because nothing refuses them: they carry no level restriction, so they ask level one of every class, and
	 * they sit in generic groups that name no armour type, so no mastery is required either. A bot dressed by score put them on in preference to
	 * everything else.
	 * <p>
	 * What separates them is that the game does not know what mastery they would need. Every piece a player is meant to wear does: robe, leather,
	 * chain, plate, or a weapon's own kind.
	 *
	 * @return true if this is gear a player could be handed.
	 */
	public static boolean isPlayerGear(ItemTemplate template) {
		// Two things, and each lets something different through. A piece whose group names no mastery is a generic slot only npc and test gear sits
		// in. A piece that declares no level restriction takes the default of "level one for every class", which is how a level two warrior came to
		// be standing in full white plate: the pieces were level one, so every level filter passed them, and they were npc costume all the same.
		return template.hasLevelRestrictions() && !DataManager.SKILL_DATA.getMasterySkills(template.getItemGroup()).isEmpty();
	}

	/**
	 * @return true if nothing that would let this piece be worn appears anywhere in this class's skill tree — plate for a priest, a bow for a
	 *         templar. Masteries are taught as late as level fifty, so "does not have it" is no answer at all; "will never be taught it" is.
	 */
	public static boolean willNeverMaster(Player bot, ItemTemplate template) {
		Set<Integer> mastery = DataManager.SKILL_DATA.getMasterySkills(template.getItemGroup());
		if (mastery.isEmpty())
			return false; // nothing to master, anyone may wear it
		for (int skillId : mastery) {
			if (bot.getSkillList().isSkillPresent(skillId))
				return false;
			if (!DataManager.SKILL_TREE_DATA.getTemplatesForSkill(skillId, bot.getPlayerClass(), bot.getRace()).isEmpty())
				return false; // it comes with a later level
		}
		return true;
	}

	private static long firstSlot(long mask) {
		ItemSlot[] slots = ItemSlot.getSlotsFor(mask);
		return slots.length == 0 ? 0 : slots[0].getSlotIdMask();
	}

	/**
	 * Starts reading an unidentified piece that would be an upgrade.
	 * <p>
	 * A piece that rolls bonus stats drops unidentified, and unidentified is unwearable — which is why the first version of this quietly wore
	 * nothing at all. Reading one costs no scroll, only five seconds of standing perfectly still: the observer watching those seconds gives up on
	 * movement, on a blow landed or taken, on a cast, even on sitting down. Picking a quiet moment is therefore the caller's job; this only says
	 * whether there is anything worth reading.
	 *
	 * @return true if a reading was started, during which the bot must be left alone.
	 */
	public static boolean identifyUpgrade(Player bot) {
		if (isReading(bot))
			return true;
		List<Item> unread = upgrades(bot, false);
		if (unread.isEmpty())
			return false;
		// the judgement holds unread: identifying rolls bonus stats without touching the level or the quality it was judged on
		ItemActionService.identifyItem(bot, unread.get(0));
		return true;
	}

	/** @return true if there is anything in the bag worth putting on, read or not. Lets the caller decide to make room for it before trying. */
	public static boolean hasUpgradeWaiting(Player bot) {
		return !upgrades(bot, true).isEmpty() || !upgrades(bot, false).isEmpty();
	}

	/** @return true while a reading or a binding is under way, five seconds the bot must spend doing nothing else. */
	public static boolean isBusyDressing(Player bot) {
		return isReading(bot);
	}

	/**
	 * {@code hasScheduledTask}, not {@code hasTask}: the latter only asks whether that slot was ever filled, and nothing empties it when the task
	 * finishes, so a bot that read one thing would have stood there claiming to be busy for the rest of its life.
	 */
	private static boolean isReading(Player bot) {
		return bot.getController().hasScheduledTask(TaskId.ITEM_USE);
	}

	/** @return Every piece in the bag that beats what is worn in its place, among those already read or not. */
	private static List<Item> upgrades(Player bot, boolean identified) {
		List<Item> found = new ArrayList<>();
		for (Item candidate : bot.getInventory().getItems()) {
			if (!isGear(candidate) || candidate.isEquipped() || candidate.isIdentified() != identified)
				continue;
			// A piece made for npcs asks level one of every class while being level sixty five, and sits in a generic group that needs no mastery, so
			// neither the engine nor the score would ever turn it down. Judged on what it is rather than on what it asks for.
			ItemTemplate template = candidate.getItemTemplate();
			if (!isPlayerGear(template) || template.getLevel() > bot.getLevel())
				continue;
			Item worn = wornInPlaceOf(bot, candidate);
			// A weapon is only ever weighed against a weapon, and armour against armour. Slots overlap in ways that make a free-for-all dangerous:
			// a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon
			// would not win it back. Anything finer than that — insisting on the same kind of armour, say — would block the ordinary case, since
			// levelling gear crosses from leather to cloth and back with whatever drops.
			if (worn != null && (worn.getItemTemplate().isWeapon() != candidate.getItemTemplate().isWeapon() || score(worn) >= score(candidate)))
				continue;
			found.add(candidate);
		}
		return found;
	}

	/** Weapons and armour, which here includes the accessories: stigmas and everything else are not this rule's business. */
	private static boolean isGear(Item item) {
		ItemTemplate template = item.getItemTemplate();
		return template.getItemSlot() != 0 && (template.isWeapon() || template.isArmor());
	}

	/**
	 * @return What the bot already wears where this would go, or null when the place is free. Two-slot pieces such as rings match whichever is found
	 *         first, so an upgrade over the lesser of a pair is sometimes missed — it is picked up the next time something better drops.
	 */
	private static Item wornInPlaceOf(Player bot, Item candidate) {
		long slot = candidate.getItemTemplate().getItemSlot();
		for (Item worn : bot.getEquipment().getEquippedItemsWithoutStigma()) {
			if ((worn.getEquipmentSlot() & slot) != 0)
				return worn;
		}
		return null;
	}

	/**
	 * How good a piece is, as a levelling character would judge it: its level first, its quality to separate two of the same level.
	 * <p>
	 * This is a proxy and says so. Comparing what items actually grant would mean weighing attack against defence against a resistance, which has no
	 * answer that holds for every class. Over the levelling range the item level carries nearly all of the difference, and being wrong between a
	 * heroic and a superior of the same level costs a bot very little.
	 */
	private static int score(Item item) {
		ItemTemplate template = item.getItemTemplate();
		return template.getLevel() * 10 + template.getItemQuality().getQualityId();
	}
}
