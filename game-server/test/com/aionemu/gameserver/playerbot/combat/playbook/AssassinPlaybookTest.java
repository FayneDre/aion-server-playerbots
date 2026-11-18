package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.AssassinPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.AssassinPlaybook.Situation;

/**
 * The assassin's rules, decided without a world. See {@code docs/classes/plans/assassin-plan.md}.
 */
class AssassinPlaybookTest {

	/** A healthy assassin in a group on an ordinary monster, everything ready, the rune full and Killer's Eye up: a burst is what comes next. */
	private static Situation ripe() {
		Situation s = new Situation().readyAll();
		s.inTeam = true;
		s.rune = AssassinPlaybook.RUNE_FULL;
		s.killersEyeUp = true;
		s.enemiesEngaged = 1;
		return s;
	}

	/** The same assassin on a fresh target: no rune yet. */
	private static Situation fresh() {
		Situation s = ripe();
		s.rune = 0;
		return s;
	}

	// the runes

	@Test
	void aRuneIsNeverBurstBeforeItIsFull() {
		for (int rune = 0; rune < AssassinPlaybook.RUNE_FULL; rune++) {
			Situation s = fresh();
			s.rune = rune;
			Move move = AssassinPlaybook.decideOffence(s);
			assertFalse(AssassinPlaybook.BURSTS.contains(move), "burst at rune " + rune + ": " + move);
		}
		assertEquals(Move.EXPLOSIVE_REBRANDING, AssassinPlaybook.decideOffence(ripe()), "at five, the burst that carves again");
	}

	@Test
	void withoutACarverReachingFiveItBurstsAtThree() {
		Situation s = fresh();
		s.knowsFullCarver = false;
		s.rune = AssassinPlaybook.RUNE_EARLY;
		assertTrue(AssassinPlaybook.BURSTS.contains(AssassinPlaybook.decideOffence(s)));
		s.rune = AssassinPlaybook.RUNE_EARLY - 1;
		assertFalse(AssassinPlaybook.BURSTS.contains(AssassinPlaybook.decideOffence(s)));
	}

	@Test
	void aDyingTargetIsBurstAtAnyRuneButNone() {
		Situation s = fresh();
		s.targetHp = AssassinPlaybook.FINISH_BELOW - 1;
		assertFalse(AssassinPlaybook.BURSTS.contains(AssassinPlaybook.decideOffence(s)), "a burst at none does a tenth");
		s.rune = 1;
		assertTrue(AssassinPlaybook.BURSTS.contains(AssassinPlaybook.decideOffence(s)));
	}

	@Test
	void killersEyeGoesBeforeTheBurst() {
		Situation s = ripe();
		s.killersEyeUp = false;
		assertEquals(Move.KILLERS_EYE, AssassinPlaybook.decideOffence(s));
		s.rune = 2;
		assertNotEquals(Move.KILLERS_EYE, AssassinPlaybook.decideOffence(s), "not for a rune that is not ripe");
	}

	@Test
	void theBurstFollowsTheMoment() {
		Situation s = ripe();
		s.targetCasts = true;
		s.ready.remove(Move.SEARCHING_STRIKE);
		assertEquals(Move.SIGNET_SILENCE, AssassinPlaybook.burst(s), "a caster is silenced");
		s.targetCasts = false;
		s.hp = AssassinPlaybook.BLOOD_RUNE_BELOW - 1;
		assertEquals(Move.BLOOD_RUNE, AssassinPlaybook.burst(s), "hurt, the burst that drains");
		s.hp = 100;
		s.ready.remove(Move.EXPLOSIVE_REBRANDING);
		assertEquals(Move.PAIN_RUNE, AssassinPlaybook.burst(s));
		s.ready.removeAll(AssassinPlaybook.PLAIN_BURSTS);
		assertEquals(Move.SIGNET_SILENCE, AssassinPlaybook.burst(s), "any burst rather than none");
	}

	@Test
	void aRuneIsCarvedWithTheHighestCapFirst() {
		Situation s = fresh();
		assertEquals(Move.FANG_STRIKE, AssassinPlaybook.carver(s));
		s.ready.remove(Move.FANG_STRIKE);
		assertEquals(Move.RUNE_CARVE, AssassinPlaybook.carver(s), "in a group, Ripclaw Strike's hate is not wanted and Massacre needs two");
		s.inTeam = false;
		assertEquals(Move.RIPCLAW_STRIKE, AssassinPlaybook.carver(s));
		s.enemiesEngaged = 2;
		assertEquals(Move.MASSACRE, AssassinPlaybook.carver(s));
	}

