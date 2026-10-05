package com.aionemu.gameserver.playerbot.economy;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.EnchantService;

/**
 * Brings a piece of gear up to the state a character of this level would have got it into.
 * <p>
 * A player does not wear a piece as it dropped. Enchanting is the ordinary business of levelling — stones are common, the attempt is cheap at low
 * levels, and a character arrives at forty with most of its gear several levels up — and the stats it adds are not a rounding error: a plastron gains
 * ninety physical defence and a hundred and twenty health between +0 and +15. A bot in unenchanted gear is wearing about two thirds of a character.
 * <p>
 * None of it has to be simulated. The level is a field on the item, the engine applies the stats when the piece goes on, and there is no stone to
 * buy and no attempt to fail. What a bot would have spent hours doing is a number.
 */
public class BotGearRefiner {

	/**
	 * How far a piece may be off the character's own level and still be the one it enchanted. A level 40 templar wearing a level 36 torso has had it
	 * since 36 and has had four levels of stones to put into it, so about +4 — then two either way, because a world where every character's gear is
	 * exactly its level minus the item's level is a spreadsheet, not a population.
	 */
	private static final int SPREAD = 2;

	private BotGearRefiner() {
	}

	/**
	 * Enchants a piece to where this character would have got it.
	 * <p>
	 * Never downwards. A looted piece that already carries more than this would have given it keeps what it has, because taking it away would be the
	 * bot destroying its own property to match a formula.
	 *
	 * @return true if the piece ended up better than it was.
	 */
	public static boolean refine(Player bot, Item item) {
		int level = enchantLevelFor(bot, item.getItemTemplate());
		if (level <= item.getEnchantLevel())
			return false;
		// The service and not {@code Item.setEnchantLevel}, which only writes the field. This ends the old effect, applies the new stats when the
		// piece is already worn, and marks the right store dirty — all three of which matter, because this is called both on a piece going on and on
		// a piece that has been worn for weeks.
		EnchantService.setEnchantLevel(bot, item, level);
		return true;
	}

	/**
	 * @return What this character would have enchanted this piece to, or 0 for a piece that cannot be enchanted at all.
	 *         <p>
	 *         The ceiling the item itself declares is the one that matters, and it answers the awkward cases for free: <b>every accessory in the
	 *         table declares none</b>, so rings, earrings, necklaces, belts and head pieces come out 0 without needing to be named here — they are
	 *         not enchanted in this game, they are socketed. Ordinary gear declares 10 or 15.
	 */
	private static int enchantLevelFor(Player bot, ItemTemplate template) {
		int ceiling = template.getMaxEnchantLevel();
		if (ceiling <= 0 || template.isNoEnchant())
			return 0;
		// How long the character has been wearing it, which is the only honest reading of how much it has put into it.
		int levelsOwned = bot.getLevel() - template.getLevel();
		return Math.clamp(levelsOwned + Rnd.get(-SPREAD, SPREAD), 0, ceiling);
	}
}
