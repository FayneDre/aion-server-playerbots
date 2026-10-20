package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A floor and a terrace fifty metres over it that nothing walks between, joined by a lift written to disk and read back through the real mesh format: the
 * route takes the lift, says how long it lasts, and is no glide or flight.
 */
class RideRoutesTest {

	/** A map id no real map has, so the files this writes under {@code data/navmesh} cannot be mistaken for one. */
	private static final int TEST_MAP_ID = 987656;
	private static final int SIZE = 128;
	private static final float FLOOR = 500f, TERRACE = 550f;
	private static final int LIFT_SECONDS = 20;

	private Navmesh mesh;

	@AfterEach
	void removeTheFiles() throws IOException {
		if (mesh != null)
			mesh.close();
		Files.deleteIfExists(Navmesh.fileOf(TEST_MAP_ID));
		Files.deleteIfExists(RideLinks.fileOf(TEST_MAP_ID));
	}

	/** The floor on cells 0-49, the terrace on cells 90-127, the lift from the floor's east edge to the terrace's west edge. */
	private Navmesh floorAndTerrace() throws IOException {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		BitSet walkable = new BitSet();
		int surface = 0;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = surface;
				if (x < 50 || x >= 90) {
					surfaces[surface] = x < 50 ? FLOOR : TERRACE;
					walkable.set(surface++);
				}
			}
		}
		offsets[SIZE * SIZE] = surface;
		Heightfield field = new Heightfield(SIZE, SIZE, offsets, java.util.Arrays.copyOf(surfaces, surface), walkable);
		NavmeshWriter.write(TEST_MAP_ID, field);
		int[] regions = field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(TEST_MAP_ID));
		int floor = regions[field.columnStart(40, 32)], terrace = regions[field.columnStart(100, 32)];
		RideLinks.write(TEST_MAP_ID, List.of(new GlideLink(20, 16, FLOOR, 50, 16, TERRACE, floor, terrace, false, LIFT_SECONDS),
			new GlideLink(50, 16, TERRACE, 20, 16, FLOOR, terrace, floor, false, LIFT_SECONDS)));
		mesh = Navmesh.open(TEST_MAP_ID);
		return mesh;
	}

	@Test
	void theRouteTakesTheLift() throws IOException {
		BotPathFinder.Route route = BotPathFinder.findPath(floorAndTerrace(), 5, 16, FLOOR, 60, 16, TERRACE, Avoidance.NONE, GlideRoutes.AirTest.ALWAYS);

		assertFalse(route.isEmpty(), "a route up");
		assertEquals(1, route.rides().size(), "one ride: " + route.waypoints());
		int arrival = route.rides().keySet().iterator().next();
		assertEquals(LIFT_SECONDS, route.rides().get(arrival));
		assertEquals(TERRACE, route.waypoints().get(arrival).getZ(), 0.01f, "the ride arrives on the terrace");
		assertTrue(route.glides().isEmpty() && route.flights().isEmpty(), "a lift is neither a glide nor a flight");
	}

	@Test
	void theLiftComesBackDown() throws IOException {
		BotPathFinder.Route route = BotPathFinder.findPath(floorAndTerrace(), 60, 16, TERRACE, 5, 16, FLOOR, Avoidance.NONE, GlideRoutes.AirTest.ALWAYS);

		assertEquals(1, route.rides().size(), "the same lift, the other way: " + route.waypoints());
	}
}
