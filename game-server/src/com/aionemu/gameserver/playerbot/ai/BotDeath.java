package com.aionemu.gameserver.playerbot.ai;

import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.lifecycle.BotMapChange;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;

/**
 * A bot's death and how it comes back: waiting for a resurrection a group may cast, taking one, or going to the obelisk, and the walk home that
 * follows. Taken out of {@link PlayerBotAI} on 27 October 2026, unchanged; what it decides is in the static methods below, which need no world.
 * <p>
 * The revival's state is guarded by the AI's {@code combatLock}, as it was inside the AI: the decision tick reads it under that lock to know a bot is
 * already being brought back.
 */
final class BotDeath {

	private static final Logger log = LoggerFactory.getLogger(BotDeath.class);

	/** How long whatever killed the bot is left alone after. */
	private static final long KILLER_AVOIDED_MILLIS = 120000;
	/** Roughly the time a player spends looking at the resurrection window. */
	private static final int REVIVE_DELAY_MILLIS = 10000;
	/**
	 * How long a bot dead in a group still waits for a resurrection once the group has stopped fighting. A cleric's or a chanter's raise takes some
	 * seconds to cast and is cast after the fight, and a player has to walk over first: ten seconds was never enough, and a tester could not raise a
	 * single bot on the spot.
	 */
	static final long RESURRECTION_WAIT_MILLIS = 60_000;
	/** Longest a bot dead in a group waits, whatever the group does, so a mate who stays alive and never raises it does not keep it down for good. */
	static final long LONGEST_RESURRECTION_WAIT_MILLIS = 600_000;
	/** How often a bot waiting for a resurrection looks again at whether its group is still fighting. */
	private static final long RESURRECTION_LOOK_MILLIS = 5_000;
	/** How long a player takes to click "accept" once a resurrection has been cast on them. */
	private static final int ACCEPT_RESURRECTION_DELAY_MILLIS = 2000;
	/** How far in height a revived bot's home may be from the obelisk and still be walked or flown back to: Reshanta's layers are 1200 m apart. */
	static final float ANOTHER_LAYER = 300;
	/** How often a bot revived on another layer looks at whether it is well enough to be taken home, and how many times before it goes anyway. */
	private static final long TAKEN_HOME_DELAY_MILLIS = 5000;
	private static final int TAKEN_HOME_LOOKS = 120;

	/** The three ways back, as a player chooses among them. */
	enum Revival {
		/** Where it lies, taking a resurrection somebody cast. */
		RESURRECTION,
		/** At the instance's entrance: the obelisk is on another map, and only the player's portal leads back in. */
		INSTANCE_ENTRANCE,
		/** At its obelisk, on a quarter of its health. */
		OBELISK
	}

	private final PlayerBotAI ai;
	/** Non null between death and resurrection, which also marks the death as already handled. */
	private ScheduledFuture<?> reviveTask;
	/** Whether the pending revival is the acceptance of a resurrection somebody cast, rather than the walk back from the obelisk. */
	private boolean resurrectionPending;
	private long diedAt, groupLastFoughtAt;

	BotDeath(PlayerBotAI ai) {
		this.ai = ai;
	}

	/** @return true between the death and the revival. Read under the AI's combat lock, as it is written. */
	boolean isBeingBroughtBack() {
		return reviveTask != null;
	}

	/**
	 * Reacts to the bot being dead, at most once per death.
	 * <p>
	 * The engine only fires {@code AIEventType.DIED} from {@code NpcController}: {@code PlayerController.onDie} never notifies the AI, since real
	 * players have a dummy one. Rather than patch a core file upstream changes often, the decision tick notices the death itself.
	 */
	void onDeath() {
		synchronized (ai.combatLock) {
			if (reviveTask != null) { // already dealt with, unless a resurrection has been cast on it since
				if (!resurrectionPending && ai.getOwner().getResStatus()) {
					// the window a resurrection opens is clicked by the player it is cast on, so for a bot the decision tick clicks it: taking a resurrection
					// instead of the obelisk is what a player does when one is offered, and it is what a chanter's Light of Resurrection is for
					resurrectionPending = true;
					log.info("Bot {} has been offered a resurrection and takes it in {} ms", ai.getOwner().getName(), ACCEPT_RESURRECTION_DELAY_MILLIS);
					reviveTask.cancel(false);
					reviveTask = BotScheduler.getInstance().schedule(this::acceptResurrection, ACCEPT_RESURRECTION_DELAY_MILLIS);
				}
				return;
			}
			// nobody will ever click the resurrection window for a bot, so without this it lies dead forever
			diedAt = groupLastFoughtAt = System.currentTimeMillis();
			reviveTask = BotScheduler.getInstance().schedule(this::reviveUnlessRaised, REVIVE_DELAY_MILLIS);
		}
		Player bot = ai.getOwner();
		// whatever it was fighting just won, so leave it alone for a while instead of walking straight back into it
		if (bot.getTarget() instanceof Creature killer)
			ai.ignoreFor(killer, System.currentTimeMillis() + KILLER_AVOIDED_MILLIS);
		ai.cancelCombat();
		if (bot.getMoveController() instanceof BotMoveController moveController)
			moveController.abandonGlide();
		ai.enterState(AIState.DIED);
		log.info("Bot {} died at {} {} {} (anchor {})", bot.getName(), bot.getX(), bot.getY(), bot.getZ(), ai.describeAnchor());
		ai.day().recordDeath(); // a home that keeps killing its resident is a home the resident leaves, whatever the reason turns out to be
	}

