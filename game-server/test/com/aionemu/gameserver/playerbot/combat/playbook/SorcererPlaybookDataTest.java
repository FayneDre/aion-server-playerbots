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
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the sorcerer's playbook names, held against the game data. Reads the skill file directly and is skipped without it.
 */
class SorcererPlaybookDataTest {

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
		for (String group : SorcererPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (SorcererPlaybook.Move move : SorcererPlaybook.Move.values()) {
			if (move.group != null)
				assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		}
		for (String group : SorcererPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : SorcererPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> SorcererPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void theBansAreClaimedAndTheFillerIsNot() {
		for (String group : SorcererPlaybook.FORBIDDEN_GROUPS)
			assertTrue(claimed(group), group + " is forbidden and so claimed");
		assertFalse(claimed("MA_FLAMEBOLT"), "Flame Bolt, the filler, stays with the generic order");
		assertFalse(claimed("WI_BURNINGSOUL"), "Flame Harpoon opens the fire chain for the generic order too");
		assertFalse(claimed("MA_IGNICBURN"), "Blaze, the fire chain's second link, is the generic order's");
		assertFalse(claimed("WI_CHAINBURN"), "Ember, its third, is the generic order's");
		assertTrue(claimed("MA_STONESKIN"));
		assertTrue(claimed("WI_ABYSALSTALKER"), "the Lumiel pair, against players only, is the playbook's");
	}

	@Test
	void theChainsThePlaybookWatchesAreTheOnesTheirSkillsOpen() {
		assertEquals(SorcererPlaybook.ICE_CHAIN, category("MA_ICEGRAB"), "Ice Chain opens chain B");
		assertEquals(SorcererPlaybook.SHARD_CHAIN, category("WI_MAGICALCHAIN"), "Refracting Shard opens chain F");
		assertEquals(SorcererPlaybook.SPEAR_CHAIN, category("WI_WINDSPEAR"), "Wind Spear is its own chain");
		assertEquals(SorcererPlaybook.METEORITE_CHAIN, category("WI_RAISINGSTORM"), "Meteorite is its own chain");
		assertEquals(SorcererPlaybook.SHOCK_CHAIN, category("ALL_SHOCKREFLECT"));
		assertEquals(SorcererPlaybook.FOLLOWED_CHAINS.get(0), category("WI_BURNINGSOUL"), "Flame Harpoon opens the fire chain");
		assertEquals(SorcererPlaybook.FOLLOWED_CHAINS.get(1), category("MA_IGNICBURN"), "Blaze carries it on");
	}

	@Test
	void theMulticastsAreMulticasts() {
		assertTrue(first("WI_WINDSPEAR").isMultiCast());
		assertTrue(first("WI_RAISINGSTORM").isMultiCast());
	}

	@Test
	void theGroundSpellsAreCastOnAPoint() {
		assertEquals(FirstTargetAttribute.POINT, first("WI_METEOR").getProperties().getFirstTarget(), "Flaming Meteor is laid on the ground");
		assertEquals(FirstTargetAttribute.POINT, first(SorcererPlaybook.GATE_GROUP).getProperties().getFirstTarget(), "so is Summon Rift");
	}

	private static String category(String group) {
		return first(group).getChainCondition().getCategory();
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).reduce((a, b) -> b).orElseThrow();
	}

	private static boolean claimed(String group) {
		return SorcererPlaybook.claimsSkill(first(group));
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
