package com.aionemu.gameserver.playerbot.ai;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.playerbot.combat.BotLootManager;
import com.aionemu.gameserver.playerbot.economy.BotEquipManager;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/**
 * The chores between fights: picking up what was killed, putting on what the bag has to offer, and walking a full bag to a shop.
 * <p>
 * They share a shape, which is why they sit together. Each one takes the whole tick when it is under way, each may need the bot on its feet and its
 * weapon away before it can start, and each can be defeated by the map rather than by the bot — a corpse behind a rock, a shop that is not where the
 * spawn data said it would be. So each returns whether it claimed the tick, and gives up in a way that does not come round again immediately.
 */
class BotErrands {

	private static final Logger log = LoggerFactory.getLogger(BotErrands.class);

	/** Close enough to a shop's spawn point that a shop keeper would be in sight if there were one. */
	private static final float VENDOR_SPOT_TOLERANCE = 10f;
	/** How long the bot leaves its bag alone after a fruitless look. Long enough not to stutter, short enough to notice the next drop. */
	private static final long DRESSING_RETRY_MILLIS = 60000;
	/** How long a shop spot that turned out to be empty is left alone. Long: npcs do not appear and disappear minute by minute. */
	private static final long EMPTY_SHOP_MILLIS = 600000;

	private final PlayerBotAI ai;
	/** A corpse the bot means to walk back to, set when a fight ends over something it may loot. */
	/** How long a bot waits before walking to a shop for flasks again, after one that sold it none. */
	private static final long FLASK_TRIP_COOLDOWN_MILLIS = 600000;
	/**
	 * How long a bot leaves shops alone after a walk that could not be planned. Shorter than {@link #EMPTY_SHOP_MILLIS}: a shop spot that is wrong
	 * stays wrong, whereas a route may be refused because of where the bot happens to be standing, and it moves.
	 */
	private static final long UNREACHABLE_SHOP_COOLDOWN_MILLIS = 120000;

	private volatile int pendingCorpse;
	/** When the bot may next bother with its bag, after finding nothing in it that it could actually put on. */
	private volatile long dressingIdleUntil;
	/** Shop spots that turned out to have no shop keeper standing on them, so the next trip goes somewhere else. */
	private final Map<Vector3f, Long> ignoredVendors = new ConcurrentHashMap<>();
	/**
	 * Shop spots the mesh has proved this bot cannot walk to.
	 * <p>
	 * Kept without expiry, unlike {@link #ignoredVendors}: a shop that stood empty may be served again within the hour, but ground that does not
	 * connect to other ground does not change its mind. Forgetting these on a timer only bought back the sweep they were written down to stop.
	 */
	private final Set<Vector3f> unreachableVendors = ConcurrentHashMap.newKeySet();
	/** The map {@link #unreachableVendors} was gathered on, so a bot that moves house does not carry another map's refusals to its new one. */
	private volatile int unreachableOn;
	/** Where the bot is headed to sell, non null only while a trip is in progress. */
	private volatile Vector3f vendorDestination;
	/** Set when an operator asked for a trip, which waives the full bag test for the whole errand rather than only to start it. */
	private volatile boolean sellingOnDemand;
	/** Until when this bot stops making the walk for flasks, after a shop that had none to sell it. */
	private volatile long noFlasksUntil;
	/** Until when this bot stops setting out for a shop at all, after a walk the map refused. */
	private volatile long noShopUntil;

	BotErrands(PlayerBotAI ai) {
		this.ai = ai;
	}

	/** Remembers a corpse worth going back to, which the decision tick then walks to and picks up. */
	void rememberCorpse(int objectId) {
		pendingCorpse = objectId;
	}

