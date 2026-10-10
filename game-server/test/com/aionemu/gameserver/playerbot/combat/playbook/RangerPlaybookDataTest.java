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
 * Every skill group the ranger's playbook names, held against the game data, so that a typo or a group renamed in an update fails here and not as a rule
 * that silently never fires. Skipped without the game data, like {@link TemplarPlaybookDataTest}.
 */
class RangerPlaybookDataTest {

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
	void everyGroupThePlaybookNamesExistsInTheData() {
		for (RangerPlaybook.Move move : RangerPlaybook.Move.values()) {
			if (move.group != null)
				assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		}
		for (String group : RangerPlaybook.NEVER_GROUPS)
			assertTrue(hasGroup(group), "never-cast group " + group + " matches no skill");
		for (String group : RangerPlaybook.NOT_IN_A_GROUP)
			assertTrue(hasGroup(group), "group-only ban " + group + " matches no skill");
		for (String group : new RangerPlaybook().preferredStigmas(false))
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : new RangerPlaybook().preferredStigmas(true))
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		assertTrue(templates.size() > 10000, "the game data was read: " + templates.size());
		for (SkillTemplate template : templates) {
			assertDoesNotThrow(() -> RangerPlaybook.claimsSkill(true, template), "in a group, skill " + template.getSkillId());
			assertDoesNotThrow(() -> RangerPlaybook.claimsSkill(false, template), "alone, skill " + template.getSkillId());
		}
	}

	@Test
	void theTransformationHasAStartConditionWhichIsTheDivinePower() {
		assertNotNull(first("RA_SABAGEROAR").getStartconditions(), "the Transformation asks for something to be cast: the divine power");
		assertNotNull(first("RA_L_SABAGEROAR").getStartconditions());
	}

	@Test
	void theBuffsThePlaybookCountsAsOneMinuteBuffsAreBuffs() {
		for (RangerPlaybook.Move move : RangerPlaybook.ONE_MINUTE_ORDER)
			assertEquals(com.aionemu.gameserver.skillengine.model.SkillSubType.BUFF, first(move.group).getSubType(), move + " is not a buff");
	}

	@Test
	void theClaimsMatchWhatTheGuideBansAndWhatItTimes() {
		assertTrue(claimed(true, "RA_BACKDASHSTAB"));
		assertTrue(claimed(false, "RA_BACKDASHSTAB"), "the dash is never cast, alone either");
		assertTrue(claimed(false, "RA_SPIKETRAP"), "no trap is ever cast");
		assertTrue(claimed(false, "RA_STIGMA_TIGEREYE"));
		assertTrue(claimed(false, "RA_BRAINSHOT"));
		assertTrue(claimed(true, "RA_DRAINSHOT"));
		assertFalse(claimed(false, "RA_DRAINSHOT"), "the end of chain B is allowed alone");
		assertTrue(claimed(true, "RA_SPOUTARROW"));
		assertFalse(claimed(false, "RA_SPOUTARROW"), "Arrow Deluge is allowed alone");
		assertTrue(claimed(false, "SC_HIDE"), "Stealth is not for a fight");
		assertTrue(claimed(false, "RA_PARALYZEARROW"));
		assertFalse(claimed(false, "RA_ARROWSTORM"), "Arrow Storm is the guide's own point of watch and is allowed");
		assertFalse(claimed(true, "RA_FINALFIRE"), "Finishing Arrow is an attack the generic order may cast; the playbook adds its moment");
	}

	private static boolean claimed(boolean inTeam, String group) {
		return RangerPlaybook.claimsSkill(inTeam, first(group));
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).findFirst().orElseThrow();
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
