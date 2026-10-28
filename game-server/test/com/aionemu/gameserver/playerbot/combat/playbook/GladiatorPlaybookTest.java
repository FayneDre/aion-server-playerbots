package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.GladiatorPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.GladiatorPlaybook.Situation;

/**
 * The gladiator's rules, decided without a world. See {@code docs/classes/history/gladiator-plan.md}.
 */
class GladiatorPlaybookTest {

	/** The offence of milestone 6, kept out of the earlier tests, which would otherwise all end on Weakening Blow where they expect nothing. */
	private static final Set<Move> OFFENCE = EnumSet.of(Move.BERSERKING, Move.EMPYREAN_BLESSING, Move.DAEVIC_FURY, Move.EXPLOSION_OF_RAGE, Move.CHARGE,
		Move.CLEAVE, Move.FEROCIOUS_CHOP, Move.WEAKENING_BLOW, Move.SURE_STRIKE, Move.AERIAL_LOCKDOWN, Move.CRUSHING_BLOW, Move.PULL_SECOND,
		Move.DEFENSE_PREPARATION, Move.TAUNT, Move.MOCKING_BLAST, Move.BODY_SLICE);
	private static final Set<Move> ALL = EnumSet.complementOf(EnumSet.copyOf(OFFENCE));

	/** A gladiator in a fight with everything ready, Slaughter up and nothing else going on, at the given health. */
	private static Situation fight(int hp) {
		return new Situation(hp, false, false, false, false, false, false, true, false, false, false, ALL);
	}

	private static Situation with(Situation s, Set<Move> ready) {
		return new Situation(s.hpPercent(), s.controlled(), s.recentlyControlled(), s.shockChainOpen(), s.attacked(), s.targetDown(), s.targetAerial(),
			s.slaughterUp(), s.wallUp(), s.armorUp(), s.devotionUp(), ready);
	}

	private static Set<Move> without(Move... moves) {
		Set<Move> ready = EnumSet.copyOf(ALL);
		for (Move move : moves)
			ready.remove(move);
		return ready;
	}

	// the posture

	@Test
	void slaughterGoesUpWhenItIsOff() {
		assertEquals(Move.SLAUGHTER, GladiatorPlaybook.decideAtRest(Situation.atRest(false, ALL)));
	}

	@Test
	void slaughterIsNotCastAgainWhenItIsOn() {
		// a toggle cast while it is on switches it off
		assertNull(GladiatorPlaybook.decideAtRest(Situation.atRest(true, ALL)));
	}

	@Test
	void nothingIsSaidAboutAPostureTheGladiatorDoesNotKnow() {
		assertNull(GladiatorPlaybook.decideAtRest(Situation.atRest(false, EnumSet.noneOf(Move.class))), "under level 13 there is no Slaughter");
	}

	@Test
	void theDefensivesWaitForAFight() {
		assertNull(GladiatorPlaybook.decideAtRest(new Situation(10, false, true, false, true, false, false, true, false, false, false, ALL)));
	}

	@Test
	void slaughterLostInAFightIsPutBack() {
		assertEquals(Move.SLAUGHTER, GladiatorPlaybook.decide(new Situation(100, false, false, false, false, false, false, false, false, false, false, ALL)));
	}

	// control

	@Test
	void controlledItCastsRemoveShockAndNothingElse() {
		Situation stunned = new Situation(20, true, false, false, true, false, false, false, false, false, false, ALL);
		assertEquals(Move.REMOVE_SHOCK, GladiatorPlaybook.decide(stunned));
		assertNull(GladiatorPlaybook.decide(with(stunned, without(Move.REMOVE_SHOCK))), "nothing else goes through a stun");
	}

	@Test
	void afterRemoveShockWrathfulExplosionThenFerocity() {
		Situation chain = new Situation(100, false, false, true, false, false, false, true, false, false, false, ALL);
		assertEquals(Move.WRATHFUL_EXPLOSION, GladiatorPlaybook.decide(chain));
		assertEquals(Move.FEROCITY, GladiatorPlaybook.decide(with(chain, without(Move.WRATHFUL_EXPLOSION))), "before level 53");
	}

	@Test
	void unwaveringDevotionAnswersARecentControlOnce() {
		Situation after = new Situation(100, false, true, false, false, false, false, true, false, false, false, ALL);
		assertEquals(Move.UNWAVERING_DEVOTION, GladiatorPlaybook.decide(after));
		assertNull(GladiatorPlaybook.decide(new Situation(100, false, true, false, false, false, false, true, false, false, true, ALL)), "already up");
	}

