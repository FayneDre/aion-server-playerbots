package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.ai.TownRoutine.Activity;
import com.aionemu.gameserver.playerbot.ai.TownRoutine.Spell;

/**
 * A town resident's day: the same every day, an errand always somewhere, a town that is not all doing one thing at once, and residents who do not all
 * set off at the same moment.
 */
class TownRoutineTest {

	private static final int DAY = 24 * 60 * 60;

	@Test
	void theSameSpellIsSpentTheSameWayEveryDay() {
		for (int id = 1000; id < 1200; id++) {
			for (int second = 0; second < DAY; second += 437)
				assertEquals(TownRoutine.at(id, second), TownRoutine.at(id, second + DAY), "resident " + id + " at second " + second);
		}
	}

	@Test
	void aSpellIsOneThingFromItsFirstSecondToItsLast() {
		for (int id = 1000; id < 1200; id++) {
			int start = 9 * 3600;
			int left = TownRoutine.secondsLeft(id, start);
			assertEquals(TownRoutine.at(id, start), TownRoutine.at(id, start + left - 1), "resident " + id);
			assertEquals(TownRoutine.SPELL_SECONDS, TownRoutine.secondsLeft(id, start + left), "a new spell starts where the last one ends");
		}
	}

	@Test
	void anErrandAlwaysHasSomewhereToGo() {
		for (int id = 0; id < 5000; id++) {
			Spell spell = TownRoutine.at(id, 36000);
			assertEquals(spell.activity() == Activity.ERRAND, spell.service() != null, "resident " + id + ": " + spell);
		}
	}

	@Test
	void aTownIsNotAllDoingOneThing() {
		Map<Activity, Integer> counts = new EnumMap<>(Activity.class);
		int total = 0;
		for (int id = 0; id < 2000; id++) {
			for (int second = 0; second < DAY; second += TownRoutine.SPELL_SECONDS) {
				counts.merge(TownRoutine.at(id, second).activity(), 1, Integer::sum);
				total++;
			}
		}
		// the weights are 20, 10, 35 and 35 in a hundred: each share within two points of its own
		assertEquals(20, 100.0 * counts.get(Activity.HOME) / total, 2);
		assertEquals(10, 100.0 * counts.get(Activity.SIT) / total, 2);
		assertEquals(35, 100.0 * counts.get(Activity.ERRAND) / total, 2);
		assertEquals(35, 100.0 * counts.get(Activity.VISIT) / total, 2);
	}

	@Test
	void residentsSetOffAtMomentsOfTheirOwn() {
		// spells on the clock had everybody set off in the same second: a town walked for a minute and stood still for fourteen
		int[] startsByMinute = new int[TownRoutine.SPELL_SECONDS / 60];
		int residents = 1000;
		for (int id = 0; id < residents; id++) {
			int startsIn = TownRoutine.secondsLeft(id, 0) % TownRoutine.SPELL_SECONDS;
			startsByMinute[startsIn / 60]++;
		}
		for (int minute = 0; minute < startsByMinute.length; minute++)
			assertEquals(residents / startsByMinute.length, startsByMinute[minute], residents / 20, "spells starting in minute " + minute);
	}

	@Test
	void eightResidentsInTenAreMerchants() {
		int merchants = 0;
		for (int id = 0; id < 2000; id++) {
			if (TownRoutine.isMerchant(id))
				merchants++;
		}
		assertEquals(1600, merchants, 60);
	}

	@Test
	void theRotaKeepsTheSameNumberOfStoresAtEveryHour() {
		// the number is the rota's and not chance: Sanctum, with a quarter fewer residents than Pandaemonium, held 26 stores against its 40
		for (int merchants : new int[] { 45, 77, 102, 300 }) {
			for (int second = 0; second < DAY; second += 600) {
				int open = 0;
				for (int rank = 0; rank < merchants; rank++) {
					if (TownRoutine.onRota(rank, merchants, second))
						open++;
				}
				assertEquals(TownRoutine.ON_SHIFT, open, merchants + " merchants at second " + second);
			}
		}
	}

	@Test
	void aShiftIsOneStretchAsLongAsTheTownNeeds() {
		int merchants = 77, minutes = 0, starts = 0;
		boolean was = TownRoutine.onRota(10, merchants, DAY - 60);
		for (int second = 0; second < DAY; second += 60) {
			boolean is = TownRoutine.onRota(10, merchants, second);
			if (is)
				minutes++;
			if (is && !was)
				starts++;
			was = is;
		}
		assertEquals(1, starts, "one shift a day");
		assertEquals(24 * 60 * TownRoutine.ON_SHIFT / merchants, minutes, 2, "44 of 77 merchants: about 14 hours");
	}

	@Test
	void aTownWithFewMerchantsKeepsThemAllOnShift() {
		assertTrue(TownRoutine.onRota(30, 40, 12345));
	}

	@Test
	void neighboursDoNotMoveAsOne() {
		// the residents of one place have neighbouring ids: if they all changed together, a square would empty and fill again every spell
		int changed = 0;
		for (int id = 5000; id < 5100; id++) {
			if (TownRoutine.at(id, 36000).activity() != TownRoutine.at(id + 1, 36000).activity())
				changed++;
		}
		assertTrue(changed > 40, "neighbours differ " + changed + " times in 100");
	}
}
