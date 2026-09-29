package com.aionemu.gameserver.playerbot.ai;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AITemplate;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotLootManager;
import com.aionemu.gameserver.playerbot.combat.BotRestManager;
import com.aionemu.gameserver.playerbot.combat.BotPotionManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetRegistry;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.economy.BotEquipManager;
import com.aionemu.gameserver.playerbot.economy.BotVendorManager;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * Brain of a player bot. The engine fires creature events on whatever AI is attached to a creature, so a bot receives ATTACK, MOVE_ARRIVED and
 * ATTACK_COMPLETE for free, unlike the NpcAI-typed handlers in {@code ai/handler} which cannot be reused here.
 */
public class PlayerBotAI extends AITemplate<Player> {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	private static final int THINK_INTERVAL_MILLIS = 1000;
	/** A chase is abandoned after this long, whatever the reason it is not getting anywhere. */
	private static final long MAX_CHASE_MILLIS = 15000;
	/**
	 * How far from its anchor a bot may be dragged before it breaks off and comes back. It is the camp radius on purpose: the bot roams that far to
	 * find fights, so a shorter leash would make it abandon the very mobs it just walked to.
	 */
	private static final float LEASH_DISTANCE = BotTargetSelector.HOME_RADIUS;
	/** A chased target is only given a new route once it has moved this far, instead of on every tick. */
	private static final float RETARGET_STEP = 3f;
	/**
	 * Minimum delay between two routes towards the same chased target. Each new route makes clients restart their interpolation, so re-routing on
	 * every attack tick turns a run into a stutter.
	 */
	private static final long CHASE_REROUTE_INTERVAL = 600;
	/**
	 * How long after standing up the bot may move again, or the client shows it sliding to its feet.
	 * <p>
	 * Both delays below are empirical: animation lengths live in the client and are exposed nowhere server side, so they are tuned by watching.
	 */
	private static final long STAND_UP_MILLIS = 1000;
	/** Drawing the weapon blends worse with standing up than walking does, so it waits a little longer. */
	private static final long DRAW_AFTER_STAND_MILLIS = 1500;
	/** How long the weapon stays drawn after a fight, so the bot does not sheathe it between two mobs of the same pull. */
	private static final long SHEATHE_DELAY_MILLIS = 8000;
	/** How long a target the bot could not reach is left alone, so it does not pick the same unreachable one again right away. */
	private static final long UNREACHABLE_MILLIS = 10000;
	/** Close enough to a shop's spawn point that a shop keeper would be in sight if there were one. */
	private static final float VENDOR_SPOT_TOLERANCE = 10f;
	/** How long the bot leaves its bag alone after a fruitless look. Long enough not to stutter, short enough to notice the next drop. */
	private static final long DRESSING_RETRY_MILLIS = 60000;
	/** How long a shop spot that turned out to be empty is left alone. Long: npcs do not appear and disappear minute by minute. */
	private static final long EMPTY_SHOP_MILLIS = 600000;
	/** How long the bot leaves alone whatever killed it, so a lost fight is not restarted on a loop. */
	private static final long KILLER_AVOIDED_MILLIS = 120000;
	/** Health below which the bot waits to regenerate instead of looking for a fight. */
	private static final int MIN_ENGAGE_HP_PERCENT = 90;
	/** Close enough to the anchor to count as home, so the bot does not fidget over a metre. */
	/** How far from its place a bot will stand about, so a village square is people spread over it rather than a stack on one point. */
	private static final float LOITERING_SPREAD = 14f;
	/** How many spots to try before settling for the place itself. */
	private static final int RESTING_SPOTS = 6;
	/** How close to somebody else a bot may stop. Bots pass through npcs, so nothing but this keeps one from halting inside a blacksmith. */
	private static final float PERSONAL_SPACE = 2.5f;
	/** The penalty skill a player carries after dying, which a bot now sits out rather than fighting under. */
	private static final int SOUL_SICKNESS_SKILL = 8291;
	/** How little life a target needs for the bot to stop looking after itself and simply end the fight. */
	private static final int FINISH_IT_PERCENT = 20;
	private static final float ANCHOR_TOLERANCE = 5f;
	/** How near its place in the formation a follower settles. Small, because that place is already set apart from the leader. */
	private static final float FOLLOW_DISTANCE = 2f;
	/** Roughly the time a player spends looking at the resurrection window. */
	private static final int REVIVE_DELAY_MILLIS = 10000;

	/** Guards the scheduled tasks against concurrent starts and stops, since events and ticks run on different pool threads. */
	private final Object combatLock = new Object();
	private ScheduledFuture<?> attackTask;
	private ScheduledFuture<?> thinkTask;
	/** Non null between death and resurrection, which also marks the death as already handled. */
	private ScheduledFuture<?> reviveTask;
	private volatile boolean autonomous = true;

