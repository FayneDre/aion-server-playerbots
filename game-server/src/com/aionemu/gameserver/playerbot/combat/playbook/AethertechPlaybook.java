package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.attack.AggroTarget;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotMarks;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How an aethertech plays. Written without a guide, as the bard's, the spirit master's and the gunner's were: every rule comes from the skill data or is
 * the module's own judgement of how the class is played, and the plan says which ({@code docs/classes/plans/aethertech-plan.md}).
 * <p>
 * A magical melee class in its mech, and a tank as the templar is: {@code BotRole} names one tank per group, so this one holds the enemy only when it is
 * that one. Its damage is its chains, all of which the generic order carries on; the playbook keeps the toggles the generic order never casts (two mana
 * shields and a mana-hungry boost, on in a fight and off after it), its hate, its divine power, its defences and its controls. The engineer's gun skills
 * in its tree ask for a gun, and it holds a key: claimed and never cast. Nothing but a defence or a taunt is cast while a chain is open.
 */
final class AethertechPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(AethertechPlaybook.class);

	/**
	 * What an aethertech never casts: the engineer's six gun skills, Mobility Thrusters (speed bought with 810 physical defence, which a tank cannot
	 * spare), and Boost and Double Boost, dashes of 15 m wherever the mech faces.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("EN_ENFIRECHAIN", "EN_ANKLESNIPE", "GU_SNIPINGFIRE", "EN_FORMWALL", "EN_DOUBLEFIRECHAIN",
		"GU_CRACKSNIPE", "RI_SPEEDOVERDRIVE", "RI_FORWARDDASH", "RI_CHAINDASH");
	/** The regular stigmas: Riplash, Aimbot Assist, Drillbore, Steel Storm, Aether Recharge. The module's judgement, not a guide's. */
	static final List<String> REGULAR_STIGMAS = List.of("RI_FORWARDSTRIKE", "RI_TUNESENSOR", "RI_SHIELDDESTROYER", "RI_EATHIUMBLAST", "RI_EATHIUMREACTIVE");
	/** The advanced ones: Rain of Knuckles, Aethercharged Steel, Trauma Plate Trigger, Kinetic Bulwark, Convulsion Beam, Meteor Strike. */
	static final List<String> ADVANCED_STIGMAS = List.of("RI_RAPIDATTACK", "RI_OVERCHARGE", "RI_MAGNETICFIELD", "RI_EATHIUMWALL", "RI_BINDINGSLAM",
		"RI_POWERSLAM");

	/**
	 * The chains the generic order carries on once open, every category a link follows or repeats: I (Bludgeon, Battery), C (Rocket Punch), D (Sundering
	 * Blade, Rain of Knuckles three times), E (Siphoning Slash), F (Cinder Cannon, Gatling Gun twice), B (Chilling Wave) and H (Electric Shock).
	 */
	static final List<String> FOLLOWED_CHAINS = List.of("E_CHAINI_1TH_1", "E_CHAINI_2TH_1", "E_CHAINC_1TH_1", "E_CHAIND_1TH_1", "E_CHAIND_2TH_1",
		"E_CHAINE_1TH_1", "E_CHAINF_1TH_1", "E_CHAINF_2TH_1", "E_CHAINB_1TH_1", "E_CHAINH_1TH_1");
	/** The chain Remove Shock opens, which Uppercut and Protective Shell follow. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";

	/** Mana above which Kinetic Battery is turned on in a fight, and under which it is turned off: apart, so that it is not switched every tick. */
	static final int BATTERY_ON = 20, BATTERY_OFF = 10;
	/** The same for Stability Thrusters, which costs 4 % of the mana every four seconds. */
	static final int THRUSTERS_ON = 40, THRUSTERS_OFF = 30;
	/** Kinetic Bulwark takes 60 % of every blow from the mana: raised with half of it, ended under a fifth. */
	static final int BULWARK_MANA = 50, BULWARK_OFF = 20;
	/** Health under which Kinetic Bulwark, Nullification Trigger and Protective Shell are raised. */
	static final int BULWARK_BELOW = 60, NULLIFICATION_BELOW = 40, SHELL_BELOW = 50;
	/** Health under which a lone aethertech leaves, Hypergate Detonation first. */
	static final int FLEE_BELOW = 25;
	/** Mana under which Recharge or Powerspike Trigger is spent in a fight, Power Flux turns divine power into mana, Aether Recharge is cast. */
	static final int RECHARGE_BELOW = 25, POWER_FLUX_BELOW = 30, AETHER_RECHARGE_BELOW = 50;
	/** Health under which Recharge is spent for its 3000 health, whatever the mana. */
	static final int RECHARGE_HEALTH_BELOW = 30;
	/** What Overdrive Trigger and Power Flux cost, and Annihilation Barrage. */
	static final int OVERDRIVE_DP = 2000, BARRAGE_DP = 4000;
	/** How many enemies near a lone aethertech make its cooldowns and Annihilation Barrage worth spending, as an elite would. */
	static final int ENEMIES_WORTH_A_BURST = 3;
	/** How many enemies loose on the group make Magnetic Wave worth its cast rather than Provoking Whispers. */
	static final int LOOSE_FOR_THE_WAVE = 2;
	/** Flight time, in percent, under which Fuel Reserves is spent. */
	static final int FUEL_BELOW = 33;
	/** Magnetic Wave's and Annihilation Barrage's reach in front of the mech, Electric Shock's and Provoking Whispers', and how far it looks. */
	static final float WAVE_REACH = 15f, SHOCK_REACH = 8f, WHISPER_REACH = 6f, ENEMY_RADIUS = 25f;

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** An enemy engaged and not held, which the controls are for. */
		SECOND,
		/** An enemy hitting a group mate, which the tank takes back. */
		LOOSE
	}

	/** What the playbook may cast; every one claimed, kept from the generic order. */
	enum Move {
		// the toggles
		KINETIC_BATTERY("RI_EATHIUMSHIELD", Aim.SELF),
		STABILITY_THRUSTERS("RI_POWEROVERDRIVE", Aim.SELF),
		KINETIC_BULWARK("RI_EATHIUMWALL", Aim.SELF),
		/** Hate +100 % and nothing else: the group's tank's alone. */
		MOUNTING_FRUSTRATION("RI_WARNINGALARM", Aim.SELF),
		// the taunts
		MAGNETIC_WAVE("RI_PROVOKEWAVE", Aim.SELF),
		PROVOKING_WHISPERS("RI_PROVOKINGATTACK", Aim.TARGET),
		PROVOKING_WHISPERS_LOOSE("RI_PROVOKINGATTACK", Aim.LOOSE),
		// upkeep and the cooldowns, on what is worth them
		CHARGING_ACTIVATION("RI_ACTIVATEGENERATOR", Aim.SELF),
		LEECHING_STEEL("RI_ITHEUMREFLECTOR", Aim.SELF),
		AIMBOT_ASSIST("RI_TUNESENSOR", Aim.SELF),
		LIFE_SUPPORT_TRIGGER("RI_EATHIUMCOMPRESS", Aim.SELF),
		AETHERCHARGED_STEEL("RI_OVERCHARGE", Aim.SELF),
		// divine power
		OVERDRIVE_TRIGGER("RI_HIGHENDOVERDRIVE", Aim.SELF),
		ANNIHILATION_BARRAGE("RI_EXPLODEDP", Aim.SELF),
		POWER_FLUX("RI_DPCHARGE", Aim.SELF),
		// mana
		AETHER_RECHARGE("RI_EATHIUMREACTIVE", Aim.SELF),
		RECHARGE("RI_RAPIDCHARGE", Aim.SELF),
		POWERSPIKE_TRIGGER("RI_EATHIUMCHARGE", Aim.SELF),
		// defence
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		PROTECTIVE_SHELL("RI_PROTECTIONCURTAIN", Aim.SELF),
		UPPERCUT("RI_UPPERCUT", Aim.TARGET),
		NULLIFICATION_TRIGGER("RI_EATHIUMCURTAIN", Aim.SELF),
		TRAUMA_PLATE_TRIGGER("RI_MAGNETICFIELD", Aim.SELF),
		/** Cleanses roots and snares. */
		REPAIR_PROTOCOL("RI_INSTANTTHRUST", Aim.SELF),
		/** A jump back, a cleanse and a trap left behind; it ends the mech, so it is the way out and nothing else. */
		HYPERGATE_DETONATION("RI_ESCAPEROBOT", Aim.SELF),
		// control
		ELECTRIC_SHOCK("RI_TANGLINGATTACK", Aim.SECOND),
		CONVULSION_BEAM("RI_BINDINGSLAM", Aim.SECOND),
		AETHERLOCK_BLADE("RI_SHOCKSTRIKE", Aim.TARGET),
		DRILLBORE("RI_SHIELDDESTROYER", Aim.TARGET),
		BACKLASH("RI_BREAKINGATTACK", Aim.TARGET),
		/** Only on a target stunned, staggered or stumbling, which the data asks. */
		STEAM_RUSH("RI_CHARGEATTACK", Aim.TARGET),
		// flight
		FUEL_RESERVES("RI_REPLENISHBLAST", Aim.SELF);

		final String group;
		final Aim aim;

		Move(String group, Aim aim) {
			this.group = group;
			this.aim = aim;
		}
	}

	/** The cooldowns spent on what is worth them, the longest lasting first. */
	static final List<Move> BURST_ORDER = List.of(Move.LEECHING_STEEL, Move.AIMBOT_ASSIST, Move.LIFE_SUPPORT_TRIGGER, Move.AETHERCHARGED_STEEL);

	/** Everything the decisions look at, filled field by field: a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		int hp = 100, mp = 100, dp;
		boolean inTeam, controlled, assailed;
		/** Whether the bot is the one holding what its group fights: alone, or its group's tank. */
		boolean groupTank = true;
		boolean batteryUp, thrustersUp, bulwarkUp, frustrationUp, generatorUp = true, overdriveUp;
		/** Whether a chain the generic order carries on is open, and Remove Shock's. */
		boolean followedChainOpen, shockChainOpen;
		boolean rootedOrSnared;
		/** The target: on the aethertech, a caster, a player, an elite or a boss, a boss, stunned, hitting somebody else and within Whispers' reach. */
		boolean targetOnMe, targetCasts, targetIsPlayer, targetElite, targetBoss, targetStunned, targetLoose, targetWithinWhisper;
		/** Another enemy on a mate within Whispers' reach, and how many are on mates within the wave's. */
		boolean looseWithinWhisper;
		int looseNear;
		boolean secondToControl, secondWithinShock;
		/** Every enemy fighting the bot or its group, and those of them within the wave's reach. */
		int enemiesEngaged, enemiesNear;

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
			s.mp = mp;
			s.dp = dp;
			s.inTeam = inTeam;
			s.controlled = controlled;
			s.assailed = assailed;
			s.groupTank = groupTank;
			s.batteryUp = batteryUp;
			s.thrustersUp = thrustersUp;
			s.bulwarkUp = bulwarkUp;
			s.frustrationUp = frustrationUp;
			s.generatorUp = generatorUp;
			s.overdriveUp = overdriveUp;
			s.followedChainOpen = followedChainOpen;
			s.shockChainOpen = shockChainOpen;
			s.rootedOrSnared = rootedOrSnared;
			s.targetOnMe = targetOnMe;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetElite = targetElite;
			s.targetBoss = targetBoss;
			s.targetStunned = targetStunned;
			s.targetLoose = targetLoose;
			s.targetWithinWhisper = targetWithinWhisper;
			s.looseWithinWhisper = looseWithinWhisper;
			s.looseNear = looseNear;
			s.secondToControl = secondToControl;
			s.secondWithinShock = secondWithinShock;
			s.enemiesEngaged = enemiesEngaged;
			s.enemiesNear = enemiesNear;
			return s;
		}
	}

	/** The creatures a move may be aimed at, which the situation only says exist. */
	record Targets(Creature target, Creature second, Creature loose) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the aethertech alone decides on this skill. Takes the template so that every template in the game can be put through it. */
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

	/**
	 * Hypergate Detonation as it breaks off, Nullification Trigger without it. Here rather than in {@link #decideDefence} alone: the retreat is decided on
	 * the decision tick at the same health, so a lone aethertech was gone before its attack tick saw the threshold. It ends the mech, which a bot
	 * running home has no use for, and it is cast standing, as the data asks, since the bot has not set off yet.
	 */
	@Override
	public void onRetreat(Player bot) {
		for (Move move : List.of(Move.HYPERGATE_DETONATION, Move.NULLIFICATION_TRIGGER)) {
			if (BotSkillManager.tryCastGroup(bot, bot, move.group)) {
				log.debug("Aethertech {} casts {} as it flees", bot.getName(), move);
				return;
			}
		}
	}

	@Override
	public void whileFlying(Player bot) {
		if (bot.getLifeStats().getMaxFp() <= 0 || bot.getLifeStats().getCurrentFp() * 100 >= bot.getLifeStats().getMaxFp() * FUEL_BELOW)
			return;
		if (BotSkillManager.tryCastGroup(bot, bot, Move.FUEL_RESERVES.group))
			log.debug("Aethertech {} casts {} at {} fp", bot.getName(), Move.FUEL_RESERVES, bot.getLifeStats().getCurrentFp());
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	/** Out of a fight: the toggles that drain mana ended, so that a resting aethertech regains it, then Aether Recharge on a low bar. */
	@Override
	public boolean tend(Player bot) {
		Reading reading = readingOf(bot, null);
		if (endToggles(bot, togglesToEnd(reading.situation(), false)))
			return true;
		Situation s = reading.situation();
		// three seconds cast standing (move_casting false): one started on the way would be thrown away and the skill paused
		boolean moving = bot.getMoveController() instanceof BotMoveController mover && mover.isInMove();
		return s.mp < AETHER_RECHARGE_BELOW && !s.assailed && !moving && perform(bot, reading, Move.AETHER_RECHARGE);
	}

	/** A fight coming: Charging Activation, and the group's tank's hate. */
	@Override
	public boolean prepare(Player bot) {
		Reading reading = readingOf(bot, null);
		return performFirst(bot, reading, AethertechPlaybook::decidePreparation);
	}

	/** The toggles it no longer wants ended, which is no cast; then its defences, its hold on the enemy, its upkeep and the fight. */
	@Override
	public boolean act(Player bot, Creature target) {
		Reading reading = readingOf(bot, target);
		endToggles(bot, togglesToEnd(reading.situation(), true));
		return performFirst(bot, reading, AethertechPlaybook::decideDefence) || performFirst(bot, reading, AethertechPlaybook::decideHold)
			|| performFirst(bot, reading, AethertechPlaybook::decideUpkeep) || performFirst(bot, reading, AethertechPlaybook::decideOffence);
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** @return true if the bot should be raising the hate of what it fights: it is its group's tank, and in a group. Alone the hate is its own anyway. */
	static boolean holdsTheEnemy(Situation s) {
		return s.inTeam && s.groupTank;
	}

	/**
	 * @param inFight Whether the bot is fighting: out of one, every toggle that drains mana goes.
	 * @return The toggles to turn off now, by removing their effect rather than casting them again.
	 */
	static List<Move> togglesToEnd(Situation s, boolean inFight) {
		List<Move> ended = new ArrayList<>();
		if (s.batteryUp && (!inFight || s.mp < BATTERY_OFF))
			ended.add(Move.KINETIC_BATTERY);
		if (s.thrustersUp && (!inFight || s.mp < THRUSTERS_OFF))
			ended.add(Move.STABILITY_THRUSTERS);
		if (s.bulwarkUp && (!inFight || s.mp < BULWARK_OFF))
			ended.add(Move.KINETIC_BULWARK);
		if (s.frustrationUp && !holdsTheEnemy(s))
			ended.add(Move.MOUNTING_FRUSTRATION);
		return ended;
	}

	/** Before a fight: the proc kept up, and the hate of the group's tank raised before the first blow. */
	static Move decidePreparation(Situation s) {
		if (s.controlled)
			return null;
		if (!s.generatorUp && s.ready.contains(Move.CHARGING_ACTIVATION))
			return Move.CHARGING_ACTIVATION;
		if (holdsTheEnemy(s) && !s.frustrationUp && s.ready.contains(Move.MOUNTING_FRUSTRATION))
			return Move.MOUNTING_FRUSTRATION;
		return null;
	}

	/** What the aethertech does about being held, hit and nearly dead: asked first, and cast while a chain is open. */
	static Move decideDefence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.shockChainOpen) {
			if (s.hp < SHELL_BELOW && ready.contains(Move.PROTECTIVE_SHELL))
				return Move.PROTECTIVE_SHELL;
			if (ready.contains(Move.UPPERCUT))
				return Move.UPPERCUT;
		}
		if (!s.inTeam && s.hp < FLEE_BELOW && ready.contains(Move.HYPERGATE_DETONATION))
			return Move.HYPERGATE_DETONATION;
		if (s.assailed && s.hp < NULLIFICATION_BELOW && ready.contains(Move.NULLIFICATION_TRIGGER))
			return Move.NULLIFICATION_TRIGGER;
		if (s.assailed && s.hp < BULWARK_BELOW && !s.bulwarkUp && s.mp >= BULWARK_MANA && s.targetElite && s.targetOnMe
			&& ready.contains(Move.KINETIC_BULWARK))
			return Move.KINETIC_BULWARK;
		if (s.targetIsPlayer && s.targetOnMe && ready.contains(Move.TRAUMA_PLATE_TRIGGER))
			return Move.TRAUMA_PLATE_TRIGGER;
		if (s.rootedOrSnared && ready.contains(Move.REPAIR_PROTOCOL))
			return Move.REPAIR_PROTOCOL;
		if (s.assailed && s.mp < RECHARGE_BELOW) {
			for (Move move : List.of(Move.RECHARGE, Move.POWERSPIKE_TRIGGER)) {
				if (ready.contains(move))
					return move;
			}
		}
		// Recharge gives back 3000 health as well, and claimed here it is no longer the generic heal's to cast
		if (s.assailed && s.hp < RECHARGE_HEALTH_BELOW && ready.contains(Move.RECHARGE))
			return Move.RECHARGE;
		return null;
	}

	/**
	 * The group's tank's hold on the enemy: the wave when two are on mates, Provoking Whispers on its own target turned to somebody else, then on another
	 * that has got loose, and its hate raised. The taunts go while a chain is open, since a mate being hit costs more than a link; the toggle waits.
	 */
	static Move decideHold(Situation s) {
		if (s.controlled || !holdsTheEnemy(s))
			return null;
		Set<Move> ready = s.ready;
		if (s.looseNear >= LOOSE_FOR_THE_WAVE && ready.contains(Move.MAGNETIC_WAVE))
			return Move.MAGNETIC_WAVE;
		if (s.targetLoose && s.targetWithinWhisper && ready.contains(Move.PROVOKING_WHISPERS))
			return Move.PROVOKING_WHISPERS;
		if (s.looseWithinWhisper && ready.contains(Move.PROVOKING_WHISPERS_LOOSE))
			return Move.PROVOKING_WHISPERS_LOOSE;
		if (!s.frustrationUp && !s.followedChainOpen && ready.contains(Move.MOUNTING_FRUSTRATION))
			return Move.MOUNTING_FRUSTRATION;
		return null;
	}

	/** The mana shield and the boost on while the mana holds, and the proc; not while a chain is open, which any other cast would close. */
	static Move decideUpkeep(Situation s) {
		if (s.controlled || s.followedChainOpen)
			return null;
		Set<Move> ready = s.ready;
		if (!s.batteryUp && s.mp >= BATTERY_ON && ready.contains(Move.KINETIC_BATTERY))
			return Move.KINETIC_BATTERY;
		if (!s.thrustersUp && s.mp >= THRUSTERS_ON && ready.contains(Move.STABILITY_THRUSTERS))
			return Move.STABILITY_THRUSTERS;
		if (!s.generatorUp && ready.contains(Move.CHARGING_ACTIVATION))
			return Move.CHARGING_ACTIVATION;
		if (s.mp < AETHER_RECHARGE_BELOW && !s.assailed && ready.contains(Move.AETHER_RECHARGE))
			return Move.AETHER_RECHARGE;
		return null;
	}

	/** @return true if the target is worth the cooldowns: an elite, a boss or a player, or three enemies on an aethertech alone. */
	static boolean worthABurst(Situation s) {
		return s.targetElite || s.targetIsPlayer || !s.inTeam && s.enemiesEngaged >= ENEMIES_WORTH_A_BURST;
	}

	/** @return true if Annihilation Barrage is worth its 4000 divine power: on a boss, or three enemies within its reach of an aethertech alone. */
	static boolean worthTheBarrage(Situation s) {
		return s.targetBoss || !s.inTeam && s.enemiesNear >= ENEMIES_WORTH_A_BURST;
	}

	/**
	 * The fight once the aethertech is looked after, while no chain is open: the divine power, the cooldowns on what is worth them, a second enemy held,
	 * a caster silenced, a player stunned, a stunned target rushed. The chains are the generic order's.
	 */
	static Move decideOffence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled || s.followedChainOpen)
			return null;
		boolean worth = worthABurst(s);
		if (s.dp >= BARRAGE_DP && worthTheBarrage(s) && ready.contains(Move.ANNIHILATION_BARRAGE))
			return Move.ANNIHILATION_BARRAGE;
		if (s.dp >= OVERDRIVE_DP && worth && !s.overdriveUp && ready.contains(Move.OVERDRIVE_TRIGGER))
			return Move.OVERDRIVE_TRIGGER;
		if (s.dp >= OVERDRIVE_DP && s.mp < POWER_FLUX_BELOW && !worth && ready.contains(Move.POWER_FLUX))
			return Move.POWER_FLUX;
		if (worth) {
			for (Move move : BURST_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.secondToControl && (s.inTeam || s.enemiesEngaged > 1)) {
			if (s.secondWithinShock && ready.contains(Move.ELECTRIC_SHOCK))
				return Move.ELECTRIC_SHOCK;
			if (ready.contains(Move.CONVULSION_BEAM))
				return Move.CONVULSION_BEAM;
		}
		if (s.targetCasts && ready.contains(Move.AETHERLOCK_BLADE))
			return Move.AETHERLOCK_BLADE;
		if (s.targetIsPlayer) {
			for (Move move : List.of(Move.DRILLBORE, Move.BACKLASH)) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.targetStunned && ready.contains(Move.STEAM_RUSH))
			return Move.STEAM_RUSH;
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

	private static boolean endToggles(Player bot, List<Move> toggles) {
		boolean ended = false;
		for (Move toggle : toggles) {
			if (BotSkillManager.endToggle(bot, toggle.group)) {
				log.debug("Aethertech {} ends {} ({}% mana)", bot.getName(), toggle, bot.getLifeStats().getMpPercentage());
				ended = true;
			}
		}
		return ended;
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
			case LOOSE -> reading.targets().loose();
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Aethertech {} casts {} at {} ({}% health, {}% mana)", bot.getName(), move, aimedAt.getName(), reading.situation().hp,
				reading.situation().mp);
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
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		s.inTeam = team != null;
		s.groupTank = BotRole.isGroupTank(bot);
		s.hp = bot.getLifeStats().getHpPercentage();
		s.mp = bot.getLifeStats().getMpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		s.assailed = BotGroupManager.enemyHitting(bot, ENEMY_RADIUS) != null;
		s.batteryUp = BotSkillManager.isUp(bot, Move.KINETIC_BATTERY.group);
		s.thrustersUp = BotSkillManager.isUp(bot, Move.STABILITY_THRUSTERS.group);
		s.bulwarkUp = BotSkillManager.isUp(bot, Move.KINETIC_BULWARK.group);
		s.frustrationUp = BotSkillManager.isUp(bot, Move.MOUNTING_FRUSTRATION.group);
		s.generatorUp = BotSkillManager.isUp(bot, Move.CHARGING_ACTIVATION.group);
		s.overdriveUp = BotSkillManager.isUp(bot, Move.OVERDRIVE_TRIGGER.group);
		s.followedChainOpen = FOLLOWED_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		s.shockChainOpen = BotSkillManager.isChainOpen(bot, SHOCK_CHAIN);
		s.rootedOrSnared = bot.getEffectController().isAbnormalSet(AbnormalState.ROOT) || bot.getEffectController().isAbnormalSet(AbnormalState.SNARE);
		List<Player> members = team == null ? List.of(bot) : team.getMembers();
		int[] engaged = { 0 }, near = { 0 }, looseNear = { 0 };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc) || !npc.equals(target) && members.stream().noneMatch(m -> npc.getAggroList().isHating(m)))
				return;
			engaged[0]++;
			if (PositionUtil.getDistance(bot, npc) > WAVE_REACH)
				return;
			near[0]++;
			Creature victim = npc.getAggroList().getTarget(AggroTarget.MOST_HATED);
			if (victim != null && !victim.equals(bot) && members.contains(victim)
				&& !npc.getEffectController().isInAnyAbnormalState(AbnormalState.CANT_ATTACK_STATE))
				looseNear[0]++;
		});
		s.enemiesEngaged = engaged[0];
		s.enemiesNear = near[0];
		s.looseNear = looseNear[0];
		Creature second = null, loose = null;
		if (target != null) {
			s.targetOnMe = bot.equals(target.getTarget());
			// a player keeps no aggro list for the reading above: one whose target is the bot is hitting it
			s.assailed |= s.targetOnMe && target instanceof Player;
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			NpcRating rating = target instanceof Npc npc ? npc.getObjectTemplate().getRating() : null;
			s.targetElite = rating != null && BardPlaybook.isEliteOrBoss(rating);
			s.targetBoss = rating == NpcRating.HERO || rating == NpcRating.LEGENDARY;
			s.targetStunned = target.getEffectController().isAbnormalSet(AbnormalState.STUN)
				|| target.getEffectController().isAbnormalSet(AbnormalState.STAGGER) || target.getEffectController().isAbnormalSet(AbnormalState.STUMBLE);
			s.targetWithinWhisper = PositionUtil.getDistance(bot, target) <= WHISPER_REACH;
			VisibleObject victim = target.getTarget();
			s.targetLoose = s.inTeam && victim != null && !victim.equals(bot) && members.contains(victim);
			second = SorcererPlaybook.secondToControl(bot, target);
			s.secondToControl = second != null;
			s.secondWithinShock = second != null && PositionUtil.getDistance(bot, second) <= SHOCK_REACH;
			if (s.inTeam && s.groupTank) {
				loose = BotGroupManager.enemyLooseOnAMate(bot, target, BotMarks.skulled(bot));
				s.looseWithinWhisper = loose != null && PositionUtil.getDistance(bot, loose) <= WHISPER_REACH;
			}
		}
		return new Reading(s, new Targets(target, second, loose));
	}
}
