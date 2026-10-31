package com.aionemu.gameserver.playerbot.navmesh;

import java.util.List;

/**
 * Places where a model's steep faces are not walls: rock that overhangs a way a body walks, which the generator would take for a wall and shut.
 * <p>
 * Found by hand, from a tester standing where the mesh said nobody could, so each entry names the model and the box it is about; the same model elsewhere on the map is a wall still.
 */
final class MapOpenings {

	private record Opening(int mapId, String model, float minX, float minY, float maxX, float maxY) {
		boolean holds(int map, String name, float x, float y) {
			return map == mapId && name.equals(model) && x >= minX && x <= maxX && y >= minY && y <= maxY;
		}
	}

	/**
	 * Morheim, the cave of the east: a rock lip with its foot 1.4 m over the floor ran across the mouth, and its steep face shut a band of cells from wall to wall. The inside was a pocket
	 * of 308 cells that no route entered, five metres from the entrance (31 October, a group's bots at 768 318 could not follow the player who stood at 771 320).
	 */
	private static final List<Opening> OPENINGS = List.of(new Opening(220020000, "na_d_icerock_b_04d.cgf", 755, 305, 790, 335));

	private MapOpenings() {
	}

	/** @return true if a face of this model at this spot is no wall. */
	static boolean isOpen(int mapId, String modelFile, float x, float y) {
		for (Opening opening : OPENINGS) {
			if (opening.holds(mapId, modelFile, x, y))
				return true;
		}
		return false;
	}
}
