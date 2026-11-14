package com.aionemu.gameserver.playerbot.ai;

import static com.aionemu.gameserver.playerbot.ai.SkillOrder.Step.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.ai.SkillOrder.Step;

/**
 * The order a bot tries its skills in: survive, keep the advantage its class was given, then deal damage; a healer's heals before all of it, and a
 * target almost dead finished rather than survived.
 */
class SkillOrderTest {

	@Test
	void aFightOpenedInReachRunsTheWholeOrder() {
		assertEquals(List.of(PLAYBOOK, OPENING, DEFENSIVE, HEAL_SELF, POTIONS, HEAL_ALLY, ATTACK), SkillOrder.steps(false, false, false, false, false));
	}

	@Test
	void closingInAddsTheApproachAndTheLeap() {
		assertEquals(List.of(PLAYBOOK, APPROACH, OPENING, DEFENSIVE, HEAL_SELF, POTIONS, HEAL_ALLY, GAP_CLOSER, ATTACK),
			SkillOrder.steps(false, true, false, false, false));
	}

	@Test
	void theApproachAndTheOpeningComeOnceAFight() {
		List<Step> later = SkillOrder.steps(false, true, true, true, false);
		assertFalse(later.contains(APPROACH));
		assertFalse(later.contains(OPENING));
		assertTrue(later.contains(GAP_CLOSER), "the leap is not spent: it closes whenever there is ground to close");
	}

	@Test
	void theApproachIsOnlyForGroundStillToCover() {
		assertFalse(SkillOrder.steps(false, false, false, false, false).contains(APPROACH));
		assertFalse(SkillOrder.steps(false, false, false, false, false).contains(GAP_CLOSER));
	}

	@Test
	void aHealerHealsBeforeAnythingButItsClass() {
		List<Step> steps = SkillOrder.steps(true, true, false, false, false);
		assertEquals(List.of(PLAYBOOK, HEAL_SELF_FIRST, HEAL_ALLY_FIRST), steps.subList(0, 3));
		assertFalse(steps.contains(HEAL_ALLY), "it has done that already, first");
	}

	@Test
	void aTargetAlmostDeadIsFinishedNotSurvived() {
		assertEquals(List.of(PLAYBOOK, ATTACK), SkillOrder.steps(false, false, true, true, true));
		assertEquals(List.of(PLAYBOOK, GAP_CLOSER, ATTACK), SkillOrder.steps(false, true, true, true, true));
	}

	@Test
	void aHealersHealsDoNotWaitForTheFinish() {
		// the rule is about the bot's own survival: a monster with a sliver left is no reason to let the tank die under it
		assertEquals(List.of(PLAYBOOK, HEAL_SELF_FIRST, HEAL_ALLY_FIRST, ATTACK), SkillOrder.steps(true, false, true, true, true));
	}

	@Test
	void theDefenceComesBeforeTheHealAndTheHealBeforeTheFlask() {
		List<Step> steps = SkillOrder.steps(false, false, true, true, false);
		assertTrue(steps.indexOf(DEFENSIVE) < steps.indexOf(HEAL_SELF));
		assertTrue(steps.indexOf(HEAL_SELF) < steps.indexOf(POTIONS));
		assertTrue(steps.indexOf(POTIONS) < steps.indexOf(HEAL_ALLY), "a mate's life outranks the bot's damage, not the bot's own");
	}

	@Test
	void theClassComesFirstAndDamageLastWhateverTheMoment() {
		for (int flags = 0; flags < 32; flags++) {
			List<Step> steps = SkillOrder.steps((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0);
			assertEquals(PLAYBOOK, steps.getFirst());
			assertEquals(ATTACK, steps.getLast());
			assertEquals(steps.size(), steps.stream().distinct().count(), "nothing is tried twice in the same moment");
		}
	}
}
