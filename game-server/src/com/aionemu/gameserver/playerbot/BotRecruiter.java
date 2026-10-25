package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.autogroup.AutoGroup;
import com.aionemu.gameserver.model.gameobjects.findGroup.ServerWideGroup;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.team.group.PlayerGroup;
import com.aionemu.gameserver.model.team.group.PlayerGroupService;
import com.aionemu.gameserver.model.templates.InstanceCooltime;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.lifecycle.BotMapChange;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.movement.BotFlight;
import com.aionemu.gameserver.playerbot.social.BotInstances;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.playerbot.social.RecruitPlan;
import com.aionemu.gameserver.playerbot.social.RecruitPlan.Candidate;
import com.aionemu.gameserver.services.findgroup.FindGroupService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Answers a player's offer in the Instance Groups tab of the Find Group window with bots, enough of them and of the right kinds.
 * <p>
 * The seats are the number of members the player asked for, within what the instance takes; the roles are a tank, a healer and a support before any
 * damage, counting the player's own class (see {@link RecruitPlan}); the levels are the player's, give or take two, within what the instance lets
 * in, widened to four when nobody of the role is that close. A recruit comes from the pool first, so a group is not made by emptying a field
 * somebody may be looking at, and from the bots in the world only when the pool has nobody suitable.
 * <p>
 * A recruit is brought beside the player and invited, and the player's group fills as if strangers had answered: the offer leaves the list by
 * itself once it is full ({@code FindGroupService.onJoinedTeam}). When the recruit leaves the group, or never joins it, it goes home, as somebody
 * who answered an offer from across the world takes a scroll back.
 */
public final class BotRecruiter {

	private static final Logger log = LoggerFactory.getLogger(BotRecruiter.class);

	private static final long REVIEW_MILLIS = 5000;
	/** How many recruits one review brings, so the lane that wakes the population is not held for long: the next review brings the rest. */
	private static final int MAX_PER_REVIEW = 2;
	/** How far from the player's level a recruit may be, and how far once nobody of the role is that close. */
	private static final int LEVEL_SPREAD = 2, WIDER_LEVEL_SPREAD = 4;
	/** How long an invited recruit has to join before it is given up on and sent home. A bot answers an invitation on its next tick. */
	private static final long JOIN_WAIT_MILLIS = 30_000;
	private static final int GROUP_SIZE = 6;
	/** How far from the player who invited it a bot must be to be brought to the player's side rather than left to walk there, in metres. */
	private static final float BRING_INVITED_BEYOND = 50;
	/** The offer of a bot a player invited by hand, which answered none. */
	private static final int NO_OFFER = -1;

	/** The bots recruited and not yet sent home, by object id. */
	private static final Map<Integer, Recruit> recruits = new ConcurrentHashMap<>();
	/** Offers whose missing role has already been reported, so a role nobody can fill is said once and not every five seconds. */
	private static final Set<String> reported = ConcurrentHashMap.newKeySet();

	/** A recruit, where it goes back to, and the offer it answered. */
	private static final class Recruit {

		private final int homeWorldId;
		private final Vector3f home;
		private final int offerId;
		private final BotRole role;
		private final long invitedAt = System.currentTimeMillis();
		private volatile boolean joined;

		private Recruit(int homeWorldId, Vector3f home, int offerId, BotRole role) {
			this.homeWorldId = homeWorldId;
			this.home = home;
			this.offerId = offerId;
			this.role = role;
		}
	}

	private BotRecruiter() {
	}

	public static void start() {
		BotScheduler scheduler = BotScheduler.getInstance();
		scheduler.scheduleAtFixedRate(() -> scheduler.enterOrLeaveWorld(BotRecruiter::review), REVIEW_MILLIS, REVIEW_MILLIS);
	}

