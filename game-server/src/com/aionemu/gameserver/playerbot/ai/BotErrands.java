package com.aionemu.gameserver.playerbot.ai;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.playerbot.combat.BotLootManager;
import com.aionemu.gameserver.playerbot.economy.BotEquipManager;
import com.aionemu.gameserver.playerbot.economy.BotOutfitter;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.BotVisitors;
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
	/** Where round a keeper to look for floor that leads back, nearest first and all inside trading reach. */
	private static final float[] KEEPER_SPOT_RADII = { 2, 3.5f, 5, 6.5f };
	private static final int KEEPER_SPOT_DIRECTIONS = 8;
	/** How many route searches that look may cost, once a trip. */
	private static final int KEEPER_SPOT_SEARCHES = 8;
	/** How long the bot leaves its bag alone after a fruitless look. Long enough not to stutter, short enough to notice the next drop. */
	private static final long DRESSING_RETRY_MILLIS = 60000;
	/** How long a shop spot that turned out to be empty is left alone. Long: npcs do not appear and disappear minute by minute. */
	private static final long EMPTY_SHOP_MILLIS = 600000;

	private final PlayerBotAI ai;
	/** How long a bot waits before walking to a shop for flasks again, after one that sold it none. */
	private static final long FLASK_TRIP_COOLDOWN_MILLIS = 600000;
	/**
	 * How long a bot leaves shops alone after a walk that could not be planned. Shorter than {@link #EMPTY_SHOP_MILLIS}: a shop spot that is wrong
	 * stays wrong, whereas a route may be refused because of where the bot happens to be standing, and it moves.
	 */
	private static final long UNREACHABLE_SHOP_COOLDOWN_MILLIS = 120000;

	/** A corpse the bot means to walk back to, set when a fight ends over something it may loot. */
	private volatile int pendingCorpse;
	/** When the bot may next bother with its bag, after finding nothing in it that it could actually put on. */
	private volatile long dressingIdleUntil;
	/** The level the bot's wings were last brought up to, so that they are looked at again once a level is gained, and not on every tick. */
	private int wingsCheckedAtLevel;
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
	/** The keeper {@link #keeperSpot} was chosen for this trip, 0 before one is in sight. */
	private volatile int keeperSpotOf;
	/** Where the bot stands to trade with {@link #keeperSpotOf}, null when nothing better than the line towards it was found. */
	private volatile Vector3f keeperSpot;

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
			// the move alone, which asks whether it is blocked once the journey is this one: see the shop trip below
			if (!ai.tryMoveTo(moveController, corpse.getX(), corpse.getY(), corpse.getZ())) {
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
		// Never a player's own character: its bag is its owner's, and everything below touches it. Dressing put the owner's stored pieces on in
		// place of what they had chosen, read the unidentified ones (which rolls their bonuses), and said yes to binding, which turns a piece that
		// could be traded into one that never can.
		if (ai.isOwned())
			return false;
		Player bot = ai.getOwner();
		if (BotEquipManager.isBusyDressing(bot))
			return true;
		// A group member dresses only once the weapon is away by itself, which the calm after a group's fights does, and never in an instance: what
		// drops there seldom beats what it wears, and five seconds of standing still with the weapon away is five seconds the group goes without it.
		if (bot.getCurrentTeam() != null && (bot.isInInstance() || bot.isInState(CreatureState.WEAPON_EQUIPPED)))
			return false;
		if (bot.getLevel() != wingsCheckedAtLevel && !bot.isFlying()) {
			wingsCheckedAtLevel = bot.getLevel();
			if (BotOutfitter.renewWings(bot))
				log.info("Bot {} wears new wings for level {}", bot.getName(), bot.getLevel());
		}
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
		if (ai.isOwned())
			return false; // see runVendorTrip: a companion's bag is never sold from, even on demand
		Player bot = ai.getOwner();
		if (!BotVendorManager.hasJunk(bot))
			return false;
		Vector3f vendor = BotVendorManager.findVendor(bot, false, this::isIgnoredVendor, this::rememberUnreachableVendor);
		if (vendor == null)
			return false;
		vendorDestination = vendor;
		sellingOnDemand = true; // sees the errand through: a shop spot with nobody on it is a detour, not an answer
		return true;
	}

	/** @return true if the bot wants flasks and is not serving out a wait from a shop that had none for it. */
	private boolean wantsFlasks(Player bot) {
		return System.currentTimeMillis() >= noFlasksUntil && BotVendorManager.needsPotions(bot);
	}

	/**
	 * Walks a full bag to the nearest shop and sells what the bot has no use for, then lets it drift back to its anchor on its own.
	 *
	 * @return true while a trip is starting or under way, so nothing else is decided this tick.
	 */
	boolean runVendorTrip() {
		// Never a player's own character. A full bag sent it to sell everything its class can never wear, whatever the quality: a chanter of 65 sold
		// the 15 to 20 orange and yellow pieces of cloth and plate its owner kept in its bag for other characters, as reported from in game. What a
		// companion carries is its owner's to keep, sell or throw away, and the kinah its flasks would cost is theirs too.
		if (ai.isOwned())
			return false;
		Player bot = ai.getOwner();
		// Nor a visitor to a capital. Each arrives with a hunter's full bag, so the fifteen of them set off for the same shop at once, 500 m from the
		// teleporter in Pandaemonium, and stood waiting minutes for their routes, looking lost. It sells at home; in town the day takes it to shops.
		if (BotVisitors.isVisitor(bot))
			return false;
		if (vendorDestination == null) {
			// a full bag alone is not enough: one full of gear, quest items or anything rare never empties and would loop forever. An operator who
			// asked for a trip has already made that judgement, and keeps the bot going until it has actually sold: without this, a first shop spot
			// that turns out to be empty ends the errand here, since the bag it was never waiting for is still not full.
			// Or because it has run out of flasks, which is a reason to walk to a shop in its own right: a bot with a tidy bag and no mana potion
			// left would otherwise never go, and fight on empty until something killed it.
			// A trip that the map has just refused is not worth announcing again on the next tick. Without this the bot stood where its route ran
			// out, declared a trip and abandoned it once per tick for as long as there were shop spots left to burn -- measured at 191 departures
			// against 16 sales, and seen in game as a knot of bots at the citadel steps doing nothing at all.
			boolean sells = BotVendorManager.hasFullBag(bot) && BotVendorManager.hasJunk(bot);
			if (!sellingOnDemand && (System.currentTimeMillis() < noShopUntil || !wantsFlasks(bot) && !sells))
				return false;
			vendorDestination = BotVendorManager.findVendor(bot, !sellingOnDemand && !sells, this::isIgnoredVendor, this::rememberUnreachableVendor);
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
			keeperSpotOf = 0; // chosen again from where this trip starts
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
			// And one that sold it some is not either: a bot whose purchase still left it wanting flasks turned round on the spot and bought again,
			// once a second, ten times in a minute in Cygnea on 18 October.
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
		// Sold to whoever is in reach, as above, but walked to the keeper this trip was chosen for. The nearest one in sight was walked to instead:
		// in Pandaemonium a bot chose a shop it could reach, walked towards a nearer keeper it could not, wrote the chosen shop off, and did that
		// to every shop of the street in turn, a trip every two minutes and no sale. Trips given up fell from 269 to 91 in 23 minutes on 18 October.
		vendor = BotVendorManager.findKnownVendorNear(bot, vendorDestination, VENDOR_SPOT_TOLERANCE);
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
		// The walkable ground nearest the keeper's feet, at its own height, when it is within trading reach of it. A point on the line between bot
		// and keeper is at the bot's height: in Teminon the keepers stand a floor below the deck, the bot came within three metres across and seven
		// above, out of reach, and then aimed at the keeper's feet and was refused, 89 of the base's 93 refused routes in half an hour.
		Vector3f besideKeeper = vendor == null ? null : spotBeside(bot, vendor);
		if (besideKeeper != null && PositionUtil.getDistance(besideKeeper.x, besideKeeper.y, besideKeeper.z, x, y, z) < BotVendorManager.TRADE_RANGE - 1) {
			x = besideKeeper.x;
			y = besideKeeper.y;
			z = besideKeeper.z;
		} else if (vendor != null) {
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
		// The move alone, which asks whether it is blocked once the journey is this one. Asked first, the flag could still hold the refusal of the last
		// journey, which only a new one clears, and turn a shop down without a route being searched.
		if (!ai.tryMoveTo(moveController, x, y, z)) {
			log.info("Bot {} cannot reach a shop and gives up selling", bot.getName());
			ai.destinationRefused();
			noShopUntil = System.currentTimeMillis() + UNREACHABLE_SHOP_COOLDOWN_MILLIS;
			// remembered, exactly as an empty spot is. Forgetting it only meant the next trip chose the same nearest shop and failed the same way,
			// which is how one unreachable spot produced thousands of trips and no sales.
			ignoredVendors.put(vendorDestination, System.currentTimeMillis() + EMPTY_SHOP_MILLIS);
			BotVendorManager.tripGivenUp(bot, vendorDestination);
			vendorDestination = null;
			sellingOnDemand = false;
			return false;
		}
		return true;
	}

	/**
	 * The walkable ground nearest a keeper's feet that the bot can walk back to its anchor from, chosen once a trip: the move is asked again every tick.
	 * <p>
	 * The keeper's own floor is not always that ground. Behind a counter there is floor enough for the keeper and nothing joins it to the shop: a bot
	 * sent there walked the last metres straight through the counter, could not plan a single route out, and sat down and stood up every thirteen
	 * seconds beside Drasia in the Verteron citadel, its rescue held back by the player watching it. A strip of floor larger than a pocket does the same
	 * once the mesh keeps a body's width from walls, and the walk there can be one way: on 18 October a Pandaemonium keeper's floor was a step down
	 * a bot took and could not climb back, and each of its customers was rescued three metres away after every sale. Such rescues had doubled across
	 * the maps. So the question is the way back, asked once a trip with a real search: the coarse grid carries every floor of its 4 m cells and said
	 * yes to that floor.
	 *
	 * @return The spot, or null when nothing near the keeper leads back to the bot's anchor.
	 */
	private Vector3f spotBeside(Player bot, Npc keeper) {
		if (keeperSpotOf == keeper.getObjectId())
			return keeperSpot;
		NavmeshService mesh = NavmeshService.getInstance();
		int worldId = bot.getWorldId();
		// back to the anchor, which is where the bot goes once it has sold and what the rescue judges it by. Back to where the bot stood when it first saw
		// the keeper was asked first, and from the floor that traps it a search to there ran out of budget rather than prove anything
		Vector3f from = ai.anchor();
		Vector3f spot = mesh.groundNear(worldId, keeper.getX(), keeper.getY(), keeper.getZ());
		if (spot != null && (mesh.isPocket(worldId, spot.x, spot.y, spot.z) || !leadsBack(mesh, worldId, spot, from))) {
			spot = null;
			int searches = 0;
			search: for (float radius : KEEPER_SPOT_RADII) {
				for (int k = 0; k < KEEPER_SPOT_DIRECTIONS; k++) {
					double angle = Math.PI * 2 * k / KEEPER_SPOT_DIRECTIONS;
					float x = keeper.getX() + (float) Math.cos(angle) * radius, y = keeper.getY() + (float) Math.sin(angle) * radius;
					for (Vector3f floor : mesh.floorsAt(worldId, x, y, keeper.getZ())) {
						if (Math.abs(floor.z - keeper.getZ()) > BotVendorManager.TRADE_RANGE - 1)
							break; // nearest first: the rest are further off still
						if (searches++ >= KEEPER_SPOT_SEARCHES)
							break search;
						if (leadsBack(mesh, worldId, floor, from)) {
							spot = floor;
							break search;
						}
					}
				}
			}
		}
		keeperSpot = spot;
		keeperSpotOf = keeper.getObjectId();
		return spot;
	}

	/** @return false only when the mesh proves there is no walk from the spot back to {@code to}: a search that ran out of budget does not count against it. */
	private static boolean leadsBack(NavmeshService mesh, int worldId, Vector3f spot, Vector3f to) {
		return mesh.isNotProvedOutOfReach(worldId, spot.x, spot.y, spot.z, to.x, to.y, to.z);
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
