package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.skillengine.model.DispelCategoryType;

/**
 * Which weakening a cleanse reaches, as {@code EffectController.removeEffectByDispelCat} decides it.
 */
class CleanseReachTest {

	@Test
	void aGeneralCleanseReachesBothKinds() {
		assertTrue(CleanseReach.ALL.reaches(DispelCategoryType.ALL));
		assertTrue(CleanseReach.ALL.reaches(DispelCategoryType.DEBUFF_MENTAL));
		assertTrue(CleanseReach.ALL.reaches(DispelCategoryType.DEBUFF_PHYSICAL));
	}

	@Test
	void aPhysicalCleanseLeavesASleepAlone() {
		assertTrue(CleanseReach.PHYSICAL.reaches(DispelCategoryType.ALL), "an effect open to every dispel");
		assertTrue(CleanseReach.PHYSICAL.reaches(DispelCategoryType.DEBUFF_PHYSICAL));
		assertFalse(CleanseReach.PHYSICAL.reaches(DispelCategoryType.DEBUFF_MENTAL));
		assertFalse(CleanseReach.MENTAL.reaches(DispelCategoryType.DEBUFF_PHYSICAL));
	}

	@Test
	void noCleanseReachesABuffOrAStun() {
		for (CleanseReach reach : CleanseReach.values()) {
			assertFalse(reach.reaches(DispelCategoryType.BUFF), reach + " is a debuff dispel");
			assertFalse(reach.reaches(DispelCategoryType.STUN), reach + " leaves a stun to Remove Shock");
		}
	}
}
