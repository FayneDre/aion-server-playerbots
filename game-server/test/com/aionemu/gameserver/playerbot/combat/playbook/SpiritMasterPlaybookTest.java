package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.SpiritMasterPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.SpiritMasterPlaybook.Situation;

/**
 * The spirit master's rules, decided without a world. See {@code docs/classes/plans/spirit-master-plan.md}.
 */
class SpiritMasterPlaybookTest {

	/** A healthy spirit master in a fight, its spirit out and whole, everything ready and up, the curses on the target: nothing pressing. */
	private static Situation calm() {
		Situation s = new Situation().readyAll();
		s.inFight = true;
		s.spirit = Move.EARTH_SPIRIT;
		s.assailed = true;
		s.gloomOn = s.painOn = s.blightOn = s.freedomOn = s.shackleOn = true;
		s.targetWithin15m = false;
		return s;
	}

	/** The calm situation with the spirit's commands spent, so that what comes after them can be asked. */
	private static Situation commandsSpent() {
		Situation s = calm();
		s.ready.removeAll(List.of(Move.SPIRIT_EROSION, Move.WRATH_POSITION, Move.BURN_TO_ASHES, Move.SPIRIT_DISTURBANCE, Move.RUINOUS_OFFENSIVE,
			Move.DETONATION_CLAW));
		return s;
	}

	// which spirit

	@Test
	void earthAloneAndWithoutATankFireWithOne() {
		assertEquals(Move.EARTH_SPIRIT, SpiritMasterPlaybook.spiritOrder(false, false).get(0));
		assertEquals(Move.EARTH_SPIRIT, SpiritMasterPlaybook.spiritOrder(true, false).get(0), "nobody else holds the enemy");
		assertEquals(Move.FIRE_SPIRIT, SpiritMasterPlaybook.spiritOrder(true, true).get(0), "a tank holds it: the spirit's damage");
		for (boolean inTeam : new boolean[] { false, true })
			for (boolean tank : new boolean[] { false, true }) {
				List<Move> order = SpiritMasterPlaybook.spiritOrder(inTeam, tank);
				assertEquals(EnumSet.copyOf(SpiritMasterPlaybook.LASTING_SPIRITS), EnumSet.copyOf(order), "every lasting spirit, once");
				assertEquals(Move.WIND_SPIRIT, order.get(order.size() - 1), "Wind last");
				assertFalse(order.contains(Move.TEMPEST_SPIRIT), "the great spirits are never the one to walk about with");
			}
	}

	@Test
	void aSpiritMasterCallsUpTheFirstSpiritItKnows() {
		Situation s = new Situation();
		s.known = EnumSet.of(Move.FIRE_SPIRIT);
		assertEquals(Move.FIRE_SPIRIT, SpiritMasterPlaybook.wantedSpirit(s), "at level 10 it has the fire spirit only");
		s.known = EnumSet.noneOf(Move.class);
		assertNull(SpiritMasterPlaybook.wantedSpirit(s));
	}

	@Test
	void theWrongSpiritIsSentAwayOnlyOutOfAFightAndNeverAGreatOne() {
		Situation s = new Situation();
		s.inTeam = true;
		s.tankInGroup = true;
		s.spirit = Move.EARTH_SPIRIT;
		assertTrue(SpiritMasterPlaybook.wrongSpiritOut(s), "joined a group with a tank: fire instead");
		s.inFight = true;
		assertFalse(SpiritMasterPlaybook.wrongSpiritOut(s), "never mid fight");
		s.inFight = false;
		s.spirit = Move.FIRE_SPIRIT;
		assertFalse(SpiritMasterPlaybook.wrongSpiritOut(s), "the right one");
		s.spirit = null;
		s.greatSpirit = true;
		assertFalse(SpiritMasterPlaybook.wrongSpiritOut(s), "twenty minutes of divine power are kept");
	}

	// the spirit in a fight

	@Test
	void aSpiritThatDiedIsCalledUpAgainFasterAndTheGreatOneAgainstAnElite() {
		Situation s = calm();
		s.spiritOut = false;
		assertEquals(Move.SUMMONING_ALACRITY, SpiritMasterPlaybook.decideSpirit(s), "the summoning hastened first");
		s.alacrityUp = true;
		assertEquals(Move.EARTH_SPIRIT, SpiritMasterPlaybook.decideSpirit(s));
		s.targetElite = true;
		assertEquals(Move.EARTH_SPIRIT, SpiritMasterPlaybook.decideSpirit(s), "without divine power the lasting one");
		s.dp = SpiritMasterPlaybook.ABILITY_DP;
		assertEquals(Move.TEMPEST_SPIRIT, SpiritMasterPlaybook.decideSpirit(s));
		s.targetElite = false;
		s.targetIsPlayer = true;
		assertEquals(Move.WATER_SPIRIT, SpiritMasterPlaybook.decideSpirit(s), "against a player, the slows");
	}

