package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.AethertechPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.AethertechPlaybook.Situation;

/**
 * The aethertech's rules, decided without a world. See {@code docs/classes/plans/aethertech-plan.md}.
 */
class AethertechPlaybookTest {

	/** A healthy aethertech alone on an ordinary monster, everything ready and its toggles up: nothing is left but the chains. */
	private static Situation calm() {
		Situation s = new Situation().readyAll();
		s.assailed = true;
		s.targetOnMe = true;
		s.batteryUp = true;
		s.thrustersUp = true;
		return s;
	}

	/** The same aethertech tanking a group. */
	private static Situation tanking() {
		Situation s = calm();
		s.inTeam = true;
		s.frustrationUp = true;
		return s;
	}

	@Test
	void aCalmFightIsTheChains() {
		Situation s = calm();
		assertNull(AethertechPlaybook.decideDefence(s));
		assertNull(AethertechPlaybook.decideHold(s));
		assertNull(AethertechPlaybook.decideUpkeep(s));
		assertNull(AethertechPlaybook.decideOffence(s), "the chains are the generic order's");
	}

	// a chain is never closed by the playbook, but for a defence or a taunt

	@Test
	void nothingButADefenceOrATauntIsCastWhileAChainIsOpen() {
		Situation s = tanking();
		s.followedChainOpen = true;
		s.batteryUp = false;
		s.frustrationUp = false;
		s.targetElite = true;
		s.dp = AethertechPlaybook.BARRAGE_DP;
		assertNull(AethertechPlaybook.decideOffence(s));
		assertNull(AethertechPlaybook.decideUpkeep(s), "not even the shield");
		assertNull(AethertechPlaybook.decideHold(s), "nor the hate toggle");
		s.targetLoose = true;
		s.targetWithinWhisper = true;
		assertEquals(Move.PROVOKING_WHISPERS, AethertechPlaybook.decideHold(s), "a mate being hit costs more than a link");
		s.hp = AethertechPlaybook.NULLIFICATION_BELOW - 1;
		assertEquals(Move.NULLIFICATION_TRIGGER, AethertechPlaybook.decideDefence(s));
	}

	// the toggles

	@Test
	void theShieldAndTheBoostGoOnWhileTheManaHolds() {
		Situation s = calm();
		s.batteryUp = false;
		s.thrustersUp = false;
		assertEquals(Move.KINETIC_BATTERY, AethertechPlaybook.decideUpkeep(s));
		s.batteryUp = true;
		assertEquals(Move.STABILITY_THRUSTERS, AethertechPlaybook.decideUpkeep(s));
		s.mp = AethertechPlaybook.THRUSTERS_ON - 1;
		assertNull(AethertechPlaybook.decideUpkeep(s), "the boost waits for the mana");
		s.batteryUp = false;
		s.mp = AethertechPlaybook.BATTERY_ON - 1;
		assertNull(AethertechPlaybook.decideUpkeep(s));
	}

	@Test
	void theTogglesGoOffUnderTheirThresholdsAndBetweenFights() {
		Situation s = calm();
		assertEquals(List.of(), AethertechPlaybook.togglesToEnd(s, true));
		s.mp = AethertechPlaybook.THRUSTERS_ON - 1;
		assertEquals(List.of(), AethertechPlaybook.togglesToEnd(s, true), "between the two thresholds the boost stays, so it is not switched every tick");
		s.mp = AethertechPlaybook.THRUSTERS_OFF - 1;
		assertEquals(List.of(Move.STABILITY_THRUSTERS), AethertechPlaybook.togglesToEnd(s, true));
		s.mp = AethertechPlaybook.BATTERY_OFF - 1;
		assertEquals(List.of(Move.KINETIC_BATTERY, Move.STABILITY_THRUSTERS), AethertechPlaybook.togglesToEnd(s, true));
		s.mp = 100;
		s.bulwarkUp = true;
		assertEquals(List.of(Move.KINETIC_BATTERY, Move.STABILITY_THRUSTERS, Move.KINETIC_BULWARK), AethertechPlaybook.togglesToEnd(s, false),
			"a resting aethertech regains its mana");
	}

