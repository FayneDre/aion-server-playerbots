package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.BitSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The one budget for every open map's tiles: past it, the map nobody walks gives its tiles back to the one bots walk. Two flat maps of 4 x 4 tiles
 * written to disk under map ids no real map has, and a budget of a few tiles set in place of the share of the heap.
 */
class TileBudgetTest {

	private static final int IDLE_MAP = 987660, BUSY_MAP = 987661;
	private static final int SIZE = Navmesh.TILE_SIZE * 4;
	private static final int TILES = 16;

	private Navmesh idle, busy;

	@AfterEach
	void cleanUp() throws IOException {
		TileBudget.INSTANCE.setLimitForTests(0);
		// closed before the delete: Windows refuses to delete a file still open
		if (idle != null)
			idle.close();
		if (busy != null)
			busy.close();
		Files.deleteIfExists(Navmesh.fileOf(IDLE_MAP));
		Files.deleteIfExists(Navmesh.fileOf(BUSY_MAP));
	}

	private static Navmesh flatMap(int mapId) throws IOException {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE];
		BitSet walkable = new BitSet();
		for (int i = 0; i < SIZE * SIZE; i++) {
			offsets[i] = i;
			surfaces[i] = 10f;
			walkable.set(i);
		}
		offsets[SIZE * SIZE] = SIZE * SIZE;
		NavmeshWriter.write(mapId, new Heightfield(SIZE, SIZE, offsets, Arrays.copyOf(surfaces, SIZE * SIZE), walkable));
		return Navmesh.open(mapId);
	}

	/** Reads every tile of the map, one cell in each. */
	private static void walkAll(Navmesh mesh) {
		for (int tile = 0; tile < TILES; tile++)
			mesh.hasFooting(tile % 4 * Navmesh.TILE_SIZE + 1, tile / 4 * Navmesh.TILE_SIZE + 1);
	}

	@Test
	void theMapNobodyWalksGivesItsTilesToTheOneBotsWalk() throws IOException {
		idle = flatMap(IDLE_MAP);
		busy = flatMap(BUSY_MAP);
		walkAll(idle);
		assertEquals(TILES, idle.loadedTiles());
		long tile = idle.memoryFootprint() / TILES;
		// room for the busy map's sixteen tiles and two more, while both maps' together are thirty two
		TileBudget.INSTANCE.setLimitForTests(TileBudget.INSTANCE.held() - idle.memoryFootprint() + tile * (TILES + 2));
		for (int round = 0; round < 6; round++)
			walkAll(busy);
		assertEquals(TILES, busy.loadedTiles(), "the map walked keeps every tile its bots stand on");
		assertTrue(idle.loadedTiles() <= 2, "the idle map gave its tiles back: " + idle.loadedTiles() + " left");
		assertTrue(TileBudget.INSTANCE.held() <= TileBudget.INSTANCE.limit(), "and the total is inside the budget");
	}

	@Test
	void aTileGivenBackIsReadAgainWhenABotComesBack() throws IOException {
		idle = flatMap(IDLE_MAP);
		busy = flatMap(BUSY_MAP);
		walkAll(idle);
		long tile = idle.memoryFootprint() / TILES;
		TileBudget.INSTANCE.setLimitForTests(TileBudget.INSTANCE.held() - idle.memoryFootprint() + tile * (TILES + 2));
		for (int round = 0; round < 6; round++)
			walkAll(busy);
		assertTrue(idle.hasFooting(1, 1), "the same ground, read from the file again");
	}

	@Test
	void aClosedMapTakesItsTilesOutOfTheCount() throws IOException {
		long before = TileBudget.INSTANCE.held();
		idle = flatMap(IDLE_MAP);
		walkAll(idle);
		assertTrue(TileBudget.INSTANCE.held() > before);
		idle.close();
		idle = null;
		assertEquals(before, TileBudget.INSTANCE.held());
	}
}
