package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.ClericPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.ClericPlaybook.Decision;
import com.aionemu.gameserver.playerbot.combat.playbook.ClericPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.ClericPlaybook.Situation;
import com.aionemu.gameserver.playerbot.combat.playbook.ClericPlaybook.Who;

/**
 * The cleric's decisions, argued with the thresholds of the guide's "Clerc 101". Nothing here needs a world: a {@link Situation} is built by hand.
 */
class ClericPlaybookTest {

	/** A mutable stand-in for a {@link Situation}, calm and alone at full health, to be changed by the test and then built. */
	private static final class S {

		int hp = 100, mp = 100, level = 60, dp = 0, tankHp = -1, memberHp = -1, hurtUnder60, hurtUnder30, othersUnder90, debuffedCount, deadCount, tankLoss, membersLosing;
		boolean inTeam, inFight, assailed, hasTank, tankHasRejuvenation, tankHasSavingGrace, hasMember, selfHasRejuvenation, penanceUp, shieldUp, shroudUp, amplificationUp,
			focusUp, selfDebuffed, tankDebuffed, hasDebuffedMember, controlled, shockChainOpen, justControlled, hasMateToRaise, hasDeadMate, chanterInGroup, inInstance,
			mending, hasOtherEnemy, hasAssailant, targetInMelee;
		final Set<Move> onTarget = EnumSet.noneOf(Move.class), ready = EnumSet.noneOf(Move.class);

		S ready(Move... moves) {
			ready.addAll(Set.of(moves));
			return this;
		}

		/** A group with a tank at the given health, the cleric in a fight. */
		S group(int tankHp) {
			inTeam = true;
			inFight = true;
			hasTank = true;
			this.tankHp = tankHp;
			return this;
		}

		Situation build() {
			return new Situation(hp, mp, level, dp, inTeam, inFight, assailed, hasTank, tankHp, tankHasRejuvenation, tankHasSavingGrace, hasMember, memberHp, hurtUnder60,
				hurtUnder30, othersUnder90, selfHasRejuvenation, penanceUp, shieldUp, shroudUp, amplificationUp, focusUp, selfDebuffed, tankDebuffed, hasDebuffedMember,
				debuffedCount, controlled, shockChainOpen, justControlled, hasMateToRaise, hasDeadMate, deadCount, chanterInGroup, inInstance, tankLoss, membersLosing, mending,
				hasOtherEnemy, hasAssailant, targetInMelee, onTarget, ready);
		}
	}

	private static Decision heal(S s) {
		return ClericPlaybook.decideHeal(s.build());
	}

	private static Move moveOf(Decision decision) {
		return decision == null ? null : decision.move();
	}

	private static void assertCasts(Move move, Who who, Decision decision) {
		assertNotNull(decision, "expected " + move + " on " + who);
		assertEquals(move, decision.move());
		assertEquals(who, decision.who());
	}

	// --- in a group -------------------------------------------------------------------------------------------------------------------------------------

	@Test
	void nobodyIsHealedWhileEveryoneIsAboveNinetyPercent() {
		S s = new S().group(95).ready(HEALING_LIGHT, HEALING_GRACE, FLASH_OF_RECOVERY, HEALING_WIND);
		s.hp = 92;

		assertNull(heal(s));
	}

	@Test
	void aMemberUnderEightyGetsTheStandardHeal() {
		S s = new S().group(100).ready(HEALING_LIGHT, HEALING_GRACE);
		s.hasMember = true;
		s.memberHp = 75;

		assertCasts(HEALING_LIGHT, Who.MEMBER, heal(s));
	}

	@Test
	void theTankUnderFortyGetsTheBigHealAndNotTheStandardOne() {
		S s = new S().group(38).ready(HEALING_LIGHT, HEALING_GRACE);

		assertCasts(HEALING_GRACE, Who.TANK, heal(s));
	}

	@Test
	void theBigHealIsNotBegunWhileSomethingIsHittingTheCleric() {
		S s = new S().group(38).ready(HEALING_LIGHT, HEALING_GRACE);
		s.assailed = true;

		assertCasts(HEALING_LIGHT, Who.TANK, heal(s));
	}

