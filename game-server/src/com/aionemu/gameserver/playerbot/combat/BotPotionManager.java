package com.aionemu.gameserver.playerbot.combat;

import java.util.function.Predicate;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.model.templates.item.actions.AbstractItemAction;
import com.aionemu.gameserver.model.templates.item.actions.ItemActions;
import com.aionemu.gameserver.model.templates.item.actions.SkillUseAction;
import com.aionemu.gameserver.restrictions.PlayerRestrictions;
import com.aionemu.gameserver.skillengine.change.Change;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.effect.StatupEffect;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * Drinking, which until now no bot did. A class with no heal of its own had nothing between being hurt and sitting down, and healers spent their
 * last mana without ever reaching for the flask that would have given it back.
 */
public class BotPotionManager {

	/**
	 * What a flask does, as the data says it rather than as the name suggests.
	 * <p>
	 * The {@code PROC} variants are the whole point and were the whole bug: every drinkable in the game restores through
	 * {@code procmphealinstant} or {@code prochealinstant} — a hundred and a hundred and forty six skills respectively — while
	 * {@code MPHEAL} and {@code HEALINSTANT} belong to the spells a healer casts. Written with the spell effects alone, this class could never match
	 * a potion, so no bot ever drank one, including the ones it had picked up itself. Both are kept: a flask that heals the way a spell does is still
	 * a flask.
	 */
	public static final EffectType[] RESTORES_MANA = { EffectType.MPHEAL, EffectType.MPHEALINSTANT, EffectType.PROCMPHEALINSTANT };
	public static final EffectType[] RESTORES_HEALTH = { EffectType.HEAL, EffectType.HEALINSTANT, EffectType.PROCHEALINSTANT };
	/** What a wind serum does: flight points back, through the same {@code PROC} variant the flasks use. */
	public static final EffectType[] RESTORES_FLIGHT = { EffectType.FPHEAL, EffectType.FPHEALINSTANT, EffectType.PROCFPHEALINSTANT };

	private BotPotionManager() {
	}

	/** @return true if the bot drank something that restores health, in which case it is busy with it. */
	public static boolean tryHealingPotion(Player bot, int belowPercent) {
		if (bot.getLifeStats().getHpPercentage() >= belowPercent)
			return false;
		return drink(bot, RESTORES_HEALTH);
	}

	/** @return true if the bot drank something that restores mana. */
	public static boolean tryManaPotion(Player bot, int belowPercent) {
		if (bot.getLifeStats().getMpPercentage() >= belowPercent)
			return false;
		return drink(bot, RESTORES_MANA);
	}

	/**
	 * Drinks a wind serum in the air, which a player does without thinking and no bot ever did: a bot following somebody came down on its last
	 * points, wherever it was, while the player beside it drank and flew on.
	 *
	 * @return true if the bot drank one.
	 */
	public static boolean tryFlightPotion(Player bot, int belowPercent) {
		int max = bot.getLifeStats().getMaxFp();
		if (max <= 0 || bot.getLifeStats().getCurrentFp() * 100 / max >= belowPercent)
			return false;
		return drink(bot, RESTORES_FLIGHT);
	}

	/**
	 * Reads a scroll that raises one stat, unless something already does: a running scroll to catch up with a group, a raging wind scroll before a
	 * flight, a courage or awakening scroll before a fight. Every character is born with fifty each of three kinds, and no bot had ever read one.
	 * <p>
	 * "Already does" is asked of the effects on the bot and not of the scroll's own stack, because the event scrolls a character starts with and the
	 * ordinary ones raise the same stat under different stack names: reading the second over the first wastes it.
	 *
	 * @return true if the bot read one.
	 */
	public static boolean tryScroll(Player bot, StatEnum stat) {
		for (Effect effect : bot.getEffectController().getAbnormalEffects()) {
			if (changes(effect.getSkillTemplate(), stat))
				return false;
		}
		return drink(bot, skill -> changes(skill, stat));
	}

	/** @return true if the skill is a buff that changes this stat, whichever way the stat counts as better: attack speed is better lower. */
	private static boolean changes(SkillTemplate skill, StatEnum stat) {
		if (skill == null || skill.getEffects() == null || skill.getEffects().getEffects() == null)
			return false;
		for (EffectTemplate effect : skill.getEffects().getEffects()) {
			if (!(effect instanceof StatupEffect) || effect.getChange() == null)
				continue;
			for (Change change : effect.getChange()) {
				if (change.getStat() == stat)
					return true;
			}
		}
		return false;
	}

	/**
	 * Uses the first thing in the bag whose effect matches, mirroring what the client packet does and nothing more.
	 * <p>
	 * Nothing here decides whether the item may be used. {@code PlayerRestrictions.canUseItem} and the action's own {@code canAct} between them
	 * already weigh the prison, the abnormal states, the map, the cooldown and whether the potion would even do anything at full health — and
	 * {@code canUseItem} only became reachable for a bot once {@code isOnline()} stopped meaning "has a socket".
	 */
	private static boolean drink(Player bot, EffectType... effects) {
		return drink(bot, skill -> skill.hasAnyEffect(effects));
	}

	private static boolean drink(Player bot, Predicate<SkillTemplate> wanted) {
		if (bot.getLifeStats().isDead() || bot.isCasting())
			return false;
		for (Item item : bot.getInventory().getItems()) {
			if (!grants(item, wanted) || !PlayerRestrictions.canUseItem(bot, item))
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
	private static boolean grants(Item item, Predicate<SkillTemplate> wanted) {
		ItemActions actions = item.getItemTemplate().getActions();
		if (actions == null)
			return false;
		for (AbstractItemAction action : actions.getItemActions()) {
			if (!(action instanceof SkillUseAction skillUse))
				continue;
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(skillUse.getSkillId());
			if (template != null && wanted.test(template))
				return true;
		}
		return false;
	}
}
