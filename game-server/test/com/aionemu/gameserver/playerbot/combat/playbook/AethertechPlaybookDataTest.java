package com.aionemu.gameserver.playerbot.combat.playbook;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.condition.Condition;
import com.aionemu.gameserver.skillengine.condition.Conditions;
import com.aionemu.gameserver.skillengine.condition.DpCondition;
import com.aionemu.gameserver.skillengine.condition.RideRobotCondition;
import com.aionemu.gameserver.skillengine.condition.WeaponCondition;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the aethertech's playbook names, held against the game data, with the mech each move needs and the gun the forbidden ones
 * ask for. Reads the skill file directly and is skipped without it.
 */
class AethertechPlaybookDataTest {

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
		for (String group : AethertechPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (AethertechPlaybook.Move move : AethertechPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		for (String group : AethertechPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : AethertechPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> AethertechPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	/** Every move but Remove Shock, which every class has, is cast from the mech: the module boards it before anything in a fight. */
	@Test
	void everyMoveButRemoveShockNeedsTheMech() {
		for (AethertechPlaybook.Move move : AethertechPlaybook.Move.values()) {
			if (move != AethertechPlaybook.Move.REMOVE_SHOCK)
				assertTrue(needsTheMech(first(move.group)), move + " is cast outside the mech");
		}
	}

	/** It holds a key: every engineer skill the playbook forbids asks for a gun, and nothing it casts or wears does. */
	@Test
	void theForbiddenEngineerSkillsAskForAGun() {
		for (String group : AethertechPlaybook.FORBIDDEN_GROUPS) {
			if (!group.startsWith("RI_"))
				assertTrue(asksForAGun(first(group)), group + " asks for no gun");
		}
		for (AethertechPlaybook.Move move : AethertechPlaybook.Move.values())
			assertFalse(asksForAGun(first(move.group)), move + " asks for a gun");
	}

	/** Every category an aethertech link follows or repeats is one the playbook waits on, so it never closes a chain the generic order carries on. */
	@Test
	void everyChainTheGenericOrderCarriesOnIsFollowed() {
		for (SkillTemplate template : templates) {
			String group = template.getGroup();
			ChainCondition chain = template.getChainCondition();
			if (group == null || !group.startsWith("RI_") || chain == null || AethertechPlaybook.FORBIDDEN_GROUPS.contains(group))
				continue;
			String follows = preCategoryOf(chain);
			if (follows != null && !follows.equals(AethertechPlaybook.SHOCK_CHAIN))
				assertTrue(AethertechPlaybook.FOLLOWED_CHAINS.contains(follows), group + " follows " + follows + ", which the playbook does not wait on");
			if (chain.getAllowedActivations() > 1)
				assertTrue(AethertechPlaybook.FOLLOWED_CHAINS.contains(chain.getCategory()), group + " repeats " + chain.getCategory() + " unwaited");
		}
		for (String category : AethertechPlaybook.FOLLOWED_CHAINS)
			assertTrue(templates.stream().anyMatch(t -> t.getChainCondition() != null && category.equals(t.getChainCondition().getCategory())),
				category + " is opened by no skill");
		assertEquals(AethertechPlaybook.SHOCK_CHAIN, first("ALL_SHOCKREFLECT").getChainCondition().getCategory());
		for (AethertechPlaybook.Move link : List.of(AethertechPlaybook.Move.UPPERCUT, AethertechPlaybook.Move.PROTECTIVE_SHELL))
			assertEquals(AethertechPlaybook.SHOCK_CHAIN, preCategoryOf(first(link.group).getChainCondition()), link + " follows Remove Shock");
	}

	@Test
	void theTogglesAreToggles() {
		for (AethertechPlaybook.Move move : List.of(AethertechPlaybook.Move.KINETIC_BATTERY, AethertechPlaybook.Move.STABILITY_THRUSTERS,
			AethertechPlaybook.Move.KINETIC_BULWARK, AethertechPlaybook.Move.MOUNTING_FRUSTRATION))
			assertTrue(first(move.group).isToggle(), move + " is a toggle");
	}

	/** Read on 18 November: the two mana shields are of two stacks, so both may be up at once. */
	@Test
	void theTwoManaShieldsStack() {
		assertNotEquals(first(AethertechPlaybook.Move.KINETIC_BATTERY.group).getStack(), first(AethertechPlaybook.Move.KINETIC_BULWARK.group).getStack());
	}

	@Test
	void theDivinePowerMovesCostWhatThePlaybookSays() {
		assertEquals(AethertechPlaybook.OVERDRIVE_DP, dpOf(first(AethertechPlaybook.Move.OVERDRIVE_TRIGGER.group)));
		assertEquals(AethertechPlaybook.OVERDRIVE_DP, dpOf(first(AethertechPlaybook.Move.POWER_FLUX.group)));
		assertEquals(AethertechPlaybook.BARRAGE_DP, dpOf(first(AethertechPlaybook.Move.ANNIHILATION_BARRAGE.group)));
	}

	@Test
	void theChainsStayWithTheGenericOrder() {
		for (String group : List.of("RI_RIGHTJAB", "RI_LEFTJAB", "RI_POWERJAB", "RI_SHOCKATTACK", "RI_EXPLODINGPUNCH", "RI_ANGRYPUNCH", "RI_AIMINGATTACK",
			"RI_WEAKENINGATTACK", "RI_RAPIDATTACK", "RI_DRAINJAB", "RI_CHAINDRAIN", "RI_DRAININGNET", "RI_DIRECTFIRE", "RI_CHAINFIRE", "RI_FORWARDATTACK",
			"RI_FORWARDSTRIKE", "RI_BINDINGATTACK", "RI_CHARGEDPUNCH", "RI_EATHIUMBEAM", "RI_EATHIUMBLAST", "RI_POWERSLAM", "RI_SUMMONARMOR"))
			assertFalse(AethertechPlaybook.claimsSkill(first(group)), group + " is the generic order's");
	}

	private static int dpOf(SkillTemplate template) {
		for (Condition condition : template.getStartconditions().getConditions()) {
			if (condition instanceof DpCondition dp)
				return AethertechPlaybookDataTest.<Integer> read(DpCondition.class, "value", dp);
		}
		return 0;
	}

	private static boolean needsTheMech(SkillTemplate template) {
		Conditions use = template.getUseconditions();
		return use != null && use.getConditions().stream().anyMatch(condition -> condition instanceof RideRobotCondition);
	}

	private static boolean asksForAGun(SkillTemplate template) {
		for (Conditions conditions : new Conditions[] { template.getStartconditions(), template.getUseconditions() }) {
			if (conditions == null)
				continue;
			for (Condition condition : conditions.getConditions()) {
				if (condition instanceof WeaponCondition weapon) {
					List<ItemGroup> groups = read(WeaponCondition.class, "itemGroups", weapon);
					return groups != null && groups.contains(ItemGroup.GUN) && !groups.contains(ItemGroup.KEYBLADE);
				}
			}
		}
		return false;
	}

	private static String preCategoryOf(ChainCondition chain) {
		return read(ChainCondition.class, "preCategory", chain);
	}

	/** Read through its field: the engine keeps these to itself, and a getter added to upstream's class for a test alone is a merge to carry. */
	@SuppressWarnings("unchecked")
	private static <T> T read(Class<?> type, String name, Object instance) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(type.getSimpleName() + " no longer has its " + name + " field", e);
		}
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).reduce((a, b) -> b).orElseThrow();
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