	/** Brings recruits to the offers that are short of members, a few at a time. Runs on the lifecycle lane, since waking a sleeper blocks. */
	private static void review() {
		int budget = MAX_PER_REVIEW;
		Map<Race, List<BotRoster.Sleeper>> sleepers = new HashMap<>(); // read once per review and faction, and only if an offer needs it
		for (ServerWideGroup offer : List.copyOf(FindGroupService.getInstance().getInstanceGroups())) {
			if (budget == 0)
				return;
			budget -= answer(offer, budget, sleepers);
		}
	}

	/** @return How many recruits were brought. */
	private static int answer(ServerWideGroup offer, int budget, Map<Race, List<BotRoster.Sleeper>> sleepers) {
		Player recruiter = offer.getRecruiter();
		if (recruiter.isBot() || !recruiter.isOnline() || !recruiter.isSpawned())
			return 0;
		TemporaryPlayerTeam<?> team = recruiter.getCurrentTeam();
		if (team != null && (!(team instanceof PlayerGroup) || !team.isLeader(recruiter)))
			return 0; // an alliance, or a group somebody else leads, whose invitations are not the recruiter's to send
		AutoGroup instance = DataManager.AUTO_GROUP.getTemplateByInstanceMaskId(offer.getInstanceMaskId());
		if (instance == null)
			return 0;
		int mapId = instance.getInstanceMapId();
		int instanceSize = DataManager.INSTANCE_COOLTIME_DATA.getMaxMemberCount(mapId, recruiter.getRace());
		int target = Math.min(offer.getMinMembers(), Math.min(instanceSize > 0 ? instanceSize : GROUP_SIZE, GROUP_SIZE));

		Collection<Player> members = offer.getMembers();
		List<BotRole> present = new ArrayList<>();
		Set<PlayerClass> classes = EnumSet.noneOf(PlayerClass.class);
		for (Player member : members) {
			present.add(BotRole.of(member));
			classes.add(member.getPlayerClass());
		}
		int seated = members.size();
		for (Recruit pending : recruits.values()) {
			if (pending.offerId == offer.getId() && !pending.joined) { // on its way: its seat is taken already
				present.add(pending.role);
				seated++;
			}
		}
		if (seated >= target)
			return 0;

		int brought = 0;
		for (BotRole role : RecruitPlan.rolesWanted(present, target - seated)) {
			if (brought == budget)
				break;
			if (recruit(offer, recruiter, mapId, role, classes, sleepers)) {
				brought++;
			} else if (role != BotRole.DAMAGE && recruit(offer, recruiter, mapId, BotRole.DAMAGE, classes, sleepers)) {
				brought++; // the seat is better filled by damage than left empty, and the log has said which role was missing
			}
		}
		return brought;
	}

	private static boolean recruit(ServerWideGroup offer, Player recruiter, int mapId, BotRole role, Set<PlayerClass> classes,
		Map<Race, List<BotRoster.Sleeper>> sleepers) {
		Race race = recruiter.getRace();
		int level = recruiter.getLevel();
		InstanceCooltime entry = DataManager.INSTANCE_COOLTIME_DATA.getInstanceCooltimeByWorldId(mapId);
		int entryMin = entry == null ? 1 : race == Race.ELYOS ? entry.getEnterMinLevelLight() : entry.getEnterMinLevelDark();
		int entryMax = entry == null ? 0 : race == Race.ELYOS ? entry.getEnterMaxLevelLight() : entry.getEnterMaxLevelDark();
		List<BotRoster.Sleeper> asleep = sleepers.computeIfAbsent(race, BotRoster::sleepers);
		for (int spread : new int[] { LEVEL_SPREAD, WIDER_LEVEL_SPREAD }) {
			int min = Math.max(entryMin, level - spread), max = entryMax > 0 ? Math.min(entryMax, level + spread) : level + spread;
			BotRoster.Sleeper sleeper = chooseSleeper(asleep, role, classes, level, min, max);
			if (sleeper != null) {
				asleep.remove(sleeper);
				// a recruit before it is in the world: its first tick can come before this lane gets back to it, and must already know it is away
				int playerId = sleeper.resident().playerId();
				recruits.put(playerId, new Recruit(sleeper.resident().worldId(), sleeper.resident().home(), offer.getId(), role));
				Player bot = PlayerBotService.getInstance().wakeBeside(sleeper.resident(), recruiter);
				if (bot == null) {
					recruits.remove(playerId);
					return false;
				}
				invite(recruiter, bot, role, classes);
				return true;
			}
			Player awake = chooseAwake(race, role, classes, level, min, max);
			if (awake != null) {
				recruits.put(awake.getObjectId(), new Recruit(awake.getWorldId(), BotRoster.homeOf(awake.getName()), offer.getId(), role));
				TeleportService.teleportTo(awake, recruiter.getWorldId(), recruiter.getInstanceId(), recruiter.getX() + 2, recruiter.getY(), recruiter.getZ());
				BotMapChange.arrive(awake);
				invite(recruiter, awake, role, classes);
				return true;
			}
		}
		if (reported.add(offer.getId() + ":" + role))
			log.info("No {} of the {} between levels {} and {} for {}'s offer on map {}", role, race, Math.max(entryMin, level - WIDER_LEVEL_SPREAD),
				level + WIDER_LEVEL_SPREAD, recruiter.getName(), mapId);
		return false;
	}

