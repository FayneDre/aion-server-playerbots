package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A target given up on is left alone for a while, longer each time the same one fails, and three chases failed in a row without a blow landed send
 * the bot back to its camp.
 */
class FightGiveUpsTest {

	private static final int MONSTER = 1001;

	@Test
	void anIgnoredTargetIsLeftAloneUntilItsTime() {
		FightGiveUps giveUps = new FightGiveUps();
		giveUps.ignore(MONSTER, 5_000);

		assertTrue(giveUps.isIgnored(MONSTER, 4_999));
		assertFalse(giveUps.isIgnored(MONSTER, 5_000), "the time given is the first moment it may be picked again");
		assertFalse(giveUps.isIgnored(MONSTER + 1, 0), "nothing else is ignored");
	}

	@Test
	void anExpiredEntryIsDroppedSoALaterIgnoreStands() {
		FightGiveUps giveUps = new FightGiveUps();
		giveUps.ignore(MONSTER, 1_000);
		assertFalse(giveUps.isIgnored(MONSTER, 2_000));

		giveUps.ignore(MONSTER, 10_000);
		assertTrue(giveUps.isIgnored(MONSTER, 3_000));
	}

	@Test
	void theLatestIgnoreReplacesTheEarlierOne() {
		FightGiveUps giveUps = new FightGiveUps();
		giveUps.ignore(MONSTER, 600_000);
		giveUps.ignore(MONSTER, 1_000); // a retreat after a long give-up: the decision taken last is the one that holds

		assertFalse(giveUps.isIgnored(MONSTER, 2_000));
	}

	@Test
	void theSameMonsterGivenUpOnAgainIsLeftAloneLonger() {
		FightGiveUps giveUps = new FightGiveUps();

		giveUps.chaseFailed(MONSTER, 0);
		assertTrue(giveUps.isIgnored(MONSTER, FightGiveUps.UNREACHABLE_MILLIS - 1));
		assertFalse(giveUps.isIgnored(MONSTER, FightGiveUps.UNREACHABLE_MILLIS));

		long second = 100_000;
		giveUps.chaseFailed(MONSTER, second);
		assertTrue(giveUps.isIgnored(MONSTER, second + FightGiveUps.UNREACHABLE_AGAIN_MILLIS - 1));
		assertFalse(giveUps.isIgnored(MONSTER, second + FightGiveUps.UNREACHABLE_AGAIN_MILLIS));

		long third = 1_000_000;
		giveUps.chaseFailed(MONSTER, third);
		assertTrue(giveUps.isIgnored(MONSTER, third + FightGiveUps.UNREACHABLE_FOR_GOOD_MILLIS - 1));
	}

	@Test
	void theIgnoreTimeGrowsAndThenStays() {
		assertEquals(FightGiveUps.UNREACHABLE_MILLIS, FightGiveUps.unreachableFor(1));
		assertEquals(FightGiveUps.UNREACHABLE_AGAIN_MILLIS, FightGiveUps.unreachableFor(2));
		assertEquals(FightGiveUps.UNREACHABLE_FOR_GOOD_MILLIS, FightGiveUps.unreachableFor(3));
		assertEquals(FightGiveUps.UNREACHABLE_FOR_GOOD_MILLIS, FightGiveUps.unreachableFor(9));
	}

	@Test
	void threeGiveUpsInARowSendTheBotToCampAndStartOver() {
		FightGiveUps giveUps = new FightGiveUps();
		for (int i = 0; i < FightGiveUps.GIVE_UPS_BEFORE_CAMP - 1; i++)
			assertFalse(giveUps.chaseFailed(MONSTER + i, 0));
		assertTrue(giveUps.chaseFailed(MONSTER + 10, 0));

		assertFalse(giveUps.chaseFailed(MONSTER + 11, 0), "the count starts over once it has said so");
	}

	@Test
	void aBlowLandedBreaksTheRun() {
		FightGiveUps giveUps = new FightGiveUps();
		for (int i = 0; i < FightGiveUps.GIVE_UPS_BEFORE_CAMP - 1; i++)
			giveUps.chaseFailed(MONSTER + i, 0);
		giveUps.landed();

		for (int i = 0; i < FightGiveUps.GIVE_UPS_BEFORE_CAMP - 1; i++)
			assertFalse(giveUps.chaseFailed(MONSTER + 10 + i, 0));
		assertTrue(giveUps.chaseFailed(MONSTER + 20, 0));
	}

	@Test
	void ignoringIsNoGiveUp() {
		FightGiveUps giveUps = new FightGiveUps();
		for (int i = 0; i < FightGiveUps.GIVE_UPS_BEFORE_CAMP + 2; i++)
			giveUps.ignore(MONSTER + i, 1_000); // beyond the camp, a fruitless fight, a retreat: none says anything about the ground

		for (int i = 0; i < FightGiveUps.GIVE_UPS_BEFORE_CAMP - 1; i++)
			assertFalse(giveUps.chaseFailed(MONSTER + 100 + i, 0));
	}

	@Test
	void theFailuresAreForgottenPastTheirLimit() {
		FightGiveUps giveUps = new FightGiveUps();
		giveUps.chaseFailed(MONSTER, 0); // its first failure
		for (int i = 1; i <= FightGiveUps.MAX_FAILED_CHASES_KEPT + 1; i++)
			giveUps.chaseFailed(MONSTER + i, 0);

		long later = 10_000_000;
		giveUps.chaseFailed(MONSTER, later);
		assertFalse(giveUps.isIgnored(MONSTER, later + FightGiveUps.UNREACHABLE_MILLIS),
			"the memory was started again, so this counts as its first failure, not its second");
	}
}
