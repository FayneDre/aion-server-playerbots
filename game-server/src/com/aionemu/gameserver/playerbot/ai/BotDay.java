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
import com.aionemu.gameserver.playerbot.world.BotQuestGrounds;
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

	/** How many spots to try before settling for the place itself. More than a handful, since a busy village has most of its corners taken. */
	private static final int RESTING_SPOTS = 12;
	/** How far a bot's level may drift from its home's before it moves house. */
	private static final int HOME_LEVEL_DRIFT = 2;
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
	 * Every bot has a day. This was once asked of {@code noExp}, on the grounds that a bot which gains no experience is one that belongs to a place —
	 * and that made the flag answer two questions at once, "does not level" and "has a life". The moment levelling was turned on, the whole of the
	 * daily round went silent with it: no loitering, no wandering, and farming no longer moved the anchor out of the village. Measured immediately:
	 * zero occupations drawn where there had been sixty in three minutes.
	 *
	 * @return true if the occupation took this tick, false to go hunting as before.
	 */
	boolean pursue() {
		Player bot = ai.getOwner();
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
			case FARMING -> farm();
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

	/**
	 * Moves a bot out when it has outgrown where it lives.
	 * <p>
	 * Bots grow, and nothing used to follow. Born at the level of Akarios and left there, one reaches seven while still keeping house on ground worth
	 * three, and spends its days beating creatures five levels beneath it in the middle of a beginners' village — seen in game within an hour of
	 * levelling being turned on. Poeta reads as Akarios 3 and its camps at 5, 6 and 7: the valley steepens as you walk away from the village, so
	 * growing up means moving out, and the map already says where to.
	 * <p>
	 * Only the home moves. Everything else — where it stands about, where it hunts, how far it may be dragged — is written against the home or the
	 * anchor, so they follow of their own accord.
	 *
	 * @return true if the bot has just moved house, in which case whatever it was doing was decided somewhere it no longer lives.
	 */
	boolean moveOutIfOutgrown() {
		Player bot = ai.getOwner();
		Vector3f where = home();
		if (where == null || Math.abs(BotPlaces.levelAt(bot.getWorldId(), where) - bot.getLevel()) <= HOME_LEVEL_DRIFT)
			return false;
		Vector3f better = BotPlaces.homeForLevel(bot.getWorldId(), bot.getLevel(), bot.getObjectId());
		if (better == null || better.equals(where))
			return false; // the region has nowhere better; the ceiling in BotPacing is what stops it growing further
		log.info("Bot {} has outgrown its home at level {} and moves to {} {}", bot.getName(), bot.getLevel(), Math.round(better.getX()),
			Math.round(better.getY()));
		home = better;
		BotRoster.setHome(bot.getName(), better);
		ai.setAnchor(better.getX(), better.getY(), better.getZ());
		forget(); // whatever it was doing was chosen from the place it has just left
		return true;
	}

	/** Drops the current occupation, for when it was decided from a place the bot is no longer in. */
	void forget() {
		occupationUntil = 0;
		occupationDestination = null;
	}

	/**
	 * Sends the bot out to work a stretch of country near home.
	 * <p>
	 * It always returns false, because farming is not something this decides for the tick — it only says <i>where</i>. Moving the anchor is the whole
	 * of the instruction: the tick then goes hunting as it always did, and every rule already written against the anchor comes along.
	 * <p>
	 * Without this, farming left the anchor wherever the last occupation had put it, which for a villager is the village. Hunting refuses any target
	 * more than a camp radius from the anchor and there is nothing hostile that close to a village, so a resident of Akarios spent its farming hours
	 * finding nothing and walking back to the well — the one occupation that was meant to take it out of town kept it in.
	 */
	private boolean farm() {
		Player bot = ai.getOwner();
		if (occupationDestination == null) {
			int pick = bot.getObjectId() + (int) occupationUntil;
			// Where the game itself sends a character of this level, taken from the quest data, and only then a hunting ground picked by level. The
			// quests know things a level band cannot: which valley a character of four is meant to be clearing, and which one belongs to eights.
			Vector3f ground = BotQuestGrounds.groundFor(bot.getWorldId(), bot.getRace(), bot.getLevel(), home(), BotPlaces.WANDERING_RANGE, pick);
			if (ground == null || unreachable.contains(ground))
				ground = BotPlaces.groundToWork(bot.getWorldId(), home(), bot.getLevel(), pick);
			occupationDestination = ground != null && !unreachable.contains(ground) ? ground : null;
		}
		if (occupationDestination != null)
			ai.setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		return false; // hunts from here, wherever "here" turned out to be
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
		// the ground the place actually covers, measured from its own occupants. A number invented for this was wrong in both directions at once: a
		// formula gave Akarios 26 m where it measures 42, so its inhabitants stood at twice the density of the npcs the village was built with.
		float spread = BotPlaces.reachAt(bot.getWorldId(), place);
		for (int attempt = 0; attempt < RESTING_SPOTS; attempt++) {
			double angle = Math.PI * 2 * Math.floorMod(Integer.hashCode(bot.getObjectId() * 0x9E3779B9) + attempt * 37, 360) / 360;
			float reach = spread * (0.4f + 0.6f * (Math.floorMod(bot.getObjectId() + attempt, 10) / 10f));
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

	/**
	 * @return true if somebody is already standing there — an npc, another bot, or a real player.
	 *         <p>
	 *         Anything a bot can walk through is still somebody as far as anyone watching is concerned, and bots walk through everything. Only npcs
	 *         were checked at first, which was invisible while a place held one or two inhabitants and is not once it holds twenty: they would have
	 *         picked their corners without reference to each other and stood inside one another in the middle of a village square.
	 */
	private boolean isCrowded(Vector3f spot) {
		boolean[] taken = { false };
		Player bot = ai.getOwner();
		bot.getKnownList().forEachNpc(npc -> {
			if (PositionUtil.getDistance(npc.getX(), npc.getY(), spot.getX(), spot.getY()) < PERSONAL_SPACE)
				taken[0] = true;
		});
		if (taken[0])
			return true;
		bot.getKnownList().forEachPlayer(other -> {
			if (!other.equals(bot) && PositionUtil.getDistance(other.getX(), other.getY(), spot.getX(), spot.getY()) < PERSONAL_SPACE)
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
