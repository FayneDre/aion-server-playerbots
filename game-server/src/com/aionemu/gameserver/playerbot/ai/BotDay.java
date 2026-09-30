package com.aionemu.gameserver.playerbot.ai;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * What a resident does with its day.
 * <p>
 * The fork was built to populate a world and produced machines for killing things, because farming was the whole of a bot's life. A resident draws an
 * occupation with a duration instead, and draws again when it runs out — working its ground, standing about where it lives, or walking to another
 * settlement. Adventurers have no day: they hunt, which is how they level.
 * <p>
 * Everything here ends in the bot's anchor, which is deliberate. Walking back, breaking off a chase that strays, settling down on arrival — all of it
 * is already written against the anchor, so moving the anchor is the whole of "go and be somewhere", and no second set of movement rules has to be
 * kept in step with the first.
 */
class BotDay {

	private static final Logger log = LoggerFactory.getLogger(BotDay.class);

	/** How far from its place a bot will stand about, so a village square is people spread over it rather than a stack on one point. */
	private static final float LOITERING_SPREAD = 14f;
	/** How many spots to try before settling for the place itself. */
	private static final int RESTING_SPOTS = 6;
	/** How close to somebody else a bot may stop. Bots pass through npcs, so nothing but this keeps one from halting inside a blacksmith. */
	private static final float PERSONAL_SPACE = 2.5f;

	private final PlayerBotAI ai;
	/** What the bot is doing with its day, and until when. */
	private volatile Occupation occupation = Occupation.FARMING;
	private volatile long occupationUntil;
	/** Where the current occupation is taking the bot, so it keeps going to the same place rather than re-choosing every tick. */
	private volatile Vector3f occupationDestination;
	/** Where this bot lives, read once and kept. */
	private volatile Vector3f home;
	/** Places this bot has proved it cannot get to, so it stops choosing them. */
	private final Set<Vector3f> unreachable = ConcurrentHashMap.newKeySet();

	BotDay(PlayerBotAI ai) {
		this.ai = ai;
	}

	/**
	 * Keeps the day going: renews the occupation when its time runs out, and carries out the ones that are not hunting.
	 * <p>
	 * Whether this bot has a day at all is read from {@code noExp} rather than from the stored roster, because that flag is already on the character
	 * and says the same thing: a bot that gains no experience is one that belongs to a place. An adventurer always falls through to hunting.
	 *
	 * @return true if the occupation took this tick, false to go hunting as before.
	 */
	boolean pursue() {
		Player bot = ai.getOwner();
		if (!bot.getCommonData().getNoExp())
			return false;
		long now = System.currentTimeMillis();
		if (now > occupationUntil) {
			// whether this bot lives among people decides how much of its day is spent standing about: a village square wants idlers, a hillside does
			// not, and it was the same draw for both that filled the countryside with characters doing nothing
			boolean inTown = BotPlaces.isSettlement(bot.getWorldId(), home());
			occupation = Occupation.drawFor(bot.getObjectId(), inTown);
			occupationUntil = now + occupation.draw(inTown);
			occupationDestination = null;
			log.info("Bot {} takes to {}", bot.getName(), occupation);
		}
		return switch (occupation) {
			case FARMING -> false;
			case LOITERING -> loiter();
			case WANDERING -> wander();
		};
	}

	/**
	 * Where this bot lives.
	 * <p>
	 * Read from the roster rather than worked out from the map: it is where the bot was actually put down, and that is a fact about the bot, not a
	 * function of the map's list of villages. Falls back to where it stands, which is what a bot made before homes were recorded has.
	 */
	Vector3f home() {
		if (home == null)
			home = BotRoster.homeOf(ai.getOwner().getName());
		if (home == null)
			home = ai.anchor();
		return home;
	}

	/** Drops the current occupation, for when it was decided from a place the bot is no longer in. */
	void forget() {
		occupationUntil = 0;
		occupationDestination = null;
	}

	/**
	 * Stands about where the bot lives. Doing nothing is a thing people do, and it is most of what makes a village look inhabited.
	 */
	private boolean loiter() {
		if (occupationDestination == null) {
			// A spot of its own a few paces from where it lives, not the place itself. Sending every idler to one point put them inside each other
			// and inside the npcs standing there — a village square of bots occupying the same square metre. And loitering happens at home rather
			// than at the nearest village: villagers live in villages, so they are the ones who fill them, while everyone within walking distance
			// converging on the same square is what made Akarios a crowd.
			Vector3f where = home();
			occupationDestination = where == null ? null : restingSpot(where);
		}
		if (occupationDestination == null)
			return false; // nowhere of its own to stand about, so it works its ground instead
		ai.setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		if (!ai.returnToAnchor())
			return giveUp();
		return true;
	}