	@Test
	void theTankAboutToDieGetsTheInstantHealFirst() {
		S s = new S().group(25).ready(HEALING_LIGHT, HEALING_GRACE, FLASH_OF_RECOVERY);

		assertCasts(FLASH_OF_RECOVERY, Who.TANK, heal(s));
	}

	@Test
	void savingGraceGoesOnTheTankUnderSixtyOnceAndNotAgainWhileItIsOn() {
		S s = new S().group(55).ready(SAVING_GRACE, HEALING_LIGHT);
		assertCasts(SAVING_GRACE, Who.TANK, heal(s));

		s.tankHasSavingGrace = true;
		assertCasts(HEALING_LIGHT, Who.TANK, heal(s));
	}

	@Test
	void theClericAndTheTankAreTheSameRankAndTheLowerGoesFirst() {
		S s = new S().group(60).ready(HEALING_LIGHT);
		s.hp = 50;
		assertEquals(Who.SELF, ClericPlaybook.whoNeedsAHeal(s.build()));

		s.hp = 70;
		assertEquals(Who.TANK, ClericPlaybook.whoNeedsAHeal(s.build()));
	}

	@Test
	void aMateNearlyDeadGoesBeforeATankAndClericThatAreNotInRealDanger() {
		S s = new S().group(70);
		s.hp = 75;
		s.hasMember = true;
		s.memberHp = 20;
		assertEquals(Who.MEMBER, ClericPlaybook.whoNeedsAHeal(s.build()));

		s.tankHp = 45; // the tank is in danger too: the tank first
		assertEquals(Who.TANK, ClericPlaybook.whoNeedsAHeal(s.build()));
	}

	@Test
	void twoMembersUnderSixtyAreHealedByTheAreaHeal() {
		S s = new S().group(55).ready(HEALING_WIND, HEALING_LIGHT);
		s.hurtUnder60 = 2;

		assertCasts(HEALING_WIND, Who.SELF, heal(s));
	}

	@Test
	void aHurtClericThatIsNotInDangerHealsItselfThroughTheAreaHeal() {
		S s = new S().group(100).ready(HEALING_WIND, HEALING_LIGHT);
		s.hp = 75;
		s.othersUnder90 = 1;

		assertCasts(HEALING_WIND, Who.SELF, heal(s));

		s.hp = 50; // in danger now: the standard heal, on itself
		assertCasts(HEALING_LIGHT, Who.SELF, heal(s));
	}

	@Test
	void theHealOverTimeIsKeptOnTheTankInAFightAndNotRecastWhileItIsOn() {
		S s = new S().group(90).ready(REJUVENATION);
		assertCasts(REJUVENATION, Who.TANK, heal(s));

		s.tankHasRejuvenation = true;
		assertNull(heal(s));

		s.tankHasRejuvenation = false;
		s.inFight = false;
		assertNull(heal(s), "only in a fight");
	}

	@Test
	void afterPenanceTheClericRejuvenatesItself() {
		S s = new S().group(100).ready(REJUVENATION);
		s.penanceUp = true;
		s.hp = 85;

		assertCasts(REJUVENATION, Who.SELF, heal(s));
	}

	@Test
	void cleansingComesAfterHealingAndTheClericIsCleansedBeforeTheTank() {
		S s = new S().group(100).ready(CLEANSE);
		s.selfDebuffed = true;
		s.tankDebuffed = true;
		s.debuffedCount = 2;
		assertCasts(CLEANSE, Who.SELF, heal(s));

		s.selfDebuffed = false;
		assertCasts(CLEANSE, Who.TANK, heal(s));

		s.tankDebuffed = false;
		s.hasDebuffedMember = true;
		s.mp = 40;
		assertNull(heal(s), "another member only with mana above 50 %");
		s.mp = 80;
		assertCasts(CLEANSE, Who.MEMBER, heal(s));
	}

	@Test
	void aHealComesBeforeACleanse() {
		S s = new S().group(30).ready(CLEANSE, HEALING_LIGHT);
		s.selfDebuffed = true;

		assertEquals(HEALING_LIGHT, moveOf(heal(s)));
	}

