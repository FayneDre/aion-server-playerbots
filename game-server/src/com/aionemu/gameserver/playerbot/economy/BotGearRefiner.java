package com.aionemu.gameserver.playerbot.economy;

import java.util.List;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ManaStone;
import com.aionemu.gameserver.model.stats.calc.functions.StatFunction;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.model.stats.listeners.ItemEquipmentListener;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.EnchantService;
import com.aionemu.gameserver.services.item.ItemSocketService;

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

	/**
	 * How likely a piece is to have its slots filled at all, by its owner's level. A character of twenty has a few stones and spends them on what it
	 * is wearing this week; one at the ceiling has had years to fill everything. Straight from the source document.
	 */
	private static int socketChanceAt(int level) {
		if (level >= 65)
			return 100;
		if (level > 40)
			return 75;
		if (level >= 20)
			return 50;
		return 10;
	}

	private BotGearRefiner() {
	}

	/**
	 * Fills a piece's manastone slots, the way somebody playing would: one stat stacked across the slots rather than a different stone in each.
	 * <p>
	 * Stacking is not laziness, it is how the game is played — six crit stones in a weapon, not one of each — and the one thing that interrupts it is
	 * the crit cap, at which point the stacking moves to whatever the class wants next.
	 *
	 * @return How many stones went in.
	 */
	public static int socket(Player bot, Item item) {
		// The item's own count rather than the template's, because a tuned piece carries sockets its template never declared — and it answers the
		// accessories for free, exactly as the enchant ceiling does: no accessory in the table declares a slot, so in this build they take no stones
		// either.
		if (item.getSockets(false) <= 0 || Rnd.get(1, 100) > socketChanceAt(bot.getLevel()))
			return 0;
		List<ItemTemplate> stones = stonesFor(bot, item);
		if (stones.isEmpty())
			return 0;
		int socketed = 0, critPromised = 0;
		// Until the engine refuses, which is the only honest way to count the room left: special slots are reserved at the front for ancient stones
		// and {@code addManaStone} does that accounting itself, so a loop that worked it out here would be a second copy of its rules.
		while (true) {
			ItemTemplate stone = bestStone(bot, stones, critPromised);
			if (stone == null)
				break;
			ManaStone added = ItemSocketService.addManaStone(item, stone.getTemplateId(), false);
			if (added == null)
				break;
			critPromised += grantOf(stone, StatEnum.PHYSICAL_CRITICAL);
			socketed++;
			// A piece that is already worn needs telling, exactly as the player-facing socketing does: the stats of a stone are applied when the
			// piece goes on, and this one went on before the stone did.
			if (item.isEquipped())
				ItemEquipmentListener.addStoneStats(item, added, bot.getGameStats());
		}
		if (socketed > 0 && item.isEquipped())
			bot.getGameStats().updateStatsAndSpeedVisually();
		return socketed;
	}

	/**
	 * @return The stones a character of this level could have put in this piece.
	 *         <p>
	 *         Two ceilings, and the lower wins. The engine's own is
	 *         {@code 10 × ceil((item level + 10) / 10)} and is generous — it would allow a level 50 stone in a level 36 torso. The real limit is that
	 *         a character has the stones its own levels have given it, so the stone level is also capped at the owner's level rounded down to the ten
	 *         it belongs to, which is the only granularity stones come in: 10, 20, 30, 40, 50, 60, 70.
	 */
	private static List<ItemTemplate> stonesFor(Player bot, Item item) {
		int allowedByItem = 10 * (int) Math.ceil((item.getItemTemplate().getLevel() + 10) / 10d);
		int ownedByCharacter = bot.getLevel() / 10 * 10;
		for (int level = Math.min(allowedByItem, ownedByCharacter); level >= 10; level -= 10) {
			List<ItemTemplate> stones = DataManager.ITEM_DATA.getManastones(level);
			if (stones != null && !stones.isEmpty())
				return stones;
		}
		return List.of();
	}

	/**
	 * @return The stone this class wants most, or null when none of them is worth anything to it. Crit stones drop out once the character has as much
	 *         crit as the game will pay it for, which is what makes a weapon come out as crit up to the cap and attack after it.
	 */
	private static ItemTemplate bestStone(Player bot, List<ItemTemplate> stones, int critPromised) {
		boolean critIsWasted = BotStatWeights.physicalCritIsSaturated(bot, critPromised);
		ItemTemplate best = null;
		float bestScore = 0;
		for (ItemTemplate stone : stones) {
			if (critIsWasted && grantOf(stone, StatEnum.PHYSICAL_CRITICAL) > 0)
				continue;
			float score = BotStatWeights.score(bot, stone);
			if (score > bestScore) {
				bestScore = score;
				best = stone;
			}
		}
		return best;
	}

	/** @return How much of one stat a stone grants. Stones carry a single modifier, so this is usually its only one. */
	private static int grantOf(ItemTemplate stone, StatEnum stat) {
		List<StatFunction> modifiers = stone.getModifiers();
		if (modifiers == null)
			return 0;
		for (StatFunction modifier : modifiers) {
			if (modifier.getName() == stat)
				return modifier.getValue();
		}
		return 0;
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
