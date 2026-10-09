package com.aionemu.gameserver.playerbot.social;

import com.aionemu.gameserver.controllers.attack.AggroTarget;
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
	/**
	 * How many points of health being under attack is worth, when a healer weighs who to tend to first. It is both a tie-break and a widening: a
	 * member this far above the healing threshold is healed anyway while something is hitting it, because by the time the cast lands it will be
	 * under it.
	 */
	private static final int UNDER_FIRE_URGENCY = 20;

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
	 * What a grouped bot fights, by the guide's two rules in order: the enemy marked with the skull, and failing that the enemy fighting the group (hitting a
	 * member or the bot itself) that has the least health. Every class follows them, so a group converges on one target instead of each member assisting
	 * whoever is nearest or whatever the leader happens to have clicked. Nothing here checks the
	 * level gap a bot applies to fights it picks itself: the group has chosen this fight, and refusing to help because the mob is big is the one
	 * thing a member must not do.
	 *
	 * @return The target to assist, or null if the team is not fighting anything reachable.
	 */
	public static Creature targetToAssist(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		// The mark before anyone's selection: it is what the tank chose for the whole group, and a group in which each member follows its own target is
		// the thing it exists to stop.
		Creature marked = BotMarks.skulled(bot);
		if (marked != null)
			return marked;
		// Otherwise the enemy that is fighting the group with the least health, which is the whole of the second rule and is the same for every class.
		return BotMarks.weakestEngaged(bot);
	}

	/**
	 * Finds a monster that has got loose and is hitting somebody else in the group.
	 * <p>
	 * A tank's job is not one monster, it is all of them, and holding only the one it is swinging at is what a group feels as "the templar cannot
	 * cope with more than one" — reported from the Fire Temple exactly that way. The target the bot is attacking is already handled by preferring
	 * taunts on it; this is the other half, the one nobody is pulling back.
	 * <p>
	 * Whoever it is on, rather than the worst case, because the aggro list says who is most hated and not who can least afford it, and a rule that
	 * ranked mates would be guessing at a group's intentions. First found is enough: the taunt is cheap and comes round again every swing.
	 *
	 * @return An npc whose chosen victim is a team mate other than this bot, or null when the tank already has them all.
	 */
	public static Creature enemyLooseOnAMate(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		List<Player> members = team.getMembers();
		Creature[] found = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (found[0] != null || !isWorthAssistingOn(bot, npc))
				return;
			// The one it is actually hitting, not merely one it holds a grudge against: a monster hates everything that has touched it, and a tank
			// that answered every grudge would spend the fight taunting things already looking at it.
			Creature victim = npc.getAggroList().getTarget(AggroTarget.MOST_HATED);
			if (victim != null && !victim.equals(bot) && members.contains(victim))
				found[0] = npc;
		});
		return found[0];
	}


	/**
	 * @return How many enemies are within reach of the radius that have picked a fight with the group, the bot itself included. For an ability that
	 *         is centred on the caster: casting it with nobody inside its radius is a cast spent on nothing.
	 */
	public static int enemiesFightingTheGroupWithin(Player bot, float radius) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return 0;
		List<Player> members = team.getMembers();
		int[] count = { 0 };
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.isDead() && npc.isSpawned() && bot.isEnemy(npc) && PositionUtil.getDistance(bot, npc) <= radius
				&& (npc.getAggroList().isHating(bot) || hatesAnyMember(npc, members, bot)))
				count[0]++;
		});
		return count[0];
	}

	/**
	 * @return The group mate in most need of a heal, or null. The bot itself is left out: it heals itself on its own thresholds, and healing is the
	 *         one thing it can do for others that they cannot do for themselves.
	 */
	public static Player mostHurtMember(Player bot, int belowPercent) {
		return mostHurtMember(bot, belowPercent, false);
	}

	/**
	 * @param anticipate Whether to count the danger a member is in as well as the damage already done to it, which is what separates a healer from
	 *          everyone else who happens to carry a heal. Lowest health alone is a rule that always acts one beat late: the member on 80% with three
	 *          mobs on it is the one about to die, and the member on 60% that nothing is hitting will be fine. A healer that waits for the bar to
	 *          drop heals a corpse.
	 */
	public static Player mostHurtMember(Player bot, int belowPercent, boolean anticipate) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		List<Player> candidates = new ArrayList<>();
		int widened = belowPercent + (anticipate ? UNDER_FIRE_URGENCY : 0);
		for (Player member : team.getMembers()) {
			if (member.equals(bot) || member.isDead() || !isNearEnoughToHelp(bot, member))
				continue;
			if (member.getLifeStats().getHpPercentage() < widened)
				candidates.add(member);
		}
		if (candidates.isEmpty())
			return null;
		// one sweep of the neighbourhood for the lot, rather than one per member: this runs on every tick of every healer in every group
		List<Player> underFire = anticipate ? membersUnderFire(bot, candidates) : List.of();
		Player worst = null;
		int worstUrgency = belowPercent;
		for (Player member : candidates) {
			int urgency = member.getLifeStats().getHpPercentage() - (underFire.contains(member) ? UNDER_FIRE_URGENCY : 0);
			if (urgency < worstUrgency) {
				worstUrgency = urgency;
				worst = member;
			}
		}
		return worst;
	}

	/** @return Which of these members something is currently trying to kill. */
	private static List<Player> membersUnderFire(Player bot, List<Player> members) {
		List<Player> hunted = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !npc.isSpawned())
				return;
			for (Player member : members) {
				if (!hunted.contains(member) && npc.getAggroList().isHating(member))
					hunted.add(member);
			}
		});
		return hunted;
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

	/** @return true if the monster is fighting this group: hitting or hating the bot or any member, close enough to matter and in sight. */
	static boolean isEngagedWithTheGroup(Player bot, Npc npc) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return team != null && isWorthAssistingOn(bot, npc) && (npc.getAggroList().isHating(bot) || hatesAnyMember(npc, team.getMembers(), bot));
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

}
