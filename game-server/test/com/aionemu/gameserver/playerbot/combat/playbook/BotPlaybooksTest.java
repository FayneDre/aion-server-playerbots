package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.PlayerClass;

/**
 * Which class gets a playbook.
 */
class BotPlaybooksTest {

	private static final Set<PlayerClass> WITH_A_PLAYBOOK = Set.of(PlayerClass.TEMPLAR, PlayerClass.CHANTER, PlayerClass.RANGER, PlayerClass.CLERIC);

	@Test
	void everyClassHasAPlaybook() {
		for (PlayerClass playerClass : PlayerClass.values())
			assertNotNull(BotPlaybooks.of(playerClass), playerClass + " must get at least the empty playbook, or the caller needs a null check");
	}

	@Test
	void onlyTheClassesWithAGuideHaveOneOfTheirOwn() {
		for (PlayerClass playerClass : PlayerClass.values()) {
			if (WITH_A_PLAYBOOK.contains(playerClass))
				assertNotSame(ClassPlaybook.NONE, BotPlaybooks.of(playerClass), playerClass + " has a playbook");
			else
				assertSame(ClassPlaybook.NONE, BotPlaybooks.of(playerClass), playerClass + " has no playbook yet");
		}
	}

	@Test
	void theChantersOrderNamesEveryMantraOnceAndPutsTheGuidesChoicesFirst() {
		ClassPlaybook chanter = BotPlaybooks.of(PlayerClass.CHANTER);
		for (boolean inTeam : new boolean[] { false, true }) {
			assertEquals(Set.copyOf(ChanterPlaybook.MANTRA_GROUPS), Set.copyOf(chanter.mantraOrder(inTeam)));
			assertEquals(ChanterPlaybook.MANTRA_GROUPS.size(), chanter.mantraOrder(inTeam).size(), "no mantra twice");
			assertEquals("CH_CHANT_IMPROVEDCRITICAL", chanter.mantraOrder(inTeam).get(0), "Hit first");
			assertEquals("CH_CHANT_INVINCIBLE", chanter.mantraOrder(inTeam).get(1));
			assertEquals("CH_CHANT_WINDYWINGS", chanter.mantraOrder(inTeam).get(5), "Wind last");
		}
		assertTrue(rank(chanter, false, "CH_CHANT_REGENERATION") < rank(chanter, false, "CH_CHANT_IMPROVEDMOVE"), "alone, Revival before Celerity");
		assertTrue(rank(chanter, true, "CH_CHANT_IMPROVEDMOVE") < rank(chanter, true, "CH_CHANT_REGENERATION"), "in a group, Celerity before Revival");
	}

	@Test
	void everyOtherClassKeepsTheNewestMantraFirst() {
		assertTrue(ClassPlaybook.NONE.mantraOrder(true).isEmpty());
		assertTrue(BotPlaybooks.of(PlayerClass.TEMPLAR).mantraOrder(false).isEmpty());
	}

	private static int rank(ClassPlaybook playbook, boolean inTeam, String group) {
		return playbook.mantraOrder(inTeam).indexOf(group);
	}
}
