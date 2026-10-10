package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.ChanterPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.ChanterPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.ChanterPlaybook.Situation;

/**
 * The chanter's thresholds, argued without a world: each rule here is a number the guide gives.
 */
class ChanterPlaybookTest {

	/** A chanter alone at full health and mana with nothing on it; each test changes only what it is about. */
	private static final class Scene {
		int hp = 100;
		int mp = 100;
		int level = 50;
		int dp;
		boolean inTeam;
		int tankHp = -1;
		boolean debuffed, wardUp, ascensionUp, unstoppableUp, parryUp, quicknessUp, mending, mateToRaise, controlled, shockChainOpen;
		int hurtCount;
		final Set<Move> ready = EnumSet.noneOf(Move.class);

		Scene ready(Move... moves) {
			ready.addAll(Set.of(moves));
			return this;
		}

		Scene hp(int hp) {
			this.hp = hp;
			return this;
		}

		Scene dp(int dp, int level) {
			this.dp = dp;
			this.level = level;
			return this;
		}

		Scene mp(int mp) {
			this.mp = mp;
			return this;
		}

		Scene grouped(int tankHp) {
			this.inTeam = true;
			this.tankHp = tankHp;
			return this;
		}

		Situation build() {
			return new Situation(hp, mp, level, dp, inTeam, tankHp, debuffed, wardUp, ascensionUp, unstoppableUp, parryUp, quicknessUp, mending, mateToRaise, hurtCount, controlled, shockChainOpen, ready);
		}

		Move fight() {
			return ChanterPlaybook.decide(build());
		}

		Move atRest() {
			return ChanterPlaybook.decideAtRest(build());
		}
	}

	private static Scene scene() {
		return new Scene();
	}

	@Test
	void theWardIsRaisedUnderHalfHealthAndNotAboveItOrTwice() {
		assertEquals(PROTECTIVE_WARD, scene().hp(49).ready(PROTECTIVE_WARD).fight());
		assertNull(scene().hp(50).ready(PROTECTIVE_WARD).fight());
		Scene up = scene().hp(40).ready(PROTECTIVE_WARD);
		up.wardUp = true;
		assertNull(up.fight(), "already up");
	}

	@Test
	void theCurtainIsForTheTankInAGroupAndForTheChanterAlone() {
		assertEquals(BLOCK_CURTAIN, scene().hp(29).ready(BLOCK_CURTAIN).fight());
		assertNull(scene().hp(30).ready(BLOCK_CURTAIN).fight());
		// in a group it is the tank's health that counts, whatever the chanter's own
		assertEquals(BLOCK_CURTAIN, scene().hp(100).grouped(29).ready(BLOCK_CURTAIN).fight());
		assertNull(scene().hp(10).grouped(80).ready(BLOCK_CURTAIN).fight(), "the tank is fine");
		// a group with no tank falls back on the chanter
		assertEquals(BLOCK_CURTAIN, scene().hp(20).grouped(-1).ready(BLOCK_CURTAIN).fight());
	}

	@Test
	void theDispelIsCastWhenSomethingCanBeDispelledAndLeadsEverything() {
		Scene debuffed = scene().hp(20).ready(ENERGY_DISCHARGE, PROTECTIVE_WARD, BLOCK_CURTAIN);
		debuffed.debuffed = true;
		assertEquals(ENERGY_DISCHARGE, debuffed.fight());
		assertEquals(ENERGY_DISCHARGE, debuffed.atRest(), "out of a fight as well");
		assertNull(scene().ready(ENERGY_DISCHARGE).atRest(), "nothing to dispel");
		Scene notReady = scene();
		notReady.debuffed = true;
		assertNull(notReady.atRest(), "not ready");
	}

	@Test
	void ascensionAndUnstoppableAreRenewedOnlyWhenTheyHaveRunOut() {
		assertEquals(ASCENSION_SPELL, scene().ready(ASCENSION_SPELL, UNSTOPPABLE).fight());
		Scene ascensionUp = scene().ready(ASCENSION_SPELL, UNSTOPPABLE);
		ascensionUp.ascensionUp = true;
		assertEquals(UNSTOPPABLE, ascensionUp.fight());
		ascensionUp.unstoppableUp = true;
		assertNull(ascensionUp.fight());
	}

