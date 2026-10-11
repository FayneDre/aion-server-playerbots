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
 * The cleric's rules put through every skill the game has: most templates have no group, and a rule that asks a set about null throws on every tick of every bot.
 * Skipped when the game data is not in the checkout.
 */
class ClericPlaybookDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");

	private static List<SkillTemplate> templates;

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			templates = new ArrayList<>();
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
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> ClericPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void everyGroupThePlaybookNamesExistsInTheData() {
		for (ClericPlaybook.Move move : ClericPlaybook.Move.values())
			assertTrue(templates.stream().anyMatch(template -> move.group.equals(template.getGroup())), move + " names the group " + move.group + ", which no skill has");
		for (String group : ClericPlaybook.FORBIDDEN_GROUPS)
			assertTrue(templates.stream().anyMatch(template -> group.equals(template.getGroup())), group + " is forbidden but no skill has it");
		for (String group : new ClericPlaybook().preferredStigmas(false))
			assertTrue(templates.stream().anyMatch(template -> group.equals(template.getGroup())), "stigma group " + group + " matches no skill");
	}

	@Test
	void theHealsAreClaimedAndTheDamageOverTimeIsNot() {
		assertTrue(claimed("CL_HEAL"));
		assertTrue(claimed("PR_GREATHEAL"));
		assertTrue(claimed("PR_GODSVOICE"), "Word of Destruction is forbidden");
		assertTrue(claimed("PR_HOLYLIGHT"), "and so is the seven second channel");
		assertFalse(claimed("PR_PURGATORY"), "Earth's Wrath is cast first by the playbook and may be cast by the generic order too");
		assertFalse(claimed("PR_DIVINEBOLT"), "Slashing Wind stays with the generic order");
	}

	@Test
	void theStigmasTheGuideListsAreRegularOnesAndReverseConditionIsNotAmongThem() {
		List<String> stigmas = new ClericPlaybook().preferredStigmas(false);
		assertEquals(List.of("PR_RESTORELIFE", "PR_BINDINGHEAL", "PR_HEALINGGRACE"), stigmas);
		assertFalse(stigmas.contains("PR_REVERSECONDITION"));
		assertTrue(new ClericPlaybook().preferredStigmas(true).isEmpty());
	}

	private static boolean claimed(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).findFirst().map(ClericPlaybook::claimsSkill).orElseThrow();
	}
}
