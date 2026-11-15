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
import com.aionemu.gameserver.playerbot.combat.CleanseReach;
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the bard's playbook names, held against the game data. Reads the skill file directly and is skipped without it. With no
 * guide behind the playbook, the data is all it rests on, so the facts it reads are checked here one by one.
 */
class BardPlaybookDataTest {

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
		for (String group : BardPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (BardPlaybook.Move move : BardPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		for (String group : BardPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : BardPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> BardPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void theDamageChainsStayWithTheGenericOrder() {
		for (String group : List.of("AR_SONGOFCOLD", "AR_SONGOFFIRE", "BA_SONGOFWIND", "BA_SONGOFLAND", "BA_SONGOFSHOCK", "BA_SONGOFMINDDAMAGE",
			"BA_SONGOFMENTALIC", "BA_SONGOFDELAYDAMAGE", "BA_SONGOFINJURY", "BA_SONGOFTIDALWAVE", "AR_SONGOFAIR"))
			assertFalse(claimed(group), group + " is a damage song the generic order casts and carries on");
		assertFalse(claimed("BA_SONGOFSOULEXTINCTION"), "Quaver is cast on a caster by the playbook and as an attack by the generic order");
		assertTrue(claimed("AR_SONGOFHEAL"), "every heal is the playbook's");
		assertTrue(claimed("BA_PLAYINGSTYLESCHANGEA"), "and the stances");
		assertTrue(claimed("BA_EXCITINGMELODY"), "the banned are claimed");
	}

	@Test
	void theChainsThePlaybookWatchesAreTheOnesTheirSkillsOpen() {
		assertEquals(BardPlaybook.HEAL_CHAIN_1, category("AR_SONGOFHEAL"), "Soothing Melody opens chain B");
		assertEquals(BardPlaybook.HEAL_CHAIN_1, precategory("BA_SONGOFEMERGENTHEAL"), "Soothing Counterpoint follows it");
		assertEquals(BardPlaybook.HEAL_CHAIN_2, category("BA_SONGOFEMERGENTHEAL"));
		assertEquals(BardPlaybook.HEAL_CHAIN_2, precategory("BA_SONGOFGREATHEAL"), "Soothing Finale ends it");
		assertEquals(BardPlaybook.HEAL_CHAIN_2, precategory("BA_SONGOFWARMTH"), "and Joyous Carol, the other branch");
		assertEquals(BardPlaybook.MANA_CHAIN, category("AR_SONGOFRELAX"), "Resonating Melody opens chain F");
		assertEquals(BardPlaybook.MANA_CHAIN, precategory("BA_SONGOFSERENITY"), "Resonant Hymn follows it");
		assertEquals(BardPlaybook.SHOCK_CHAIN, category("ALL_SHOCKREFLECT"));
		assertEquals(BardPlaybook.SHOCK_CHAIN, precategory("BA_SONGOFAPPRECIATION"), "Hymn of Thanksgiving follows Remove Shock");
		assertEquals(BardPlaybook.SHOCK_CHAIN, precategory("BA_SONGOFRESISTANCE"), "and Chorus of Fortitude");
	}

	@Test
	void everyChainTheStanceWaitsForIsOneABardOpens() {
		for (String category : BardPlaybook.OPEN_CHAINS)
			assertTrue(templates.stream().anyMatch(template -> template.getChainCondition() != null && category.equals(template.getChainCondition().getCategory())),
				category + " is opened by no skill");
	}

	/**
	 * The skill a bard learns is the charged one; its stages, one per length of charge, are plain casts of the same group that the engine releases into. So
	 * the test asks whether the group has a charged skill, not whether its last template is one.
	 */
	@Test
	void theChargedSongsAreCharged() {
		for (BardPlaybook.Move move : BardPlaybook.CHARGED_ORDER)
			assertTrue(hasChargedSkill(move.group), move + " is meant to be a charged song");
		assertTrue(hasChargedSkill(BardPlaybook.Move.SOOTHING_HYMN.group), "Soothing Hymn is charged, and never begun under attack");
		assertFalse(hasChargedSkill(BardPlaybook.Move.SOOTHING_MELODY.group), "Soothing Melody is a plain cast");
	}

	private static boolean hasChargedSkill(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()) && template.isCharge());
	}

	@Test
	void theStancesAreTogglesThatShareOneCooldown() {
		SkillTemplate exultation = first(BardPlaybook.Move.EXULTATION.group), impassion = first(BardPlaybook.Move.IMPASSION.group);
		assertTrue(exultation.isToggle());
		assertTrue(impassion.isToggle());
		assertEquals(exultation.getCooldownId(), impassion.getCooldownId(), "switching one stance cools both down");
	}

	@Test
	void theBardsCleansesReachPhysicalWeakeningOnly() {
		assertEquals(CleanseReach.PHYSICAL, CleanseReach.of(first(BardPlaybook.Move.PURIFYING_PAEAN.group)));
		assertEquals(CleanseReach.PHYSICAL, CleanseReach.of(first(BardPlaybook.Move.PURGING_PAEAN.group)));
		assertEquals(CleanseReach.ALL, CleanseReach.of(first(BardPlaybook.Move.CHORUS_OF_BLESSING.group)), "the group heal's dispel is a general one");
		assertEquals(CleanseReach.ALL, CleanseReach.of(first("PR_PURIFY")), "a cleric's Cleanse");
		assertEquals(CleanseReach.ALL, CleanseReach.of(first("PR_MASSDISPEL")), "both kinds at once count as all");
		assertNull(CleanseReach.of(first("AR_SONGOFHEAL")), "a heal cleanses nothing");
	}

	private static String category(String group) {
		return first(group).getChainCondition().getCategory();
	}

	/** Read through its field: the engine keeps the link a chain follows to itself, and a getter added to upstream's class for a test alone is a merge to carry. */
	private static String precategory(String group) {
		try {
			Field field = ChainCondition.class.getDeclaredField("preCategory");
			field.setAccessible(true);
			return (String) field.get(first(group).getChainCondition());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("ChainCondition no longer has its preCategory field", e);
		}
	}

	private static SkillTemplate first(String group) {
		return templates.stream().filter(template -> group.equals(template.getGroup())).reduce((a, b) -> b).orElseThrow();
	}

	private static boolean claimed(String group) {
		return BardPlaybook.claimsSkill(first(group));
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
