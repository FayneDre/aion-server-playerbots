package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.team.group.PlayerGroup;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a sorcerer plays, after the guide's "Sorcier 101". The plan, milestone by milestone, is {@code docs/classes/history/sorcerer-plan.md}.
 * <p>
 * A ranged caster that never swings and never stops a cast it has begun: its upkeep (Stone Skin, a robe, the wisdom), its mana (Gain Mana, the drains,
 * resting to full), its defences, a damage order after the guide's sequence, ground spells at the target's feet, control of a second enemy, holding its
 * fire in a full group once the monster has turned on it, and a flight alone. The chains the generic order carries on (the fire chain's Blaze and Ember)
 * are left to it while open, as for the gladiator.
 */
final class SorcererPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(SorcererPlaybook.class);

	/**
	 * Skills the guide says never to use, by group: Blind Leap and Winter Binding outside the flight, Boon of Clairvoyance, Tranquilizing Cloud, Vaizel's
	 * Wisdom, Rift Jump, Graspbreaker, Thick Shield (the guide's Repeated Protective Shield), Mounting Explosion (its Delayed Heatwave), and Summon Rift
	 * outside {@code //bot gate}. Claimed, so the generic order leaves them alone; the playbook casts the first two in its flight.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("WI_DIMENSIONDOOR", "WI_FROZENFIELD", "WI_MINDSEYE", "WI_SLEEPINGCLOUD", "WI_ARCANEBOOST",
		"WI_DIEMSIONJUMP", "WI_CRYSTALMIRROR", "WI_RAPIDSHIELD", "WI_DELAYEDSTRIKE", "WI_ILLUSIONGATE");
	/** Summon Rift, the gate to the capital that {@code //bot gate} asks for. */
	static final String GATE_GROUP = "WI_ILLUSIONGATE";
	/** Mana under which Gain Mana is cast: the guide's 50 %. */
	static final int GAIN_MANA_BELOW_PERCENT = 50;
	/** Mana under which Refracting Shard starts draining, and the mana it drains up to: the guide's 30 % and 60 %. */
	static final int DRAIN_FROM_PERCENT = 30, DRAIN_UP_TO_PERCENT = 60;
	/** Mana over which Glacial Shard may be cast: the guide's 30 %. */
	static final int GLACIAL_SHARD_ABOVE_PERCENT = 30;
	/** Mana under which a sorcerer out of a fight sits down, and rests until it is full: the guide's 50 %. */
	static final int REST_BELOW_PERCENT = 50;
	/** Health under which Boon of Iron-Clad stands in for a Stone Skin that is gone and cooling down: the guide's 50 %. */
	static final int IRON_CLAD_BELOW_PERCENT = 50;
	/** Health under which a lone sorcerer leaves: the guide's 30 %. */
	static final int FLEE_BELOW_PERCENT = 30;
	/** How near the main target must be for Boon of Peace, an area of 5 m round the sorcerer, to reach it. */
	static final float BOON_OF_PEACE_REACH = 5f;
	/** How near an enemy must be for Freezing Wind, which reaches 3 m. */
	static final float FREEZING_WIND_REACH = 3f;
	/** How near an enemy must be for Meteorite, an area of 7 m round the sorcerer, to be worth it. */
	static final float METEORITE_REACH = 7f;
	/** How near an enemy hitting the sorcerer must be for it to be hitting with a weapon, which Illusion answers. */
	static final float MELEE_REACH = 5f;
	/** How far a second enemy may be to be controlled: the 25 m the controls reach. */
	static final float CONTROL_REACH = 25f;
	/** How long the main target may be on the sorcerer, in a full group with a tank, before it stops attacking: the guide's 10 s. */
	static final long HOLD_FIRE_AFTER_MILLIS = 10_000;
	/** How many Aether Flames before Magic Fist ends the hold: the guide's two. */
	static final int AETHER_FLAMES = 2;
	/** What Empyrean Fire costs, and what each of the Lumiel pair costs. */
	static final int ABILITY_DP = 2000, LUMIEL_DP = 4000;
	/** The group size the hold-fire rule is for: a full group. */
	static final int FULL_GROUP = 6;
	/** The chain categories the generic order carries on: the fire chain after Flame Bolt or Flame Harpoon (Blaze), then Ember. */
	static final List<String> FOLLOWED_CHAINS = List.of("M_CHAINA_1TH_1", "M_CHAINA_2TH_1");
	/** Chain B after Ice Chain, which Frozen Shock asks for. */
	static final String ICE_CHAIN = "M_CHAINB_1TH_1";
	/** Chain F after Refracting Shard, open one time in four, which Soul Absorption asks for. */
	static final String SHARD_CHAIN = "M_CHAINF_1TH_1";
	/** The multicasts: Wind Spear's chain (three casts) and Meteorite's (four), each recast while it is open. */
	static final String SPEAR_CHAIN = "M_CHAIND_1TH_1", METEORITE_CHAIN = "M_CHAINE_1TH_1";
	/** The chain Remove Shock opens, which Curse of Old Roots asks for. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";
	/** The stigmas the guide lists (section IV), the three regular sockets first. */
	static final List<String> REGULAR_STIGMAS = List.of("WI_STIGMA_ICELANCE", "WI_CYCLONESTRIKE", "WI_STORMSHOCK");
	static final List<String> ADVANCED_STIGMAS = List.of("WI_FLAMESTRIKE", "WI_ROCKFALL", "WI_ZEROPOINT");

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** A second enemy, engaged and not held, which the controls are for. */
		SECOND,
		/** Nobody: the move is holding fire, which casts nothing. */
		NONE
	}

	/** What the playbook may cast; claimed, kept from the generic order, unless it says otherwise. */
	enum Move {
		// upkeep
		STONE_SKIN("MA_STONESKIN", Aim.SELF),
		ROBE_OF_FLAME("WI_FLAMESEAL", Aim.SELF),
		ROBE_OF_COLD("WI_FROZENSEAL", Aim.SELF),
		/** Zikel's Wisdom in the guide. */
		EMPYREAN_WISDOM("WI_ARCANEPOWER", Aim.SELF),
		MAGIC_ASSIST("WI_MANABOOST", Aim.SELF),
		SUPPLICATION_OF_FOCUS("WI_SOULGAIN", Aim.SELF),
		// mana
		GAIN_MANA("WI_GAINMANA", Aim.SELF),
		REFRACTING_SHARD("WI_MAGICALCHAIN", Aim.TARGET),
		SOUL_ABSORPTION("WI_SOULEATER", Aim.TARGET),
		// defence
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		CURSE_OF_OLD_ROOTS("WI_CURSEDCORRUPTTREE", Aim.SECOND),
		ILLUSION("WI_ILLUSIONDANCE", Aim.SELF),
		BOON_OF_IRON_CLAD("WI_PHYSICALWALL", Aim.SELF),
		/** An area round the sorcerer, but the data asks for an npc as the target ({@code <target value="NPC"/>}): aimed at the target, not at itself. */
		BOON_OF_PEACE("WI_ABSENCESOUL", Aim.TARGET),
		FREEZING_WIND("WI_HYDROIMPACT", Aim.TARGET),
		// the flight
		WINTER_BINDING("WI_FROZENFIELD", Aim.SELF),
		BLIND_LEAP("WI_DIMENSIONDOOR", Aim.SELF),
		// control
		/** Induce Deep Sleep in the guide. */
		SOMNOLENCE("WI_DEEPSLEEP", Aim.SECOND),
		/** Curse: Tree in the guide. */
		CURSE_OF_ROOTS("WI_CURSEDTREE", Aim.SECOND),
		/** Restraint in the guide. */
		ROOT("MA_ROOT", Aim.SECOND),
		SOUL_FREEZE("WI_SOULFREEZE", Aim.TARGET),
		// damage
		WIND_SPEAR("WI_WINDSPEAR", Aim.TARGET),
		METEORITE("WI_RAISINGSTORM", Aim.SELF),
		DELAYED_BLAST("WI_DELAYEDEXPLOSION", Aim.TARGET),
		ICE_CHAIN("MA_ICEGRAB", Aim.TARGET),
		FROZEN_SHOCK("MA_FROZENSHOCK", Aim.TARGET),
		FLAME_CAGE("WI_FLAMECAGE", Aim.TARGET),
		EROSION("MA_GRAVITYCAGE", Aim.TARGET),
		AETHERS_HOLD("WI_WIZARDRYHOOK", Aim.TARGET),
		AETHER_FLAME("WI_FIRESHOOTER", Aim.TARGET),
		MAGIC_FIST("WI_MAGICFIST", Aim.TARGET),
		/** Cyclone Strike in the guide. */
		STORM_STRIKE("WI_CYCLONESTRIKE", Aim.TARGET),
		ARCANE_THUNDERBOLT("WI_STORMSHOCK", Aim.TARGET),
		/** Fire of Magic Power in the guide. */
		AETHERFLAME("WI_MAGICALFLAME", Aim.TARGET),
		FLAME_FUSION("WI_MELTDOWN", Aim.TARGET),
		FLAME_SPRAY("WI_FLAMESTRIKE", Aim.TARGET),
		/** Big Magma Eruption in the guide, an area of 10 m on the target. */
		MAGMA_BURST("WI_STIGMA_VOLCANICFLAME", Aim.TARGET),
		/** An area of 5 m laid on the ground at the target's feet. */
		FLAMING_METEOR("WI_METEOR", Aim.TARGET),
		/** Frost in the guide. */
		FREEZE("WI_FROZENCORE", Aim.TARGET),
		SUMMON_ROCK("WI_ROCKFALL", Aim.TARGET),
		GLACIAL_SHARD("WI_ZEROPOINT", Aim.TARGET),
		STAMINA_ABSORPTION("WI_VITALITYDRAIN", Aim.TARGET),
		ICE_HARPOON("WI_STIGMA_ICELANCE", Aim.TARGET),
		/** The fire chain's opener, also the generic order's filler. */
		FLAME_HARPOON("WI_BURNINGSOUL", Aim.TARGET, false),
		// divine power
		/** Aetheric Spell in the guide. */
		EMPYREAN_FIRE("MA_PANDEMONIUMFOCUS", Aim.TARGET),
		/** Lumiel's Lament in the guide: against players only. */
		SHADOWBURST("WI_ABYSALSTALKER", Aim.TARGET),
		/** Lumiel's Wrath in the guide: against players only. */
		EMPYREAN_WRATH("WI_ABYSALSLAYER", Aim.TARGET),
		/** Nothing at all: a sorcerer that has drawn the monster in a full group stops attacking until the tank has it back. */
		HOLD_FIRE(null, Aim.NONE);

		final String group;
		final Aim aim;
		final boolean claimed;

		Move(String group, Aim aim) {
			this(group, aim, true);
		}

		Move(String group, Aim aim, boolean claimed) {
			this.group = group;
			this.aim = aim;
			this.claimed = claimed;
		}
	}

	/** The damage moves after the upkeep, the controls and the openings, in the order of the guide's sequence at 51 and its long cooldowns. */
	static final List<Move> DAMAGE_ORDER = List.of(Move.STORM_STRIKE, Move.ARCANE_THUNDERBOLT, Move.AETHERFLAME, Move.AETHERS_HOLD, Move.FLAME_FUSION,
		Move.FLAME_SPRAY, Move.MAGMA_BURST, Move.FLAMING_METEOR, Move.WIND_SPEAR, Move.FREEZE, Move.SUMMON_ROCK, Move.GLACIAL_SHARD, Move.STAMINA_ABSORPTION,
		Move.METEORITE, Move.ICE_HARPOON, Move.FLAME_HARPOON);
	/** The controls, best first: the guide's "Induce Deep Sleep > Curse: Tree > Restraint". */
	static final List<Move> CONTROL_ORDER = List.of(Move.SOMNOLENCE, Move.CURSE_OF_ROOTS, Move.ROOT);

	/** Bots draining their mana back with Refracting Shard, from under 30 % until 60 %. */
	private static final Set<Integer> draining = ConcurrentHashMap.newKeySet();
	/** How many Aether Flames each bot has put on its target's hold so far. */
	private static final Map<Integer, Integer> aetherFlames = new ConcurrentHashMap<>();
	/** Since when each bot's target has been on it, for the hold-fire rule. */
	private static final Map<Integer, Long> aimedSince = new ConcurrentHashMap<>();

	/** Everything {@link #decide} looks at, filled field by field: there are too many for a constructor to read well, and a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		int hp = 100, mp = 100, dp;
		boolean inTeam, controlled, shockChainOpen;
		boolean stoneSkinUp = true, robeUp = true, wisdomUp = true, assistUp = true, focusUp = true, illusionUp;
		boolean draining, hitInMelee;
		/** The target: none, near, on the sorcerer, held in the air, carrying the damage over time or Flame Fusion's mark, a caster, a player. */
		boolean hasTarget = true, targetWithin3m, targetWithin5m, targetOnMe, targetAerial, dotOnTarget, fusionOnTarget, targetCasts, targetIsPlayer;
		/** Whether the target has not turned on the sorcerer yet, which is when the solo openers go. */
		boolean targetIdle;
		/** Chains: the fire chain the generic order carries on, chain B, chain F, the two multicasts. */
		boolean followedChainOpen, iceChainOpen, shardChainOpen, spearChainOpen, meteoriteChainOpen;
		int aetherFlamesCast;
		boolean enemyWithin7m, secondToControl, severalEnemies, holdFire;

		Situation ready(Move... moves) {
			ready.addAll(List.of(moves));
			return this;
		}

		Situation readyAll() {
			ready = EnumSet.allOf(Move.class);
			return this;
		}

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
			s.shockChainOpen = shockChainOpen;
			s.stoneSkinUp = stoneSkinUp;
			s.robeUp = robeUp;
			s.wisdomUp = wisdomUp;
			s.assistUp = assistUp;
			s.focusUp = focusUp;
			s.illusionUp = illusionUp;
			s.draining = draining;
			s.hitInMelee = hitInMelee;
			s.hasTarget = hasTarget;
			s.targetWithin3m = targetWithin3m;
			s.targetWithin5m = targetWithin5m;
			s.targetOnMe = targetOnMe;
			s.targetAerial = targetAerial;
			s.dotOnTarget = dotOnTarget;
			s.fusionOnTarget = fusionOnTarget;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetIdle = targetIdle;
			s.followedChainOpen = followedChainOpen;
			s.iceChainOpen = iceChainOpen;
			s.shardChainOpen = shardChainOpen;
			s.spearChainOpen = spearChainOpen;
			s.meteoriteChainOpen = meteoriteChainOpen;
			s.aetherFlamesCast = aetherFlamesCast;
			s.enemyWithin7m = enemyWithin7m;
			s.secondToControl = secondToControl;
			s.severalEnemies = severalEnemies;
			s.holdFire = holdFire;
			return s;
		}
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the sorcerer alone decides on this skill. Takes the template so that every template in the game can be put through it. */
	static boolean claimsSkill(SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (FORBIDDEN_GROUPS.contains(group))
			return true;
		for (Move move : Move.values()) {
			if (group.equals(move.group))
				return move.claimed;
		}
		return false;
	}

	@Override
	public boolean swings(Player bot) {
		return false; // "Always Be Casting": the orb is never swung
	}

	/** Under half its mana after a fight, and until full once sitting: "après un combat", so never while its group is fighting. */
	@Override
	public boolean wantsToRest(Player bot) {
		if (BotGroupManager.isGroupFighting(bot))
			return false;
		int mp = bot.getLifeStats().getMpPercentage();
		return mp < REST_BELOW_PERCENT || mp < 100 && bot.isInState(CreatureState.RESTING);
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && bot.getLifeStats().getHpPercentage() < FLEE_BELOW_PERCENT;
	}

	/** The guide's flight: Winter Binding to hold whatever is close, then Blind Leap away. The walk away is the generic retreat's. */
	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.WINTER_BINDING.group))
			log.debug("Sorcerer {} casts {} as it flees", bot.getName(), Move.WINTER_BINDING);
		if (BotSkillManager.tryCastGroup(bot, bot, Move.BLIND_LEAP.group))
			log.debug("Sorcerer {} casts {} as it flees", bot.getName(), Move.BLIND_LEAP);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public boolean tend(Player bot) {
		return perform(bot, null, null, decideAtRest(atRest(bot), false));
	}

	@Override
	public boolean prepare(Player bot) {
		return perform(bot, null, null, decideAtRest(atRest(bot), true));
	}

	/** Casts the first move of the order that goes through; one the engine refuses gives way to the next, as for the gladiator. */
	@Override
	public boolean act(Player bot, Creature target) {
		Creature second = secondToControl(bot, target);
		Situation situation = inFight(bot, target, second != null);
		for (int tries = 0; tries < Move.values().length; tries++) {
			Move move = decide(situation);
			if (move == null)
				return false;
			if (perform(bot, target, second, move)) {
				noteCast(bot, move);
				return true;
			}
			situation = situation.without(move);
		}
		return false;
	}

	/**
	 * @param engaging Whether a fight is about to start: "avant d'engager la cible".
	 * @return The move out of a fight. Idle, Gain Mana under half and nothing else: a sorcerer living in a town does not renew a shield every five minutes
	 *         for nobody. Engaging, Stone Skin, the robe for being alone or in a group, Gain Mana, Empyrean Wisdom.
	 */
	static Move decideAtRest(Situation s, boolean engaging) {
		if (engaging && !s.stoneSkinUp && s.ready.contains(Move.STONE_SKIN))
			return Move.STONE_SKIN;
		Move robe = s.inTeam ? Move.ROBE_OF_FLAME : Move.ROBE_OF_COLD;
		if (engaging && !s.robeUp && s.ready.contains(robe))
			return robe;
		if (s.mp < GAIN_MANA_BELOW_PERCENT && s.ready.contains(Move.GAIN_MANA))
			return Move.GAIN_MANA;
		if (engaging && !s.wisdomUp && s.ready.contains(Move.EMPYREAN_WISDOM))
			return Move.EMPYREAN_WISDOM;
		return null;
	}

	/**
	 * Picks what the sorcerer does with this moment of a fight, most urgent first.
	 * <p>
	 * Control of itself, then Stone Skin ("priorité absolue en combat") and the defences, then its mana. Then, in a full group whose monster has been on it
	 * for ten seconds, nothing more: the defences above still go. Then the buffs of a fight, the multicasts and the chains it has open, the hold in the
	 * air, the divine power, the controls, the solo openers, the damage over time, and the damage order. A chain the generic order carries on (the fire
	 * chain's Blaze and Ember) is left to it while open.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.shockChainOpen && s.secondToControl && s.severalEnemies && ready.contains(Move.CURSE_OF_OLD_ROOTS))
			return Move.CURSE_OF_OLD_ROOTS;
		if (!s.stoneSkinUp && ready.contains(Move.STONE_SKIN))
			return Move.STONE_SKIN;
		if (s.hp < IRON_CLAD_BELOW_PERCENT && !s.stoneSkinUp && !ready.contains(Move.STONE_SKIN) && ready.contains(Move.BOON_OF_IRON_CLAD))
			return Move.BOON_OF_IRON_CLAD;
		if (s.hitInMelee && !s.illusionUp && ready.contains(Move.ILLUSION))
			return Move.ILLUSION;
		if (s.targetOnMe && s.targetWithin5m && ready.contains(Move.BOON_OF_PEACE))
			return Move.BOON_OF_PEACE;
		if (s.targetWithin3m && ready.contains(Move.FREEZING_WIND))
			return Move.FREEZING_WIND;
		if (s.mp < GAIN_MANA_BELOW_PERCENT && ready.contains(Move.GAIN_MANA))
			return Move.GAIN_MANA;
		if (s.shardChainOpen && ready.contains(Move.SOUL_ABSORPTION))
			return Move.SOUL_ABSORPTION;
		if (s.draining && ready.contains(Move.REFRACTING_SHARD))
			return Move.REFRACTING_SHARD;
		if (s.holdFire)
			return Move.HOLD_FIRE;
		Move robe = s.inTeam ? Move.ROBE_OF_FLAME : Move.ROBE_OF_COLD;
		if (!s.robeUp && ready.contains(robe))
			return robe;
		if (!s.wisdomUp && ready.contains(Move.EMPYREAN_WISDOM))
			return Move.EMPYREAN_WISDOM;
		if (!s.assistUp && ready.contains(Move.MAGIC_ASSIST))
			return Move.MAGIC_ASSIST;
		// the multicasts and chain B, which close if anything else is cast
		if (s.spearChainOpen && ready.contains(Move.WIND_SPEAR))
			return Move.WIND_SPEAR;
		if (s.meteoriteChainOpen && ready.contains(Move.METEORITE))
			return Move.METEORITE;
		if (s.iceChainOpen && !s.inTeam && ready.contains(Move.FROZEN_SHOCK))
			return Move.FROZEN_SHOCK;
		if (s.followedChainOpen)
			return null;
		if (s.targetAerial) {
			if (s.aetherFlamesCast >= AETHER_FLAMES && ready.contains(Move.MAGIC_FIST))
				return Move.MAGIC_FIST;
			if (ready.contains(Move.AETHER_FLAME))
				return Move.AETHER_FLAME;
			if (ready.contains(Move.MAGIC_FIST))
				return Move.MAGIC_FIST;
		}
		Move divine = decideDivinePower(s);
		if (divine != null)
			return divine;
		if (s.secondToControl && (s.inTeam || s.severalEnemies)) {
			for (Move move : CONTROL_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.targetCasts && ready.contains(Move.SOUL_FREEZE))
			return focusFirst(s, Move.SOUL_FREEZE);
		if (!s.inTeam && s.targetIdle) {
			if (ready.contains(Move.DELAYED_BLAST))
				return Move.DELAYED_BLAST;
			if (ready.contains(Move.ICE_CHAIN))
				return Move.ICE_CHAIN;
		}
		if (!s.dotOnTarget) {
			if (ready.contains(Move.FLAME_CAGE))
				return Move.FLAME_CAGE;
			if (ready.contains(Move.EROSION))
				return Move.EROSION;
		}
		for (Move move : DAMAGE_ORDER) {
			if (!ready.contains(move) || !fits(s, move))
				continue;
			return move == Move.AETHERS_HOLD ? focusFirst(s, move) : move;
		}
		return null;
	}

	/** @return Whether a move of the damage order is worth casting now, beyond being ready. */
	private static boolean fits(Situation s, Move move) {
		return switch (move) {
			case FLAME_FUSION -> !s.fusionOnTarget;
			case FLAME_SPRAY -> s.fusionOnTarget; // "à utiliser après Flame Fusion"
			case GLACIAL_SHARD -> s.mp > GLACIAL_SHARD_ABOVE_PERCENT;
			case METEORITE -> s.enemyWithin7m; // 7 m round the sorcerer: nothing to hit from 25 m
			default -> true;
		};
	}

	/** @return Supplication of Focus before a skill whose effect has to land (Soul Freeze, Aether's Hold), when it is ready and off; otherwise the skill. */
	private static Move focusFirst(Situation s, Move move) {
		return !s.focusUp && s.ready.contains(Move.SUPPLICATION_OF_FOCUS) ? Move.SUPPLICATION_OF_FOCUS : move;
	}

	/** Empyrean Fire at 2000 DP; against a player, Shadowburst then Empyrean Wrath at 4000, which the guide keeps for PvP. */
	static Move decideDivinePower(Situation s) {
		if (s.targetIsPlayer && s.dp >= LUMIEL_DP) {
			if (s.ready.contains(Move.SHADOWBURST))
				return Move.SHADOWBURST;
			if (s.ready.contains(Move.EMPYREAN_WRATH))
				return Move.EMPYREAN_WRATH;
		}
		if (s.dp >= ABILITY_DP && s.ready.contains(Move.EMPYREAN_FIRE))
			return Move.EMPYREAN_FIRE;
		return null;
	}

	/**
	 * Opens a gate to the capital for the bot's group, at its feet: Summon Rift, which {@code //bot gate} asks for.
	 *
	 * @return A line to answer the command with.
	 */
	static String openGate(Player bot) {
		if (bot.getPlayerClass() != PlayerClass.SORCERER)
			return bot.getName() + " is not a sorcerer.";
		if (!BotSkillManager.isReady(bot, GATE_GROUP))
			return bot.getName() + " cannot open a gate now: it does not know Summon Rift (level 22) or it is cooling down.";
		if (!BotSkillManager.tryCastGroup(bot, bot, GATE_GROUP))
			return bot.getName() + " could not open a gate here.";
		return bot.getName() + " opens a gate to the capital at its feet.";
	}

	private static boolean perform(Player bot, Creature target, Creature second, Move move) {
		if (move == null)
			return false;
		if (move.aim == Aim.NONE)
			return true; // holding fire is a decision, and the fight loop must not fill it with something else
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> target;
			case SECOND -> second;
			case NONE -> null;
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Sorcerer {} casts {} at {}% health, {}% mana", bot.getName(), move, bot.getLifeStats().getHpPercentage(),
				bot.getLifeStats().getMpPercentage());
		return cast;
	}

	/** Keeps the state the rules read from one tick to the next: the Aether Flames put on a hold. */
	private static void noteCast(Player bot, Move move) {
		if (move == Move.AETHER_FLAME)
			aetherFlames.merge(bot.getObjectId(), 1, Integer::sum);
		else if (move == Move.MAGIC_FIST)
			aetherFlames.remove(bot.getObjectId());
	}

	private static Set<Move> ready(Player bot) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (move.group != null && BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		return ready;
	}

	private static Situation atRest(Player bot) {
		Situation s = new Situation();
		s.ready = ready(bot);
		s.inTeam = bot.getCurrentTeam() != null;
		s.mp = bot.getLifeStats().getMpPercentage();
		s.stoneSkinUp = BotSkillManager.isUp(bot, Move.STONE_SKIN.group);
		s.robeUp = BotSkillManager.isUp(bot, (s.inTeam ? Move.ROBE_OF_FLAME : Move.ROBE_OF_COLD).group);
		s.wisdomUp = BotSkillManager.isUp(bot, Move.EMPYREAN_WISDOM.group);
		return s;
	}

	private static Situation inFight(Player bot, Creature target, boolean secondToControl) {
		Situation s = atRest(bot);
		int id = bot.getObjectId();
		s.hp = bot.getLifeStats().getHpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		s.shockChainOpen = BotSkillManager.isChainOpen(bot, SHOCK_CHAIN);
		s.assistUp = BotSkillManager.isUp(bot, Move.MAGIC_ASSIST.group);
		s.focusUp = BotSkillManager.isUp(bot, Move.SUPPLICATION_OF_FOCUS.group);
		s.illusionUp = BotSkillManager.isUp(bot, Move.ILLUSION.group);
		// from under 30 % until 60 %: a state, or the drain would stop the moment the mana passed 30 again
		if (s.mp < DRAIN_FROM_PERCENT)
			draining.add(id);
		else if (s.mp >= DRAIN_UP_TO_PERCENT)
			draining.remove(id);
		s.draining = draining.contains(id);
		s.followedChainOpen = FOLLOWED_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		s.iceChainOpen = BotSkillManager.isChainOpen(bot, ICE_CHAIN);
		s.shardChainOpen = BotSkillManager.isChainOpen(bot, SHARD_CHAIN);
		s.spearChainOpen = BotSkillManager.isChainOpen(bot, SPEAR_CHAIN);
		s.meteoriteChainOpen = BotSkillManager.isChainOpen(bot, METEORITE_CHAIN);
		s.secondToControl = secondToControl;
		s.hasTarget = target != null;
		if (target != null) {
			double distance = PositionUtil.getDistance(bot, target);
			s.targetWithin3m = distance <= FREEZING_WIND_REACH;
			s.targetWithin5m = distance <= BOON_OF_PEACE_REACH;
			s.targetOnMe = bot.equals(target.getTarget());
			s.targetAerial = target.getEffectController().isAbnormalSet(AbnormalState.OPENAERIAL);
			s.dotOnTarget = BotSkillManager.isOnTarget(bot, target, Move.FLAME_CAGE.group) || BotSkillManager.isOnTarget(bot, target, Move.EROSION.group);
			s.fusionOnTarget = BotSkillManager.isOnTarget(bot, target, Move.FLAME_FUSION.group);
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			s.targetIdle = !target.getAggroList().isHating(bot);
		}
		if (!s.targetAerial)
			aetherFlames.remove(id);
		s.aetherFlamesCast = aetherFlames.getOrDefault(id, 0);
		int[] fighting = { 0 };
		boolean[] meleeHit = { false }, within7m = { false };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !bot.isEnemy(npc) || !npc.getAggroList().isHating(bot) && !npc.equals(target))
				return;
			fighting[0]++;
			double distance = PositionUtil.getDistance(bot, npc);
			if (distance <= METEORITE_REACH)
				within7m[0] = true;
			if (distance <= MELEE_REACH && bot.equals(npc.getTarget()))
				meleeHit[0] = true;
		});
		s.severalEnemies = fighting[0] > 1;
		s.enemyWithin7m = within7m[0];
		s.hitInMelee = meleeHit[0];
		s.holdFire = holdsFire(bot, target, s.targetOnMe);
		return s;
	}

	/**
	 * @return true if the sorcerer, in a full group with a tank, has had its target on it for ten seconds: it then stops attacking until the tank has it
	 *         back. The time is remembered from tick to tick and forgotten the moment the target looks elsewhere.
	 */
	private static boolean holdsFire(Player bot, Creature target, boolean targetOnMe) {
		int id = bot.getObjectId();
		if (!targetOnMe || !inFullGroupWithATank(bot)) {
			aimedSince.remove(id);
			return false;
		}
		long since = aimedSince.computeIfAbsent(id, _ -> System.currentTimeMillis());
		return System.currentTimeMillis() - since >= HOLD_FIRE_AFTER_MILLIS;
	}

	private static boolean inFullGroupWithATank(Player bot) {
		PlayerGroup group = bot.getPlayerGroup();
		if (group == null || group.size() < FULL_GROUP)
			return false;
		return group.getMembers().stream().anyMatch(member -> member.getPlayerClass() == PlayerClass.TEMPLAR && !member.isDead());
	}

	/**
	 * @return An enemy to control: engaged with the sorcerer or its group, not its target, not already held, slept, transformed or silenced, within reach
	 *         of the controls. Alone only with several on it; in a group whenever one is there, as the guide asks.
	 */
	private static Creature secondToControl(Player bot, Creature target) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		List<Player> fighting = team == null ? List.of(bot) : team.getMembers();
		Npc[] found = { null };
		bot.getKnownList().forEachNpc(npc -> {
			if (found[0] != null || npc.equals(target) || npc.isDead() || !bot.isEnemy(npc) || PositionUtil.getDistance(bot, npc) > CONTROL_REACH)
				return;
			if (fighting.stream().noneMatch(member -> npc.getAggroList().isHating(member)))
				return;
			if (npc.getEffectController().isInAnyAbnormalState(AbnormalState.CANT_MOVE_STATE) || npc.getEffectController().isAbnormalSet(AbnormalState.SLEEP)
				|| npc.getEffectController().isAbnormalSet(AbnormalState.SILENCE) || npc.getEffectController().isAbnormalSet(AbnormalState.DEFORM))
				return;
			found[0] = npc;
		});
		return found[0];
	}

	@Override
	public void forget(Player bot) {
		int id = bot.getObjectId();
		draining.remove(id);
		aetherFlames.remove(id);
		aimedSince.remove(id);
	}
}