	@Test
	void aCarverStoppingAtThreeIsNotCastOnThreeOrMore() {
		Situation s = fresh();
		s.ready.removeAll(AssassinPlaybook.FULL_CARVERS);
		s.rune = AssassinPlaybook.LOW_CAP - 1;
		assertEquals(Move.RUNE_CARVE, AssassinPlaybook.carver(s));
		s.rune = AssassinPlaybook.LOW_CAP;
		assertNull(AssassinPlaybook.carver(s), "it would only refresh the rune");
		s.rune = AssassinPlaybook.RUNE_FULL;
		s.ready.addAll(AssassinPlaybook.FULL_CARVERS);
		assertNull(AssassinPlaybook.carver(s), "a full rune is carved no further");
	}

	// a chain is never closed by the playbook

	@Test
	void nothingButADefenceIsCastWhileAChainIsOpen() {
		Situation s = ripe();
		s.followedChainOpen = true;
		s.poisonUp = false;
		s.attackersOnMe = 2;
		assertNull(AssassinPlaybook.decideOffence(s));
		assertNull(AssassinPlaybook.decideUpkeep(s));
		assertNull(AssassinPlaybook.decideHate(s));
		s.assailed = true;
		s.hp = AssassinPlaybook.EVASION_BELOW - 1;
		assertEquals(Move.FOCUSED_EVASION, AssassinPlaybook.decideDefence(s));
	}

	// the opening

	@Test
	void fromHidingItStrikesFromBehindOrAppearsThere() {
		Situation s = fresh();
		s.hidden = true;
		s.behindTarget = true;
		assertEquals(Move.SURPRISE_ATTACK, AssassinPlaybook.decideOffence(s));
		s.behindTarget = false;
		assertEquals(Move.AMBUSH, AssassinPlaybook.decideOffence(s));
		s.ready.remove(Move.AMBUSH);
		assertNull(AssassinPlaybook.decideOffence(s), "nothing else breaks the hide");
		assertNull(AssassinPlaybook.decideUpkeep(s), "not even the poison");
	}

	// buffs and divine power

	@Test
	void theCooldownsGoOnWhatIsWorthThem() {
		Situation s = fresh();
		s.ready.remove(Move.DEADLY_ABANDON);
		assertEquals(Move.FANG_STRIKE, AssassinPlaybook.decideOffence(s), "an ordinary monster gets the runes alone");
		s.targetElite = true;
		assertEquals(Move.APPLY_LETHAL_VENOM, AssassinPlaybook.decideOffence(s));
	}

	@Test
	void deadlyAbandonIsForAnOrdinaryMonsterAlone() {
		Situation s = fresh();
		s.inTeam = false;
		assertEquals(Move.DEADLY_ABANDON, AssassinPlaybook.decideOffence(s));
		s.enemiesEngaged = 2;
		assertNotEquals(Move.DEADLY_ABANDON, AssassinPlaybook.decideOffence(s), "not with a defence down against two");
	}

	@Test
	void theDivinePowerGoesOnWhatIsWorthIt() {
		Situation s = fresh();
		s.dp = AssassinPlaybook.DIRK_DP;
		assertNull(AssassinPlaybook.divinePower(s), "an ordinary monster is not worth it");
		s.targetElite = true;
		assertEquals(Move.DIVINE_STRIKE, AssassinPlaybook.divinePower(s), "an elite is worth the strike, not the dirk");
		s.targetBoss = true;
		assertEquals(Move.TRINIELS_DIRK, AssassinPlaybook.divinePower(s));
		s.ready.remove(Move.TRINIELS_DIRK);
		assertEquals(Move.VAIZELS_DIRK, AssassinPlaybook.divinePower(s), "the other faction's");
		s.targetBoss = false;
		s.targetElite = false;
		s.targetIsPlayer = true;
		s.ready.remove(Move.VAIZELS_DIRK);
		s.targetOutOfReach = true;
		assertEquals(Move.SPIRAL_SLASH, AssassinPlaybook.divinePower(s));
		s.dp = AssassinPlaybook.STRIKE_DP;
		assertEquals(Move.DIVINE_STRIKE, AssassinPlaybook.divinePower(s));
	}

	@Test
	void thePoisonIsKeptOn() {
		Situation s = fresh();
		s.poisonUp = false;
		assertEquals(Move.APPLY_DEADLY_POISON, AssassinPlaybook.decideUpkeep(s));
		s.poisonUp = true;
		assertNull(AssassinPlaybook.decideUpkeep(s));
	}

