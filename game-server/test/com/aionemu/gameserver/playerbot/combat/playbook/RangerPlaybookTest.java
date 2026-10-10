package com.aionemu.gameserver.playerbot.combat.playbook;

import static com.aionemu.gameserver.playerbot.combat.playbook.RangerPlaybook.Move.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.combat.playbook.RangerPlaybook.EnemyKind;
import com.aionemu.gameserver.playerbot.combat.playbook.RangerPlaybook.Hold;
import com.aionemu.gameserver.playerbot.combat.playbook.RangerPlaybook.Move;
import com.aionemu.gameserver.playerbot.combat.playbook.RangerPlaybook.Situation;

/**
 * The ranger's rules, argued without a world: each number here is one the guide gives.
 */
class RangerPlaybookTest {

	private static final Hold FREE = new Hold(false, false, 0, 0);

	/** A ranger alone at full health, with nothing on it and nothing ready; each test changes only what it is about. */
	private static final class Scene {
		int hp = 100;
		int dp;
		boolean inTeam, targeted, controlled, shockChainOpen, breathUp, bowUp, windUp, transformed, evasionUp, opened, inMelee, otherEnemy;
		int oneMinuteUp;
		EnemyKind kind = EnemyKind.OTHER;
		Hold hold = FREE;
		final Set<Move> ready = EnumSet.noneOf(Move.class);

		Scene ready(Move... moves) {
			ready.addAll(Set.of(moves));
			return this;
		}

		Scene hp(int hp) {
			this.hp = hp;
			return this;
		}

		Scene grouped() {
			this.inTeam = true;
			return this;
		}

		Situation build() {
			return new Situation(hp, dp, inTeam, targeted, controlled, shockChainOpen, oneMinuteUp, breathUp, bowUp, windUp, transformed, evasionUp, kind, opened,
				inMelee, otherEnemy, hold, ready);
		}

		Move fight() {
			return RangerPlaybook.decide(build());
		}

		Move atRest() {
			return RangerPlaybook.decideAtRest(build());
		}
	}

	private static Scene scene() {
		return new Scene();
	}

	@Test
	void theOneMinuteBuffsGoOnBestFirstAndNeverMoreThanTwoAtOnce() {
		Scene s = scene().ready(DODGING, AIMING, STRONG_SHOTS);
		assertEquals(STRONG_SHOTS, s.atRest(), "Strong Shots leads");
		s.ready.remove(STRONG_SHOTS);
		s.oneMinuteUp = 1;
		assertEquals(AIMING, s.atRest());
		s.oneMinuteUp = 2;
		assertNull(s.atRest(), "two are up: a third would remove the first, and the ranger would cast it again for ever");
	}

	@Test
	void focusedShotsTakeAimingsPlaceInTheOrder() {
		assertEquals(FOCUSED_SHOTS, scene().ready(AIMING, FOCUSED_SHOTS, DODGING).atRest());
		assertEquals(AIMING, scene().ready(AIMING, DODGING).atRest());
	}

	@Test
	void bestialFuryIsNeverInTheOrder() {
		assertFalse(RangerPlaybook.ONE_MINUTE_ORDER.stream().anyMatch(move -> move.group.equals("RA_STIGMA_TIGEREYE")));
		assertTrue(RangerPlaybook.NEVER_GROUPS.contains("RA_STIGMA_TIGEREYE"));
	}

	@Test
	void breathOfNatureIsCastOnceAndNeverSwitchedOff() {
		assertEquals(BREATH_OF_NATURE, scene().ready(BREATH_OF_NATURE).atRest());
		Scene up = scene().ready(BREATH_OF_NATURE);
		up.breathUp = true;
		assertNull(up.atRest(), "a toggle cast again turns itself off");
	}

	@Test
	void bowOfBlessingAndSpeedOfTheWindFollowTheBuffsAndNotTwice() {
		Scene s = scene().ready(BOW_OF_BLESSING, SPEED_OF_THE_WIND);
		assertEquals(BOW_OF_BLESSING, s.atRest());
		s.bowUp = true;
		assertEquals(SPEED_OF_THE_WIND, s.atRest());
		s.windUp = true;
		assertNull(s.atRest());
		Scene both = scene().ready(STRONG_SHOTS, SPEED_OF_THE_WIND);
		assertEquals(STRONG_SHOTS, both.atRest(), "the wind comes after the one minute buffs");
	}

	@Test
	void devotionAndTheTransformationAreForTheFightAndTheTransformationNeedsTheDivinePower() {
		assertNull(scene().ready(DEVOTION, TRANSFORMATION_MAU).atRest(), "not out of a fight");
		assertEquals(DEVOTION, scene().ready(DEVOTION, TRANSFORMATION_MAU).fight(), "Devotion before any damage");
		Scene transform = scene().ready(TRANSFORMATION_MAU);
		transform.dp = 1999;
		assertNull(transform.fight());
		transform.dp = 2000;
		assertEquals(TRANSFORMATION_MAU, transform.fight());
		transform.transformed = true;
		assertNull(transform.fight(), "already transformed");
		Scene light = scene().ready(TRANSFORMATION_MAU_LIGHT);
		light.dp = 2000;
		assertEquals(TRANSFORMATION_MAU_LIGHT, light.fight(), "the other faction's Mau");
	}

	@Test
	void seizureArrowUnderThreeQuartersOfHealth() {
		assertEquals(SEIZURE_ARROW, scene().hp(74).ready(SEIZURE_ARROW).fight());
		assertNull(scene().hp(75).ready(SEIZURE_ARROW).fight());
	}

