package com.aionemu.gameserver.playerbot.economy;

import java.util.Set;
import java.util.regex.Pattern;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Equipment;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.trade.TradeList;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.skillengine.effect.WeaponDualEffect;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * Power shards: bought at the general goods merchant with the flasks, worn in the two shard places, switched on for a fight and off after it.
 * <p>
 * The engine does the rest, as it does for a player: with the mode on, every blow adds the shard's boost to the weapon's attack, physical or magical,
 * and burns one shard per hand that struck ({@code PlayerGameStats.getPowerShardDamage}). It also equips the next stack of the same shard when one
 * runs out, and turns the mode off when none is left. Nothing of it was asked for a bot before, so none used any.
 */
public final class BotPowerShards {

	/** How many a bot carries after a purchase: a few evenings' fights at one or two shards a blow. */
	static final int SHARDS_CARRIED = 200;
	/** From when a bot buys them. The first shard of the merchants' shelves, Lesser, is level 10. */
	private static final int FROM_LEVEL = 10;
	/**
	 * The merchants' shards, by model: {@code battery_10} to {@code battery_70}. Not the test, quest, legion reward, event or abyss ones, which are
	 * {@code test_battery_}, {@code battery_q_}, {@code rmc_battery_}, {@code event_battery_} and {@code abyssrank10_battery_}.
	 */
	private static final Pattern ORDINARY = Pattern.compile("battery_\\d+", Pattern.CASE_INSENSITIVE);

	private BotPowerShards() {
	}

	static boolean isOrdinary(ItemTemplate template) {
		return template.getItemGroup() == ItemGroup.POWER_SHARDS && template.getCName() != null && ORDINARY.matcher(template.getCName()).matches();
	}

	/** @return How many ordinary shards the bot has, in its bag and in its shard places. */
	static long held(Player bot) {
		long count = 0;
		for (Item item : bot.getInventory().getItems()) {
			if (isOrdinary(item.getItemTemplate()))
				count += item.getItemCount();
		}
		for (Item worn : new Item[] { bot.getEquipment().getMainHandPowerShard(), bot.getEquipment().getOffHandPowerShard() }) {
			if (worn != null)
				count += worn.getItemCount();
		}
		return count;
	}

	/** @return true if the bot is down to half its stock, which is worth a walk to the merchant as its flasks are. */
	public static boolean needsShards(Player bot) {
		return bot.getLevel() >= FROM_LEVEL && held(bot) * 2 < SHARDS_CARRIED;
	}

	/** Puts the strongest shard on sale the bot's level may use on the order, as many as bring it back to its stock. */
	static boolean addToOrder(Player bot, TradeList order, Set<Integer> onSale) {
		if (bot.getLevel() < FROM_LEVEL)
			return false;
		ItemTemplate best = null;
		for (int itemId : onSale) {
			ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
			if (template != null && isOrdinary(template) && template.getLevel() <= bot.getLevel() && (best == null || template.getLevel() > best.getLevel()))
				best = template;
		}
		long wanted = SHARDS_CARRIED - held(bot);
		if (best == null || wanted <= 0)
			return false;
		order.addItem(best.getTemplateId(), wanted);
		return true;
	}

	/**
	 * Fills the shard places from the bag: both for a weapon in each hand or one in both, the right one only for a single one handed weapon, which is the
	 * only one the engine boosts then. On the bot's own tick, after a purchase and in the calm between fights, never as a fight begins: a fight is
	 * begun from whatever thread the blow that started it came on, and the bag and the equipment are the tick's to change.
	 */
	public static void load(Player bot) {
		Equipment equipment = bot.getEquipment();
		if (equipment.getMainHandPowerShard() == null)
			wear(bot, ItemSlot.POWER_SHARD_RIGHT);
		if (equipment.getOffHandPowerShard() == null && wantsBoth(bot))
			wear(bot, ItemSlot.POWER_SHARD_LEFT);
	}

	/** Switches the mode on as a fight begins, when shards are worn. */
	public static void switchOn(Player bot) {
		if (!bot.getEquipment().isPowerShardEquipped() || bot.isInState(CreatureState.POWERSHARD))
			return;
		bot.setState(CreatureState.POWERSHARD);
		PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.POWERSHARD_ON, 0, 0));
	}

	/** And off once it is over, as a player does so as not to burn shards on a gatherer's node or a crate. */
	public static void switchOff(Player bot) {
		if (!bot.isInState(CreatureState.POWERSHARD))
			return;
		bot.unsetState(CreatureState.POWERSHARD);
		PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.POWERSHARD_OFF, 0, 0));
	}

	private static boolean wantsBoth(Player bot) {
		Item weapon = bot.getEquipment().getMainHandWeapon();
		return weapon != null && (weapon.getItemTemplate().isTwoHandWeapon() || WeaponDualEffect.hasDualWieldEffect(bot)
			&& bot.getEquipment().getOffHandWeapon() != null);
	}

	/**
	 * Wears the strongest stack of the bag the bot may use in that place. For the left one, when the bag has none because the whole purchase went on
	 * the right, half the right's stack is taken off and put back in the bag first: a purchase arrives as one stack, and the engine moves a whole
	 * stack into a place.
	 */
	private static void wear(Player bot, ItemSlot slot) {
		if (bot.getLevel() < FROM_LEVEL)
			return;
		Item stack = strongestInBag(bot);
		Item right = bot.getEquipment().getMainHandPowerShard();
		if (stack == null && slot == ItemSlot.POWER_SHARD_LEFT && right != null && right.getItemCount() >= 2) {
			int half = (int) (right.getItemCount() / 2);
			bot.getEquipment().decreaseEquippedItemCount(right.getObjectId(), half);
			stack = bot.getInventory().add(ItemFactory.newItem(right.getItemId(), half));
		}
		if (stack != null)
			bot.getEquipment().equipItem(stack.getObjectId(), slot.getSlotIdMask());
	}

	private static Item strongestInBag(Player bot) {
		Item best = null;
		for (Item item : bot.getInventory().getItems()) {
			ItemTemplate template = item.getItemTemplate();
			if (isOrdinary(template) && template.getLevel() <= bot.getLevel() && (best == null || template.getLevel() > best.getItemTemplate().getLevel()))
				best = item;
		}
		return best;
	}
}