	@Test
	void perfectParryIsCastWhenTheStrikeIsReadyAndTheStrikeFollowsIt() {
		assertEquals(PERFECT_PARRY, scene().ready(PERFECT_PARRY, PARRYING_STRIKE).fight());
		assertNull(scene().ready(PERFECT_PARRY).fight(), "no strike to pair it with");
		Scene parrying = scene().ready(PARRYING_STRIKE);
		parrying.parryUp = true;
		assertEquals(PARRYING_STRIKE, parrying.fight());
		parrying.ready.clear();
		assertNull(parrying.fight(), "the strike is on cooldown");
	}

	@Test
	void staminaRestorationIsForHealthOrManaUnderSixtyAndWorksInAGroupToo() {
		assertEquals(STAMINA_RESTORATION, scene().hp(59).ready(STAMINA_RESTORATION).fight());
		assertEquals(STAMINA_RESTORATION, scene().mp(59).ready(STAMINA_RESTORATION).fight(), "mana counts as much as health");
		assertNull(scene().hp(60).mp(60).ready(STAMINA_RESTORATION).fight());
		assertEquals(STAMINA_RESTORATION, scene().hp(50).grouped(100).ready(STAMINA_RESTORATION).fight());
		assertEquals(STAMINA_RESTORATION, scene().hp(50).ready(STAMINA_RESTORATION).atRest(), "after a fight as well");
	}

	@Test
	void healingLightIsForAChanterAloneAndOnlyAfterTheFightUnderEighty() {
		assertEquals(HEALING_LIGHT, scene().hp(79).ready(HEALING_LIGHT).atRest());
		assertNull(scene().hp(80).ready(HEALING_LIGHT).atRest());
		assertNull(scene().hp(40).grouped(100).ready(HEALING_LIGHT).atRest(), "in a group the chanter heals nobody, itself included");
		assertNull(scene().hp(70).ready(HEALING_LIGHT).fight(), "in a fight it is not the answer until the situation is critical");
	}

	@Test
	void theCriticalSequenceRunsInTheGuidesOrderAndSkipsWhatIsNotReady() {
		Scene critical = scene().hp(19).ready(WORD_OF_QUICKNESS, RECOVERY_SPELL, WORD_OF_REVIVAL, HEALING_LIGHT);
		assertEquals(WORD_OF_QUICKNESS, critical.fight());
		critical.quicknessUp = true;
		assertEquals(RECOVERY_SPELL, critical.fight());
		critical.ready.remove(RECOVERY_SPELL);
		assertEquals(WORD_OF_REVIVAL, critical.fight());
		critical.ready.remove(WORD_OF_REVIVAL);
		assertEquals(HEALING_LIGHT, critical.fight());
		critical.ready.remove(HEALING_LIGHT);
		assertNull(critical.fight(), "nothing left to cast: the generic order fights on");
	}

	@Test
	void onceTheSequenceHasBegunItGoesOnUntilTheChanterIsWhole() {
		Scene mending = scene().hp(60).ready(HEALING_LIGHT);
		assertNull(mending.fight(), "60 % and not mending: it fights");
		mending.mending = true;
		assertEquals(HEALING_LIGHT, mending.fight(), "60 % but it began at 19: it goes on");
		assertEquals(HEALING_LIGHT, mending.atRest(), "and after the fight too, up to full");
	}

	@Test
	void inAGroupThereIsNoCriticalSequence() {
		assertNull(scene().hp(10).grouped(100).ready(WORD_OF_QUICKNESS, RECOVERY_SPELL, WORD_OF_REVIVAL, HEALING_LIGHT).fight());
	}

	@Test
	void aDeadMateIsRaisedAfterQuickeningTheGroupInAFightAndAtOnceAfterIt() {
		Scene dead = scene().grouped(100).ready(WORD_OF_QUICKNESS, LIGHT_OF_RESURRECTION);
		dead.mateToRaise = true;
		assertEquals(WORD_OF_QUICKNESS, dead.fight());
		dead.quicknessUp = true;
		assertEquals(LIGHT_OF_RESURRECTION, dead.fight());
		dead.quicknessUp = false;
		dead.ready.remove(WORD_OF_QUICKNESS);
		assertEquals(LIGHT_OF_RESURRECTION, dead.fight(), "quickening is skipped when it is not ready");
		Scene after = scene().grouped(100).ready(WORD_OF_QUICKNESS, LIGHT_OF_RESURRECTION);
		after.mateToRaise = true;
		assertEquals(LIGHT_OF_RESURRECTION, after.atRest(), "after the fight the spell is cast without the quickening");
	}

