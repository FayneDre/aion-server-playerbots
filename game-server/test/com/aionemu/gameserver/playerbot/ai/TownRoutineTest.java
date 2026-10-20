package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.ai.TownRoutine.Activity;
import com.aionemu.gameserver.playerbot.ai.TownRoutine.Quarter;

/**
 * A town resident's day: the same every day, an errand always somewhere, and a town that is mostly people standing about and going on errands rather
 * than all of them doing one thing at once.
 */
class TownRoutineTest {

	@Test
	void theSameQuarterIsSpentTheSameWayEveryDay() {
		for (int id = 1000; id < 1200; id++) {
			for (int minute = 0; minute < 24 * 60; minute += 7)
				assertEquals(TownRoutine.at(id, minute), TownRoutine.at(id, minute + 24 * 60), "resident " + id + " at minute " + minute);
		}
	}

	@Test
	void aQuarterIsOneThingFromItsFirstMinuteToItsLast() {
		for (int id = 1000; id < 1200; id++)
			assertEquals(TownRoutine.at(id, 9 * 60), TownRoutine.at(id, 9 * 60 + 14), "resident " + id);
	}

	@Test
	void anErrandAlwaysHasSomewhereToGo() {
		for (int id = 0; id < 5000; id++) {
			Quarter quarter = TownRoutine.at(id, 600);
			assertEquals(quarter.activity() == Activity.ERRAND, quarter.service() != null, "resident " + id + ": " + quarter);
		}
	}

	@Test
	void aTownIsMostlyStandingAboutAndErrands() {
		Map<Activity, Integer> counts = new EnumMap<>(Activity.class);
		int total = 0;
		for (int id = 0; id < 2000; id++) {
			for (int minute = 0; minute < 24 * 60; minute += TownRoutine.QUARTER_MINUTES) {
				counts.merge(TownRoutine.at(id, minute).activity(), 1, Integer::sum);
				total++;
			}
		}
		// the weights are 35, 20, 30 and 15 in a hundred: each share within two points of its own
		assertEquals(35, 100.0 * counts.get(Activity.HOME) / total, 2);
		assertEquals(20, 100.0 * counts.get(Activity.SIT) / total, 2);
		assertEquals(30, 100.0 * counts.get(Activity.ERRAND) / total, 2);
		assertEquals(15, 100.0 * counts.get(Activity.VISIT) / total, 2);
	}

	@Test
	void neighboursDoNotMoveAsOne() {
		// the residents of one place have neighbouring ids: if they all changed together, a square would empty and fill again every quarter
		int changed = 0;
		for (int id = 5000; id < 5100; id++) {
			if (TownRoutine.at(id, 600).activity() != TownRoutine.at(id + 1, 600).activity())
				changed++;
		}
		assertTrue(changed > 40, "neighbours differ " + changed + " times in 100");
	}

	@Test
	void theQuarterEndsOnTheQuarterHour() {
		assertEquals(15, TownRoutine.minutesLeft(600));
		assertEquals(1, TownRoutine.minutesLeft(614));
		assertEquals(14, TownRoutine.minutesLeft(24 * 60 + 1));
	}
}