	/**
	 * Walks back to what the bot killed and takes what it may.
	 *
	 * @return true if the bot is busy with a corpse, in which case everything else can wait.
	 */
	boolean collectLoot() {
		Player bot = ai.getOwner();
		int corpseId = pendingCorpse;
		if (corpseId == 0)
			return false;

		VisibleObject corpse = World.getInstance().findVisibleObject(corpseId);
		if (corpse == null || !BotLootManager.hasLootFor(bot, corpseId)) { // decayed, empty, or nothing the bot may take
			pendingCorpse = 0;
			return false;
		}
		if (ai.posture().standUp())
			return true; // on its feet first, so it does not slide to the corpse

		if (PositionUtil.getDistance(bot, corpse) > BotLootManager.LOOT_RANGE) {
			BotMoveController moveController = ai.moves();
			if (moveController == null)
				return false;
			if (moveController.isInMove())
				return true;
			if (moveController.isBlocked() || !ai.tryMoveTo(moveController, corpse.getX(), corpse.getY(), corpse.getZ())) {
				log.info("Bot {} cannot reach the corpse it wanted to loot", bot.getName());
				pendingCorpse = 0;
				return false;
			}
			return true;
		}

		int looted = BotLootManager.lootAll(bot, corpseId);
		pendingCorpse = 0;
		log.info("Bot {} looted {} item(s)", bot.getName(), looted);
		return false;
	}

