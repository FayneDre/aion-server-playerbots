package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.actions.AbstractItemAction;
import com.aionemu.gameserver.model.templates.item.actions.ItemActions;
import com.aionemu.gameserver.model.templates.item.actions.SkillUseAction;
import com.aionemu.gameserver.restrictions.PlayerRestrictions;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * Drinking, which until now no bot did. A class with no heal of its own had nothing between being hurt and sitting down, and healers spent their
 * last mana without ever reaching for the flask that would have given it back.
 */
public class BotPotionManager {

	private BotPotionManager() {
	}

	/** @return true if the bot drank something that restores health, in which case it is busy with it. */
	public static boolean tryHealingPotion(Player bot, int belowPercent) {
		if (bot.getLifeStats().getHpPercentage() >= belowPercent)
			return false;
		return drink(bot, EffectType.HEAL, EffectType.HEALINSTANT);
	}

	/** @return true if the bot drank something that restores mana. */
	public static boolean tryManaPotion(Player bot, int belowPercent) {
		if (bot.getLifeStats().getMpPercentage() >= belowPercent)
			return false;
		return drink(bot, EffectType.MPHEAL, EffectType.MPHEALINSTANT);
	}

	/**
	 * Uses the first thing in the bag whose effect matches, mirroring what the client packet does and nothing more.
	 * <p>
	 * Nothing here decides whether the item may be used. {@code PlayerRestrictions.canUseItem} and the action's own {@code canAct} between them
	 * already weigh the prison, the abnormal states, the map, the cooldown and whether the potion would even do anything at full health — and
	 * {@code canUseItem} only became reachable for a bot once {@code isOnline()} stopped meaning "has a socket".
	 */
	private static boolean drink(Player bot, EffectType... effects) {
		if (bot.getLifeStats().isDead() || bot.isCasting())
			return false;
		for (Item item : bot.getInventory().getItems()) {
			if (!grants(item, effects) || !PlayerRestrictions.canUseItem(bot, item))
				continue;
			for (AbstractItemAction action : item.getItemTemplate().getActions().getItemActions()) {
				// only the skill it casts: a potion has nothing else, and refusing to run anything else keeps this from firing off a scroll or a
				// transformation the moment one shares a bag with a flask
				if (action instanceof SkillUseAction && useOnItself(bot, item, action))
					return true;
			}
		}
		return false;
	}

	/**
	 * Uses an item on the bot rather than on whatever it happens to be looking at.
	 * <p>
	 * {@link SkillUseAction} reads {@code player.getTarget()} twice — once to decide whether the item may be used at all, once to decide who it
	 * lands on — because for a real player the target is the answer to both. A bot's target is its quarry in a fight and its ally out of one, so a
	 * flask drunk because the bot is dying went to the mob or to the group mate, and one drunk beside somebody at full health was refused outright as
	 * pointless. Aiming at itself first is what a player does by using the item with nothing selected.
	 * <p>
	 * The previous target is put back only if nothing else claimed it meanwhile: the decision tick can start or end a fight while this runs, and its
	 * choice is newer than ours.
	 */
	private static boolean useOnItself(Player bot, Item item, AbstractItemAction action) {
		VisibleObject aimedAt = bot.getTarget();
		bot.setTarget(bot);
		try {
			if (!action.canAct(bot, item, null))
				return false;
			action.act(bot, item, null);
			return true;
		} finally {
			if (bot.getTarget() == bot)
				bot.setTarget(aimedAt);
		}
	}

	/** @return true if using this item casts a skill with one of those effects, which is what a potion is. */
	private static boolean grants(Item item, EffectType... effects) {
		ItemActions actions = item.getItemTemplate().getActions();
		if (actions == null)
			return false;
		for (AbstractItemAction action : actions.getItemActions()) {
			if (!(action instanceof SkillUseAction skillUse))
				continue;
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(skillUse.getSkillId());
			if (template != null && template.hasAnyEffect(effects))
				return true;
		}
		return false;
	}
}
