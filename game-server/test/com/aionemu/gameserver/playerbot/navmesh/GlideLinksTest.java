package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The glide links of a made-up ground: a high platform with a low floor beside it, three metres of air between the two, and nothing joining them.
 */
class GlideLinksTest {

	private static final int SIZE = 64;
	private static final float PLATFORM = 12f, FLOOR = 0f;

	/** Platform on the west half of the square (cells 0-19), the floor on the east (cells 26-63): six cells, three metres, of nothing in between. */
	private Heightfield field() {
		return field(false);
	}

	/** @param treeTops whether the floor's first columns hold, halfway up, something nobody can stand on: a tree's crown, a rail, a steep rock */
	private Heightfield field(boolean treeTops) {
		int[] offsets = new int[SIZE * SIZE + 1];
		List<Float> heights = new ArrayList<>();
		BitSet walkable = new BitSet();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = heights.size();
				if (x < 20 || x >= 26) {
					walkable.set(heights.size());
					heights.add(x < 20 ? PLATFORM : FLOOR);
				}
				if (treeTops && x >= 26 && x < 20 + GlideLinks.REACH_CELLS + 2) // under the whole reach of a glide, or the floor past them would be found anyway
					heights.add(PLATFORM / 2);
			}
		}
		offsets[SIZE * SIZE] = heights.size();
		float[] surfaces = new float[heights.size()];
		for (int i = 0; i < surfaces.length; i++)
			surfaces[i] = heights.get(i);
		return new Heightfield(SIZE, SIZE, offsets, surfaces, walkable);
	}

	@Test
	void theWayDownFromThePlatformIsALinkAndTheWayUpIsNot() {
		Heightfield field = field();

		List<GlideLink> links = GlideLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		assertFalse(links.isEmpty(), "a platform above a floor has somewhere to land");
		for (GlideLink link : links) {
			assertTrue(link.fromZ() > link.toZ() + GlideLinks.MIN_DROP, "every link goes down: " + link);
			assertNotEquals(link.fromRegion(), link.toRegion());
			assertTrue(link.fromX() < 10 && link.toX() > 13, "from the platform to the floor: " + link);
		}
	}

	@Test
	void somethingNobodyStandsOnUnderTheEdgeDoesNotHideTheFloor() {
		Heightfield field = field(true);

		List<GlideLink> links = GlideLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		assertFalse(links.isEmpty(), "the floor below the crowns is still where a glide lands");
		for (GlideLink link : links)
			assertEquals(FLOOR, link.toZ(), 0.01f, "landing on the floor, not on the crown: " + link);
	}

	@Test
	void linksOfOnePairOfStretchesAreKeptApart() {
		Heightfield field = field();

		List<GlideLink> links = GlideLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		// 32 m of edge would give sixty-four candidates at one per cell; at one per eight metres it is a handful
		assertTrue(links.size() <= 8, "thinned along the edge: " + links.size());
		assertTrue(links.size() >= 2, "but more than one choice is kept: " + links.size());
	}

	@Test
	void noPlatformMeansNoLinks() {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		BitSet walkable = new BitSet();
		for (int i = 0; i < SIZE * SIZE; i++) {
			offsets[i] = i;
			walkable.set(i);
		}
		offsets[SIZE * SIZE] = SIZE * SIZE;
		Heightfield flat = new Heightfield(SIZE, SIZE, offsets, surfaces, walkable);

		assertTrue(GlideLinks.find(flat, flat.regions(BotPathFinder.STEP_TOLERANCE)).isEmpty());
	}
}