	/**
	 * Puts on what the bag has to offer.
	 * <p>
	 * Reading a piece and binding one each take five seconds of standing still with the weapon away — binding refuses outright otherwise, and both
	 * are undone by a single step or blow. Waiting for that to happen by chance never worked: a bot that farms is in its weapon stance almost always,
	 * and the few idle moments were spent walking. So the bot commits to it instead, the way a player does: it stops, puts the weapon away, and
	 * holds its tick until the piece is on.
	 *
	 * @return true if the bot is busy dressing, in which case it does nothing else this tick.
	 */
	boolean dressUp() {
		Player bot = ai.getOwner();
		if (BotEquipManager.isBusyDressing(bot))
			return true;
		// A piece can score higher and still be unwearable for good — a mace a priest has no mastery for, a piece for the other race — and nothing
		// short of trying says so. Without this the bot would stop and sheathe on every tick of its life for one such item sitting in its bag.
		if (System.currentTimeMillis() < dressingIdleUntil || !BotEquipManager.hasUpgradeWaiting(bot))
			return false;
		ai.stopMoving();
		ai.posture().sheathe();
		if (ai.posture().standUp() || !CreatureState.isStanding(bot.getState()))
			return true; // on its feet first, and the weapon is away as of this tick
		if (BotEquipManager.equipUpgrades(bot) || BotEquipManager.identifyUpgrade(bot))
			return true;
		dressingIdleUntil = System.currentTimeMillis() + DRESSING_RETRY_MILLIS;
		return false;
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check — for testing the trip without farming a bag full first.
	 *
	 * @return false if there is nothing worth selling, or the bot's map has no shop to walk to.
	 */
	boolean forceSellTrip() {
		Player bot = ai.getOwner();
		if (!BotVendorManager.hasJunk(bot))
			return false;
		Vector3f vendor = BotVendorManager.findVendor(bot, this::isIgnoredVendor, this::rememberUnreachableVendor);
		if (vendor == null)
			return false;
		vendorDestination = vendor;
		sellingOnDemand = true; // sees the errand through: a shop spot with nobody on it is a detour, not an answer
		return true;
	}

	/**
	 * Walks a full bag to the nearest shop and sells what the bot has no use for, then lets it drift back to its anchor on its own.
	 *
	 * @return true while a trip is starting or under way, so nothing else is decided this tick.
	 */
	/** @return true if the bot wants flasks and is not serving out a wait from a shop that had none for it. */
	private boolean wantsFlasks(Player bot) {
		return System.currentTimeMillis() >= noFlasksUntil && BotVendorManager.needsPotions(bot);
	}

	boolean runVendorTrip() {
		Player bot = ai.getOwner();
		if (vendorDestination == null) {
			// a full bag alone is not enough: one full of gear, quest items or anything rare never empties and would loop forever. An operator who
			// asked for a trip has already made that judgement, and keeps the bot going until it has actually sold: without this, a first shop spot
			// that turns out to be empty ends the errand here, since the bag it was never waiting for is still not full.
			// Or because it has run out of flasks, which is a reason to walk to a shop in its own right: a bot with a tidy bag and no mana potion
			// left would otherwise never go, and fight on empty until something killed it.
			// A trip that the map has just refused is not worth announcing again on the next tick. Without this the bot stood where its route ran
			// out, declared a trip and abandoned it once per tick for as long as there were shop spots left to burn -- measured at 191 departures
			// against 16 sales, and seen in game as a knot of bots at the citadel steps doing nothing at all.
			if (!sellingOnDemand && (System.currentTimeMillis() < noShopUntil
				|| !wantsFlasks(bot) && (!BotVendorManager.hasFullBag(bot) || !BotVendorManager.hasJunk(bot))))
				return false;
			vendorDestination = BotVendorManager.findVendor(bot, this::isIgnoredVendor, this::rememberUnreachableVendor);
			if (vendorDestination == null) {
				// Nothing was armed here at first, and it was the dearest line of the errand. The gate above lets the bot through on every tick for
				// as long as it wants flasks or carries junk, so a bot with no shop within reach ran the whole look -- a path search per shop -- once
				// per tick, for ever. The two failures that do arm a wait are both further down, past the look that had already been paid for.
				noShopUntil = System.currentTimeMillis() + UNREACHABLE_SHOP_COOLDOWN_MILLIS;
				// The flask errand needs its own wait, because it has its own gate: a bot that wants flasks and can reach no shop would otherwise
				// come straight back through the door above the moment it opened, having learnt nothing in the meantime.
				noFlasksUntil = System.currentTimeMillis() + FLASK_TRIP_COOLDOWN_MILLIS;
				sellingOnDemand = false;
				return false;
			}
			log.info("Bot {} heads to a shop to sell", bot.getName());
		}

		Npc vendor = BotVendorManager.findKnownVendor(bot);
		if (vendor != null && BotVendorManager.isWithinTradeRange(bot, vendor)) {
			int sold = BotVendorManager.sellJunk(bot, vendor);
			// Selling first, and not only for the kinah: buying needs a free slot per kind, and a bag full enough to bring the bot here has none.
			int bought = BotVendorManager.buyPotions(bot, vendor);
			// A shop that sold the bot nothing is one it must not walk straight back to. Not every vendor stocks flasks, and the bot still wants them
			// when it gets home, so without this it turned round and came again -- measured at 733 fruitless trips against 91 that bought something,
			// from 37 bots, inside two minutes. The wait is per bot rather than per shop: which shop was wrong is not the useful question when the
			// answer may equally be that the purse is empty.
			if (bought == 0)
				noFlasksUntil = System.currentTimeMillis() + FLASK_TRIP_COOLDOWN_MILLIS;
			if (sold > 0 || bought > 0)
				log.info("Bot {} sold {} stack(s) and bought {} kind(s) of flask", bot.getName(), sold, bought);
			vendorDestination = null;
			sellingOnDemand = false;
			return false; // roams or fights again from here, and drifts back to its anchor like after any other trip
		}

		if (ai.posture().standUp())
			return true; // on its feet first, so it does not slide off
		BotMoveController moveController = ai.moves();
		if (moveController == null)
			return false;
		// Nothing in sight buys, and the bot is standing where the map said a shop would be. Before this, it re-issued a move to the spot it was
		// already on, arrived instantly, found nothing again, and did that for ever — a bot frozen at a shop that never was. The spot is dropped
		// instead, and the next trip goes to the next nearest one.
		if (vendor == null && PositionUtil.getDistance(bot.getX(), bot.getY(), vendorDestination.x, vendorDestination.y) <= VENDOR_SPOT_TOLERANCE) {
			log.info("Bot {} found no shop keeper where one was expected, trying another", bot.getName());
			ignoredVendors.put(vendorDestination, System.currentTimeMillis() + EMPTY_SHOP_MILLIS);
			vendorDestination = null;
			return false;
		}
		// walk to the shop keeper itself when one is in sight, and only to the recorded spot while none is
		float x = vendor != null ? vendor.getX() : vendorDestination.x;
		float y = vendor != null ? vendor.getY() : vendorDestination.y;
		float z = vendor != null ? vendor.getZ() : vendorDestination.z;
		if (vendor != null) {
			// Stop short of the keeper rather than aiming at its feet. Shop keepers stand on counters, podiums and shop fronts, and the mesh does not
			// always carry a polygon under one: the route to the exact spot is then refused although the bot only ever needed to be near it. Trading
			// happens anywhere inside TRADE_RANGE, so the destination is a point on the line between them, just inside it.
			double distance = PositionUtil.getDistance(bot.getX(), bot.getY(), x, y);
			if (distance > BotVendorManager.TRADE_RANGE) {
				float fraction = (float) ((distance - BotVendorManager.TRADE_RANGE + 1) / distance);
				x = bot.getX() + (x - bot.getX()) * fraction;
				y = bot.getY() + (y - bot.getY()) * fraction;
				z = bot.getZ() + (z - bot.getZ()) * fraction;
			}
		}
		if (moveController.isBlocked() || !ai.tryMoveTo(moveController, x, y, z)) {
			log.info("Bot {} cannot reach a shop and gives up selling", bot.getName());
			noShopUntil = System.currentTimeMillis() + UNREACHABLE_SHOP_COOLDOWN_MILLIS;
			// remembered, exactly as an empty spot is. Forgetting it only meant the next trip chose the same nearest shop and failed the same way,
			// which is how one unreachable spot produced thousands of trips and no sales.
			ignoredVendors.put(vendorDestination, System.currentTimeMillis() + EMPTY_SHOP_MILLIS);
			vendorDestination = null;
			sellingOnDemand = false;
			return false;
		}
		return true;
	}

	/**
	 * @return true if this spot, or one close enough to share its fate, has already defeated the bot.
	 *         <p>
	 *         By neighbourhood and not by point, because shops come in rows: a dozen keepers of the citadel stand within a few metres of each other,
	 *         and {@code BotMoveController} already refuses them all as one journey once it has refused any of them. Matching exactly meant the bot
	 *         set out for each in turn and was refused instantly each time.
	 */
	private boolean isIgnoredVendor(Vector3f spot) {
		long now = System.currentTimeMillis();
		ignoredVendors.values().removeIf(until -> until <= now);
		return isNear(ignoredVendors.keySet(), spot) || isNear(unreachableVendors, spot);
	}

	private static boolean isNear(Collection<Vector3f> known, Vector3f spot) {
		return known.stream().anyMatch(spot2 -> PositionUtil.getDistance(spot2.x, spot2.y, spot.x, spot.y) <= VENDOR_SPOT_TOLERANCE);
	}

	/**
	 * Writes down a shop the mesh proved this bot cannot walk to, so the next look spends its budget on shops it has not tried yet.
	 * <p>
	 * This is what makes a capped look safe: the cap would otherwise hide the fourth nearest shop for ever from a bot whose first three are walled
	 * off. With the refusals remembered, each look tests three <em>fresh</em> shops, so the map is explored in full a few at a time and the cost per
	 * tick stays bounded.
	 */
	private void rememberUnreachableVendor(Vector3f spot) {
		int worldId = ai.getOwner().getWorldId();
		if (unreachableOn != worldId) {
			unreachableVendors.clear();
			unreachableOn = worldId;
		}
		unreachableVendors.add(spot);
	}
}
