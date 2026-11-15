package com.aionemu.gameserver.playerbot.combat;

import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.DispelCategoryType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * Which weakening effects a cleanse reaches, sorted the way the engine sorts them when it is cast ({@code EffectController.removeEffectByDispelCat}).
 * <p>
 * A cleric's Cleanse and a chanter's Energy Discharge are general dispels and reach both kinds; a bard's Purifying Paean reaches only the physical ones.
 * Asked as one, a bard would cleanse a sleep it cannot touch every time its song came back, and the effects it could not touch would be written down as
 * resisted, so that a cleric beside it left them alone too.
 */
public enum CleanseReach {

	/** A general debuff dispel ({@code dispeldebuff}), or one carrying both kinds. */
	ALL,
	/** {@code dispeldebuffmental} alone. */
	MENTAL,
	/** {@code dispeldebuffphysical} alone. */
	PHYSICAL;

	/** @return true if a cleanse of this reach can remove an effect of that category, the level and the power aside. */
	public boolean reaches(DispelCategoryType category) {
		if (category == DispelCategoryType.ALL)
			return true;
		return switch (this) {
			case ALL -> category == DispelCategoryType.DEBUFF_MENTAL || category == DispelCategoryType.DEBUFF_PHYSICAL;
			case MENTAL -> category == DispelCategoryType.DEBUFF_MENTAL;
			case PHYSICAL -> category == DispelCategoryType.DEBUFF_PHYSICAL;
		};
	}

	/** @return What a cleanse skill reaches, or null for a skill that cleanses nothing. */
	public static CleanseReach of(SkillTemplate template) {
		boolean general = template.hasAnyEffect(EffectType.DISPELDEBUFF);
		boolean mental = template.hasAnyEffect(EffectType.DISPELDEBUFFMENTAL);
		boolean physical = template.hasAnyEffect(EffectType.DISPELDEBUFFPHYSICAL);
		if (general || mental && physical)
			return ALL;
		if (mental)
			return MENTAL;
		return physical ? PHYSICAL : null;
	}
}
