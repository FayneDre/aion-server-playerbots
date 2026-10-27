package com.aionemu.gameserver.playerbot.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.world.BotLanding;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Refusals counted, and a bot freed from where it cannot move: stepped out of a pocket, put beside its leader, flown or taken home. Taken out of
 * {@link PlayerBotAI} on 27 October 2026, unchanged. Every move the AI asks for goes through {@link #tryMoveTo}, so that a bot refused everything
 * is noticed whichever of its errands it happened to be on.
 */
final class BotRescue {

	private static final Logger log = LoggerFactory.getLogger(BotRescue.class);

	private final PlayerBotAI ai;

	/** How many refusals in a row mean the bot is not blocked from somewhere but wedged where it stands. */
	private static final int STUCK_REFUSALS = 12;
	/** Refusals in a row, reset by any movement that starts. */
	private int refusedMoves;
	/**
	 * How many destinations in a row a bot may give up on from one spot before the spot is the fault rather than the destinations.
	 * <p>
	 * A count of its own, beside {@link #refusedMoves}, because that one never reaches its bound for a bot that is refused everything: a route is
	 * searched on another thread, the move is "on its way" until the search fails, and that answer resets the count. A gladiator walked onto a ledge
	 * under Pandaemonium's temple dome, 27 m above the floor, and gave up on every destination it drew for two days without being rescued once.
	 */
	private static final int ISOLATED_REFUSALS = 6;
	/** How far a bot may have moved between two give-ups for both to count as made from the same spot, in metres. */
	private static final float SAME_SPOT = 3;
	/** Where the give-ups being counted were made from, null when none is. */
	private Vector3f refusedFrom;
	/** Destinations given up on in a row from {@link #refusedFrom}. */
	private int refusedFromThere;
	/** The destinations given up for want of budget lately, which tell a bot that is lost. */
	private final LostTrail lostTrail = new LostTrail();
	/** The destination of the last move refused for searches out of budget, which the caller reports afterwards through {@link #destinationRefused}. */
	private Vector3f unprovenGoal;

	BotRescue(PlayerBotAI ai) {
		this.ai = ai;
	}

	/** Notes that the bot stands where it means to be, so whatever it was refused, the spot it stands on is not the fault. */
	void atItsSpot() {
		refusedFromThere = 0;
	}

	/**
	 * Notes that the bot gave up on where it was going, and frees it once it has given up on everything from the spot it stands on.
	 * <p>
	 * Not at home: a bot standing in its own village and refused a hunting ground has an unreachable hunting ground, and lifting it home moves nothing.
	 */
	void destinationRefused() {
		Player bot = ai.getOwner();
		Vector3f here = new Vector3f(bot.getX(), bot.getY(), bot.getZ());
		if (bot.getMoveController() instanceof BotMoveController moveController && moveController.isRefusalUnproven()) {
			// a far place whose searches gave up says nothing about this one (see BotMoveController.refusalUnproven), but every place doing so does
			Vector3f goal = unprovenGoal;
			unprovenGoal = null; // weighed once: a later refusal that set none must not count this one again
			// not isolated: a bot lost on open ground is no proof that the spot leads nowhere, and a player watching does not see it vanish
			if (lostTrail.gaveUp(here, goal, System.currentTimeMillis()))
				rescueFrom(here, "had its searches run out of budget for " + LostTrail.DESTINATIONS + " destinations", false);
			return;
		}
		if (refusedFrom == null || PositionUtil.getDistance(refusedFrom.getX(), refusedFrom.getY(), refusedFrom.getZ(), here.getX(), here.getY(),
			here.getZ()) > SAME_SPOT) {
			refusedFrom = here;
			refusedFromThere = 0;
		}
		if (++refusedFromThere < ISOLATED_REFUSALS)
			return;
		refusedFrom = null;
		refusedFromThere = 0;
		rescueFrom(here, "gave up on " + ISOLATED_REFUSALS + " destinations in a row", true);
	}

	/**
	 * Frees a bot whose spot is the fault, unless it is already at home, where the stranding is the home's business.
	 *
	 * @param isolated Whether the spot is proved to lead nowhere, which lifts the bot even in a player's sight; see {@link #freeItself}.
	 */
	private void rescueFrom(Vector3f here, String why, boolean isolated) {
		Player bot = ai.getOwner();
		Vector3f home = ai.day().home();
		if (home == null)
			return;
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), home.getX(), home.getY(), home.getZ());
		Vector3f doorstep = ground != null ? ground : home;
		if (PositionUtil.getDistance(here.getX(), here.getY(), here.getZ(), doorstep.getX(), doorstep.getY(), doorstep.getZ()) < STRANDED_AT_HOME)
			return;
		log.info("Bot {} {} from {} {} {}, so it is the spot that leads nowhere", bot.getName(), why, Math.round(here.getX()), Math.round(here.getY()),
			Math.round(here.getZ()));
		freeItself(isolated);
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
	boolean tryMoveTo(BotMoveController moveController, float x, float y, float z) {
		// A sidestep is not progress. moveToPoint answers "on its way" whenever the reactive layer found some direction to try, including the one it
		// has already tried and abandoned, so a bot wedged against scenery reported a move every tick and reset this counter every tick -- and the
		// rescue below, which exists for exactly that bot, was never reached. Asking the controller whether the journey is blocked is the honest
		// question: it says so only once it has given up, either walled in or making no headway, and it clears the moment a new journey is asked for.
		if (moveController.moveToPoint(x, y, z) && !moveController.isBlocked()) {
			refusedMoves = 0;
			return true;
		}
		if (moveController.isRefusalUnproven()) {
			unprovenGoal = new Vector3f(x, y, z); // weighed when the caller reports the refusal, see destinationRefused
			return false; // searches that ran out of budget: the journey is off, and nothing is known against the spot the bot stands on
		}
		if (++refusedMoves >= STUCK_REFUSALS)
			freeItself(false);
		return false;
	}

	/**
	 * How near its leader a grouped bot has to be for being unable to walk to count as waiting rather than being stuck.
	 * <p>
	 * Generous on purpose: a player who has flown off to a ledge is exactly the case this protects, and the distance to them says nothing about
	 * whether the bot is in trouble. Past it, the bot has been left behind rather than kept, and the ordinary rescue is right again.
	 */
	private static final float STAY_WITH_THE_GROUP = 200;
	/**
	 * How near its home a bot has to be stranded for the stranding to be evidence about the home, in metres.
	 * <p>
	 * A home has fifteen metres of scatter, so this is a little over it: near enough to be "where it lives", far enough not to miss a resident put
	 * down at the far edge of its own village.
	 */
	private static final float STRANDED_AT_HOME = 40;
	/** How near the ground a rescue would put a bot on it must stand for the rescue to be no move at all, in metres. */
	private static final float ALREADY_THERE = 5;
	/**
	 * How far a bot on ground cut off from where it lives may be stepped off it while a player watches: over a counter or a rail, off the plinth of
	 * an obelisk, not across a room. Five metres and pockets only until 24 October, when the floor round Altgard's obelisk, joined to nothing, kept a
	 * bot "doing something else" in front of a player 25 times in half an hour, the ground it needed 10 m away.
	 */
	private static final float STEP_OUT_IN_SIGHT = 12;

	/**
	 * Puts a bot that cannot move at all back where it lives.
	 * <p>
	 * However carefully a place is chosen, a bot walks on its own afterwards, and the reactive layer that steps round obstacles can walk it into one
	 * — behind a rock, under a root, onto a shelf. From inside, every destination is refused, including spots a pace away: three separate bots have
	 * now spent minutes asking for routes that do not exist from where they stand. There is no diagnosis to make at that point and nothing to walk
	 * out along, which is exactly why players are given an unstick command rather than advice.
	 *
	 * @param isolated Whether the bot has given up on everything from where it stands, rather than been refused a few moves: it is then lifted even
	 *          with a player looking, since a body standing in the air over a capital's crowd is a worse sight than one that blinks out of it.
	 */
	private void freeItself(boolean isolated) {
		Player bot = ai.getOwner();
		Vector3f home = ai.day().home();
		refusedMoves = 0;
		if (home == null)
			return;
		// Onto ground the mesh agrees a body stands on, which is not the same as the recorded home. A home is a spot chosen from spawn data, and
		// sending a bot back to one that sits on a roof or inside a rock is how the rescue came to feed the fault it exists to cure: one bot was put
		// back home five times and was still stuck twenty-five minutes later, because every landing was as unwalkable as the place it left.
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), home.getX(), home.getY(), home.getZ());
		float x = ground != null ? ground.getX() : home.getX();
		float y = ground != null ? ground.getY() : home.getY();
		float z = ground != null ? ground.getZ() : home.getZ();

		// A body blinking across the ground in front of somebody is the fault itself, not its cure, so a bot a player can see is not lifted: it drops
		// its errand and picks another, and the rescue waits for nobody to be looking. Except in a player's group, where staying stuck is worse than
		// being seen: the player is waiting for it, and a member left behind is a member lost.
		if (!isolated && !isInAPlayersGroup(bot) && isSeenByAPlayer(bot)) {
			// Except off ground that leads nowhere, onto floor a step away. Doing something else never ends there, since every something else is a route
			// out: a bot shut behind a shop counter sat down and stood up every thirteen seconds in front of the player for as long as they stayed. A step
			// over the counter is less of a sight than that. Any ground cut off from the bot's anchor, not only a pocket: toWalkableGround answers only
			// for a bot that is trapped, and the floor round an obelisk joined to nothing is too large to be a pocket.
			Vector3f step = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), ai.anchor(), bot.getObjectId(), false);
			if (step != null && PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), step.getX(), step.getY(), step.getZ()) <= STEP_OUT_IN_SIGHT) {
				log.info("Bot {} could not move at all from {} {}, on ground cut off from where it lives, and steps out onto {} {} {} in sight of a player", bot.getName(),
					Math.round(bot.getX()), Math.round(bot.getY()), Math.round(step.getX()), Math.round(step.getY()), Math.round(step.getZ()));
				forgetRefusals();
				TeleportService.teleportTo(bot, bot.getWorldId(), step.getX(), step.getY(), step.getZ());
				return;
			}
			log.info("Bot {} could not move at all from {} {}, but a player has it in sight, so it does something else rather than being lifted", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()));
			ai.day().forget();
			forgetRefusals();
			return;
		}

		// A bot in somebody's group is where it is for a reason, and a lift home is the one thing that cannot be allowed to happen to it: the whole
		// point of following somebody is being with them. It waits instead -- for the player to land, to come back, or for its own flight points.
		// Reported from in game as a bot that followed into the air, landed, and was gone: not a flight fault at all, but the oldest rescue in the
		// module doing what it was written to do to a bot that had never been stuck.
		// In an instance at any distance: the player went on by a teleporter, a lever, a lift or a door their key opened, which a bot does not use, and
		// the ordinary rescue below would send it to its home's coordinates on the instance's own map.
		// A player's group at any distance as well: a player who has gone a long way off went by something the bot does not use, and home is the one
		// place it should not be put. On 20 October three members were rescued to their homes across Eltnen while their player was on a flight.
		Player leader = BotGroupManager.leaderToFollow(bot);
		if (leader != null && (bot.isInInstance() || isInAPlayersGroup(bot) && leader.getWorldId() == bot.getWorldId() || PositionUtil.getDistance(bot, leader) <= STAY_WITH_THE_GROUP)) {
			// Waiting is for a leader in the air. One standing on the ground can be walked to from somewhere, and a bot that cannot is in a pocket, which
			// waiting never ends: in the first squads a cleric and a ranger stood in one for minutes, 18 to 45 m from their leader, giving up every
			// target the group fought. It is put on the nearest floor joined to the leader's, which keeps it with the group as the wait meant to.
			if (!leader.isFlying()) {
				Vector3f out = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()),
					new Vector3f(leader.getX(), leader.getY(), leader.getZ()), bot.getObjectId(), false);
				if (out != null) {
					log.info("Bot {} could not move at {} {}, cut off from {}, and is put on walkable ground at {} {} {}{}", bot.getName(), Math.round(bot.getX()),
						Math.round(bot.getY()), leader.getName(), Math.round(out.getX()), Math.round(out.getY()), Math.round(out.getZ()), witnessed(bot));
					forgetRefusals(); // what was refused was refused from where it stood
					TeleportService.teleportTo(bot, bot.getWorldId(), out.getX(), out.getY(), out.getZ());
					return;
				}
			}
			if (!leader.isFlying()) {
				// No floor joined to the leader's near the bot: waiting would never end, so it goes to the leader, onto the ground under their feet
				Vector3f beside = NavmeshService.getInstance().groundNear(bot.getWorldId(), leader.getX(), leader.getY(), leader.getZ());
				float toX = beside != null ? beside.getX() : leader.getX(), toY = beside != null ? beside.getY() : leader.getY();
				float toZ = beside != null ? beside.getZ() : leader.getZ();
				log.info("Bot {} could not move at {} {}, cut off from {}, and is put beside them at {} {} {}{}", bot.getName(), Math.round(bot.getX()),
					Math.round(bot.getY()), leader.getName(), Math.round(toX), Math.round(toY), Math.round(toZ), witnessed(bot));
				forgetRefusals(); // what was refused was refused from where it stood
				TeleportService.teleportTo(bot, bot.getWorldId(), toX, toY, toZ);
				return;
			}
			// a leader in the air lands somewhere, and the bot follows it there; lifting it now would only put it under them
			log.info("Bot {} cannot move from {} {} but is {} m from {}, so it waits rather than being put back home", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()), Math.round(PositionUtil.getDistance(bot, leader)), leader.getName());
			return;
		}
		if (bot.isInInstance()) {
			// no leader here to be put beside, and its home is on another map: it waits where it is, and BotInstances takes it out
			log.info("Bot {} cannot move from {} {} in map {} and has no leader there, so it waits", bot.getName(), Math.round(bot.getX()),
				Math.round(bot.getY()), bot.getWorldId());
			forgetRefusals();
			return;
		}

		// Ground it can walk away from, close by, before anything that takes it across the map: a bot that cannot leave its pocket does not need its
		// home, it needs the floor beside it. Only when the mesh says the spot is cut off from its anchor or is inside geometry; one that is merely
		// wedged against a prop on connected ground is left to the rescue below.
		Vector3f near = BotLanding.toWalkableGround(bot.getWorldId(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), ai.anchor(), bot.getObjectId(),
			bot.getCommonData().isDaeva() && !bot.getTransformModel().cantFly());
		if (near != null) {
			log.info("Bot {} could not move at {} {} {}, which the map does not join to its anchor, and is put on walkable ground at {} {} {}{}", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()), Math.round(bot.getZ()), Math.round(near.getX()), Math.round(near.getY()), Math.round(near.getZ()),
				witnessed(bot));
			forgetRefusals(); // what was refused was refused from where it stood
			TeleportService.teleportTo(bot, bot.getWorldId(), near.getX(), near.getY(), near.getZ());
			return;
		}
		// A bot that can fly out of here is not stuck, and a lift home is the wrong answer to a problem it can solve itself. This is now the ordinary
		// way a flight ends somewhere with no way off it: an escort breaks off over a ledge, lands on it, finds every walking route refused -- and
		// goes home through the air it came by, once its flight points are back.
		if (BotFlight.tryFlyTo(bot, new Vector3f(x, y, z))) {
			log.info("Bot {} could not walk off {} {} and is flying home instead", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()));
			ai.day().forget();
			return;
		}
		// Counted only when it happened at home, which is the one place a stranding says anything about. The count exists to recognise a home a bot
		// cannot walk out of -- without it, that rescue runs every thirteen seconds for as long as the server is up. Stranded somewhere else, the bot
		// went there: on a terrace it flew to, at the end of an errand, behind a rock. Rehousing it for that moves a resident across the map for
		// having gone somewhere, which is what an escort landing on a ledge made it do -- three rescues in under a minute, and its home 1200 m away.
		boolean atHome = PositionUtil.getDistance(bot.getX(), bot.getY(), home.getX(), home.getY()) < STRANDED_AT_HOME;
		ai.day().forget(); // whatever it was doing was decided from a place it is no longer in
		Vector3f moved = atHome ? ai.day().recordStranding() : null;
		if (moved != null) {
			x = moved.getX();
			y = moved.getY();
			z = moved.getZ();
		} else if (PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), x, y, z) < ALREADY_THERE) {
			// in three dimensions: a bot on a ledge straight above its front door is not at its front door
			// A lift onto the spot the bot stands on moves nothing and shows a body blinking: 136 of 403 rescues in half an hour were that. A bot standing
			// at its own front door asking for somewhere the planner cannot find is not stuck, its destination is, and forgetting it was the cure.
			log.info("Bot {} could not move at all from {} {}, where it lives, and is left there with its errand forgotten", bot.getName(),
				Math.round(bot.getX()), Math.round(bot.getY()));
			forgetRefusals();
			return;
		}
		log.info("Bot {} could not move at all from {} {} and is put back home{}{}", bot.getName(), Math.round(bot.getX()), Math.round(bot.getY()),
			atHome && moved == null ? ", which is where it already was" : "", witnessed(bot));
		forgetRefusals(); // what was refused was refused from where it stood
		TeleportService.teleportTo(bot, bot.getWorldId(), x, y, z);
		ai.setAnchor(x, y, z);
	}

	private void forgetRefusals() {
		if (ai.getOwner().getMoveController() instanceof BotMoveController moveController)
			moveController.forgetRefusals();
	}

	/**
	 * @return A note for the log when a real player has the bot in sight, the same question {@link com.aionemu.gameserver.playerbot.PlayerBotService#sleep} asks before taking one away:
	 *         a teleport nobody sees is a fault in the log, one somebody sees is a fault in the game.
	 */
	static String witnessed(Player bot) {
		return isSeenByAPlayer(bot) ? " (seen by a player)" : "";
	}

	/** @return true if a real player has the bot in sight, the same question {@link com.aionemu.gameserver.playerbot.PlayerBotService#sleep} asks. */
	private static boolean isSeenByAPlayer(Player bot) {
		return bot.getKnownList().streamPlayers().anyMatch(other -> !other.isBot());
	}

	/** @return true if the bot's group has a real player in it, as opposed to a squad of bots or no group at all. */
	private static boolean isInAPlayersGroup(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return team != null && team.getMembers().stream().anyMatch(member -> !member.isBot());
	}
}