	@Test
	void noSpiritIsCalledUpByANearlyDeadMasterNorOutOfAFight() {
		Situation s = calm();
		s.spiritOut = false;
		s.hp = SpiritMasterPlaybook.RESUMMON_ABOVE;
		assertNull(SpiritMasterPlaybook.decideSpirit(s), "four and a half seconds rooted is a death");
		s.hp = 100;
		s.inFight = false;
		assertNull(SpiritMasterPlaybook.decideSpirit(s), "out of a fight the idle tick calls it up, in the order of the playbook");
	}

	@Test
	void theSpiritIsMendedByHowHurtItIs() {
		Situation s = calm();
		assertNull(SpiritMasterPlaybook.decideSpirit(s), "whole");
		s.spiritHp = SpiritMasterPlaybook.REPLENISH_BELOW - 1;
		assertEquals(Move.REPLENISH_ELEMENT, SpiritMasterPlaybook.decideSpirit(s));
		s.spiritHp = SpiritMasterPlaybook.HEALING_SPIRIT_BELOW - 1;
		assertEquals(Move.HEALING_SPIRIT, SpiritMasterPlaybook.decideSpirit(s));
		s.spiritHp = SpiritMasterPlaybook.DIVINE_ARMOR_BELOW - 1;
		assertEquals(Move.HEALING_SPIRIT, SpiritMasterPlaybook.decideSpirit(s), "Divine Spirit Armor needs its divine power");
		s.dp = SpiritMasterPlaybook.ABILITY_DP;
		assertEquals(Move.DIVINE_SPIRIT_ARMOR, SpiritMasterPlaybook.decideSpirit(s));
	}

	// upkeep

	@Test
	void theUpkeepIsStoneSkinThenTheSpiritsArmours() {
		Situation s = calm();
		assertNull(SpiritMasterPlaybook.decideUpkeep(s));
		s.armorSpiritOn = false;
		s.sympatheticOn = false;
		assertEquals(Move.ARMOR_SPIRIT, SpiritMasterPlaybook.decideUpkeep(s));
		s.stoneSkinUp = false;
		assertEquals(Move.STONE_SKIN, SpiritMasterPlaybook.decideUpkeep(s));
		s.stoneSkinUp = true;
		s.armorSpiritOn = true;
		assertEquals(Move.SYMPATHETIC_MIND, SpiritMasterPlaybook.decideUpkeep(s));
		s.spiritOut = false;
		assertNull(SpiritMasterPlaybook.decideUpkeep(s), "nothing to put on a spirit that is not there");
	}

	// defence

	@Test
	void aHeldMasterBreaksFreeAndDoesNothingElse() {
		Situation s = calm();
		s.controlled = true;
		s.hp = 10;
		assertEquals(Move.REMOVE_SHOCK, SpiritMasterPlaybook.decideDefence(s));
		s.ready.remove(Move.REMOVE_SHOCK);
		assertNull(SpiritMasterPlaybook.decideDefence(s));
		assertNull(SpiritMasterPlaybook.decideOffence(s));
		assertNull(SpiritMasterPlaybook.decideSpirit(s));
	}

	@Test
	void whatTurnedOnTheMasterIsSentBackToTheSpirit() {
		Situation s = calm();
		s.targetOnMe = true;
		assertEquals(Move.EMNITY_SWAP, SpiritMasterPlaybook.decideDefence(s), "the hate swapped onto the spirit");
		s.ready.remove(Move.EMNITY_SWAP);
		assertEquals(Move.SPIRIT_PIQUE, SpiritMasterPlaybook.decideDefence(s), "alone, then the spirit taunts");
		s.inTeam = true;
		assertNull(SpiritMasterPlaybook.decideDefence(s), "in a group the taunt would take the tank's enemies");
	}

	@Test
	void underHalfTheSpiritTakesTheBlowsAndUnderAFifthItIsSpent() {
		Situation s = calm();
		s.hp = SpiritMasterPlaybook.PROTECT_BELOW - 1;
		assertEquals(Move.ABSORB_WOUNDS, SpiritMasterPlaybook.decideDefence(s));
		s.ready.removeAll(List.of(Move.ABSORB_WOUNDS, Move.BODYGUARD, Move.SPIRIT_PRESERVE, Move.WALL_OF_PROTECTION));
		s.hp = SpiritMasterPlaybook.ABSORB_BELOW - 1;
		assertEquals(Move.SPIRIT_ABSORPTION, SpiritMasterPlaybook.decideDefence(s));
		s.spiritHp = SpiritMasterPlaybook.ABSORB_SPIRIT_ABOVE;
		s.hp = SpiritMasterPlaybook.KAMIKAZE_BELOW - 1;
		assertEquals(Move.KAMIKAZE, SpiritMasterPlaybook.decideDefence(s), "a spirit too hurt to give");
		s.greatSpirit = true;
		assertNotEquals(Move.KAMIKAZE, SpiritMasterPlaybook.decideDefence(s), "never twenty minutes of divine power in one blast");
	}

