package com.aionemu.gameserver.playerbot.economy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.actions.AbstractItemAction;
import com.aionemu.gameserver.model.templates.item.actions.CraftLearnAction;
import com.aionemu.gameserver.model.templates.item.actions.DecomposeAction;
import com.aionemu.gameserver.model.templates.item.actions.ExpExtractAction;
import com.aionemu.gameserver.model.templates.item.actions.SkillLearnAction;
import com.aionemu.gameserver.model.templates.item.actions.SkillUseAction;
import com.aionemu.gameserver.model.templates.recipe.RecipeTemplate;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.HealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.MPHealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.ProcHealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.ProcMPHealInstantEffect;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * What the data says of a template that the broker's rules ask (docs/capitals/plans/auction-house-plan.md): whether it is a sub category of the client's
 * broker, whether a craft makes it, how much a potion restores. Each answer is read from the item data once and kept.
 */
final class BrokerHouseCatalog {

	private static final Map<Long, Boolean> IN_SUB_CATEGORY = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> POTION_POINTS = new ConcurrentHashMap<>();
	private static volatile Set<Integer> crafted;

	private BrokerHouseCatalog() {
	}

	/** @return true if the client's broker shows the template under that sub category (a scroll, a potion, the enchantment stones...). */
	static boolean isShownIn(ItemTemplate template, BrokerItemMask subCategory) {
		return IN_SUB_CATEGORY.computeIfAbsent((long) template.getTemplateId() * 100 + subCategory.ordinal(), key -> subCategory.isMatches(new Item(0, template)));
	}

	/** @return true if one of the actions of the template is of that kind. */
	static boolean hasAction(ItemTemplate template, Class<? extends AbstractItemAction> kind) {
		return template.getActions() != null && template.getActions().getItemActions().stream().anyMatch(kind::isInstance);
	}

	/** @return true for a box or a bundle that opens into other items, and for an item that turns experience into another: not goods between players. */
	static boolean isBundle(ItemTemplate template) {
		return hasAction(template, DecomposeAction.class) || hasAction(template, ExpExtractAction.class);
	}

	/** @return true for a skill book: the skills are learned by themselves since 4.8, so what is left are relics of older versions. */
	static boolean isSkillBook(ItemTemplate template) {
		return hasAction(template, SkillLearnAction.class);
	}

	/** @return true for a design: the item that teaches a recipe. */
	static boolean isDesign(ItemTemplate template) {
		return hasAction(template, CraftLearnAction.class);
	}

	/** @return true if a recipe of the data makes the template, which is what a player can make of a scroll. */
	static boolean isCrafted(ItemTemplate template) {
		Set<Integer> found = crafted;
		if (found == null && DataManager.RECIPE_DATA != null) {
			found = new HashSet<>();
			for (RecipeTemplate recipe : DataManager.RECIPE_DATA.getRecipeTemplates())
				if (recipe.getProductId() != null)
					found.add(recipe.getProductId());
			crafted = found = Set.copyOf(found);
		}
		return found != null && found.contains(template.getTemplateId());
	}

	/** @return The health and mana points a potion restores in all, 0 for anything else. A percentage heal counts as its percent. */
	static long potionPoints(ItemTemplate template) {
		return POTION_POINTS.computeIfAbsent(template.getTemplateId(), id -> restoredBy(template));
	}

	private static long restoredBy(ItemTemplate template) {
		if (template.getActions() == null || DataManager.SKILL_DATA == null)
			return 0;
		long points = 0;
		for (AbstractItemAction action : template.getActions().getItemActions()) {
			if (!(action instanceof SkillUseAction use))
				continue;
			SkillTemplate skill = DataManager.SKILL_DATA.getSkillTemplate(use.getSkillId());
			if (skill == null || skill.getEffects() == null || skill.getEffects().getEffects() == null)
				continue;
			for (EffectTemplate effect : skill.getEffects().getEffects()) {
				if (effect instanceof HealInstantEffect || effect instanceof MPHealInstantEffect || effect instanceof ProcHealInstantEffect
					|| effect instanceof ProcMPHealInstantEffect)
					points += Math.max(0, effect.getValue());
			}
		}
		return points;
	}
}
