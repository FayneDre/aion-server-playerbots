package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Situation;

/**
 * The guide's rules, one case each. They are a pure function of a {@link Situation}, so a number it wants changed is a number changed here first.
 */
class TemplarPlaybookTest {

	private static final Set<Move> EVERYTHING = EnumSet.allOf(Move.class);

	/** A templar on its own: no group, so none of the aggro rules can apply. */
	private static Situation alone(int level, int hp, int dp, boolean armorUp, Set<Move> ready) {
		return new Situation(level, hp, dp, armorUp, false, false, false, ready);
	}

	/** A templar in a group, healthy and with no divine power, so only the aggro rules have anything to say. */
	private static Situation grouped(boolean enemyInRoarRange, boolean enemyLoose, Set<Move> ready) {
		return new Situation(40, 100, 0, false, true, enemyInRoarRange, enemyLoose, ready);
	}

	@Test
	void handOfHealingOnlyInACriticalSituationWithTheDivinePowerToPayForIt() {
		assertEquals(HAND_OF_HEALING, TemplarPlaybook.decide(alone(40, 19, 2000, false, EVERYTHING)));
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(alone(40, 20, 2000, false, EVERYTHING)), "twenty is not under twenty");
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(alone(40, 10, 1999, false, EVERYTHING)), "one point short of the cost");
		assertNotEquals(HAND_OF_HEALING, TemplarPlaybook.decide(alone(40, 10, 2000, false, EnumSet.complementOf(EnumSet.of(HAND_OF_HEALING)))),
			"on cooldown");
	}

	@Test
	void empyreanArmorBelowThreeQuartersOfTheHealth() {
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(alone(40, 74, 0, false, EVERYTHING)));
		assertNull(TemplarPlaybook.decide(alone(40, 75, 0, false, EVERYTHING)));
	}

	@Test
	void ironSkinWhenTheArmorIsNeitherReadyNorUp() {
		Set<Move> armorOnCooldown = EnumSet.of(IRON_SKIN);
		assertEquals(IRON_SKIN, TemplarPlaybook.decide(alone(40, 49, 0, false, armorOnCooldown)));
		assertNull(TemplarPlaybook.decide(alone(40, 50, 0, false, armorOnCooldown)), "fifty is not under fifty");
		assertNull(TemplarPlaybook.decide(alone(40, 30, 0, true, armorOnCooldown)), "Armor's own buff is carrying the templar, which is what Iron Skin is kept for");
	}

	@Test
	void armorComesBeforeIronSkinWhenBothAreReady() {
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(alone(40, 40, 0, false, EnumSet.of(EMPYREAN_ARMOR, IRON_SKIN))));
	}

	@Test
	void theUltimateHealComesBeforeEverythingElseOnceItApplies() {
		assertEquals(HAND_OF_HEALING, TemplarPlaybook.decide(alone(40, 10, 2500, false, EVERYTHING)));
	}

	@Test
	void chastisementUsesTheDivinePowerUpToLevelThirtyAndKeepsItAfter() {
		Set<Move> onlyChastisement = EnumSet.of(EMPYREAN_CHASTISEMENT);
		assertEquals(EMPYREAN_CHASTISEMENT, TemplarPlaybook.decide(alone(10, 100, 2000, false, onlyChastisement)));
		assertEquals(EMPYREAN_CHASTISEMENT, TemplarPlaybook.decide(alone(30, 100, 2000, false, onlyChastisement)));
		assertNull(TemplarPlaybook.decide(alone(31, 100, 4000, false, onlyChastisement)), "from 31 the points are for Hand of Healing");
		assertNull(TemplarPlaybook.decide(alone(20, 100, 1999, false, onlyChastisement)), "not enough to pay for it");
	}

	@Test
	void aHealthyTemplarWithNothingReadyHasNothingToSay() {
		assertNull(TemplarPlaybook.decide(alone(40, 100, 0, false, EnumSet.noneOf(Move.class))));
		assertNull(TemplarPlaybook.decide(alone(40, 100, 5000, false, EnumSet.of(HAND_OF_HEALING, EMPYREAN_ARMOR, IRON_SKIN))));
	}

	@Test
	void theRoarEveryTimeItIsReadyWithSomethingInReach() {
		assertEquals(PROVOKING_ROAR, TemplarPlaybook.decide(grouped(true, false, EVERYTHING)));
		assertNull(TemplarPlaybook.decide(grouped(false, false, EVERYTHING)), "nobody within its radius is a cast spent on nothing");
		assertNull(TemplarPlaybook.decide(grouped(true, false, EnumSet.complementOf(EnumSet.of(PROVOKING_ROAR)))), "on cooldown");
	}

	@Test
	void theSingleTargetTauntsAreSavedForAMonsterThatHasGotLoose() {
		assertNull(TemplarPlaybook.decide(grouped(false, false, EVERYTHING)), "everything is on the templar already: nothing to peel");
		assertNotNull(TemplarPlaybook.decide(grouped(false, true, EVERYTHING)));
	}

	@Test
	void captureBeforeTauntBecauseItIsTheSlowerToComeBack() {
		assertEquals(CAPTURE, TemplarPlaybook.decide(grouped(false, true, EnumSet.of(CAPTURE, TAUNT))));
		assertEquals(TAUNT, TemplarPlaybook.decide(grouped(false, true, EnumSet.of(TAUNT))), "whichever is ready");
		assertNull(TemplarPlaybook.decide(grouped(false, true, EnumSet.noneOf(Move.class))), "neither is, so the generic order carries on");
	}

	@Test
	void theRoarComesBeforePeeling() {
		assertEquals(PROVOKING_ROAR, TemplarPlaybook.decide(grouped(true, true, EVERYTHING)));
	}

	@Test
	void stayingAliveComesBeforeTheAggro() {
		Situation hurt = new Situation(40, 60, 0, false, true, true, true, EVERYTHING);
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(hurt));
	}

	@Test
	void aTemplarAloneNeverTauntsOrRoars() {
		Situation alone = new Situation(40, 100, 0, false, false, true, true, EVERYTHING);
		assertNull(TemplarPlaybook.decide(alone), "a group's rules need a group, whatever the flags say");
	}
}
