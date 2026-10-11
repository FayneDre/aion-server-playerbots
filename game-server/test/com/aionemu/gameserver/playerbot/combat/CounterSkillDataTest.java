package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.controllers.attack.AttackStatus;
import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The skills that can only be used after a block, a dodge, a parry or a resist, read out of the game's data.
 * <p>
 * Written for Courageous Shield: it names two statuses, {@code BLOCK,RESIST}, which the engine's single status field never parsed, so it was unrestricted and a bot cast it all day.
 * Skipped when the game data is not in the checkout.
 */
class CounterSkillDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");
	private static final Map<String, SkillTemplate> byGroup = new HashMap<>();

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null && template.getGroup() != null)
					byGroup.putIfAbsent(template.getGroup(), template);
			}
		}
	}

	@Test
	void courageousShieldAnswersABlockOrAResist() {
		assertEquals(EnumSet.of(AttackStatus.BLOCK, AttackStatus.RESIST), byGroup.get("KN_COUNTEROFFENSIVE").getCounterSkills());
		assertNull(byGroup.get("KN_COUNTEROFFENSIVE").getCounterSkill(), "the single status the engine checks stays empty for a skill that names two");
	}

	@Test
	void theOtherTemplarAndGladiatorAnswersToo() {
		assertEquals(EnumSet.of(AttackStatus.BLOCK, AttackStatus.RESIST), byGroup.get("KN_AVENGINGBLADE").getCounterSkills());
		assertEquals(EnumSet.of(AttackStatus.BLOCK, AttackStatus.RESIST), byGroup.get("WA_AVENGINGCRASH").getCounterSkills());
	}

	@Test
	void aSkillWithOneStatusKeepsItsSingleStatus() {
		assertEquals(AttackStatus.PARRY, byGroup.get("CH_PARRYINGSTRIKE").getCounterSkill());
		assertEquals(EnumSet.of(AttackStatus.PARRY), byGroup.get("CH_PARRYINGSTRIKE").getCounterSkills());
	}

	@Test
	void anOrdinarySkillAnswersNothingAndIsNeverHeldBack() {
		assertTrue(byGroup.get("WA_PROVOKE").getCounterSkills().isEmpty());
		assertNull(byGroup.get("WA_PROVOKE").getCounterSkill());
	}
}
