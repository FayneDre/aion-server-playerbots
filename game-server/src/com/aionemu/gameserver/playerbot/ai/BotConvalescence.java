package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.BindPointPosition;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Where a bot spends the soul sickness that follows a death.
 * <p>
 * The penalty takes a large bite out of every stat for minutes, and the bot comes back from the dead at an obelisk. Two things were tried and both
 * failed. Walking straight back to the farm ground put a crippled bot among the mobs that had just killed it, and it died again, in a loop. Waiting
 * where it stood fixed that and filled every obelisk in the region with seated bots. A player does neither: they step away from the plinth and wait
 * somewhere quiet.
 * <p>
 * So the bot gets a spot of its own, a fair way off the obelisk, on ground joined to it, with no hostile creature near enough to notice it and nobody
 * standing on it. Spots are claimed, nine metres apart at the least, so that a crowd of corpses coming back at once spreads out instead of queueing on the same
 * few metres. When the sickness is over the bot goes back to its anchor like any other.
 */
final class BotConvalescence {

	private static final Logger log = LoggerFactory.getLogger(BotConvalescence.class);

	/** How far around the obelisk a spot may be. The nearest third is skipped by {@code BotPlaces.spotAround}, which keeps it off the plinth. */
	private static final float SPREAD = 45f;
	/** How many spots to try. Each costs a path search, and a spot is refused for mobs, for company and for being cut off. */
	private static final int ATTEMPTS = 30;
	/**
	 * How near a hostile creature a spot may be, tried in this order: past the aggro range of nearly everything that walks the open world, sleeping or not;
	 * then a good deal closer; then not at all. The last two are for obelisks that stand among creatures, a fortress or an outpost, where the first finds no
	 * ground at all, and a bot with no spot rests where it stands, which is on the plinth. Seated beside a creature for a minute is better than seated on the
	 * obelisk with everybody else who died in the region.
	 */
	private static final float[] MOB_CLEARANCES = { 25f, 10f, 0f };
	/** How far from its obelisk a bot is still worth walking back to it. Past that the bot stays where it is rather than cross the country sick. */
	private static final float MAX_DISTANCE = 150f;
	/**
	 * How long after finding no spot at all the search is not made again. It is a run of path searches, up to ninety of them, and the decision tick comes round
	 * every second: asked every time, an obelisk with no room costs a bot that many searches a second for as long as it is sick.
	 */
	private static final long RETRY_MILLIS = 5000;

	/**
	 * How far apart two convalescent bots must be, spot to spot. Checking where bots stand is not enough: a crowd that dies together chooses in the same
	 * second, before any of them has walked anywhere, and every one of them found the same ground empty.
	 */
	private static final float SEPARATION = 9f;
	/** The spot each convalescent bot has claimed, by object id, from the moment it is chosen until the bot is done with it. */
	private static final Map<Integer, Vector3f> claimed = new ConcurrentHashMap<>();

	private final Player bot;
	private final Predicate<Vector3f> crowded;
	private BindPointPosition chosenFor;
	private Vector3f spot;
	/** How close a hostile creature the chosen spot allows, which is what it is checked against again later. */
	private float spotClearance;
	/** Whether this death has been written to the log yet: one line each, however many times the decision tick asks. */
	private boolean reported;
	/** Before this, a search that found nothing is not repeated. */
	private long noSpotUntil;

	/**
	 * @param crowded Whether somebody is already standing at a spot, which the bot's day already knows how to answer.
	 */
	BotConvalescence(Player bot, Predicate<Vector3f> crowded) {
		this.bot = bot;
		this.crowded = crowded;
	}

	/**
	 * @return The bot's place to wait, or null when it has none: no obelisk on this map, one too far away, or no room worth the name around it.
	 */
	synchronized Vector3f spot() {
		BindPointPosition bind = bot.getBindPoint();
		if (bind == null || bind.getMapId() != bot.getWorldId())
			return null;
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), bind.getX(), bind.getY()) > MAX_DISTANCE)
			return null;
		// A member of a group waits with its group: the leader's heels are the place, and a bot raised where it fell by a resurrection is nowhere near its
		// obelisk's quiet ground, so sending it there would walk it away from the people who have just raised it.
		if (bot.getCurrentTeam() != null)
			return null;
		if (spot != null && chosenFor == bind && !hostileNear(spot, spotClearance))
			return spot;
		if (spot == null && chosenFor == bind && System.currentTimeMillis() < noSpotUntil)
			return null;
		release();
		chosenFor = bind;
		Vector3f obelisk = new Vector3f(bind.getX(), bind.getY(), bind.getZ());
		// drawn at random rather than from the id: the claims are what keep the bots apart, and an id-fixed spot would be the same few metres for the
		// same bot at every death whatever else stood there
		Vector3f chosen = null;
		float clearance = 0;
		int tier = 0;
		for (; tier < MOB_CLEARANCES.length && chosen == null; tier++) {
			float wanted = MOB_CLEARANCES[tier];
			// being crowded is only a reason to look further at the first try: a second and a third are for places with no room to be picky
			boolean lookAtCrowd = tier == 0;
			chosen = BotPlaces.spotAround(bot.getWorldId(), obelisk, SPREAD, ATTEMPTS, -1,
				ground -> lookAtCrowd && crowded.test(ground) || hostileNear(ground, wanted) || isClaimedNear(ground));
			clearance = wanted;
		}
		if (chosen != null) {
			claimed.put(bot.getObjectId(), chosen);
			spot = chosen;
			spotClearance = clearance;
		} else {
			noSpotUntil = System.currentTimeMillis() + RETRY_MILLIS;
		}
		if (!reported) {
			reported = true;
			if (chosen == null)
				log.info("Bot {} has no place to wait out its sickness around the obelisk at {} {}, and will rest on it", bot.getName(), Math.round(bind.getX()),
					Math.round(bind.getY()));
			else if (tier > 1)
				log.info("Bot {} waits out its sickness at {} m from the obelisk at {} {}, among creatures, having found nothing quieter", bot.getName(),
					Math.round(PositionUtil.getDistance(chosen.getX(), chosen.getY(), bind.getX(), bind.getY())), Math.round(bind.getX()),
					Math.round(bind.getY()));
		}
		return spot;
	}

	/** Forgets the spot, because the sickness is over and the next death should choose again among whatever has changed by then. */
	synchronized void forget() {
		release();
		reported = false;
		noSpotUntil = 0;
	}

	/** Lets go of the spot and its claim to choose again, without forgetting that this death has already been written to the log. */
	private void release() {
		claimed.remove(bot.getObjectId());
		spot = null;
		chosenFor = null;
	}

	private boolean isClaimedNear(Vector3f ground) {
		return claimed.entrySet().stream().anyMatch(entry -> entry.getKey() != bot.getObjectId()
			&& PositionUtil.getDistance(entry.getValue().getX(), entry.getValue().getY(), ground.getX(), ground.getY()) < SEPARATION);
	}

	private boolean hostileNear(Vector3f ground, float clearance) {
		if (clearance <= 0)
			return false;
		boolean[] found = { false };
		bot.getKnownList().forEachNpc(npc -> {
			if (!found[0] && isThreat(npc) && PositionUtil.getDistance(npc.getX(), npc.getY(), ground.getX(), ground.getY()) < clearance)
				found[0] = true;
		});
		return found[0];
	}

	private boolean isThreat(Npc npc) {
		return !npc.isDead() && bot.isEnemy(npc) && !BotTargetSelector.isScenery(npc);
	}
}
