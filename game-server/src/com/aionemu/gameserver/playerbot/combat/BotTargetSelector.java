package com.aionemu.gameserver.playerbot.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcRank;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Picks what a bot should fight on its own.
 */
public class BotTargetSelector {

	/** How far a bot is willing to walk to engage something it spotted. */
	public static final float CHASE_RADIUS = 25f;
	/** The camp a bot works: it roams and fights inside this radius around its anchor, and never follows a target out of it. */
	public static final float HOME_RADIUS = 60f;
	/** Height difference above which a target is treated as being on another level (a cliff, a roof, the floor below) and left alone. */
	private static final float MAX_Z_DELTA = 8f;
	/** How far above its own level a bot will pick a fight it started. Anything higher kills it, and it would just keep dying. */
	public static final int MAX_LEVEL_GAP = 3;
	/** How far beneath its own level a bot will still bother. Below that the fight is worth nothing and a player walks past it. */
	private static final int MIN_LEVEL_GAP = 6;

	private BotTargetSelector() {
	}

	/**
	 * Finds the closest hostile npc worth engaging, within walking distance rather than within weapon reach.
	 *
	 * @param isIgnored Targets the bot already failed to reach, so it does not immediately pick them again.
	 */
	public static Creature findTarget(Player bot, Predicate<Creature> isIgnored) {
		return findTargetWithin(bot, CHASE_RADIUS, isIgnored);
	}

