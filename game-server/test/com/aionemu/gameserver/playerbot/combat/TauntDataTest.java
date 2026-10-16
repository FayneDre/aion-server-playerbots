package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Which skills count as a taunt, which a bot that is not the group's tank keeps out of its fights. Read out of the game's data, since "raises hate and
 * does no damage" is a property of the templates. Skipped when the game data is not in the checkout.
 */
class TauntDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");
	private static final Map<String, SkillTemplate> byGroup = new HashMap<>();

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null && template.getGroup() != null)
					byGroup.putIfAbsent(template.getGroup(), template);
			}
		}
	}

	@Test
	void theWarriorsTauntIsATaunt() {
		assertTrue(BotSkillManager.isTaunt(byGroup.get("WA_PROVOKE")), "a gladiator learns it too, and cast it in a group");
		assertTrue(BotSkillManager.isTaunt(byGroup.get("KN_HIGHPROVOKE")));
	}

	@Test
	void anAttackThatAddsHateIsStillAnAttack() {
		assertFalse(BotSkillManager.isTaunt(byGroup.get("FI_BODYSMASH")), "damage with some hate on top is the gladiator's to use");
		assertFalse(BotSkillManager.isTaunt(byGroup.get("KN_ABYSALJUDGEMENT")));
	}

	@Test
	void aSkillThatLowersHateIsNotATaunt() {
		assertFalse(BotSkillManager.isTaunt(byGroup.get("AS_BREEZEOFCALM")));
		assertFalse(BotSkillManager.isTaunt(byGroup.get("WI_ABSENCESOUL")));
	}
}
