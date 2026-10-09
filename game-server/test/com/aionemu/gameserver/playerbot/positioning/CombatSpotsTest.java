package com.aionemu.gameserver.playerbot.positioning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.playerbot.positioning.CombatSpots.Situation;

/**
 * The guide's positioning rules on made up ground: the enemy at the origin, facing +x, which is where the tank stands. Behind it is therefore -x.
 */
class CombatSpotsTest {

	private static final Vector3f ENEMY = new Vector3f(0, 0, 10);

	private static Vector3f at(float x, float y) {
		return new Vector3f(x, y, 10);
	}

	/** The group stands behind the enemy, as it does when the tank has done its job. */
	private static Situation groupBehind(Vector3f from, List<AggroZone> zones, List<Vector3f> members) {
		return new Situation(ENEMY, 1, 0, at(-12, 0), members, from, 40, zones);
	}

	private static double angleFromBehind(Vector3f spot) {
		double length = Math.hypot(spot.getX() - ENEMY.getX(), spot.getY() - ENEMY.getY());
		double cosine = -(spot.getX() - ENEMY.getX()) / length; // facing is +x, so behind is -x
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
	}

	private static double distanceToEnemy(Vector3f spot) {
		return Math.hypot(spot.getX() - ENEMY.getX(), spot.getY() - ENEMY.getY());
	}

	@Test
	void everyClassHasAPlaceAndTheStartingOnesHaveNone() {
		assertEquals(BotPosition.FRONT, BotPosition.of(PlayerClass.TEMPLAR));
		assertEquals(BotPosition.FRONT, BotPosition.of(PlayerClass.RIDER));
		assertEquals(BotPosition.BEHIND, BotPosition.of(PlayerClass.ASSASSIN));
		assertEquals(BotPosition.BEHIND, BotPosition.of(PlayerClass.CHANTER));
		assertEquals(BotPosition.BEHIND, BotPosition.of(PlayerClass.GLADIATOR));
		for (PlayerClass ranged : List.of(PlayerClass.SPIRIT_MASTER, PlayerClass.SORCERER, PlayerClass.BARD, PlayerClass.RANGER, PlayerClass.GUNNER))
			assertEquals(BotPosition.RANGED, BotPosition.of(ranged), ranged.name());
		assertEquals(BotPosition.HEALER, BotPosition.of(PlayerClass.CLERIC));
		assertEquals(BotPosition.NONE, BotPosition.of(PlayerClass.WARRIOR));
	}

	@Test
	void aBehindClassStandsWithinFortyFiveDegreesOfStraightBehindAtContact() {
		Vector3f spot = CombatSpots.spotFor(BotPosition.BEHIND, groupBehind(at(-3, 8), List.of(), List.of()), 2f);

		assertNotNull(spot);
		assertTrue(angleFromBehind(spot) <= CombatSpots.BEHIND_WINDOW_DEGREES + 0.01, "angle " + angleFromBehind(spot));
		assertEquals(1.5, distanceToEnemy(spot), 0.01, "three quarters of a 2 m weapon");
	}

	@Test
	void theBehindSpotIsTheNearestAcceptableOneToWhereTheBotAlreadyStands() {
		Vector3f fromAbove = CombatSpots.spotFor(BotPosition.BEHIND, groupBehind(at(-3, 8), List.of(), List.of()), 2f);
		Vector3f fromBelow = CombatSpots.spotFor(BotPosition.BEHIND, groupBehind(at(-3, -8), List.of(), List.of()), 2f);

		assertTrue(fromAbove.getY() > 0, "it does not walk round the enemy to the other edge of the arc");
		assertTrue(fromBelow.getY() < 0);
	}

	@Test
	void theTankStandsOnTheSideOppositeTheGroup() {
		// the group is at -12, so the far side is +x
		Vector3f spot = CombatSpots.spotFor(BotPosition.FRONT, groupBehind(at(5, 5), List.of(), List.of()), 2f);

		assertNotNull(spot);
		assertTrue(spot.getX() > 0, "on the side the group is not: " + spot);
		assertEquals(1.5, distanceToEnemy(spot), 0.01);
	}

	@Test
	void aRangedClassKeepsFifteenMetresWhenItsReachAllowsIt() {
		Vector3f spot = CombatSpots.spotFor(BotPosition.RANGED, groupBehind(at(-20, 0), List.of(), List.of()), 25f);

		assertNotNull(spot);
		assertEquals(15, distanceToEnemy(spot), 0.01);
		assertTrue(spot.getX() <= 0, "away from the enemy's face, which is the side its cleave does not reach: " + spot);
	}

	@Test
	void aShorterReachPullsTheRangedClassCloser() {
		// a spellbook reaches 15 m, and two metres are kept in hand
		Vector3f spot = CombatSpots.spotFor(BotPosition.RANGED, groupBehind(at(-20, 0), List.of(), List.of()), 15f);

		assertEquals(13, distanceToEnemy(spot), 0.01);
	}

	@Test
	void theHealerStandsWithinReachOfEveryMember() {
		List<Vector3f> members = List.of(at(-10, 8), at(-12, -6), at(3, 0));
		Vector3f spot = CombatSpots.spotFor(BotPosition.HEALER, groupBehind(at(-14, 0), List.of(), members), 25f);

		assertNotNull(spot);
		for (Vector3f member : members)
			assertTrue(Math.hypot(spot.getX() - member.getX(), spot.getY() - member.getY()) <= 23.01, "out of reach of " + member + " from " + spot);
	}

