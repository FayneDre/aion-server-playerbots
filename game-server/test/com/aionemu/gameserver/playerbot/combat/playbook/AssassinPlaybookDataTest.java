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
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.condition.Condition;
import com.aionemu.gameserver.skillengine.condition.DpCondition;
import com.aionemu.gameserver.skillengine.effect.CarveSignetEffect;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.SignetBurstEffect;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every skill group and chain the assassin's playbook names, held against the game data, with each carver's cap and each burst's rune. Reads the skill
 * file directly and is skipped without it.
 */
class AssassinPlaybookDataTest {

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
		for (String group : AssassinPlaybook.FORBIDDEN_GROUPS)
			assertTrue(hasGroup(group), "forbidden group " + group + " matches no skill");
		for (AssassinPlaybook.Move move : AssassinPlaybook.Move.values())
			assertTrue(hasGroup(move.group), move + " names the group " + move.group + ", which no skill in the game has");
		for (String group : AssassinPlaybook.REGULAR_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
		for (String group : AssassinPlaybook.ADVANCED_STIGMAS)
			assertTrue(hasGroup(group), "stigma group " + group + " matches no skill");
	}

	@Test
	void everySkillTemplateCanBeAskedAbout() {
		for (SkillTemplate template : templates)
			assertDoesNotThrow(() -> AssassinPlaybook.claimsSkill(template), "skill " + template.getSkillId());
	}

	/** Every rank of every carver the playbook ranks reaches the cap it is ranked by. */
	@Test
	void theCarversReachTheirCaps() {
		for (AssassinPlaybook.Move move : AssassinPlaybook.FULL_CARVERS)
			assertEquals(AssassinPlaybook.RUNE_FULL, capOf(first(move.group)), move + " carves to five");
		for (AssassinPlaybook.Move move : AssassinPlaybook.LOW_CARVERS)
			assertEquals(AssassinPlaybook.LOW_CAP, capOf(first(move.group)), move + " carves to three");
	}

	@Test
	void everyBurstSpendsTheRune() {
		for (AssassinPlaybook.Move move : AssassinPlaybook.BURSTS)
			assertNotNull(effect(first(move.group), SignetBurstEffect.class), move + " bursts no rune");
	}

	/** Every rune an assassin carves or bursts is the one {@code BotSkillManager.runeLevel} reads. */
	@Test
	void theClassHasOneRune() {
		for (SkillTemplate template : templates) {
			String group = template.getGroup();
			if (group == null || !group.startsWith("AS_"))
				continue;
			CarveSignetEffect carve = effect(template, CarveSignetEffect.class);
			if (carve != null)
				assertEquals(BotSkillManager.RUNE_STACK, read(CarveSignetEffect.class, "signet", carve), group + " carves another rune");
			SignetBurstEffect burst = effect(template, SignetBurstEffect.class);
			if (burst != null)
				assertEquals(BotSkillManager.RUNE_STACK, burst.getSignet(), group + " bursts another rune");
		}
	}

	/** Every category an assassin link follows or repeats is one the playbook waits on, so it never closes a chain the generic order carries on. */
	@Test
	void everyChainTheGenericOrderCarriesOnIsFollowed() {
		for (SkillTemplate template : templates) {
			String group = template.getGroup();
			ChainCondition chain = template.getChainCondition();
			if (group == null || !(group.startsWith("AS_") || group.startsWith("SC_")) || chain == null)
				continue;
			String follows = read(ChainCondition.class, "preCategory", chain);
			if (follows != null && !follows.equals(AssassinPlaybook.SHOCK_CHAIN) && follows.startsWith("SRA_"))
				assertTrue(AssassinPlaybook.FOLLOWED_CHAINS.contains(follows), group + " follows " + follows + ", which the playbook does not wait on");
			if (chain.getAllowedActivations() > 1)
				assertTrue(AssassinPlaybook.FOLLOWED_CHAINS.contains(chain.getCategory()), group + " repeats " + chain.getCategory() + " unwaited");
		}
		assertEquals(AssassinPlaybook.SHOCK_CHAIN, first("ALL_SHOCKREFLECT").getChainCondition().getCategory());
		for (AssassinPlaybook.Move link : List.of(AssassinPlaybook.Move.SHADOW_ILLUSION, AssassinPlaybook.Move.CYCLONE_SLASH))
			assertEquals(AssassinPlaybook.SHOCK_CHAIN, read(ChainCondition.class, "preCategory", first(link.group).getChainCondition()), link + " follows Remove Shock");
	}

	@Test
	void theDivinePowerMovesCostWhatThePlaybookSays() {
		assertEquals(AssassinPlaybook.STRIKE_DP, dpOf(first(AssassinPlaybook.Move.DIVINE_STRIKE.group)));
		for (AssassinPlaybook.Move move : List.of(AssassinPlaybook.Move.TRINIELS_DIRK, AssassinPlaybook.Move.VAIZELS_DIRK, AssassinPlaybook.Move.SPIRAL_SLASH))
			assertEquals(AssassinPlaybook.DIRK_DP, dpOf(first(move.group)), move.toString());
	}

	@Test
	void theChainsStayWithTheGenericOrder() {
		for (String group : List.of("SC_SWIFTEDGE", "SC_SWIFTSHOCK", "AS_SIGNETASSAULT", "AS_FLASHSLASH", "SC_ASSAULTSTABBER", "AS_BACKSLASH",
			"AS_BACKSTRIKE", "SC_COUNTERSLASH", "AS_FLOWINGSPINER", "AS_STRAIGHTCOUNTER", "AS_CORRUPTINGCUT", "AS_POLLUTIONCUT", "AS_TIGERKICK", "AS_TIGERUPPER",
			"AS_DASHATTACK", "AS_BLINDSTRIKE", "AS_CARVESIGNETCHAIN", "AS_CHAINSIGNETBURST", "AS_STORMSLASH", "AS_BLINDSIDE", "SC_HIDE",
			"AS_STIGMA_SHADOWWALK"))
			assertFalse(AssassinPlaybook.claimsSkill(first(group)), group + " is the generic order's");
	}

	private static int capOf(SkillTemplate template) {
		CarveSignetEffect carve = effect(template, CarveSignetEffect.class);
		assertNotNull(carve, template.getGroup() + " carves no rune");
		return read(CarveSignetEffect.class, "signetCap", carve);
	}

	private static <E extends EffectTemplate> E effect(SkillTemplate template, Class<E> type) {
		if (template.getEffects() == null)
			return null;
		for (EffectTemplate effect : template.getEffects().getEffects()) {
			if (type.isInstance(effect))
				return type.cast(effect);
		}
		return null;
	}

	private static int dpOf(SkillTemplate template) {
		for (Condition condition : template.getStartconditions().getConditions()) {
			if (condition instanceof DpCondition dp)
				return AssassinPlaybookDataTest.<Integer> read(DpCondition.class, "value", dp);
		}
		return 0;
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