	@Test
	void aLoneMasterFrightensWhatSurroundsItAndAGroupedOneNever() {
		Situation s = calm();
		s.spiritOut = false;
		s.hp = SpiritMasterPlaybook.SHRIEK_BELOW - 1;
		s.earthenCallUp = true;
		s.enemiesWithin15m = SpiritMasterPlaybook.SHRIEK_ENEMIES;
		assertEquals(Move.FEAR_SHRIEK, SpiritMasterPlaybook.decideDefence(s));
		s.inTeam = true;
		assertNull(SpiritMasterPlaybook.decideDefence(s));
	}

	// offence

	@Test
	void theCommandsComeFirstAndTheClawFollowsTheDisturbance() {
		Situation s = calm();
		assertEquals(Move.SPIRIT_EROSION, SpiritMasterPlaybook.decideOffence(s));
		s.disturbanceChainOpen = true;
		assertEquals(Move.DETONATION_CLAW, SpiritMasterPlaybook.decideOffence(s), "the chain's three seconds are not wasted");
		s.disturbanceChainOpen = false;
		s.targetWithin15m = true;
		assertEquals(Move.RUINOUS_OFFENSIVE, SpiritMasterPlaybook.decideOffence(s), "within its reach");
		s.spiritOut = false;
		assertNotEquals(Move.SPIRIT_EROSION, SpiritMasterPlaybook.decideOffence(s), "no command without a spirit to carry it out");
	}

	@Test
	void theCursesAreKeptOnTheTarget() {
		Situation s = commandsSpent();
		assertNull(SpiritMasterPlaybook.decideOffence(s), "everything on, Stone Scour not begun under attack: the fillers are the generic order's");
		s.painOn = false;
		assertEquals(Move.INFERNAL_PAIN, SpiritMasterPlaybook.decideOffence(s));
		s.gloomOn = false;
		assertEquals(Move.WITHERING_GLOOM, SpiritMasterPlaybook.decideOffence(s));
		s = commandsSpent();
		s.freedomOn = s.shackleOn = false;
		assertNull(SpiritMasterPlaybook.decideOffence(s), "the defence and the slow are for an elite and a player");
		s.targetIsPlayer = true;
		s.ready.remove(Move.DISENCHANT);
		assertEquals(Move.MAGICS_FREEDOM, SpiritMasterPlaybook.decideOffence(s));
		s.freedomOn = true;
		assertEquals(Move.SHACKLE_OF_VULNERABILITY, SpiritMasterPlaybook.decideOffence(s));
	}

	@Test
	void stoneScourIsChargedOnlyWhileNothingHitsTheMaster() {
		Situation s = commandsSpent();
		s.assailed = false;
		assertEquals(Move.STONE_SCOUR, SpiritMasterPlaybook.decideOffence(s));
	}

	@Test
	void aSecondEnemyIsFrightenedAloneAndHeldInAGroup() {
		Situation s = calm();
		s.secondToControl = true;
		s.enemiesEngaged = 2;
		assertEquals(Move.FEAR, SpiritMasterPlaybook.decideOffence(s));
		s.inTeam = true;
		assertEquals(Move.BODY_ROOT, SpiritMasterPlaybook.decideOffence(s), "a frightened monster drags packs in");
	}

	@Test
	void theDivinePowerGoesToCursecloudAloneAmongThreeAndDisenchantOnAPlayer() {
		Situation s = calm();
		s.enemiesEngaged = SpiritMasterPlaybook.CURSECLOUD_ENEMIES;
		s.dp = SpiritMasterPlaybook.CURSECLOUD_DP;
		assertEquals(Move.CURSECLOUD, SpiritMasterPlaybook.decideOffence(s));
		s.inTeam = true;
		assertNotEquals(Move.CURSECLOUD, SpiritMasterPlaybook.decideOffence(s), "an area among the group's packs");
		s.targetIsPlayer = true;
		assertEquals(Move.DISENCHANT, SpiritMasterPlaybook.decideOffence(s));
	}

	@Test
	void aChainTheGenericOrderCarriesOnIsNotClosed() {
		Situation s = calm();
		s.followedChainOpen = true;
		s.stoneSkinUp = false;
		assertNull(SpiritMasterPlaybook.decideOffence(s), "Stone Shock after Chain of Earth is the generic order's");
		assertNull(SpiritMasterPlaybook.decideUpkeep(s));
		s.disturbanceChainOpen = true;
		assertEquals(Move.DETONATION_CLAW, SpiritMasterPlaybook.decideOffence(s), "the playbook's own chain is carried on");
		s.disturbanceChainOpen = false;
		s.spiritHp = SpiritMasterPlaybook.HEALING_SPIRIT_BELOW - 1;
		assertEquals(Move.HEALING_SPIRIT, SpiritMasterPlaybook.decideSpirit(s), "a dying spirit is worth more than a link");
	}

	@Test
	void aCasterIsSilenced() {
		Situation s = calm();
		s.targetCasts = true;
		assertEquals(Move.SIGIL_OF_SILENCE, SpiritMasterPlaybook.decideOffence(s));
	}
}