	@Test
	void theHealerComesNearerWhenTheMembersAreTooSpreadForTheFirstRing() {
		// one member 24 m behind and one 15 m in front: from no spot 15 m out can she reach both, and from a nearer ring she can
		List<Vector3f> members = List.of(at(-24, 0), at(15, 0));
		Vector3f spot = CombatSpots.spotFor(BotPosition.HEALER, groupBehind(at(-14, 0), List.of(), members), 25f);

		assertNotNull(spot);
		assertTrue(distanceToEnemy(spot) < 15, "nearer than the area safe ring: " + distanceToEnemy(spot));
	}

	@Test
	void aHealerWithNobodyToHealHasNoSpot() {
		assertNull(CombatSpots.spotFor(BotPosition.HEALER, groupBehind(at(-14, 0), List.of(), List.of()), 25f));
	}

	@Test
	void aSpotInsideAnotherPacksConeIsRefusedAndTheNextBestTaken() {
		// a pack just behind the enemy whose 240 degree cone faces the enemy and covers the straight behind spot
		AggroZone pack = new AggroZone(-6, 0, 1, 0, 10, 240, 40, false);
		Vector3f spot = CombatSpots.spotFor(BotPosition.BEHIND, groupBehind(at(-3, 0), List.of(pack), List.of()), 2f);

		assertTrue(spot == null || !pack.notices(spot.getX(), spot.getY(), 40), "the spot must be unseen by that pack");
	}

	@Test
	void everySpotInsideAZoneMeansNoSpotAtAll() {
		AggroZone everywhere = new AggroZone(0, 0, 1, 0, 100, 360, 40, false);

		assertNull(CombatSpots.spotFor(BotPosition.RANGED, groupBehind(at(-20, 0), List.of(everywhere), List.of()), 25f));
	}

	@Test
	void aClassWithoutARuleStaysWhereItIs() {
		assertNull(CombatSpots.spotFor(BotPosition.NONE, groupBehind(at(-20, 0), List.of(), List.of()), 25f));
	}

	@Test
	void withNoFacingThereIsNoBehind() {
		Situation noFacing = new Situation(ENEMY, 0, 0, at(-12, 0), List.of(), at(-3, 0), 40, List.of());

		assertNull(CombatSpots.spotFor(BotPosition.BEHIND, noFacing, 2f));
	}

	private static Situation healerAt(Vector3f from, List<Vector3f> members) {
		return new Situation(ENEMY, 1, 0, at(-12, 0), members, from, 40, List.of());
	}

	@Test
	void aHealerBehindTheEnemyAndInReachOfEveryoneIsPlaced() {
		assertTrue(CombatSpots.isHealerPlaced(healerAt(at(-14, 0), List.of(at(-10, 5), at(3, 0))), 25f));
	}

	@Test
	void aHealerInFrontOfTheEnemyIsNotPlaced() {
		assertFalse(CombatSpots.isHealerPlaced(healerAt(at(10, 0), List.of(at(-10, 5))), 25f));
	}

	@Test
	void aHealerInMeleeRangeIsNotPlaced() {
		assertFalse(CombatSpots.isHealerPlaced(healerAt(at(-3, 0), List.of(at(-10, 5))), 25f));
	}

	@Test
	void aHealerOutOfReachOfOneMemberIsNotPlaced() {
		assertFalse(CombatSpots.isHealerPlaced(healerAt(at(-14, 0), List.of(at(-10, 5), at(-14, 30))), 25f));
	}

	@Test
	void aHealerWithNothingToHealOrNobodyToHealHasNoPlaceToBeWrongAbout() {
		assertTrue(CombatSpots.isHealerPlaced(healerAt(at(10, 0), List.of()), 25f), "nobody to heal");
		assertTrue(CombatSpots.isHealerPlaced(healerAt(at(10, 0), List.of(at(-10, 5))), 0f), "no heal that reaches anybody");
	}

	@Test
	void theSpotAHealerIsSentToIsAlwaysPlaced() {
		List<Vector3f> members = List.of(at(-10, 8), at(-12, -6), at(3, 0));
		Situation situation = healerAt(at(-14, 0), members);
		Vector3f spot = CombatSpots.spotFor(BotPosition.HEALER, situation, 25f);

		assertNotNull(spot);
		assertTrue(CombatSpots.isHealerPlaced(new Situation(ENEMY, 1, 0, at(-12, 0), members, spot, 40, List.of()), 25f), "arriving must end the walk");
	}

	private static Situation tankAt(Vector3f from) {
		return new Situation(ENEMY, 1, 0, at(-12, 0), List.of(), from, 40, List.of());
	}

	@Test
	void aTankOnTheFarSideOfTheEnemyFromTheGroupIsPlaced() {
		assertTrue(CombatSpots.isTankPlaced(tankAt(at(2, 0))));
		assertTrue(CombatSpots.isTankPlaced(tankAt(at(1.5f, 1.5f))), "within sixty degrees of straight opposite is still placed");
	}

	@Test
	void aTankOnTheGroupsSideOfTheEnemyIsNotPlaced() {
		assertFalse(CombatSpots.isTankPlaced(tankAt(at(-2, 0))));
		assertFalse(CombatSpots.isTankPlaced(tankAt(at(0, 2))), "at right angles to the group it has not put the enemy's back to them");
	}

	@Test
	void aGroupOnTopOfTheEnemySaysNothingAboutWhichSideToStandOn() {
		Situation onTop = new Situation(ENEMY, 1, 0, at(0.1f, 0), List.of(), at(-2, 0), 40, List.of());

		assertTrue(CombatSpots.isTankPlaced(onTop));
	}

	@Test
	void theSpotATankIsSentToIsAlwaysPlaced() {
		Vector3f spot = CombatSpots.spotFor(BotPosition.FRONT, tankAt(at(-5, 3)), 2f);

		assertNotNull(spot);
		assertTrue(CombatSpots.isTankPlaced(tankAt(spot)), "arriving must end the walk");
	}
}
