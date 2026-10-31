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

	/**
	 * A floor the data does not hold: a flat plane over {@code minX..maxX} by {@code minY..maxY}, at {@code z} where x is {@code minX} and falling {@code fall} metres to the metre of x.
	 */
	record Floor(int mapId, float minX, float minY, float maxX, float maxY, float z, float fall) {
		float zAt(float x) {
			return z - (x - minX) * fall;
		}
	}

	/**
	 * Morheim, the cave of the east: the way from the mouth in the west (768,8 318,3) to the corridor that runs north (771,3 320,3) has a floor that the data does not hold. The ground is
	 * marked steep at 770,2 319,4 at any slope up to 80 degrees and absent at 770,8 319,8, and the tester walks it (31 October, with a group whose bots could only reach the corridor by a
	 * walk of 166 m round the hill, and the cave is the only way).
	 */
	private static final List<Floor> FLOORS = List.of(new Floor(220020000, 768.5f, 317.8f, 772.5f, 321.8f, 516.3f, 0.32f));

	/** @return the floors the data lacks on this map. */
	static List<Floor> floors(int mapId) {
		return FLOORS.stream().filter(floor -> floor.mapId() == mapId).toList();
	}

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
