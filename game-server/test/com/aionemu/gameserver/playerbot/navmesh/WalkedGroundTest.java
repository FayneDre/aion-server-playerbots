package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;

import org.junit.jupiter.api.Test;

class WalkedGroundTest {

	private static final int SIZE = 7;

	/** A flat floor of one surface a column at z 10, none of it walkable: what the head room, the walls and the erosion left of a corridor one cell wide. */
	private static Heightfield shutFloor() {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		for (int i = 0; i < surfaces.length; i++) {
			offsets[i] = i;
			surfaces[i] = 10;
		}
		offsets[SIZE * SIZE] = SIZE * SIZE;
		return new Heightfield(SIZE, SIZE, offsets, surfaces, new BitSet());
	}

	@Test
	void aWalkIsAStepOnTheGroundAndNotATeleportOrAFall() {
		assertTrue(WalkedGround.isAWalk(0, 0, 10, 6, 0, 10.5f), "a run of two seconds");
		assertFalse(WalkedGround.isAWalk(0, 0, 10, 0.5f, 0, 10), "standing still");
		assertFalse(WalkedGround.isAWalk(0, 0, 10, 40, 0, 10), "a teleport");
		assertFalse(WalkedGround.isAWalk(0, 0, 10, 4, 0, 6), "a fall");
	}

	@Test
	void theSurfacesRoundAWalkedPointAreGivenBackWithinItsHeightOnly() {
		Heightfield field = shutFloor();
		BitSet before = new BitSet();
		before.set(0, SIZE * SIZE); // all of it walked at the sampling

		int given = field.restoreNear(before, 1.75f, 1.75f, 10.3f, 1, 1.2f);

		assertEquals(9, given, "the point's column and its eight neighbours");
		assertTrue(field.isWalkable(field.columnStart(3, 3)));
		assertFalse(field.isWalkable(field.columnStart(6, 6)), "far from the point stays shut");
		assertEquals(0, field.restoreNear(before, 1.75f, 1.75f, 14f, 1, 1.2f), "a point 4 m above is another floor");
	}

	@Test
	void aSurfaceThatNeverWalkedIsNotMadeToWalk() {
		Heightfield field = shutFloor();

		assertEquals(0, field.restoreNear(new BitSet(), 1.75f, 1.75f, 10, 1, 1.2f), "a wall's face was never ground, whoever passed by it");
	}

	@Test
	void theCellOfAPointIsAMetreOfGroundAndHeight() {
		assertEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(10.9f, 20.1f, 5.8f));
		assertNotEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(11.2f, 20.7f, 5.1f));
		assertNotEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(10.2f, 20.7f, 7.1f));
	}
}
