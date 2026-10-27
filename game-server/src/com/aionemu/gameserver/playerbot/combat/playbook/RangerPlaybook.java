package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.NpcSkillEntry;
import com.aionemu.gameserver.model.skill.NpcSkillList;
import com.aionemu.gameserver.playerbot.combat.BotCombatMemory;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.skillengine.model.SkillSubType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.model.SkillType;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a ranger plays, after the guide's "Rôdeur 101". The plan, milestone by milestone, is {@code docs/classes/ranger-plan.md}.
 * <p>
 * The guide's rule for a ranger is distance: kill before the enemy arrives, and in a group never become the target. So the playbook prepares (the personal
 * buffs, two of the four one minute ones at most), opens with the handicap that suits the enemy, keeps a hand on its own health with Seizure Arrow, and in a
 * group stops shooting while its target is looking at it. Everything it does not name, the damage included, stays with the generic order.
 * <p>
 * The guide gives no rule for traps beyond a paragraph it has since struck out, so none is ever cast, and no skill that needs a weapon in hand either: the
 * engine refuses those to a bow.
 */
final class RangerPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(RangerPlaybook.class);

	/** Health under which Seizure Arrow, which drains the enemy's life into the ranger's, is cast. */
	static final int SEIZURE_BELOW_PERCENT = 75;
	/** Health under which, being the target, the ranger raises Focused Evasion first. */
	static final int EVASION_BELOW_PERCENT = 50;
	/** The divine power the Transformation costs, as the data states it for all four. */
	static final int TRANSFORMATION_DP = 2000;
	/** How many of the four one minute buffs may run at once. The guide says a third removes the first. */
	static final int ONE_MINUTE_BUFFS_AT_ONCE = 2;
	/** How long a grouped ranger stops shooting once its target has turned on it, at most. */
	static final long HOLD_FIRE_MILLIS = 5000;
	/** How long after one hold it will not start another, so that a ranger the tank cannot take it off is not a ranger that never shoots. */
	static final long HOLD_FIRE_REARM_MILLIS = 10000;
	/** Attackers on a ranger alone at which the guide has it run. */
	static final int ATTACKERS_TO_FLEE = 3;
	/** Flight points under which Heal Wings is cast, as a share of the maximum: the guide's "flight time under half". */
	static final int WINGS_BELOW_PERCENT = 50;
	/** How close an enemy must be to be "in melee" for Shackle Arrow, in metres: the guide says one. */
	static final float MELEE_DISTANCE = 2.5f;
	/** The widest attack range, in metres, of an enemy that fights in melee. */
	static final int MELEE_ENEMY_RANGE = 3;
	/** How far Sleep Arrow reaches, from the data. */
	static final float SLEEP_RANGE = 15f;
	/** How far an aggressive enemy has to be for a ranger on a journey to have it in mind: the guide's "less than 15 m for a standard aggressive enemy". */
	static final float NOTICE_RADIUS = 15f;
	/** How many enemies inside {@link #NOTICE_RADIUS} make ground "dense" enough to hide in. */
	static final int DENSE_ENEMIES = 3;

	/** The four one minute buffs and the order they are wanted in: Strong Shots, then Focused Shots (which replaces Aiming), Aiming, Dodging. */
	static final List<Move> ONE_MINUTE_ORDER = List.of(Move.STRONG_SHOTS, Move.FOCUSED_SHOTS, Move.AIMING, Move.DODGING);

	/** The stigmas the guide lists: Bow of Blessing, Focused Shots, Arrow Deluge, then Lethal Arrow, Gale Arrow, Agonizing Arrow. */
	static final List<String> REGULAR_STIGMAS = List.of("RA_ENCHANTBOW", "SC_TRUESHOTMIND", "RA_SPOUTARROW");
	static final List<String> ADVANCED_STIGMAS = List.of("RA_MASSEXPLOSIONARROW", "RA_MOVINGSHOT", "RA_PAINARROW");

	/**
	 * Skills a ranger never casts, whatever the generic order thinks they are: the dashes (they pull monsters), the eyes that see hidden players, Bestial Fury
	 * (it costs ten metres of range), Aether Arrow (4000 DP that the Transformation needs), the other transformations, every trap and Nimble Fingers, the
	 * buff that serves only a trap, and the toggle Sharpen Arrows.
	 */
	static final Set<String> NEVER_GROUPS = Set.of("RA_BACKDASHSTAB", "RA_INSTANTSPRINT", "RA_CRISISCOUNTER", "RA_MINDSEYE", "AS_SIXSENSE_H",
		"RA_STIGMA_TIGEREYE", "RA_BRAINSHOT", "RA_WHITETIGER", "RA_KRALLSCOUT", "RA_QUICKSETTING", "RA_TRACKERMIND", "RA_SPIKETRAP", "RA_POISONTRAP",
		"RA_SANDSTORMTRAP", "RA_SPIKEBITETRAP", "RA_SLEEPINGTRAP", "RA_SHOCKWAVETRAP", "RA_THROWINGTRAP", "RA_SNAKEBITETRAP", "RA_BLAZINGTRAP",
		"RA_FAIRYFLARE", "RA_SHOCKTRAP");
	/** What is not cast in a group: the end of chain B, which the guide says forces melee, and the area attack that would pull the others. */
	static final Set<String> NOT_IN_A_GROUP = Set.of("RA_WOUNDINGARROW", "RA_DRAINSHOT", "RA_SPOUTARROW");

	/** What an enemy is, as far as the guide's handicaps are concerned. */
	enum EnemyKind {
		MELEE, CASTER, OTHER
	}

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** An enemy fighting the group that is not the one the group is attacking. */
		OTHER_ENEMY,
		/** Nobody: the move is to stand still. */
		NOBODY
	}

	/**
	 * What the playbook may cast, and the skill group that names it. {@code claimed} is whether the generic order is told to leave the skill alone.
	 */
	enum Move {
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF, true),
		FINISHING_ARROW("RA_FINALFIRE", Aim.TARGET, false),
		CALMING_WHISPER("AS_BREEZEOFCALM", Aim.TARGET, true),
		HOLD_FIRE(null, Aim.NOBODY, false),
		FOCUSED_EVASION("SC_WHISPEROFWIND", Aim.SELF, true),
		SEIZURE_ARROW("RA_APPROACHSHOT", Aim.TARGET, true),
		BREATH_OF_NATURE("RA_BREATHOFNATURE", Aim.SELF, true),
		STRONG_SHOTS("RA_SNAKEEYE", Aim.SELF, true),
		FOCUSED_SHOTS("SC_TRUESHOTMIND", Aim.SELF, true),
		AIMING("RA_EAGLEEYE", Aim.SELF, true),
		DODGING("RA_HAWKEYE", Aim.SELF, true),
		BOW_OF_BLESSING("RA_ENCHANTBOW", Aim.SELF, true),
		SPEED_OF_THE_WIND("RA_PANTHERMOVE", Aim.SELF, true),
		DEVOTION("SC_INVOKEPOWER", Aim.SELF, true),
		TRANSFORMATION_MAU("RA_SABAGEROAR", Aim.SELF, true),
		TRANSFORMATION_MAU_LIGHT("RA_L_SABAGEROAR", Aim.SELF, true),
		ENTANGLING_SHOT("RA_ENTANGLESHOT", Aim.TARGET, true),
		SILENCE_ARROW("RA_SILENTARROW", Aim.TARGET, true),
		SHACKLE_ARROW("RA_ROOTARROW", Aim.TARGET, true),
		SLEEP_ARROW("RA_PARALYZEARROW", Aim.OTHER_ENEMY, true),
		STEALTH("SC_HIDE", Aim.SELF, true),
		TACTICAL_RETREAT("SC_SPIRITOFGALE", Aim.SELF, true),
		HEAL_WINGS("RA_RESTOREWING", Aim.SELF, true);

		final String group;
		final Aim aim;
		final boolean claimed;

		Move(String group, Aim aim, boolean claimed) {
			this.group = group;
			this.aim = aim;
			this.claimed = claimed;
		}
	}

	/**
	 * What the ranger's own fight memory says, which the pure rules need and a playbook shared by every ranger cannot hold: since when it has held its fire, and
	 * until when it will not start again.
	 *
	 * @param holding Whether it is holding its fire as of now.
	 * @param startedNow Whether it began to hold on this very tick, which is the moment Calming Whisper is cast.
	 */
	record Hold(boolean holding, boolean startedNow, long since, long rearmAt) {
	}

	/**
	 * Everything {@link #decide} looks at, so that it can be decided without a world.
	 *
	 * @param hpPercent The ranger's health.
	 * @param dp Its divine power.
	 * @param inTeam Whether it is in a group.
	 * @param targeted Whether the enemy it is shooting is aiming at it.
	 * @param controlled Whether it is stunned, knocked about, spun or held in the air right now.
	 * @param shockChainOpen Whether Remove Shock was the last skill it used, which is the only moment Finishing Arrow can follow it.
	 * @param oneMinuteOn Which of the four one minute buffs are on it: how many decides whether another may go on, and which decides that the one that is on
	 *          is not cast again, which a one second cooldown would otherwise have it do every second.
	 * @param breathUp Whether Breath of Nature, a toggle that must not be cast twice, is on.
	 * @param bowUp Whether Bow of Blessing is on.
	 * @param windUp Whether Speed of the Wind is on.
	 * @param transformed Whether a Transformation is on.
	 * @param evasionUp Whether Focused Evasion is on.
	 * @param kind What the enemy is.
	 * @param opened Whether the opening handicap has already been spent on this enemy.
	 * @param inMelee Whether the enemy is within a metre or two of it.
	 * @param otherEnemy Whether there is another enemy on the group than the one it is shooting.
	 * @param hold Its hold-fire state.
	 * @param ready The moves it knows and that are off cooldown.
	 */
	record Situation(int hpPercent, int dp, boolean inTeam, boolean targeted, boolean controlled, boolean shockChainOpen, Set<Move> oneMinuteOn, boolean breathUp,
		boolean bowUp, boolean windUp, boolean transformed, boolean evasionUp, EnemyKind kind, boolean opened, boolean inMelee, boolean otherEnemy, Hold hold,
		Set<Move> ready) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(bot.getCurrentTeam() != null, template);
	}

	/** @return true if the ranger alone decides on this skill. Takes the group flag and the template, so that every template in the game can be put through it. */
	static boolean claimsSkill(boolean inTeam, SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (NEVER_GROUPS.contains(group))
			return true;
		if (NOT_IN_A_GROUP.contains(group))
			return inTeam;
		for (Move move : Move.values()) {
			if (move.claimed && group.equals(move.group))
				return true;
		}
		return false;
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public boolean tend(Player bot) {
		// a fight that has ended leaves its hold behind, which a new fight would take for one it is in the middle of
		if (BotCombatMemory.of(bot) instanceof BotCombatMemory memory && memory.holdingFireSince() != 0)
			memory.setHoldingFire(0, memory.holdFireRearmAt());
		return false;
	}

	@Override
	public boolean prepare(Player bot) {
		return perform(bot, null, decideAtRest(situationOf(bot, null, false)));
	}

	@Override
	public boolean act(Player bot, Creature target) {
		Situation situation = situationOf(bot, target, true);
		Move move = decide(situation);
		boolean did = perform(bot, target, move);
		// a first hold is also its start: the memory is written whether or not Calming Whisper could be cast, or it would be started again every tick
		if (BotCombatMemory.of(bot) instanceof BotCombatMemory memory) {
			memory.setHoldingFire(situation.hold().since(), situation.hold().rearmAt());
			if (did && (move == Move.ENTANGLING_SHOT || move == Move.SILENCE_ARROW))
				memory.setOpenedOn(target.getObjectId());
		}
		return did;
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && attackers >= ATTACKERS_TO_FLEE;
	}

	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.TACTICAL_RETREAT.group))
			log.debug("Ranger {} casts {} as it breaks off", bot.getName(), Move.TACTICAL_RETREAT);
	}

	@Override
	public void whileFlying(Player bot) {
		if (bot.getLifeStats().getMaxFp() <= 0 || bot.getLifeStats().getCurrentFp() * 100 >= bot.getLifeStats().getMaxFp() * WINGS_BELOW_PERCENT)
			return;
		if (BotSkillManager.tryCastGroup(bot, bot, Move.HEAL_WINGS.group))
			log.debug("Ranger {} casts {} at {} fp", bot.getName(), Move.HEAL_WINGS, bot.getLifeStats().getCurrentFp());
	}

	private static boolean perform(Player bot, Creature target, Move move) {
		if (move == null)
			return false;
		if (move == Move.HOLD_FIRE)
			return true; // standing still is a thing it did: the generic order must not shoot, and the swing is not taken
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> target;
			case OTHER_ENEMY -> target == null ? null : BotGroupManager.otherEnemyOnTheGroup(bot, target, SLEEP_RANGE);
			case NOBODY -> null;
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Ranger {} casts {} at {}% health", bot.getName(), move, bot.getLifeStats().getHpPercentage());
		return cast;
	}

	private static Situation situationOf(Player bot, Creature target, boolean inFight) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (move.group != null && BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		boolean inTeam = bot.getCurrentTeam() != null;
		Set<Move> oneMinuteOn = EnumSet.noneOf(Move.class);
		for (Move move : ONE_MINUTE_ORDER) {
			if (BotSkillManager.isUp(bot, move.group))
				oneMinuteOn.add(move);
		}
		boolean targeted = inFight && target != null && bot.equals(target.getTarget());
		long now = System.currentTimeMillis();
		long since = 0;
		long rearmAt = 0;
		boolean opened = false;
		if (BotCombatMemory.of(bot) instanceof BotCombatMemory memory) {
			since = memory.holdingFireSince();
			rearmAt = memory.holdFireRearmAt();
			opened = target != null && memory.openedOn() == target.getObjectId();
		}
		Hold hold = inFight ? nextHold(inTeam, targeted, now, since, rearmAt) : new Hold(false, false, 0, rearmAt);
		// asked only when the move is ready: each is a walk over what the bot can see
		boolean otherEnemy = inFight && inTeam && ready.contains(Move.SLEEP_ARROW) && target != null
			&& BotGroupManager.otherEnemyOnTheGroup(bot, target, SLEEP_RANGE) != null;
		boolean inMelee = inFight && !inTeam && target != null && ready.contains(Move.SHACKLE_ARROW)
			&& PositionUtil.getDistance(bot, target, true) <= MELEE_DISTANCE;
		// read only when there is an opener to cast: it walks the enemy's skill list
		boolean haveOpener = ready.contains(Move.SILENCE_ARROW) || ready.contains(Move.ENTANGLING_SHOT);
		EnemyKind kind = inFight && !inTeam && !opened && haveOpener && target != null ? kindOf(target) : EnemyKind.OTHER;
		boolean stealthNeeded = !inFight && !inTeam && ready.contains(Move.STEALTH) && !BotSkillManager.isUp(bot, Move.STEALTH.group) && onAJourneyNearEnemies(bot);
		// the need to hide is carried in the ready set rather than a field of its own: only a ranger at rest on a journey is ever asked it
		if (stealthNeeded)
			ready.add(Move.STEALTH);
		else
			ready.remove(Move.STEALTH);
		return new Situation(bot.getLifeStats().getHpPercentage(), bot.getCommonData().getDp(), inTeam, targeted, BotSkillManager.isCrowdControlled(bot),
			BotSkillManager.isChainOpen(bot, "ALL_CHAINA_1TH"), oneMinuteOn, BotSkillManager.isUp(bot, Move.BREATH_OF_NATURE.group),
			BotSkillManager.isUp(bot, Move.BOW_OF_BLESSING.group), BotSkillManager.isUp(bot, Move.SPEED_OF_THE_WIND.group),
			BotSkillManager.isUp(bot, Move.TRANSFORMATION_MAU.group) || BotSkillManager.isUp(bot, Move.TRANSFORMATION_MAU_LIGHT.group),
			BotSkillManager.isUp(bot, Move.FOCUSED_EVASION.group), kind, opened, inMelee, otherEnemy, hold, ready);
	}

	/**
	 * @return What a ranger's hold on its fire becomes. In a group, a ranger whose target is aiming at it stops shooting for five seconds, or until the target
	 *         looks away, and then shoots on whether it does or not; it will not start again for ten seconds. Alone there is nobody to hand the enemy to, so
	 *         it never holds.
	 */
	static Hold nextHold(boolean inTeam, boolean targeted, long now, long since, long rearmAt) {
		if (!inTeam)
			return new Hold(false, false, 0, rearmAt);
		if (since != 0) {
			if (!targeted)
				return new Hold(false, false, 0, rearmAt);
			if (now - since >= HOLD_FIRE_MILLIS)
				return new Hold(false, false, 0, now + HOLD_FIRE_REARM_MILLIS);
			return new Hold(true, false, since, rearmAt);
		}
		if (targeted && now >= rearmAt)
			return new Hold(true, true, now, rearmAt);
		return new Hold(false, false, 0, rearmAt);
	}

	/**
	 * @return Whether the enemy is a caster, which Silence Arrow is for, or fights in melee, which Entangling Shot is for. A caster is one that has a magical
	 *         attack in its skill list; the rest that hit from reach are neither, and get no handicap.
	 */
	static EnemyKind kindOf(Creature target) {
		if (!(target instanceof Npc npc))
			return EnemyKind.OTHER;
		NpcSkillList skills = npc.getSkillList();
		for (int i = 0; i < skills.size(); i++) {
			NpcSkillEntry entry = skills.getSkillOnPosition(i);
			SkillTemplate template = entry == null ? null : DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId());
			if (template != null && template.getType() == SkillType.MAGICAL && template.getSubType() == SkillSubType.ATTACK)
				return EnemyKind.CASTER;
		}
		return npc.getObjectTemplate().getAttackRange() <= MELEE_ENEMY_RANGE ? EnemyKind.MELEE : EnemyKind.OTHER;
	}

	/**
	 * @return true if the ranger is walking somewhere with an aggressive enemy close enough to notice it, or three of them near: the guide's reasons to hide
	 *         while travelling. Read from where it stands now, which is all there is; the route is the walker's.
	 */
	private static boolean onAJourneyNearEnemies(Player bot) {
		if (!(bot.getMoveController() instanceof BotMoveController mover) || !mover.isInMove())
			return false;
		int[] near = { 0 };
		boolean[] noticed = { false };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || BotTargetSelector.isScenery(npc) || !bot.isEnemy(npc))
				return;
			if (PositionUtil.getDistance(bot, npc) <= NOTICE_RADIUS) {
				near[0]++;
				if (npc.getAggroRange() > 0) // one that attacks on sight, as against one that has to be hit first
					noticed[0] = true;
			}
		});
		return noticed[0] || near[0] >= DENSE_ENEMIES;
	}

	/**
	 * What the ranger does about itself while nobody is fighting it: hide when a journey takes it past enemies, then prepare for the next fight.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decideAtRest(Situation situation) {
		if (situation.ready().contains(Move.STEALTH))
			return Move.STEALTH;
		return nextBuff(situation, false);
	}

	/**
	 * The personal buffs, in the guide's order. Breath of Nature once, since it is a toggle that is switched off by a second cast. Then the one minute buffs,
	 * two at most so that no third removes the first and the bot casts it again for ever, best first. Then Bow of Blessing, and Speed of the Wind right after
	 * them. In a fight there is also Devotion, before any damage, and the Transformation with the divine power for it.
	 */
	private static Move nextBuff(Situation situation, boolean inFight) {
		Set<Move> ready = situation.ready();
		if (ready.contains(Move.BREATH_OF_NATURE) && !situation.breathUp())
			return Move.BREATH_OF_NATURE;
		if (situation.oneMinuteOn().size() < ONE_MINUTE_BUFFS_AT_ONCE) {
			for (Move buff : ONE_MINUTE_ORDER) {
				if (ready.contains(buff) && !situation.oneMinuteOn().contains(buff))
					return buff;
			}
		}
		if (ready.contains(Move.BOW_OF_BLESSING) && !situation.bowUp())
			return Move.BOW_OF_BLESSING;
		if (ready.contains(Move.SPEED_OF_THE_WIND) && !situation.windUp())
			return Move.SPEED_OF_THE_WIND;
		if (inFight) {
			if (ready.contains(Move.DEVOTION))
				return Move.DEVOTION;
			if (!situation.transformed() && situation.dp() >= TRANSFORMATION_DP) {
				if (ready.contains(Move.TRANSFORMATION_MAU))
					return Move.TRANSFORMATION_MAU;
				if (ready.contains(Move.TRANSFORMATION_MAU_LIGHT))
					return Move.TRANSFORMATION_MAU_LIGHT;
			}
		}
		return null;
	}

	/**
	 * Picks what the ranger does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * Control first: a ranger that cannot act can only cast Remove Shock, and Finishing Arrow follows it while its chain is open. Then, in a group, the hold
	 * on its fire, with Calming Whisper as it starts. Then the guide's two answers to being hurt, and the buffs before the damage. The openers and
	 * handicaps come last, and only when nothing is wrong.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled())
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		// Before anything else, because the chain is the most fragile thing here: it lasts a second and a half
		if (situation.shockChainOpen() && ready.contains(Move.FINISHING_ARROW))
			return Move.FINISHING_ARROW;
		if (situation.hold().holding()) {
			if (situation.hold().startedNow() && ready.contains(Move.CALMING_WHISPER))
				return Move.CALMING_WHISPER;
			if (evasionIsDue(situation))
				return Move.FOCUSED_EVASION;
			return Move.HOLD_FIRE;
		}
		if (evasionIsDue(situation))
			return Move.FOCUSED_EVASION;
		if (ready.contains(Move.SEIZURE_ARROW) && situation.hpPercent() < SEIZURE_BELOW_PERCENT)
			return Move.SEIZURE_ARROW;
		Move buff = nextBuff(situation, true);
		if (buff != null)
			return buff;
		if (!situation.inTeam()) {
			if (!situation.opened()) {
				if (situation.kind() == EnemyKind.CASTER && ready.contains(Move.SILENCE_ARROW))
					return Move.SILENCE_ARROW;
				if (situation.kind() == EnemyKind.MELEE && ready.contains(Move.ENTANGLING_SHOT))
					return Move.ENTANGLING_SHOT;
			}
			if (situation.inMelee() && ready.contains(Move.SHACKLE_ARROW))
				return Move.SHACKLE_ARROW;
		} else if (situation.otherEnemy() && ready.contains(Move.SLEEP_ARROW)) {
			return Move.SLEEP_ARROW;
		}
		return null;
	}

	/** @return true if the first half of the guide's combo is due: under half health, aimed at, with the heal that follows it ready. */
	private static boolean evasionIsDue(Situation situation) {
		return situation.targeted() && situation.hpPercent() < EVASION_BELOW_PERCENT && situation.ready().contains(Move.FOCUSED_EVASION)
			&& !situation.evasionUp() && situation.ready().contains(Move.SEIZURE_ARROW);
	}
}