	// hate

	@Test
	void inAGroupItGivesTheHateBack() {
		Situation s = fresh();
		s.targetOnMe = true;
		assertEquals(Move.CALMING_WHISPER, AssassinPlaybook.decideHate(s));
		s.targetIsPlayer = true;
		assertNull(AssassinPlaybook.decideHate(s), "a player keeps no hate to lower");
		s.targetIsPlayer = false;
		s.attackersOnMe = AssassinPlaybook.ENEMIES_ON_IT;
		assertEquals(Move.SHIMMERBOMB, AssassinPlaybook.decideHate(s));
		s.attackersOnMe = 1;
		s.inTeam = false;
		assertNull(AssassinPlaybook.decideHate(s), "alone the hate is its own");
	}

	// defence

	@Test
	void heldItBreaksFreeThenTheShockChain() {
		Situation s = fresh();
		s.controlled = true;
		assertEquals(Move.REMOVE_SHOCK, AssassinPlaybook.decideDefence(s));
		s.controlled = false;
		s.shockChainOpen = true;
		assertEquals(Move.CYCLONE_SLASH, AssassinPlaybook.decideDefence(s));
		s.inTeam = false;
		s.hp = AssassinPlaybook.ILLUSION_BELOW - 1;
		assertEquals(Move.SHADOW_ILLUSION, AssassinPlaybook.decideDefence(s), "alone and hurt, it vanishes");
	}

	@Test
	void rootsAndSnaresAreShakenOff() {
		Situation s = fresh();
		s.rooted = true;
		assertEquals(Move.FLASH_OF_SPEED, AssassinPlaybook.decideDefence(s), "Break Away does not reach a root");
		s.rooted = false;
		s.snared = true;
		assertEquals(Move.BREAK_AWAY, AssassinPlaybook.decideDefence(s));
	}

	@Test
	void aCasterOnItIsResisted() {
		Situation s = fresh();
		s.targetCasts = true;
		s.targetOnMe = true;
		assertEquals(Move.AETHERTWISTING, AssassinPlaybook.decideDefence(s));
		s.ready.remove(Move.AETHERTWISTING);
		assertEquals(Move.SPELLDODGING, AssassinPlaybook.decideDefence(s));
	}

	@Test
	void hurtItDodgesThenTakesSlayerForm() {
		Situation s = fresh();
		s.assailed = true;
		s.hp = AssassinPlaybook.SLAYER_BELOW - 1;
		assertEquals(Move.FOCUSED_EVASION, AssassinPlaybook.decideDefence(s));
		s.ready.remove(Move.FOCUSED_EVASION);
		assertEquals(Move.SLAYER_FORM, AssassinPlaybook.decideDefence(s));
	}

	// control

	@Test
	void theControlsFollowWhatTheDataAllows() {
		Situation s = fresh();
		s.ready.removeAll(AssassinPlaybook.FULL_CARVERS);
		s.ready.removeAll(AssassinPlaybook.LOW_CARVERS);
		assertNull(AssassinPlaybook.decideOffence(s), "an ordinary monster is left to the chains");
		s.targetStunned = true;
		assertEquals(Move.SHADOWFALL, AssassinPlaybook.decideOffence(s));
		s.targetStunned = false;
		s.targetAerial = true;
		assertEquals(Move.CRASHING_WIND_STRIKE, AssassinPlaybook.decideOffence(s));
		s.targetAerial = false;
		s.targetCasts = true;
		assertEquals(Move.SEARCHING_STRIKE, AssassinPlaybook.decideOffence(s));
	}

	@Test
	void aPlayerWithinReachIsLeaptFrom() {
		Situation s = fresh();
		s.targetIsPlayer = true;
		s.targetWithinLeap = true;
		s.ready.removeAll(AssassinPlaybook.BURST_ORDER);
		s.ready.remove(Move.SEARCHING_STRIKE);
		s.ready.remove(Move.QUICKENING_DOOM);
		s.ready.removeAll(AssassinPlaybook.FULL_CARVERS);
		s.ready.removeAll(AssassinPlaybook.LOW_CARVERS);
		assertEquals(Move.BEAST_LEAP, AssassinPlaybook.decideOffence(s));
	}

	@Test
	void theSightsAndWindWalkAreNeverOffered() {
		for (String group : AssassinPlaybook.FORBIDDEN_GROUPS) {
			for (Move move : Move.values())
				assertNotEquals(group, move.group, move + " is forbidden");
		}
	}
}
