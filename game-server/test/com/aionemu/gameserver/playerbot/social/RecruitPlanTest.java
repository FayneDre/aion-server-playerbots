package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.playerbot.social.RecruitPlan.Candidate;

class RecruitPlanTest {

	@Test
	void aDamageDealerAloneIsGivenATankAHealerASupportAndDamage() {
		assertEquals(List.of(BotRole.TANK, BotRole.HEALER, BotRole.SUPPORT, BotRole.DAMAGE, BotRole.DAMAGE),
			RecruitPlan.rolesWanted(List.of(BotRole.DAMAGE), 5));
	}

	@Test
	void theRecruitersOwnRoleIsNotAskedForAgain() {
		assertEquals(List.of(BotRole.HEALER, BotRole.SUPPORT, BotRole.DAMAGE), RecruitPlan.rolesWanted(List.of(BotRole.TANK), 3));
		assertEquals(List.of(BotRole.TANK, BotRole.SUPPORT, BotRole.DAMAGE), RecruitPlan.rolesWanted(List.of(BotRole.HEALER), 3));
		assertEquals(List.of(BotRole.TANK, BotRole.HEALER, BotRole.DAMAGE), RecruitPlan.rolesWanted(List.of(BotRole.SUPPORT), 3));
	}

	@Test
	void withFewSeatsTheTankComesFirstThenTheHealer() {
		assertEquals(List.of(BotRole.TANK), RecruitPlan.rolesWanted(List.of(BotRole.DAMAGE), 1));
		assertEquals(List.of(BotRole.TANK, BotRole.HEALER), RecruitPlan.rolesWanted(List.of(BotRole.DAMAGE), 2));
	}

	@Test
	void aFullGroupWantsNobody() {
		assertEquals(List.of(), RecruitPlan.rolesWanted(List.of(BotRole.DAMAGE), 0));
	}

	@Test
	void aSupportIsAChanterBeforeABard() {
		List<Candidate> pool = List.of(new Candidate(1, PlayerClass.BARD, 28), new Candidate(2, PlayerClass.CHANTER, 26));
		assertEquals(2, RecruitPlan.choose(pool, BotRole.SUPPORT, Set.of(), 28).playerId());
	}

	@Test
	void damageIsSpreadOverClassesTheGroupDoesNotHaveYet() {
		List<Candidate> pool = List.of(new Candidate(1, PlayerClass.RANGER, 28), new Candidate(2, PlayerClass.SORCERER, 28));
		assertEquals(2, RecruitPlan.choose(pool, BotRole.DAMAGE, Set.of(PlayerClass.RANGER), 28).playerId());
	}

	@Test
	void theNearestLevelWinsBetweenTwoOfTheSameClass() {
		List<Candidate> pool = List.of(new Candidate(1, PlayerClass.TEMPLAR, 25), new Candidate(2, PlayerClass.TEMPLAR, 29));
		assertEquals(2, RecruitPlan.choose(pool, BotRole.TANK, Set.of(), 28).playerId());
	}

	@Test
	void noCandidateOfTheRoleIsNoAnswer() {
		assertNull(RecruitPlan.choose(List.of(new Candidate(1, PlayerClass.RANGER, 28)), BotRole.HEALER, Set.of(), 28));
	}
}