	@Test
	void theAreaCleanseIsPreferredWhenSeveralNeedIt() {
		S s = new S().group(100).ready(CLEANSE, SPLENDOR_OF_PURIFICATION);
		s.selfDebuffed = true;
		s.debuffedCount = 3;

		assertCasts(SPLENDOR_OF_PURIFICATION, Who.SELF, heal(s));
	}

	@Test
	void acquittalNeedsSeveralMembersToHaveLostAWholeBarAndTheRightLevel() {
		S s = new S().group(100).ready(ACQUITTAL);
		s.level = 40;
		s.dp = 2000;
		s.membersLosing = 2;
		assertCasts(ACQUITTAL, Who.SELF, heal(s));

		s.membersLosing = 1;
		assertNull(heal(s));
		s.membersLosing = 3;
		s.level = 55;
		assertNull(heal(s), "from 50 it is Brilliant Protection that answers");
		s.dp = 1000;
		s.level = 40;
		assertNull(heal(s), "and it costs 2000");
	}

	@Test
	void brilliantProtectionWhenTheClericIsAboutToDieOrTheTankHasLostTwoBars() {
		S s = new S().group(100).ready(BRILLIANT_PROTECTION);
		s.dp = 4000;
		s.tankLoss = 210;
		assertCasts(BRILLIANT_PROTECTION, Who.SELF, heal(s));

		s.tankLoss = 50;
		assertNull(heal(s));
		s.hp = 15;
		assertCasts(BRILLIANT_PROTECTION, Who.SELF, heal(s));

		s.dp = 3000;
		assertNotEquals(BRILLIANT_PROTECTION, moveOf(heal(s)), "it costs 4000");
	}

	@Test
	void aHeldClericCastsNothingButRemoveShock() {
		S s = new S().group(30).ready(HEALING_GRACE, REMOVE_SHOCK);
		s.controlled = true;

		assertNull(heal(s));
		assertCasts(REMOVE_SHOCK, Who.SELF, ClericPlaybook.decideFight(s.build()));

		s.ready.remove(REMOVE_SHOCK);
		assertNull(ClericPlaybook.decideFight(s.build()));
	}

	@Test
	void afterRemoveShockTheChainIsTheVeilWhenHurtAndTheStormOtherwise() {
		S s = new S().group(100).ready(IMPERVIOUS_VEIL, STORM_OF_VENGEANCE);
		s.shockChainOpen = true;
		assertCasts(STORM_OF_VENGEANCE, Who.ENEMY, ClericPlaybook.decideFight(s.build()));

		s.hp = 25;
		assertCasts(IMPERVIOUS_VEIL, Who.SELF, ClericPlaybook.decideFight(s.build()));
	}

	@Test
	void prayerOfFocusFollowsBeingHeldAndIsNotRecastWhileOn() {
		S s = new S().group(100).ready(PRAYER_OF_FOCUS);
		s.justControlled = true;
		assertCasts(PRAYER_OF_FOCUS, Who.SELF, ClericPlaybook.decideFight(s.build()));

		s.focusUp = true;
		assertNull(ClericPlaybook.decideFight(s.build()));
	}

	// --- alone ------------------------------------------------------------------------------------------------------------------------------------------

	@Test
	void aClericAloneUnderFortyHealsToFullWithTheInstantSpellsFirst() {
		S s = new S().ready(FLASH_OF_RECOVERY, LIGHT_OF_RECOVERY, HEALING_LIGHT, HEALING_GRACE);
		s.inFight = true;
		s.hp = 35;
		assertCasts(FLASH_OF_RECOVERY, Who.SELF, heal(s));

		s.ready.remove(FLASH_OF_RECOVERY);
		assertCasts(LIGHT_OF_RECOVERY, Who.SELF, heal(s));

		s.ready.remove(LIGHT_OF_RECOVERY);
		assertCasts(HEALING_LIGHT, Who.SELF, heal(s));

		s.ready.remove(HEALING_LIGHT);
		assertNull(heal(s), "the long big heal is not cast in a fight alone: it is the one that gets interrupted");
	}

	@Test
	void onceItBeganAClericAloneKeepsHealingAboveFortyUntilFull() {
		S s = new S().ready(HEALING_LIGHT);
		s.inFight = true;
		s.hp = 70;
		s.mending = true;

		assertCasts(HEALING_LIGHT, Who.SELF, heal(s));
	}

