package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * The creatures filed by squares answer whether one stands near a spot exactly as the whole list walked one by one did, on both sides of zero and on
 * the edges of the squares.
 */
class DangersTest {

	private static final float RADIUS = 40;

	@Test
	void theSquaresAnswerAsTheWholeListDid() {
		Random random = new Random(7);
		List<Vector3f> creatures = new ArrayList<>();
		for (int i = 0; i < 1500; i++)
			creatures.add(new Vector3f(random.nextFloat() * 4000 - 2000, random.nextFloat() * 4000 - 2000, random.nextFloat() * 200));
		NavmeshService.Dangers dangers = NavmeshService.Dangers.of(0, creatures);
		int near = 0;
		for (int i = 0; i < 20_000; i++) {
			// spots on the squares' edges as well as anywhere: that is where a missing neighbour would show
			float x = i % 2 == 0 ? random.nextFloat() * 4000 - 2000 : Math.round(random.nextFloat() * 100 - 50) * RADIUS;
			float y = random.nextFloat() * 4000 - 2000, z = random.nextFloat() * 200;
			boolean walked = creatures.stream().anyMatch(c -> Math.sqrt((c.x - x) * (c.x - x) + (c.y - y) * (c.y - y) + (c.z - z) * (c.z - z)) < RADIUS);
			assertEquals(walked, dangers.isNear(x, y, z), "at " + x + " " + y + " " + z);
			near += walked ? 1 : 0;
		}
		assertTrue(near > 100, "the spots must find creatures near them often enough to mean something: " + near);
	}

	@Test
	void noCreaturesIsNeverNear() {
		assertFalse(NavmeshService.Dangers.NONE.isNear(0, 0, 0));
		assertFalse(NavmeshService.Dangers.of(0, List.of()).isNear(10, 10, 10));
	}
}
