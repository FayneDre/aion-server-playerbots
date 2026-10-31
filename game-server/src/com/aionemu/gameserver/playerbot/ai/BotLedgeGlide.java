package com.aionemu.gameserver.playerbot.ai;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.LedgeGlides;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * A bot at its leisure now and then glides off an edge near it to the ground below, for the look of it, where a real player can see.
 * <p>
 * Nothing else makes a bot glide by choice: the planner glides only where the walk does not reach, and a group follows its leader's glide. Bots are seen walking
 * everywhere and never leaving a height any other way than on foot. Looked at once a minute while a bot strolls or loiters, one look in twenty going for an edge, and only with a
 * real player within {@link #PLAYER_RANGE}: a glide nobody sees is time for nothing. {@link NavmeshService#findLedge} vets the edge (the air clear, the landing off siege ground,
 * the ground below joined to the bot's by a walk). See docs/navigation/plans/glide-use-plan.md, milestone 2.
 */
final class BotLedgeGlide {

	private static final Logger log = LoggerFactory.getLogger(BotLedgeGlide.class);

	/** How near a real player must be for a bot to glide at all, in metres. */
	static final float PLAYER_RANGE = 150;
	/** How far from itself a bot looks for an edge, and the heights it will glide down. */
	private static final float LEDGE_RADIUS = 25, MIN_DROP = 8, MAX_DROP = 40;
	/** The chance, at each look, that the bot goes for an edge: about one look a minute, so about one glide in twenty minutes of leisure near a player. */
	private static final double CHANCE = 0.05;
	private static final long LOOK_EVERY_MILLIS = 60_000, REST_MILLIS = 10 * 60_000L, WALK_TO_EDGE_MILLIS = 25_000;
	/** Near enough to the take-off to glide: the walk ends where its arrival test says, which is not always on the very cell of the edge. */
	private static final float AT_EDGE = 2.5f;
	/** A bot hurt more than this does not go for an edge: it is recovering, and a glide ends in a landing among whatever hurt it. */
	private static final int MIN_HP_PERCENT = 70;

	private final PlayerBotAI ai;
	private volatile long nextLookAt;
	private volatile LedgeGlides.Ledge ledge;
	private volatile long giveUpAt;

	BotLedgeGlide(PlayerBotAI ai) {
		this.ai = ai;
	}

	/** @return true if the tick was taken: the bot is walking to an edge, or off it. */
	boolean tick() {
		if (!PlayerBotConfig.AMBIENT_GLIDE)
			return false;
		Player bot = ai.getOwner();
		long now = System.currentTimeMillis();
		// asked again on every tick, not only when an edge is chosen: a bot that joins a group, is taken in hand, takes to the air or is hurt on its way to the edge stops going
		if (bot.isInTeam() || ai.isOwned() || BotFlight.isInTheAir(bot) || bot.getLifeStats().getHpPercentage() < MIN_HP_PERCENT) {
			if (ledge != null)
				done(now);
			return false;
		}
		if (ledge == null) {
			if (now < nextLookAt)
				return false;
			nextLookAt = now + LOOK_EVERY_MILLIS;
			if (ThreadLocalRandom.current().nextDouble() >= CHANCE || !playerNear(bot))
				return false;
			ledge = NavmeshService.getInstance().findLedge(bot, LEDGE_RADIUS, MIN_DROP, MAX_DROP);
			if (ledge == null)
				return false;
			giveUpAt = now + WALK_TO_EDGE_MILLIS;
			log.info("Bot {} goes to glide off the edge at {} to {}", bot.getName(), describe(ledge.takeoff()), describe(ledge.landing()));
		}
		BotMoveController moves = ai.moves();
		Vector3f takeoff = ledge.takeoff();
		if (moves == null || now > giveUpAt || ai.posture().standUp()) {
			if (moves == null || now > giveUpAt)
				done(now);
			return moves != null && now <= giveUpAt;
		}
		if (PositionUtil.getDistance(bot.getX(), bot.getY(), takeoff.getX(), takeoff.getY()) <= AT_EDGE) {
			boolean glided = moves.glideAlong(List.of(ledge.landing()));
			log.info("Bot {} {}", bot.getName(), glided ? "glides off the edge" : "could not glide off the edge");
			done(now);
			return glided;
		}
		return moves.moveToPoint(takeoff.getX(), takeoff.getY(), takeoff.getZ());
	}

	private void done(long now) {
		ledge = null;
		nextLookAt = now + REST_MILLIS;
	}

	private static boolean playerNear(Player bot) {
		boolean[] seen = { false };
		bot.getKnownList().forEachPlayer(other -> {
			if (!other.isBot() && PositionUtil.getDistance(bot, other) <= PLAYER_RANGE)
				seen[0] = true;
		});
		return seen[0];
	}

	private static String describe(Vector3f point) {
		return Math.round(point.getX()) + " " + Math.round(point.getY()) + " " + Math.round(point.getZ());
	}
}
