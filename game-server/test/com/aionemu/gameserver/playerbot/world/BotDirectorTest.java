package com.aionemu.gameserver.playerbot.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.world.BotDirector.Difference;
import com.aionemu.gameserver.playerbot.world.BotPlaces.Settlement;

/**
 * What one review concludes about one region.
 * <p>
 * Every case here is a fault that actually happened on the live server on 2026-10-04, and each one cost hours to find through the log because the
 * summary line a churning population writes is indistinguishable from the one a settled population writes. They are all decidable from counts, ids and
 * places alone, which is why {@code BotDirector.sort} takes its two dependencies — "is this one awake" and "which village is this" — as arguments.
 */
class BotDirectorTest {

	private static final Vector3f VILLAGE = new Vector3f(268.25f, 2730.25f, 270.65002f);
	private static final Vector3f OTHER_VILLAGE = new Vector3f(1960.75f, 2029.25f, 361.65002f);
	private static final Vector3f GROUND_A = new Vector3f(800f, 1200f, 100f);
	private static final Vector3f GROUND_B = new Vector3f(1500f, 900f, 120f);

	/** The real predicate, so the rule the production code uses to recognise a village is the rule under test. */
	private static final UnaryOperator<Vector3f> SETTLEMENTS = home -> {
		for (Vector3f centre : List.of(VILLAGE, OTHER_VILLAGE))
			if (BotPlaces.isSamePlace(centre, home))
				return centre;
		return null;
	};

