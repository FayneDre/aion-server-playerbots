package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.BitSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Routes between two islands with twenty metres of air between them, written to disk with their flight links and read back through the real mesh
 * format: the crossing is part of the route, and a landing the body may not come down at leaves no route at all rather than one that goes there anyway.
 */
class FlightRoutesTest {

	/** A map id no real map has, so the files this writes under {@code data/navmesh} cannot be mistaken for one. */
	private static final int TEST_MAP_ID = 987655;
	private static final int SIZE = 128;
	private static final float HEIGHT = 1600f;

	private Navmesh mesh;

	@AfterEach
	void removeTheFiles() throws IOException {
		if (mesh != null)
			mesh.close();
		Files.deleteIfExists(Navmesh.fileOf(TEST_MAP_ID));
		Files.deleteIfExists(FlightLinks.fileOf(TEST_MAP_ID));
	}

	/** West island on cells 0-49, east island on cells 90-127. */
	private Navmesh twoIslands() throws IOException {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		BitSet walkable = new BitSet();
		int surface = 0;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = surface;
				if (x < 50 || x >= 90) {
					surfaces[surface] = HEIGHT;
					walkable.set(surface++);
				}
			}
		}
		offsets[SIZE * SIZE] = surface;
		Heightfield field = new Heightfield(SIZE, SIZE, offsets, java.util.Arrays.copyOf(surfaces, surface), walkable);
		NavmeshWriter.write(TEST_MAP_ID, field);
		FlightLinks.write(TEST_MAP_ID, FlightLinks.find(field, field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(TEST_MAP_ID))));
		mesh = Navmesh.open(TEST_MAP_ID);
		return mesh;
	}

	@Test
	void theRouteFliesAcrossTheGap() throws IOException {
		BotPathFinder.Route route = BotPathFinder.findPath(twoIslands(), 10, 32, HEIGHT, 60, 32, HEIGHT, Avoidance.NONE, GlideRoutes.AirTest.ALWAYS);

		assertFalse(route.isEmpty(), "a route across");
		assertFalse(route.flights().isEmpty(), "with a crossing by air: " + route.waypoints());
	}

	@Test
	void aLandingRefusedLeavesNoRoute() throws IOException {
		GlideRoutes.AirTest nowhereToLand = new GlideRoutes.AirTest() {

			@Override
			public boolean isClear(GlideLink link) {
				return true;
			}

			@Override
			public boolean mayLand(GlideLink link) {
				return link.toX() < 30; // the west island only, so nothing comes down on the east one
			}
		};

		BotPathFinder.Route route = BotPathFinder.findPath(twoIslands(), 10, 32, HEIGHT, 60, 32, HEIGHT, Avoidance.NONE, nowhereToLand);

		assertTrue(route.isEmpty(), "no route onto the east island: " + route.waypoints());
		assertFalse(route.gaveUp(), "and that is a proof, not a search that ran out");
	}
}
