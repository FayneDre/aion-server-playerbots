package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.playerbot.combat.playbook.BardPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.BardPlaybook.Situation;

/**
 * The bard's rules, decided without a world. See {@code docs/classes/plans/bard-plan.md}.
 */
class BardPlaybookTest {

	/** A healthy bard in a fight, everything ready and up, nobody hurt, nothing on it, in the stance it wants: nothing pressing. */
	private static Situation calm() {
		Situation s = new Situation().readyAll();
		s.inFight = true;
		s.impassionUp = true;
		s.dirgeOnTarget = true;
		s.assailed = true;
		return s;
	}

	private static Situation grouped(boolean cleric) {
		Situation s = calm();
		s.inTeam = true;
		s.clericInGroup = cleric;
		s.impassionUp = cleric;
		s.exultationUp = !cleric;
		return s;
	}

	// the stance

	@Test
	void theStanceIsDamageAloneAndWithAClericAndHealingWithout() {
		Situation s = new Situation();
		assertEquals(Move.IMPASSION, BardPlaybook.wantedStance(s), "alone");
		s.inTeam = true;
		s.clericInGroup = true;
		assertEquals(Move.IMPASSION, BardPlaybook.wantedStance(s), "a cleric heals");
		s.hurtUnder50 = 2;
		assertEquals(Move.IMPASSION, BardPlaybook.wantedStance(s), "not switched on a member's health, every twelve seconds");
		s.clericInGroup = false;
		assertEquals(Move.EXULTATION, BardPlaybook.wantedStance(s), "nobody else heals");
	}

	@Test
	void aBardKeepsTheOnlyStanceItWears() {
		Situation s = new Situation();
		s.knowsImpassion = false;
		assertEquals(Move.EXULTATION, BardPlaybook.wantedStance(s), "a stigma it does not wear is no stance to want");
		s.knowsExultation = false;
		assertNull(BardPlaybook.wantedStance(s));
	}

	@Test
	void theStanceIsNotSwitchedTwiceNorMidChain() {
		Situation s = new Situation().readyAll();
		assertEquals(Move.IMPASSION, BardPlaybook.stanceToTurnOn(s));
		s.impassionUp = true;
		assertNull(BardPlaybook.stanceToTurnOn(s), "already in it: cast again, a toggle turns itself off");
		s.impassionUp = false;
		s.anyChainOpen = true;
		assertNull(BardPlaybook.stanceToTurnOn(s), "a chain closes with any other cast");
	}

	// upkeep

	@Test
	void beforeAFightTheStanceThenTheMovementsThenTheShield() {
		Situation s = new Situation().readyAll();
		s.summerUp = false;
		s.autumnUp = false;
		s.odeUp = false;
		assertEquals(Move.IMPASSION, BardPlaybook.decideUpkeep(s));
		s.impassionUp = true;
		assertEquals(Move.SUMMER, BardPlaybook.decideUpkeep(s));
		s.summerUp = true;
		assertEquals(Move.AUTUMN, BardPlaybook.decideUpkeep(s));
		s.autumnUp = true;
		assertEquals(Move.PROTECTIVE_ODE, BardPlaybook.decideUpkeep(s));
		s.odeUp = true;
		assertNull(BardPlaybook.decideUpkeep(s));
	}

	// defence

	@Test
	void heldOnlyRemoveShockThenItsChain() {
		Situation s = calm();
		s.controlled = true;
		assertEquals(Move.REMOVE_SHOCK, BardPlaybook.decideDefence(s));
		assertNull(BardPlaybook.decideSupport(s), "a held bard casts nothing else");
		assertNull(BardPlaybook.decideOffence(s));
		s.controlled = false;
		s.shockChainOpen = true;
		assertEquals(Move.CHORUS_OF_FORTITUDE, BardPlaybook.decideDefence(s), "whole: the damage link");
		s.hp = 60;
		assertEquals(Move.HYMN_OF_THANKSGIVING, BardPlaybook.decideDefence(s), "hurt: the heal link");
	}

	@Test
	void springAfterAStunWinterNearlyDead() {
		Situation s = calm();
		s.justControlled = true;
		assertEquals(Move.SPRING, BardPlaybook.decideDefence(s));
		s.justControlled = false;
		s.hp = 25;
		assertEquals(Move.WINTER, BardPlaybook.decideDefence(s));
		s.winterUp = true;
		assertNotEquals(Move.WINTER, BardPlaybook.decideDefence(s), "not over itself");
	}

	@Test
	void requiemAloneAndSurroundedOnly() {
		Situation s = calm();
		s.hp = 40;
		s.enemiesWithin7m = 2;
		assertEquals(Move.REQUIEM, BardPlaybook.decideDefence(s));
		s.enemiesWithin7m = 1;
		assertNull(BardPlaybook.decideDefence(s));
		s.enemiesWithin7m = 3;
		s.inTeam = true;
		assertNotEquals(Move.REQUIEM, BardPlaybook.decideDefence(s), "in a group it would sleep what the group is hitting");
	}

