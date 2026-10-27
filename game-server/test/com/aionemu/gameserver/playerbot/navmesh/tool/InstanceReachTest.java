package com.aionemu.gameserver.playerbot.navmesh.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void anEntranceWithADialogBeforeItsLocationIsAnArrival(@TempDir Path directory) throws IOException {
		Path list = directory.resolve("portal_loc.xml");
		Files.writeString(list, """
			<portal_loc world_id="300600000" loc_id="3006000" x="707.06085" y="159.32991" z="453.47333"/>
			<portal_loc world_id="300600000" loc_id="3006001" x="295.75" y="735.73" z="215.8" h="60"/>
			""");
		Path templates = directory.resolve("portal_template2.xml");
		Files.writeString(templates, """
			<portal_use npc_id="219982">
				<portal_path loc_id="3006001" />
			</portal_use>
			<portal_dialog npc_id="730366">
				<portal_path dialog="10000" loc_id="3006000" siege_id="1011" race="ASMODIANS" />
			</portal_dialog>
			""");

		List<float[]> splinter = InstanceReach.entries(list, templates, 300600000);
		assertEquals(2, splinter.size(), "the entrance from outside and the pad inside are both led to");
		assertEquals(707.06085f, splinter.get(0)[0], 0.001f);
	}

	@Test
	void aWindstreamOfTheMapSetsItsRiderDownWhereItsFlightPathEnds(@TempDir Path directory) throws IOException {
		Path windstreams = directory.resolve("windstreams.xml");
		Files.writeString(windstreams, """
			<windstreams>
				<windstream mapid="300020000">
					<locations>
						<location id="158" state="1" fly_path="TWO_WAY"/>
					</locations>
				</windstream>
				<windstream mapid="300250000">
					<locations>
						<location id="159" state="1" fly_path="ONE_WAY"/>
						<location id="161" state="0" fly_path="ONE_WAY"/>
					</locations>
				</windstream>
			</windstreams>
			""");
		Path flyPaths = directory.resolve("flypath_template.xml");
		Files.writeString(flyPaths, """
			<flypath_location id="158" sx="230" sy="114" sz="35" sworld="300020000" ex="230" ey="140" ez="35" eworld="300020000" time="8"/>
			<flypath_location id="159" sx="398" sy="553" sz="319" sworld="300250000" ex="881" ey="643" ez="187" eworld="300250000" time="26"/>
			<flypath_location id="160" sx="398" sy="553" sz="318" sworld="300250000" ex="1279" ey="597" ez="304" eworld="300250000" time="24.6"/>
			<flypath_location id="161" sx="396" sy="552" sz="320" sworld="300250000" ex="769" ey="863" ez="374" eworld="300250000" time="26.9"/>
			""");

		List<float[]> esoterrace = InstanceReach.windstreamLandings(windstreams, flyPaths, 300250000);
		assertEquals(2, esoterrace.size(), "160 is not one of the map's windstreams, and one not yet open still counts");
		assertEquals(881f, esoterrace.get(0)[0], 0.001f);
		assertEquals(374f, esoterrace.get(1)[2], 0.001f);
		assertTrue(InstanceReach.windstreamLandings(windstreams, flyPaths, 300040000).isEmpty());
		assertTrue(InstanceReach.windstreamLandings(directory.resolve("missing.xml"), flyPaths, 300250000).isEmpty(), "no list is no landing, not a failure");
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
}
