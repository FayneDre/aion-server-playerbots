package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The templar's rules put through every skill the game has, not through the few the other tests make up.
 * <p>
 * Written because the one fault that reached a live server from this class was a fact about the data and not about the logic: most skill templates have no
 * group, and the rule asked a set about {@code null}, so it threw on every decision tick of every templar, four thousand times, while every test passed.
 * What a test can do about that is feed the code the same input the server does.
 * <p>
 * Reads {@code data/static_data/skills/skill_templates.xml} directly rather than starting the whole data layer, which takes a minute and needs the other
 * eighty files. The test is skipped when the file is not there, as it is in a checkout without the game data.
 */
class TemplarPlaybookDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");

	private static List<SkillTemplate> templates;

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			templates = new ArrayList<>();
			// the by-id index is what the engine keeps; the list the loader read is dropped once it is built
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null)
					templates.add(template);
			}
		}
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		assertTrue(templates.size() > 10000, "the game data was read: " + templates.size());
		for (SkillTemplate template : templates) {
			assertDoesNotThrow(() -> TemplarPlaybook.claimsSkill(true, template), "in a group, skill " + template.getSkillId());
			assertDoesNotThrow(() -> TemplarPlaybook.claimsSkill(false, template), "alone, skill " + template.getSkillId());
		}
	}

	@Test
	void theSkillsThePlaybookNamesExistInTheDataUnderThoseGroups() {
		for (TemplarPlaybook.Move move : TemplarPlaybook.Move.values()) {
			assertTrue(templates.stream().anyMatch(template -> move.group.equals(template.getGroup())),
				move + " names the group " + move.group + ", which no skill in the game has: a typo, or a group renamed in a data update");
		}
		for (String group : TemplarPlaybook.FORBIDDEN_GROUPS) {
			assertTrue(templates.stream().anyMatch(template -> group.equals(template.getGroup())), group + " is forbidden but no skill has it");
		}
		for (String group : new TemplarPlaybook().preferredStigmas(false)) {
			assertTrue(templates.stream().anyMatch(template -> group.equals(template.getGroup())), "stigma group " + group + " matches no skill");
		}
		for (String group : new TemplarPlaybook().preferredStigmas(true)) {
			assertTrue(templates.stream().anyMatch(template -> group.equals(template.getGroup())), "stigma group " + group + " matches no skill");
		}
	}

	@Test
	void theRoarIsClaimedOnlyInAGroupAndTheDefensivesAlways() {
		SkillTemplate roar = templates.stream().filter(template -> "KN_MASSIVEPROVOKE".equals(template.getGroup())).findFirst().orElseThrow();
		SkillTemplate hand = templates.stream().filter(template -> "KN_DIVINEHAND".equals(template.getGroup())).findFirst().orElseThrow();

		assertTrue(TemplarPlaybook.claimsSkill(true, roar));
		assertFalse(TemplarPlaybook.claimsSkill(false, roar), "alone, the generic order may still cast it");
		assertTrue(TemplarPlaybook.claimsSkill(true, hand));
		assertTrue(TemplarPlaybook.claimsSkill(false, hand));
	}
}
