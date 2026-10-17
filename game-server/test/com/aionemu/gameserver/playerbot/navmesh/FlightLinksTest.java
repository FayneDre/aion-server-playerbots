package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The flight links of a made-up archipelago: two islands at one height with twenty metres of air between them, and a rock too small to be worth landing on.
 */
class FlightLinksTest {

	private static final int SIZE = 128;
	private static final float HEIGHT = 1600f;

	/** West island on cells 0-49, east island on cells 90-127, a rock of 4 by 4 cells in the middle of the gap. */
	private Heightfield field() {
		return field(false);
	}

	/** @param overhang whether rock juts out five metres above the east island's first six columns, over its edge */
	private Heightfield field(boolean overhang) {
		int[] offsets = new int[SIZE * SIZE + 1];
		List<Float> heights = new ArrayList<>();
		BitSet walkable = new BitSet();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = heights.size();
				boolean rock = x >= 68 && x < 72 && y >= 60 && y < 64;
				if (x < 50 || x >= 90 || rock) {
					walkable.set(heights.size());
					heights.add(HEIGHT);
				}
				if (overhang && x >= 90 && x < 96)
					heights.add(HEIGHT + 5); // the top of a rock nobody stands on
			}
		}
		offsets[SIZE * SIZE] = heights.size();
		float[] surfaces = new float[heights.size()];
		for (int i = 0; i < surfaces.length; i++)
			surfaces[i] = heights.get(i);
		return new Heightfield(SIZE, SIZE, offsets, surfaces, walkable);
	}

	@Test
	void twoIslandsAreLinkedBothWaysAcrossTheGap() {
		Heightfield field = field();

		List<GlideLink> links = FlightLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		assertTrue(links.stream().anyMatch(link -> link.fromX() < 25 && link.toX() > 45), "west to east: " + links);
		assertTrue(links.stream().anyMatch(link -> link.fromX() > 45 && link.toX() < 25), "east to west: " + links);
		for (GlideLink link : links) {
			assertTrue(link.flight(), "marked as flown: " + link);
			assertNotEquals(link.fromRegion(), link.toRegion());
			assertTrue(Math.hypot(link.toX() - link.fromX(), link.toY() - link.fromY()) <= FlightLinks.MAX_ACROSS, "within a flight's reach: " + link);
		}
	}

	@Test
	void theShortestCrossingIsKeptAndTheTakeOffsAreApart() {
		Heightfield field = field();

		List<GlideLink> eastward = FlightLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE)).stream().filter(link -> link.fromX() < 25).toList();

		// the gap is 40 cells, 20 m: the nearest edges face each other straight across
		assertTrue(eastward.stream().anyMatch(link -> FlightLinks.length(link) < 22), "straight across the gap: " + eastward);
		assertTrue(eastward.size() <= 3, "a few a pair: " + eastward.size());
		for (GlideLink one : eastward)
			for (GlideLink other : eastward)
				assertTrue(one == other || Math.hypot(one.fromX() - other.fromX(), one.fromY() - other.fromY()) >= 40, "take-offs apart: " + eastward);
	}

	@Test
	void nothingLandsUnderAnOverhang() {
		Heightfield field = field(true);

		List<GlideLink> links = FlightLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		assertTrue(links.stream().anyMatch(link -> link.toX() > 45), "the east island is still reached, past the rock: " + links);
		for (GlideLink link : links) {
			assertFalse(link.toX() > 44 && link.toX() < 48, "nothing comes down under the rock: " + link);
			assertFalse(link.fromX() > 44 && link.fromX() < 48, "nothing climbs out from under it: " + link);
		}
	}

	@Test
	void aRockTooSmallToLiveOnIsNeitherLeftNorReached() {
		Heightfield field = field();

		List<GlideLink> links = FlightLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE));

		for (GlideLink link : links) {
			assertFalse(link.fromX() > 30 && link.fromX() < 40, "nothing leaves the rock: " + link);
			assertFalse(link.toX() > 30 && link.toX() < 40, "nothing lands on the rock: " + link);
		}
	}
}
