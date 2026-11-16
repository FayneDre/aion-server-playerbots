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
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the spirit master's playbook names, held against the game data. Reads the skill file directly and is skipped without it.
 * With no guide behind the playbook, the data is all it rests on, so the facts it reads are checked here one by one.
 */
class SpiritMasterPlaybookDataTest {

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
		for (String group : SpiritMasterPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (SpiritMasterPlaybook.Move move : SpiritMasterPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		for (String group : SpiritMasterPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : SpiritMasterPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> SpiritMasterPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	@Test
	void theFillersAndTheDamageChainsStayWithTheGenericOrder() {
		for (String group : List.of("MA_GRAVITYCAGE", "EL_VACUUMEXPLOSION", "EL_EARTHGRAB", "EL_STONESHOCK", "EL_DIMISSPOLYMORPH", "EL_ELEMENTALSTRIKE",
			"EL_DARKBURST", "EL_DISPELEXPLOSION", "EL_SOULSTEAL", "EL_COUNTHPMPDRAIN", "EL_QUICKHPMPDRAIN"))
			assertFalse(claimed(group), group + " is an attack the generic order casts and carries on");
		assertTrue(claimed("EL_SUMMON_EARTHELEMENTAL"), "the spirits are the playbook's");
		assertTrue(claimed("EL_ORDER_DECAYING"), "and the commands");
		assertTrue(claimed("EL_SOULKING"), "the banned are claimed");
	}

	@Test
	void theSpiritsAreSummonsAndTheGreatOnesLastTwentyMinutes() {
		for (SpiritMasterPlaybook.Move spirit : SpiritMasterPlaybook.LASTING_SPIRITS)
			assertTrue(first(spirit.group).hasAnyEffect(EffectType.SUMMON), spirit + " calls up a servant");
		assertTrue(first(SpiritMasterPlaybook.Move.TEMPEST_SPIRIT.group).hasAnyEffect(EffectType.SUMMON));
		assertTrue(first(SpiritMasterPlaybook.Move.LAVA_SPIRIT.group).hasAnyEffect(EffectType.SUMMON));
	}

	@Test
	void theCommandsQueueAnOrderOnTheSpirit() {
		for (SpiritMasterPlaybook.Move command : List.of(SpiritMasterPlaybook.Move.SPIRIT_EROSION, SpiritMasterPlaybook.Move.DETONATION_CLAW,
			SpiritMasterPlaybook.Move.WRATH_POSITION, SpiritMasterPlaybook.Move.SPIRIT_DISTURBANCE, SpiritMasterPlaybook.Move.BODYGUARD,
			SpiritMasterPlaybook.Move.KAMIKAZE, SpiritMasterPlaybook.Move.SPIRIT_PIQUE))
			assertTrue(first(command.group).hasAnyEffect(EffectType.PETORDERUSEULTRASKILL), command + " is an order the spirit carries out");
	}

	@Test
	void theSpiritsOwnSkillsAimAtTheSpirit() {
		for (SpiritMasterPlaybook.Move move : SpiritMasterPlaybook.Move.values()) {
			boolean atThePet = first(move.group).getProperties().getFirstTarget() == FirstTargetAttribute.MYPET;
			assertEquals(atThePet, move.aim == SpiritMasterPlaybook.Aim.SPIRIT, move + ": the aim must follow the data");
		}
	}

	@Test
	void theChainsThePlaybookWatchesAreTheOnesTheirSkillsOpen() {
		assertEquals(SpiritMasterPlaybook.DISTURBANCE_CHAIN, category("EL_PET_INTERUPT"), "Spirit Disturbance opens chain N");
		assertEquals(SpiritMasterPlaybook.DISTURBANCE_CHAIN, precategory("EL_ORDER_ASSAIL"), "Spirit Detonation Claw follows it");
		assertNull(first("EL_SYMPATHETICSWITCH").getChainCondition(), "Emnity Swap follows nothing: it is cast whenever the hate must move");
	}

	@Test
	void theFollowedChainsAreOpenedBySkillsTheGenericOrderCarriesOn() {
		for (String category : SpiritMasterPlaybook.FOLLOWED_CHAINS)
			assertTrue(templates.stream().anyMatch(template -> template.getChainCondition() != null && category.equals(template.getChainCondition().getCategory())),
				category + " is opened by no skill");
		assertEquals("MWE_CHAINE_1TH", precategory("EL_STONESHOCK"), "Stone Shock follows Chain of Earth");
		assertEquals("MWE_CHAINO_1TH", precategory("EL_ELEMENTALSTRIKE"), "Ritual Push follows Weaken Spirit");
	}

	@Test
	void theFearsAreFearsAndTheRootIsNot() {
		for (SpiritMasterPlaybook.Move fear : SpiritMasterPlaybook.FEARS)
			assertTrue(first(fear.group).hasAnyEffect(EffectType.FEAR), fear + " is a fear, which the cast rule keeps out of a group");
		assertTrue(first(SpiritMasterPlaybook.Move.FEAR_SHRIEK.group).hasAnyEffect(EffectType.FEAR));
		assertFalse(first(SpiritMasterPlaybook.Move.BODY_ROOT.group).hasAnyEffect(EffectType.FEAR), "Body Root is the group's control");
	}

	@Test
	void stoneScourIsCharged() {
		assertTrue(templates.stream().anyMatch(template -> SpiritMasterPlaybook.Move.STONE_SCOUR.group.equals(template.getGroup()) && template.isCharge()));
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
		return SpiritMasterPlaybook.claimsSkill(first(group));
	}

	private static boolean hasGroup(String group) {
		return templates.stream().anyMatch(template -> group.equals(template.getGroup()));
	}
}