	@Test
	void focusedEvasionComesFirstWhenTargetedUnderHalfAndNeverOnItsOwn() {
		Scene s = scene().hp(49).ready(FOCUSED_EVASION, SEIZURE_ARROW);
		s.targeted = true;
		assertEquals(FOCUSED_EVASION, s.fight());
		s.evasionUp = true;
		assertEquals(SEIZURE_ARROW, s.fight(), "then the arrow");
		Scene notTargeted = scene().hp(49).ready(FOCUSED_EVASION, SEIZURE_ARROW);
		assertEquals(SEIZURE_ARROW, notTargeted.fight(), "nobody is aiming at it: the arrow alone");
		Scene noArrow = scene().hp(49).ready(FOCUSED_EVASION);
		noArrow.targeted = true;
		assertNull(noArrow.fight(), "the evasion is half a combo, never cast alone");
	}

	@Test
	void soloTheOpenerSuitsTheEnemyAndIsSpentOnce() {
		Scene melee = scene().ready(ENTANGLING_SHOT, SILENCE_ARROW);
		melee.kind = EnemyKind.MELEE;
		assertEquals(ENTANGLING_SHOT, melee.fight());
		Scene caster = scene().ready(ENTANGLING_SHOT, SILENCE_ARROW);
		caster.kind = EnemyKind.CASTER;
		assertEquals(SILENCE_ARROW, caster.fight());
		caster.opened = true;
		assertNull(caster.fight(), "already opened on this enemy");
		Scene other = scene().ready(ENTANGLING_SHOT, SILENCE_ARROW);
		assertNull(other.fight(), "an archer is neither");
	}

	@Test
	void shackleArrowAnswersAnEnemyThatReachesMeleeWhenAlone() {
		Scene s = scene().ready(SHACKLE_ARROW);
		s.inMelee = true;
		assertEquals(SHACKLE_ARROW, s.fight());
		s.inTeam = true;
		assertNull(s.fight(), "in a group the tank has it");
	}

	@Test
	void sleepArrowIsForAnotherEnemyThanTheMainOneInAGroup() {
		Scene s = scene().grouped().ready(SLEEP_ARROW);
		s.otherEnemy = true;
		assertEquals(SLEEP_ARROW, s.fight());
		s.otherEnemy = false;
		assertNull(s.fight(), "one enemy: it would put the main target to sleep");
		Scene alone = scene().ready(SLEEP_ARROW);
		alone.otherEnemy = true;
		assertNull(alone.fight());
	}

	@Test
	void aStunnedRangerCastsRemoveShockAndThenFinishingArrow() {
		Scene stunned = scene().hp(10).ready(REMOVE_SHOCK, SEIZURE_ARROW);
		stunned.controlled = true;
		assertEquals(REMOVE_SHOCK, stunned.fight());
		stunned.ready.remove(REMOVE_SHOCK);
		assertNull(stunned.fight());
		Scene open = scene().hp(10).ready(FINISHING_ARROW, SEIZURE_ARROW);
		open.shockChainOpen = true;
		assertEquals(FINISHING_ARROW, open.fight(), "ahead of everything, the chain lasts a second and a half");
	}

	@Test
	void aGroupedRangerHoldsItsFireAndCallsOffTheEnemyFirst() {
		Scene start = scene().grouped().ready(CALMING_WHISPER, SEIZURE_ARROW);
		start.hold = new Hold(true, true, 1000, 0);
		assertEquals(CALMING_WHISPER, start.fight(), "as it starts");
		Scene holding = scene().grouped().ready(CALMING_WHISPER);
		holding.hold = new Hold(true, false, 1000, 0);
		assertEquals(HOLD_FIRE, holding.fight());
		Scene noWhisper = scene().grouped();
		noWhisper.hold = new Hold(true, true, 1000, 0);
		assertEquals(HOLD_FIRE, noWhisper.fight(), "not ready: it still stops");
	}

	@Test
	void theHoldStartsWhenTargetedLastsFiveSecondsEndsWhenTheEnemyLooksAwayAndRearms() {
		Hold started = RangerPlaybook.nextHold(true, true, 10_000, 0, 0);
		assertTrue(started.holding() && started.startedNow());
		Hold still = RangerPlaybook.nextHold(true, true, 12_000, 10_000, 0);
		assertTrue(still.holding() && !still.startedNow());
		Hold lookedAway = RangerPlaybook.nextHold(true, false, 12_000, 10_000, 0);
		assertFalse(lookedAway.holding(), "released as soon as it is no longer the target");
		Hold timedOut = RangerPlaybook.nextHold(true, true, 15_000, 10_000, 0);
		assertFalse(timedOut.holding());
		assertEquals(25_000, timedOut.rearmAt(), "ten seconds before another");
		assertFalse(RangerPlaybook.nextHold(true, true, 16_000, 0, 25_000).holding(), "inside the rearm time it shoots on");
		assertFalse(RangerPlaybook.nextHold(false, true, 10_000, 0, 0).holding(), "alone there is nobody to hand the enemy to");
	}

	@Test
	void theGuidesFleeNumberAndStigmasAreAsWritten() {
		assertEquals(3, RangerPlaybook.ATTACKERS_TO_FLEE);
		assertEquals(List.of("RA_ENCHANTBOW", "SC_TRUESHOTMIND", "RA_SPOUTARROW"), new RangerPlaybook().preferredStigmas(false));
		assertEquals(List.of("RA_MASSEXPLOSIONARROW", "RA_MOVINGSHOT", "RA_PAINARROW"), new RangerPlaybook().preferredStigmas(true));
	}

	@Test
	void anIdleRangerWithNothingReadyHasNothingToSay() {
		assertNull(scene().fight());
		assertNull(scene().atRest());
	}
}