	// health

	@Test
	void secondWindUnderHalfHealth() {
		assertEquals(Move.SECOND_WIND, GladiatorPlaybook.decide(fight(49)));
		assertNotEquals(Move.SECOND_WIND, GladiatorPlaybook.decide(fight(50)));
	}

	@Test
	void drainingSwordUnderForty() {
		assertEquals(Move.DRAINING_SWORD, GladiatorPlaybook.decide(with(fight(39), without(Move.SECOND_WIND))));
		assertEquals(Move.COUNTER_LEECH, GladiatorPlaybook.decide(with(fight(45), without(Move.SECOND_WIND))), "between 40 and 50: the smaller drain");
	}

	@Test
	void counterLeechUnderSixty() {
		assertEquals(Move.COUNTER_LEECH, GladiatorPlaybook.decide(fight(59)));
		assertNull(GladiatorPlaybook.decide(fight(60)));
	}

	@Test
	void drainingBlowOnlyOnATargetKnockedDown() {
		Situation down = new Situation(55, false, false, false, false, true, false, true, false, false, false, ALL);
		assertEquals(Move.DRAINING_BLOW, GladiatorPlaybook.decide(down));
		assertEquals(Move.COUNTER_LEECH, GladiatorPlaybook.decide(fight(55)), "standing: Counter Leech instead");
		assertEquals(Move.FINAL_STRIKE, GladiatorPlaybook.decide(new Situation(80, false, false, false, false, true, false, true, false, false, false, ALL)),
			"healthy: no drain, the opening is used for damage");
	}

	// attacked

	@Test
	void attackedItRaisesWallOfSteelThenArmorOfAttrition() {
		Situation hit = new Situation(100, false, false, false, true, false, false, true, false, false, false, ALL);
		assertEquals(Move.WALL_OF_STEEL, GladiatorPlaybook.decide(hit));
		assertEquals(Move.ARMOR_OF_ATTRITION, GladiatorPlaybook.decide(new Situation(100, false, false, false, true, false, false, true, true, false, false, ALL)));
		assertNull(GladiatorPlaybook.decide(new Situation(100, false, false, false, true, false, false, true, true, true, false, ALL)), "both up");
	}

	@Test
	void notAttackedNoDefensive() {
		assertNull(GladiatorPlaybook.decide(fight(100)));
	}

	@Test
	void healthComesBeforeTheDefensives() {
		Situation hitAndHurt = new Situation(30, false, false, false, true, false, false, true, false, false, false, ALL);
		assertEquals(Move.SECOND_WIND, GladiatorPlaybook.decide(hitAndHurt));
	}

	// the openings

	/** A healthy gladiator in a fight whose target is knocked down or held in the air. */
	private static Situation opening(boolean down, boolean aerial) {
		return new Situation(100, false, false, false, false, down, aerial, true, false, false, false, ALL);
	}

	@Test
	void finalStrikeFirstOnEitherOpening() {
		assertEquals(Move.FINAL_STRIKE, GladiatorPlaybook.decide(opening(true, false)));
		assertEquals(Move.FINAL_STRIKE, GladiatorPlaybook.decide(opening(false, true)));
	}

	@Test
	void onATargetDownCripplingCutThenSpringingSlice() {
		assertEquals(Move.CRIPPLING_CUT, GladiatorPlaybook.decide(with(opening(true, false), without(Move.FINAL_STRIKE))));
		assertEquals(Move.SPRINGING_SLICE, GladiatorPlaybook.decide(with(opening(true, false), without(Move.FINAL_STRIKE, Move.CRIPPLING_CUT))));
		assertNull(GladiatorPlaybook.decide(with(opening(true, false), without(Move.FINAL_STRIKE, Move.CRIPPLING_CUT, Move.SPRINGING_SLICE))));
	}

	@Test
	void onATargetInTheAirCrashingBlow() {
		assertEquals(Move.CRASHING_BLOW, GladiatorPlaybook.decide(with(opening(false, true), without(Move.FINAL_STRIKE))));
		assertNull(GladiatorPlaybook.decide(with(opening(false, true), without(Move.FINAL_STRIKE, Move.CRASHING_BLOW))), "nothing for the ground skills");
	}

	@Test
	void noOpeningNothingConditional() {
		assertNull(GladiatorPlaybook.exploitTheOpening(opening(false, false)));
	}

