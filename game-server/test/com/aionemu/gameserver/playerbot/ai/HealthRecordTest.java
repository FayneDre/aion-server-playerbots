package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class HealthRecordTest {

	private static final int TANK = 1, MATE = 2;

	@Test
	void aMemberNeverSeenHasLostNothing() {
		assertEquals(0, new HealthRecord().lossOverWindow(TANK));
	}

	@Test
	void theLossIsTheSumOfTheDropsAndAHealIsNotCounted() {
		HealthRecord record = new HealthRecord();
		record.sample(0, TANK, 100);
		record.sample(1000, TANK, 70); // -30
		record.sample(2000, TANK, 90); // healed: nothing counted
		record.sample(3000, TANK, 50); // -40

		assertEquals(70, record.lossOverWindow(TANK));
	}

	@Test
	void aTankHealedAsFastAsItIsHitStillShowsEverythingItTook() {
		HealthRecord record = new HealthRecord();
		int hp = 100;
		for (int second = 0; second < 30; second++) {
			record.sample(second * 1000L, TANK, hp);
			hp = hp == 100 ? 60 : 100; // hit for 40, healed back, over and over
		}

		assertTrue(record.lossOverWindow(TANK) >= 200, "fifteen blows of 40: " + record.lossOverWindow(TANK));
	}

	@Test
	void aSecondReadingSoonerThanASecondIsIgnored() {
		HealthRecord record = new HealthRecord();
		record.sample(0, TANK, 100);
		record.sample(300, TANK, 10);
		record.sample(600, TANK, 5);

		assertEquals(0, record.lossOverWindow(TANK), "a caller on a faster tick must not fill the ring with its own rate");
	}

	@Test
	void whatHappenedMoreThanThirtySecondsAgoIsForgotten() {
		HealthRecord record = new HealthRecord();
		record.sample(0, TANK, 100);
		record.sample(1000, TANK, 20); // -80
		for (int second = 2; second <= 40; second++)
			record.sample(second * 1000L, TANK, 20);

		assertEquals(0, record.lossOverWindow(TANK));
	}

	@Test
	void aLongGapPushesOldDropsOutAndCountsNoDropForTheGap() {
		HealthRecord record = new HealthRecord();
		record.sample(0, TANK, 100);
		record.sample(1000, TANK, 50); // -50
		record.sample(61_000, TANK, 50); // a minute with no reading

		assertEquals(0, record.lossOverWindow(TANK));
	}

	@Test
	void membersAreCountedByWhatTheyLost() {
		HealthRecord record = new HealthRecord();
		for (int member : new int[] { TANK, MATE }) {
			record.sample(0, member, 100);
			record.sample(1000, member, member == TANK ? 0 : 60);
		}
		record.sample(2000, TANK, 100);
		record.sample(3000, TANK, 0);

		assertEquals(1, record.membersWhoLostAtLeast(List.of(TANK, MATE), 100));
		assertEquals(2, record.membersWhoLostAtLeast(List.of(TANK, MATE), 40));
	}

	@Test
	void clearForgetsEverybody() {
		HealthRecord record = new HealthRecord();
		record.sample(0, TANK, 100);
		record.sample(1000, TANK, 0);
		record.clear();

		assertEquals(0, record.lossOverWindow(TANK));
	}
}