	@Test
	void nothingIsCastWhenThereIsNoOneToRaiseOrSomebodyElseIsTo() {
		assertNull(scene().grouped(100).ready(LIGHT_OF_RESURRECTION).fight());
		assertNull(scene().grouped(100).ready(LIGHT_OF_RESURRECTION).atRest());
	}

	@Test
	void divineProtectionBeforeLevelThirtyOneIsCastAsSoonAsItIsPaidFor() {
		assertEquals(DIVINE_PROTECTION, scene().dp(2000, 30).ready(DIVINE_PROTECTION).fight());
		assertNull(scene().dp(1999, 30).ready(DIVINE_PROTECTION).fight(), "not enough divine power: the engine would refuse it");
		assertNull(scene().dp(5000, 30).fight(), "nothing ready");
	}

	@Test
	void fromLevelThirtyOneDivineProtectionWaitsForTwoAbilitiesWorthOfDivinePower() {
		assertNull(scene().dp(3999, 31).ready(DIVINE_PROTECTION).fight());
		assertEquals(DIVINE_PROTECTION, scene().dp(4000, 31).ready(DIVINE_PROTECTION).fight());
	}

	@Test
	void wordOfWindComesFirstAndIsCastWheneverItIsReadyAndPaidFor() {
		assertEquals(WORD_OF_WIND, scene().dp(2000, 40).ready(WORD_OF_WIND, DIVINE_PROTECTION).fight());
		assertEquals(WORD_OF_WIND, scene().dp(4000, 40).ready(WORD_OF_WIND, DIVINE_PROTECTION).fight(), "and the protection after it, on the next tick");
		assertNull(scene().dp(1999, 40).ready(WORD_OF_WIND).fight());
		assertEquals(WORD_OF_WIND, scene().dp(2000, 40).grouped(100).ready(WORD_OF_WIND).fight(), "in a group as alone");
	}

	@Test
	void aStunnedChanterCastsRemoveShockAndNothingElse() {
		Scene stunned = scene().hp(10).ready(REMOVE_SHOCK, PROTECTIVE_WARD, BLOCK_CURTAIN);
		stunned.controlled = true;
		assertEquals(REMOVE_SHOCK, stunned.fight());
		stunned.ready.remove(REMOVE_SHOCK);
		assertNull(stunned.fight(), "nothing else can be cast while it is held");
	}

	@Test
	void backshockFollowsRemoveShockAtOnceAndOnlyThen() {
		Scene open = scene().hp(10).ready(BACKSHOCK, PROTECTIVE_WARD, ASCENSION_SPELL);
		open.shockChainOpen = true;
		assertEquals(BACKSHOCK, open.fight(), "ahead of the ward, which would close the chain");
		open.ready.remove(BACKSHOCK);
		assertEquals(PROTECTIVE_WARD, open.fight(), "on cooldown: the rest carries on");
		assertNull(scene().ready(BACKSHOCK).fight(), "no chain open: the generic order's own business");
	}

	@Test
	void wordOfLifeIsForTheChanterAloneAndForTwoHurtInAGroup() {
		Scene alone = scene().ready(WORD_OF_LIFE);
		alone.hurtCount = 1;
		assertEquals(WORD_OF_LIFE, alone.fight());
		assertEquals(WORD_OF_LIFE, alone.atRest(), "and after the fight");
		Scene grouped = scene().grouped(100).ready(WORD_OF_LIFE);
		grouped.hurtCount = 1;
		assertNull(grouped.fight(), "one hurt in a group is not worth the cooldown");
		grouped.hurtCount = 2;
		assertEquals(WORD_OF_LIFE, grouped.fight());
		assertNull(scene().ready(WORD_OF_LIFE).fight(), "nobody hurt");
	}

	@Test
	void theStigmasAreTheGuidesInItsOrder() {
		ChanterPlaybook chanter = new ChanterPlaybook();
		assertEquals(java.util.List.of("CH_IMPROVEDLIFESTREAM", "CH_TRIPLETHRUST", "CH_HARSHTHRUST"), chanter.preferredStigmas(false));
		assertEquals(java.util.List.of("CH_BLESSPROTECT", "CH_SHOCKWAVE", "CH_SLOWCRASH"), chanter.preferredStigmas(true));
	}

	@Test
	void aHealthyChanterWithNothingReadyHasNothingToSay() {
		assertNull(scene().fight());
	}
}
