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
import com.aionemu.gameserver.skillengine.condition.Condition;
import com.aionemu.gameserver.skillengine.condition.Conditions;
import com.aionemu.gameserver.skillengine.condition.WeaponCondition;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the gunner's playbook names, held against the game data, and the weapon each asks for. Reads the skill file directly and is
 * skipped without it.
 */
class GunnerPlaybookDataTest {

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
		for (String group : GunnerPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (GunnerPlaybook.Move move : GunnerPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		for (String group : GunnerPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : GunnerPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> GunnerPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	/** The module arms a gunner with pistols: nothing the playbook casts or wears may ask for a cannon alone, and every gunner skill that does is forbidden. */
	@Test
	void aPistolGunnerNeverAsksForACannon() {
		for (GunnerPlaybook.Move move : GunnerPlaybook.Move.values())
			assertFalse(cannonOnly(first(move.group)), move + " asks for a cannon");
		for (String group : GunnerPlaybook.REGULAR_STIGMAS)
			assertFalse(cannonOnly(first(group)), "stigma " + group + " asks for a cannon");
		for (String group : GunnerPlaybook.ADVANCED_STIGMAS)
			assertFalse(cannonOnly(first(group)), "stigma " + group + " asks for a cannon");
		for (SkillTemplate template : templates) {
			if (template.getGroup() != null && template.getGroup().startsWith("GU_") && cannonOnly(template))
				assertTrue(GunnerPlaybook.FORBIDDEN_GROUPS.contains(template.getGroup()), template.getGroup() + " asks for a cannon and is not forbidden");
		}
	}

	@Test
	void theOpenersShareTheCooldownReloadResets() {
		int cooldown = first(GunnerPlaybook.Move.GUNSHOT.group).getCooldownId();
		for (GunnerPlaybook.Move opener : GunnerPlaybook.OPENERS)
			assertEquals(cooldown, first(opener.group).getCooldownId(), opener + " shares the openers' cooldown");
	}

	@Test
	void theFollowedChainsAreOpenedByAGunnerSkill() {
		for (String category : GunnerPlaybook.FOLLOWED_CHAINS)
			assertTrue(templates.stream().anyMatch(template -> template.getChainCondition() != null && category.equals(template.getChainCondition().getCategory())),
				category + " is opened by no skill");
		for (GunnerPlaybook.Move opener : GunnerPlaybook.OPENERS)
			assertTrue(GunnerPlaybook.FOLLOWED_CHAINS.contains(first(opener.group).getChainCondition().getCategory()), opener + " opens a followed chain");
		assertEquals(GunnerPlaybook.SHOCK_CHAIN, first("ALL_SHOCKREFLECT").getChainCondition().getCategory());
	}

	@Test
	void theStableStanceIsAToggle() {
		assertTrue(first(GunnerPlaybook.Move.STABLE_STANCE.group).isToggle());
	}

	@Test
	void theLinksAndTheFillersStayWithTheGenericOrder() {
		for (String group : List.of("EN_ENFIRECHAIN", "GU_RAPIDENFIRE", "GU_HEAVYFIRECHAIN", "GU_CURBINGFIRECHAIN", "GU_WEAKSPOTSNIPE", "GU_BREAKINGFIRECHAIN",
			"GU_MENTALICRAPIDFIRE", "GU_MENTALICSHOT", "GU_SNIPINGFIRE", "GU_ONEPOINTRAPIDFIRE", "GU_DRAINATTACK"))
			assertFalse(GunnerPlaybook.claimsSkill(first(group)), group + " is the generic order's");
	}

	private static boolean cannonOnly(SkillTemplate template) {
		for (Conditions conditions : new Conditions[] { template.getStartconditions(), template.getUseconditions() }) {
			if (conditions == null)
				continue;
			for (Condition condition : conditions.getConditions()) {
				if (condition instanceof WeaponCondition weapon) {
					List<ItemGroup> groups = weaponsOf(weapon);
					return groups != null && groups.contains(ItemGroup.CANNON) && !groups.contains(ItemGroup.GUN);
				}
			}
		}
		return false;
	}

	/** Read through its field: the engine keeps the weapons a skill asks for to itself, and a getter added to upstream's class for a test alone is a merge to carry. */
	@SuppressWarnings("unchecked")
	private static List<ItemGroup> weaponsOf(WeaponCondition condition) {
		try {
			Field field = WeaponCondition.class.getDeclaredField("itemGroups");
			field.setAccessible(true);
			return (List<ItemGroup>) field.get(condition);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("WeaponCondition no longer has its itemGroups field", e);
		}
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).reduce((a, b) -> b).orElseThrow();
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
