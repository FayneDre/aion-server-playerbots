package com.aionemu.gameserver.playerbot.social;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;

/**
 * What a bot does about being in a group: joining one, staying with it, and fighting what it fights.
 */
public class BotGroupManager {

	private BotGroupManager() {
	}

	/**
	 * Answers a pending group or alliance invitation.
	 * <p>
	 * A real player gets a window and clicks yes. A bot has no window, so the decision tick answers for it. {@code respond} returns false when there
	 * was nothing pending, which makes asking the whole test — no new engine api needed to find out whether an invitation arrived.
	 *
	 * @return true if an invitation was accepted.
	 */
	public static boolean acceptPendingInvite(Player bot) {
		return bot.getResponseRequester().respond(SM_QUESTION_WINDOW.STR_PARTY_DO_YOU_ACCEPT_INVITATION, 1)
			|| bot.getResponseRequester().respond(SM_QUESTION_WINDOW.STR_FORCE_DO_YOU_ACCEPT_INVITATION, 1);
	}

	/**
	 * @return The bot's group leader, or null when it is not in a group, leads it itself, or the leader is somewhere it cannot walk to.
	 */
	public static Player leaderToFollow(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		Player leader = team.getLeaderObject();
		if (leader == null || leader.equals(bot) || leader.isBot())
			return null;
		// another map is not a navigation problem but a travel one, and bots cannot travel yet
		return leader.getWorldId() == bot.getWorldId() && leader.getInstanceId() == bot.getInstanceId() ? leader : null;
	}

	/**
	 * What the bot's team is already fighting, which is what a grouped player helps with rather than pulling a second mob onto the group.
	 * <p>
	 * The leader is asked first, so a group converges on one target instead of each member assisting whoever is nearest. Nothing here checks the
	 * level gap a bot applies to fights it picks itself: the group has chosen this fight, and refusing to help because the mob is big is the one
	 * thing a member must not do.
	 *
	 * @return The target to assist, or null if the team is not fighting anything reachable.
	 */
	public static Creature targetToAssist(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		Player leader = team.getLeaderObject();
		Creature leaderTarget = leader == null ? null : engagedTarget(bot, leader);
		if (leaderTarget != null)
			return leaderTarget;
		// getMembers, not getOnlineMembers: the latter filters on isOnline, which is the very question bots make ambiguous
		for (Player member : team.getMembers()) {
			Creature target = engagedTarget(bot, member);
			if (target != null)
				return target;
		}
		return null;
	}

	private static Creature engagedTarget(Player bot, Player member) {
		if (member.equals(bot) || !(member.getTarget() instanceof Creature target))
			return null;
		if (target.isDead() || !target.isSpawned() || !bot.isEnemy(target) || target instanceof Player)
			return null;
		return bot.canSee(target) ? target : null;
	}
}