	private static BotRoster.Sleeper chooseSleeper(List<BotRoster.Sleeper> asleep, BotRole role, Set<PlayerClass> classes, int level, int min, int max) {
		Map<Integer, BotRoster.Sleeper> byId = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		for (BotRoster.Sleeper sleeper : asleep) {
			if (sleeper.level() < min || sleeper.level() > max || recruits.containsKey(sleeper.resident().playerId()))
				continue;
			byId.put(sleeper.resident().playerId(), sleeper);
			candidates.add(new Candidate(sleeper.resident().playerId(), sleeper.playerClass(), sleeper.level()));
		}
		Candidate chosen = RecruitPlan.choose(candidates, role, classes, level);
		return chosen == null ? null : byId.get(chosen.playerId());
	}

	/** @return A bot in the world that is free to answer: the world's own, alone, alive, not fighting, not in the air and not in an instance. */
	private static Player chooseAwake(Race race, BotRole role, Set<PlayerClass> classes, int level, int min, int max) {
		Map<Integer, Player> byId = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		for (Player bot : PlayerBotService.getInstance().spawnedBots()) {
			if (bot.getRace() != race || bot.getLevel() < min || bot.getLevel() > max || recruits.containsKey(bot.getObjectId()))
				continue;
			if (!(bot.getAi() instanceof PlayerBotAI ai) || ai.isOwned() || ai.isAttacking() || bot.getCurrentTeam() != null || bot.isDead()
				|| !bot.isSpawned() || bot.isInInstance() || BotFlight.isInTheAir(bot) || BotRoster.homeOf(bot.getName()) == null)
				continue;
			byId.put(bot.getObjectId(), bot);
			candidates.add(new Candidate(bot.getObjectId(), bot.getPlayerClass(), bot.getLevel()));
		}
		Candidate chosen = RecruitPlan.choose(candidates, role, classes, level);
		return chosen == null ? null : byId.get(chosen.playerId());
	}

