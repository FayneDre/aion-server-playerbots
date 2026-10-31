package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Who is fighting a bot, or a mate of its group, among the enemy players it knows. The counterpart for players of what the AI does for monsters
 * with their aggro lists: a player who hits a bot goes on the bot's own aggro list like a monster does, with the same decay, so the list is the
 * record of who attacked lately.
 * <p>
 * Nothing here looks at the PvP option: defending against a real player is needed whether bots pick PvP fights or not.
 */
public final class BotPvpDefence {

	/** How far from the bot a player attacking one of its mates is still helped against. */
	public static final float ASSIST_RADIUS = 40f;

	private BotPvpDefence() {
	}

	/** @return Every living enemy player the bot knows that has attacked it lately and is not in {@code excluded}. */
	public static List<Player> attackersOf(Player bot, Predicate<Creature> excluded) {
		List<Player> attackers = new ArrayList<>();
		bot.getKnownList().forEachPlayer(player -> {
			if (!player.equals(bot) && !player.isDead() && player.isSpawned() && bot.isEnemy(player) && bot.getAggroList().isHating(player)
				&& !excluded.test(player))
				attackers.add(player);
		});
		return attackers;
	}

	/** @return The nearest of the bot's attackers, or null. */
	public static Player nearestAttacker(Player bot, Predicate<Creature> excluded) {
		return nearest(bot, attackersOf(bot, excluded));
	}

	/**
	 * @return The nearest living enemy player, in sight and within {@value #ASSIST_RADIUS} m, that has attacked a mate of the bot's group lately, or
	 *         null. A bot grouped with a player helps whatever its temperament; the enemy test keeps it from ever turning on a player of its own side.
	 */
	public static Player enemyOnAMate(Player bot, Predicate<Creature> excluded) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		List<Player> mates = team.getMembers().stream().filter(member -> !member.equals(bot)).toList();
		List<Player> found = new ArrayList<>();
		bot.getKnownList().forEachPlayer(player -> {
			if (!player.equals(bot) && !player.isDead() && player.isSpawned() && bot.isEnemy(player) && !excluded.test(player)
				&& mates.stream().anyMatch(mate -> mate.getAggroList().isHating(player))
				&& PositionUtil.getDistance(bot, player) <= ASSIST_RADIUS && GeoService.getInstance().canSee(bot, player))
				found.add(player);
		});
		return nearest(bot, found);
	}

	private static Player nearest(Player bot, List<Player> players) {
		Player nearest = null;
		double least = Double.MAX_VALUE;
		for (Player player : players) {
			double distance = PositionUtil.getDistance(bot, player);
			if (distance < least) {
				least = distance;
				nearest = player;
			}
		}
		return nearest;
	}
}