	/** Drops a revival in progress, as the bot leaves the world. Called under the AI's combat lock. */
	void cancel() {
		if (reviveTask != null) {
			reviveTask.cancel(false);
			reviveTask = null;
		}
	}

	/**
	 * Goes back to the obelisk, unless the group may still raise the bot where it lies: then it looks again in a moment.
	 * <p>
	 * A bot alone goes at once, as before. One in a group waits while the group is fighting and for {@value #RESURRECTION_WAIT_MILLIS} ms after, as a
	 * player waits for the cleric before releasing; it goes sooner when nobody in the group is left standing on its map to raise it.
	 */
	private void reviveUnlessRaised() {
		long wait = resurrectionWaitLeft();
		synchronized (ai.combatLock) {
			// A resurrection taken in the meantime has replaced this task with its own, and a bot leaving the world has dropped it: either way this run is
			// stale, and going to the obelisk now would throw away the raise the tick has just accepted.
			if (reviveTask == null || resurrectionPending)
				return;
			if (wait > 0) {
				reviveTask = BotScheduler.getInstance().schedule(this::reviveUnlessRaised, Math.min(wait, RESURRECTION_LOOK_MILLIS));
				return;
			}
		}
		bringBack(false);
	}

	/** @return How much longer the bot waits to be raised, in milliseconds; 0 or less to go back now. */
	private long resurrectionWaitLeft() {
		Player bot = ai.getOwner();
		if (!BotGroupManager.hasLivingMateOnTheMap(bot))
			return 0;
		long now = System.currentTimeMillis();
		if (BotGroupManager.isGroupFighting(bot))
			groupLastFoughtAt = now;
		return waitLeft(now, diedAt, groupLastFoughtAt);
	}

	/**
	 * @return How much longer a bot with a living mate on its map waits to be raised: {@value #RESURRECTION_WAIT_MILLIS} ms after its group last fought,
	 *         and never past {@value #LONGEST_RESURRECTION_WAIT_MILLIS} ms after it died. 0 or less to go back now.
	 */
	static long waitLeft(long now, long diedAt, long groupLastFoughtAt) {
		return Math.min(groupLastFoughtAt + RESURRECTION_WAIT_MILLIS, diedAt + LONGEST_RESURRECTION_WAIT_MILLIS) - now;
	}

	/**
	 * Takes the resurrection somebody has cast on the bot, where it lies: no obelisk and no walk home, which is the whole of what a resurrection is for. It
	 * comes back on a third of its health with soul sickness, as the engine gives it to a player ({@code skillRevive}), and its anchor stays where it was,
	 * since it is still beside the group that raised it.
	 */
	private void acceptResurrection() {
		bringBack(true);
	}

	/**
	 * @param resurrected Whether the bot is taking a resurrection rather than releasing.
	 * @param resurrectionCast Whether one has really been cast on it: {@code skillRevive} writes the character into the audit log as a resurrection hack
	 *          when nobody cast one, so it is asked first.
	 * @return How the bot comes back.
	 */
	static Revival revivalFor(boolean resurrected, boolean resurrectionCast, boolean inInstance) {
		if (resurrected && resurrectionCast)
			return Revival.RESURRECTION;
		return inInstance ? Revival.INSTANCE_ENTRANCE : Revival.OBELISK;
	}

