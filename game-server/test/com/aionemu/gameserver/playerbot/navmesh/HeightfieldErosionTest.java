package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

class HeightfieldErosionTest {

	private static final int SIZE = 9, WALL_X = 4;
	private static final float FLOOR = 10, UNDER_FLOOR = 8, WALL_TOP = 11.5f, ROOF = 20;

	/** One surface of a column: its height and whether a body may stand on it before erosion. */
	private record Surface(float z, boolean walkable) {
	}

	private static Heightfield field(IntFunction<List<Surface>> columnAtX) {
		return field((x, y) -> columnAtX.apply(x));
	}

	private interface Columns {
		List<Surface> at(int x, int y);
	}

	private static Heightfield field(Columns columns) {
		int[] offsets = new int[SIZE * SIZE + 1];
		List<Float> heights = new ArrayList<>();
		BitSet walkable = new BitSet();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = heights.size();
				for (Surface surface : columns.at(x, y)) {
					walkable.set(heights.size(), surface.walkable());
					heights.add(surface.z());
				}
			}
		}
		offsets[SIZE * SIZE] = heights.size();
		float[] surfaces = new float[heights.size()];
		for (int i = 0; i < surfaces.length; i++)
			surfaces[i] = heights.get(i);
		return new Heightfield(SIZE, SIZE, offsets, surfaces, walkable);
	}

	/** @return Whether the surface of that column nearest the height is walkable. */
	private static boolean walkableAt(Heightfield field, int x, int y, float z) {
		for (int i = field.columnStart(x, y); i < field.columnEnd(x, y); i++) {
			if (Math.abs(field.surfaceAt(i) - z) < 0.01f)
				return field.isWalkable(i);
		}
		throw new AssertionError("no surface at " + z + " in column " + x + " " + y);
	}

	/** A floor crossed by a wall along one column, whose own column still holds walkable ground: under the floor, and on top of the wall. */
	private static Heightfield floorWithAWall() {
		return field(x -> x == WALL_X ? List.of(new Surface(UNDER_FLOOR, true), new Surface(FLOOR, false), new Surface(WALL_TOP, true))
			: List.of(new Surface(FLOOR, true)));
	}

	@Test
	void theFloorBesideAWallIsShavedEvenWhenTheWallsColumnHasGroundElsewhere() {
		Heightfield field = floorWithAWall();

		field.erode(1, BotPathFinder.STEP_TOLERANCE, 1);

		assertFalse(walkableAt(field, WALL_X - 1, 4, FLOOR), "the floor against one side of the wall");
		assertFalse(walkableAt(field, WALL_X + 1, 4, FLOOR), "and against the other");
		assertTrue(walkableAt(field, WALL_X - 2, 4, FLOOR), "a body's width away it is floor again");
		assertTrue(walkableAt(field, WALL_X + 2, 4, FLOOR));
		assertFalse(walkableAt(field, WALL_X, 4, WALL_TOP), "the top of the wall is no floor a body fits on");
	}

	@Test
	void aHillsideWithStepsTallerThanAStrideIsNotSliced() {
		// a slope judged over two metres but sampled every half: neighbours a stride apart in height, which is no edge
		Heightfield field = field(x -> List.of(new Surface(x * 0.8f, true)));

		field.erode(1, BotPathFinder.STEP_TOLERANCE, 1);

		for (int x = 1; x < SIZE - 1; x++)
			assertTrue(walkableAt(field, x, 4, x * 0.8f), "the hillside at " + x);
	}

	@Test
	void aDoorwayInAWallAtAnAngleStaysOpen() {
		// a wall along the diagonal, two cells thick so no step crosses it, with a doorway where x is 3 or 4
		Heightfield field = field((x, y) -> (x + y == 8 || x + y == 9) && (x < 3 || x > 4) ? List.of(new Surface(FLOOR, false))
			: List.of(new Surface(FLOOR, true)));

		field.erode(1, BotPathFinder.STEP_TOLERANCE, 1);

		// the way through: 3 4 on one side, 3 5 and 4 5 in the doorway, 4 6 on the other
		assertTrue(walkableAt(field, 3, 5, FLOOR), "the doorway, which a square reaching to its diagonal neighbours shut");
		assertTrue(walkableAt(field, 4, 5, FLOOR));
		assertTrue(walkableAt(field, 3, 4, FLOOR) && walkableAt(field, 4, 6, FLOOR), "the floor on either side of it");
		assertFalse(walkableAt(field, 1, 6, FLOOR), "the floor against the wall is still shaved");
	}

	@Test
	void shavingAFloorLeavesTheRoofAboveIt() {
		Heightfield field = field(x -> x == WALL_X ? List.of(new Surface(FLOOR, false), new Surface(ROOF, true))
			: List.of(new Surface(FLOOR, true), new Surface(ROOF, true)));

		field.erode(1, BotPathFinder.STEP_TOLERANCE, 1);

		assertFalse(walkableAt(field, WALL_X - 1, 4, FLOOR));
		assertTrue(walkableAt(field, WALL_X - 1, 4, ROOF), "the roof over the shaved floor is ground of its own");
	}
}
