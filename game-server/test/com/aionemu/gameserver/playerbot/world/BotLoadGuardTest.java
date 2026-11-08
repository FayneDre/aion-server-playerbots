package com.aionemu.gameserver.playerbot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.world.BotLoadGuard.Cause;
import com.aionemu.gameserver.playerbot.world.BotLoadGuard.Limits;
import com.aionemu.gameserver.playerbot.world.BotLoadGuard.Reading;

class BotLoadGuardTest {

	private static final Limits LIMITS = new Limits(85, 80, 20_000, 5_000);
	private static final Set<Cause> OPEN = EnumSet.noneOf(Cause.class);

	@Test
	void aServerWellClearWakesAsBefore() {
		assertEquals(OPEN, BotLoadGuard.next(OPEN, new Reading(60, 1_000), LIMITS));
	}

	@Test
	void theHeapHoldsFromItsMarkAndUntilUnderTheLowerOne() {
		Set<Cause> held = BotLoadGuard.next(OPEN, new Reading(85, 0), LIMITS);
		assertEquals(EnumSet.of(Cause.HEAP), held);
		assertEquals(EnumSet.of(Cause.HEAP), BotLoadGuard.next(held, new Reading(82, 0), LIMITS), "between the marks it stays as it was");
		assertEquals(OPEN, BotLoadGuard.next(held, new Reading(79, 0), LIMITS));
		assertEquals(OPEN, BotLoadGuard.next(OPEN, new Reading(82, 0), LIMITS), "and between the marks an open guard stays open");
	}

	@Test
	void routePlanningHoldsTheSameWay() {
		Set<Cause> held = BotLoadGuard.next(OPEN, new Reading(50, 25_000), LIMITS);
		assertEquals(EnumSet.of(Cause.ROUTES), held);
		assertEquals(EnumSet.of(Cause.ROUTES), BotLoadGuard.next(held, new Reading(50, 8_000), LIMITS));
		assertEquals(OPEN, BotLoadGuard.next(held, new Reading(50, 4_000), LIMITS));
	}

	@Test
	void eachCauseLetsGoOnItsOwn() {
		Set<Cause> both = BotLoadGuard.next(OPEN, new Reading(90, 30_000), LIMITS);
		assertEquals(EnumSet.allOf(Cause.class), both);
		assertEquals(EnumSet.of(Cause.HEAP), BotLoadGuard.next(both, new Reading(90, 0), LIMITS), "the routes clear while the heap stays full");
	}

	@Test
	void aHeapNotYetCollectedChangesNothing() {
		assertEquals(OPEN, BotLoadGuard.next(OPEN, new Reading(-1, 0), LIMITS));
		assertEquals(EnumSet.of(Cause.HEAP), BotLoadGuard.next(EnumSet.of(Cause.HEAP), new Reading(-1, 0), LIMITS),
			"an unknown reading is no reason to let go");
	}

	@Test
	void theDecisionLeavesTheSetItWasGivenAsItWas() {
		Set<Cause> held = EnumSet.of(Cause.HEAP);
		BotLoadGuard.next(held, new Reading(10, 0), LIMITS);
		assertEquals(EnumSet.of(Cause.HEAP), held);
	}
}
