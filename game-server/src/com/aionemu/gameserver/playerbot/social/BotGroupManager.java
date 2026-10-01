package com.aionemu.gameserver.playerbot.social;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.team.group.PlayerGroupService;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;

/**
 * What a bot does about being in a group: joining one, staying with it, and fighting what it fights.
 */
public class BotGroupManager {

	/** How far from itself a bot looks for a fight its group is already in. Beyond this it is someone else's problem, or the group is scattered. */
	private static final float ASSIST_RADIUS = 25f;
	/** How far from the leader a follower stands. Close enough to be with the group, far enough not to be inside it. */
	private static final float FORMATION_RADIUS = 3f;
	/** How far a bot may sit off its exact share of the circle, as a fraction of the gap to its neighbour. */
	private static final float FORMATION_ANGLE_SPREAD = 0.35f;
	/** How much nearer or further than the nominal radius a bot may stand. */
	private static final float FORMATION_RADIUS_SPREAD = 0.25f;

	private BotGroupManager() {
	}

	/**
	 * Leaves a group that has nobody left to lead it.
	 * <p>
	 * A bot cannot lead — leading means answering invitations and setting loot rules — so a group whose last player has gone is a group that will
	 * never decide anything again. Staying in it would leave bots counting each other as team mates for targeting and loot for ever, so they walk
	 * out and go back to their own business, which is what a player would do.
	 *
	 * @return true if the bot left, in which case it is no longer in a group.
	 */
	public static boolean leaveIfLeaderless(Player bot) {
		if (bot.getCurrentTeam() == null || hasPlayerMember(bot))
			return false;
		if (bot.getPlayerGroup() == null)
			return false; // an alliance is not ours to dissolve, and bots are never invited into one on purpose
		PlayerGroupService.removePlayer(bot);
		return true;
	}

	/**
	 * @return true when a real player is still in the bot's team.
	 *         <p>
	 *         Two unrelated questions turn out to be this one: whether the group still has anybody who can lead it, and whether the bot is somebody's
	 *         companion right now rather than one of the world's own inhabitants.
	 *         <p>
	 *         A team keeps members in {@code getMembers()} that it no longer counts, so membership is confirmed rather than assumed — without that,
	 *         a player who has left is still found here and the bot goes on treating itself as grouped with them.
	 */
	public static boolean hasPlayerMember(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return false;
		for (Player member : team.getMembers()) {
			if (!member.isBot() && team.hasMember(member.getObjectId()))
				return true;
		}
		return false;
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
	 * Where a bot stands when it has nothing to do but keep up: its own place around the leader rather than the leader's own feet.
	 * <p>
	 * Every follower aiming at the same point is what made them pile onto their leader and jostle each other for it. A slot each fixes both at once:
	 * they stand beside rather than on top, and they stop competing for one spot, which is most of what read as restlessness.
	 * <p>
	 * The places are laid out in <b>world</b> directions, not relative to where the leader faces. Tied to a facing, the whole formation would swing
	 * round every time the leader turned on the spot, and send everyone running for no reason — the opposite of what this is for. Members are ordered
	 * by object id so a bot keeps the same place from one tick to the next.
	 *
	 * @return The point to stand on, or the leader's own position if the group cannot be read.
	 */
	public static Vector3f formationSpot(Player bot, Player leader) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return new Vector3f(leader.getX(), leader.getY(), leader.getZ());
		List<Player> followers = new ArrayList<>();
		for (Player member : team.getMembers()) {
			if (!member.equals(leader))
				followers.add(member);
		}
		followers.sort(Comparator.comparingInt(Player::getObjectId));
		int slot = Math.max(0, followers.indexOf(bot));
		int count = Math.max(1, followers.size());
		double share = 2 * Math.PI / count;
		// An exact share of an exact circle is what a surveyor would lay out, and it reads that way. Each bot sits a little off its share and a
		// little nearer or further than the rest, by an amount fixed to its own id: the group settles in a loose knot instead of on a ring, and the
		// same bot takes the same liberty every time, so nothing drifts or swaps places from one tick to the next.
		double angle = slot * share + share * FORMATION_ANGLE_SPREAD * spread(bot.getObjectId());
		float radius = FORMATION_RADIUS * (1 + FORMATION_RADIUS_SPREAD * spread(bot.getObjectId() * 31));
		return new Vector3f(leader.getX() + (float) Math.cos(angle) * radius, leader.getY() + (float) Math.sin(angle) * radius, leader.getZ());
	}