	@Test
	void aClericAloneHealsAfterAFightUnderNinetyAndNotAbove() {
		S s = new S().ready(HEALING_LIGHT);
		s.hp = 85;
		assertCasts(HEALING_LIGHT, Who.SELF, heal(s));

		s.hp = 93;
		assertNull(heal(s));
	}

	@Test
	void aClericAloneRaisesBlessedShieldUnderThirtyAndSalvationUnderTwenty() {
		S s = new S().ready(BLESSED_SHIELD, SALVATION, FLASH_OF_RECOVERY);
		s.inFight = true;
		s.dp = 2000;
		s.hp = 25;
		assertCasts(BLESSED_SHIELD, Who.SELF, heal(s));

		s.shieldUp = true;
		assertCasts(FLASH_OF_RECOVERY, Who.SELF, heal(s));

		s.hp = 15;
		assertCasts(SALVATION, Who.SELF, heal(s));

		s.dp = 1999;
		assertCasts(FLASH_OF_RECOVERY, Who.SELF, heal(s));
	}

	@Test
	void aClericAloneKeepsTheHealOverTimeOnItselfInAFight() {
		S s = new S().ready(REJUVENATION);
		s.inFight = true;
		s.hp = 80;
		assertCasts(REJUVENATION, Who.SELF, heal(s));

		s.selfHasRejuvenation = true;
		assertNull(heal(s));
	}

	@Test
	void lifeCurtainAloneNeedsSeventyPercentAndSomethingToRestore() {
		S s = new S().ready(LIFE_CURTAIN);
		s.inFight = true;
		s.hp = 100;
		s.mp = 100;
		assertNull(heal(s), "nothing to restore");

		s.mp = 60;
		assertCasts(LIFE_CURTAIN, Who.SELF, heal(s));

		s.hp = 60;
		assertNotEquals(LIFE_CURTAIN, moveOf(heal(s)), "under 70 % it is not cast");
	}

	// --- reviving ---------------------------------------------------------------------------------------------------------------------------------------

	@Test
	void lightOfResurrectionOnlyOutOfAFightAndOnAMemberItIsToRaise() {
		S s = new S().group(100).ready(LIGHT_OF_RESURRECTION);
		s.hasMateToRaise = true;
		assertNull(ClericPlaybook.decideRevive(s.build()), "not in a fight");

		s.inFight = false;
		assertCasts(LIGHT_OF_RESURRECTION, Who.DEAD, ClericPlaybook.decideRevive(s.build()));

		s.hasMateToRaise = false; // a chanter in the group, or another cleric, is to do it
		assertNull(ClericPlaybook.decideRevive(s.build()));
	}

	@Test
	void theAreaResurrectionNeedsMoreThanThreeDeadAndAnInstance() {
		S s = new S().group(100).ready(RESURRECTION_LOCI);
		s.hasDeadMate = true;
		s.deadCount = 4;
		assertNull(ClericPlaybook.decideRevive(s.build()), "outside an instance");

		s.inInstance = true;
		assertCasts(RESURRECTION_LOCI, Who.DEAD, ClericPlaybook.decideRevive(s.build()));

		s.deadCount = 3;
		assertNull(ClericPlaybook.decideRevive(s.build()), "three is not more than three");
	}

	// --- utilities --------------------------------------------------------------------------------------------------------------------------------------

	@Test
	void penanceUnderHalfManaAndNotWhileItIsOn() {
		S s = new S().group(100).ready(PENANCE);
		s.mp = 40;
		assertCasts(PENANCE, Who.SELF, ClericPlaybook.decideUtility(s.build()));

		s.penanceUp = true;
		assertNull(ClericPlaybook.decideUtility(s.build()));
		s.penanceUp = false;
		s.hp = 30;
		assertNull(ClericPlaybook.decideUtility(s.build()), "it is paid for in health");
	}

