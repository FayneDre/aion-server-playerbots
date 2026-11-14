package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.ai.FightVerdicts.Stall;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.combat.playbook.ClassPlaybook.Outnumbered;
import com.aionemu.gameserver.playerbot.positioning.BotPosition;

/**
 * The judgements a fight is decided by, each at its edges: when a fight has stalled, who closes in, when a fight is lost, what is defended against,
 * and when the bot is fit to start one.
 */
class FightVerdictsTest {

	private static final long NOW = 1_000_000;

	@Test
	void aFightThatTicksAndLandsIsKept() {
		assertEquals(Stall.KEEP, FightVerdicts.stall(NOW, NOW - 500, NOW - 1_000, false));
		assertEquals(Stall.KEEP, FightVerdicts.stall(NOW, NOW - 500, NOW - FightVerdicts.FRUITLESS_FIGHT_MILLIS + 1, false));
	}

	@Test
	void aLoopThatStoppedIsLetGoUnlessItsSwingIsWaitingItsTurn() {
		long gap = NOW - FightVerdicts.STALLED_FIGHT_MILLIS;
		assertEquals(Stall.LOOP_STOPPED, FightVerdicts.stall(NOW, gap, NOW, false));
		assertEquals(Stall.QUEUED, FightVerdicts.stall(NOW, gap, NOW, true), "a busy pool is not a lost loop");
		assertEquals(Stall.KEEP, FightVerdicts.stall(NOW, gap + 1, NOW, false));
	}

	@Test
	void theLoopIsReadBeforeTheBlows() {
		long longAgo = NOW - 10 * FightVerdicts.FRUITLESS_FIGHT_MILLIS;
		assertEquals(Stall.LOOP_STOPPED, FightVerdicts.stall(NOW, longAgo, longAgo, false), "a lost loop has landed nothing either");
	}

	@Test
	void aFightThatLandsNothingIsFruitless() {
		assertEquals(Stall.FRUITLESS, FightVerdicts.stall(NOW, NOW - 500, NOW - FightVerdicts.FRUITLESS_FIGHT_MILLIS, false));
	}

	@Test
	void onlyMeleeClosesInAndOnlyAfterThePull() {
		assertTrue(FightVerdicts.closesIn(true, BotPosition.FRONT, false));
		assertTrue(FightVerdicts.closesIn(true, BotPosition.BEHIND, false));
		assertFalse(FightVerdicts.closesIn(false, BotPosition.FRONT, false), "the pull is cast from afar");
		for (BotPosition position : BotPosition.values()) {
			if (position != BotPosition.FRONT && position != BotPosition.BEHIND)
				assertFalse(FightVerdicts.closesIn(true, position, false), position + " fights from where it stands");
		}
	}

	@Test
	void aRunningTargetIsShotAtNotChased() {
		assertFalse(FightVerdicts.closesIn(true, BotPosition.FRONT, true));
	}

	@Test
	void anOutmatchedBotLeavesAtAnyHealth() {
		int bot = 20;
		assertTrue(FightVerdicts.retreats(bot, 100, List.of(bot + BotTargetSelector.MAX_LEVEL_GAP + 1), Outnumbered.GENERIC, false));
		assertFalse(FightVerdicts.retreats(bot, 100, List.of(bot + BotTargetSelector.MAX_LEVEL_GAP), Outnumbered.GENERIC, false),
			"at the gap itself it is a fight the bot may start, so one it may finish");
	}

	@Test
	void anOutnumberedBotLeavesOnlyOnceItsHealthIsGoing() {
		List<Integer> two = List.of(20, 20);
		assertFalse(FightVerdicts.retreats(20, FightVerdicts.RETREAT_HP_PERCENT, two, Outnumbered.GENERIC, false));
		assertTrue(FightVerdicts.retreats(20, FightVerdicts.RETREAT_HP_PERCENT - 1, two, Outnumbered.GENERIC, false));
		assertFalse(FightVerdicts.retreats(20, 1, List.of(20), Outnumbered.GENERIC, false), "one attacker of its size is no reason to leave");
	}

	@Test
	void theClassOverridesTheOutnumberedRule() {
		List<Integer> two = List.of(20, 20);
		assertFalse(FightVerdicts.retreats(20, 1, two, Outnumbered.STAND, false), "it holds, whatever the health");
		assertTrue(FightVerdicts.retreats(20, 100, two, Outnumbered.FLEE, false), "it leaves, at any health");
		assertTrue(FightVerdicts.retreats(20, 100, List.of(20), Outnumbered.GENERIC, true), "a guide that says to leave is obeyed");
	}

	@Test
	void standingDoesNotHoldAgainstWhatOutmatchesIt() {
		assertTrue(FightVerdicts.retreats(20, 100, List.of(20, 20 + BotTargetSelector.MAX_LEVEL_GAP + 1), Outnumbered.STAND, false));
	}

	@Test
	void aBotDefendsAgainstWhatHatesIt() {
		assertTrue(FightVerdicts.defendsAgainst(false, false, true, false, false, false), "hated, even when not aimed at this instant");
		assertFalse(FightVerdicts.defendsAgainst(false, false, false, false, false, true), "aimed at without hate is no fight");
		assertFalse(FightVerdicts.defendsAgainst(true, false, true, false, false, true), "dead");
		assertFalse(FightVerdicts.defendsAgainst(false, true, true, false, false, true), "scenery, the training dummy");
		assertFalse(FightVerdicts.defendsAgainst(false, false, true, true, false, true), "what it has just broken off from");
	}

	@Test
	void aGroupHealerAnswersOnlyWhatIsAimedAtIt() {
		assertFalse(FightVerdicts.defendsAgainst(false, false, true, false, true, false), "the rooted add of 20 October");
		assertTrue(FightVerdicts.defendsAgainst(false, false, true, false, true, true));
	}

	@Test
	void aHealerWithoutManaToSpareStopsUnlessItIsTheTarget() {
		assertTrue(FightVerdicts.healerHasNoSurplus(true, false, false));
		assertFalse(FightVerdicts.healerHasNoSurplus(true, true, false), "the surplus is the damage a healer is allowed");
		assertFalse(FightVerdicts.healerHasNoSurplus(true, false, true), "a monster hitting the healer is fought whatever the mana");
		assertFalse(FightVerdicts.healerHasNoSurplus(false, false, false), "the rule is a group healer's only");
	}

	@Test
	void theEngageThresholdsAreReachedAtTheFigure() {
		int hp = FightVerdicts.MIN_ENGAGE_HP_PERCENT, mp = FightVerdicts.MIN_ENGAGE_MP_PERCENT;
		assertTrue(FightVerdicts.healthyEnoughToFight(hp, mp, false));
		assertFalse(FightVerdicts.healthyEnoughToFight(hp - 1, mp, false));
		assertFalse(FightVerdicts.healthyEnoughToFight(hp, mp - 1, false));
		assertFalse(FightVerdicts.healthyEnoughToFight(100, 100, true), "a class that wants to rest rests");
	}
}
