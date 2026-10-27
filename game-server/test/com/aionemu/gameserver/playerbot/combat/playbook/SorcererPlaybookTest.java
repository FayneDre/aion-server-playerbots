package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.SorcererPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.SorcererPlaybook.Situation;

/**
 * The sorcerer's rules, decided without a world. See {@code docs/classes/sorcerer-plan.md}.
 */
class SorcererPlaybookTest {

	/** A healthy sorcerer in a fight, everything ready and up, its target at range, carrying its damage over time: nothing pressing. */
	private static Situation calm() {
		Situation s = new Situation().readyAll();
		s.dotOnTarget = true;
		s.fusionOnTarget = true;
		return s;
	}

	// upkeep

	@Test
	void beforeAFightStoneSkinThenTheRobeForBeingAloneOrGrouped() {
		Situation s = new Situation().readyAll();
		s.stoneSkinUp = false;
		s.robeUp = false;
		assertEquals(Move.STONE_SKIN, SorcererPlaybook.decideAtRest(s, true));
		s.stoneSkinUp = true;
		assertEquals(Move.ROBE_OF_COLD, SorcererPlaybook.decideAtRest(s, true), "alone: the defensive robe");
		s.inTeam = true;
		assertEquals(Move.ROBE_OF_FLAME, SorcererPlaybook.decideAtRest(s, true), "in a group: the damage robe");
	}

	@Test
	void idleOnlyGainMana() {
		Situation s = new Situation().readyAll();
		s.stoneSkinUp = false;
		s.robeUp = false;
		assertNull(SorcererPlaybook.decideAtRest(s, false), "a resident in town renews no shield for nobody");
		s.mp = 40;
		assertEquals(Move.GAIN_MANA, SorcererPlaybook.decideAtRest(s, false));
	}

	@Test
	void empyreanWisdomOnlyWhenAFightIsComing() {
		Situation s = new Situation().readyAll();
		s.wisdomUp = false;
		assertNull(SorcererPlaybook.decideAtRest(s, false));
		assertEquals(Move.EMPYREAN_WISDOM, SorcererPlaybook.decideAtRest(s, true));
	}

	@Test
	void stoneSkinComesFirstInAFight() {
		Situation s = calm();
		s.stoneSkinUp = false;
		s.mp = 10;
		assertEquals(Move.STONE_SKIN, SorcererPlaybook.decide(s));
	}

	// mana

	@Test
	void gainManaUnderHalf() {
		Situation s = calm();
		s.mp = 49;
		assertEquals(Move.GAIN_MANA, SorcererPlaybook.decide(s));
	}

	@Test
	void refractingShardWhileDrainingAndSoulAbsorptionOnTheChain() {
		Situation s = calm().without(Move.GAIN_MANA);
		s.draining = true;
		assertEquals(Move.REFRACTING_SHARD, SorcererPlaybook.decide(s));
		s.shardChainOpen = true;
		assertEquals(Move.SOUL_ABSORPTION, SorcererPlaybook.decide(s), "the proc first: its chain closes");
	}

	@Test
	void glacialShardOnlyOverThirtyPercentMana() {
		Situation s = new Situation().ready(Move.GLACIAL_SHARD);
		s.dotOnTarget = true;
		s.mp = 30;
		assertNull(SorcererPlaybook.decide(s));
		s.mp = 31;
		assertEquals(Move.GLACIAL_SHARD, SorcererPlaybook.decide(s));
	}

	// defence

	@Test
	void controlledOnlyRemoveShockThenOldRootsOnASecondEnemy() {
		Situation s = calm();
		s.controlled = true;
		assertEquals(Move.REMOVE_SHOCK, SorcererPlaybook.decide(s));
		s.controlled = false;
		s.shockChainOpen = true;
		s.secondToControl = true;
		s.severalEnemies = true;
		assertEquals(Move.CURSE_OF_OLD_ROOTS, SorcererPlaybook.decide(s));
	}

	@Test
	void ironCladOnlyWithStoneSkinGoneAndCooling() {
		Situation s = calm().without(Move.STONE_SKIN);
		s.stoneSkinUp = false;
		s.hp = 40;
		assertEquals(Move.BOON_OF_IRON_CLAD, SorcererPlaybook.decide(s));
		s.hp = 60;
		assertNotEquals(Move.BOON_OF_IRON_CLAD, SorcererPlaybook.decide(s));
	}

	@Test
	void illusionWhenHitInMeleeBoonOfPeaceWhenTheTargetIsCloseAndOnIt() {
		Situation s = calm();
		s.hitInMelee = true;
		assertEquals(Move.ILLUSION, SorcererPlaybook.decide(s));
		s.illusionUp = true;
		s.targetOnMe = true;
		s.targetWithin5m = true;
		assertEquals(Move.BOON_OF_PEACE, SorcererPlaybook.decide(s));
	}

	@Test
	void freezingWindOnlyWithinThreeMetres() {
		Situation s = calm();
		s.targetWithin3m = true;
		assertEquals(Move.FREEZING_WIND, SorcererPlaybook.decide(s));
	}

	// holding fire

	@Test
	void holdingFireStopsTheAttackButNotTheDefences() {
		Situation s = calm();
		s.holdFire = true;
		assertEquals(Move.HOLD_FIRE, SorcererPlaybook.decide(s));
		s.stoneSkinUp = false;
		assertEquals(Move.STONE_SKIN, SorcererPlaybook.decide(s), "its defences still go");
	}