	@Test
	void theOpeningComesBeforeTheDefensivesAndAfterTheHeal() {
		Situation hitWithTargetDown = new Situation(100, false, false, false, true, true, false, true, false, false, false, ALL);
		assertEquals(Move.FINAL_STRIKE, GladiatorPlaybook.decide(hitWithTargetDown), "the window closes, the defensive can wait a second");
		Situation hurtWithTargetDown = new Situation(30, false, false, false, false, true, false, true, false, false, false, ALL);
		assertEquals(Move.SECOND_WIND, GladiatorPlaybook.decide(hurtWithTargetDown));
	}

	// the areas

	/** A healthy gladiator, Slaughter up, nothing to answer, with chain C open or not and so many enemies on it. */
	private static Situation areas(int hp, boolean chainOpen, int enemies, Set<Move> ready) {
		return new Situation(hp, false, false, false, false, false, false, true, false, false, false, ready, chainOpen, enemies);
	}

	@Test
	void afterAbsorbingFuryPressureWaveAboveEightyRoilingHackBelow() {
		assertEquals(Move.PRESSURE_WAVE, GladiatorPlaybook.decide(areas(81, true, 1, ALL)));
		assertEquals(Move.ROILING_HACK, GladiatorPlaybook.decide(areas(80, true, 1, ALL)));
	}

	@Test
	void theOtherLinkWhenTheWantedOneIsCooling() {
		assertEquals(Move.PRESSURE_WAVE, GladiatorPlaybook.decide(areas(60, true, 1, without(Move.ROILING_HACK))), "the guide's \"ou si Absorption Wave en CD\"");
		assertEquals(Move.ROILING_HACK, GladiatorPlaybook.decide(areas(95, true, 1, without(Move.PRESSURE_WAVE))));
	}

	@Test
	void oneEnemyNoAreaOfThePlaybooks() {
		assertNull(GladiatorPlaybook.decide(areas(100, false, 1, ALL)), "one enemy: the generic order, which keeps Absorbing Fury");
	}

	@Test
	void severalEnemiesTheAreasInTheGuidesOrder() {
		assertEquals(Move.ABSORBING_FURY, GladiatorPlaybook.decide(areas(100, false, 2, ALL)));
		assertEquals(Move.SEVERE_PRECISION_CUT, GladiatorPlaybook.decide(areas(100, false, 3, without(Move.ABSORBING_FURY))));
		assertEquals(Move.PIERCING_RUPTURE, GladiatorPlaybook.decide(areas(100, false, 3, without(Move.ABSORBING_FURY, Move.SEVERE_PRECISION_CUT))));
		assertEquals(Move.EARTHQUAKE_WAVE,
			GladiatorPlaybook.decide(areas(100, false, 3, without(Move.ABSORBING_FURY, Move.SEVERE_PRECISION_CUT, Move.PIERCING_RUPTURE))));
		assertEquals(Move.WHIRLING_ADVANCE, GladiatorPlaybook.decide(
			areas(100, false, 3, without(Move.ABSORBING_FURY, Move.SEVERE_PRECISION_CUT, Move.PIERCING_RUPTURE, Move.EARTHQUAKE_WAVE))));
	}

	@Test
	void theDefensivesAndTheChainComeBeforeTheAreas() {
		Situation hitBySeveral = new Situation(100, false, false, false, true, false, false, true, false, false, false, ALL, false, 3);
		assertEquals(Move.WALL_OF_STEEL, GladiatorPlaybook.decide(hitBySeveral));
		assertEquals(Move.PRESSURE_WAVE, GladiatorPlaybook.decide(areas(100, true, 3, ALL)), "the open chain closes, the next area can wait");
	}

	// the offence

	private static final Set<Move> EVERYTHING = EnumSet.allOf(Move.class);

	/** A healthy gladiator, Slaughter up, one enemy, everything ready, with this much DP and its target this far. */
	private static Situation offence(int dp, boolean inReach, float distance, boolean chainToFollow, boolean berserkingUp, Set<Move> ready) {
		return new Situation(100, false, false, false, false, false, false, true, false, false, false, ready, false, 1,
			new GladiatorPlaybook.Offence(dp, inReach, distance, chainToFollow, berserkingUp));
	}

	private static Set<Move> everythingBut(Move... moves) {
		Set<Move> ready = EnumSet.allOf(Move.class);
		for (Move move : moves)
			ready.remove(move);
		return ready;
	}

