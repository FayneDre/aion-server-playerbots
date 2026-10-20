package com.aionemu.gameserver.playerbot.ai;

import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.playerbot.BotRecruiter;
import com.aionemu.gameserver.playerbot.economy.BotStores;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.world.BotPresence;
import com.aionemu.gameserver.playerbot.world.BotQuestGrounds;
import com.aionemu.gameserver.playerbot.world.TownServices;
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
	/** The place {@link #restingSpot} was last worked out for, and its answer. */
	private volatile Vector3f restingFor, restingSpot;
	/** Until when a place that gave no resting spot is not asked again, see {@link #restingSpot}. */
	private volatile long noRestingSpotUntil;
	private static final long NO_RESTING_SPOT_MILLIS = 600000;
	/** How far a bot's level may drift from its home's before it moves house. */
	private static final int HOME_LEVEL_DRIFT = 2;
	/** How close to somebody else a bot may stop. Bots pass through npcs, so nothing but this keeps one from halting inside a blacksmith. */
	private static final float PERSONAL_SPACE = 2.5f;

	/** How many deaths in the window below mean the home is the problem rather than the fight. */
	private static final int DEATHS_BEFORE_MOVING = 3;
	/** Long enough that a bot having a bad evening is not re-housed, short enough to catch a loop. */
	private static final long DEATH_WINDOW_MILLIS = 600000;
	/** How far round a service's keeper a resident on an errand stands, in metres: near enough to be at the counter, not in the keeper's lap. */
	private static final float ERRAND_SPREAD = 6;
	/** How near its spot a resident has to be to sit down there, in metres. */
	private static final float SEATED_REACH = 3;
	/** How near its stall a merchant has to stand to open its store, in metres: stalls are three apart. */
	private static final float STALL_REACH = 1.5f;
	/** How long a merchant turned away from a stall waits before it tries again. */
	private static final long STALL_RETRY_MILLIS = 30 * 60_000L;

	private final PlayerBotAI ai;
	/** What the bot is doing with its day, and until when. */
	private volatile Occupation occupation = Occupation.FARMING;
	/** A town resident's spell of a few minutes, and when it ends: see {@link TownRoutine}. */
	private volatile TownRoutine.Spell spell;
	private volatile long spellUntil;
	/** Whether the spell's every place was refused, so the resident stays where it is until the next one. */
	private volatile boolean spellGivenUp;
	/** Until when a merchant turned away from a stall leaves its store shut. */
	private volatile long stallRefusedUntil;
	private volatile long occupationUntil;
	/** Where the current occupation is taking the bot, so it keeps going to the same place rather than re-choosing every tick. */
	private volatile Vector3f occupationDestination;
	/** Where this bot lives, read once and kept. */
	private volatile Vector3f home;
	/** Places this bot has proved it cannot get to, so it stops choosing them. */
	private final Set<Vector3f> unreachable = ConcurrentHashMap.newKeySet();
	/** The destination whose way there has already been weighed for a flight, so a trip is decided once and not on every tick of the walk. */
	private volatile Vector3f flightConsideredFor;
	/** Deaths since the clock below started, which is how a home proves itself lethal without anybody working out why. */
	private volatile int deathsAtHome;
	private volatile long deathsSince;
	private volatile int strandingsAtHome;
	private volatile long strandingsSince;

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
		if (BotPresence.isTown(bot.getWorldId()))
			return spendTheSpell();
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
	 * Moves a bot out of a home that keeps killing it.
	 * <p>
	 * The backstop under every rule that is supposed to stop this happening in the first place — the level a ground is worth, the fights a bot may
	 * start, the ones it breaks off from. Each of those is a judgement made from data, and a judgement made from data is wrong somewhere. This needs
	 * no theory of why: three deaths inside ten minutes is a place this character cannot live, whatever the reason, and it moves.
	 * <p>
	 * The window matters as much as the count. Three deaths over an evening is a bot playing; three in ten minutes is a loop, which is what was
	 * reported — bots resurrecting at an obelisk, walking back to the ground beside the thing that killed them, and dying again, at nil survival
	 * after five minutes.
	 */
	void recordDeath() {
		long now = System.currentTimeMillis();
		if (now - deathsSince > DEATH_WINDOW_MILLIS) {
			deathsSince = now;
			deathsAtHome = 0;
		}
		if (++deathsAtHome < DEATHS_BEFORE_MOVING || ai.isOwned() || isAwayFromHome())
			return;
		Player bot = ai.getOwner();
		Vector3f elsewhere = BotPlaces.homeForLevel(bot.getWorldId(), bot.getLevel(), bot.getRace(), home(), bot.getObjectId() + (int) now);
		if (elsewhere == null || BotPlaces.isSamePlace(elsewhere, home()))
			return; // nowhere else on this map suits it; the region's ceiling is what stops it growing into trouble
		log.info("Bot {} has died {} times at {} {} and moves to {} {}", bot.getName(), deathsAtHome, Math.round(home().getX()),
			Math.round(home().getY()), Math.round(elsewhere.getX()), Math.round(elsewhere.getY()));
		deathsAtHome = 0;
		moveTo(elsewhere);
	}

	/**
	 * Notes that the bot had to be rescued from where it was standing, and moves it out of a home that keeps producing that.
	 * <p>
	 * The sibling of {@link #recordDeath()}, and for the same reason: the rescue puts a bot back at its home, so a home that cannot be walked out of
	 * makes the rescue feed the fault it exists to cure. One bot was put back five times in two minutes onto the top of a tower — ground the mesh
	 * calls walkable and plans routes across, which the bot could nonetheless not cross a single metre of. There is no theory to have about why a
	 * given spot does that, and none is needed: a home a character has to be rescued from three times is not a home.
	 *
	 * @return The new home when the bot has just moved, so the caller can put it there rather than leave it at the one it is leaving, or null.
	 */
	Vector3f recordStranding() {
		long now = System.currentTimeMillis();
		if (now - strandingsSince > DEATH_WINDOW_MILLIS) {
			strandingsSince = now;
			strandingsAtHome = 0;
		}
		if (++strandingsAtHome < DEATHS_BEFORE_MOVING || ai.isOwned() || home() == null || isAwayFromHome())
			return null;
		Player bot = ai.getOwner();
		Vector3f elsewhere = BotPlaces.homeForLevel(bot.getWorldId(), bot.getLevel(), bot.getRace(), home(), bot.getObjectId() + (int) now);
		if (elsewhere == null || BotPlaces.isSamePlace(elsewhere, home()))
			return null;
		log.info("Bot {} had to be freed {} times at {} {} and moves to {} {}", bot.getName(), strandingsAtHome, Math.round(home().getX()),
			Math.round(home().getY()), Math.round(elsewhere.getX()), Math.round(elsewhere.getY()));
		strandingsAtHome = 0;
		moveTo(elsewhere);
		return elsewhere;
	}

	/**
	 * @return true while the bot is on a map that is not its home's: in an instance, or brought to a player's group from elsewhere. Every rule that moves
	 *         a home asks the map the bot stands on, so asked from there it would write a place of that map as the home of a bot that lives on another.
	 *         Seven recruits had their homes rewritten that way in the second they waited for their invitation, and one was then sent home to a point
	 *         1448 m up in Eltnen's sky every fourteen seconds.
	 */
	private boolean isAwayFromHome() {
		return ai.getOwner().isInInstance() || BotRecruiter.isRecruit(ai.getOwner());
	}

	/** Takes up a new home: everything else a bot does is written against the home or the anchor, so the rest follows on its own. */
	void moveTo(Vector3f elsewhere) {
		home = elsewhere;
		BotRoster.setHome(ai.getOwner().getName(), elsewhere);
		ai.setAnchor(elsewhere.getX(), elsewhere.getY(), elsewhere.getZ());
		forget();
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
		if (ai.isOwned())
			return false; // somebody's own bot lives where they left it; moving it is the world rearranging a character that is not the world's
		Player bot = ai.getOwner();
		if (isAwayFromHome())
			return false;
		Vector3f where = home();
		if (where == null)
			return false;
		// A villager stays put. It lives there because the population director gave it the post, not because the ground suits its level, and a
		// village has no level worth comparing against: the nearest thing BotPlaces can answer for a village square is the rating of the beginners'
		// ground beside it. Left ungated, the two systems moved the same bot back and forth for ever -- the director emptied the countryside into
		// the village posts nobody lived at, and this evicted every one of them on the next tick. Seen in the log as a region asking for arrivals
		// and departures in the same breath, half a minute apart, with the village count never moving off 11 of 25.
		if (BotPlaces.isSettlement(bot.getWorldId(), where))
			return false;
		if (Math.abs(BotPlaces.levelAt(bot.getWorldId(), where) + BotPlaces.levelBonusOf(bot.getWorldId()) - bot.getLevel()) <= HOME_LEVEL_DRIFT)
			return false;
		Vector3f better = BotPlaces.homeForLevel(bot.getWorldId(), bot.getLevel(), bot.getRace(), where, bot.getObjectId());
		if (better == null || BotPlaces.isSamePlace(better, where))
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
		spellUntil = 0;
	}

	/**
	 * A town resident's spell of a few minutes: at home, sitting, on an errand to one of the town's services, or visiting another part of it, as
	 * {@link TownRoutine} has it for this resident at this time of day. Never hunting: a town has nothing to hunt.
	 *
	 * @return true, the spell deciding what this tick does.
	 */
	private boolean spendTheSpell() {
		Player bot = ai.getOwner();
		if (keepsStore())
			return keepTheStall();
		if (bot.hasStore())
			BotStores.close(bot); // the shift is over
		long now = System.currentTimeMillis();
		if (now > spellUntil || spell == null) {
			int second = LocalTime.now().toSecondOfDay();
			spell = TownRoutine.at(bot.getObjectId(), second);
			spellUntil = now + TownRoutine.secondsLeft(bot.getObjectId(), second) * 1000L;
			spellGivenUp = false;
			occupationDestination = null;
			log.info("Bot {} spends the spell {}", bot.getName(),
				spell.service() == null ? spell.activity() : spell.activity() + " at the " + spell.service());
		}
		if (spellGivenUp)
			return true; // refused the way to every place the spell had for it: stays where it is until the next one
		// and where a spell finds nowhere to go, the resident stays where it is: false would send it hunting, in a town with nothing to hunt
		switch (spell.activity()) {
			case HOME -> loiter();
			case SIT -> {
				if (loiter())
					sitOnArrival();
			}
			case ERRAND -> runErrand();
			case VISIT -> wander();
		}
		return true;
	}

	/**
	 * @return true while this resident should be keeping its store: a town's merchant of level 20 and over, on its shift, free of any group, and not
	 *         turned away from a stall within the last half hour. See docs/bot-stores-plan.md.
	 */
	boolean keepsStore() {
		Player bot = ai.getOwner();
		return !ai.isOwned() && bot.getLevel() >= TownRoutine.STORE_LEVEL && TownRoutine.isMerchant(bot.getObjectId())
			&& !bot.isInGroup() && !bot.isInAlliance() && System.currentTimeMillis() >= stallRefusedUntil && BotPresence.isTown(bot.getWorldId())
			&& TownRoutine.onShift(bot.getObjectId(), LocalTime.now().toSecondOfDay());
	}

	/**
	 * Walks the merchant to its stall and opens its store there. Once open, the tick does nothing else until the shift ends ({@code PlayerBotAI}).
	 *
	 * @return true, the shift deciding what this tick does.
	 */
	private boolean keepTheStall() {
		Player bot = ai.getOwner();
		if (bot.hasStore())
			return true;
		Vector3f stall = BotStores.stallFor(bot, this::isCrowded);
		if (stall == null)
			return refuseStall("no stall is free by a broker or a warehouse");
		BotMoveController moves = ai.moves();
		if (moves == null)
			return true;
		ai.setAnchor(stall.getX(), stall.getY(), stall.getZ());
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), stall.getX(), stall.getY()) > STALL_REACH) {
			// its own spot to the metre, not the standing spot round the anchor every other walk ends at: stalls are three metres apart
			if (!moves.isInMove() && !ai.tryMoveTo(moves, stall.getX(), stall.getY(), stall.getZ()))
				return refuseStall("no way to its stall");
			return true;
		}
		if (moves.isInMove() || ai.posture().standUp() || ai.posture().sheathe())
			return true; // the engine opens no store on a body that is moving, seated or holding its weapon
		if (!BotStores.open(bot))
			return refuseStall("nothing to sell");
		return true;
	}

	/** Gives the stall up for half an hour and goes back to the town's spells meanwhile. */
	private boolean refuseStall(String why) {
		log.info("Bot {} keeps no store for now: {}", ai.getOwner().getName(), why);
		BotStores.release(ai.getOwner());
		stallRefusedUntil = System.currentTimeMillis() + STALL_RETRY_MILLIS;
		return true;
	}

	/** @return true while a resident sits out its spell, which the tick leaves alone instead of getting it to its feet. */
	boolean sitsOutTheSpell() {
		return spell != null && spell.activity() == TownRoutine.Activity.SIT && System.currentTimeMillis() < spellUntil
			&& ai.getOwner().isInState(CreatureState.RESTING);
	}

	/** Sits the resident down once it stands at its spot. */
	private void sitOnArrival() {
		Player bot = ai.getOwner();
		Vector3f anchor = ai.anchor();
		BotMoveController moves = ai.moves();
		if (moves != null && !moves.isInMove() && PositionUtil.getDistance(bot.getX(), bot.getY(), anchor.getX(), anchor.getY()) <= SEATED_REACH)
			ai.posture().sitDown();
	}

	/**
	 * Goes to one of the town's services and stands near its keeper for the spell: the shop, the broker, the warehouse, the crafting masters. The
	 * keeper and the spot round it are the resident's own, so the one who keeps the broker's hours stands at the same broker in the same place.
	 */
	private boolean runErrand() {
		Player bot = ai.getOwner();
		if (occupationDestination == null) {
			Vector3f keeper = TownServices.keeperOf(bot, spell.service(), spell.seed());
			Vector3f spot = keeper == null ? null
				: BotPlaces.spotAround(bot.getWorldId(), keeper, ERRAND_SPREAD, RESTING_SPOTS, bot.getObjectId(), this::isCrowded);
			if (spot == null || unreachable.contains(spot)) {
				// no such service on the map, no room by it, or no way there: the spell is spent at home, and said so, or the look round the keeper
				// -- a dozen route searches -- would be made again on every tick of the spell
				spell = new TownRoutine.Spell(TownRoutine.Activity.HOME, null, spell.seed());
				return loiter();
			}
			occupationDestination = spot;
		}
		ai.setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		if (!ai.returnToAnchor())
			return giveUp();
		return true;
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
		if (flightConsideredFor != occupationDestination) {
			flightConsideredFor = occupationDestination;
			// a trip long enough to be worth it, in about half the cases, and only where the air and the flight points allow: every refusal BotFlight
			// can give is a quiet no, and the bot walks as it always did
			float distance = (float) PositionUtil.getDistance(bot.getX(), bot.getY(), occupationDestination.getX(), occupationDestination.getY());
			if (PlayerBotConfig.AMBIENT_FLIGHT && BotFlight.worthFlyingTo(distance, bot.getObjectId() + (int) occupationUntil)) {
				String refusal = BotFlight.cruiseOrWhyNot(bot, occupationDestination);
				if (refusal == null) {
					log.info("Bot {} flies {} m to its destination instead of walking", bot.getName(), Math.round(distance));
					return true;
				}
				// said at info level because this is the line that says why nobody is flying; a trip is weighed once, so it is a few lines a minute. A bot
				// outside every fly zone is the ordinary case, most of the map, and is left at debug
				if (BotFlight.NOT_IN_A_FLY_ZONE.equals(refusal))
					log.debug("Bot {} walks its {} m trip: {}", bot.getName(), Math.round(distance), refusal);
				else
					log.info("Bot {} walks its {} m trip: {}", bot.getName(), Math.round(distance), refusal);
			}
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
		// Kept, because the answer for one bot and one place is always the same: the attempts are drawn from the bot's id. Worked out afresh on every
		// spell of loitering it was up to twelve route searches each time, and the biggest thing on the bots' threads with 800 of them awake.
		Vector3f known = restingSpot;
		if (known != null && restingFor != null && restingFor.equals(place) && !isCrowded(known))
			return known;
		// No answer is kept too, for a while: it cost the most of all, every attempt a search that ran out of its budget. On 18 October one
		// Pandaemonium home gave none, and its residents asked again at every spell of loitering, a two second search every few seconds on the bots' threads.
		if (known == null && place.equals(restingFor) && System.currentTimeMillis() < noRestingSpotUntil)
			return null;
		// the ground the place actually covers, measured from its own occupants. A number invented for this was wrong in both directions at once: a
		// formula gave Akarios 26 m where it measures 42, so its inhabitants stood at twice the density of the npcs the village was built with.
		float spread = BotPlaces.reachAt(bot.getWorldId(), place);
		// null rather than the centre of the place: everybody shares that point, and in a village it is the obelisk. The caller reads null as "no
		// room here, go and work instead", which is the honest answer when a place is full. Only joined to the place, not searched for again from
		// where the bot stands as well: spotAround already asks that of the place, and a bot that cannot walk to its own home is the rescue's business.
		Vector3f found = BotPlaces.spotAround(bot.getWorldId(), place, spread, RESTING_SPOTS, bot.getObjectId(), this::isCrowded);
		restingFor = place;
		restingSpot = found;
		if (found == null)
			noRestingSpotUntil = System.currentTimeMillis() + NO_RESTING_SPOT_MILLIS;
		return found;
	}

	/**
	 * @return true if somebody is already standing there — an npc, another bot, or a real player.
	 *         <p>
	 *         Anything a bot can walk through is still somebody as far as anyone watching is concerned, and bots walk through everything. Only npcs
	 *         were checked at first, which was invisible while a place held one or two inhabitants and is not once it holds twenty: they would have
	 *         picked their corners without reference to each other and stood inside one another in the middle of a village square.
	 */
	boolean isCrowded(Vector3f spot) {
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
	 * Gives up a hunting ground the bot was refused the walk to, and brings the anchor back home.
	 * <p>
	 * Loitering and wandering give up on the first refusal; farming never did, because it only moves the anchor and the hunt walks there. The hunt
	 * was refused tick after tick until the anti-stuck rescue teleported a bot that was not stuck: 1046 of 1352 rescues over two hours with 800 bots
	 * came within half a minute of a refused route to a farming ground. The anchor goes home with it, or the next draw that finds no ground would
	 * leave the bot hunting from the one it was just refused.
	 */
	void anchorRefused() {
		if (occupation != Occupation.FARMING || occupationDestination == null)
			return;
		log.info("Bot {} cannot get to its hunting ground at {} {}, and does something else", ai.getOwner().getName(),
			Math.round(occupationDestination.getX()), Math.round(occupationDestination.getY()));
		unreachable.add(occupationDestination);
		forget();
		Vector3f where = home();
		if (where != null)
			ai.setAnchor(where.getX(), where.getY(), where.getZ());
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
		if (spell != null && BotPresence.isTown(ai.getOwner().getWorldId())) {
			// in town the spell goes on: an errand refused is spent at home, and a home refused where the resident stands
			spellGivenUp = spell.activity() == TownRoutine.Activity.HOME || spell.activity() == TownRoutine.Activity.SIT;
			spell = new TownRoutine.Spell(TownRoutine.Activity.HOME, null, spell.seed());
			occupationDestination = null;
			return true;
		}
		forget();
		return true;
	}
}
