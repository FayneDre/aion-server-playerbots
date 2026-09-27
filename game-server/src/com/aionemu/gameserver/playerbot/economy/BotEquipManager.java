package com.aionemu.gameserver.playerbot.economy;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.item.ItemActionService;

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
		// An identification is five seconds of standing still, watched by an observer that cancels it the moment its owner moves or fights. Claiming
		// the tick while it runs is what lets it finish.
		if (bot.getController().hasTask(TaskId.ITEM_USE))
			return true;
		for (Item candidate : bot.getInventory().getItems()) {
			if (!isGear(candidate) || candidate.isEquipped())
				continue;
			Item worn = wornInPlaceOf(bot, candidate);
			// A weapon is only ever weighed against a weapon, and armour against armour. Slots overlap in ways that make a free-for-all dangerous:
			// a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon
			// would not win it back. Anything finer than that — insisting on the same kind of armour, say — would block the ordinary case, since
			// levelling gear crosses from leather to cloth and back with whatever drops.
			if (worn != null && (worn.getItemTemplate().isWeapon() != candidate.getItemTemplate().isWeapon() || score(worn) >= score(candidate)))
				continue;
			// A piece that rolls bonus stats drops unidentified, and unidentified is unwearable. Identifying costs nothing but the five seconds:
			// the scroll a player buys is for re-rolling an identified piece, not for reading an unknown one. Only worthwhile pieces are read, and
			// the judgement holds either way, since identifying rolls bonus stats without touching the level or the quality it was judged on.
			if (!candidate.isIdentified()) {
				ItemActionService.identifyItem(bot, candidate);
				return true;
			}
			if (bot.getEquipment().equipItem(candidate.getObjectId(), candidate.getItemTemplate().getItemSlot()) != null) {
				LoggerFactory.getLogger(BotEquipManager.class).info("Bot {} puts on {}", bot.getName(), candidate.getItemTemplate().getL10n());
				return true;
			}
		}
		return false;
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
