package com.aionemu.gameserver.playerbot.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TeamType;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.team.group.PlayerGroup;
import com.aionemu.gameserver.model.team.group.PlayerGroupService;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Groups of bots with a bot at their head, which exist so that a group fight can be watched, and measured, without a player in the group.
 * <p>
 * An ordinary bot cannot lead: {@link BotGroupManager#leaveIfLeaderless} walks bots out of a group with no player in it, and
 * {@link BotGroupManager#leaderToFollow} never follows a bot. A squad is the one exception to both. Its leader goes on playing as it would alone, picking
 * fights round its camp, and waits before each pull for the squad to have recovered; the members follow it and assist it as they would a player.
 * The plan is {@code docs/test-harness-plan.md}.
 */
public final class BotSquads {

	private static final Logger log = LoggerFactory.getLogger(BotSquads.class);

	/** Most members a group takes, the leader included. */
	private static final int MAX_SIZE = 6;
	/** How far apart the members are put round the leader when a squad is formed. */
	private static final float FORMED_SPACING = 2.5f;
	/** How long after forming a squad its members are looked at again, and how far from the leader one may then be before it is brought over a second time. */
	private static final long ARRIVAL_CHECK_MILLIS = 2000;
	private static final float ARRIVED_WITHIN = 30;

	/** The team ids of the groups that are squads. A group keeps its id for as long as it exists, whatever happens to its leader. */
	private static final Set<Integer> squads = ConcurrentHashMap.newKeySet();

	private BotSquads() {
	}

	/** @return true if the team is a squad, whose leader may be a bot and which is not dissolved for having no player in it. */
	public static boolean isSquad(TemporaryPlayerTeam<?> team) {
		return team != null && squads.contains(team.getTeamId());
	}

	/** @return true if the bot leads a squad, and so picks the squad's fights itself. */
	public static boolean leads(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return isSquad(team) && bot.equals(team.getLeaderObject());
	}

	/**
	 * Puts spawned bots into a squad under the first one, brings the others to it, and makes the place it stands its camp.
	 *
	 * @return The answer for whoever asked.
	 */
	public static String form(Player leader, List<Player> members) {
		if (members.isEmpty())
			return "A squad needs at least one member besides its leader.";
		if (members.size() + 1 > MAX_SIZE)
			return "A group holds " + MAX_SIZE + " at most, the leader included.";
		List<Player> everyone = new ArrayList<>(members.size() + 1);
		everyone.add(leader);
		everyone.addAll(members);
		for (Player bot : everyone) {
			if (!(bot.getAi() instanceof PlayerBotAI))
				return bot.getName() + " is not driven by the bot AI.";
			if (bot.getCurrentTeam() != null)
				return bot.getName() + " is already in a group or an alliance.";
			if (bot.isDead())
				return bot.getName() + " is dead.";
			if (bot.getRace() != leader.getRace())
				return bot.getName() + " is not of " + leader.getName() + "'s race.";
			if (everyone.indexOf(bot) != everyone.lastIndexOf(bot))
				return bot.getName() + " is named twice.";
		}
		bringOver(leader, members);
		// Looked at again a moment later. In the first squad a cleric that had resurrected seconds before stayed at its obelisk 770 m away, for a reason
		// the log did not give, and walked the whole way afterwards; one that has not arrived is brought over once more, and the log says so.
		BotScheduler.getInstance().schedule(() -> {
			List<Player> strayed = members.stream().filter(member -> member.isSpawned() && !member.isDead()
				&& (member.getWorldId() != leader.getWorldId() || PositionUtil.getDistance(member, leader) > ARRIVED_WITHIN)).toList();
			if (strayed.isEmpty())
				return;
			log.info("Squad of {}: {} had not arrived, brought over again", leader.getName(), strayed.stream().map(Player::getName).toList());
			bringOver(leader, strayed);
		}, ARRIVAL_CHECK_MILLIS);
		PlayerGroup group = PlayerGroupService.createGroup(leader, members.getFirst(), TeamType.GROUP, 0);
		squads.add(group.getTeamId());
		for (Player member : members.subList(1, members.size()))
			PlayerGroupService.addPlayer(group, member);
		PlayerBotAI leaderAi = (PlayerBotAI) leader.getAi();
		leaderAi.setAnchor(leader.getX(), leader.getY(), leader.getZ());
		String names = String.join(", ", members.stream().map(Player::getName).toList());
		log.info("Squad {} formed: {} leads {} on map {} at {} {} {}", group.getTeamId(), leader.getName(), names, leader.getWorldId(),
			Math.round(leader.getX()), Math.round(leader.getY()), Math.round(leader.getZ()));
		return "Squad formed: " + leader.getName() + " leads " + names + ", camping where " + leader.getName() + " stands.";
	}

	private static void bringOver(Player leader, List<Player> members) {
		for (int i = 0; i < members.size(); i++) {
			double angle = 2 * Math.PI * i / members.size();
			TeleportService.teleportTo(members.get(i), leader.getWorldId(), leader.getInstanceId(), leader.getX() + (float) Math.cos(angle) * FORMED_SPACING,
				leader.getY() + (float) Math.sin(angle) * FORMED_SPACING, leader.getZ());
		}
	}

	/** Dissolves the squad the bot is in. */
	public static String disband(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (!isSquad(team) || !(team instanceof PlayerGroup group))
			return bot.getName() + " is not in a squad.";
		squads.remove(group.getTeamId());
		PlayerGroupService.disband(group);
		log.info("Squad {} disbanded", group.getTeamId());
		return "Squad of " + bot.getName() + " disbanded.";
	}

	/**
	 * @return true when every member of the bot's squad is alive and rested enough for the leader to pull, the way a player leading a group waits for its
	 *         healer's mana. The thresholds are the caller's: the same as a bot alone asks of itself.
	 */
	public static boolean isReady(Player leader, int minHpPercent, int minMpPercent) {
		TemporaryPlayerTeam<?> team = leader.getCurrentTeam();
		if (!isSquad(team))
			return true;
		for (Player member : team.getMembers()) {
			if (member.isDead() || member.getLifeStats().getHpPercentage() < minHpPercent || member.getLifeStats().getMpPercentage() < minMpPercent)
				return false;
		}
		return true;
	}

	/** @return One line per squad: its leader, its members, and the state of each. */
	public static String describe(List<Player> spawnedBots) {
		List<String> lines = new ArrayList<>();
		for (Player bot : spawnedBots) {
			if (!leads(bot))
				continue;
			StringBuilder line = new StringBuilder("Squad of " + bot.getName() + ":");
			for (Player member : bot.getCurrentTeam().getMembers())
				line.append(String.format(" %s (%s, %d%% hp, %d%% mp%s)", member.getName(), member.getPlayerClass(), member.getLifeStats().getHpPercentage(),
					member.getLifeStats().getMpPercentage(), member.isDead() ? ", dead" : ""));
			lines.add(line.toString());
		}
		return lines.isEmpty() ? "No squad." : String.join(System.lineSeparator(), lines);
	}
}