	/** Where the bot belongs: it fights around this point and returns to it rather than following a target across the map. */
	private volatile float anchorX, anchorY, anchorZ;
	private volatile long chaseStartTime;
	private volatile long lastChaseRoute;
	private volatile long standUpTime;
	/** Object id of the corpse left by the bot's last kill, 0 when there is nothing to pick up. */
	private volatile int pendingCorpse;
	/** When the last fight ended, 0 once the weapon has been put away. */
	private volatile long combatEndedAt;
	/** Targets not to pick for a while: either out of reach, or having just killed the bot. */
	private final Map<Integer, Long> ignoredTargets = new ConcurrentHashMap<>();
	/** What a resident is doing with its day, and until when. Adventurers have no occupation: they hunt, which is how they level. */
	private volatile Occupation occupation = Occupation.FARMING;
	private volatile long occupationUntil;
	/** Where the current occupation is taking the bot, so it keeps going to the same place rather than re-choosing every tick. */
	private volatile Vector3f occupationDestination;
	/** Where this bot lives, read from the roster on first use. A fact about the bot, not something recomputed from the map every time it is asked. */
	private volatile Vector3f home;
	/** How many refusals in a row mean the bot is not blocked from somewhere but wedged where it stands. */
	private static final int STUCK_REFUSALS = 12;
	/** Refusals in a row, reset by any movement that starts. */
	private int refusedMoves;
	/** Places this bot has found it cannot get to, so it stops choosing them. A map has pockets, and a bot can be standing in one. */
	private final Set<Vector3f> unreachable = ConcurrentHashMap.newKeySet();
	/** When the bot may next bother with its bag, after finding nothing in it that it could actually put on. */
	private volatile long dressingIdleUntil;
	/** Shop spots that turned out to have no shop keeper standing on them, so the next trip goes somewhere else. */
	private final Map<Vector3f, Long> ignoredVendors = new ConcurrentHashMap<>();
	/** Where the bot is headed to sell, non null only while a trip is in progress. */
	private volatile Vector3f vendorDestination;
	/** An operator asked for a sale, so the full bag test is waived until one actually happens. */
	private volatile boolean sellingOnDemand;
	/**
	 * Whether this fight's opening has been tried. One attempt per fight and no more: an opening burst that will not go off now is on cooldown, which
	 * is itself the answer, and retrying it every tick would spend it halfway through the fight where most of it is wasted.
	 */
	private volatile boolean openingSpent;
	/** Whether this fight's approach has been tried. Separate from the opening, so hiding does not cost the bot its burst as well. */
	private volatile boolean approachSpent;
	/**
	 * Set when sent somewhere by command rather than by its own decision, so the tick that runs a second later does not immediately hijack the trip
	 * for whatever mob happens to be findable along the way. Self defence still overrides it: this only holds off the bot picking its own fights.
	 * <p>
	 * Never read directly — {@link #isRunningErrand()} pairs it with the move controller, which is what actually knows whether the bot is still on
	 * its way. A flag cleared by hand instead would have to be cleared on every way a journey can end, and missing one leaves a bot standing still
	 * for good.
	 */
	private volatile boolean manualErrand;

	public PlayerBotAI(Player owner) {
		super(owner);
	}

	/**
	 * Sets the point the bot fights around and returns to when a chase drags it too far.
	 */
	public void setAnchor(float x, float y, float z) {
		anchorX = x;
		anchorY = y;
		anchorZ = z;
	}

	public boolean isAutonomous() {
		return autonomous;
	}

	/**
	 * When autonomous, the bot engages hostiles within reach and fights back on its own. Turn it off to drive it purely by command.
	 */
	public void setAutonomous(boolean autonomous) {
		this.autonomous = autonomous;
		if (!autonomous)
			stopAttacking();
	}

	/**
	 * Sends the bot walking to a point on command, protected from the next decision tick picking a fight along the way instead. Self defence is
	 * exempt on purpose: a bot that ignored being hit while running an errand would be worse than one that took a short detour.
	 *
	 * @return false if the bot could not set off at all, in which case nothing is being protected.
	 */
	public boolean walkTo(float x, float y, float z) {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		manualErrand = tryMoveTo(moveController, x, y, z);
		return manualErrand;
	}

