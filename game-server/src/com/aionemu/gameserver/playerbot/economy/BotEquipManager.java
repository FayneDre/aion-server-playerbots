package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_PLAYER_APPEARANCE;
import com.aionemu.gameserver.services.item.ItemActionService;
import com.aionemu.gameserver.skillengine.effect.WeaponDualEffect;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.idfactory.IDFactory;

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
	 * Throws away a piece that was created and then refused, and gives its object id back.
	 * <p>
	 * The giving back is the part that is not obvious and cannot be left out. {@code Item.setPersistentState} turns a {@code DELETED} on a
	 * {@code NEW} item into {@code NOACTION} — correct, since something never written has nothing to delete — but the consequence is that the item
	 * never reaches {@code InventoryDAO.deleteItems}, which is the only place object ids are released. So every refused piece leaked one, for as long
	 * as the server was up. It did not matter while nothing was ever discarded; it matters now that dressing tries a whole shortlist and that stigmas
	 * are offered on every spawn.
	 * <p>
	 * The state is asked rather than assumed, because a save sweep runs on its own thread and may have written the item in between. If it did, the
	 * state is {@code DELETED}, the database will remove it and release the id, and releasing it here as well would be a double release.
	 */
	public static void discard(Player bot, Item item) {
		bot.getInventory().delete(item);
		if (item.getPersistentState() == PersistentState.NOACTION)
			IDFactory.getInstance().releaseId(item.getObjectId());
	}

	/**
	 * Puts one piece on, in one place, and lets everyone watching see it.
	 *
	 * @return true if it went on.
	 */
	public static boolean wear(Player bot, int itemObjectId, ItemTemplate template) {
		long slot = slotFor(bot, template);
		if (slot == 0)
			return false; // asked before equipping and not alongside it: a mask of no slots is removed from the bag and worn nowhere
		Item worn = bot.getEquipment().equipItem(itemObjectId, slot);
		if (worn == null)
			return false;
		// The one place everything a bot puts on goes through, which is why the enchanting hangs here rather than in the dressing: a piece created
		// for a new character and a piece looted an hour later both deserve to be in the state its owner would have got it into, and hanging it off
		// the creation would have left every looted replacement at +0 — a visible downgrade from the gear it was replacing.
		// Not for a player's own character. It may put on a piece it found when that piece is better than what it wears, which is the dressing, but it is
		// never enchanted or socketed: that is the owner's to do. A bot being created has no ai yet, so it is not taken for owned here.
		if (!(bot.getAi() instanceof PlayerBotAI ai && ai.isOwned())) {
			BotGearRefiner.refine(bot, worn);
			BotGearRefiner.socket(bot, worn);
		}
		showAppearance(bot);
		return true;
	}

	/**
	 * Tells every client in range what the bot now looks like.
	 * <p>
	 * This is what {@code CM_EQUIP_ITEM} does after a successful equip, and leaving it out is how a village of bots came to be seen in the gear they
	 * were created with: the piece is worn as far as the server is concerned and invisible to everyone watching. Taking a piece <b>off</b> needs the
	 * same word said, which is less obvious — a bot stripped of the wrong faction's armour otherwise goes on wearing it in every onlooker's client.
	 */
	public static void showAppearance(Player bot) {
		PacketSendUtility.broadcastPacket(bot, new SM_UPDATE_PLAYER_APPEARANCE(bot.getObjectId(), bot.getEquipment().getEquippedForAppearance()),
			true);
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
		if (template.isWeapon()) {
			long main = ItemSlot.MAIN_HAND.getSlotIdMask(), sub = ItemSlot.SUB_HAND.getSlotIdMask();
			if ((mask & main) == 0)
				return firstSlot(mask);
			// The off hand, but only once the main hand is full and only for a class trained to wield two. Both halves matter: without the first the
			// bot puts its best weapon in the wrong hand and fights with the spare, and without the second the engine quietly moves the request back
			// to the main hand — equipItem does that itself, with no message — so the second weapon replaced the first and an assassin was still
			// holding one dagger. Anything else gets nothing here, which leaves the hand free for the shield it is meant to carry.
			if ((mask & sub) != 0 && bot.getEquipment().isSlotEquipped(main) && !bot.getEquipment().isSlotEquipped(sub)
				&& WeaponDualEffect.hasDualWieldEffect(bot))
				return sub;
			return main;
		}
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
	 * What separates them is where they come from, and {@code BotGearSources} answers that by reading the four tables a player reaches gear through.
	 * Anything in none of them falls back on the older pair of questions below, which still serves for the ordinary levelling drops that are listed
	 * nowhere.
	 * <p>
	 * The fallback is kept rather than trusted, because on its own it rejects <b>every accessory</b> and <b>the whole coin vendor tier</b> — see
	 * {@code BotGearSources} for why. It is a last resort, not the rule.
	 *
	 * @return true if this is gear a player could be handed.
	 */
	public static boolean isPlayerGear(ItemTemplate template) {
		// Asked first and as a veto, not left to the index: an event sword and a three day mace are both listed by a vendor and both declare proper
		// level restrictions, so the index would hold them and the fallback below would pass them. Reported as a level six character with a snow
		// crystal event sword and a level 43 one with a "Warhammer of Inconstancy (3 days)".
		if (!BotGearFit.isObtainable(template))
			return false;
		if (BotGearSources.isReachable(template))
			return true;
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
			// Looted gear gets the same faction check as gear a bot is created in. The engine will happily equip the other side's piece and the
			// client then draws nothing where it should be, so a bot that looted an upgrade would go about with a hole in it.
			if (BotGearSources.isOtherFaction(bot, template))
				continue;
			// The same two rules the dressing applies, which this used to leave entirely to the score — and the score only ever asks how good a piece
			// is, never whether it is the right kind of piece. A cleric that looted a staff one level newer than its mace put the staff on and threw
			// its own weapon away, and a templar did the same with leather gloves. Every class is allowed everything lighter and almost every weapon,
			// so sooner or later one drops that scores higher, and then the character fights for the rest of its life in gear it should never wear.
			if (!BotGearFit.isRightWeapon(bot, template) || !BotGearFit.isRightArmour(bot, template))
				continue;
			Item worn = wornInPlaceOf(bot, candidate);
			// A weapon is only ever weighed against a weapon, and armour against armour. Slots overlap in ways that make a free-for-all dangerous:
			// a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon
			// would not win it back. The kind of piece is settled above rather than here, so what is left to compare is genuinely comparable: two
			// pieces of the class's own armour type, or two weapons it is meant to fight with.
			if (worn != null
				&& (worn.getItemTemplate().isWeapon() != candidate.getItemTemplate().isWeapon() || score(bot, worn) >= score(bot, candidate)))
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
		ItemTemplate template = candidate.getItemTemplate();
		long slot = template.getItemSlot();
		// An empty off hand on a class that wields two is a free place, not the main hand's place. A one handed weapon reads as "either hand", so it
		// matched the weapon already held, lost the comparison to it, and the hand stayed empty for the rest of the character's life.
		if (template.isOneHandWeapon() && (slot & ItemSlot.SUB_HAND.getSlotIdMask()) != 0
			&& !bot.getEquipment().isSlotEquipped(ItemSlot.SUB_HAND.getSlotIdMask()) && WeaponDualEffect.hasDualWieldEffect(bot))
			return null;
		for (Item worn : bot.getEquipment().getEquippedItemsWithoutStigma()) {
			if ((worn.getEquipmentSlot() & slot) != 0)
				return worn;
		}
		return null;
	}

	/**
	 * How good a piece is, as a levelling character would judge it: its level first, then its quality, then what it actually grants.
	 * <p>
	 * The first two used to be the whole of it, and the comment here said why: weighing attack against defence against a resistance has no answer
	 * that holds for every class. It has one per class, which is what {@code BotStatWeights} is, so the third term is now asked — but kept strictly
	 * below the other two, because a piece's level is what says how much of everything it carries and no amount of the right stat makes a level 25
	 * ring beat a level 40 one.
	 * <p>
	 * The stat term is clamped below 10 so it can never reach into the quality digit. Scores above that would mean a piece granting ten typical
	 * pieces' worth of a class's first stat, which does not exist in the levelling range and would be a data error rather than a windfall.
	 */
	private static double score(Player bot, Item item) {
		ItemTemplate template = item.getItemTemplate();
		double stats = Math.clamp(BotStatWeights.score(bot, template), 0, 9.99);
		return template.getLevel() * 1000 + template.getItemQuality().getQualityId() * 10 + stats;
	}
}