	@Test
	void odeToFriendshipWhenTheGroupsTargetTurnsOnTheBard() {
		Situation s = grouped(true);
		assertNull(BardPlaybook.decideDefence(s));
		s.targetOnMe = true;
		assertEquals(Move.ODE_TO_FRIENDSHIP, BardPlaybook.decideDefence(s));
		s.inTeam = false;
		assertNull(BardPlaybook.decideDefence(s), "alone there is nobody else for it to turn to");
	}

	// heals

	@Test
	void anOpenHealChainIsCarriedOnFirst() {
		Situation s = grouped(false);
		s.hurtHp = 75;
		s.healChain1Open = true;
		assertEquals(Move.SOOTHING_COUNTERPOINT, BardPlaybook.decideSupport(s));
		s.healChain1Open = false;
		s.healChain2Open = true;
		assertEquals(Move.SOOTHING_FINALE, BardPlaybook.decideSupport(s));
		s.ready.remove(Move.SOOTHING_FINALE);
		assertEquals(Move.JOYOUS_CAROL, BardPlaybook.decideSupport(s), "the other branch of the third link");
		s.hurtHp = 95;
		assertNotEquals(Move.JOYOUS_CAROL, BardPlaybook.decideSupport(s), "nobody needs it: not a link spent on the whole");
	}

	@Test
	void theGroupsHealerHealsSoonerThanACleric() {
		Situation healer = grouped(false), second = grouped(true);
		healer.hurtHp = second.hurtHp = 65;
		assertEquals(Move.SOOTHING_MELODY, BardPlaybook.decideSupport(healer));
		assertNull(BardPlaybook.decideSupport(second), "a cleric is there for 65 %");
		second.hurtHp = 45;
		assertEquals(Move.SOOTHING_MELODY, BardPlaybook.decideSupport(second));
	}

	@Test
	void soothingHymnUnderTheBarAndNeverUnderAttack() {
		Situation s = grouped(false);
		s.hurtHp = 35;
		s.assailed = false;
		assertEquals(Move.SOOTHING_HYMN, BardPlaybook.decideSupport(s));
		s.assailed = true;
		assertEquals(Move.SOOTHING_MELODY, BardPlaybook.decideSupport(s), "five seconds of charge a hit cancels");
	}

	@Test
	void chorusOfBlessingForThreeHurt() {
		Situation s = grouped(false);
		s.hurtHp = 65;
		s.hurtUnder70 = 3;
		assertEquals(Move.CHORUS_OF_BLESSING, BardPlaybook.decideSupport(s));
		Situation second = grouped(true);
		second.hurtHp = 65;
		second.hurtUnder70 = 3;
		assertNull(BardPlaybook.decideSupport(second), "beside a cleric, three under 50 %");
		second.hurtUnder50 = 3;
		second.hurtHp = 45;
		assertEquals(Move.CHORUS_OF_BLESSING, BardPlaybook.decideSupport(second));
	}

	@Test
	void theHealOverTimeOnTheTankOnce() {
		Situation s = grouped(false);
		s.tankHp = 75;
		assertEquals(Move.HYMN_OF_REJUVENATION, BardPlaybook.decideSupport(s));
		s.tankHasRejuvenation = true;
		assertNull(BardPlaybook.decideSupport(s));
	}

	@Test
	void aloneItHealsItselfSoonerOutOfAFight() {
		Situation s = calm();
		s.hp = s.hurtHp = 70;
		assertNull(BardPlaybook.decideSupport(s), "in a fight, 70 % is fine");
		s.inFight = false;
		assertEquals(Move.SOOTHING_MELODY, BardPlaybook.decideSupport(s));
		s.ready.remove(Move.SOOTHING_MELODY);
		s.assailed = false;
		assertEquals(Move.SOOTHING_HYMN, BardPlaybook.decideSupport(s), "nothing hits it out of a fight: the charged heal");
		s.ready.remove(Move.SOOTHING_HYMN);
		assertEquals(Move.HYMN_OF_REJUVENATION, BardPlaybook.decideSupport(s));
		s.tankHasRejuvenation = true;
		assertNull(BardPlaybook.decideSupport(s), "not over the one it carries");
	}

	@Test
	void theDeadAreRaisedOutOfAFightOnly() {
		Situation s = grouped(false);
		s.mateToRaise = true;
		assertNotEquals(Move.CHERUBIM_CALL, BardPlaybook.decideSupport(s));
		s.inFight = false;
		assertEquals(Move.CHERUBIM_CALL, BardPlaybook.decideSupport(s));
	}

	@Test
	void physicalWeakeningCleansedOnOneOrOnTheGroup() {
		Situation s = grouped(false);
		s.debuffedCount = 1;
		assertEquals(Move.PURIFYING_PAEAN, BardPlaybook.decideSupport(s));
		s.debuffedCount = 2;
		assertEquals(Move.PURGING_PAEAN, BardPlaybook.decideSupport(s));
	}

