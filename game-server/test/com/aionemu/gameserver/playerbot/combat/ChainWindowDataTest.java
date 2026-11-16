package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.ChainSkills;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The window {@link BotSkillManager#isChainOpen} reads: no link of the data may be given longer, or a chain would be called closed while its next link
 * could still be cast. Skipped when the game data is not in the checkout.
 */
class ChainWindowDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");

	@Test
	void noLinkIsGivenLongerThanTheWindowTheBotReads() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		int longest = 0;
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null && template.getChainCondition() != null)
					longest = Math.max(longest, template.getChainCondition().getActivationWindow());
			}
		}
		assertTrue(longest > 0, "the data has chain windows");
		assertTrue(longest <= BotSkillManager.LONGEST_CHAIN_WINDOW_MILLIS, "a link is given " + longest + " ms");
	}

	@Test
	void aChainIsOpenWithinItsWindowAndClosedPastIt() {
		ChainSkills chain = new ChainSkills();
		chain.updateChain("E_CHAINA_1TH_1", 0);
		assertTrue(chain.canContinue("E_CHAINA_1TH_1", 1, BotSkillManager.LONGEST_CHAIN_WINDOW_MILLIS));
		chain.updateChain("E_CHAINA_1TH_1", -BotSkillManager.LONGEST_CHAIN_WINDOW_MILLIS - 1);
		assertFalse(chain.canContinue("E_CHAINA_1TH_1", 1, BotSkillManager.LONGEST_CHAIN_WINDOW_MILLIS), "a hit landed longer ago than any window");
	}
}
