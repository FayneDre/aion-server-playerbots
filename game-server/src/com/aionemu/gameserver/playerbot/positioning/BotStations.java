package com.aionemu.gameserver.playerbot.positioning;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcTemplateType;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.services.TribeRelationService;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * The world's side of positioning: reads what {@link CombatSpots} needs from the engine, asks it, and says whether a bot is where its class belongs.
 * <p>
 * Only melee behind the enemy and ranged classes back from it so far. The tank and the healer have their own milestones, and a role that is not handled
 * here is simply "not active", which sends the bot back to the old way of walking to its target.
 */
public final class BotStations {

	private static final Logger log = LoggerFactory.getLogger(BotStations.class);

	/**
	 * How far from straight behind a melee bot may be and still count as placed. Wider than the 45 degrees the spot is chosen in, so that a bot standing
	 * on its spot is always placed, and one that has drifted a little is not walked back for it.
	 */
	static final float PLACED_BEHIND_DEGREES = 60;
	/** The half of the enemy a ranged bot stays in: out of its cone, which is the one thing about it the engine's own back arc settles. */
	static final float BACK_HALF_DEGREES = 90;
	/** Monsters further than this from the enemy are not looked at: their notice cannot reach where the fight is. */
	private static final float ZONE_LOOKUP_RADIUS = 60;
	/** Samples between two lines in the log. */
	private static final int REPORT_EVERY = 200;

	private static final Map<BotPosition, AtomicInteger[]> samples = new EnumMap<>(BotPosition.class);

	static {
		for (BotPosition role : BotPosition.values())
			samples.put(role, new AtomicInteger[] { new AtomicInteger(), new AtomicInteger() });
	}

	private BotStations() {
	}

	/** @return true if this bot's place in a fight is one this class handles, so that the old way of walking to the target is not the answer. */
	public static boolean handles(Player bot) {
		BotPosition role = BotPosition.of(bot);
		return bot.getCurrentTeam() != null && (role == BotPosition.BEHIND || role == BotPosition.RANGED);
	}

	/** @return true if the bot stands where its class does against this enemy. */
	public static boolean isWellPlaced(Player bot, Creature enemy) {
		return switch (BotPosition.of(bot)) {
			case BEHIND -> PositionUtil.isBehind(bot, enemy, PLACED_BEHIND_DEGREES) && BotAttackManager.isInAttackRange(bot, enemy);
			case RANGED -> {
				double distance = PositionUtil.getDistance(bot, enemy, true);
				yield PositionUtil.isBehind(bot, enemy, BACK_HALF_DEGREES) && distance >= CombatSpots.MIN_STANDOFF && distance <= BotSkillManager.reach(bot);
			}
			default -> true;
		};
	}

	/**
	 * @return Where to stand to fight this enemy, on the ground, or null when the class has no rule here or no acceptable spot was found. The bot then
	 *         does what it did before positioning existed.
	 */
	public static Vector3f spotFor(Player bot, Creature enemy) {
		BotPosition role = BotPosition.of(bot);
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		if (team == null)
			return null;
		float reach = role == BotPosition.BEHIND ? bot.getGameStats().getAttackRange().getCurrent() / 1000f : BotSkillManager.reach(bot);
		double facing = Math.toRadians(PositionUtil.convertHeadingToAngle(enemy.getHeading()));
		CombatSpots.Situation situation = new CombatSpots.Situation(new Vector3f(enemy.getX(), enemy.getY(), enemy.getZ()), (float) Math.cos(facing),
			(float) Math.sin(facing), groupCentre(bot, team), List.of(), new Vector3f(bot.getX(), bot.getY(), bot.getZ()), bot.getLevel(), zonesAround(bot, enemy));
		Vector3f spot = CombatSpots.spotFor(role, situation, reach);
		if (spot == null)
			return null;
		// the geometry works on a plane; the ground is the navmesh's to say, and a spot with none under it is no spot
		return NavmeshService.getInstance().groundNear(bot.getWorldId(), spot.getX(), spot.getY(), spot.getZ());
	}

	/**
	 * Counts one look at a bot in a fight, placed or not, and says so in the log every {@value #REPORT_EVERY} looks. Done whether positioning is on or
	 * off: the figures with it off are what the figures with it on are judged against.
	 */
	public static void sample(Player bot, Creature enemy) {
		if (!handles(bot))
			return;
		BotPosition role = BotPosition.of(bot);
		AtomicInteger[] counts = samples.get(role);
		int total = counts[0].incrementAndGet();
		if (isWellPlaced(bot, enemy))
			counts[1].incrementAndGet();
		if (total >= REPORT_EVERY && counts[0].compareAndSet(total, 0)) {
			log.info("Positioning {}: {} of {} samples well placed", role, counts[1].getAndSet(0), total);
		}
	}

	private static Vector3f groupCentre(Player bot, TemporaryPlayerTeam<?> team) {
		float x = 0, y = 0, z = 0;
		int count = 0;
		for (Player member : team.getMembers()) {
			if (member.equals(bot) || member.isDead() || member.getWorldId() != bot.getWorldId())
				continue;
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			count++;
		}
		return count == 0 ? new Vector3f(bot.getX(), bot.getY(), bot.getZ()) : new Vector3f(x / count, y / count, z / count);
	}

	/** @return What every monster near the fight that is not part of it would notice: the places the bot must not stand. */
	private static List<AggroZone> zonesAround(Player bot, Creature enemy) {
		List<AggroZone> zones = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.equals(enemy) || npc.isDead() || !npc.isSpawned() || BotGroupManager.isEngagedWithTheGroup(bot, npc))
				return;
			// a monster that would not turn on this bot anyway has no zone: a friendly guard's range is of no interest
			if (!bot.isEnemy(npc) || !TribeRelationService.isAggressive(npc, bot) || TribeRelationService.isFriend(npc, bot))
				return;
			if (PositionUtil.getDistance(npc, enemy) > ZONE_LOOKUP_RADIUS)
				return;
			zones.add(zoneOf(npc));
		});
		return zones;
	}

	private static AggroZone zoneOf(Npc npc) {
		double facing = Math.toRadians(PositionUtil.convertHeadingToAngle(npc.getHeading()));
		NpcTemplateType type = npc.getObjectTemplate().getNpcTemplateType();
		boolean guard = type == NpcTemplateType.GUARD || type == NpcTemplateType.ABYSS_GUARD;
		return new AggroZone(npc.getX(), npc.getY(), (float) Math.cos(facing), (float) Math.sin(facing), npc.getAggroRange(), npc.getAggroAngle(), npc.getLevel(),
			guard);
	}
}