	// chains and the hold in the air

	@Test
	void theMulticastsAreRecastWhileTheirChainIsOpen() {
		Situation s = calm();
		s.spearChainOpen = true;
		assertEquals(Move.WIND_SPEAR, SorcererPlaybook.decide(s));
	}

	@Test
	void theFireChainIsLeftToTheGenericOrder() {
		Situation s = calm();
		s.followedChainOpen = true;
		assertNull(SorcererPlaybook.decide(s), "Blaze after Flame Harpoon is the generic order's");
	}

	@Test
	void frozenShockAfterIceChainAloneOnly() {
		Situation s = calm();
		s.iceChainOpen = true;
		assertEquals(Move.FROZEN_SHOCK, SorcererPlaybook.decide(s));
		s.inTeam = true;
		assertNotEquals(Move.FROZEN_SHOCK, SorcererPlaybook.decide(s), "never in a group");
	}

	@Test
	void aetherFlameTwiceThenMagicFist() {
		Situation s = calm();
		s.targetAerial = true;
		assertEquals(Move.AETHER_FLAME, SorcererPlaybook.decide(s));
		s.aetherFlamesCast = 1;
		assertEquals(Move.AETHER_FLAME, SorcererPlaybook.decide(s));
		s.aetherFlamesCast = 2;
		assertEquals(Move.MAGIC_FIST, SorcererPlaybook.decide(s));
	}

	// divine power

	@Test
	void theLumielPairAgainstPlayersOnly() {
		Situation s = calm();
		s.dp = 4000;
		assertEquals(Move.EMPYREAN_FIRE, SorcererPlaybook.decide(s), "a monster: Empyrean Fire, never the Lumiel pair");
		s.targetIsPlayer = true;
		assertEquals(Move.SHADOWBURST, SorcererPlaybook.decide(s));
	}

	// control

	@Test
	void controlsInTheGuidesOrderOnASecondEnemy() {
		Situation s = calm().without(Move.EMPYREAN_FIRE);
		s.secondToControl = true;
		s.inTeam = true;
		assertEquals(Move.SOMNOLENCE, SorcererPlaybook.decide(s));
		assertEquals(Move.CURSE_OF_ROOTS, SorcererPlaybook.decide(s.without(Move.SOMNOLENCE)));
		assertEquals(Move.ROOT, SorcererPlaybook.decide(s.without(Move.SOMNOLENCE).without(Move.CURSE_OF_ROOTS)));
	}

	@Test
	void aloneNoControlWithASingleEnemy() {
		Situation s = calm();
		s.secondToControl = true;
		assertNotEquals(Move.SOMNOLENCE, SorcererPlaybook.decide(s), "alone, controls are for several enemies");
	}

	@Test
	void soulFreezeOnACasterWithFocusFirst() {
		Situation s = calm();
		s.targetCasts = true;
		s.focusUp = false;
		assertEquals(Move.SUPPLICATION_OF_FOCUS, SorcererPlaybook.decide(s));
		s.focusUp = true;
		assertEquals(Move.SOUL_FREEZE, SorcererPlaybook.decide(s));
	}

	// damage

	@Test
	void theSoloOpenersOnAnIdleTargetOnlyAlone() {
		Situation s = calm();
		s.targetIdle = true;
		assertEquals(Move.DELAYED_BLAST, SorcererPlaybook.decide(s));
		assertEquals(Move.ICE_CHAIN, SorcererPlaybook.decide(s.without(Move.DELAYED_BLAST)));
		s.inTeam = true;
		assertNotEquals(Move.DELAYED_BLAST, SorcererPlaybook.decide(s), "never in a group");
	}

	@Test
	void theDamageOverTimeIsKeptUp() {
		Situation s = calm();
		s.dotOnTarget = false;
		assertEquals(Move.FLAME_CAGE, SorcererPlaybook.decide(s));
		assertEquals(Move.EROSION, SorcererPlaybook.decide(s.without(Move.FLAME_CAGE)), "before level 16");
	}

	@Test
	void theLongCooldownsInTheGuidesOrder() {
		Situation s = calm();
		assertEquals(Move.STORM_STRIKE, SorcererPlaybook.decide(s));
		assertEquals(Move.ARCANE_THUNDERBOLT, SorcererPlaybook.decide(s.without(Move.STORM_STRIKE)));
	}

	@Test
	void flameSprayOnlyAfterFlameFusion() {
		Situation s = new Situation().ready(Move.FLAME_FUSION, Move.FLAME_SPRAY);
		s.dotOnTarget = true;
		assertEquals(Move.FLAME_FUSION, SorcererPlaybook.decide(s));
		s.fusionOnTarget = true;
		assertEquals(Move.FLAME_SPRAY, SorcererPlaybook.decide(s));
	}

	@Test
	void meteoriteOnlyWithAnEnemyWithinSevenMetres() {
		Situation s = new Situation().ready(Move.METEORITE);
		s.dotOnTarget = true;
		assertNull(SorcererPlaybook.decide(s));
		s.enemyWithin7m = true;
		assertEquals(Move.METEORITE, SorcererPlaybook.decide(s));
	}

	@Test
	void aRefusedMoveGivesWayToTheNext() {
		Situation s = calm();
		assertEquals(Move.ARCANE_THUNDERBOLT, SorcererPlaybook.decide(s.without(Move.STORM_STRIKE)));
		assertTrue(s.ready.contains(Move.STORM_STRIKE), "the situation asked about is left as it was");
	}
}
