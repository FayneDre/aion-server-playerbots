package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which floor round the body a glide plan takes for the body's own, first: a street, and a gallery 13 m over part of it that no stair joins. The
 * gallery's edge is right overhead and the street's own take-off is far, so by the edges alone the gallery comes first, which is how a Sanctum plan spent
 * its budget walking up to it. A glide from the gallery landing beside the body names the street.
 */
class GlideStartOrderTest {

	/** A map id no real map has, so the files this writes under {@code data/navmesh} cannot be mistaken for one. */
	private static final int TEST_MAP_ID = 987655;
	private static final int SIZE = 128;
	private static final float STREET = 500f, GALLERY = 513f;
	private static final float BODY_X = 30, BODY_Y = 10;

	private Navmesh mesh;
	private int street, gallery;

	@AfterEach
	void removeTheFiles() throws IOException {
		if (mesh != null)
			mesh.close();
		Files.deleteIfExists(Navmesh.fileOf(TEST_MAP_ID));
		Files.deleteIfExists(GlideLinks.fileOf(TEST_MAP_ID));
	}

	/** The street on every cell, the gallery over cells 60-99 of x: 30 to 50 m, the body standing under its west edge. */
	private Navmesh streetAndGallery(boolean landingBesideTheBody) throws IOException {
		int[] offsets = new int[SIZE * SIZE + 1];
		float[] surfaces = new float[SIZE * SIZE * 2];
		BitSet walkable = new BitSet();
		int surface = 0;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				offsets[y * SIZE + x] = surface;
				surfaces[surface] = STREET;
				walkable.set(surface++);
				if (x >= 60 && x < 100) {
					surfaces[surface] = GALLERY;
					walkable.set(surface++);
				}
			}
		}
		offsets[SIZE * SIZE] = surface;
		Heightfield field = new Heightfield(SIZE, SIZE, offsets, java.util.Arrays.copyOf(surfaces, surface), walkable);
		NavmeshWriter.write(TEST_MAP_ID, field);
		int[] regions = field.regions(BotPathFinder.STEP_TOLERANCE, MapSlopes.tangent(TEST_MAP_ID));
		street = regions[field.columnStart(10, 10)];
		gallery = regions[field.columnStart(70, 20) + 1];
		List<GlideLink> links = new ArrayList<>();
		// the gallery's edge right over the body, down onto the street's far corner, past where a landing names the body's floor
		links.add(new GlideLink(30.5f, 10.5f, GALLERY, 62, 63, STREET, gallery, street));
		// the street's own take-off, far from the body: over 60 m, past the short walk
		links.add(new GlideLink(63.5f, 63.5f, STREET, 63.5f, 60, STREET - 1, street, street));
		if (landingBesideTheBody)
			links.add(new GlideLink(31, 20, GALLERY, BODY_X - 0.5f, BODY_Y, STREET, gallery, street));
		GlideLinks.write(TEST_MAP_ID, links);
		mesh = Navmesh.open(TEST_MAP_ID);
		return mesh;
	}

	private List<Integer> startsFor(Navmesh mesh) {
		int factor = mesh.coarseFactor();
		int cell = BotPathFinder.coarseCellNear(mesh, mesh.cellX(BODY_X) / factor, mesh.cellY(BODY_Y) / factor);
		return GlideRoutes.startsInOrder(mesh, cell, BODY_X, BODY_Y, STREET);
	}

	@Test
	void byTheEdgesAloneTheGalleryOverheadComesFirst() throws IOException {
		List<Integer> starts = startsFor(streetAndGallery(false));

		assertTrue(starts.contains(street) && starts.contains(gallery), "both floors are round the body: " + starts);
		assertEquals(gallery, starts.getFirst(), "the edge overhead is nearer on paper than the street's own take-off");
	}

	@Test
	void aLandingBesideTheBodyNamesTheStreet() throws IOException {
		List<Integer> starts = startsFor(streetAndGallery(true));

		assertEquals(street, starts.getFirst());
	}
}
