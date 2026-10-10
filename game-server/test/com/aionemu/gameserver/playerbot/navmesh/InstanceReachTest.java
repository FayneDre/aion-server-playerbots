package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a dungeon's mesh is asked about is read from the game's own files, so the reading is tested on lines shaped like theirs.
 */
class InstanceReachTest {

	@Test
	void entriesAreTheLocationsAPortalLeadsToAndOnlyOfTheMapAskedFor(@TempDir Path directory) throws IOException {
		Path list = directory.resolve("portal_loc.xml");
		Files.writeString(list, """
			<portal_locs>
				<portal_loc world_id="300040000" loc_id="3000400" x="1225.9075" y="419.7187" z="140.18988"/>
				<portal_loc world_id="300040000" loc_id="3000401" x="1188.80707" y="1241.4151" z="143.85065"/>
				<portal_loc world_id="300040000" loc_id="3000499" x="10.0" y="20.0" z="30.0"/>
				<portal_loc world_id="300090000" loc_id="3000900" x="265.984" y="387.77493" z="103.24291"/>
				<portal_loc world_id="300200000" loc_id="3002000" x="1.0" y="2.0" z="3.0"/>
				<portal_loc world_id="300200000" loc_id="3002001" x="4.0" y="5.0" z="6.0"/>
			</portal_locs>
			""");
		Path templates = directory.resolve("portal_template2.xml");
		Files.writeString(templates, """
			<portal_use npc_id="1"><portal_path loc_id="3000400" race="ELYOS" /></portal_use>
			<portal_use npc_id="2"><portal_path loc_id="3000401" race="ASMODIANS" /></portal_use>
			<portal_use npc_id="3"><portal_path loc_id="3000900" /></portal_use>
			""");

		List<float[]> poeta = InstanceReach.entries(list, templates, 300040000);
		assertEquals(2, poeta.size(), "the third location of the map is not led to by any portal: not an arrival");
		assertEquals(1225.9075f, poeta.get(0)[0], 0.001f);
		assertEquals(143.85065f, poeta.get(1)[2], 0.001f);
		assertEquals(1, InstanceReach.entries(list, templates, 300090000).size());
		assertEquals(2, InstanceReach.entries(list, templates, 300200000).size(), "no portal leads to either: all of them, rather than none");
		assertTrue(InstanceReach.entries(list, templates, 999).isEmpty());
		assertEquals(3, InstanceReach.entries(list, directory.resolve("missing.xml"), 300040000).size(), "no list of portals to go by: every location");
	}

	@Test
	void spawnSpotsAreReadFromTheFilesOfThatInstanceOnly(@TempDir Path directory) throws IOException {
		Files.writeString(directory.resolve("300090000_Right_Wing_Chamber.xml"), """
			<spawns>
				<spawn_map map_id="300090000">
					<spawn npc_id="700457">
						<spot x="263.6143" y="395.2482" z="103.2429" static_id="14" />
					</spawn>
					<spawn npc_id="700471">
						<spot x="193.054" y="220.265" z="101.841" h="67"/>
						<spot x="194.761" y="208.34" z="101.841" h="14"/>
					</spawn>
				</spawn_map>
			</spawns>
			""");
		Files.writeString(directory.resolve("300040000_Dark_Poeta.xml"), "<spot x=\"1.0\" y=\"2.0\" z=\"3.0\"/>");

		List<float[]> spots = InstanceReach.spawnSpots(directory, 300090000);
		assertEquals(3, spots.size());
		assertEquals(193.054f, spots.get(1)[0], 0.001f);
		assertEquals(1, InstanceReach.spawnSpots(directory, 300040000).size());
		assertTrue(InstanceReach.spawnSpots(directory.resolve("nowhere"), 300090000).isEmpty(), "no folder is no spots, not a failure");
	}

	@Test
	void aMapIsAnInstanceOnlyWhenTheListSaysSo(@TempDir Path directory) throws IOException {
		Path list = directory.resolve("world_maps.xml");
		Files.writeString(list, """
			<world_maps>
				<map id="210010000" cName="LF1" name="Poeta" world_size="3072" flags="BIND RECALL"/>
				<map id="300040000" cName="IDLF1" name="Dark Poeta" name_id="401172" instance="true" water_level="100" world_size="1536"/>
			</world_maps>
			""");

		assertTrue(MapSize.isInstance(list, 300040000));
		assertFalse(MapSize.isInstance(list, 210010000));
		assertFalse(MapSize.isInstance(list, 999));
	}
}
