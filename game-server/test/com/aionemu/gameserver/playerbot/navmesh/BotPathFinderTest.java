package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntBinaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Route planning over a ground made up for the purpose, written to disk and read back through the real mesh format, so that the writer, the reader and
 * the search are exercised together. Nothing here needs a world: a bot that cannot plan a route stands where it was dropped, and none of it is
 * visible from outside until a population has stood there for an hour.
 * <p>
 * The ground is a flat square of 64 m. {@link #SOLID} columns carry no surface at all, which is what a wall looks like to the mesh.
 */
class BotPathFinderTest {

	/** A map id no real map has, so the file this writes under {@code data/navmesh} cannot be mistaken for one. */
	private static final int TEST_MAP_ID = 987654;
	private static final int SIZE = 128;
	private static final float HEIGHT = 10f;
	private static final int SOLID = 0, OPEN = 1;

	private Navmesh mesh;

	@AfterEach
	void removeTheFile() throws IOException {
		// Windows refuses to delete a file that is still open, so the channel has to go first; the mesh has no close of its own because in the server it
		// lives as long as the process does.
		if (mesh != null)
			mesh.close();
		Files.deleteIfExists(Navmesh.fileOf(TEST_MAP_ID));
	}

	/** @param ground 1 where a body can stand at cell x, y, and {@link #SOLID} where it cannot. */
	private Navmesh meshOf(IntBinaryOperator ground) throws IOException {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		BitSet walkable = new BitSet();
		int surface = 0;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = surface;
				if (ground.applyAsInt(x, y) == OPEN) {
					surfaces[surface] = HEIGHT;
					walkable.set(surface);
					surface++;
				}
			}
		}
		offsets[SIZE * SIZE] = surface;
		NavmeshWriter.write(TEST_MAP_ID, new Heightfield(SIZE, SIZE, offsets, java.util.Arrays.copyOf(surfaces, surface), walkable));
		mesh = Navmesh.open(TEST_MAP_ID);
		return mesh;
	}

	private static boolean wall(int x, int y) {
		return x >= 60 && x < 64;
	}

	@Test
	void walksStraightAcrossOpenGround() throws IOException {
		Navmesh open = meshOf((x, y) -> OPEN);

		BotPathFinder.Route route = BotPathFinder.findPath(open, 10, 10, HEIGHT, 50, 50, HEIGHT);

		assertFalse(route.isEmpty());
		assertFalse(route.gaveUp());
		List<Vector3f> points = route.waypoints();
		assertEquals(50, points.getLast().getX(), 1.5f);
		assertEquals(50, points.getLast().getY(), 1.5f);
		assertTrue(points.size() <= 4, "open ground is a straight line, however many cells it crosses: " + points.size());
	}

	@Test
	void goesRoundAWallThroughItsGap() throws IOException {
		// a wall from the south edge to y=50 (cells 0..99), open above it
		Navmesh walled = meshOf((x, y) -> wall(x, y) && y < 100 ? SOLID : OPEN);

		BotPathFinder.Route route = BotPathFinder.findPath(walled, 20, 10, HEIGHT, 44, 10, HEIGHT);

		assertFalse(route.isEmpty(), "the gap is a way through");
		float furthestNorth = (float) route.waypoints().stream().mapToDouble(Vector3f::getY).max().orElse(0);
		assertTrue(furthestNorth >= 49, "the route must climb to the gap at y=50 before it can come back: " + furthestNorth);
		for (Vector3f point : route.waypoints())
			assertFalse(point.getX() >= 30 && point.getX() < 32 && point.getY() < 49, "no waypoint may sit inside the wall: " + point);
	}

	@Test
	void provesThereIsNoWayAcrossAWallWithNoGap() throws IOException {
		Navmesh sealed = meshOf((x, y) -> wall(x, y) ? SOLID : OPEN);

		BotPathFinder.Route route = BotPathFinder.findPath(sealed, 20, 10, HEIGHT, 44, 10, HEIGHT);

		assertTrue(route.isEmpty());
		assertFalse(route.gaveUp(), "a search that exhausted the ground proved the place unreachable, which is not the same as running out of time");
	}

	@Test
	void sharesGroundOnlyWhereThereIsAWayBetween() throws IOException {
		assertTrue(BotPathFinder.shareGround(meshOf((x, y) -> wall(x, y) && y < 100 ? SOLID : OPEN), 20, 10, 44, 10));
		assertFalse(BotPathFinder.shareGround(meshOf((x, y) -> wall(x, y) ? SOLID : OPEN), 20, 10, 44, 10));
	}

	@Test
	void callsEnclosedGroundAPocketOnlyWhenItIsSmall() throws IOException {
		// a 3 m square ringed by solid ground, in the middle of an open field
		Navmesh pocketed = meshOf((x, y) -> (x >= 60 && x < 66 && y >= 60 && y < 66) ? (x > 60 && x < 65 && y > 60 && y < 65 ? OPEN : SOLID) : OPEN);

		assertTrue(BotPathFinder.isPocket(pocketed, 31.5f, 31.5f, HEIGHT, 100), "sixteen cells with no way out");
		assertFalse(BotPathFinder.isPocket(pocketed, 31.5f, 31.5f, HEIGHT, 10), "a bound smaller than the ground proves nothing about it");
		assertFalse(BotPathFinder.isPocket(pocketed, 5, 5, HEIGHT, 100), "the open field is not enclosed");
	}

	@Test
	void findsNoGroundWhereNoneIsSampled() throws IOException {
		Navmesh walled = meshOf((x, y) -> wall(x, y) ? SOLID : OPEN);

		assertNull(BotPathFinder.nearestGround(walled, 500, 500, HEIGHT));
		assertNotNull(BotPathFinder.nearestGround(walled, 10, 10, HEIGHT));
	}
}
