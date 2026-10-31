package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The temperament draw and the fairness rules of a bot picking a fight with a player.
 */
class BotPvpRulesTest {

	@Test
	void temperamentIsFixedForAnId() {
		for (int id = 100000; id < 100100; id++)
			assertEquals(BotTemperament.of(id), BotTemperament.of(id));
	}

	@Test
	void temperamentSharesAreAboutAQuarterAQuarterAndAHalf() {
		Map<BotTemperament, Integer> counts = new EnumMap<>(BotTemperament.class);
		int total = 20000;
		for (int id = 100000; id < 100000 + total; id++) // consecutive ids, as bots have
			counts.merge(BotTemperament.of(id), 1, Integer::sum);
		assertEquals(0.25, counts.get(BotTemperament.FIGHTER) / (double) total, 0.02);
		assertEquals(0.25, counts.get(BotTemperament.OPPORTUNIST) / (double) total, 0.02);
		assertEquals(0.50, counts.get(BotTemperament.PEACEFUL) / (double) total, 0.02);
	}

	@Test
	void neighbouringIdsDoNotAlternateInAPattern() {
		int sameAsPrevious = 0;
		for (int id = 200000; id < 204000; id++)
			if (BotTemperament.of(id) == BotTemperament.of(id + 1))
				sameAsPrevious++;
		// 3/8 of neighbours match for independent draws with these shares; a strict alternation would give 0
		assertTrue(sameAsPrevious > 1100 && sameAsPrevious < 1900, "same as previous: " + sameAsPrevious);
	}

	@Test
	void fighterStartsAgainstAnyone() {
		assertTrue(BotTemperament.FIGHTER.mayStartAgainst(false, 40, 43, 100));
	}

	@Test
	void peacefulNeverStartsExceptOnAnInvasion() {
		assertFalse(BotTemperament.PEACEFUL.mayStartAgainst(false, 40, 30, 5));
		assertTrue(BotTemperament.PEACEFUL.mayStartAgainst(true, 40, 43, 100));
	}

	@Test
	void opportunistNeedsALowerLevelOrAWeakenedEnemy() {
		assertFalse(BotTemperament.OPPORTUNIST.mayStartAgainst(false, 40, 40, 100));
		assertFalse(BotTemperament.OPPORTUNIST.mayStartAgainst(false, 40, 43, 51));
		assertTrue(BotTemperament.OPPORTUNIST.mayStartAgainst(false, 40, 39, 100));
		assertTrue(BotTemperament.OPPORTUNIST.mayStartAgainst(false, 40, 42, 50));
		assertTrue(BotTemperament.OPPORTUNIST.mayStartAgainst(true, 40, 43, 100));
	}

	@Test
	void levelBandIsPlusThreeAndMinusSix() {
		assertTrue(BotPvpRules.isInLevelBand(40, 43, 3, 6));
		assertFalse(BotPvpRules.isInLevelBand(40, 44, 3, 6));
		assertTrue(BotPvpRules.isInLevelBand(40, 34, 3, 6));
		assertFalse(BotPvpRules.isInLevelBand(40, 33, 3, 6));
	}

	@Test
	void aLonePlayerIsAttackedByAtMostTwoBots() {
		assertTrue(BotPvpRules.mayJoinAttack(true, 0));
		assertTrue(BotPvpRules.mayJoinAttack(true, 1));
		assertFalse(BotPvpRules.mayJoinAttack(true, 2));
		assertTrue(BotPvpRules.mayJoinAttack(false, 5));
	}

	@Test
	void aBotWithdrawsHurtOutnumberedOrInACrowd() {
		assertFalse(BotPvpRules.shouldWithdraw(10, 0)); // nobody to withdraw from
		assertTrue(BotPvpRules.shouldWithdraw(29, 1));
		assertFalse(BotPvpRules.shouldWithdraw(30, 1));
		assertFalse(BotPvpRules.shouldWithdraw(100, 1));
		assertTrue(BotPvpRules.shouldWithdraw(49, 2));
		assertFalse(BotPvpRules.shouldWithdraw(50, 2));
		assertTrue(BotPvpRules.shouldWithdraw(100, 3));
	}

	@Test
	void aDyingPlayerIsNotPicked() {
		assertTrue(BotPvpRules.isDying(10));
		assertFalse(BotPvpRules.isDying(11));
	}

	@Test
	void aMonsterOnlySkillIsNotTriedOnAPlayer() {
		assertTrue(BotPvpRules.isWrongSpecies(true, true));
		assertFalse(BotPvpRules.isWrongSpecies(true, false));
		assertFalse(BotPvpRules.isWrongSpecies(false, true));
	}
}
