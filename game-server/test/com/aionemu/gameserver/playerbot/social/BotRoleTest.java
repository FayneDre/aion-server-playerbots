package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.playerbot.social.BotRole.Seat;
import com.aionemu.gameserver.playerbot.social.RecruitPlan.Candidate;

/**
 * The aethertech as a tank, and the one member that tanks a group. See {@code docs/classes/plans/aethertech-plan.md}, milestone 1.
 */
class BotRoleTest {

	@Test
	void theTemplarAndTheAethertechAreTanks() {
		assertEquals(BotRole.TANK, BotRole.of(PlayerClass.TEMPLAR));
		assertEquals(BotRole.TANK, BotRole.of(PlayerClass.RIDER));
		assertEquals(BotRole.DAMAGE, BotRole.of(PlayerClass.GLADIATOR), "a gladiator holds a mob and is still not a second tank");
	}

	@Test
	void aGroupWithNoTankHasNone() {
		assertNull(BotRole.groupTank(List.of(new Seat(1, PlayerClass.CLERIC, false), new Seat(2, PlayerClass.GLADIATOR, false))));
		assertNull(BotRole.groupTank(List.of()));
	}

	@Test
	void aGroupWithOneTankHasThatOne() {
		Seat aethertech = new Seat(7, PlayerClass.RIDER, false);
		assertEquals(aethertech, BotRole.groupTank(List.of(new Seat(1, PlayerClass.CLERIC, false), aethertech)));
	}

	@Test
	void theTemplarTanksAGroupThatHasBoth() {
		Seat templar = new Seat(9, PlayerClass.TEMPLAR, false);
		assertEquals(templar, BotRole.groupTank(List.of(new Seat(3, PlayerClass.RIDER, false), templar)), "whatever their order or their ids");
	}

	@Test
	void theAethertechTakesOverFromADeadTemplar() {
		Seat aethertech = new Seat(3, PlayerClass.RIDER, false);
		assertEquals(aethertech, BotRole.groupTank(List.of(new Seat(9, PlayerClass.TEMPLAR, true), aethertech)));
		assertNull(BotRole.groupTank(List.of(new Seat(9, PlayerClass.TEMPLAR, true), new Seat(3, PlayerClass.RIDER, true))), "every tank dead");
	}

	@Test
	void twoOfTheSameClassNameTheLowestIdWhateverTheOrder() {
		Seat first = new Seat(4, PlayerClass.TEMPLAR, false), second = new Seat(8, PlayerClass.TEMPLAR, false);
		assertEquals(first, BotRole.groupTank(List.of(second, first)));
		assertEquals(first, BotRole.groupTank(List.of(first, second)));
	}

	@Test
	void aTankIsATemplarBeforeAnAethertech() {
		List<Candidate> candidates = List.of(new Candidate(1, PlayerClass.RIDER, 30), new Candidate(2, PlayerClass.TEMPLAR, 30));
		assertEquals(PlayerClass.TEMPLAR, RecruitPlan.choose(candidates, BotRole.TANK, Set.of(), 30).playerClass());
		assertEquals(PlayerClass.RIDER, RecruitPlan.choose(candidates.subList(0, 1), BotRole.TANK, Set.of(), 30).playerClass());
		assertNull(RecruitPlan.choose(candidates.subList(0, 1), BotRole.DAMAGE, Set.of(), 30), "no longer recruited as damage");
	}
}
