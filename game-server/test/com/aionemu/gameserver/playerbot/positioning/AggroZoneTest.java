package com.aionemu.gameserver.playerbot.positioning;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * What a monster notices, by the engine's own rules: range, cone, a short radius all round, and the ten level gap.
 */
class AggroZoneTest {

	/** A monster at the origin facing +x with a range of 10 m and a cone of 240 degrees. */
	private static final AggroZone CONE = new AggroZone(0, 0, 1, 0, 10, 240, 40, false);

	@Test
	void noticesWhatIsInFrontAndInRange() {
		assertTrue(CONE.notices(8, 0, 40));
		assertFalse(CONE.notices(11, 0, 40), "out of range");
	}

	@Test
	void doesNotNoticeWhatIsInItsBlindSectorOutsideTheShortRadius() {
		// 240 degrees leaves 60 either side of straight behind: at -x, 6 m away, it sees nothing
		assertFalse(CONE.notices(-6, 0, 40));
		assertFalse(CONE.notices(-6, 2, 40));
	}

	@Test
	void noticesAnythingWithinTheShortRadiusEvenBehindIt() {
		assertEquals(4f, CONE.shortRange());
		assertTrue(CONE.notices(-3, 0, 40));
		assertFalse(CONE.notices(-5, 0, 40));
	}

	@Test
	void aShortRangeMonsterHasAShortRadiusOfHalfItsRange() {
		AggroZone shortSighted = new AggroZone(0, 0, 1, 0, 6, 240, 40, false);

		assertEquals(3f, shortSighted.shortRange());
	}

	@Test
	void aMonsterThatSeesAllRoundNoticesInEveryDirection() {
		AggroZone allRound = new AggroZone(0, 0, 1, 0, 10, 360, 40, false);

		assertTrue(allRound.notices(-8, 0, 40));
		assertTrue(allRound.notices(0, 9, 40));
	}

	@Test
	void aMonsterWithNoRangeOrNoAngleNoticesNobody() {
		assertFalse(new AggroZone(0, 0, 1, 0, 0, 360, 40, false).notices(0.5f, 0, 40));
		assertFalse(new AggroZone(0, 0, 1, 0, 10, 0, 40, false).notices(1, 0, 40), "an angle of 0 is the engine's way of saying it never aggroes");
	}

	@Test
	void aCreatureTenLevelsAboveIsGreyAndIgnoredExceptByGuards() {
		assertFalse(CONE.notices(5, 0, 50), "ten levels above");
		assertTrue(CONE.notices(5, 0, 49), "nine levels above is still noticed");
		AggroZone guard = new AggroZone(0, 0, 1, 0, 10, 240, 40, true);
		assertTrue(guard.notices(5, 0, 80));
	}

	@Test
	void theRangeIsMeasuredFromEdgeToEdgeSoBodiesWidenIt() {
		// two bodies of 1.5 m between them: 12 m between centres is 10.5 between edges, 11.5 is still outside, 13 is inside
		AggroZone big = new AggroZone(0, 0, 1, 0, 10, 360, 40, false, 3f);

		assertTrue(big.notices(12.9f, 0, 40), "9.9 m between edges");
		assertFalse(big.notices(13.1f, 0, 40), "10.1 m between edges");
	}

	@Test
	void theShortRadiusIsMeasuredTheSameWay() {
		AggroZone big = new AggroZone(0, 0, 1, 0, 10, 240, 40, false, 3f);

		assertTrue(big.notices(-6.9f, 0, 40), "behind it, 3.9 m between edges, inside the short radius of 4");
		assertFalse(big.notices(-7.5f, 0, 40), "4.5 m between edges");
	}
}
