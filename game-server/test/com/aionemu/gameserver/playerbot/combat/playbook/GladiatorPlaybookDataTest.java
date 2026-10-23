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
 * Every skill group the gladiator's playbook names, held against the game data, so that a typo or a group renamed in an update fails here and not as a
 * rule that silently never fires. Reads the skill file directly and is skipped without it, like {@link TemplarPlaybookDataTest}.
 */
class GladiatorPlaybookDataTest {

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
		for (String group : GladiatorPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (GladiatorPlaybook.Move move : GladiatorPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
	}

	@Test
	void theStigmasTheGuideListsAreStigmasOfTheGladiator() {
		for (String group : GladiatorPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : GladiatorPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		assertEquals(3, GladiatorPlaybook.REGULAR_STIGMAS.size());
		assertEquals(3, GladiatorPlaybook.ADVANCED_STIGMAS.size());
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> GladiatorPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void theBansAndThePostureAreClaimedAndTheAttacksAreNot() {
		for (String group : GladiatorPlaybook.FORBIDDEN_GROUPS)
			assertTrue(claimed(group), group + " is forbidden and so claimed");
		assertTrue(claimed("FI_BLADEMODE"), "Slaughter is the playbook's");
		assertFalse(claimed("WA_ROBUSTHIT"), "Ferocious Strike, the filler, stays with the generic order");
		assertTrue(claimed("FI_WINGBLADE"), "Cleave is the opener from afar, the playbook's");
		assertFalse(claimed("FI_GUSTBLADE"), "Cleave's chain is carried on by the generic order");
		assertFalse(claimed("FI_CONSECUTIVEHIT"), "Energy Impact, chain E's link, is the generic order's");
		assertFalse(claimed("FI_TRIPLESLASH"), "Body Combo, chain B's link, is the generic order's");
		assertFalse(claimed("FI_ENERGYWING"), "Strengthen Wings is cast on take-off by the generic flight buff");
		assertFalse(claimed("MENDING"), "Herb Treatment is every class's and the guide does not name it");
		assertFalse(claimed("FI_SEISMICDRAIN"), "Absorbing Fury is a single target move too, and the generic order keeps it");
		assertFalse(claimed("FI_SEISMICBILLOW"), "chain C's third link is a plain continuation");
		for (String group : new String[] { "FI_DRAINWAVE", "FI_SEISMICIMPACT", "FI_CHARGINGSHOCK", "FI_STIGMA_SHOCKBURST", "FI_HEAVYIMPACT", "FI_DRAGONSLASH" })
			assertTrue(claimed(group), group + " is chosen by the playbook");
	}

	@Test
	void theChainsThePlaybookWatchesAreTheOnesTheirOpenersOpen() {
		// the playbook asks whether these categories are open; the data says which skill opens each
		assertEquals(GladiatorPlaybook.AREA_CHAIN, first("FI_SEISMICDRAIN").getChainCondition().getCategory(), "Absorbing Fury opens chain C");
		assertEquals(GladiatorPlaybook.SHOCK_CHAIN, first("ALL_SHOCKREFLECT").getChainCondition().getCategory(), "Remove Shock opens its chain");
	}

	@Test
	void everyFollowedChainHasALinkTheGenericOrderMayCast() {
		// a category in the list that no unclaimed skill continues would hold the playbook back for nothing
		for (String category : GladiatorPlaybook.FOLLOWED_CHAINS)
			assertTrue(templates.stream().anyMatch(template -> template.getChainCondition() != null && category.equals(precategory(template))
				&& !GladiatorPlaybook.claimsSkill(template)), category + " has no link the generic order may cast");
	}

	/** The chain category a template follows, which the condition keeps to itself. Read by reflection, as the data test it is. */
	private static String precategory(SkillTemplate template) {
		try {
			java.lang.reflect.Field field = template.getChainCondition().getClass().getDeclaredField("preCategory");
			field.setAccessible(true);
			return (String) field.get(template.getChainCondition());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void theTwoPosturesAreToggles() {
		assertTrue(first("FI_BLADEMODE").isToggle(), "Slaughter is a toggle, which is why it is cast only when it is off");
		assertTrue(first("FI_DEFENSEMODE").isToggle(), "Defense Preparation is a toggle");
	}

	@Test
	void rageIsALinkOfTheFillerChain() {
		// the reason it is banned: offered after Ferocious Strike, it would take the place of Robust Blow and end the filler there
		assertNotNull(first("WA_RAGE").getChainCondition(), "Rage has no chain condition any more; the reason for its ban is to be looked at again");
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).findFirst().orElseThrow();
	}

	private static boolean claimed(String group) {
		return GladiatorPlaybook.claimsSkill(first(group));
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