	@Test
	void sagesWisdomAloneWheneverReadyAndInAGroupOnlyInAnEmergency() {
		S solo = new S().ready(SAGES_WISDOM);
		solo.inFight = true;
		assertCasts(SAGES_WISDOM, Who.SELF, ClericPlaybook.decideUtility(solo.build()));

		S group = new S().group(100).ready(SAGES_WISDOM);
		assertNull(ClericPlaybook.decideUtility(group.build()));
		group.mp = 8;
		assertCasts(SAGES_WISDOM, Who.SELF, ClericPlaybook.decideUtility(group.build()));
		group.mp = 100;
		group.tankHp = 9;
		assertCasts(SAGES_WISDOM, Who.SELF, ClericPlaybook.decideUtility(group.build()));
	}

	@Test
	void theShroudAndAmplificationAreCastWhenReadyAndNotWhileOn() {
		S s = new S().group(100).ready(IMMORTAL_SHROUD, AMPLIFICATION);
		assertCasts(IMMORTAL_SHROUD, Who.SELF, ClericPlaybook.decideUtility(s.build()));

		s.shroudUp = true;
		assertCasts(AMPLIFICATION, Who.SELF, ClericPlaybook.decideUtility(s.build()));

		s.amplificationUp = true;
		assertNull(ClericPlaybook.decideUtility(s.build()));
	}

	@Test
	void stabilityGoesAtTheEnemyThatIsHittingTheClericAndOnlyInAGroup() {
		S s = new S().group(100).ready(STABILITY);
		s.hasAssailant = true;
		assertCasts(STABILITY, Who.ASSAILANT, ClericPlaybook.decideUtility(s.build()));

		s.inTeam = false;
		assertNull(ClericPlaybook.decideUtility(s.build()));
	}

	@Test
	void rootGoesAtAnotherEnemyOfTheGroup() {
		S s = new S().group(100).ready(ROOT);
		s.hasOtherEnemy = true;

		assertCasts(ROOT, Who.OTHER_ENEMY, ClericPlaybook.decideUtility(s.build()));
	}

	// --- damage -----------------------------------------------------------------------------------------------------------------------------------------

	@Test
	void theDamageOverTimeComesFirstAndIsNotCastOverItself() {
		S s = new S().ready(EARTHS_WRATH, AERO_SNARE, CHASTISE);
		s.inFight = true;
		assertCasts(EARTHS_WRATH, Who.ENEMY, ClericPlaybook.decideOffence(s.build()));

		s.onTarget.add(EARTHS_WRATH);
		assertCasts(AERO_SNARE, Who.ENEMY, ClericPlaybook.decideOffence(s.build()));

		s.onTarget.addAll(Set.of(AERO_SNARE, CHASTISE));
		assertNull(ClericPlaybook.decideOffence(s.build()));
	}

	@Test
	void inAGroupDamageIsOnlyForTheManaItHasToSpare() {
		S s = new S().group(100).ready(EARTHS_WRATH);
		s.mp = 49;
		assertNull(ClericPlaybook.decideOffence(s.build()));

		s.mp = 50;
		assertCasts(EARTHS_WRATH, Who.ENEMY, ClericPlaybook.decideOffence(s.build()));

		S solo = new S().ready(EARTHS_WRATH);
		solo.inFight = true;
		solo.mp = 5;
		assertCasts(EARTHS_WRATH, Who.ENEMY, ClericPlaybook.decideOffence(solo.build()));
	}

	@Test
	void theBlowInMeleeIsForAClericAloneThatSomethingHasReached() {
		S s = new S().ready(HALLOWED_STRIKE, SLOWING_HAMMER);
		s.inFight = true;
		assertNull(ClericPlaybook.decideOffence(s.build()));

		s.targetInMelee = true;
		assertCasts(HALLOWED_STRIKE, Who.ENEMY, ClericPlaybook.decideOffence(s.build()));

		s.ready.remove(HALLOWED_STRIKE);
		assertCasts(SLOWING_HAMMER, Who.ENEMY, ClericPlaybook.decideOffence(s.build()));

		S group = new S().group(100).ready(HALLOWED_STRIKE);
		group.targetInMelee = true;
		assertNull(ClericPlaybook.decideOffence(group.build()), "never in a group");
	}

	@Test
	void aHeldClericDoesNotAttack() {
		S s = new S().ready(EARTHS_WRATH);
		s.controlled = true;

		assertNull(ClericPlaybook.decideOffence(s.build()));
		assertNull(ClericPlaybook.decideUtility(s.build()));
	}
}