	// holding the enemy

	@Test
	void theGroupsTankRaisesItsHateAndAloneItDoesNot() {
		Situation s = tanking();
		s.frustrationUp = false;
		assertEquals(Move.MOUNTING_FRUSTRATION, AethertechPlaybook.decideHold(s));
		assertEquals(Move.MOUNTING_FRUSTRATION, AethertechPlaybook.decidePreparation(s), "before the first blow");
		s.inTeam = false;
		assertNull(AethertechPlaybook.decideHold(s), "alone the hate is its own anyway");
		s.frustrationUp = true;
		assertEquals(List.of(Move.MOUNTING_FRUSTRATION), AethertechPlaybook.togglesToEnd(s, true));
	}

	@Test
	void besideATemplarItIsDamage() {
		Situation s = tanking();
		s.groupTank = false;
		s.frustrationUp = false;
		s.targetLoose = true;
		s.targetWithinWhisper = true;
		s.looseNear = 3;
		assertNull(AethertechPlaybook.decideHold(s), "two tanks on the taunts pull the same enemy back and forth");
		s.frustrationUp = true;
		assertEquals(List.of(Move.MOUNTING_FRUSTRATION), AethertechPlaybook.togglesToEnd(s, true));
	}

	@Test
	void twoEnemiesOnMatesTakeTheWaveOneTakesWhispers() {
		Situation s = tanking();
		s.looseNear = AethertechPlaybook.LOOSE_FOR_THE_WAVE;
		assertEquals(Move.MAGNETIC_WAVE, AethertechPlaybook.decideHold(s));
		s.looseNear = 1;
		s.looseWithinWhisper = true;
		assertEquals(Move.PROVOKING_WHISPERS_LOOSE, AethertechPlaybook.decideHold(s));
		s.targetLoose = true;
		s.targetWithinWhisper = true;
		assertEquals(Move.PROVOKING_WHISPERS, AethertechPlaybook.decideHold(s), "its own target first");
	}

	// divine power

	@Test
	void theDivinePowerGoesOnWhatIsWorthIt() {
		Situation s = calm();
		s.dp = AethertechPlaybook.OVERDRIVE_DP;
		assertNull(AethertechPlaybook.decideOffence(s), "an ordinary monster is not worth it");
		s.targetElite = true;
		assertEquals(Move.OVERDRIVE_TRIGGER, AethertechPlaybook.decideOffence(s));
		s.dp = AethertechPlaybook.BARRAGE_DP;
		assertEquals(Move.OVERDRIVE_TRIGGER, AethertechPlaybook.decideOffence(s), "an elite is not worth the barrage");
		s.targetBoss = true;
		assertEquals(Move.ANNIHILATION_BARRAGE, AethertechPlaybook.decideOffence(s));
		s.targetBoss = false;
		s.targetElite = false;
		s.enemiesNear = AethertechPlaybook.ENEMIES_WORTH_A_BURST;
		assertEquals(Move.ANNIHILATION_BARRAGE, AethertechPlaybook.decideOffence(s), "alone among three");
	}

	@Test
	void divinePowerNobodyWantsBecomesMana() {
		Situation s = calm();
		s.dp = AethertechPlaybook.OVERDRIVE_DP;
		s.mp = AethertechPlaybook.POWER_FLUX_BELOW - 1;
		assertEquals(Move.POWER_FLUX, AethertechPlaybook.decideOffence(s));
		s.targetIsPlayer = true;
		assertEquals(Move.OVERDRIVE_TRIGGER, AethertechPlaybook.decideOffence(s), "a player is worth the overdrive");
	}

	// defence and mana

	@Test
	void heldItBreaksFreeThenTheShockChain() {
		Situation s = calm();
		s.controlled = true;
		assertEquals(Move.REMOVE_SHOCK, AethertechPlaybook.decideDefence(s));
		s.controlled = false;
		s.shockChainOpen = true;
		assertEquals(Move.UPPERCUT, AethertechPlaybook.decideDefence(s));
		s.hp = AethertechPlaybook.SHELL_BELOW - 1;
		assertEquals(Move.PROTECTIVE_SHELL, AethertechPlaybook.decideDefence(s));
	}

