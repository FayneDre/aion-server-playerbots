package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.TemplarPlaybook.Situation;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;

/**
 * The guide's rules, one case each. They are a pure function of a {@link Situation}, so a number it wants changed is a number changed here first.
 */
class TemplarPlaybookTest {

	private static final Set<Move> EVERYTHING = EnumSet.allOf(Move.class);

	/** A templar on its own: no group, so none of the aggro rules can apply. */
	private static Situation alone(int level, int hp, int dp, boolean armorUp, Set<Move> ready) {
		return new Situation(level, hp, dp, armorUp, false, false, false, false, false, false, false, false, ready);
	}

	/** A templar in a group, healthy and with no divine power, so only the aggro rules have anything to say. */
	private static Situation grouped(boolean enemyInRoarRange, boolean enemyLoose, Set<Move> ready) {
		return new Situation(40, 100, 0, false, true, enemyInRoarRange, enemyLoose, false, false, false, false, false, ready);
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
		Situation hurt = new Situation(40, 60, 0, false, true, true, true, false, false, false, false, false, EVERYTHING);
		assertEquals(EMPYREAN_ARMOR, TemplarPlaybook.decide(hurt));
	}

	@Test
	void aTemplarAloneNeverTauntsOrRoars() {
		Situation alone = new Situation(40, 100, 0, false, false, true, true, false, false, false, false, false, EVERYTHING);
		assertNull(TemplarPlaybook.decide(alone), "a group's rules need a group, whatever the flags say");
	}

	/** A templar at full health, alone, in the given state of control. */
	private static Situation shaken(boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean devotionUp, int hp, Set<Move> ready) {
		return new Situation(45, hp, 0, false, false, false, false, controlled, recentlyControlled, shockChainOpen, devotionUp, false, ready);
	}

	@Test
	void whileControlledTheOnlyThingToDoIsRemoveShock() {
		assertEquals(REMOVE_SHOCK, TemplarPlaybook.decide(shaken(true, false, false, false, 100, EVERYTHING)));
		assertNull(TemplarPlaybook.decide(shaken(true, false, false, false, 10, EnumSet.complementOf(EnumSet.of(REMOVE_SHOCK)))),
			"everything else the engine would refuse, so there is nothing to decide, even with a heal ready and only a tenth of the health left");
	}

	@Test
	void refreshSpiritFollowsRemoveShockOnlyBelowThreeQuartersOfTheHealth() {
		assertEquals(REFRESH_SPIRIT, TemplarPlaybook.decide(shaken(false, true, true, false, 74, EVERYTHING)));
		assertNotEquals(REFRESH_SPIRIT, TemplarPlaybook.decide(shaken(false, true, true, false, 75, EVERYTHING)), "it would heal what is not hurt");
		assertNotEquals(REFRESH_SPIRIT, TemplarPlaybook.decide(shaken(false, true, false, false, 60, EVERYTHING)), "the chain is closed: something else was cast since");
	}

	@Test
	void theChainComesBeforeEveryOtherDefensive() {
		assertEquals(REFRESH_SPIRIT, TemplarPlaybook.decide(shaken(false, true, true, false, 40, EVERYTHING)),
			"Armor would close the chain on the way past, and Refresh Spirit would then be refused");
	}

	@Test
	void devotionOnceTheTemplarIsFreeAndHasJustBeenControlled() {
		assertEquals(UNWAVERING_DEVOTION, TemplarPlaybook.decide(shaken(false, true, false, false, 100, EVERYTHING)));
		assertNull(TemplarPlaybook.decide(shaken(false, false, false, false, 100, EVERYTHING)), "never controlled lately, so nothing to answer");
		assertNull(TemplarPlaybook.decide(shaken(false, true, false, true, 100, EVERYTHING)), "its resistance is already on");
		assertNull(TemplarPlaybook.decide(shaken(false, true, false, false, 100, EnumSet.complementOf(EnumSet.of(UNWAVERING_DEVOTION)))), "on cooldown");
	}

	@Test
	void stubbornSpiritAndBodyguardAreNeverCast() {
		assertTrue(TemplarPlaybook.FORBIDDEN_GROUPS.contains("KN_MOVINGSTANCE"));
		assertTrue(TemplarPlaybook.FORBIDDEN_GROUPS.contains("KN_GRANDPROTECTION"));
		for (Move move : Move.values())
			assertFalse(TemplarPlaybook.FORBIDDEN_GROUPS.contains(move.group), move + " is a move the playbook casts, so it cannot also be forbidden");
	}

	@Test
	void onlyAnAreaOfEnemiesCountsAsAnAreaAttack() {
		assertTrue(TemplarPlaybook.isAreaAttack(TargetRelationAttribute.ENEMY, TargetRangeAttribute.AREA));
		assertFalse(TemplarPlaybook.isAreaAttack(TargetRelationAttribute.ENEMY, TargetRangeAttribute.ONLYONE), "a single target is not an area");
		assertFalse(TemplarPlaybook.isAreaAttack(TargetRelationAttribute.MYPARTY, TargetRangeAttribute.AREA), "Prayer of Victory is a buff, not an attack");
	}

	@Test
	void barricadeOfSteelIsRaisedUnderSeventyAndOnlyOnce() {
		Set<Move> onlyBarricade = EnumSet.of(BARRICADE_OF_STEEL);
		assertEquals(BARRICADE_OF_STEEL, TemplarPlaybook.decide(alone(40, 69, 0, false, onlyBarricade)));
		assertNull(TemplarPlaybook.decide(alone(40, 70, 0, false, onlyBarricade)));
		Situation alreadyOn = new Situation(40, 30, 0, false, false, false, false, false, false, false, false, true, onlyBarricade);
		assertNull(TemplarPlaybook.decide(alreadyOn), "a toggle cast while it is on switches itself off");
	}

	@Test
	void inciteRageIsTheFirstTauntToSpendOnAMonsterOnAMate() {
		assertEquals(INCITE_RAGE, TemplarPlaybook.decide(grouped(false, true, EVERYTHING)));
		assertEquals(CAPTURE, TemplarPlaybook.decide(grouped(false, true, EnumSet.of(CAPTURE, TAUNT))), "when it is not ready the others follow in their order");
	}

	@Test
	void theStigmasAreThoseTheGuideListsInTheOrderItListsThem() {
		assertEquals(java.util.List.of("KN_HIGHPROVOKE", "KN_REFLECTSHIELD", "KN_THUNDERBLADE"), new TemplarPlaybook().preferredStigmas(false));
		assertEquals(java.util.List.of("KN_RECOVER", "KN_SENTINEL", "KN_DESTRUCTSHIELD"), new TemplarPlaybook().preferredStigmas(true));
		assertTrue(ClassPlaybook.NONE.preferredStigmas(false).isEmpty(), "a class with no opinion leaves the fitter to rank by role and power");
	}

	@Test
	void aSkillWithNoGroupIsNotForbiddenAndDoesNotThrow() {
		assertFalse(TemplarPlaybook.isForbidden(null), "most templates state no group");
		assertTrue(TemplarPlaybook.isForbidden("KN_GRANDPROTECTION"));
		assertFalse(TemplarPlaybook.isForbidden("KN_STONEBODY"));
	}
}