	@Test
	void inReachBerserkingFirst() {
		assertEquals(Move.BERSERKING, GladiatorPlaybook.decide(offence(0, true, 2, false, false, EVERYTHING)));
	}

	@Test
	void zikelsThreatAtFourThousandAndThePointsKeptForIt() {
		assertEquals(Move.EMPYREAN_BLESSING, GladiatorPlaybook.decide(offence(4000, true, 2, false, true, EVERYTHING)));
		assertEquals(Move.WEAKENING_BLOW, GladiatorPlaybook.decide(offence(3999, true, 2, false, true, EVERYTHING)),
			"short of 4000: nothing spent, the points wait for it");
	}

	@Test
	void daevicFuryWhenZikelsThreatIsCoolingOrUnknown() {
		assertEquals(Move.DAEVIC_FURY, GladiatorPlaybook.decide(offence(2000, true, 2, false, true, everythingBut(Move.EMPYREAN_BLESSING))));
		assertEquals(Move.WEAKENING_BLOW, GladiatorPlaybook.decide(offence(1999, true, 2, false, true, everythingBut(Move.EMPYREAN_BLESSING))));
	}

	@Test
	void explosionOfRageWithWhatNoBuffIsWaitingFor() {
		assertEquals(Move.EXPLOSION_OF_RAGE,
			GladiatorPlaybook.decide(offence(2000, true, 2, false, true, everythingBut(Move.EMPYREAN_BLESSING, Move.DAEVIC_FURY))));
	}

	@Test
	void fromAfarChargeBeyondTwentyThenCleaveThenTheLeap() {
		assertEquals(Move.CHARGE, GladiatorPlaybook.decide(offence(0, false, 25, false, false, EVERYTHING)));
		assertEquals(Move.CLEAVE, GladiatorPlaybook.decide(offence(0, false, 15, false, false, EVERYTHING)));
		assertEquals(Move.FEROCIOUS_CHOP, GladiatorPlaybook.decide(offence(0, false, 15, false, false, everythingBut(Move.CLEAVE))));
		assertNull(GladiatorPlaybook.decide(offence(0, false, 18, false, false, everythingBut(Move.CLEAVE))), "beyond the leap: the walk");
	}

	@Test
	void fromAfarNoBurst() {
		assertEquals(Move.CLEAVE, GladiatorPlaybook.decide(offence(4000, false, 12, false, false, EVERYTHING)), "the buffs wait for the contact");
	}

	@Test
	void inReachTheGuidesSingleTargetOrder() {
		Set<Move> ready = everythingBut(Move.BERSERKING);
		assertEquals(Move.WEAKENING_BLOW, GladiatorPlaybook.decide(offence(0, true, 2, false, false, ready)));
		ready.remove(Move.WEAKENING_BLOW);
		assertEquals(Move.SURE_STRIKE, GladiatorPlaybook.decide(offence(0, true, 2, false, false, ready)));
		ready.remove(Move.SURE_STRIKE);
		assertEquals(Move.AERIAL_LOCKDOWN, GladiatorPlaybook.decide(offence(0, true, 2, false, false, ready)));
		ready.remove(Move.AERIAL_LOCKDOWN);
		assertEquals(Move.CRUSHING_BLOW, GladiatorPlaybook.decide(offence(0, true, 2, false, false, ready)));
		ready.remove(Move.CRUSHING_BLOW);
		assertNull(GladiatorPlaybook.decide(offence(0, true, 2, false, false, ready)), "all cooling: the filler, the generic order's");
	}

	@Test
	void anOpenChainIsLeftToTheGenericOrder() {
		assertNull(GladiatorPlaybook.decide(offence(4000, true, 2, true, false, EVERYTHING)), "Energy Impact after Weakening Blow, not Sure Strike over it");
	}

	@Test
	void anOpenChainDoesNotHoldTheHeal() {
		Situation hurt = new Situation(30, false, false, false, false, false, false, true, false, false, false, EVERYTHING, false, 1,
			new GladiatorPlaybook.Offence(0, true, 2, true, false));
		assertEquals(Move.SECOND_WIND, GladiatorPlaybook.decide(hurt));
	}

	// two monsters at once, and when to leave