	/**
	 * Same search over a wider circle, used to find where the next fight is once the bot has cleared what is around it.
	 */
	public static Creature findTargetWithin(Player bot, float radius, Predicate<Creature> isIgnored) {
		List<Npc> candidates = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (isAttackable(bot, npc, radius) && !isIgnored.test(npc))
				candidates.add(npc);
		});
		return candidates.stream().min(Comparator.comparingDouble(npc -> PositionUtil.getDistance(bot, npc))).orElse(null);
	}

	/**
	 * Finds the closest enemy player worth attacking, for a bot standing in a PvP zone. Nothing is ever returned unless the PvP option is on and the
	 * bot is inside such a zone, and {@link Player#isEnemy} then holds only for the other race (or a duel partner).
	 * <p>
	 * The rules are those of a monster (in sight, within {@link #MAX_Z_DELTA} in height, never above level + {@link #MAX_LEVEL_GAP}, never beneath
	 * level - {@value #MIN_LEVEL_GAP}) with two more: the bot's {@link BotTemperament}, and no more than {@link BotPvpRules#MAX_BOTS_ON_A_LONE_PLAYER} bots
	 * on a player who is in no team.
	 *
	 * @param invasion true if the bot is on an invasion, where temperament does not apply (nothing sets this yet)
	 * @param isIgnored Targets the bot already failed to reach.
	 */
	public static Player findPlayerTarget(Player bot, float radius, boolean invasion, Predicate<Creature> isIgnored) {
		if (!PlayerBotConfig.PVP_ENABLE || !bot.isInsidePvPZone())
			return null;
		BotTemperament temperament = BotTemperament.of(bot.getObjectId());
		if (temperament == BotTemperament.PEACEFUL && !invasion)
			return null; // saves the scan: such a bot never starts a fight
		List<Player> candidates = new ArrayList<>();
		bot.getKnownList().forEachPlayer(player -> {
			if (isPlayerFairGame(bot, player, radius, temperament, invasion) && !isIgnored.test(player))
				candidates.add(player);
		});
		return candidates.stream().min(Comparator.comparingDouble(player -> PositionUtil.getDistance(bot, player))).orElse(null);
	}

	private static boolean isPlayerFairGame(Player bot, Player player, float radius, BotTemperament temperament, boolean invasion) {
		if (player.equals(bot) || player.isDead() || !player.isSpawned() || !bot.isEnemy(player))
			return false;
		if (Math.abs(bot.getZ() - player.getZ()) > MAX_Z_DELTA)
			return false;
		if (!BotPvpRules.isInLevelBand(bot.getLevel(), player.getLevel(), MAX_LEVEL_GAP, MIN_LEVEL_GAP))
			return false;
		if (!temperament.mayStartAgainst(invasion, bot.getLevel(), player.getLevel(), player.getLifeStats().getHpPercentage()))
			return false;
		if (PositionUtil.getDistance(bot, player) > radius || !GeoService.getInstance().canSee(bot, player))
			return false;
		long botsOnIt = bot.getKnownList().streamPlayers().filter(other -> other.isBot() && !other.equals(bot) && other.getTarget() == player).count();
		return BotPvpRules.mayJoinAttack(!player.isInTeam(), (int) botsOnIt);
	}

	/**
	 * @return true if this is part of the furniture rather than something to fight: the little animals that wander through a village, the young of
	 *         real creatures, the training dummy standing in the square.
	 *         <p>
	 *         Two markers, and both are needed: JUNK catches the qooqoo, NOVICE catches the elroco, the dummy and the young, which are rated NORMAL
	 *         like anything else. Measured over the thirty three kinds of thing bots had picked on Poeta, every real monster is DISCIPLINED or above.
	 */
	public static boolean isScenery(Npc npc) {
		return npc.getObjectTemplate().getRating() == NpcRating.JUNK || npc.getObjectTemplate().getRank() == NpcRank.NOVICE;
	}

	/**
	 * @return true if this is more than one character is meant to take on alone.
	 *         <p>
	 *         The level gap does not catch these and cannot: an elite of a bot's own level is several times the creature a normal one is, and the
	 *         data says so in one word rather than in its level. This is what sent bots into the group quest camps to be torn apart — they read as
	 *         ordinary monsters of a suitable level, because by every number except this one they were.
	 *         <p>
	 *         It also explains why keeping bots out needed more than refusing to attack: {@code NpcRating} maps ELITE to {@code SEARCH1} and HERO and
	 *         LEGENDARY to {@code SEARCH2}, so these creatures hunt for somebody to fight instead of waiting to be provoked. A bot that merely stood
	 *         in such a camp was attacked without ever having picked anything. So they are kept out of the grounds bots are sent to as well.
	 */
	public static boolean needsAGroup(NpcTemplate template) {
		NpcRating rating = template.getRating();
		return rating == NpcRating.ELITE || rating == NpcRating.HERO || rating == NpcRating.LEGENDARY;
	}

	/**
	 * Leaves alone what someone else is already fighting, so several bots in the same spot spread over the mobs around them instead of piling onto
	 * the nearest one. Team mates are excluded: helping them is the whole point of being grouped. This also stops bots from stealing kills from real
	 * players.
	 */
	private static boolean isTakenByAnotherPlayer(Player bot, Npc npc) {
		return isFighting(bot, npc.getTarget()) || isFighting(bot, BotTargetRegistry.getOwner(npc));
	}

	private static boolean isFighting(Player bot, Object candidate) {
		return candidate instanceof Player other && !other.equals(bot) && !other.isInSameTeam(bot);
	}

	/**
	 * @return true if the bot could pick this monster for itself within that radius: its level, its rank, nobody else's, in sight. For a class that pulls
	 *         a second monster on purpose, which must be one the bot would have chosen alone.
	 */
	public static boolean isFairGame(Player bot, Npc npc, float radius) {
		return isAttackable(bot, npc, radius);
	}

	private static boolean isAttackable(Player bot, Npc npc, float radius) {
		if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc))
			return false;
		if (Math.abs(bot.getZ() - npc.getZ()) > MAX_Z_DELTA)
			return false;
		if (npc.getLevel() > bot.getLevel() + MAX_LEVEL_GAP)
			return false; // this only limits what the bot picks: it still fights back against anything that attacks it
		if (isScenery(npc))
			return false;
		// Only what the bot picks for itself. Defending itself and assisting its group both go another way and are untouched, which is right: an
		// elite that attacks a bot is still to be fought, and a group is exactly what elites are for.
		if (needsAGroup(npc.getObjectTemplate()))
			return false;
		if (npc.getLevel() < bot.getLevel() - MIN_LEVEL_GAP)
			return false; // far enough beneath it to be worth nothing, and a player walks past those
		if (isTakenByAnotherPlayer(bot, npc))
			return false;
		// line of sight is not just a targeting rule here: without it the bot would walk towards things behind walls it cannot reach
		return PositionUtil.getDistance(bot, npc) <= radius && GeoService.getInstance().canSee(bot, npc);
	}
}