	/** Walks to another settlement, and looks for something else to do once it arrives rather than waiting out the clock. */
	private boolean wander() {
		Player bot = ai.getOwner();
		if (occupationDestination == null) {
			// somewhere near home and fit for its level, rather than any settlement on the map: the walk itself crosses everything in between, and
			// that is how a character of two came to be standing in a forest of eights
			Vector3f elsewhere = BotPlaces.placeToVisit(bot.getWorldId(), home(), bot.getLevel(), bot.getObjectId() + (int) occupationUntil);
			// a place already known to be out of reach is no destination at all. Nowhere suitable nearby means staying put rather than setting off
			// across the region: the fallback used to be "any settlement", which is exactly the walk this is meant to prevent.
			occupationDestination = elsewhere != null && !unreachable.contains(elsewhere) ? elsewhere : null;
		}
		if (occupationDestination == null)
			return false;
		ai.setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		if (ai.isAtAnchor()) {
			occupationUntil = 0; // arrived: something else next tick
			return true;
		}
		if (!ai.returnToAnchor())
			return giveUp();
		return true;
	}

	/**
	 * Finds this bot its own place to stand about in, a few paces from the given spot.
	 * <p>
	 * Fixed to the bot's id, so it goes back to the same corner rather than picking a new one every time and shuffling about. Ground the mesh
	 * accepts, and clear of the npcs who are already standing there: a bot has no collision with them, so it walks into a blacksmith and stops
	 * inside him, which is the one thing that reads as broken from across a square.
	 *
	 * @return Somewhere to idle, or the spot itself if nothing better was found.
	 */
	private Vector3f restingSpot(Vector3f place) {
		Player bot = ai.getOwner();
		for (int attempt = 0; attempt < RESTING_SPOTS; attempt++) {
			double angle = Math.PI * 2 * Math.floorMod(Integer.hashCode(bot.getObjectId() * 0x9E3779B9) + attempt * 37, 360) / 360;
			float reach = LOITERING_SPREAD * (0.4f + 0.6f * (Math.floorMod(bot.getObjectId() + attempt, 10) / 10f));
			float x = place.getX() + (float) Math.cos(angle) * reach, y = place.getY() + (float) Math.sin(angle) * reach;
			Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), x, y, place.getZ());
			// standable, unoccupied, and joined to where the bot is standing. The third is not optional and leaving it out here cost a bot twenty one
			// refusals in four minutes for a corner ten metres away: a low wall or a ledge makes perfectly good ground that cannot be walked to.
			if (ground != null && !isCrowded(ground) && NavmeshService.getInstance().canReach(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ(),
				ground.getX(), ground.getY(), ground.getZ()))
				return ground;
		}
		return place;
	}

	/** @return true if somebody is already standing there. An npc a bot can walk through is still somebody, as far as anyone watching is concerned. */
	private boolean isCrowded(Vector3f spot) {
		boolean[] taken = { false };
		ai.getOwner().getKnownList().forEachNpc(npc -> {
			if (PositionUtil.getDistance(npc.getX(), npc.getY(), spot.getX(), spot.getY()) < PERSONAL_SPACE)
				taken[0] = true;
		});
		return taken[0];
	}

	/**
	 * Drops what the bot was doing when the place it was doing it in turns out to be unreachable, so something else is drawn on the next tick.
	 * <p>
	 * Without this a bot keeps asking for the same impossible journey for as long as the occupation lasts — several minutes of planning a route that
	 * does not exist, many times a second. The unreachable place stays unreachable, so retrying is not patience, it is a loop.
	 *
	 * @return true, because the occupation did decide what this tick does: nothing.
	 */
	private boolean giveUp() {
		log.info("Bot {} cannot get to where {} would take it, and does something else", ai.getOwner().getName(), occupation);
		// Remembered, not merely abandoned. The place a bot is sent to is chosen the same way every time — the nearest village, the next place along
		// the list — so forgetting a refusal means choosing the same unreachable place on the next tick, for ever. One bot standing in a pocket of
		// the map cut off from the villages asked sixty times in three minutes.
		if (occupationDestination != null)
			unreachable.add(occupationDestination);
		forget();
		return true;
	}
}
