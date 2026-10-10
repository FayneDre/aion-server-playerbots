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
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group the chanter's playbook names, held against the game data, so that a typo or a group renamed in an update fails here and not as a rule
 * that silently never fires. Reads the skill file directly and is skipped without it, like {@link TemplarPlaybookDataTest}.
 */
class ChanterPlaybookDataTest {

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
		for (String group : ChanterPlaybook.MANTRA_GROUPS)
			assertTrue(hasGroup(group), "mantra group " + group + " matches no skill");
		for (String group : ChanterPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (String group : ChanterPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : ChanterPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everyMoveTheChanterNamesExistsInTheData() {
		for (ChanterPlaybook.Move move : ChanterPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> ChanterPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void theBuffsTheChanterTimesAreClaimedAndItsPlainAttacksAreNot() {
		assertTrue(claimed("CH_PROTECTSELF"));
		assertTrue(claimed("CH_RENEWEDVITALITY"));
		assertTrue(claimed("CH_IMPROVEDPOWERATTACK"));
		assertTrue(claimed("CL_HEAL"), "Healing Light is the playbook's: none in a group");
		assertTrue(claimed("CH_REJUVENATION"));
		assertTrue(claimed("CH_STIGMA_RECOVERWORD"));
		assertTrue(claimed("CH_CHAKRA"));
		assertTrue(claimed("CL_REVIVE"));
		assertTrue(claimed("ALL_SHOCKREFLECT"));
		assertFalse(claimed("CH_SHOCKATTACK"), "Backshock is an attack the generic order may cast; the playbook adds its moment");
		assertTrue(claimed("CH_ABYSSGLORIA"));
		assertTrue(claimed("CH_PROVIDENCE"));
		for (String group : ChanterPlaybook.FORBIDDEN_GROUPS)
			assertTrue(claimed(group), group + " is forbidden and so claimed");
		assertFalse(claimed("MENDING"), "Herb Treatment is every class's and the guide does not name it");
		assertFalse(claimed("CH_PARRYINGSTRIKE"), "the generic order may keep casting the strike");
		assertFalse(claimed("CH_BLESSOFROCK"), "the group buff stays with the generic order");
		assertFalse(claimed("CH_VIOLENTSWING"));
	}

	@Test
	void everyMantraIsAToggleThatTheMantraRuleCanTurnOn() {
		for (String group : ChanterPlaybook.MANTRA_GROUPS) {
			SkillTemplate template = templates.stream().filter(candidate -> group.equals(candidate.getGroup())).findFirst().orElseThrow();
			assertTrue(template.isToggle(), group + " is not a toggle");
		}
	}

	@Test
	void theRealMantrasAreSortedByTheGuidesOrderAndTheirRanksKeepTheirOrder() {
		List<SkillTemplate> mantras = templates.stream().filter(template -> template.getGroup() != null && ChanterPlaybook.MANTRA_GROUPS.contains(template.getGroup())).toList();
		assertFalse(mantras.isEmpty());
		// and the sort must survive what the server really feeds it: every template in the game, most of them with no group
		for (boolean inTeam : new boolean[] { false, true })
			assertEquals(templates.size(), BotSkillManager.byMantraOrder(templates, new ChanterPlaybook().mantraOrder(inTeam)).size());
		for (boolean inTeam : new boolean[] { false, true }) {
			List<String> order = new ChanterPlaybook().mantraOrder(inTeam);
			List<SkillTemplate> sorted = BotSkillManager.byMantraOrder(mantras, order);
			assertEquals(mantras.size(), sorted.size());
			int previous = -1;
			for (SkillTemplate template : sorted) {
				int rank = order.indexOf(template.getGroup());
				assertTrue(rank >= previous, template.getGroup() + " came after a mantra the guide ranks below it");
				previous = rank;
			}
		}
		assertSame(mantras, BotSkillManager.byMantraOrder(mantras, List.of()), "no order given: left as it came");
	}

	private static boolean claimed(String group) {
		return ChanterPlaybook.claimsSkill(templates.stream().filter(template -> group.equals(template.getGroup())).findFirst().orElseThrow());
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