	/** @return A number between -1 and 1, the same one every time for a given seed. */
	private static float spread(int seed) {
		return Math.floorMod(Integer.hashCode(seed * 0x9E3779B9), 2000) / 1000f - 1;
	}

	/**
	 * @return The bot's group leader, or null when it is not in a group, leads it itself, or the leader is somewhere it cannot walk to.
	 */
	public static Player leaderToFollow(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		Player leader = team.getLeaderObject();
		// A team keeps its leader field when that member leaves, and nothing replaces a leader a bot is not allowed to become — so the group of a
		// player who has just walked out still names them, and bots went on following someone who was no longer in it.
		if (leader == null || leader.equals(bot) || leader.isBot() || !team.hasMember(leader.getObjectId()))
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
		Creature chosen = engagedTarget(bot, team.getLeaderObject());
		return chosen != null ? chosen : npcFightingTheTeam(bot, team);
	}

	/**
	 * Anything in sight that has picked a fight with the group, the leader's attacker first.
	 * <p>
	 * Reading the leader's selection was not enough, and standing by while its leader was being eaten is exactly how that showed. A player whose
	 * selection is on something else, or who never clicked the mob that jumped them, is being attacked all the same. The mob's own aggro list is the
	 * honest source: it remembers who it is fighting, whatever anyone has selected.
	 */
	private static Creature npcFightingTheTeam(Player bot, TemporaryPlayerTeam<?> team) {
		Player leader = team.getLeaderObject();
		// getMembers, not getOnlineMembers: the latter filters on isOnline, which is the very question bots make ambiguous
		List<Player> members = team.getMembers();
		Creature[] hatingLeader = { null };
		Creature[] hatingAnyone = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (!isWorthAssistingOn(bot, npc))
				return;
			if (leader != null && !leader.equals(bot) && npc.getAggroList().isHating(leader)) {
				if (hatingLeader[0] == null)
					hatingLeader[0] = npc;
			} else if (hatingAnyone[0] == null && hatesAnyMember(npc, members, bot)) {
				hatingAnyone[0] = npc;
			}
		});
		return hatingLeader[0] != null ? hatingLeader[0] : hatingAnyone[0];
	}

	/**
	 * @return The group mate in most need of a heal, or null. The bot itself is left out: it heals itself on its own thresholds, and healing is the
	 *         one thing it can do for others that they cannot do for themselves.
	 */
	public static Player mostHurtMember(Player bot, int belowPercent) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		Player worst = null;
		int worstPercent = belowPercent;
		for (Player member : team.getMembers()) {
			if (member.equals(bot) || member.isDead() || !isNearEnoughToHelp(bot, member))
				continue;
			int percent = member.getLifeStats().getHpPercentage();
			if (percent < worstPercent) {
				worstPercent = percent;
				worst = member;
			}
		}
		return worst;
	}

	/** @return A group mate to look after, or null. Used to spread buffs around without a list of who has what. */
	public static List<Player> membersToTendTo(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return List.of();
		List<Player> nearby = new ArrayList<>();
		for (Player member : team.getMembers()) {
			if (!member.equals(bot) && !member.isDead() && isNearEnoughToHelp(bot, member))
				nearby.add(member);
		}
		return nearby;
	}

	private static boolean isNearEnoughToHelp(Player bot, Player member) {
		return member.getWorldId() == bot.getWorldId() && PositionUtil.getDistance(bot, member) <= ASSIST_RADIUS;
	}

	private static boolean hatesAnyMember(Npc npc, List<Player> members, Player bot) {
		for (Player member : members) {
			if (!member.equals(bot) && npc.getAggroList().isHating(member))
				return true;
		}
		return false;
	}

	private static boolean isWorthAssistingOn(Player bot, Npc npc) {
		if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc))
			return false;
		return PositionUtil.getDistance(bot, npc) <= ASSIST_RADIUS && bot.canSee(npc);
	}

	/**
	 * @return What this member is actually fighting, or null. Selecting a target is not fighting it: players click things to read their level all the
	 *         time, and a group whose bots pull whatever the leader looks at is unusable. The mob's own aggro list settles it — it holds a grudge
	 *         against whoever has hit it, and equally against whoever it decided to attack, so a leader under attack is assisted too.
	 */
	private static Creature engagedTarget(Player bot, Player member) {
		if (member == null || member.equals(bot) || !(member.getTarget() instanceof Creature target))
			return null;
		if (target.isDead() || !target.isSpawned() || !bot.isEnemy(target) || target instanceof Player)
			return null;
		if (!target.getAggroList().isHating(member))
			return null;
		return bot.canSee(target) ? target : null;
	}
}
