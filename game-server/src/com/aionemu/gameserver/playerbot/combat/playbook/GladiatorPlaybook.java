package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.team.group.PlayerGroup;
import com.aionemu.gameserver.playerbot.combat.BotAttackManager;
import com.aionemu.gameserver.playerbot.combat.BotCombatMemory;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a gladiator plays, after the guide's "Gladiateur 101". The plan, milestone by milestone, is {@code docs/classes/history/gladiator-plan.md}.
 * <p>
 * Built so far: the bans (milestone 1), the posture (milestone 2), survival (milestone 3), the conditionals (milestone 4), the areas (milestone 5; the
 * bystander rule itself is the generic order's, in {@code BotSkillManager.cast}) and the openers, burst and divine power (milestone 6). The chains' later
 * links and the filler are the generic order's, which casts chain continuations first and then the strongest stated damage.
 */
final class GladiatorPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(GladiatorPlaybook.class);

	/**
	 * Skills the guide says never to use, by group. Rage is a second link of chain A and would break the filler; Wrathful Wave is 4000 DP over 20 m;
	 * Taunt, Body Slice, Mocking Blast and Defense Preparation hold a monster's attention, which is the templar's job and, from milestone 8, the
	 * gladiator's in a group of six whose templar is dead. Claimed without being cast, so that the generic order leaves them alone.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("WA_RAGE", "WA_PROVOKE", "FI_WHIRLWIND", "FI_BODYSMASH", "FI_SLASHIGFORCE", "FI_DEFENSEMODE");
	/** Health under which Second Wind, the gladiator's own heal, is cast: the guide's 50 %. */
	static final int SECOND_WIND_BELOW_PERCENT = 50;
	/** Health under which Counter Leech and Draining Blow drain the target: the guide's 60 %. */
	static final int DRAIN_BELOW_PERCENT = 60;
	/** Health under which Draining Sword, the biggest drain, is cast: 40 %, the guide's "above" having been a slip. */
	static final int DRAINING_SWORD_BELOW_PERCENT = 40;
	/** The chain category Remove Shock opens, which Ferocity and Wrathful Explosion ask for. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";
	/** How long after being controlled Unwavering Devotion still answers it: the templar's figure, for the same skill. */
	static final long RECENTLY_CONTROLLED_MILLIS = TemplarPlaybook.RECENTLY_CONTROLLED_MILLIS;
	/** The chain category Absorbing Fury opens, which Roiling Hack and Pressure Wave both ask for. */
	static final String AREA_CHAIN = "W_CHAINC_1TH_1";
	/** Health above which Pressure Wave is taken after Absorbing Fury rather than Roiling Hack, whose lifesteal is then wasted: the guide's 80 %. */
	static final int PRESSURE_WAVE_ABOVE_PERCENT = 80;
	/** How many enemies fighting the gladiator within {@link #AREA_REACH} make it a fight for its areas: the guide's "plusieurs ennemis". */
	static final int ENEMIES_FOR_AREAS = 2;
	/** How far around the gladiator an enemy counts towards {@link #ENEMIES_FOR_AREAS}: the 7 m most of its areas reach. */
	static final float AREA_REACH = 7f;
	/** What Daevic Fury and Explosion of Rage cost in divine power, as the data states it for both. */
	static final int ABILITY_DP = 2000;
	/** What Empyrean Blessing, the guide's Zikel's Threat, costs. */
	static final int ZIKEL_DP = 4000;
	/** How far a target must be for Charge, a run speed buff, to be worth it: the guide's 20 m. */
	static final float CHARGE_BEYOND = 20f;
	/** How far Ferocious Chop leaps, its first target range in the data. */
	static final float FEROCIOUS_CHOP_REACH = 17f;
	/**
	 * The chain categories whose next link the generic order casts: the filler (A), Body Smash's (B), the end of chain C, Cleave's (D) and Energy Impact
	 * after Weakening Blow, Sure Strike or Severe Precision Cut (E). While one is open the playbook's buffs and single target moves wait: any of them would
	 * close it, the engine resetting a chain the moment another skill goes through. Chain B's second link is left out, its third being Body Slice, banned.
	 */
	static final List<String> FOLLOWED_CHAINS = List.of("W_CHAINA_1TH_1", "W_CHAINA_2TH_1", "W_CHAINA_3TH_2", "W_CHAINB_1TH_1", "W_CHAINC_2TH_1",
		"W_CHAIND_1TH_1", "W_CHAIND_2TH_1", "W_CHAINE_1TH_1");
	/** The chain category Body Combo opens, which Body Slice asks for: the tank-dead mode's third link of chain B. */
	static final String BODY_CHAIN = "W_CHAINB_2TH_1";
	/** Health over which a lone gladiator pulls a second monster: the guide's "en bonne santé (HP > 90 %)". */
	static final int PULL_ABOVE_PERCENT = 90;
	/** How many monsters a lone gladiator takes on at once on purpose: two, the guide's "2 à 3" brought down to two. */
	static final int MOST_PULLED = 2;
	/** How far a second monster may be to be pulled: Cleave's 15 m. */
	static final float PULL_REACH = 15f;
	/** How near a second monster another one may stand, not fighting, before pulling it would wake a pack rather than one monster. */
	static final float PACK_RADIUS = 6f;
	/** Health under which a gladiator outnumbered by normal monsters is in danger, and leaves after all. The generic rule leaves at 40 %. */
	static final int DANGER_BELOW_PERCENT = 25;
	/** The group size the tank-dead mode is for: the guide's "groupe 6 personnes". */
	static final int FULL_GROUP = 6;
	/** The stigmas the guide lists (section IV), the three regular sockets first, then the advanced ones. */
	static final List<String> REGULAR_STIGMAS = List.of("FI_STIGMA_DRAINCUT", "FI_HEAVYIMPACT", "FI_CRIPPLINGCUT");
	static final List<String> ADVANCED_STIGMAS = List.of("FI_BURSERKLANCE", "FI_CHARGINGSHOCK", "FI_DRAINSWORD");

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** A second monster the gladiator pulls on purpose. */
		SECOND
	}

	/**
	 * What the playbook may cast, and the skill group that names it; every level of a skill has its own id and the group is what they share. A move is
	 * claimed, kept from the generic order, unless it says otherwise.
	 */
	enum Move {
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		/** Explosion of Wrath in the guide, after Remove Shock, preferred from 53 when it is learnt. Area of 10 m around the gladiator. */
		WRATHFUL_EXPLOSION("FI_RAGEBURST", Aim.SELF),
		/** Wrath in the guide (Colère): an 8 s buff after Remove Shock, until Wrathful Explosion is learnt. */
		FEROCITY("FI_FIGHTERRAGE", Aim.SELF),
		SECOND_WIND("FI_SURVIVORSTANCE", Aim.SELF),
		DRAINING_SWORD("FI_DRAINSWORD", Aim.TARGET),
		COUNTER_LEECH("FI_COUNTERDRAIN", Aim.TARGET),
		/** A stigma, on a target knocked down only, as the data states it. */
		DRAINING_BLOW("FI_STIGMA_DRAINCUT", Aim.TARGET),
		/** The guide's "L'attaque finale": on a target knocked down or held in the air, the biggest hit of the class. */
		FINAL_STRIKE("FI_CRUELBLOW", Aim.TARGET),
		/** A stigma, on a target knocked down. */
		CRIPPLING_CUT("FI_CRIPPLINGCUT", Aim.TARGET),
		/** A leap of 15 m onto a target knocked down: the guide's conditional gap closer. */
		SPRINGING_SLICE("FI_JUMPINGCUT", Aim.TARGET),
		/** On a target held in the air by Aerial Lockdown (Aether's Hold), which it brings down. */
		CRASHING_BLOW("FI_DROPIMPACT", Aim.TARGET),
		/** Absorption Wave in the guide: chain C's second link, a lifesteal over 7 m, when the gladiator is at 80 % or less. */
		ROILING_HACK("FI_DRAINWAVE", Aim.TARGET),
		/** Chain C's other second link, above 80 % or while Roiling Hack is cooling down. */
		PRESSURE_WAVE("FI_SEISMICIMPACT", Aim.TARGET),
		/** Fury Absorption in the guide: chain C's opener, 7 m around the gladiator. Also a single target move, so the generic order keeps it. */
		ABSORBING_FURY("FI_SEISMICDRAIN", Aim.TARGET, false),
		/** A stigma: an area of 7 m that knocks down, for several enemies. */
		SEVERE_PRECISION_CUT("FI_CHARGINGSHOCK", Aim.TARGET),
		/** An area of 10 m that lowers defences, for several enemies. */
		PIERCING_RUPTURE("FI_STIGMA_SHOCKBURST", Aim.TARGET),
		/** A stigma: an area of 10 m that snares, for several enemies. */
		EARTHQUAKE_WAVE("FI_HEAVYIMPACT", Aim.TARGET),
		/** Dragon Cut Variation in the guide: an area of 7 m, for several enemies. */
		WHIRLING_ADVANCE("FI_DRAGONSLASH", Aim.TARGET),
		/** Shadow Rage in the guide (La rage de l'ombre): attack and defence for 30 s, as soon as it is ready once in reach. */
		BERSERKING("FI_BERSERKSTANCE", Aim.SELF),
		/** Zikel's Threat in the guide: 4000 DP, attack, defence, attack speed and speed for 30 s, preferred from 50. */
		EMPYREAN_BLESSING("FI_CRUSADE", Aim.SELF),
		/** 2000 DP, attack and attack speed for 30 s: from 31, and from 50 only while Empyrean Blessing is cooling down. */
		DAEVIC_FURY("FI_RAMPAGEMODE", Aim.SELF),
		/** 2000 DP, a big hit that knocks down: what is left of the divine power once no buff is waiting for it. */
		EXPLOSION_OF_RAGE("FI_ABYSALRAGE", Aim.TARGET),
		/** A run speed buff, not a leap: for a target more than 20 m away. */
		CHARGE("WA_DASH", Aim.SELF),
		/** The opener from afar: 15 m plus the weapon, snares. Its chain is the generic order's. */
		CLEAVE("FI_WINGBLADE", Aim.TARGET),
		/** Raging Blow in the guide: a leap of 17 m into an area of 7 m, the gap closer from 65. */
		FEROCIOUS_CHOP("FI_FLYINGSLASH", Aim.TARGET),
		/** The opener in reach, lowers defences; opens chain E, Energy Impact. */
		WEAKENING_BLOW("FI_ENFEEBLEHIT", Aim.TARGET),
		/** A stigma, right after Weakening Blow, both on 30 s; opens chain E as well. */
		SURE_STRIKE("FI_BURSERKLANCE", Aim.TARGET),
		/** Holds the target in the air, which opens Final Strike and Crashing Blow. */
		AERIAL_LOCKDOWN("FI_RAISINGIMPACT", Aim.TARGET),
		/** Knocks the target down, which opens Crippling Cut, Springing Slice and Final Strike. */
		CRUSHING_BLOW("FI_DESTRUCTBLOW", Aim.TARGET),
		/** Cleave on a second monster, alone and healthy: the guide's gladiator takes two on at once. */
		PULL_SECOND("FI_WINGBLADE", Aim.SECOND),
		/** The tank-dead mode's posture, in place of Slaughter. A toggle, like it. */
		DEFENSE_PREPARATION("FI_DEFENSEMODE", Aim.SELF),
		/** The tank-dead mode: on a target hitting somebody else. */
		TAUNT("WA_PROVOKE", Aim.TARGET),
		/** Blade of Incitement in the guide (Lame d'incitation): the tank-dead mode, when ready. */
		MOCKING_BLAST("FI_SLASHIGFORCE", Aim.TARGET),
		/** Body Blow in the guide: chain B's third link, the tank-dead mode only. */
		BODY_SLICE("FI_BODYSMASH", Aim.TARGET),
		WALL_OF_STEEL("FI_PARRYFOCUS", Aim.SELF),
		ARMOR_OF_ATTRITION("FI_FORTITUDE", Aim.SELF),
		UNWAVERING_DEVOTION("WA_STEADINESS", Aim.SELF),
		/** Slaughter, the guide's "Préparation au combat": the one posture, solo and in a group. A toggle, so it is cast only when it is not up. */
		SLAUGHTER("FI_BLADEMODE", Aim.SELF);

		final String group;
		final Aim aim;
		/** Whether the generic order is kept from the move. */
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

	/** The area moves, in the order a gladiator with several enemies on it takes them: the guide's group sequence. */
	static final List<Move> AREA_ORDER = List.of(Move.ABSORBING_FURY, Move.SEVERE_PRECISION_CUT, Move.PIERCING_RUPTURE, Move.EARTHQUAKE_WAVE,
		Move.WHIRLING_ADVANCE);
	/** The single target moves in reach, in the order of the guide's sequence against one enemy. */
	static final List<Move> SINGLE_TARGET_ORDER = List.of(Move.WEAKENING_BLOW, Move.SURE_STRIKE, Move.AERIAL_LOCKDOWN, Move.CRUSHING_BLOW);

	/**
	 * Everything {@link #decide} looks at, so that it can be decided without a world.
	 *
	 * @param hpPercent The gladiator's health.
	 * @param controlled Whether it is stunned, knocked about, spun or held in the air right now.
	 * @param recentlyControlled Whether it was, a moment ago and no longer.
	 * @param shockChainOpen Whether Remove Shock was the last skill it used, the only moment Ferocity or Wrathful Explosion can follow it.
	 * @param attacked Whether an enemy is aiming at the gladiator, which is when the guide raises Wall of Steel and Armor of Attrition.
	 * @param targetDown Whether the target is knocked down (stumble), which Draining Blow, Final Strike, Crippling Cut and Springing Slice need.
	 * @param targetAerial Whether the target is held in the air (Aether's Hold), which Final Strike and Crashing Blow need.
	 * @param slaughterUp Whether Slaughter is on. It is a toggle, and casting a toggle that is on switches it off.
	 * @param wallUp Whether Wall of Steel is on.
	 * @param armorUp Whether Armor of Attrition is on.
	 * @param devotionUp Whether Unwavering Devotion is on.
	 * @param ready The moves the gladiator knows and that are off cooldown.
	 * @param areaChainOpen Whether Absorbing Fury was the last skill it used, the moment Roiling Hack or Pressure Wave can follow it.
	 * @param enemiesClose How many enemies fighting the gladiator or its group are within {@link #AREA_REACH} of it, its target included.
	 * @param offence What the burst and the single target moves look at.
	 * @param group What the tank-dead mode looks at.
	 */
	record Situation(int hpPercent, boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean attacked, boolean targetDown,
		boolean targetAerial, boolean slaughterUp, boolean wallUp, boolean armorUp, boolean devotionUp, Set<Move> ready, boolean areaChainOpen,
		int enemiesClose, Offence offence, Group group) {

		/** The same, outside the tank-dead mode. */
		Situation(int hpPercent, boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean attacked, boolean targetDown,
			boolean targetAerial, boolean slaughterUp, boolean wallUp, boolean armorUp, boolean devotionUp, Set<Move> ready, boolean areaChainOpen,
			int enemiesClose, Offence offence) {
			this(hpPercent, controlled, recentlyControlled, shockChainOpen, attacked, targetDown, targetAerial, slaughterUp, wallUp, armorUp, devotionUp, ready,
				areaChainOpen, enemiesClose, offence, Group.NONE);
		}

		/** A fight against one enemy in reach, chain C closed, no divine power, which is what every rule but the areas and the offence is decided in. */
		Situation(int hpPercent, boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean attacked, boolean targetDown,
			boolean targetAerial, boolean slaughterUp, boolean wallUp, boolean armorUp, boolean devotionUp, Set<Move> ready) {
			this(hpPercent, controlled, recentlyControlled, shockChainOpen, attacked, targetDown, targetAerial, slaughterUp, wallUp, armorUp, devotionUp, ready,
				false, 1);
		}

		/** The same, with chain C and the enemies around given. */
		Situation(int hpPercent, boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean attacked, boolean targetDown,
			boolean targetAerial, boolean slaughterUp, boolean wallUp, boolean armorUp, boolean devotionUp, Set<Move> ready, boolean areaChainOpen,
			int enemiesClose) {
			this(hpPercent, controlled, recentlyControlled, shockChainOpen, attacked, targetDown, targetAerial, slaughterUp, wallUp, armorUp, devotionUp, ready,
				areaChainOpen, enemiesClose, Offence.IN_REACH);
		}

		/** Out of a fight, where only the posture is decided. */
		static Situation atRest(boolean slaughterUp, Set<Move> ready) {
			return atRest(slaughterUp, Group.NONE, ready);
		}

		/** @return The same situation with the move no longer ready, for asking the order again after the engine refused it. */
		Situation without(Move move) {
			Set<Move> left = EnumSet.noneOf(Move.class);
			left.addAll(ready);
			left.remove(move);
			return new Situation(hpPercent, controlled, recentlyControlled, shockChainOpen, attacked, targetDown, targetAerial, slaughterUp, wallUp, armorUp,
				devotionUp, left, areaChainOpen, enemiesClose, offence, group);
		}

		static Situation atRest(boolean slaughterUp, Group group, Set<Move> ready) {
			return new Situation(100, false, false, false, false, false, false, slaughterUp, false, false, false, ready, false, 1, Offence.IN_REACH, group);
		}
	}

	/**
	 * What the tank-dead mode looks at: a group of six whose templar is dead, until it is raised.
	 *
	 * @param tankDead Whether the gladiator is in such a group and its templar lies dead.
	 * @param defenseUp Whether Defense Preparation is on, which outside the mode is switched off again.
	 * @param targetElsewhere Whether the target is hitting somebody other than the gladiator, which is what Taunt is for.
	 * @param bodyChainOpen Whether Body Combo was the last skill used, the moment Body Slice can follow it.
	 */
	record Group(boolean tankDead, boolean defenseUp, boolean targetElsewhere, boolean bodyChainOpen) {

		static final Group NONE = new Group(false, false, false, false);
	}

	/**
	 * What the burst and the single target moves look at.
	 *
	 * @param dp The gladiator's divine power.
	 * @param inReach Whether its target is within its weapon's reach.
	 * @param distance How far its target is, in metres.
	 * @param chainToFollow Whether one of the {@link #FOLLOWED_CHAINS} is open, whose next link the generic order casts.
	 * @param berserkingUp Whether Berserking is on.
	 * @param secondToPull Whether there is a second monster to pull on purpose: alone, healthy, one monster on it, a fair one in reach of Cleave.
	 */
	record Offence(int dp, boolean inReach, float distance, boolean chainToFollow, boolean berserkingUp, boolean secondToPull) {

		/** In reach, no divine power, no chain open, Berserking not up. */
		static final Offence IN_REACH = new Offence(0, true, 0, false, false);

		/** With no second monster to pull. */
		Offence(int dp, boolean inReach, float distance, boolean chainToFollow, boolean berserkingUp) {
			this(dp, inReach, distance, chainToFollow, berserkingUp, false);
		}
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the gladiator alone decides on this skill. Takes the template and not the bot so that every template in the game can be put through it. */
	static boolean claimsSkill(SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (FORBIDDEN_GROUPS.contains(group))
			return true;
		for (Move move : Move.values()) {
			if (move.group.equals(group))
				return move.claimed;
		}
		return false;
	}

	/** Out of a fight: the posture, which a death or a fresh login has taken off. */
	@Override
	public boolean tend(Player bot) {
		return perform(bot, null, null, decideAtRest(atRest(bot, null)));
	}

	@Override
	public boolean prepare(Player bot) {
		return perform(bot, null, null, decideAtRest(atRest(bot, null)));
	}

	/**
	 * Casts the first move of the order that goes through. A move the engine refuses (out of reach, an area over a monster nobody is fighting) is set aside
	 * for this tick and the order asked again: answered with the refused move alone, the gladiator was held on it every tick, Counter Leech refused on the
	 * way in keeping Wall of Steel from ever being raised.
	 */
	@Override
	public boolean act(Player bot, Creature target) {
		Npc second = secondToPull(bot, target);
		Situation situation = inFight(bot, target, second != null);
		for (int tries = 0; tries < Move.values().length; tries++) {
			Move move = decide(situation);
			if (move == null)
				return false;
			if (perform(bot, target, second, move))
				return true;
			situation = situation.without(move);
		}
		return false;
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	/**
	 * Holds against monsters of its own kind, as the guide's gladiator does, and leaves what it cannot hold: an elite or worse among them, at any health,
	 * or any number once in danger. The generic rule leaves two attackers under 40 % health, which is the fight a gladiator is built to win.
	 */
	@Override
	public Outnumbered whenOutnumbered(Player bot, List<Creature> attackers) {
		boolean elite = attackers.stream().anyMatch(attacker -> attacker instanceof Npc npc && BotTargetSelector.needsAGroup(npc.getObjectTemplate()));
		return decideOutnumbered(elite, bot.getLifeStats().getHpPercentage());
	}

	static Outnumbered decideOutnumbered(boolean eliteAmongThem, int hpPercent) {
		return eliteAmongThem || hpPercent < DANGER_BELOW_PERCENT ? Outnumbered.FLEE : Outnumbered.STAND;
	}

	/**
	 * @return The move to make out of a fight, or null. Only the posture, the defensives being for a fight as the guide says: Slaughter, except in the
	 *         tank-dead mode, where Defense Preparation takes its place. Both are toggles, so the one not wanted is switched off by casting it again, and the
	 *         one wanted is cast only when it is off.
	 */
	static Move decideAtRest(Situation situation) {
		Set<Move> ready = situation.ready();
		Group group = situation.group();
		if (group.tankDead()) {
			if (situation.slaughterUp() && ready.contains(Move.SLAUGHTER))
				return Move.SLAUGHTER;
			if (!group.defenseUp() && ready.contains(Move.DEFENSE_PREPARATION))
				return Move.DEFENSE_PREPARATION;
			return null;
		}
		if (group.defenseUp() && ready.contains(Move.DEFENSE_PREPARATION))
			return Move.DEFENSE_PREPARATION;
		if (!situation.slaughterUp() && ready.contains(Move.SLAUGHTER))
			return Move.SLAUGHTER;
		return null;
	}

	/**
	 * The tank-dead mode's moves, once the posture is right: Taunt on a target hitting somebody else, Mocking Blast whenever it is ready, Body Slice when
	 * chain B offers it. The guide: "utiliser les skills orienté Tanking dès que disponibles jusqu'à ce que le templier soit ressuscité".
	 *
	 * @return The move, or null outside the mode or with nothing ready.
	 */
	static Move holdTheMonsters(Situation situation) {
		Group group = situation.group();
		Set<Move> ready = situation.ready();
		if (!group.tankDead())
			return null;
		if (group.targetElsewhere() && ready.contains(Move.TAUNT))
			return Move.TAUNT;
		if (ready.contains(Move.MOCKING_BLAST))
			return Move.MOCKING_BLAST;
		if (group.bodyChainOpen() && ready.contains(Move.BODY_SLICE))
			return Move.BODY_SLICE;
		return null;
	}

	/**
	 * Picks what the gladiator does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * Control first: a gladiator that cannot act casts nothing but Remove Shock, and the chain it opens is closed by any other skill, so its follow-up comes
	 * straight after. Then its health, its own heal before the drains, the biggest drain at the lowest bar. Then a target knocked down or held in the air,
	 * a window of three seconds ({@link #exploitTheOpening}). Then the two defensives the guide raises
	 * whenever something is hitting the gladiator, the parry first since it lasts longer and its cooldown is the longer one. Then Unwavering Devotion
	 * after a control, and the posture, which only a death takes off in a fight. Then, unless a chain the generic order carries on is open, the burst,
	 * with several enemies the areas in the guide's order (each refused by the cast itself when it would also take in an enemy nobody is fighting), and
	 * the single target sequence.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled())
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (situation.shockChainOpen()) {
			if (ready.contains(Move.WRATHFUL_EXPLOSION))
				return Move.WRATHFUL_EXPLOSION;
			if (ready.contains(Move.FEROCITY))
				return Move.FEROCITY;
		}
		int hp = situation.hpPercent();
		if (hp < SECOND_WIND_BELOW_PERCENT && ready.contains(Move.SECOND_WIND))
			return Move.SECOND_WIND;
		if (hp < DRAINING_SWORD_BELOW_PERCENT && ready.contains(Move.DRAINING_SWORD))
			return Move.DRAINING_SWORD;
		if (hp < DRAIN_BELOW_PERCENT) {
			if (situation.targetDown() && ready.contains(Move.DRAINING_BLOW))
				return Move.DRAINING_BLOW;
			if (ready.contains(Move.COUNTER_LEECH))
				return Move.COUNTER_LEECH;
		}
		Move opening = exploitTheOpening(situation);
		if (opening != null)
			return opening;
		Move areaLink = continueTheAreaChain(situation);
		if (areaLink != null)
			return areaLink;
		if (situation.attacked()) {
			if (!situation.wallUp() && ready.contains(Move.WALL_OF_STEEL))
				return Move.WALL_OF_STEEL;
			if (!situation.armorUp() && ready.contains(Move.ARMOR_OF_ATTRITION))
				return Move.ARMOR_OF_ATTRITION;
		}
		if (situation.recentlyControlled() && !situation.devotionUp() && ready.contains(Move.UNWAVERING_DEVOTION))
			return Move.UNWAVERING_DEVOTION;
		Move posture = decideAtRest(situation);
		if (posture != null)
			return posture;
		Move tanking = holdTheMonsters(situation);
		if (tanking != null)
			return tanking;
		// a chain open whose next link is the generic order's: anything cast now would close it
		if (situation.offence().chainToFollow())
			return null;
		// once the first monster is held in reach, not on the way to it
		if (situation.offence().secondToPull() && situation.offence().inReach() && ready.contains(Move.PULL_SECOND))
			return Move.PULL_SECOND;
		Move burst = decideBurst(situation);
		if (burst != null)
			return burst;
		if (situation.enemiesClose() >= ENEMIES_FOR_AREAS) {
			for (Move move : AREA_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		return decideSingleTarget(situation);
	}

	/**
	 * The buffs a gladiator fights under, once in reach: Berserking whenever it is ready, then the divine power. Empyrean Blessing at 4000 DP; while it is
	 * ready the points are kept for it, whatever else they could buy. Otherwise Daevic Fury at 2000, and Explosion of Rage with what is left once neither
	 * buff is waiting for the points: the guide's order, Daevic Fury "à partir du lvl 50 si Zikel's Threat n'est pas dispo".
	 *
	 * @return The move, or null.
	 */
	static Move decideBurst(Situation situation) {
		Offence offence = situation.offence();
		if (!offence.inReach())
			return null;
		Set<Move> ready = situation.ready();
		if (!offence.berserkingUp() && ready.contains(Move.BERSERKING))
			return Move.BERSERKING;
		int dp = offence.dp();
		if (ready.contains(Move.EMPYREAN_BLESSING))
			return dp >= ZIKEL_DP ? Move.EMPYREAN_BLESSING : null;
		if (ready.contains(Move.DAEVIC_FURY))
			return dp >= ABILITY_DP ? Move.DAEVIC_FURY : null;
		if (dp >= ABILITY_DP && ready.contains(Move.EXPLOSION_OF_RAGE))
			return Move.EXPLOSION_OF_RAGE;
		return null;
	}

	/**
	 * The guide's sequence against one enemy, which is also what a gladiator turns to when its areas are spent. From afar: Charge beyond 20 m, Cleave,
	 * then Ferocious Chop within its leap. In reach: Weakening Blow, Sure Strike, Aerial Lockdown, Crushing Blow; their follow-ups are the openings
	 * above and the generic order's chains, and the filler chain is the generic order's when all of this is cooling down.
	 *
	 * @return The move, or null.
	 */
	static Move decideSingleTarget(Situation situation) {
		Offence offence = situation.offence();
		Set<Move> ready = situation.ready();
		if (!offence.inReach()) {
			if (offence.distance() > CHARGE_BEYOND && ready.contains(Move.CHARGE))
				return Move.CHARGE;
			if (ready.contains(Move.CLEAVE))
				return Move.CLEAVE;
			if (offence.distance() <= FEROCIOUS_CHOP_REACH && ready.contains(Move.FEROCIOUS_CHOP))
				return Move.FEROCIOUS_CHOP;
			return null;
		}
		for (Move move : SINGLE_TARGET_ORDER) {
			if (ready.contains(move))
				return move;
		}
		return null;
	}

	/**
	 * Chooses chain C's second link, which closes like every chain if anything else is cast first: Roiling Hack, the lifesteal, while the gladiator is at
	 * 80 % or less, Pressure Wave above that; either one when the other is cooling down, the guide's "ou si Absorption Wave en CD".
	 *
	 * @return The link, or null when the chain is not open or neither is ready.
	 */
	static Move continueTheAreaChain(Situation situation) {
		if (!situation.areaChainOpen())
			return null;
		Set<Move> ready = situation.ready();
		Move wanted = situation.hpPercent() > PRESSURE_WAVE_ABOVE_PERCENT ? Move.PRESSURE_WAVE : Move.ROILING_HACK;
		Move other = wanted == Move.PRESSURE_WAVE ? Move.ROILING_HACK : Move.PRESSURE_WAVE;
		if (ready.contains(wanted))
			return wanted;
		return ready.contains(other) ? other : null;
	}

	/**
	 * The skills that only go through on a target knocked down or held in the air, which is the class's game: those states last three seconds, so they are
	 * taken before anything that can wait. Final Strike first, the biggest and the one either state opens. Then on a target down Crippling Cut, then
	 * Springing Slice, the guide's order after Crushing Blow; on a target in the air Crashing Blow, the guide's follow-up to Aerial Lockdown.
	 *
	 * @return The move, or null when the target is in neither state or nothing for it is ready.
	 */
	static Move exploitTheOpening(Situation situation) {
		Set<Move> ready = situation.ready();
		if (!situation.targetDown() && !situation.targetAerial())
			return null;
		if (ready.contains(Move.FINAL_STRIKE))
			return Move.FINAL_STRIKE;
		if (situation.targetDown()) {
			if (ready.contains(Move.CRIPPLING_CUT))
				return Move.CRIPPLING_CUT;
			if (ready.contains(Move.SPRINGING_SLICE))
				return Move.SPRINGING_SLICE;
		}
		if (situation.targetAerial() && ready.contains(Move.CRASHING_BLOW))
			return Move.CRASHING_BLOW;
		return null;
	}

	private static boolean perform(Player bot, Creature target, Creature second, Move move) {
		if (move == null)
			return false;
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> target;
			case SECOND -> second;
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Gladiator {} casts {} at {}% health{}", bot.getName(), move, bot.getLifeStats().getHpPercentage(),
				move.aim == Aim.SECOND ? ", pulling " + second.getName() + " as a second" : "");
		return cast;
	}

	private static Set<Move> ready(Player bot) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		return ready;
	}

	private static Situation atRest(Player bot, Creature target) {
		return Situation.atRest(BotSkillManager.isUp(bot, Move.SLAUGHTER.group), groupOf(bot, target), ready(bot));
	}

	private static Situation inFight(Player bot, Creature target, boolean secondToPull) {
		Set<Move> ready = ready(bot);
		long lastControlledAt = BotCombatMemory.of(bot) instanceof BotCombatMemory memory ? memory.lastControlledAt() : 0;
		boolean controlled = BotSkillManager.isCrowdControlled(bot);
		boolean recentlyControlled = !controlled && lastControlledAt != 0 && System.currentTimeMillis() - lastControlledAt <= RECENTLY_CONTROLLED_MILLIS;
		boolean wallUp = BotSkillManager.isUp(bot, Move.WALL_OF_STEEL.group), armorUp = BotSkillManager.isUp(bot, Move.ARMOR_OF_ATTRITION.group);
		// asked only when a defensive could answer it: it is a walk over every monster the gladiator can see
		boolean attacked = (!wallUp && ready.contains(Move.WALL_OF_STEEL) || !armorUp && ready.contains(Move.ARMOR_OF_ATTRITION)) && isAimedAt(bot, target);
		boolean targetDown = target != null && target.getEffectController().isAbnormalSet(AbnormalState.STUMBLE);
		boolean targetAerial = target != null && target.getEffectController().isAbnormalSet(AbnormalState.OPENAERIAL);
		// counted only when an area move could use the answer: it is a walk over every monster the gladiator can see
		boolean areaReady = AREA_ORDER.stream().anyMatch(ready::contains);
		return new Situation(bot.getLifeStats().getHpPercentage(), controlled, recentlyControlled, BotSkillManager.isChainOpen(bot, SHOCK_CHAIN), attacked,
			targetDown, targetAerial, BotSkillManager.isUp(bot, Move.SLAUGHTER.group), wallUp, armorUp, BotSkillManager.isUp(bot, Move.UNWAVERING_DEVOTION.group), ready,
			BotSkillManager.isChainOpen(bot, AREA_CHAIN), areaReady ? enemiesClose(bot, target) : 1, offenceOf(bot, target, secondToPull), groupOf(bot, target));
	}

	private static Offence offenceOf(Player bot, Creature target, boolean secondToPull) {
		boolean chainToFollow = FOLLOWED_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		boolean inReach = target != null && BotAttackManager.isInAttackRange(bot, target);
		float distance = target == null ? 0 : (float) PositionUtil.getDistance(bot, target);
		return new Offence(bot.getCommonData().getDp(), inReach, distance, chainToFollow, BotSkillManager.isUp(bot, Move.BERSERKING.group), secondToPull);
	}

	/** @return The tank-dead mode's view of the gladiator's group: a group of six with a templar in it, every templar of it dead. */
	private static Group groupOf(Player bot, Creature target) {
		boolean defenseUp = BotSkillManager.isUp(bot, Move.DEFENSE_PREPARATION.group);
		PlayerGroup group = bot.getPlayerGroup();
		if (group == null || group.size() < FULL_GROUP)
			return new Group(false, defenseUp, false, false);
		boolean templar = false, templarStanding = false;
		for (Player member : group.getMembers()) {
			if (member.getPlayerClass() == PlayerClass.TEMPLAR) {
				templar = true;
				templarStanding |= !member.isDead();
			}
		}
		boolean tankDead = templar && !templarStanding;
		boolean targetElsewhere = tankDead && target != null && target.getTarget() != null && !bot.equals(target.getTarget());
		return new Group(tankDead, defenseUp, targetElsewhere, tankDead && BotSkillManager.isChainOpen(bot, BODY_CHAIN));
	}

	/**
	 * @return A second monster for a lone, healthy gladiator to pull with Cleave, or null. Only while a single monster is on it, so that it never takes on
	 *         more than {@value #MOST_PULLED}; only a normal monster it would have picked for itself (its level, nobody else's, in sight), within Cleave's
	 *         reach, and with no other monster beside it that is not yet fighting, or the pull would bring its pack.
	 */
	private static Npc secondToPull(Player bot, Creature target) {
		// the cheap questions first: the walk below is over every monster in sight, and a pull is only made once the first monster is in reach
		if (bot.getCurrentTeam() != null || target == null || bot.getLifeStats().getHpPercentage() <= PULL_ABOVE_PERCENT
			|| !BotAttackManager.isInAttackRange(bot, target) || !BotSkillManager.isReady(bot, Move.PULL_SECOND.group))
			return null;
		if (target instanceof Npc npc && BotTargetSelector.needsAGroup(npc.getObjectTemplate()))
			return null;
		List<Npc> fighting = new ArrayList<>(), candidates = new ArrayList<>();
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !bot.isEnemy(npc))
				return;
			if (npc.equals(target) || npc.getAggroList().isHating(bot))
				fighting.add(npc);
			else if (BotTargetSelector.isFairGame(bot, npc, PULL_REACH))
				candidates.add(npc);
		});
		if (fighting.size() >= MOST_PULLED)
			return null;
		for (Npc candidate : candidates) {
			if (candidates.stream().noneMatch(other -> !other.equals(candidate) && PositionUtil.isInRange(candidate, other, PACK_RADIUS, false)))
				return candidate;
		}
		return null;
	}

	/** @return How many enemies fighting the gladiator or its group stand within {@link #AREA_REACH} of it, its target counted whatever it hates. */
	private static int enemiesClose(Player bot, Creature target) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		List<Player> fighting = team == null ? List.of(bot) : team.getMembers();
		int[] count = { 0 };
		bot.getKnownList().forEachNpc(npc -> {
			if (!npc.isDead() && bot.isEnemy(npc) && PositionUtil.isInRange(bot, npc, AREA_REACH, false)
				&& (npc.equals(target) || fighting.stream().anyMatch(member -> npc.getAggroList().isHating(member))))
				count[0]++;
		});
		return count[0];
	}

	/** @return true if the target, or another monster the gladiator can see, has it as its target. */
	private static boolean isAimedAt(Player bot, Creature target) {
		if (target != null && bot.equals(target.getTarget()))
			return true;
		boolean[] aimed = { false };
		bot.getKnownList().forEachNpc(npc -> {
			if (!aimed[0] && !npc.isDead() && bot.equals(npc.getTarget()) && bot.isEnemy(npc))
				aimed[0] = true;
		});
		return aimed[0];
	}
}
