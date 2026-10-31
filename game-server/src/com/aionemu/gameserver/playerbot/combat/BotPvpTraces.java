package com.aionemu.gameserver.playerbot.combat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.social.BotChat;

/**
 * What is left of a player against player death for the measure: one log line per kill in which a bot is the killer or the victim, and the killer's
 * word in Region when it is a bot. Called by {@code PvpService} once the winner is known.
 */
public final class BotPvpTraces {

	private static final Logger log = LoggerFactory.getLogger(BotPvpTraces.class);

	private BotPvpTraces() {
	}

	/**
	 * Logs the kill if a bot took part in it. The line starts with {@code PVP KILL} so that the measure counts it, and names both sides with their kind,
	 * race and level, and the place.
	 */
	public static void onKill(Player killer, Player victim) {
		if (!killer.isBot() && !victim.isBot())
			return;
		log.info(line(killer, victim));
		if (killer.isBot())
			BotChat.onPlayerKilled(killer, victim);
	}

	static String line(Player killer, Player victim) {
		return "PVP KILL " + describe("killer", killer) + " " + describe("victim", victim) + " at map " + victim.getWorldId() + " " + Math.round(victim.getX())
			+ " " + Math.round(victim.getY()) + " " + Math.round(victim.getZ());
	}

	private static String describe(String role, Player player) {
		return role + " " + player.getName() + " (" + (player.isBot() ? "bot" : "player") + " " + player.getRace() + " " + player.getLevel() + ")";
	}
}
