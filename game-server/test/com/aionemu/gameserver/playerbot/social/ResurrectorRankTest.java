package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.PlayerClass;

/**
 * Who raises a dead mate first, so that two bots do not raise the same body.
 */
class ResurrectorRankTest {

	@Test
	void theChanterThenTheClericThenTheBard() {
		assertTrue(BotGroupManager.resurrectorRank(PlayerClass.CHANTER) < BotGroupManager.resurrectorRank(PlayerClass.CLERIC));
		assertTrue(BotGroupManager.resurrectorRank(PlayerClass.CLERIC) < BotGroupManager.resurrectorRank(PlayerClass.BARD));
	}

	@Test
	void aClassWithoutAResurrectionIsNoneOfThem() {
		assertEquals(-1, BotGroupManager.resurrectorRank(PlayerClass.TEMPLAR));
		assertEquals(-1, BotGroupManager.resurrectorRank(PlayerClass.SORCERER));
	}
}