	/**
	 * @return true while the bot is still walking somewhere it was sent by command. Asks the move controller rather than trusting the flag on its
	 *         own, so an errand that ended in any way at all — arrival, an obstacle, a stop, death — ends here too.
	 */
	private boolean isRunningErrand() {
		if (!manualErrand)
			return false;
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isTravelling())
			return true;
		manualErrand = false;
		return false;
	}

	/**
	 * Joins whoever invites the bot, and keeps its anchor on its group leader.
	 * <p>
	 * Moving the anchor is the whole of "follow": every rule the bot already had — come back when there is nothing to do, break off a chase that
	 * leaves the area — is written against the anchor, so pointing that at the leader makes it travel with the group without a second set of movement
	 * rules to keep in step with the first.
	 *
	 * @return true while the bot has a leader to follow, which is also what makes it stop deciding things for itself.
	 */
	private boolean followTheGroup() {
		BotGroupManager.acceptPendingInvite(getOwner());
		BotGroupManager.leaveIfLeaderless(getOwner());
		Player leader = BotGroupManager.leaderToFollow(getOwner());
		if (leader == null)
			return false;
		// the bot's own place beside the leader, not the leader's feet: everything downstream already works off the anchor, so this is all "stand
		// with the group" needs to be
		Vector3f spot = BotGroupManager.formationSpot(getOwner(), leader);
		setAnchor(spot.getX(), spot.getY(), spot.getZ());
		return true;
	}

	/**
	 * What a bot does while it belongs to someone else's group: the leader's fight, or the leader's heels. Nothing else.
	 * <p>
	 * A member that pulls what it likes is worse than no member at all — it brings a second mob into a fight the group did not choose, and wanders
	 * off while it does. So the whole of the bot's own initiative is suspended here: no picking targets, no roaming the camp, no errands, no shop
	 * runs. Defending itself is not initiative and stays where it was, ahead of everything, in {@link #botTick()}.
	 */
	private void serveTheGroup() {
		if (standUp()) // one tick before acting, so the animation has played out by then
			return;
		if (BotSkillManager.tryBuffSelf(getOwner()) || BotSkillManager.tryChantMantra(getOwner()))
			return;
		if (tendToTheGroup())
			return;
		Creature assisted = BotGroupManager.targetToAssist(getOwner());
		if (assisted == null) {
			followLeader(); // standing by is the default, not looking for something to do
			return;
		}
		BotTargetRegistry.forceClaim(assisted, getOwner()); // the group piles on together, which is the point of being one
		startAttacking(assisted);
	}

	/**
	 * Looks after the group mates: the most hurt one first, then whoever is missing a buff.
	 *
	 * @return true if a skill was cast, in which case the bot is busy with it.
	 */
	private boolean tendToTheGroup() {
		Player hurt = BotGroupManager.mostHurtMember(getOwner(), BotSkillManager.HEAL_ALLY_PERCENT);
		if (BotSkillManager.tryHealAlly(getOwner(), hurt))
			return true;
		for (Player member : BotGroupManager.membersToTendTo(getOwner())) {
			if (BotSkillManager.tryBuffAlly(getOwner(), member))
				return true;
		}
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
	private boolean dressUp() {
		Player bot = getOwner();
		if (BotEquipManager.isBusyDressing(bot))
			return true;
		// A piece can score higher and still be unwearable for good — a mace a priest has no mastery for, a piece for the other race — and nothing
		// short of trying says so. Without this the bot would stop and sheathe on every tick of its life for one such item sitting in its bag.
		if (System.currentTimeMillis() < dressingIdleUntil || !BotEquipManager.hasUpgradeWaiting(bot))
			return false;
		stopMoving();
		BotAttackManager.leaveAttackMode(bot);
		if (standUp() || !CreatureState.isStanding(bot.getState()))
			return true; // on its feet first, and the weapon is away as of this tick
		if (BotEquipManager.equipUpgrades(bot) || BotEquipManager.identifyUpgrade(bot))
			return true;
		dressingIdleUntil = System.currentTimeMillis() + DRESSING_RETRY_MILLIS;
		return false;
	}

	/**
	 * @return true when the bot has fallen behind its group and should walk rather than sit down. Resting takes a bot out of the fight for as long as
	 *         it lasts, which is fine alone in an empty camp and not fine when the group has moved on without it.
	 */
	private boolean mustCatchUp(boolean following) {
		return following && PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) > ANCHOR_TOLERANCE;
	}

	/** Decision loop, kept separate from the framework's {@code think()} so nothing in the engine can trigger it unexpectedly. */
	private void botTick() {
		sheathWhenCalm();
		boolean following = followTheGroup();
		if (getOwner().isSpawned() && getOwner().isDead())
			handleDeath();
		else if (autonomous && !isAttacking() && getOwner().isSpawned()) {
			Creature attacker = findAttacker();
			if (attacker != null)
				startAttacking(attacker); // no health check: a bot being hit defends itself, it does not sit down
			else if (collectLoot()) {
				// busy with a corpse, everything else can wait
			} else if (dressUp()) {
				// dressing itself, which is five seconds of stillness the bot has to commit to rather than hope for
			} else if (isRecoveringFromDeath()) {
				recover(); // sits out the sickness rather than walking back into a fight at a quarter of its strength
			} else if (!isHealthyEnoughToFight() && !mustCatchUp(following))
				recover();
			else if (following) {
				serveTheGroup(); // a member has no business picking its own fights, so the rest of this chain is not its to run
			} else if (runVendorTrip()) {
				// bag is full and a shop is being walked to or worked, everything else waits
			} else if (isRunningErrand()) {
				// an operator sent it somewhere on purpose; let it arrive before it goes looking for its own fights
			} else if (!standUp()) { // stand up one tick before acting, so the animation has played out by then
				// buffs go up before a fight is picked, never during one, where the cast would cost a swing. Not an early return: the tick reschedules
				// itself at the end of this method, and leaving by any other door stops the bot for good.
				if (!BotSkillManager.tryBuffSelf(getOwner()) && !BotSkillManager.tryChantMantra(getOwner()) && !pursueOccupation()) {
					Creature target = BotTargetSelector.findTarget(getOwner(), this::isIgnored);
					if (target == null)
						roam();
					else if (BotTargetRegistry.claim(target, getOwner())) // another bot may have picked it in the same tick
						startAttacking(target);
				}
			}
		}
		synchronized (combatLock) {
			if (thinkTask != null) // still spawned
				scheduleBotTick();
		}
	}

	private void scheduleBotTick() {
		thinkTask = ThreadPoolManager.getInstance().schedule(this::botTick, THINK_INTERVAL_MILLIS);
	}

	/**
	 * Sends the bot to sell right away, skipping the full bag check — for testing the trip without farming a bag full first.
	 *
	 * @return false if there is nothing worth selling, or the bot's map has no shop to walk to.
	 */
	public boolean forceSellTrip() {
		if (!BotVendorManager.hasJunk(getOwner()))
			return false;
		Vector3f vendor = BotVendorManager.findVendor(getOwner(), this::isIgnoredVendor);
		if (vendor == null)
			return false;
		vendorDestination = vendor;
		sellingOnDemand = true; // sees the errand through: a shop spot with nobody on it is a detour, not an answer
		return true;
	}

	public void startAttacking(Creature target) {
		combatEndedAt = 0; // fighting again, so the pending sheathe is off
		openingSpent = false;
		approachSpent = false;
		BotTargetRegistry.forceClaim(target, getOwner()); // retaliation and commands are not negotiable
		boolean gettingUp = standUp(); // canAttack() is false while resting
		if (getOwner().isProtectionActive()) // CM_ATTACK does this for a real player
			getOwner().getController().stopProtectionActiveTask();
		synchronized (combatLock) {
			stopAttackTask();
			getOwner().setTarget(target);
			setStateIfNot(AIState.FIGHT);
			// drawing the weapon in the same breath as standing up leaves the client blending two poses, which shows as a floating character
			scheduleAttackTick(gettingUp ? (int) DRAW_AFTER_STAND_MILLIS : 0);
		}
		log.info("Bot {} starts attacking {}", getOwner().getName(), target.getName());
	}

	public void stopAttacking() {
		cancelCombat();
		setStateIfNot(AIState.IDLE);
	}

	private void cancelCombat() {
		BotTargetRegistry.releaseAllOf(getOwner());
		synchronized (combatLock) {
			stopAttackTask();
			chaseStartTime = 0;
			stopMoving();
			// the weapon stays out for a moment: sheathing between two mobs of the same pull looks like a nervous tic, and a real player does not do it
			combatEndedAt = System.currentTimeMillis();
			getOwner().setTarget(null);
		}
	}

	public boolean isAttacking() {
		synchronized (combatLock) {
			return attackTask != null;
		}
	}

	private void attackTick() {
		Player bot = getOwner();
		Creature target = bot.getTarget() instanceof Creature creature ? creature : null;
		if (!BotAttackManager.canKeepFighting(bot, target)) {
			if (target != null && target.isDead() && BotLootManager.hasLootFor(bot, target.getObjectId()))
				pendingCorpse = target.getObjectId(); // the decision tick walks over and picks it up
			log.info("Bot {} stops attacking", bot.getName());
			stopAttacking();
			return;
		}

		BotAttackManager.enterAttackMode(bot, target); // idempotent, and deferred to here so it never overlaps the stand up animation
		if (bot.getCastingSkill() != null) {
			// casting roots a real player, so the bot neither moves nor starts another action until the cast is over
		} else if (BotAttackManager.isInAttackRange(bot, target)) {
			chaseStartTime = 0;
			stopMoving();
			if (!useBestSkill(target))
				BotAttackManager.autoAttack(bot, target);
		} else if (castFromAfar(target)) {
			// something in the bot's book reaches where its weapon does not, so there is nothing to close
		} else if (!chase(target)) {
			log.info("Bot {} cannot reach {} and gives up", bot.getName(), target.getName());
			ignoredTargets.put(target.getObjectId(), System.currentTimeMillis() + UNREACHABLE_MILLIS);
			stopAttacking();
			return;
		}

		synchronized (combatLock) {
			if (attackTask != null) // not stopped while we were attacking
				scheduleAttackTick(bot.getGameStats().getAttackSpeed().getCurrent());
		}
	}

	private void scheduleAttackTick(int delayMillis) {
		attackTask = ThreadPoolManager.getInstance().schedule(this::attackTick, delayMillis);
	}

	private void stopAttackTask() {
		if (attackTask != null) {
			attackTask.cancel(false);
			attackTask = null;
		}
	}

	/**
	 * Attacks a target still out of weapon reach, when the bot knows something that reaches that far.
	 * <p>
	 * Without this a bot walks into melee before it will cast anything, which throws its class away: a priest closed to staff range to cast a spell
	 * good from twenty five metres. There is no need to work out which skills reach, since the engine checks each one's range as it is cast — trying
	 * is itself the question "does anything reach from here".
	 *
	 * @return true if a skill went off, in which case the bot holds its ground for the cast.
	 */
	private boolean castFromAfar(Creature target) {
		if (!useBestSkill(target))
			return false;
		chaseStartTime = 0;
		stopMoving(); // a cast roots a real player, and sliding through one is the animation fault we keep paying for elsewhere
		return true;
	}

	/**
	 * Picks the one thing the bot does with this moment of the fight, most urgent first.
	 * <p>
	 * The order is the whole of the bot's combat judgement, so it lives in one place rather than being restated wherever a skill might be cast. It
	 * reads as a set of priorities: survive, then keep the advantage the class was given, then deal damage. A cast costs a swing either way, which is
	 * why staying alive comes first and why nothing here is tried twice in the same tick.
	 *
	 * @return true if a skill went off, in which case the bot is busy and should not also swing.
	 */
	/**
	 * @return true while the bot is still carrying the penalty it got for dying.
	 *         <p>
	 *         A player who has just resurrected waits it out, because it takes a large bite out of every stat and walking back into a fight under it
	 *         is how you die a second time. A bot that went straight back to work read as exactly what it was: something that does not understand
	 *         what just happened to it. Defending itself is untouched — that comes before all of this — and so is being dragged along by a group.
	 */
	private boolean isRecoveringFromDeath() {
		return getOwner().getEffectController().hasAbnormalEffect(SOUL_SICKNESS_SKILL);
	}

	/**
	 * @return true if the target is close enough to death that finishing it beats anything else the bot could do with the time.
	 */
	private static boolean isAlmostDead(Creature target) {
		return target.getLifeStats() != null && target.getLifeStats().getHpPercentage() <= FINISH_IT_PERCENT;
	}

	private boolean useBestSkill(Creature target) {
		Player bot = getOwner();
		boolean closing = !BotAttackManager.isInAttackRange(bot, target);
		if (closing && !approachSpent) {
			approachSpent = true;
			// only while there is still ground to cover: hiding on top of a target it is about to hit buys the bot nothing
			if (BotSkillManager.tryApproachUnseen(bot))
				return true;
		}
		if (!openingSpent) {
			openingSpent = true;
			// before the first blow, because a burst spent on a mob already dying is a burst thrown away — and self buffs need no range, so this works
			// while still closing in
			if (BotSkillManager.tryOpeningCooldown(bot))
				return true;
		}
		// Finish it. A target this close to death dies to the next blow or two, and every second spent mending instead is a second it spends hitting
		// back: a bot at low health healing in front of a mob with a sliver left heals, gets hit, heals again, and dies to something it could have
		// killed twice over. Ending the fight is the best defence available and no rule below can see that.
		if (!isAlmostDead(target)) {
			// the defensive ability comes before the heal: it is cheaper in mana and it stops damage instead of repairing it, which only works ahead
			if (BotSkillManager.tryDefensiveCooldown(bot) || BotSkillManager.tryHealSelf(bot, BotSkillManager.HEAL_IN_COMBAT_PERCENT))
				return true;
			// the flask is what a class with no heal of its own has instead, and what a healer reaches for when the heal is on cooldown
			if (BotPotionManager.tryHealingPotion(bot, BotSkillManager.HEAL_IN_COMBAT_PERCENT)
				|| BotPotionManager.tryManaPotion(bot, BotSkillManager.MANA_RESERVE_PERCENT))
				return true;
			// a group mate's life outranks the bot's damage, but not the bot's own: a dead healer heals nobody
			if (BotSkillManager.tryHealAlly(bot, BotGroupManager.mostHurtMember(bot, BotSkillManager.HEAL_ALLY_PERCENT)))
				return true;
		}
		// a leap covers ground the bot would otherwise walk, and those last metres on foot are where bots get stuck
		if (closing && BotSkillManager.tryGapCloser(bot, target))
			return true;
		return BotSkillManager.tryCastSkill(bot, target);
	}

	/**
	 * Walks towards a target that is out of weapon reach.
	 * <p>
	 * Every bound here exists because reactive steering cannot guarantee it will ever arrive: the leash keeps a fleeing target from dragging the bot
	 * across the map, the timeout ends chases that make no progress, and the move controller reports geometry it could not get through.
	 *
	 * @return false if the bot should give this target up.
	 */
	private boolean chase(Creature target) {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return false;
		long now = System.currentTimeMillis();
		if (chaseStartTime == 0)
			chaseStartTime = now;
		else if (now - chaseStartTime > MAX_CHASE_MILLIS)
			return false;
		if (PositionUtil.getDistance(anchorX, anchorY, target.getX(), target.getY()) > LEASH_DISTANCE)
			return false;
		if (!hasStoodUpLongEnough())
			return true; // on its feet in a moment, do not slide there

		if (moveController.isInMove()
			&& (now - lastChaseRoute < CHASE_REROUTE_INTERVAL || moveController.isHeadingTo(target.getX(), target.getY(), RETARGET_STEP)))
			return true; // already on its way, and the target has not moved enough to be worth a new route
		lastChaseRoute = now;
		return tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	private void stopMoving() {
		if (getOwner().getMoveController() instanceof BotMoveController moveController && moveController.isInMove())
			moveController.stop();
	}

	/**
	 * Keeps a resident's day going: renews the occupation when its time runs out, and carries out the ones that are not hunting.
	 * <p>
	 * Read from {@code noExp} rather than from the stored roster, because that flag is already on the character and says the same thing: a bot that
	 * gains no experience is one that belongs to a place. An adventurer always falls through to hunting, which is how it levels.
	 *
	 * @return true if the occupation took this tick, false to go hunting as before.
	 */
	private boolean pursueOccupation() {
		Player bot = getOwner();
		if (!bot.getCommonData().getNoExp())
			return false;
		long now = System.currentTimeMillis();
		if (now > occupationUntil) {
			// whether this bot lives among people decides how much of its day is spent standing about: a village square wants idlers, a hillside does
			// not, and it was the same draw for both that filled the countryside with characters doing nothing
			boolean inTown = BotPlaces.isSettlement(bot.getWorldId(), homeOfThisBot());
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
	 * Stands about in the nearest settlement. Doing nothing is a thing people do, and it is most of what makes a village look inhabited.
	 * <p>
	 * The settlement becomes the bot's anchor, so everything that already works off the anchor — walking back, breaking off a chase that strays —
	 * keeps it there without a second set of rules.
	 */
	private boolean loiter() {
		if (occupationDestination == null) {
			// A spot of its own a few paces from where it lives, not the place itself. Sending every idler to one point put them inside each other
			// and inside the npcs standing there — a village square of bots occupying the same square metre. And loitering happens at home rather
			// than at the nearest village: villagers live in villages, so they are the ones who fill them, while everyone within walking distance
			// converging on the same square is what made Akarios a crowd.
			Vector3f home = homeOfThisBot();
			occupationDestination = home == null ? null : restingSpot(home);
		}
		if (occupationDestination == null)
			return false; // nowhere of its own to stand about, so it works its ground instead
		setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		if (!returnToAnchor())
			return giveUpOccupation();
		return true;
	}

	/**
	 * Where this bot lives, read once and kept.
	 * <p>
	 * Read from the roster rather than worked out from the map: it is where the bot was actually put down, and that is a fact about the bot, not a
	 * function of the map's list of villages. Falls back to where it stands, which is what a bot made before homes were recorded has.
	 */
	private Vector3f homeOfThisBot() {
		if (home == null)
			home = BotRoster.homeOf(getOwner().getName());
		if (home == null)
			home = new Vector3f(anchorX, anchorY, anchorZ);
		return home;
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
		Player bot = getOwner();
		for (int attempt = 0; attempt < RESTING_SPOTS; attempt++) {
			double angle = Math.PI * 2 * Math.floorMod(Integer.hashCode(bot.getObjectId() * 0x9E3779B9) + attempt * 37, 360) / 360;
			float reach = LOITERING_SPREAD * (0.4f + 0.6f * (Math.floorMod(bot.getObjectId() + attempt, 10) / 10f));
			float x = place.getX() + (float) Math.cos(angle) * reach, y = place.getY() + (float) Math.sin(angle) * reach;
			Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), x, y, place.getZ());
			// standable, unoccupied, and joined to where the bot is standing. The third is not optional and leaving it out here cost a bot twenty one
			// refusals in four minutes for a corner ten metres away: a low wall or a ledge makes perfectly good ground that cannot be walked to.
			if (ground != null && !isCrowded(ground)
				&& NavmeshService.getInstance().canReach(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ(), ground.getX(), ground.getY(),
					ground.getZ()))
				return ground;
		}
		return place;
	}

	/** @return true if somebody is already standing there. An npc a bot can walk through is still somebody, as far as anyone watching is concerned. */
	private boolean isCrowded(Vector3f spot) {
		boolean[] taken = { false };
		getOwner().getKnownList().forEachNpc(npc -> {
			if (PositionUtil.getDistance(npc.getX(), npc.getY(), spot.getX(), spot.getY()) < PERSONAL_SPACE)
				taken[0] = true;
		});
		return taken[0];
	}

	/** Walks to another settlement, and looks for something else to do once it arrives rather than waiting out the clock. */
	private boolean wander() {
		Player bot = getOwner();
		if (occupationDestination == null) {
			// somewhere near home and fit for its level, rather than any settlement on the map: the walk itself crosses everything in between, and
			// that is how a character of two came to be standing in a forest of eights
			Vector3f elsewhere = BotPlaces.placeToVisit(bot.getWorldId(), homeOfThisBot(), bot.getLevel(),
				bot.getObjectId() + (int) occupationUntil);
			// a place already known to be out of reach is no destination at all. Nowhere suitable nearby means staying put rather than setting off
			// across the region: the fallback used to be "any settlement", which is exactly the walk this is meant to prevent.
			occupationDestination = elsewhere != null && !unreachable.contains(elsewhere) ? elsewhere : null;
		}
		if (occupationDestination == null)
			return false;
		setAnchor(occupationDestination.getX(), occupationDestination.getY(), occupationDestination.getZ());
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), occupationDestination.getX(), occupationDestination.getY()) <= ANCHOR_TOLERANCE) {
			occupationUntil = 0; // arrived: something else next tick
			return true;
		}
		if (!returnToAnchor())
			return giveUpOccupation();
		return true;
	}

	/**
	 * Drops what the bot was doing when the place it was doing it in turns out to be unreachable, so something else is drawn on the next tick.
	 * <p>
	 * Without this a bot keeps asking for the same impossible journey for as long as the occupation lasts — several minutes of planning a route that
	 * does not exist, many times a second. The unreachable place stays unreachable, so retrying is not patience, it is a loop.
	 *
	 * @return true, because the occupation did decide what this tick does: nothing.
	 */
	private boolean giveUpOccupation() {
		log.info("Bot {} cannot get to where {} would take it, and does something else", getOwner().getName(), occupation);
		// Remembered, not merely abandoned. The place a bot is sent to is chosen the same way every time — the nearest village, the next place along
		// the list — so forgetting a refusal means choosing the same unreachable place on the next tick, for ever. One bot standing in a pocket of
		// the map cut off from the villages asked sixty times in three minutes.
		if (occupationDestination != null)
			unreachable.add(occupationDestination);
		occupationUntil = 0;
		occupationDestination = null;
		return true;
	}

	/**
	 * Nothing to fight within reach, so go looking. The bot heads for the nearest mob still inside its camp, and only falls back to its anchor when
	 * the whole camp is clear. That is what turns a bot standing at a spawn point into one that works an area.
	 */
	private void roam() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return; // already on its way somewhere

		Creature target = BotTargetSelector.findTargetWithin(getOwner(), BotTargetSelector.HOME_RADIUS, this::isIgnored);
		if (target == null || PositionUtil.getDistance(anchorX, anchorY, target.getX(), target.getY()) > BotTargetSelector.HOME_RADIUS) {
			returnToAnchor(); // camp is empty, or the only mobs left belong to someone else's patch
			return;
		}
		if (standUp())
			return;
		tryMoveTo(moveController, target.getX(), target.getY(), target.getZ());
	}

	/** Brings the bot back where it belongs once it has nothing to fight, so a chase does not slowly displace it. */
	/**
	 * Walks to the leader, re-aimed on every tick.
	 * <p>
	 * {@link #returnToAnchor()} waits for the current leg to end before looking again, which is right for a camp that does not move and wrong for a
	 * leader who is walking: the bot heads for where the leader stood a leg ago, so the longer the journey the further behind it arrives.
	 * {@code moveToPoint} is built to be called repeatedly — it keeps its plan unless the destination has shifted more than a couple of metres — so
	 * re-aiming every tick costs nothing and bounds the lag by that distance instead of by the length of a leg.
	 */
	private void followLeader() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return;
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) > FOLLOW_DISTANCE) {
			// already on its way there, and the place has not drifted enough to be worth a new plan. Without this the bot re-plans every single tick
			// while its leader runs, and every plan turns it a little differently, which is what the walk looks like from outside: restless.
			if (moveController.isInMove() && moveController.isHeadingTo(anchorX, anchorY, RETARGET_STEP))
				return;
			tryMoveTo(moveController, anchorX, anchorY, anchorZ);
			return;
		}
		// Arrived. The leg in progress is aimed at wherever the leader stood when it was issued, so letting it run walks the bot past a leader who
		// has turned round, and the next tick walks it back: the small pacing of a follower forever arriving where its leader no longer is. Stopping
		// is safe here and nothing hangs on it — the ai reads whether the bot is travelling from the move controller rather than from a flag of its
		// own, so there is no state left believing the journey is still on.
		stopMoving();
	}

	private boolean returnToAnchor() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isInMove())
			return true;
		if (PositionUtil.getDistance(getOwner().getX(), getOwner().getY(), anchorX, anchorY) <= ANCHOR_TOLERANCE)
			return true;
		return tryMoveTo(moveController, anchorX, anchorY, anchorZ);
	}

	/**
	 * Every request this ai makes to move, so that refusals can be counted in one place.
	 * <p>
	 * Counting them inside one caller was not enough: a bot wedged behind a rock is refused whatever it asks for — a corpse to loot, a mob to reach,
	 * its own camp — and whichever of those it happened to ask for was the one path that did not count. One bot was refused sixty four times while
	 * the safety net saw twelve.
	 *
	 * @return true if the bot set off.
	 */
	private boolean tryMoveTo(BotMoveController moveController, float x, float y, float z) {
		if (moveController.moveToPoint(x, y, z)) {
			refusedMoves = 0;
			return true;
		}
		if (++refusedMoves >= STUCK_REFUSALS)
			freeItself();
		return false;
	}

	/**
	 * Puts a bot that cannot move at all back where it lives.
	 * <p>
	 * However carefully a place is chosen, a bot walks on its own afterwards, and the reactive layer that steps round obstacles can walk it into one
	 * — behind a rock, under a root, onto a shelf. From inside, every destination is refused, including spots a pace away: three separate bots have
	 * now spent minutes asking for routes that do not exist from where they stand. There is no diagnosis to make at that point and nothing to walk
	 * out along, which is exactly why players are given an unstick command rather than advice.
	 */
	private void freeItself() {
		Player bot = getOwner();
		Vector3f home = homeOfThisBot();
		refusedMoves = 0;
		if (home == null)
			return;
		log.info("Bot {} could not move at all from {} {} and is put back home", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()));
		TeleportService.teleportTo(bot, bot.getWorldId(), home.getX(), home.getY(), home.getZ());
		setAnchor(home.getX(), home.getY(), home.getZ());
		occupationUntil = 0; // whatever it was doing was decided from a place it is no longer in
		occupationDestination = null;
	}

	/**
	 * Keeps the bot from starting a fight it is in no shape for — in particular right after resurrecting at 25% hp, next to whatever killed it.
	 * Only picking a fight is gated: it always defends itself, whatever its health.
	 */
	/**
	 * Sits down to heal where the bot stands, because resting recovers eight times faster than standing around. It recovers on the spot rather than
	 * walking home first: the fight is over, and a player sits down where it ended.
	 */
	/**
	 * Gets the bot up and remembers when, because moving it while the client is still playing the stand up animation makes it slide across the
	 * ground.
	 *
	 * @return true if it was resting and has just stood up, in which case the caller should not act yet.
	 */
	/**
	 * Walks to the corpse of what the bot just killed and takes what is on it.
	 *
	 * @return true while the bot is busy looting, so nothing else is decided this tick.
	 */
	private boolean collectLoot() {
		int corpseId = pendingCorpse;
		if (corpseId == 0)
			return false;

		VisibleObject corpse = World.getInstance().findVisibleObject(corpseId);
		if (corpse == null || !BotLootManager.hasLootFor(getOwner(), corpseId)) { // decayed, empty, or nothing the bot may take
			pendingCorpse = 0;
			return false;
		}
		if (standUp())
			return true; // on its feet first, so it does not slide to the corpse

		if (PositionUtil.getDistance(getOwner(), corpse) > BotLootManager.LOOT_RANGE) {
			if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
				return false;
			if (moveController.isInMove())
				return true;
			if (moveController.isBlocked() || !tryMoveTo(moveController, corpse.getX(), corpse.getY(), corpse.getZ())) {
				log.info("Bot {} cannot reach the corpse it wanted to loot", getOwner().getName());
				pendingCorpse = 0;
				return false;
			}
			return true;
		}

		int looted = BotLootManager.lootAll(getOwner(), corpseId);
		pendingCorpse = 0;
		log.info("Bot {} looted {} item(s)", getOwner().getName(), looted);
		return false;
	}

	/**
	 * Walks a full bag to the nearest shop and sells what the bot has no use for, then lets it drift back to its anchor on its own.
	 *
	 * @return true while a trip is starting or under way, so nothing else is decided this tick.
	 */
	private boolean runVendorTrip() {
		Player bot = getOwner();
		if (vendorDestination == null) {
			// a full bag alone is not enough: one full of gear, quest items or anything rare never empties and would loop forever. An operator who
			// asked for a trip has already made that judgement, and keeps the bot going until it has actually sold: without this, a first shop spot
			// that turns out to be empty ends the errand here, since the bag it was never waiting for is still not full.
			if (!sellingOnDemand && (!BotVendorManager.hasFullBag(bot) || !BotVendorManager.hasJunk(bot)))
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
			log.info("Bot {} sold {} stack(s)", bot.getName(), sold);
			vendorDestination = null;
			sellingOnDemand = false;
			return false; // roams or fights again from here, and drifts back to its anchor like after any other trip
		}

		if (standUp())
			return true; // on its feet first, so it does not slide off
		if (!(bot.getMoveController() instanceof BotMoveController moveController))
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
		if (moveController.isBlocked() || !tryMoveTo(moveController, x, y, z)) {
			log.info("Bot {} cannot reach a shop and gives up selling", bot.getName());
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

	/** Puts the weapon away once the bot has really stopped fighting, rather than at the end of every single kill. */
	private void sheathWhenCalm() {
		if (combatEndedAt == 0 || isAttacking())
			return;
		if (System.currentTimeMillis() - combatEndedAt < SHEATHE_DELAY_MILLIS)
			return;
		combatEndedAt = 0;
		BotAttackManager.leaveAttackMode(getOwner());
	}

	private boolean standUp() {
		if (!BotRestManager.standUp(getOwner()))
			return false;
		standUpTime = System.currentTimeMillis();
		return true;
	}

	private boolean hasStoodUpLongEnough() {
		return System.currentTimeMillis() - standUpTime >= STAND_UP_MILLIS;
	}

	private void recover() {
		if (getOwner().getMoveController().isInMove())
			return;
		// Healing is far quicker than resting, but it spends mana that resting would have restored alongside the health. So it is worth a cast only
		// when there is a real wound to close and the mana to spare; a scratch, or an empty mana bar, and sitting down wins on both counts.
		if (getOwner().getLifeStats().getMpPercentage() >= BotSkillManager.HEAL_MIN_MP_PERCENT
			&& BotSkillManager.tryHealSelf(getOwner(), BotSkillManager.HEAL_AFTER_COMBAT_PERCENT))
			return;
		if (combatEndedAt != 0) {
			// put the weapon away before sitting: sheathing later, while seated, plays a standing animation on a seated body
			combatEndedAt = 0;
			BotAttackManager.leaveAttackMode(getOwner());
			return; // next tick sits down, once that animation is over
		}
		BotRestManager.sitDown(getOwner());
	}

	private boolean isHealthyEnoughToFight() {
		return getOwner().getLifeStats().getHpPercentage() >= MIN_ENGAGE_HP_PERCENT;
	}

	/**
	 * @return An npc currently attacking the bot, or null. Being hit overrides every other consideration, including the health threshold that
	 *         normally sends the bot resting: sitting down under fire is both suicidal and absurd to watch.
	 */
	private Creature findAttacker() {
		Player bot = getOwner();
		Creature[] attacker = { null };
		bot.getKnownList().forEachNpc(npc -> {
			// The aggro list, not getTarget(). A creature that has decided to kill this bot is not necessarily swinging at it this instant: it is
			// closing in, or it is mid-cast, or it is briefly aimed at something else, and through all of that getTarget() says anything but the bot.
			// This project already learned it once, for a creature attacking a group member, and left the bot's own defence reading the wrong answer:
			// aggressive monsters walked up and hit a bot that stood there as though nothing were happening.
			if (attacker[0] == null && !npc.isDead() && npc.getAggroList().isHating(bot))
				attacker[0] = npc;
		});
		return attacker[0];
	}

	private boolean isIgnored(Creature target) {
		Long until = ignoredTargets.get(target.getObjectId());
		if (until == null)
			return false;
		if (until > System.currentTimeMillis())
			return true;
		ignoredTargets.remove(target.getObjectId());
		return false;
	}

	@Override
	public boolean isDestinationReached() {
		// AITemplate returns false by default, which would prevent MoveTaskManager from ever firing MOVE_ARRIVED
		return !(getOwner().getMoveController() instanceof BotMoveController moveController) || moveController.isArrived();
	}

	@Override
	protected void handleMoveArrived() {
		if (!(getOwner().getMoveController() instanceof BotMoveController moveController))
			return;
		if (moveController.continueToGoal())
			return; // the goal is further away, another leg was started
		if (!moveController.isBlocked()) // being blocked is logged where it is detected
			log.info("Bot {} arrived at destination", getOwner().getName());
	}

	@Override
	protected void handleMoveValidate() {
		// the attack tick only runs at weapon speed, far too slow to notice the target came into reach while running at it
		if (chaseStartTime == 0 || !(getOwner().getTarget() instanceof Creature target) || !BotAttackManager.isInAttackRange(getOwner(), target))
			return;
		chaseStartTime = 0;
		stopMoving();
		// strike right away instead of standing next to the target until the scheduled tick comes round
		synchronized (combatLock) {
			if (attackTask != null) {
				stopAttackTask();
				scheduleAttackTick(0);
			}
		}
	}

	@Override
	protected void handleSpawned() {
		// without leaving AIState.CREATED, every event except (BEFORE_)SPAWNED is filtered out
		setStateIfNot(AIState.IDLE);
		setAnchor(getOwner().getX(), getOwner().getY(), getOwner().getZ());
		synchronized (combatLock) {
			scheduleBotTick();
		}
		log.info("Bot {} spawned", getOwner().getName());
	}

	@Override
	protected void handleAttack(Creature attacker) {
		if (autonomous && !isAttacking() && !attacker.equals(getOwner())) {
			log.info("Bot {} retaliates against {}", getOwner().getName(), attacker.getName());
			startAttacking(attacker);
		}
	}

	@Override
	protected void handleDied() {
		handleDeath();
	}

	/**
	 * Reacts to the bot being dead, at most once per death.
	 * <p>
	 * The engine only fires {@code AIEventType.DIED} from {@code NpcController}: {@code PlayerController.onDie} never notifies the AI, since real
	 * players have a dummy one. Rather than patch a core file upstream changes often, the decision tick notices the death itself.
	 */
	private void handleDeath() {
		synchronized (combatLock) {
			if (reviveTask != null) // already dealt with
				return;
			// nobody will ever click the resurrection window for a bot, so without this it lies dead forever
			reviveTask = ThreadPoolManager.getInstance().schedule(this::revive, REVIVE_DELAY_MILLIS);
		}
		Player bot = getOwner();
		// whatever it was fighting just won, so leave it alone for a while instead of walking straight back into it
		if (bot.getTarget() instanceof Creature killer)
			ignoredTargets.put(killer.getObjectId(), System.currentTimeMillis() + KILLER_AVOIDED_MILLIS);
		cancelCombat();
		setStateIfNot(AIState.DIED);
		log.info("Bot {} died at {} {} {} (anchor {} {} {})", bot.getName(), bot.getX(), bot.getY(), bot.getZ(), anchorX, anchorY, anchorZ);
	}

	/**
	 * Brings the bot back the way a player does: at its obelisk, on a quarter of its health, with soul sickness.
	 * <p>
	 * {@code bindRevive} rather than a revive on the spot, and that is the whole of it. Coming back where it fell — or at its anchor, which is the
	 * patch of ground it was working — puts the bot back within reach of whatever just killed it, at a quarter health: it dies again, comes back
	 * again, and the loop only ends when something else wanders past. An obelisk is far away on purpose, and the walk home is the price a player
	 * pays too.
	 * <p>
	 * Its anchor is moved home as well, because the spot it was working is on the far side of that walk and it has no business resuming there from
	 * across the region.
	 */
	private void revive() {
		Player bot = getOwner();
		try {
			if (!bot.isSpawned() || !bot.isDead())
				return;
			PlayerReviveService.bindRevive(bot);
			Vector3f home = homeOfThisBot();
			if (home != null)
				setAnchor(home.getX(), home.getY(), home.getZ());
			setStateIfNot(AIState.IDLE);
			log.info("Bot {} revived at {} {} {}", bot.getName(), bot.getX(), bot.getY(), bot.getZ());
		} finally {
			// cleared last, so the decision tick cannot mistake the bot for freshly dead while it is being brought back
			synchronized (combatLock) {
				reviveTask = null;
			}
		}
	}

	@Override
	protected void handleDespawned() {
		synchronized (combatLock) {
			if (thinkTask != null) {
				thinkTask.cancel(false);
				thinkTask = null;
			}
			if (reviveTask != null) {
				reviveTask.cancel(false);
				reviveTask = null;
			}
		}
		cancelCombat();
		setStateIfNot(AIState.DESPAWNED);
		log.info("Bot {} despawned", getOwner().getName());
	}
}
