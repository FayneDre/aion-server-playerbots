package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
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
	private volatile int pendingCorpse;
	/** When the bot may next bother with its bag, after finding nothing in it that it could actually put on. */
	private volatile long dressingIdleUntil;
	/** Shop spots that turned out to have no shop keeper standing on them, so the next trip goes somewhere else. */
	private final Map<Vector3f, Long> ignoredVendors = new ConcurrentHashMap<>();
	/** Where the bot is headed to sell, non null only while a trip is in progress. */
	private volatile Vector3f vendorDestination;
	/** Set when an operator asked for a trip, which waives the full bag test for the whole errand rather than only to start it. */
	private volatile boolean sellingOnDemand;

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
		Vector3f vendor = BotVendorManager.findVendor(bot, this::isIgnoredVendor);
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
	boolean runVendorTrip() {
		Player bot = ai.getOwner();
		if (vendorDestination == null) {
			// a full bag alone is not enough: one full of gear, quest items or anything rare never empties and would loop forever. An operator who
			// asked for a trip has already made that judgement, and keeps the bot going until it has actually sold: without this, a first shop spot
			// that turns out to be empty ends the errand here, since the bag it was never waiting for is still not full.
			// Or because it has run out of flasks, which is a reason to walk to a shop in its own right: a bot with a tidy bag and no mana potion
			// left would otherwise never go, and fight on empty until something killed it.
			if (!sellingOnDemand && !BotVendorManager.needsPotions(bot)
				&& (!BotVendorManager.hasFullBag(bot) || !BotVendorManager.hasJunk(bot)))
				return false;
			vendorDestination = BotVendorManager.findVendor(bot, this::isIgnoredVendor);
			if (vendorDestination == null) { // no shop left to walk to on this map
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
		if (moveController.isBlocked() || !ai.tryMoveTo(moveController, x, y, z)) {
			log.info("Bot {} cannot reach a shop and gives up selling", bot.getName());
			// remembered, exactly as an empty spot is. Forgetting it only meant the next trip chose the same nearest shop and failed the same way,
			// which is how one unreachable spot produced thousands of trips and no sales.
			ignoredVendors.put(vendorDestination, System.currentTimeMillis() + EMPTY_SHOP_MILLIS);
			vendorDestination = null;
			sellingOnDemand = false;
			return false;
		}
		return true;
	}

	private boolean isIgnoredVendor(Vector3f spot) {
		Long until = ignoredVendors.get(spot);
		if (until == null)
			return false;
		if (until > System.currentTimeMillis())
			return true;
		ignoredVendors.remove(spot);
		return false;
	}
}
