package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.PlayerClass;

/**
 * Which class gets a playbook, and that none of them has an opinion yet. The second half is the milestone's whole promise: the seam is in, and a
 * templar plays as it did before it.
 */
class BotPlaybooksTest {

	@Test
	void everyClassHasAPlaybook() {
		for (PlayerClass playerClass : PlayerClass.values())
			assertNotNull(BotPlaybooks.of(playerClass), playerClass + " must get at least the empty playbook, or the caller needs a null check");
	}

	@Test
	void onlyTheTemplarHasOneOfItsOwn() {
		assertNotSame(ClassPlaybook.NONE, BotPlaybooks.of(PlayerClass.TEMPLAR));
		for (PlayerClass playerClass : PlayerClass.values()) {
			if (playerClass != PlayerClass.TEMPLAR)
				assertSame(ClassPlaybook.NONE, BotPlaybooks.of(playerClass), playerClass + " has no playbook yet");
		}
	}

	@Test
	void theTemplarLeavesTheFightToTheGenericOrder() {
		assertFalse(BotPlaybooks.of(PlayerClass.TEMPLAR).act(null, null));
	}
}