	/**
	 * Brings a bot that has just accepted a player's invitation to the player's side, when it is on another map or more than 50 m away: a player who
	 * invites a bot by name from across the region wants it in the group, not walking there for minutes. Kept as a recruit with no offer, so that it
	 * goes home when it leaves the group as a recruit does, rather than staying wherever the group broke up.
	 * <p>
	 * Left alone, as other teleports already deal with them: a recruit, brought before it was invited; a player or a bot in an instance, which
	 * {@code BotInstances} follows; either of them dead.
	 */
	public static void bringInvited(Player bot, Player inviter) {
		if (recruits.containsKey(bot.getObjectId()) || bot.isDead() || inviter.isDead() || bot.isInInstance() || inviter.isInInstance())
			return;
		if (bot.getWorldId() == inviter.getWorldId() && bot.getInstanceId() == inviter.getInstanceId()
			&& PositionUtil.getDistance(bot, inviter) <= BRING_INVITED_BEYOND)
			return;
		int fromWorldId = bot.getWorldId();
		long distance = fromWorldId == inviter.getWorldId() ? Math.round(PositionUtil.getDistance(bot, inviter)) : -1;
		// a visitor already has its way home, which ends its visit; any other bot is sent back to where it lives
		if (!BotVisitors.isVisitor(bot)) {
			Vector3f home = BotRoster.homeOf(bot.getName());
			recruits.put(bot.getObjectId(), new Recruit(fromWorldId, home != null ? home : new Vector3f(bot.getX(), bot.getY(), bot.getZ()), NO_OFFER, BotRole.of(bot)));
		}
		BotInstances.moveBeside(bot, inviter);
		log.info("Bot {} accepted {}'s invitation {} and is brought beside the player", bot.getName(), inviter.getName(),
			distance < 0 ? "from map " + fromWorldId : distance + " m away");
	}

	private static void invite(Player recruiter, Player bot, BotRole role, Set<PlayerClass> classes) {
		classes.add(bot.getPlayerClass());
		PlayerGroupService.inviteToGroup(recruiter, bot);
		log.info("Bot {} ({} level {}) answers {}'s offer as {}", bot.getName(), bot.getPlayerClass(), bot.getLevel(), recruiter.getName(), role);
	}

	/** @return true while the bot is away from home answering an offer, from the moment it is brought until it is sent back. */
	public static boolean isRecruit(Player bot) {
		return recruits.containsKey(bot.getObjectId());
	}

	/**
	 * Sends a recruit home once it is no longer in the group it was brought for, or never joined it. Called on the bot's own tick, ahead of everything
	 * that would read its surroundings: without this a recruit left behind would walk towards its home's coordinates on whatever map it stood on.
	 *
	 * @return true if the bot has just been sent home.
	 */
	public static boolean sendHomeIfReleased(Player bot) {
		Recruit recruit = recruits.get(bot.getObjectId());
		if (recruit == null || !bot.isSpawned() || bot.isDead())
			return false;
		if (bot.getCurrentTeam() != null) {
			recruit.joined = true;
			return false;
		}
		if (!recruit.joined && System.currentTimeMillis() - recruit.invitedAt < JOIN_WAIT_MILLIS)
			return false; // the invitation is answered on a tick of its own
		recruits.remove(bot.getObjectId());
		goHome(bot, recruit);
		log.info("Bot {} {} and goes home to map {}", bot.getName(), recruit.joined ? "has left the group it was recruited for" : "never joined",
			recruit.homeWorldId);
		return true;
	}

	/**
	 * Sends every recruit home on the way down, out of its group first. A bot is saved where it stands, and the map it is saved on is the map it is
	 * taken to live on at the next start: a recruit saved beside its player would come back a resident of the player's map, with the coordinates of a
	 * home on another.
	 */
	public static void sendEveryoneHome() {
		for (Map.Entry<Integer, Recruit> entry : Map.copyOf(recruits).entrySet()) {
			recruits.remove(entry.getKey());
			Player bot = PlayerBotService.getInstance().spawnedBot(entry.getKey());
			if (bot == null || !bot.isSpawned())
				continue;
			if (bot.getPlayerGroup() != null)
				PlayerGroupService.removePlayer(bot);
			goHome(bot, entry.getValue());
		}
	}

	private static void goHome(Player bot, Recruit recruit) {
		Vector3f spot = BotPopulator.scatterAround(recruit.homeWorldId, recruit.home);
		TeleportService.teleportTo(bot, recruit.homeWorldId, spot.getX(), spot.getY(), spot.getZ());
		BotMapChange.arrive(bot);
		// the home it had when it was recruited, written back: nothing should have changed it since, and if something did it was decided on another map
		if (bot.getAi() instanceof PlayerBotAI ai)
			ai.takeUpHome(recruit.home);
	}
}
