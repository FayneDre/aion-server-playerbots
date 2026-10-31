package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MapSlopesTest {

	private static final int MORHEIM = 220020000, ELTNEN = 210020000;

	@Test
	void theHillBesideMorheimsCaveIsClimbedAtSixtyTwoDegrees() {
		assertEquals(Math.cos(Math.toRadians(62)), MapSlopes.zoneCosine(MORHEIM, 740, 330), 1e-6, "on the hill the way into the cave climbs");
	}

	@Test
	void groundOutsideTheZoneAndOtherMapsKeepTheirOwnLimit() {
		assertEquals(1f, MapSlopes.zoneCosine(MORHEIM, 1500, 1500), 0f, "elsewhere on the map: no relaxation");
		assertEquals(1f, MapSlopes.zoneCosine(ELTNEN, 740, 330), 0f, "the same coordinates on another map");
	}

	@Test
	void aMapWithAZoneStepsAtTheZonesSlopeAndOthersAt45() {
		assertEquals(Math.tan(Math.toRadians(62)), MapSlopes.tangent(MORHEIM), 1e-4, "the path finder steps with the steepest slope the mesh was made for");
		assertEquals(1f, MapSlopes.tangent(ELTNEN), 1e-4, "45 degrees");
		assertEquals(1f, MapSlopes.steepestZoneCosine(ELTNEN), 0f);
	}
}