	/**
	 * @return true if a bot revived at its obelisk lives on another layer of the map, which no walk and no flight joins to it, and is taken there as a
	 *         player takes the base's teleporter. Never in a group, whose members come back to it.
	 */
	static boolean livesOnAnotherLayer(Vector3f home, boolean inTeam, float revivedAtZ) {
		return home != null && !inTeam && Math.abs(home.getZ() - revivedAtZ) > ANOTHER_LAYER;
	}

	/**
	 * Brings the bot back the way a player does.
	 * <p>
	 * At the obelisk, {@code bindRevive} rather than a revive on the spot, and that is the whole of it. Coming back where it fell — or at its anchor,
	 * which is the patch of ground it was working — puts the bot back within reach of whatever just killed it, at a quarter health: it dies again,
	 * comes back again, and the loop only ends when something else wanders past. An obelisk is far away on purpose, and the walk home is the price a
	 * player pays too. Its anchor is moved home as well, because the spot it was working is on the far side of that walk.
	 */
	private void bringBack(boolean resurrected) {
		Player bot = ai.getOwner();
		try {
			if (!bot.isSpawned() || !bot.isDead())
				return;
			switch (revivalFor(resurrected, bot.getResStatus(), bot.isInInstance())) {
				case RESURRECTION -> {
					PlayerReviveService.skillRevive(bot);
					ai.convalescence().forget();
					ai.enterState(AIState.IDLE);
					if (bot.isDead())
						log.warn("Bot {} took a resurrection and is still dead", bot.getName());
					else
						log.info("Bot {} accepted a resurrection at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
				}
				case INSTANCE_ENTRANCE -> {
					PlayerReviveService.instanceRevive(bot);
					BotMapChange.arrive(bot); // the instance had no entrance on record, and the revival fell back to the obelisk
					ai.convalescence().forget();
					ai.enterState(AIState.IDLE);
					log.info("Bot {} revived at the entrance of map {} at {} {} {}", bot.getName(), bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ());
				}
				case OBELISK -> {
					PlayerReviveService.bindRevive(bot);
					BotMapChange.arrive(bot); // an obelisk on another map, which a death far from home can bring
					ai.checkLandingNextTick(); // once the move to the obelisk has settled
					ai.convalescence().forget(); // a new death, a new look at where is quiet
					Vector3f home = ai.day().home();
					if (home != null)
						ai.setAnchor(home.getX(), home.getY(), home.getZ());
					ai.enterState(AIState.IDLE);
					log.info("Bot {} revived at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
					// Reshanta's upper layer has no obelisk of its own, and its residents came back in Teminon, 1300 m below, asked for routes home that do
					// not exist and were rescued a dozen seconds later: 47 refused routes and 22 of 23 rescues of its 22 residents in half an hour.
					// Not in a group: a squad member taken home was left hundreds of metres from a leader waiting for it.
					if (livesOnAnotherLayer(home, bot.getCurrentTeam() != null, bot.getZ()))
						BotScheduler.getInstance().schedule(() -> takeHomeWhenRecovered(home, TAKEN_HOME_LOOKS), TAKEN_HOME_DELAY_MILLIS);
				}
			}
		} catch (RuntimeException e) {
			// swallowed by the scheduler otherwise: the bot stayed dead with the offer standing and was offered it again every tick, silently
			log.error("Bot " + bot.getName() + " failed to come back from its death", e);
		} finally {
			// cleared last, so the decision tick cannot mistake the bot for freshly dead while it is being brought back
			synchronized (ai.combatLock) {
				reviveTask = null;
				resurrectionPending = false;
			}
		}
	}

	/**
	 * Takes a bot revived on another layer of the map home, once it has waited out its sickness and healed: taken there at once, still sick, a resident
	 * of Reshanta's upper layer died again at home within thirty seconds of every revival, five times running. Looked at again until then.
	 */
	private void takeHomeWhenRecovered(Vector3f home, int looksLeft) {
		Player bot = ai.getOwner();
		if (!bot.isSpawned() || bot.isDead() || bot.getCurrentTeam() != null || Math.abs(home.getZ() - bot.getZ()) <= ANOTHER_LAYER)
			return;
		if ((ai.isRecoveringFromDeath() || !ai.isHealthyEnoughToFight()) && looksLeft > 0) {
			BotScheduler.getInstance().schedule(() -> takeHomeWhenRecovered(home, looksLeft - 1), TAKEN_HOME_DELAY_MILLIS);
			return;
		}
		log.info("Bot {} lives {} m above or below where it revived and is taken home", bot.getName(), Math.round(Math.abs(home.getZ() - bot.getZ())));
		TeleportService.teleportTo(bot, bot.getWorldId(), home.getX(), home.getY(), home.getZ());
	}
}