	@Test
	void aSecondMonsterIsPulledOnceTheFirstIsInReach() {
		Situation one = new Situation(100, false, false, false, false, false, false, true, false, false, false, EVERYTHING, false, 1,
			new GladiatorPlaybook.Offence(0, true, 2, false, true, true));
		assertEquals(Move.PULL_SECOND, GladiatorPlaybook.decide(one));
		Situation onTheWay = new Situation(100, false, false, false, false, false, false, true, false, false, false, EVERYTHING, false, 1,
			new GladiatorPlaybook.Offence(0, false, 12, false, true, true));
		assertEquals(Move.CLEAVE, GladiatorPlaybook.decide(onTheWay), "on the way the Cleave is for the first monster");
	}

	@Test
	void outnumberedByNormalMonstersItHolds() {
		assertEquals(ClassPlaybook.Outnumbered.STAND, GladiatorPlaybook.decideOutnumbered(false, 30));
	}

	@Test
	void anEliteOrDangerAndItLeaves() {
		assertEquals(ClassPlaybook.Outnumbered.FLEE, GladiatorPlaybook.decideOutnumbered(true, 100), "an elite among them: at any health");
		assertEquals(ClassPlaybook.Outnumbered.FLEE, GladiatorPlaybook.decideOutnumbered(false, 24), "in danger");
	}

	// the tank-dead mode

	private static GladiatorPlaybook.Group tankDead(boolean defenseUp, boolean targetElsewhere, boolean bodyChainOpen) {
		return new GladiatorPlaybook.Group(true, defenseUp, targetElsewhere, bodyChainOpen);
	}

	@Test
	void theTemplarDeadSlaughterGoesOffAndDefensePreparationOn() {
		assertEquals(Move.SLAUGHTER, GladiatorPlaybook.decideAtRest(Situation.atRest(true, tankDead(false, false, false), EVERYTHING)), "switched off");
		assertEquals(Move.DEFENSE_PREPARATION, GladiatorPlaybook.decideAtRest(Situation.atRest(false, tankDead(false, false, false), EVERYTHING)));
		assertNull(GladiatorPlaybook.decideAtRest(Situation.atRest(false, tankDead(true, false, false), EVERYTHING)));
	}

	@Test
	void theTemplarRaisedDefensePreparationGoesOffAndSlaughterBackOn() {
		GladiatorPlaybook.Group raised = new GladiatorPlaybook.Group(false, true, false, false);
		assertEquals(Move.DEFENSE_PREPARATION, GladiatorPlaybook.decideAtRest(Situation.atRest(false, raised, EVERYTHING)), "switched off");
		assertEquals(Move.SLAUGHTER, GladiatorPlaybook.decideAtRest(Situation.atRest(false, GladiatorPlaybook.Group.NONE, EVERYTHING)));
	}

	private static Situation tanking(GladiatorPlaybook.Group group, Set<Move> ready) {
		return new Situation(100, false, false, false, false, false, false, false, false, false, false, ready, false, 1,
			new GladiatorPlaybook.Offence(0, true, 2, false, true), group);
	}

	@Test
	void theTemplarDeadTauntOnATargetHittingSomebodyElseThenMockingBlastThenBodySlice() {
		assertEquals(Move.TAUNT, GladiatorPlaybook.decide(tanking(tankDead(true, true, true), EVERYTHING)));
		assertEquals(Move.MOCKING_BLAST, GladiatorPlaybook.decide(tanking(tankDead(true, false, true), EVERYTHING)), "the target is on the gladiator");
		assertEquals(Move.BODY_SLICE, GladiatorPlaybook.decide(tanking(tankDead(true, false, true), everythingBut(Move.MOCKING_BLAST))));
	}

	@Test
	void theTankingMovesNeverOutsideTheMode() {
		assertNull(GladiatorPlaybook.holdTheMonsters(tanking(new GladiatorPlaybook.Group(false, false, true, true), EVERYTHING)));
	}

	// a refused move

	@Test
	void aMoveTheEngineRefusedGivesWayToTheNextOne() {
		Situation hurtAndHit = new Situation(55, false, false, false, true, false, false, true, false, false, false, ALL);
		assertEquals(Move.COUNTER_LEECH, GladiatorPlaybook.decide(hurtAndHit));
		assertEquals(Move.WALL_OF_STEEL, GladiatorPlaybook.decide(hurtAndHit.without(Move.COUNTER_LEECH)), "Counter Leech out of reach: the defensive");
		assertTrue(hurtAndHit.ready().contains(Move.COUNTER_LEECH), "the situation asked about is left as it was");
	}
}
