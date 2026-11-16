package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.GunnerPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.GunnerPlaybook.Situation;

/**
 * The gunner's rules, decided without a world. See {@code docs/classes/plans/gunner-plan.md}.
 */
class GunnerPlaybookTest {

	/** A healthy gunner in a fight on an ordinary monster, everything ready and up, nothing on it: an opener is what comes next. */
	private static Situation calm() {
		Situation s = new Situation().readyAll();
		s.assailed = true;
		return s;
	}

	// a chain is never closed by the playbook

	@Test
	void nothingButADefenceIsCastWhileAChainIsOpen() {
		Situation s = calm();
		s.followedChainOpen = true;
		s.stanceUp = false;
		s.targetElite = true;
		assertNull(GunnerPlaybook.decideOffence(s), "the links are the generic order's, and any other cast closes the chain");
		assertNull(GunnerPlaybook.decideUpkeep(s), "not even the stance");
		s.hp = GunnerPlaybook.BULLETPROOF_BELOW - 1;
		assertEquals(Move.BULLETPROOF, GunnerPlaybook.decideDefence(s), "a defence still goes");
	}

	@Test
	void aMeleeIsNotKeptOffMidChain() {
		Situation s = calm();
		s.assailed = false;
		s.targetMeleeNear = true;
		assertEquals(Move.PARTING_SHOT, GunnerPlaybook.decideDefence(s));
		s.followedChainOpen = true;
		assertNull(GunnerPlaybook.decideDefence(s), "the jump waits for the chain to end");
	}

	// openers

	@Test
	void theOpenerFollowsTheTarget() {
		Situation s = calm();
		assertEquals(Move.GUNSHOT, GunnerPlaybook.decideOffence(s));
		s.targetCasts = true;
		s.ready.remove(Move.QUIETING_GALE);
		assertEquals(Move.TRUNK_SHOT, GunnerPlaybook.decideOffence(s), "its stagger on a caster");
		s.targetCasts = false;
		s.targetIsPlayer = true;
		s.ready.removeAll(GunnerPlaybook.BURST_ORDER);
		s.ready.remove(Move.SPEND_SUCCESS);
		assertEquals(Move.CROSSTRIGGER, GunnerPlaybook.decideOffence(s), "its mana burn on a player");
	}

	@Test
	void theOpenersAreReloadedWhenTheyCoolDownTogether() {
		Situation s = calm();
		s.ready.removeAll(GunnerPlaybook.OPENERS);
		assertEquals(Move.RELOAD, GunnerPlaybook.decideOffence(s));
		s.ready.remove(Move.RELOAD);
		assertEquals(Move.AUTOLOAD, GunnerPlaybook.decideOffence(s));
		s.ready.remove(Move.AUTOLOAD);
		assertNull(GunnerPlaybook.decideOffence(s), "then the fillers, the generic order's");
	}

	@Test
	void aGunnerThatKnowsOnlyGunshotOpensWithIt() {
		Situation s = new Situation().ready(Move.GUNSHOT);
		s.targetIsPlayer = true;
		assertEquals(Move.GUNSHOT, GunnerPlaybook.opener(s));
	}

	// cooldowns

	@Test
	void theCooldownsAreSpentOnWhatIsWorthThem() {
		Situation s = calm();
		assertFalse(GunnerPlaybook.worthABurst(s));
		assertEquals(Move.GUNSHOT, GunnerPlaybook.decideOffence(s), "nothing spent on an ordinary monster");
		s.targetElite = true;
		assertEquals(Move.NATURES_FAVOR, GunnerPlaybook.decideOffence(s));
		s.dp = GunnerPlaybook.ABILITY_DP;
		assertEquals(Move.SPEND_SUCCESS, GunnerPlaybook.decideOffence(s));
		s = calm();
		s.enemiesEngaged = GunnerPlaybook.ENEMIES_WORTH_A_BURST;
		assertTrue(GunnerPlaybook.worthABurst(s), "three on a gunner alone");
		s.inTeam = true;
		assertFalse(GunnerPlaybook.worthABurst(s), "not when the group shares them");
	}

	// upkeep

	@Test
	void theStanceAndTheProcAreKeptUp() {
		Situation s = calm();
		assertNull(GunnerPlaybook.decideUpkeep(s));
		s.favorUp = false;
		assertEquals(Move.AIONS_FAVOR, GunnerPlaybook.decideUpkeep(s));
		s.stanceUp = false;
		assertEquals(Move.STABLE_STANCE, GunnerPlaybook.decideUpkeep(s), "a toggle, cast only while it is off");
	}

	// defence

	@Test
	void afterRemoveShockAloneAndLowItEscapesOtherwiseItDrains() {
		Situation s = calm();
		s.controlled = true;
		assertEquals(Move.REMOVE_SHOCK, GunnerPlaybook.decideDefence(s));
		assertNull(GunnerPlaybook.decideOffence(s));
		s.controlled = false;
		s.shockChainOpen = true;
		s.hp = GunnerPlaybook.ESCAPE_BELOW - 1;
		assertEquals(Move.TELEPORT_ESCAPE, GunnerPlaybook.decideDefence(s));
		s.inTeam = true;
		assertEquals(Move.STEAL_HEALTH, GunnerPlaybook.decideDefence(s), "a grouped gunner stays");
	}

	@Test
	void theShieldsGoUpAsItIsHurt() {
		Situation s = calm();
		assertNull(GunnerPlaybook.decideDefence(s));
		s.hp = GunnerPlaybook.SHIELD_BELOW - 1;
		assertEquals(Move.BULLET_RESISTANCE, GunnerPlaybook.decideDefence(s));
		s.hp = GunnerPlaybook.BULLETPROOF_BELOW - 1;
		assertEquals(Move.BULLETPROOF, GunnerPlaybook.decideDefence(s));
		s.assailed = false;
		assertNull(GunnerPlaybook.decideDefence(s), "nothing hits it");
	}

	@Test
	void outOfARootWithAMeleeOnItAndItsHateLoweredInAGroup() {
		Situation s = calm();
		s.rootedOrSnared = true;
		s.meleeNear = true;
		assertEquals(Move.SURVIVAL_INSTINCT, GunnerPlaybook.decideDefence(s));
		s.rootedOrSnared = false;
		s.targetOnMe = true;
		assertNull(GunnerPlaybook.decideDefence(s), "alone the hate is its own");
		s.inTeam = true;
		assertEquals(Move.MEMORY_MANIPULATION, GunnerPlaybook.decideDefence(s));
	}

	// control

	@Test
	void aSecondEnemyIsHeldACasterSilencedAPlayerStunned() {
		Situation s = calm();
		s.secondToControl = true;
		s.enemiesEngaged = 2;
		assertEquals(Move.GREEN_GRENADE, GunnerPlaybook.decideOffence(s));
		s.secondToControl = false;
		s.targetCasts = true;
		assertEquals(Move.QUIETING_GALE, GunnerPlaybook.decideOffence(s));
		s.targetCasts = false;
		s.targetIsPlayer = true;
		s.targetWithin5m = true;
		s.ready.removeAll(GunnerPlaybook.BURST_ORDER);
		s.ready.remove(Move.SPEND_SUCCESS);
		assertEquals(Move.DAZZLING_FIRE, GunnerPlaybook.decideOffence(s));
		s.targetWithin5m = false;
		s.targetFlying = true;
		assertEquals(Move.WING_CLIP, GunnerPlaybook.decideOffence(s));
	}
}
