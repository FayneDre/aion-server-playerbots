package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.Summon;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a spirit master plays. Written without a guide, as the bard's was: every rule comes from the skill data or is the module's own judgement of how the
 * class is played, and the plan says which ({@code docs/classes/plans/spirit-master-plan.md}).
 * <p>
 * A cloth caster whose damage is two: its curses and spells, and its spirit. The playbook decides which spirit is out and keeps it alive, gives it its
 * commands (which {@code BotServantAI} carries out, the bot having no client to), keeps the curses on the target and the defences up. The fillers and
 * the damage chains it leaves to the generic order, which carries an open chain on before anything else.
 */
final class SpiritMasterPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(SpiritMasterPlaybook.class);

	/** What a spirit master never casts: Spirit's Empowerment sends the spirit away for the master's spell power, which leaves it a cloth caster alone. */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("EL_SOULKING");
	/** The regular stigmas: Withering Gloom, Magic's Freedom, Cloaking Word. The module's judgement, not a guide's. */
	static final List<String> REGULAR_STIGMAS = List.of("EL_ENERVATIONCURSE", "EL_MAGICALBREAKDOWN", "EL_ESCAPE");
	/** The advanced ones: Infernal Pain, Healing Spirit, Infernal Blight, Armor Spirit. */
	static final List<String> ADVANCED_STIGMAS = List.of("EL_HELLPAIN", "EL_ELEMENTALCHARGE", "EL_HELLCURSE", "EL_ENCHANTARMOR");

	/** The chain the rules read, as the data names it: Spirit Disturbance opens Spirit Detonation Claw. */
	static final String DISTURBANCE_CHAIN = "MWE_CHAINN_1TH_1";

	/** The spirit's health under which it is mended: Replenish Element, then Healing Spirit, then Divine Spirit Armor for divine power. */
	static final int REPLENISH_BELOW = 60, HEALING_SPIRIT_BELOW = 40, DIVINE_ARMOR_BELOW = 25;
	/** The master's health under which a dead spirit is not called up again mid fight: four and a half seconds rooted is a death. */
	static final int RESUMMON_ABOVE = 40;
	/** The master's health under which the spirit is made to take the blows, and under which it gives its own health to the master. */
	static final int PROTECT_BELOW = 50, ABSORB_BELOW = 40;
	/** The spirit's health over which Spirit Absorption may take a tenth of it. */
	static final int ABSORB_SPIRIT_ABOVE = 50;
	/** Health under which Earthen Call is raised while something hits the master. */
	static final int EARTHEN_CALL_BELOW = 60;
	/** Health under which a lone spirit master leaves, and under which its spirit is spent in Command: Kamikaze. */
	static final int FLEE_BELOW = 25, KAMIKAZE_BELOW = 20;
	/** Health under which a lone spirit master frightens everything round it with Fear Shriek, and how many enemies within its reach that takes. */
	static final int SHRIEK_BELOW = 50, SHRIEK_ENEMIES = 2;
	static final float SHRIEK_REACH = 15f;
	/** Spirit Ruinous Offensive's reach, which is shorter than the class's. */
	static final float RUINOUS_REACH = 15f;
	/** What Divine Spirit Armor, Disenchant and the two great spirits each cost, and what Cursecloud costs. */
	static final int ABILITY_DP = 2000, CURSECLOUD_DP = 4000;
	/** How many enemies on a lone spirit master make Cursecloud worth its divine power. */
	static final int CURSECLOUD_ENEMIES = 3;
	/** How far a spirit master looks for what is hitting it: its own reach. */
	static final float ENEMY_RADIUS = 25f;

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** The bot's own spirit: the engine aims these at it whatever it is given, but the cast's checks are made against it. */
		SPIRIT,
		/** An enemy engaged and not held, which the controls are for. */
		SECOND
	}

	/** What the playbook may cast; claimed, kept from the generic order, unless it says otherwise. */
	enum Move {
		// the spirits: four that stay, two great ones for twenty minutes at 2000 divine power
		EARTH_SPIRIT("EL_SUMMON_EARTHELEMENTAL", Aim.SELF),
		FIRE_SPIRIT("EL_SUMMON_FIREELEMENTAL", Aim.SELF),
		WATER_SPIRIT("EL_SUMMON_WATERELEMENTAL", Aim.SELF),
		WIND_SPIRIT("EL_SUMMON_WINDELEMENTAL", Aim.SELF),
		TEMPEST_SPIRIT("EL_SUMMON_TEMPESTELEMENTAL", Aim.SELF),
		LAVA_SPIRIT("EL_SUMMON_MAGMAELEMENTAL", Aim.SELF),
		SUMMONING_ALACRITY("EL_ELEMENTALCALL", Aim.SELF),
		// the spirit kept alive and strong
		REPLENISH_ELEMENT("EL_PET_RECOVERHEALTH", Aim.SPIRIT),
		HEALING_SPIRIT("EL_ELEMENTALCHARGE", Aim.SPIRIT),
		DIVINE_SPIRIT_ARMOR("EL_ETHERINFUSION", Aim.SPIRIT),
		ARMOR_SPIRIT("EL_ENCHANTARMOR", Aim.SPIRIT),
		ELEMENTAL_SPIRIT_ARMOR("EL_ELEMENTALSHIELD", Aim.SPIRIT),
		SYMPATHETIC_MIND("EL_ELEMENTALBOOST", Aim.SPIRIT),
		/** Heals the master from the spirit's health. */
		SPIRIT_ABSORPTION("EL_UNSUMMON", Aim.SPIRIT),
		// the commands: each queues one of the spirit's skills, which BotServantAI carries out
		SPIRIT_DISTURBANCE("EL_PET_INTERUPT", Aim.TARGET),
		/** Follows Spirit Disturbance, within three seconds. */
		DETONATION_CLAW("EL_ORDER_ASSAIL", Aim.TARGET),
		SPIRIT_EROSION("EL_ORDER_DECAYING", Aim.TARGET),
		WRATH_POSITION("EL_PET_ELEMENTALWRAITH", Aim.SPIRIT),
		BURN_TO_ASHES("EL_STIGMA_ORDER_ETHERERUPTION", Aim.SPIRIT),
		RUINOUS_OFFENSIVE("EL_STIGMA_ORDER_DESTRUCTIMPACT", Aim.TARGET),
		WALL_OF_PROTECTION("EL_ORDER_ELEMENTALFIELD", Aim.SPIRIT),
		BODYGUARD("EL_ORDER_PROTECT", Aim.SELF),
		ABSORB_WOUNDS("EL_SOULPROTECT", Aim.SELF),
		SPIRIT_PRESERVE("EL_ETERNALSHIELD", Aim.SELF),
		/** The spirit taunts what is round it. */
		SPIRIT_PIQUE("EL_PET_WIDETAUNT", Aim.SPIRIT),
		/** The spirit spent in one blast: released by the order itself. */
		KAMIKAZE("EL_ORDER_EXPLODE", Aim.SPIRIT),
		// curses, kept on the target
		WITHERING_GLOOM("EL_ENERVATIONCURSE", Aim.TARGET),
		INFERNAL_PAIN("EL_HELLPAIN", Aim.TARGET),
		INFERNAL_BLIGHT("EL_HELLCURSE", Aim.TARGET),
		MAGICS_FREEDOM("EL_MAGICALBREAKDOWN", Aim.TARGET),
		SHACKLE_OF_VULNERABILITY("EL_ENFEEBLEMENT", Aim.TARGET),
		/** Charged: never begun while something hits the master. */
		STONE_SCOUR("EL_CURSEDBREATH", Aim.TARGET),
		/** Flight time down: on a flying player only. */
		WING_ROOT("EL_DECAYINGWING", Aim.TARGET),
		// divine power
		CURSECLOUD("EL_PLAGUESWAM", Aim.TARGET),
		DISENCHANT("EL_DISENCHANTMENT", Aim.TARGET),
		// defence
		STONE_SKIN("MA_STONESKIN", Aim.SELF),
		EARTHEN_CALL("EL_EARTHGUARDIANCE", Aim.SELF),
		CONTRACT_OF_RESISTANCE("EL_RESISTMAGIC", Aim.SELF),
		/** The master and the spirit hidden: cast in the flight only. */
		CLOAKING_WORD("EL_ESCAPE", Aim.SELF),
		/** Swaps hate between the master and its spirit. */
		EMNITY_SWAP("EL_SYMPATHETICSWITCH", Aim.TARGET),
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		// control
		FEAR("EL_FEAR", Aim.SECOND),
		FEAR_POPPY("EL_FEAR_PORGUSS", Aim.SECOND),
		FEAR_GINSENG("EL_FEAR_SAAM", Aim.SECOND),
		BODY_ROOT("EL_BIND", Aim.SECOND),
		/** Frightens what is within 15 m: a lone master's way out. */
		FEAR_SHRIEK("EL_TERRORSPIRIT", Aim.SELF),
		SIGIL_OF_SILENCE("EL_SILENCE", Aim.TARGET);

		final String group;
		final Aim aim;

		Move(String group, Aim aim) {
			this.group = group;
			this.aim = aim;
		}
	}

	/** The four spirits that stay, for {@link #servantOrder}; the two great ones are the playbook's to call up in a fight. */
	static final List<Move> LASTING_SPIRITS = List.of(Move.EARTH_SPIRIT, Move.FIRE_SPIRIT, Move.WATER_SPIRIT, Move.WIND_SPIRIT);
	/** The controls on a second enemy: fears alone, held in place in a group (the cast rule refuses a fear there anyway). */
	static final List<Move> FEARS = List.of(Move.FEAR, Move.FEAR_POPPY, Move.FEAR_GINSENG);
	/** The commands that are damage, cast on cooldown while the spirit is out, strongest first. */
	static final List<Move> DAMAGE_COMMANDS = List.of(Move.SPIRIT_EROSION, Move.WRATH_POSITION, Move.BURN_TO_ASHES, Move.SPIRIT_DISTURBANCE);

	/** Everything the decisions look at, filled field by field: a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		/** The spirits the bot has learnt, which is not every one before level 19. */
		Set<Move> known = EnumSet.copyOf(LASTING_SPIRITS);
		int hp = 100, mp = 100, dp;
		boolean inTeam, inFight, controlled, tankInGroup;
		/** The spirit: out at all, its health, whether it is one of the two great ones, and which of the lasting ones it is (null for a great one). */
		boolean spiritOut = true;
		int spiritHp = 100;
		boolean greatSpirit;
		Move spirit;
		/** What is on the spirit, and on the master. */
		boolean armorSpiritOn = true, elementalArmorOn = true, sympatheticOn = true, alacrityUp, stoneSkinUp = true, earthenCallUp, contractUp;
		boolean disturbanceChainOpen;
		/** Whether something is hitting the master, which a charged spell does not survive. */
		boolean assailed;
		/** The target: on the master, a caster, casting, a player, flying, an elite or a boss, within Ruinous Offensive's reach. */
		boolean targetOnMe, targetCasts, targetIsPlayer, targetFlying, targetElite, targetWithin15m = true;
		/** The curses already on the target. */
		boolean gloomOn, painOn, blightOn, freedomOn, shackleOn;
		boolean secondToControl;
		int enemiesEngaged, enemiesWithin15m;

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
			s.known = EnumSet.noneOf(Move.class);
			s.known.addAll(known);
			s.hp = hp;
			s.mp = mp;
			s.dp = dp;
			s.inTeam = inTeam;
			s.inFight = inFight;
			s.controlled = controlled;
			s.tankInGroup = tankInGroup;
			s.spiritOut = spiritOut;
			s.spiritHp = spiritHp;
			s.greatSpirit = greatSpirit;
			s.spirit = spirit;
			s.armorSpiritOn = armorSpiritOn;
			s.elementalArmorOn = elementalArmorOn;
			s.sympatheticOn = sympatheticOn;
			s.alacrityUp = alacrityUp;
			s.stoneSkinUp = stoneSkinUp;
			s.earthenCallUp = earthenCallUp;
			s.contractUp = contractUp;
			s.disturbanceChainOpen = disturbanceChainOpen;
			s.assailed = assailed;
			s.targetOnMe = targetOnMe;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetFlying = targetFlying;
			s.targetElite = targetElite;
			s.targetWithin15m = targetWithin15m;
			s.gloomOn = gloomOn;
			s.painOn = painOn;
			s.blightOn = blightOn;
			s.freedomOn = freedomOn;
			s.shackleOn = shackleOn;
			s.secondToControl = secondToControl;
			s.enemiesEngaged = enemiesEngaged;
			s.enemiesWithin15m = enemiesWithin15m;
			return s;
		}
	}

	/** The creatures a move may be aimed at, which the situation only says exist. Kept apart so the decisions can be made without a world. */
	record Targets(Creature target, Creature second, Summon spirit) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the spirit master alone decides on this skill. Takes the template so that every template in the game can be put through it. */
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
	public boolean swings(Player bot) {
		return false; // an orb is not a weapon: with nothing to cast the master waits, and its spirit fights
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && bot.getLifeStats().getHpPercentage() < FLEE_BELOW;
	}

	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.CLOAKING_WORD.group))
			log.debug("Spirit master {} casts {} as it flees", bot.getName(), Move.CLOAKING_WORD);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public List<String> servantOrder(Player bot) {
		return spiritOrder(bot.getCurrentTeam() != null, hasTank(bot)).stream().map(move -> move.group).toList();
	}

	/**
	 * @return true if the bot's group has a tank, near or not, alive or not. The whole group and not the mates in reach: a spirit chosen by who stands
	 *         near would be sent away and called up again each time the tank walked off or died.
	 */
	private static boolean hasTank(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return team != null && team.getMembers().stream().anyMatch(member -> !member.equals(bot) && BotRole.of(member) == BotRole.TANK);
	}

	/**
	 * @return The lasting spirits, the one wanted first: Earth alone or in a group without a tank, since it holds the enemy while the master casts; Fire
	 *         with a tank to hold it, for its damage. Wind last: never chosen while another is known. Water, whose slows are for players, after Fire.
	 */
	static List<Move> spiritOrder(boolean inTeam, boolean tankInGroup) {
		return inTeam && tankInGroup ? List.of(Move.FIRE_SPIRIT, Move.EARTH_SPIRIT, Move.WATER_SPIRIT, Move.WIND_SPIRIT)
			: List.of(Move.EARTH_SPIRIT, Move.FIRE_SPIRIT, Move.WATER_SPIRIT, Move.WIND_SPIRIT);
	}

	/** @return The spirit this bot would call up, the first of the order it knows, or null when it knows none yet. */
	static Move wantedSpirit(Situation s) {
		for (Move spirit : spiritOrder(s.inTeam, s.tankInGroup)) {
			if (s.known.contains(spirit))
				return spirit;
		}
		return null;
	}

	/** @return true if the spirit out is a lasting one other than the one wanted, out of a fight: sent away so that the right one is called up. */
	static boolean wrongSpiritOut(Situation s) {
		return s.spiritOut && !s.greatSpirit && !s.inFight && s.spirit != null && wantedSpirit(s) != null && s.spirit != wantedSpirit(s);
	}

	@Override
	public boolean prepare(Player bot) {
		Reading reading = readingOf(bot, null, false);
		return performFirst(bot, reading, SpiritMasterPlaybook::decideUpkeep);
	}

	/** Between fights: the spirit changed when the group asks for another one, then mended. */
	@Override
	public boolean tend(Player bot) {
		Reading reading = readingOf(bot, null, false);
		if (wrongSpiritOut(reading.situation()) && !aimsAtAnEnemy(bot) && !aimsAtAnEnemy(bot.getSummon()) && BotSkillManager.dismissServant(bot)) {
			log.debug("Spirit master {} sends away its {} for its {}", bot.getName(), reading.situation().spirit, wantedSpirit(reading.situation()));
			return true;
		}
		return performFirst(bot, reading, SpiritMasterPlaybook::decideSpirit);
	}

	/** @return true if the creature has a living enemy in its sights: a fight about to start, which the leader's target can be before anything hits. */
	private static boolean aimsAtAnEnemy(Creature creature) {
		return creature != null && creature.getTarget() instanceof Creature target && !target.isDead() && creature.isEnemy(target);
	}

	@Override
	public boolean heal(Player bot) {
		return false; // the master's own heals are the generic order's: Herb Treatment and the potions
	}

	/** Its defences, then its spirit, then the fight: without its spirit a spirit master has no damage worth the name. */
	@Override
	public boolean act(Player bot, Creature target) {
		Reading reading = readingOf(bot, target, true);
		return performFirst(bot, reading, SpiritMasterPlaybook::decideDefence) || performFirst(bot, reading, SpiritMasterPlaybook::decideSpirit)
			|| performFirst(bot, reading, SpiritMasterPlaybook::decideUpkeep) || performFirst(bot, reading, SpiritMasterPlaybook::decideOffence);
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/**
	 * The spirit: called up again when it died in a fight (out of one the idle tick calls it up), the great one against an elite when there is divine power
	 * for it; then kept alive.
	 */
	static Move decideSpirit(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return null;
		if (!s.spiritOut) {
			if (!s.inFight || s.hp <= RESUMMON_ABOVE)
				return null;
			if (!s.alacrityUp && ready.contains(Move.SUMMONING_ALACRITY))
				return Move.SUMMONING_ALACRITY;
			if (s.targetElite && s.dp >= ABILITY_DP) {
				if (ready.contains(Move.TEMPEST_SPIRIT))
					return Move.TEMPEST_SPIRIT;
				if (ready.contains(Move.LAVA_SPIRIT))
					return Move.LAVA_SPIRIT;
			}
			if (s.targetIsPlayer && s.known.contains(Move.WATER_SPIRIT) && ready.contains(Move.WATER_SPIRIT))
				return Move.WATER_SPIRIT;
			Move wanted = wantedSpirit(s);
			if (wanted != null && ready.contains(wanted))
				return wanted;
			for (Move spirit : LASTING_SPIRITS) {
				if (s.known.contains(spirit) && ready.contains(spirit))
					return spirit;
			}
			return null;
		}
		if (s.inFight && s.spiritHp < DIVINE_ARMOR_BELOW && s.dp >= ABILITY_DP && ready.contains(Move.DIVINE_SPIRIT_ARMOR))
			return Move.DIVINE_SPIRIT_ARMOR;
		if (s.spiritHp < HEALING_SPIRIT_BELOW && ready.contains(Move.HEALING_SPIRIT))
			return Move.HEALING_SPIRIT;
		if (s.spiritHp < REPLENISH_BELOW && ready.contains(Move.REPLENISH_ELEMENT))
			return Move.REPLENISH_ELEMENT;
		return null;
	}

	/** Before a fight, and renewed in one: Stone Skin on the master, the spirit's armours and Sympathetic Mind on the spirit. */
	static Move decideUpkeep(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return null;
		if (!s.stoneSkinUp && ready.contains(Move.STONE_SKIN))
			return Move.STONE_SKIN;
		if (!s.spiritOut)
			return null;
		if (!s.armorSpiritOn && ready.contains(Move.ARMOR_SPIRIT))
			return Move.ARMOR_SPIRIT;
		if (!s.elementalArmorOn && ready.contains(Move.ELEMENTAL_SPIRIT_ARMOR))
			return Move.ELEMENTAL_SPIRIT_ARMOR;
		if (!s.sympatheticOn && ready.contains(Move.SYMPATHETIC_MIND))
			return Move.SYMPATHETIC_MIND;
		return null;
	}

	/** What the master does about being held, hit and nearly dead: asked first in a fight. */
	static Move decideDefence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.spiritOut) {
			// the spirit is the master's tank: what turned on the master is sent back to it
			if (s.targetOnMe && ready.contains(Move.EMNITY_SWAP))
				return Move.EMNITY_SWAP;
			if (s.targetOnMe && !s.inTeam && ready.contains(Move.SPIRIT_PIQUE))
				return Move.SPIRIT_PIQUE;
			if (s.hp < PROTECT_BELOW && s.assailed) {
				for (Move move : List.of(Move.ABSORB_WOUNDS, Move.BODYGUARD, Move.SPIRIT_PRESERVE, Move.WALL_OF_PROTECTION)) {
					if (ready.contains(move))
						return move;
				}
			}
			if (s.hp < ABSORB_BELOW && s.spiritHp > ABSORB_SPIRIT_ABOVE && ready.contains(Move.SPIRIT_ABSORPTION))
				return Move.SPIRIT_ABSORPTION;
			if (s.hp < KAMIKAZE_BELOW && !s.greatSpirit && s.assailed && ready.contains(Move.KAMIKAZE))
				return Move.KAMIKAZE;
		}
		if (s.hp < EARTHEN_CALL_BELOW && s.assailed && !s.earthenCallUp && ready.contains(Move.EARTHEN_CALL))
			return Move.EARTHEN_CALL;
		if (s.targetCasts && s.targetOnMe && !s.contractUp && ready.contains(Move.CONTRACT_OF_RESISTANCE))
			return Move.CONTRACT_OF_RESISTANCE;
		if (!s.inTeam && s.hp < SHRIEK_BELOW && s.enemiesWithin15m >= SHRIEK_ENEMIES && ready.contains(Move.FEAR_SHRIEK))
			return Move.FEAR_SHRIEK;
		return null;
	}

	/**
	 * The fight once the master and its spirit are looked after: the divine power, a second enemy held, a caster silenced, the spirit's commands, the
	 * curses kept on the target, then the charged Stone Scour while nothing hits the master. The fillers and the damage chains are the generic order's,
	 * after this answers null.
	 */
	static Move decideOffence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return null;
		if (!s.inTeam && s.enemiesEngaged >= CURSECLOUD_ENEMIES && s.dp >= CURSECLOUD_DP && ready.contains(Move.CURSECLOUD))
			return Move.CURSECLOUD;
		if (s.targetIsPlayer && s.dp >= ABILITY_DP && ready.contains(Move.DISENCHANT))
			return Move.DISENCHANT;
		if (s.secondToControl && (s.inTeam || s.enemiesEngaged > 1)) {
			if (!s.inTeam) {
				for (Move fear : FEARS) {
					if (ready.contains(fear))
						return fear;
				}
			}
			if (ready.contains(Move.BODY_ROOT))
				return Move.BODY_ROOT;
		}
		if (s.targetCasts && ready.contains(Move.SIGIL_OF_SILENCE))
			return Move.SIGIL_OF_SILENCE;
		if (s.spiritOut) {
			if (s.disturbanceChainOpen && ready.contains(Move.DETONATION_CLAW))
				return Move.DETONATION_CLAW;
			if (s.targetWithin15m && ready.contains(Move.RUINOUS_OFFENSIVE))
				return Move.RUINOUS_OFFENSIVE;
			for (Move command : DAMAGE_COMMANDS) {
				if (ready.contains(command))
					return command;
			}
		}
		if (!s.gloomOn && ready.contains(Move.WITHERING_GLOOM))
			return Move.WITHERING_GLOOM;
		if (!s.painOn && ready.contains(Move.INFERNAL_PAIN))
			return Move.INFERNAL_PAIN;
		if (!s.blightOn && ready.contains(Move.INFERNAL_BLIGHT))
			return Move.INFERNAL_BLIGHT;
		if (!s.freedomOn && (s.targetElite || s.targetIsPlayer) && ready.contains(Move.MAGICS_FREEDOM))
			return Move.MAGICS_FREEDOM;
		if (!s.shackleOn && s.targetIsPlayer && ready.contains(Move.SHACKLE_OF_VULNERABILITY))
			return Move.SHACKLE_OF_VULNERABILITY;
		if (s.targetFlying && s.targetIsPlayer && ready.contains(Move.WING_ROOT))
			return Move.WING_ROOT;
		if (!s.assailed && ready.contains(Move.STONE_SCOUR))
			return Move.STONE_SCOUR;
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
		Targets targets = reading.targets();
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> targets.target();
			case SPIRIT -> targets.spirit();
			case SECOND -> targets.second();
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Spirit master {} casts {} at {} ({}% health, spirit {}%)", bot.getName(), move, aimedAt.getName(), reading.situation().hp,
				reading.situation().spiritOut ? reading.situation().spiritHp : 0);
		return cast;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The situation
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	private static Reading readingOf(Player bot, Creature target, boolean fighting) {
		Situation s = new Situation();
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				s.ready.add(move);
		}
		s.known = EnumSet.noneOf(Move.class);
		for (Move spirit : LASTING_SPIRITS) {
			if (BotSkillManager.knows(bot, spirit.group))
				s.known.add(spirit);
		}
		s.inTeam = bot.getCurrentTeam() != null;
		s.inFight = fighting || bot.getAi() != null && bot.getAi().isInState(AIState.FIGHT)
			|| s.inTeam && BotGroupManager.enemiesFightingTheGroupWithin(bot, ENEMY_RADIUS) > 0;
		s.hp = bot.getLifeStats().getHpPercentage();
		s.mp = bot.getLifeStats().getMpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		s.tankInGroup = hasTank(bot);
		s.alacrityUp = BotSkillManager.isUp(bot, Move.SUMMONING_ALACRITY.group);
		s.stoneSkinUp = BotSkillManager.isUp(bot, Move.STONE_SKIN.group);
		s.earthenCallUp = BotSkillManager.isUp(bot, Move.EARTHEN_CALL.group);
		s.contractUp = BotSkillManager.isUp(bot, Move.CONTRACT_OF_RESISTANCE.group);
		s.disturbanceChainOpen = BotSkillManager.isChainOpen(bot, DISTURBANCE_CHAIN);
		s.assailed = BotGroupManager.enemyHitting(bot, ENEMY_RADIUS) != null;

		Summon spirit = bot.getSummon();
		s.spiritOut = spirit != null && spirit.isSpawned() && !spirit.isDead() && !spirit.isBeingReleased();
		if (!s.spiritOut) {
			spirit = null;
		} else {
			s.spiritHp = spirit.getLifeStats().getHpPercentage();
			s.spirit = spiritOf(spirit);
			s.greatSpirit = s.spirit == Move.TEMPEST_SPIRIT || s.spirit == Move.LAVA_SPIRIT;
			if (s.greatSpirit)
				s.spirit = null;
			s.armorSpiritOn = BotSkillManager.isCovered(bot, spirit, Move.ARMOR_SPIRIT.group);
			s.elementalArmorOn = BotSkillManager.isCovered(bot, spirit, Move.ELEMENTAL_SPIRIT_ARMOR.group);
			s.sympatheticOn = BotSkillManager.isCovered(bot, spirit, Move.SYMPATHETIC_MIND.group);
		}

		Creature second = null;
		if (target != null) {
			s.targetOnMe = bot.equals(target.getTarget());
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			s.targetFlying = target.isFlying();
			s.targetElite = target instanceof Npc npc && BardPlaybook.isEliteOrBoss(npc.getObjectTemplate().getRating());
			s.targetWithin15m = PositionUtil.getDistance(bot, target) <= RUINOUS_REACH;
			s.gloomOn = BotSkillManager.isOnTarget(bot, target, Move.WITHERING_GLOOM.group);
			s.painOn = BotSkillManager.isOnTarget(bot, target, Move.INFERNAL_PAIN.group);
			s.blightOn = BotSkillManager.isOnTarget(bot, target, Move.INFERNAL_BLIGHT.group);
			s.freedomOn = BotSkillManager.isOnTarget(bot, target, Move.MAGICS_FREEDOM.group);
			s.shackleOn = BotSkillManager.isOnTarget(bot, target, Move.SHACKLE_OF_VULNERABILITY.group);
			second = SorcererPlaybook.secondToControl(bot, target);
			s.secondToControl = second != null;
			List<Npc> engaged = new ArrayList<>();
			bot.getKnownList().forEachNpc(npc -> {
				if (!npc.isDead() && bot.isEnemy(npc) && (npc.getAggroList().isHating(bot) || npc.equals(target)))
					engaged.add(npc);
			});
			s.enemiesEngaged = engaged.size();
			s.enemiesWithin15m = (int) engaged.stream().filter(npc -> PositionUtil.getDistance(bot, npc) <= SHRIEK_REACH).count();
		}
		return new Reading(s, new Targets(target, second, spirit));
	}

	/** @return The move that called this spirit up, from the skill the engine says it came from, or null for one the playbook does not name. */
	private static Move spiritOf(Summon spirit) {
		SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(spirit.getSummonedBySkillId());
		if (template == null || template.getGroup() == null)
			return null;
		for (Move move : Move.values()) {
			if (move.group.equals(template.getGroup()))
				return move;
		}
		return null;
	}
}