	@Test
	void aLoneAethertechNearlyDeadJumpsOut() {
		Situation s = calm();
		s.hp = AethertechPlaybook.FLEE_BELOW - 1;
		assertEquals(Move.HYPERGATE_DETONATION, AethertechPlaybook.decideDefence(s));
		s.inTeam = true;
		assertEquals(Move.NULLIFICATION_TRIGGER, AethertechPlaybook.decideDefence(s), "a tank in a group stays where it is");
	}

	@Test
	void theBulwarkIsForAnEliteOnIt() {
		Situation s = calm();
		s.hp = AethertechPlaybook.BULWARK_BELOW - 1;
		assertNull(AethertechPlaybook.decideDefence(s));
		s.targetElite = true;
		assertEquals(Move.KINETIC_BULWARK, AethertechPlaybook.decideDefence(s));
		s.mp = AethertechPlaybook.BULWARK_MANA - 1;
		assertNull(AethertechPlaybook.decideDefence(s), "60 % of every blow from the mana wants the mana");
	}

	@Test
	void rootedItIsRepairedAndOutOfManaItRecharges() {
		Situation s = calm();
		s.rootedOrSnared = true;
		assertEquals(Move.REPAIR_PROTOCOL, AethertechPlaybook.decideDefence(s));
		s.rootedOrSnared = false;
		s.mp = AethertechPlaybook.RECHARGE_BELOW - 1;
		assertEquals(Move.RECHARGE, AethertechPlaybook.decideDefence(s));
		s.ready.remove(Move.RECHARGE);
		assertEquals(Move.POWERSPIKE_TRIGGER, AethertechPlaybook.decideDefence(s));
	}

	@Test
	void aetherRechargeWaitsForNothingOnIt() {
		Situation s = calm();
		s.mp = AethertechPlaybook.AETHER_RECHARGE_BELOW - 1;
		assertNull(AethertechPlaybook.decideUpkeep(s));
		s.assailed = false;
		assertEquals(Move.AETHER_RECHARGE, AethertechPlaybook.decideUpkeep(s));
	}

	// control

	@Test
	void aSecondEnemyIsHeldNearByShockAndFarByTheBeam() {
		Situation s = calm();
		s.secondToControl = true;
		assertNull(AethertechPlaybook.decideOffence(s), "alone with only one on it, there is nothing to hold");
		s.enemiesEngaged = 2;
		assertEquals(Move.CONVULSION_BEAM, AethertechPlaybook.decideOffence(s));
		s.secondWithinShock = true;
		assertEquals(Move.ELECTRIC_SHOCK, AethertechPlaybook.decideOffence(s));
	}

	@Test
	void aCasterIsSilencedAPlayerStunnedAStunnedTargetRushed() {
		Situation s = calm();
		s.targetCasts = true;
		assertEquals(Move.AETHERLOCK_BLADE, AethertechPlaybook.decideOffence(s));
		s.targetCasts = false;
		s.targetStunned = true;
		assertEquals(Move.STEAM_RUSH, AethertechPlaybook.decideOffence(s), "the data allows it on a stunned target only");
		s.targetStunned = false;
		s.targetIsPlayer = true;
		s.ready.removeAll(AethertechPlaybook.BURST_ORDER);
		s.ready.remove(Move.TRAUMA_PLATE_TRIGGER);
		assertEquals(Move.DRILLBORE, AethertechPlaybook.decideOffence(s));
	}

	@Test
	void theCooldownsGoOnWhatIsWorthThem() {
		Situation s = calm();
		s.targetElite = true;
		s.overdriveUp = true;
		assertEquals(Move.LEECHING_STEEL, AethertechPlaybook.decideOffence(s));
		s.ready.removeAll(AethertechPlaybook.BURST_ORDER);
		assertNull(AethertechPlaybook.decideOffence(s));
	}

	@Test
	void theEngineersGunsAreNeverOffered() {
		for (String group : AethertechPlaybook.FORBIDDEN_GROUPS) {
			for (Move move : Move.values())
				assertNotEquals(group, move.group, move + " is forbidden");
		}
	}
}
