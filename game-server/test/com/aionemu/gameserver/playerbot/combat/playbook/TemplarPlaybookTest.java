package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Situation;

/**
 * The guide's thresholds, one case each. They are a pure function of a {@link Situation}, so a number it wants changed is a number changed here first.
 */
class TemplarPlaybookTest {

	private static final Set<Move> EVERYTHING = EnumSet.allOf(Move.class);

	private static Situation at(int level, int hp, int dp, boolean armorUp, Set<Move> ready) {
		return new Situation(level, hp, dp, armorUp, ready);
	}

	@Test
	void handOfHealingOnlyInACriticalSituationWithTheDivinePowerToPayForIt() {
		assertEquals(HAND_OF_HEALING, TemplarPlaybook.decide(at(40, 19, 2000, false, EVERYTHING)));
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(at(40, 20, 2000, false, EVERYTHING)), "twenty is not under twenty");
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(at(40, 10, 1999, false, EVERYTHING)), "one point short of the cost");
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(at(40, 10, 2000, false, EnumSet.complementOf(EnumSet.of(HAND_OF_HEALING)))), "on cooldown");
	}

	@Test
	void empyreanArmorBelowThreeQuartersOfTheHealth() {
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(at(40, 74, 0, false, EVERYTHING)));
		assertNull(TemplarPlaybook.decide(at(40, 75, 0, false, EVERYTHING)));
	}

	@Test
	void ironSkinWhenTheArmorIsNeitherReadyNorUp() {
		Set<Move> armorOnCooldown = EnumSet.of(IRON_SKIN);
		assertEquals(IRON_SKIN, TemplarPlaybook.decide(at(40, 49, 0, false, armorOnCooldown)));
		assertNull(TemplarPlaybook.decide(at(40, 50, 0, false, armorOnCooldown)), "fifty is not under fifty");
		assertNull(TemplarPlaybook.decide(at(40, 30, 0, true, armorOnCooldown)), "Armor's own buff is carrying the templar, which is what Iron Skin is kept for");
	}

	@Test
	void armorComesBeforeIronSkinWhenBothAreReady() {
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(at(40, 40, 0, false, EnumSet.of(EMPYREAN_ARMOR, IRON_SKIN))));
	}

	@Test
	void theUltimateHealComesBeforeEverythingElseOnceItApplies() {
		assertEquals(HAND_OF_HEALING, TemplarPlaybook.decide(at(40, 10, 2500, false, EVERYTHING)));
	}

	@Test
	void chastisementUsesTheDivinePowerUpToLevelThirtyAndKeepsItAfter() {
		Set<Move> onlyChastisement = EnumSet.of(EMPYREAN_CHASTISEMENT);
		assertEquals(EMPYREAN_CHASTISEMENT, TemplarPlaybook.decide(at(10, 100, 2000, false, onlyChastisement)));
		assertEquals(EMPYREAN_CHASTISEMENT, TemplarPlaybook.decide(at(30, 100, 2000, false, onlyChastisement)));
		assertNull(TemplarPlaybook.decide(at(31, 100, 4000, false, onlyChastisement)), "from 31 the points are for Hand of Healing");
		assertNull(TemplarPlaybook.decide(at(20, 100, 1999, false, onlyChastisement)), "not enough to pay for it");
	}

	@Test
	void aHealthyTemplarWithNothingReadyHasNothingToSay() {
		assertNull(TemplarPlaybook.decide(at(40, 100, 0, false, EnumSet.noneOf(Move.class))));
		assertNull(TemplarPlaybook.decide(at(40, 100, 5000, false, EnumSet.of(HAND_OF_HEALING, EMPYREAN_ARMOR, IRON_SKIN))));
	}
}
