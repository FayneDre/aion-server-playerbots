package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a gunner plays. Written without a guide, as the bard's and the spirit master's were: every rule comes from the skill data or is the module's own
 * judgement of how the class is played, and the plan says which ({@code docs/classes/plans/gunner-plan.md}).
 * <p>
 * A ranged magical damage dealer with two pistols, which is what the module arms it with ({@code BotGearFit.weaponsOfTrade}): the nineteen skills that ask
 * for a cannon are claimed and never cast. Its damage is its chains, whose openers share one cooldown that Reload and Autoload reset: the playbook picks
 * the opener and the moment to reload, and leaves the links, which repeat, to the generic order, which carries an open chain on before anything else.
 * Nothing but a defence is cast while one of those is open, since any other cast closes it.
 */
final class GunnerPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(GunnerPlaybook.class);

	/**
	 * What a pistol gunner never casts: every skill that asks for a cannon, which the engine would refuse, and Revealing Scope, a sight of the hidden that
	 * nothing yet asks for and the generic buffs would raise every two minutes.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("GU_VASSBLASTER", "GU_ICECANNON", "GU_SHOCKCANNON", "GU_SOULBREAKCANNON", "GU_FLAMECANNON",
		"GU_SNIPINGSHOT", "GU_FIRECANNON", "GU_BINDINGCANNON", "GU_MAGICCANNON", "GU_GATEBLOWCANNON", "GU_CONDENSEDBLASTER", "GU_ARMORBREAK",
		"GU_ANTIGRAVITYCANNON", "GU_GRAVITYCANNON", "GU_MENTALICCANNON", "GU_SUPPORTFIRE", "GU_ICEBALLCANNON", "GU_MENTALICBLASTER", "GU_WINGBLASTER",
		"GU_MAGICALSEARCHEYE");
	/** The regular stigmas, pistol ones: Steady Fire, Sighting, Nature's Favor, Power Grab. The module's judgement, not a guide's. */
	static final List<String> REGULAR_STIGMAS = List.of("GU_ONEPOINTRAPIDFIRE", "GU_BULLSEYE", "GU_MAGICALBREATHING", "GU_MENTALSNATCHER");
	/** The advanced ones, pistol ones, enough of them that no cannon stone is ever reached. */
	static final List<String> ADVANCED_STIGMAS = List.of("GU_VEINSNIPE", "GU_EMPOWERAMMO", "GU_FIRECHAINREFRESH", "GU_SUNNINGSHOT", "GU_PURSUIT",
		"GU_EMPOWERMAGIC", "GU_BLINDLYSHOT");

	/**
	 * The chains the generic order carries on once open, every link that has one after it: A (Gunshot, Rapidfire, Automatic Fire), B (Trunk Shot,
	 * Volley), C (Steady Fire, Pulverizer Cannon), D (Crosstrigger, Canted Shot) and F (Anti-Enemy Fire). While one is the current chain the playbook casts
	 * nothing but a defence.
	 */
	static final List<String> FOLLOWED_CHAINS = List.of("E_CHAINA_1TH_1", "E_CHAINA_2TH_1", "E_CHAINA_3TH_1", "E_CHAINB_1TH_1", "E_CHAINB_2TH_1",
		"E_CHAINC_1TH_1", "E_CHAIND_1TH_1", "E_CHAIND_2TH_1", "E_CHAINF_1TH_1");
	/** The chain Remove Shock opens, which Steal Health and Teleport Escape follow. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";

	/** Health under which Bulletproof is raised while hit, Bullet Resistance, and under which a lone gunner leaves Remove Shock by Teleport Escape. */
	static final int BULLETPROOF_BELOW = 50, SHIELD_BELOW = 70, ESCAPE_BELOW = 30;
	/** Health under which Steal Health is worth its cooldown after Remove Shock. */
	static final int STEAL_HEALTH_BELOW = 80;
	/** Health under which a lone gunner leaves. */
	static final int FLEE_BELOW = 25;
	/** What Spend Success costs. */
	static final int ABILITY_DP = 2000;
	/** How many enemies on a lone gunner make its cooldowns worth spending, as an elite would. */
	static final int ENEMIES_WORTH_A_BURST = 3;
	/** How near a melee enemy is a threat to a gunner alone, and how near Dazzling Fire reaches. */
	static final float MELEE_THREAT = 5f, DAZZLE_REACH = 5f;
	/** How far a gunner looks for what is hitting it. */
	static final float ENEMY_RADIUS = 25f;

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** An enemy engaged and not held, which the controls are for. */
		SECOND
	}

	/** What the playbook may cast; every one claimed, kept from the generic order. */
	enum Move {
		// the openers, which share one cooldown, and what resets it
		GUNSHOT("EN_DOUBLEFIRECHAIN", Aim.TARGET),
		/** Staggers: the opener for a target that casts. */
		TRUNK_SHOT("GU_FOREHEADSNIPE", Aim.TARGET),
		/** Burns mana too: the opener against a player. */
		CROSSTRIGGER("GU_MENTALICFIRECHAIN", Aim.TARGET),
		RELOAD("GU_FIRECHAINRELOAD", Aim.SELF),
		AUTOLOAD("GU_FIRECHAINREFRESH", Aim.SELF),
		// upkeep
		/** A toggle: auto attacks 10 % faster, at no cost the data states. */
		STABLE_STANCE("GU_RAPIDSTANCE", Aim.SELF),
		/** A proc on the gunner's shots for five minutes. */
		AIONS_FAVOR("GU_MAGICALPROMISE", Aim.SELF),
		// the cooldowns, on what is worth them
		PRESSURIZED_CHAMBER("GU_EMPOWERAMMO", Aim.SELF),
		SIGHTING("GU_BULLSEYE", Aim.SELF),
		NATURES_FAVOR("GU_MAGICALBREATHING", Aim.SELF),
		STOPPING_POWER("GU_EMPOWERMAGIC", Aim.SELF),
		PURSUIT_STANCE("GU_PURSUIT", Aim.SELF),
		ANTICIPATION("GU_MINDREADING", Aim.SELF),
		SPEND_SUCCESS("GU_MAGICALSTRENGTH", Aim.SELF),
		// defence
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		STEAL_HEALTH("GU_REFRESHINGATTACK", Aim.TARGET),
		/** A jump to somewhere near and a cleanse: after Remove Shock, the way out. */
		TELEPORT_ESCAPE("GU_LIGHTNINGMOVEMENT", Aim.SELF),
		BULLETPROOF("GU_CHECKINGTOAVOID", Aim.SELF),
		BULLET_RESISTANCE("EN_FORMWALL", Aim.SELF),
		/** Cleansed and moved: out of a root with a melee on it. */
		SURVIVAL_INSTINCT("GU_SURVIVALINSTINCT", Aim.SELF),
		/** Lowers the gunner's hate on the target. */
		MEMORY_MANIPULATION("GU_MEMORYMANIPULATION", Aim.TARGET),
		/** A shot and a jump back. */
		PARTING_SHOT("GU_BACKSTEPPINGSNIPE", Aim.TARGET),
		// control
		GREEN_GRENADE("EN_ANKLESNIPE", Aim.SECOND),
		SPINNING_FIRE("GU_AIMINGSTRIKE", Aim.TARGET),
		QUIETING_GALE("GU_SILENCESHOT", Aim.TARGET),
		DAZZLING_FIRE("GU_SUNNINGSHOT", Aim.TARGET),
		WING_CLIP("GU_AIMWING", Aim.TARGET);

		final String group;
		final Aim aim;

		Move(String group, Aim aim) {
			this.group = group;
			this.aim = aim;
		}
	}

	/** The cooldowns spent on what is worth them, the longest lasting first. */
	static final List<Move> BURST_ORDER = List.of(Move.NATURES_FAVOR, Move.PURSUIT_STANCE, Move.ANTICIPATION, Move.SIGHTING, Move.STOPPING_POWER,
		Move.PRESSURIZED_CHAMBER);
	/** The openers, the usual one first. */
	static final List<Move> OPENERS = List.of(Move.GUNSHOT, Move.TRUNK_SHOT, Move.CROSSTRIGGER);

	/** Everything the decisions look at, filled field by field: a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		int hp = 100, dp;
		boolean inTeam, controlled, assailed;
		boolean stanceUp = true, favorUp = true, shieldUp;
		/** Whether a chain the generic order carries on is open, and Remove Shock's. */
		boolean followedChainOpen, shockChainOpen;
		/** Held in place or slowed, and a melee enemy within reach of the gunner. */
		boolean rootedOrSnared, meleeNear;
		/** The target: on the gunner, a caster, a player, flying, an elite or a boss, a melee within reach. */
		boolean targetOnMe, targetCasts, targetIsPlayer, targetFlying, targetElite, targetMeleeNear, targetWithin5m;
		boolean secondToControl;
		int enemiesEngaged;

		Situation ready(Move... moves) {
			ready.addAll(List.of(moves));
			return this;
		}

		Situation readyAll() {
			ready = EnumSet.allOf(Move.class);
			return this;
		}

		/** @return The same situation with the move no longer ready, for trying the next one when the engine refused it. */
		Situation without(Move move) {
			Situation copy = copy();
			copy.ready.remove(move);
			return copy;
		}

		private Situation copy() {
			Situation s = new Situation();
			s.ready = EnumSet.noneOf(Move.class);
			s.ready.addAll(ready);
			s.hp = hp;
			s.dp = dp;
			s.inTeam = inTeam;
			s.controlled = controlled;
			s.assailed = assailed;
			s.stanceUp = stanceUp;
			s.favorUp = favorUp;
			s.shieldUp = shieldUp;
			s.followedChainOpen = followedChainOpen;
			s.shockChainOpen = shockChainOpen;
			s.rootedOrSnared = rootedOrSnared;
			s.meleeNear = meleeNear;
			s.targetOnMe = targetOnMe;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetFlying = targetFlying;
			s.targetElite = targetElite;
			s.targetMeleeNear = targetMeleeNear;
			s.targetWithin5m = targetWithin5m;
			s.secondToControl = secondToControl;
			s.enemiesEngaged = enemiesEngaged;
			return s;
		}
	}

	/** The creatures a move may be aimed at, which the situation only says exist. */
	record Targets(Creature target, Creature second) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the gunner alone decides on this skill. Takes the template so that every template in the game can be put through it. */
	static boolean claimsSkill(SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (FORBIDDEN_GROUPS.contains(group))
			return true;
		for (Move move : Move.values()) {
			if (group.equals(move.group))
				return true;
		}
		return false;
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && bot.getLifeStats().getHpPercentage() < FLEE_BELOW;
	}

	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.BULLETPROOF.group))
			log.debug("Gunner {} casts {} as it flees", bot.getName(), Move.BULLETPROOF);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public boolean prepare(Player bot) {
		Reading reading = readingOf(bot, null);
		return performFirst(bot, reading, GunnerPlaybook::decideUpkeep);
	}

	/** Its defences, then its upkeep, then the fight. */
	@Override
	public boolean act(Player bot, Creature target) {
		Reading reading = readingOf(bot, target);
		return performFirst(bot, reading, GunnerPlaybook::decideDefence) || performFirst(bot, reading, GunnerPlaybook::decideUpkeep)
			|| performFirst(bot, reading, GunnerPlaybook::decideOffence);
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** The stance and the five minute proc; not while a chain is open, which any other cast would close. */
	static Move decideUpkeep(Situation s) {
		if (s.controlled || s.followedChainOpen)
			return null;
		if (!s.stanceUp && s.ready.contains(Move.STABLE_STANCE))
			return Move.STABLE_STANCE;
		if (!s.favorUp && s.ready.contains(Move.AIONS_FAVOR))
			return Move.AIONS_FAVOR;
		return null;
	}

	/** What the gunner does about being held, hit and nearly dead: asked first, and the one thing cast while a chain is open. */
	static Move decideDefence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.shockChainOpen) {
			if (!s.inTeam && s.hp < ESCAPE_BELOW && ready.contains(Move.TELEPORT_ESCAPE))
				return Move.TELEPORT_ESCAPE;
			if (s.hp < STEAL_HEALTH_BELOW && ready.contains(Move.STEAL_HEALTH))
				return Move.STEAL_HEALTH;
		}
		if (s.assailed && s.hp < BULLETPROOF_BELOW && ready.contains(Move.BULLETPROOF))
			return Move.BULLETPROOF;
		if (s.assailed && s.hp < SHIELD_BELOW && !s.shieldUp && ready.contains(Move.BULLET_RESISTANCE))
			return Move.BULLET_RESISTANCE;
		if (s.rootedOrSnared && s.meleeNear && ready.contains(Move.SURVIVAL_INSTINCT))
			return Move.SURVIVAL_INSTINCT;
		if (s.inTeam && s.targetOnMe && ready.contains(Move.MEMORY_MANIPULATION))
			return Move.MEMORY_MANIPULATION;
		// alone, a melee on the gunner is kept off: a jump back, else held or slowed where it stands
		if (!s.inTeam && s.targetMeleeNear && !s.followedChainOpen) {
			for (Move move : List.of(Move.PARTING_SHOT, Move.SPINNING_FIRE)) {
				if (ready.contains(move))
					return move;
			}
		}
		return null;
	}

	/** @return true if the target is worth the cooldowns: an elite, a boss or a player, or three enemies on a gunner alone. */
	static boolean worthABurst(Situation s) {
		return s.targetElite || s.targetIsPlayer || !s.inTeam && s.enemiesEngaged >= ENEMIES_WORTH_A_BURST;
	}

	/**
	 * The fight once the gunner is looked after, while no chain is open: the divine power and the cooldowns on what is worth them, a second enemy held, a
	 * caster silenced, a player stunned or clipped, then a chain opened, or the openers reset. The links and the fillers are the generic order's.
	 */
	static Move decideOffence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled || s.followedChainOpen)
			return null;
		if (worthABurst(s)) {
			if (s.dp >= ABILITY_DP && ready.contains(Move.SPEND_SUCCESS))
				return Move.SPEND_SUCCESS;
			for (Move move : BURST_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.secondToControl && (s.inTeam || s.enemiesEngaged > 1) && ready.contains(Move.GREEN_GRENADE))
			return Move.GREEN_GRENADE;
		if (s.targetCasts && ready.contains(Move.QUIETING_GALE))
			return Move.QUIETING_GALE;
		if (s.targetIsPlayer && s.targetWithin5m && ready.contains(Move.DAZZLING_FIRE))
			return Move.DAZZLING_FIRE;
		if (s.targetIsPlayer && s.targetFlying && ready.contains(Move.WING_CLIP))
			return Move.WING_CLIP;
		Move opener = opener(s);
		if (opener != null)
			return opener;
		// every opener cooling down together: a reload gives one back
		if (ready.contains(Move.RELOAD))
			return Move.RELOAD;
		return ready.contains(Move.AUTOLOAD) ? Move.AUTOLOAD : null;
	}

	/** @return The opener for this target, or null when they are cooling down: Trunk Shot's stagger on a caster, Crosstrigger's mana on a player, else Gunshot. */
	static Move opener(Situation s) {
		Move wanted = s.targetCasts ? Move.TRUNK_SHOT : s.targetIsPlayer ? Move.CROSSTRIGGER : Move.GUNSHOT;
		if (s.ready.contains(wanted))
			return wanted;
		for (Move move : OPENERS) {
			if (s.ready.contains(move))
				return move;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// Casting
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** What the bot saw: the facts the decisions weigh, and the creatures they may be about. */
	record Reading(Situation situation, Targets targets) {
	}

	/** One of the decisions above, asked again without a move the engine refused, until one goes through or it has nothing more to say. */
	private interface Decider {
		Move decide(Situation s);
	}

	private static boolean performFirst(Player bot, Reading reading, Decider decider) {
		Situation s = reading.situation();
		for (int tries = 0; tries < Move.values().length; tries++) {
			Move move = decider.decide(s);
			if (move == null)
				return false;
			if (perform(bot, reading, move))
				return true;
			s = s.without(move);
		}
		return false;
	}

	private static boolean perform(Player bot, Reading reading, Move move) {
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> reading.targets().target();
			case SECOND -> reading.targets().second();
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Gunner {} casts {} at {} ({}% health)", bot.getName(), move, aimedAt.getName(), reading.situation().hp);
		return cast;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The situation
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	private static Reading readingOf(Player bot, Creature target) {
		Situation s = new Situation();
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				s.ready.add(move);
		}
		s.inTeam = bot.getCurrentTeam() != null;
		s.hp = bot.getLifeStats().getHpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		s.assailed = BotGroupManager.enemyHitting(bot, ENEMY_RADIUS) != null;
		s.stanceUp = BotSkillManager.isUp(bot, Move.STABLE_STANCE.group);
		s.favorUp = BotSkillManager.isUp(bot, Move.AIONS_FAVOR.group);
		s.shieldUp = BotSkillManager.isUp(bot, Move.BULLET_RESISTANCE.group);
		s.followedChainOpen = FOLLOWED_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		s.shockChainOpen = BotSkillManager.isChainOpen(bot, SHOCK_CHAIN);
		s.rootedOrSnared = bot.getEffectController().isAbnormalSet(AbnormalState.ROOT) || bot.getEffectController().isAbnormalSet(AbnormalState.SNARE);
		boolean[] meleeNear = { false };
		int[] engaged = { 0 };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !bot.isEnemy(npc) || !npc.getAggroList().isHating(bot) && !npc.equals(target))
				return;
			engaged[0]++;
			if (PositionUtil.getDistance(bot, npc) <= MELEE_THREAT && RangerPlaybook.kindOf(npc) == RangerPlaybook.EnemyKind.MELEE)
				meleeNear[0] = true;
		});
		s.meleeNear = meleeNear[0];
		s.enemiesEngaged = engaged[0];
		Creature second = null;
		if (target != null) {
			s.targetOnMe = bot.equals(target.getTarget());
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			s.targetFlying = target.isFlying();
			s.targetElite = target instanceof Npc npc && BardPlaybook.isEliteOrBoss(npc.getObjectTemplate().getRating());
			double distance = PositionUtil.getDistance(bot, target);
			s.targetWithin5m = distance <= DAZZLE_REACH;
			s.targetMeleeNear = distance <= MELEE_THREAT && (target instanceof Player || RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.MELEE);
			second = SorcererPlaybook.secondToControl(bot, target);
			s.secondToControl = second != null;
		}
		return new Reading(s, new Targets(target, second));
	}
}