	// mana

	@Test
	void manaChainForItselfThenTheGroup() {
		Situation s = calm();
		s.mp = 40;
		assertEquals(Move.RESONATING_MELODY, BardPlaybook.decideSupport(s));
		s.manaChainOpen = true;
		assertEquals(Move.RESONANT_HYMN, BardPlaybook.decideSupport(s));
		s.mp = 90;
		s.manaChainOpen = false;
		s.inTeam = true;
		s.membersLowOnMana = 1;
		assertNull(BardPlaybook.decideSupport(s), "one member short is not worth opening the chain for");
		s.membersLowOnMana = 2;
		assertEquals(Move.RESONATING_MELODY, BardPlaybook.decideSupport(s));
		s.membersLowOnMana = 0;
		s.healerLowOnMana = true;
		assertEquals(Move.RESONANT_COUNTERPOINT, BardPlaybook.decideSupport(s));
	}

	// the fight

	@Test
	void theStanceIsPutRightInAFight() {
		Situation s = grouped(false);
		s.exultationUp = false;
		s.impassionUp = true;
		assertEquals(Move.EXULTATION, BardPlaybook.decideOffence(s), "the cleric died: the healing stance");
	}

	@Test
	void controlASecondEnemyInAGroupOrWhenSeveral() {
		Situation s = calm();
		s.secondToControl = true;
		assertNotEquals(Move.FREESTYLE, BardPlaybook.decideOffence(s), "alone with one on it, nothing to control");
		s.severalEnemies = true;
		assertEquals(Move.FREESTYLE, BardPlaybook.decideOffence(s));
		s.ready.remove(Move.FREESTYLE);
		assertEquals(Move.CAPTIVATE, BardPlaybook.decideOffence(s));
	}

	@Test
	void quaverOnACasterHalfStopOnAPlayerDirgeWhenTurnedOn() {
		Situation s = calm();
		s.targetCasts = true;
		assertEquals(Move.QUAVER, BardPlaybook.decideOffence(s));
		s.targetCasts = false;
		s.targetIsPlayer = true;
		assertEquals(Move.HALF_STOP, BardPlaybook.decideOffence(s));
		s.targetIsPlayer = false;
		s.targetOnMe = true;
		s.dirgeOnTarget = false;
		assertEquals(Move.DELUSIONAL_DIRGE, BardPlaybook.decideOffence(s));
	}

	@Test
	void chargedSongsOnlyWhileNothingHitsTheBard() {
		Situation s = calm();
		assertNull(BardPlaybook.decideOffence(s), "hit: the chains are the generic order's");
		s.assailed = false;
		assertEquals(Move.FIERY_REQUIEM, BardPlaybook.decideOffence(s));
		s.assailed = true;
		s.targetIdle = true;
		assertEquals(Move.FIERY_REQUIEM, BardPlaybook.decideOffence(s), "alone, the opener before the target notices");
		s.inTeam = true;
		s.impassionUp = false;
		s.exultationUp = true;
		assertNull(BardPlaybook.decideOffence(s), "in a group the target idle says nothing of what hits the bard");
	}

	// divine power

	@Test
	void ariaOfHopeForTheGroupGoingDownSymphonyOnABossFlairOtherwise() {
		Situation s = grouped(true);
		s.dp = 4000;
		s.hurtUnder30 = 2;
		assertEquals(Move.ARIA_OF_HOPE, BardPlaybook.decideDivinePower(s));
		s.hurtUnder30 = 0;
		s.targetElite = true;
		assertEquals(Move.SYMPHONY_OF_DESTRUCTION, BardPlaybook.decideDivinePower(s));
		s.targetElite = false;
		assertNull(BardPlaybook.decideDivinePower(s), "in a group, Aria's price is kept");
		s.dp = 6000;
		assertEquals(Move.MINSTRELS_FLAIR, BardPlaybook.decideDivinePower(s));
		Situation alone = calm();
		alone.dp = 2000;
		assertEquals(Move.MINSTRELS_FLAIR, BardPlaybook.decideDivinePower(alone));
	}

	@Test
	void eliteHeroAndLegendaryAreWorthTheSymphony() {
		assertTrue(BardPlaybook.isEliteOrBoss(NpcRating.ELITE));
		assertTrue(BardPlaybook.isEliteOrBoss(NpcRating.LEGENDARY));
		assertFalse(BardPlaybook.isEliteOrBoss(NpcRating.NORMAL));
	}

	@Test
	void anUnreadySituationSaysNothing() {
		Situation s = new Situation();
		s.inFight = true;
		assertNull(BardPlaybook.decideDefence(s));
		assertNull(BardPlaybook.decideSupport(s));
		assertNull(BardPlaybook.decideOffence(s));
		assertNull(BardPlaybook.decideUpkeep(s));
	}
}
