package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading obelisks out of the spawn files, which is the part of the audit that depends on a file format and not on a mesh. */
class ObeliskAuditTest {

	@Test
	void picksOutOnlyTheSpawnsWhoseNpcIsABindPointAndGroupsThemByMap(@TempDir Path data) throws IOException {
		Files.createDirectories(data.resolve("bind_points"));
		Files.createDirectories(data.resolve("spawns/Npcs"));
		Files.writeString(data.resolve("bind_points/bind_points.xml"), """
			<bind_points>
			    <bind_point npcid="700015" name="Binding_Stone_verterron" price="480"/>
			</bind_points>
			""");
		Files.writeString(data.resolve("spawns/Npcs/210030000_Verteron.xml"), """
			<spawns>
				<spawn_map map_id="210030000">
					<spawn npc_id="798173" respawn_time="295">
						<spot x="10.0" y="20.0" z="30.0" h="94"/>
					</spawn>
					<spawn npc_id="700015" respawn_time="0">
						<spot x="2319.5" y="1802.25" z="195.0" h="0"/>
					</spawn>
					<spawn npc_id="798174" respawn_time="295">
						<spot x="99.0" y="99.0" z="99.0" h="1"/>
					</spawn>
				</spawn_map>
			</spawns>
			""");

		Map<Integer, List<ObeliskAudit.Obelisk>> found = ObeliskAudit.readObelisks(data);

		assertEquals(Map.of(210030000, List.of(new ObeliskAudit.Obelisk(210030000, 2319.5f, 1802.25f, 195.0f))), found,
			"the bind stone is found, the two ordinary npcs on either side of it are not");
	}
}
