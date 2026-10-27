package com.aionemu.gameserver.playerbot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.ai.BotDeath.Revival;

/**
 * How a dead bot comes back, decided without a world.
 */
class BotDeathTest {

	private static final long DIED_AT = 1_000_000;

	@Test
	void aBotWaitsAMinuteAfterItsGroupLastFought() {
		long fought = DIED_AT + 30_000;
		assertEquals(BotDeath.RESURRECTION_WAIT_MILLIS, BotDeath.waitLeft(fought, DIED_AT, fought));
		assertEquals(0, BotDeath.waitLeft(fought + BotDeath.RESURRECTION_WAIT_MILLIS, DIED_AT, fought));
	}

	@Test
	void aGroupThatNeverStopsFightingDoesNotKeepTheBotDownPastTenMinutes() {
		long now = DIED_AT + BotDeath.LONGEST_RESURRECTION_WAIT_MILLIS;
		assertEquals(0, BotDeath.waitLeft(now, DIED_AT, now));
		assertTrue(BotDeath.waitLeft(now + 1, DIED_AT, now + 1) < 0);
	}

	@Test
	void aResurrectionIsTakenOnlyWhenOneWasCast() {
		assertEquals(Revival.RESURRECTION, BotDeath.revivalFor(true, true, false));
		assertEquals(Revival.RESURRECTION, BotDeath.revivalFor(true, true, true));
		// asked to take one that is not there: the audit log would call it a hack, so the bot releases instead
		assertEquals(Revival.OBELISK, BotDeath.revivalFor(true, false, false));
		assertEquals(Revival.INSTANCE_ENTRANCE, BotDeath.revivalFor(true, false, true));
	}

	@Test
	void aBotReleasedInsideAnInstanceComesBackAtItsEntrance() {
		assertEquals(Revival.INSTANCE_ENTRANCE, BotDeath.revivalFor(false, true, true));
		assertEquals(Revival.OBELISK, BotDeath.revivalFor(false, true, false));
	}

	@Test
	void aHomeOnAnotherLayerIsTakenHomeToButNotInAGroup() {
		Vector3f upperLayer = new Vector3f(1000, 1000, 2900);
		assertTrue(BotDeath.livesOnAnotherLayer(upperLayer, false, 1600));
		assertFalse(BotDeath.livesOnAnotherLayer(upperLayer, true, 1600));
		assertFalse(BotDeath.livesOnAnotherLayer(upperLayer, false, 2900 - BotDeath.ANOTHER_LAYER));
		assertFalse(BotDeath.livesOnAnotherLayer(null, false, 1600));
	}
}
