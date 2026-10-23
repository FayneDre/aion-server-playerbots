package com.aionemu.gameserver.playerbot.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.SkillData;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Which skills {@link BotSkillManager#isFlightOnly} keeps for the air, held against the game data: the flight buffs of every class and nothing a bot needs
 * on the ground. Reads the skill file directly and is skipped without it.
 */
class FlightBuffDataTest {

	private static final File SKILLS = new File("data/static_data/skills/skill_templates.xml");
	/** The class skills that do something for flight and nothing else a body on the ground would miss, as the data stood when the rule was written. */
	private static final Set<String> FLIGHT_BUFFS = Set.of("BA_SONGOFEAGLE", "CH_CHANT_IMPROVEDFLY", "FI_ENERGYWING", "PR_CIRCLEOFWINGS", "RA_RESTOREWING",
		"RI_REPLENISHBLAST");

	private static final Set<String> flightOnlyGroups = new TreeSet<>();

	@BeforeAll
	static void loadTheSkills() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(SKILLS.isFile(), "no game data in this checkout");
		try (Reader reader = Files.newBufferedReader(SKILLS.toPath(), StandardCharsets.UTF_8)) {
			SkillData data = JAXBUtil.deserialize(reader, SkillData.class);
			for (int id = 0; id < 40000; id++) {
				SkillTemplate template = data.getSkillTemplate(id);
				if (template != null && template.getGroup() != null && !template.isPassive() && BotSkillManager.isFlightOnly(template))
					flightOnlyGroups.add(template.getGroup());
			}
		}
	}

	@Test
	void everyClassFlightBuffIsKeptForTheAir() {
		for (String group : FLIGHT_BUFFS)
			assertTrue(flightOnlyGroups.contains(group), group + " is not recognised as working in flight only");
	}

	@Test
	void noOtherClassSkillIsKeptForTheAir() {
		Set<String> classSkills = new TreeSet<>();
		for (String group : flightOnlyGroups) {
			if (group.matches("(AS|BA|CH|EL|FI|GU|KN|MA|PR|RA|RI|WA|WI|CL|SC)_.*"))
				classSkills.add(group);
		}
		assertEquals(new TreeSet<>(FLIGHT_BUFFS), classSkills, "class skills recognised as working in flight only");
	}
}