	@Test
	void aRegionHoldingWhatItShouldAsksForNothing() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, VILLAGE));
		residents.add(resident(2, GROUND_A));
		Difference difference = sort(awake(1, 2), residents, villages(VILLAGE, 1), field(GROUND_A));

		assertTrue(difference.toWake().isEmpty(), "nothing to wake");
		assertTrue(difference.toSleep().isEmpty(), "nothing to sleep");
		assertTrue(difference.rehoused().isEmpty(), "nobody moves house");
	}

	/**
	 * The churn. A countryside over its target draws nobody, so every one of its sleepers is "spare" — and the short village posts then woke exactly
	 * the bots the same review had just ordered to sleep. Measured live as 2438 world entries over 222 characters, 111 of them going in and out three
	 * times or more, with the net population unchanged throughout.
	 */
	@Test
	void aRegionNeverSleepsAndWakesInTheSameBreath() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		for (int id = 1; id <= 8; id++)
			residents.add(resident(id, GROUND_A)); // eight hunters awake where the plan wants one
		residents.add(resident(9, GROUND_B)); // and one asleep, which the old code handed straight back to a short village
		Difference difference = sort(awake(1, 2, 3, 4, 5, 6, 7, 8), residents, villages(VILLAGE, 3), field(GROUND_A));

		assertEquals(7, difference.toSleep().size(), "the countryside sheds what it is over by");
		for (BotRoster.Resident waking : difference.toWake())
			assertNotEquals(9, waking.playerId(), "and the sleeper it is over by is not pulled straight back out");
		assertEquals(1, difference.rehoused().size(), "it is given the empty post where it sleeps instead");
	}

	/** A village post nobody lives at is filled by moving a sleeper's house, not by waking it: the move costs a row and no world at all. */
	@Test
	void anEmptyVillagePostIsFilledByMovingHouseRatherThanByWaking() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, GROUND_A));
		residents.add(resident(2, GROUND_B)); // one each for the empty post and the countryside, so neither starves the other
		Difference difference = sort(awake(), residents, villages(VILLAGE, 1), field(GROUND_A));

		assertEquals(1, difference.rehoused().size(), "a sleeper is given the empty post");
		BotRoster.Resident mover = difference.rehoused().get(0);
		assertEquals(VILLAGE, mover.home());
		for (BotRoster.Resident waking : difference.toWake())
			assertNotEquals(mover.playerId(), waking.playerId(), "and it is not woken to be told about it");
	}

	/** On contested ground each side is woken in the share of the countryside its grounds are, not in the pool's order: Reshanta woke 26 Elyos and 3 Asmodians. */
	@Test
	void eachSideIsWokenForItsOwnShareOfTheField() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		for (int id = 1; id <= 4; id++)
			residents.add(new BotRoster.Resident(id, "Bot" + id, 400010000, GROUND_A, Race.ELYOS));
		for (int id = 5; id <= 6; id++)
			residents.add(new BotRoster.Resident(id, "Bot" + id, 400010000, GROUND_B, Race.ASMODIANS));
		List<Settlement> plan = List.of(new Settlement(GROUND_A, 20f, 4, 20, Race.ELYOS), new Settlement(GROUND_A, 20f, 4, 20, Race.ELYOS),
			new Settlement(GROUND_B, 20f, 4, 20, Race.ASMODIANS), new Settlement(GROUND_B, 20f, 4, 20, Race.ASMODIANS));

		Difference difference = BotDirector.sort(awake(), SETTLEMENTS, post -> null, residents, Map.of(), plan);

		assertEquals(2, difference.toWake().stream().filter(waking -> waking.race() == Race.ELYOS).count(), "two Elyos: " + difference.toWake());
		assertEquals(2, difference.toWake().stream().filter(waking -> waking.race() == Race.ASMODIANS).count(), "two Asmodians: " + difference.toWake());
	}

	/** A side awake beyond its share is swapped for a sleeper of the side short of it, when the totals are already right. */
	@Test
	void aSideOverItsShareSwapsWithTheSideUnderIt() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		for (int id = 1; id <= 3; id++)
			residents.add(new BotRoster.Resident(id, "Bot" + id, 400010000, GROUND_A, Race.ELYOS));
		residents.add(new BotRoster.Resident(4, "Bot4", 400010000, GROUND_B, Race.ASMODIANS));
		List<Settlement> plan = List.of(new Settlement(GROUND_A, 20f, 4, 20, Race.ELYOS), new Settlement(GROUND_A, 20f, 4, 20, Race.ELYOS),
			new Settlement(GROUND_B, 20f, 4, 20, Race.ASMODIANS));

		Difference difference = BotDirector.sort(awake(1, 2, 3), SETTLEMENTS, post -> null, residents, Map.of(), plan);

		assertEquals(List.of(4), difference.toWake().stream().map(BotRoster.Resident::playerId).toList(), "the Asmodian is woken");
		assertEquals(1, difference.toSleep().size(), "one Elyos goes to sleep for it: " + difference.toSleep());
		assertTrue(difference.toSleep().get(0) <= 3);
	}

	/** On contested ground a post is one side's: ten Elyos residents of Reshanta were moved into Primum, the Asmodian base. */
	@Test
	void aSleeperIsNeverMovedIntoTheOtherFactionsVillage() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(new BotRoster.Resident(1, "Bot1", 400010000, GROUND_A, Race.ELYOS));
		residents.add(new BotRoster.Resident(2, "Bot2", 400010000, GROUND_B, Race.ELYOS));
		Difference difference = BotDirector.sort(awake(), SETTLEMENTS, post -> Race.ASMODIANS, residents, villages(VILLAGE, 1), field(GROUND_A));

		assertTrue(difference.rehoused().isEmpty(), "the Asmodian post stays empty rather than take an Elyos: " + difference.rehoused());
	}

	/**
	 * The way back out, which was missing: the countryside wakes only its own sleepers, so a bot given a village post stayed a villager for ever.
	 * Verteron settled at 44 residents holding 25 posts while its countryside, wanting 59, held 40 and had no sleeper left at all.
	 */
	@Test
	void aVillagerWithNoPostGoesBackToTheCountryside() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, VILLAGE)); // awake, and the post wants exactly one
		residents.add(resident(2, VILLAGE)); // asleep, and no post wants it
		residents.add(resident(3, VILLAGE)); // likewise
		Difference difference = sort(awake(1), residents, villages(VILLAGE, 1), field(GROUND_A, GROUND_B));

		assertEquals(2, difference.rehoused().size(), "both surplus villagers are sent to the countryside");
		for (BotRoster.Resident mover : difference.rehoused())
			assertNull(SETTLEMENTS.apply(mover.home()), "and their new home is open country");
	}

	/**
	 * A settlement is a fixed cast and the countryside is the part that breathes, so an empty village post is served before an empty hunting ground.
	 * The field used to claim the last sleeper first, which left the post waiting on a surplus that a region short of people never has.
	 */
	@Test
	void anEmptyVillagePostIsServedBeforeAnEmptyHuntingGround() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, GROUND_A)); // the only sleeper in the region, and both a post and a ground want somebody
		Difference difference = sort(awake(), residents, villages(VILLAGE, 1), field(GROUND_A));

		assertEquals(1, difference.rehoused().size(), "the village post takes it");
		assertTrue(difference.toWake().isEmpty(), "and the countryside waits");
	}

	/** A village is never emptied to feed a countryside that already has the people it needs. */
	@Test
	void aVillagerStaysPutWhileTheCountrysideHasEnough() {
		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, VILLAGE));
		residents.add(resident(2, VILLAGE)); // surplus at the post, but the field below is already supplied
		residents.add(resident(3, GROUND_A));
		Difference difference = sort(awake(1, 3), residents, villages(VILLAGE, 1), field(GROUND_A));

		assertTrue(difference.rehoused().isEmpty(), "nobody is moved out of a village the field does not need");
	}

	/**
	 * The float trap, at the exact values the live log printed. A home that has been to the database comes back differing in the last bit of one
	 * component, and asking by equality put 98 of Eltnen's 102 residents in open country with their homes sat on a village.
	 */
	@Test
	void aHomeThatHasBeenToTheDatabaseStillCountsAsItsVillage() {
		Vector3f asStored = new Vector3f(268.25f, 2730.25f, 270.65f); // what the pool reads back for VILLAGE
		assertNotEquals(VILLAGE, asStored, "the two are genuinely different floats, which is the whole trap");

		List<BotRoster.Resident> residents = new ArrayList<>();
		residents.add(resident(1, asStored));
		Difference difference = sort(awake(1), residents, villages(VILLAGE, 1), field(GROUND_A));

		assertEquals(1, difference.villagersAwake(), "it lives in the village, whatever its last bit says");
		assertEquals(0, difference.huntersAwake());
		assertTrue(difference.toSleep().isEmpty(), "so the post is held, not emptied");
	}

	private static Difference sort(IntPredicate isAwake, List<BotRoster.Resident> residents, Map<Vector3f, Integer> villages,
		List<Settlement> fieldPlan) {
		return BotDirector.sort(isAwake, SETTLEMENTS, post -> null, residents, villages, fieldPlan);
	}

	private static BotRoster.Resident resident(int id, Vector3f home) {
		return new BotRoster.Resident(id, "Bot" + id, 210030000, home, null);
	}

	private static IntPredicate awake(int... ids) {
		Set<Integer> up = new java.util.HashSet<>();
		for (int id : ids)
			up.add(id);
		return up::contains;
	}

	private static Map<Vector3f, Integer> villages(Vector3f centre, int wanted) {
		Map<Vector3f, Integer> posts = new LinkedHashMap<>();
		posts.put(centre, wanted);
		return posts;
	}

	private static List<Settlement> field(Vector3f... grounds) {
		List<Settlement> plan = new ArrayList<>();
		for (Vector3f ground : grounds)
			plan.add(new Settlement(ground, 20f, 4, 20, Race.ELYOS));
		return plan;
	}
}
