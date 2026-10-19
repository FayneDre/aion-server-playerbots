package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A listed face is left out only when its whole triangle lies in the plane, and only for its own model and map.
 */
class DroppedFacesTest {

	@Test
	void aTriangleIsLeftOutOnlyWithAllThreeCornersOnThePlane() {
		DroppedFaces.Plane plane = new DroppedFaces.Plane("model", true, 301.5f);
		assertTrue(plane.holds(301.5f, 155f, 301.52f, 179f, 301.49f, 160f));
		assertFalse(plane.holds(301.5f, 155f, 301.5f, 179f, 302f, 160f), "one corner off the plane: a floor or a slope meeting it");
		assertFalse(new DroppedFaces.Plane("model", false, 301.5f).holds(301.5f, 155f, 301.5f, 179f, 301.5f, 160f), "the other axis");
	}

	@Test
	void theFacesListedBelongToTheirModelAndMap() {
		assertEquals(1, DroppedFaces.of(310110000, "levels/common/light/structures/buildings/dungeon/dungeon_lf2a/bu_l_laboratory_room_06f.cgf").size());
		assertTrue(DroppedFaces.of(310110000, "levels/common/light/structures/buildings/dungeon/dungeon_lf2a/bu_l_laboratory_room_06g.cgf").isEmpty());
		assertTrue(DroppedFaces.of(300250000, "bu_l_laboratory_room_06f.cgf").isEmpty());
	}
}
