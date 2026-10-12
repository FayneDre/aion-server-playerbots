package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The order a playbook's skill group is offered in, read out of the game's data.
 * <p>
 * Written for Healing Light: a level 63 cleric cast rank 1 (110 points) instead of rank 13 (1346), because every rank is its own id in the skill list and
 * the group was handed over lowest id first. Skipped when the game data is not in the checkout.
 */
class SkillRankDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");
	private static final Map<String, List<SkillTemplate>> byGroup = new HashMap<>();

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null && template.getGroup() != null)
					byGroup.computeIfAbsent(template.getGroup(), group -> new ArrayList<>()).add(template);
			}
		}
	}

	@Test
	void healingLightIsOfferedAtItsTopRank() {
		List<SkillTemplate> ranks = new ArrayList<>(byGroup.get("CL_HEAL"));
		ranks.sort(BotSkillManager.HIGHEST_RANK_FIRST);
		assertEquals(1850, ranks.getFirst().getSkillId());
		assertEquals(13, ranks.getFirst().getLvl());
		assertEquals(1838, ranks.getLast().getSkillId());
	}

	@Test
	void theFirstRankOfferedIsNeverWeakerThanAnother() {
		List<String> weaker = new ArrayList<>();
		for (Map.Entry<String, List<SkillTemplate>> group : byGroup.entrySet()) {
			List<SkillTemplate> ranks = new ArrayList<>(group.getValue());
			if (ranks.size() < 2)
				continue;
			ranks.sort(BotSkillManager.HIGHEST_RANK_FIRST);
			int first = BotSkillManager.statedPower(ranks.getFirst());
			for (SkillTemplate rank : ranks) {
				if (BotSkillManager.statedPower(rank) > first)
					weaker.add(group.getKey() + " " + ranks.getFirst().getSkillId() + " < " + rank.getSkillId());
			}
		}
		// the stronger one is in neither case in the skill tree, so no bot holds it: Defiant Barrier (327) and Dispel Magic I (307)
		weaker.removeAll(List.of("IDSWEEP_SKILL 338 < 327", "EL_DISPEL 307 < 3570"));
		assertTrue(weaker.isEmpty(), "groups whose first offered rank is not the strongest: " + weaker);
	}
}
