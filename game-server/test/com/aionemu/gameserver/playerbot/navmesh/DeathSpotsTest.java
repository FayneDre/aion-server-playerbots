package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeathSpotsTest {

	private static final int MAP = 990000001;

	@Test
	void groundNearADeathCostsExtraAndGroundFarFromItDoesNot() {
		DeathSpots.record(MAP, 100, 100, 1000);

		Avoidance avoid = DeathSpots.around(MAP, 2000);

		assertEquals(DeathSpots.COST, avoid.extra(110, 100), "10 m from the death");
		assertEquals(0f, avoid.extra(140, 100), "40 m from it");
	}

	@Test
	void aDeathIsForgottenAfterItsMemory() {
		DeathSpots.record(MAP + 1, 100, 100, 1000);

		assertSame(Avoidance.NONE, DeathSpots.around(MAP + 1, 1000 + DeathSpots.MEMORY_MILLIS + 1));
		assertNotSame(Avoidance.NONE, DeathSpots.around(MAP + 1, 1000 + DeathSpots.MEMORY_MILLIS - 1));
	}

	@Test
	void deathsAreCountedNearAPointAndForgottenWithTime() {
		DeathSpots.record(MAP + 3, 100, 100, 1000);
		DeathSpots.record(MAP + 3, 180, 100, 1000);
		DeathSpots.record(MAP + 3, 900, 900, 1000);

		assertEquals(2, DeathSpots.countNear(MAP + 3, 140, 100, 100, 2000));
		assertEquals(1, DeathSpots.countNear(MAP + 3, 100, 100, 50, 2000));
		assertEquals(0, DeathSpots.countNear(MAP + 3, 140, 100, 100, 1000 + DeathSpots.MEMORY_MILLIS + 1));
		assertEquals(0, DeathSpots.countNear(MAP + 4, 140, 100, 100, 2000), "a map without deaths");
	}

	@Test
	void aMapWithoutDeathsHasNothingToAvoid() {
		assertSame(Avoidance.NONE, DeathSpots.around(MAP + 2, 5));
	}

	@Test
	void twoAvoidancesAddUpAndNoneIsTheOtherOne() {
		Avoidance two = (x, y) -> 2f, three = (x, y) -> 3f;

		assertEquals(5f, DeathSpots.combined(two, three).extra(0, 0));
		assertSame(two, DeathSpots.combined(two, Avoidance.NONE));
		assertSame(three, DeathSpots.combined(Avoidance.NONE, three));
	}
}
