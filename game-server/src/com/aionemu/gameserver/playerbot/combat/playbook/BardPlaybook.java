package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.playerbot.combat.BotCombatMemory;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.CleanseReach;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a bard plays. The first playbook written without a guide: every rule comes from the skill data or is the module's own judgement of how the class
 * is played, and the plan says which ({@code docs/classes/plans/bard-plan.md}).
 * <p>
 * A ranged caster with a harp that heals by chains, keeps its group's mana and resistances up and deals damage by chains. The damage chains themselves
 * (A, C, D, and E after its charged opener) are left to the generic order, which carries an open chain on before anything else; the playbook decides
 * everything else: the stance, the upkeep, every heal and cleanse, the mana, the defences, the controls, the charged songs and the divine power.
 */
final class BardPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(BardPlaybook.class);

	/**
	 * What a bard never casts: Rejuvenating Aria raises the speed of an auto attack the bard never makes, and Inspiration is a fifteen second burst of the
	 * group's run speed that the generic buffs would cast every half minute for nobody. Claimed, so the generic order leaves them alone.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("BA_EXCITINGMELODY", "BA_SONGOFADVANCE");
	/** The regular stigmas (levels 20, 30, 40): Hymn of Rejuvenation, Resonant Hymn, Freestyle. The module's judgement, not a guide's. */
	static final List<String> REGULAR_STIGMAS = List.of("BA_SONGOFREGENERATION", "BA_SONGOFSERENITY", "BA_LULLABY");
	/**
	 * The advanced ones (45 and 55): Soothing Hymn, Chorus of Blessing, and Exultation, the healing stance. The stances are stigmas, so a bard has the one
	 * it wears and keeps it up.
	 */
	static final List<String> ADVANCED_STIGMAS = List.of("BA_SONGOFMASSHEAL", "BA_SONGOFBLESS", "BA_PLAYINGSTYLESCHANGEA");

	/** The chain categories the rules read, as the data names them. */
	static final String HEAL_CHAIN_1 = "A_CHAINB_1TH", HEAL_CHAIN_2 = "A_CHAINB_2TH", MANA_CHAIN = "A_CHAINF_1TH", SHOCK_CHAIN = "ALL_CHAINA_1TH";
	/** Every chain a bard opens: a stance is not switched while one is open, since any other cast closes it. */
	static final List<String> OPEN_CHAINS = List.of("A_CHAINA_1TH", "A_CHAINA_2TH", HEAL_CHAIN_1, HEAL_CHAIN_2, "A_CHAINC_1TH", "A_CHAINC_2TH",
		"A_CHAIND_1TH", "A_CHAINE_1TH", MANA_CHAIN, SHOCK_CHAIN);
	/**
	 * The damage chains the generic order carries on, every link with one after it: no song of the playbook's offence is begun while one is open, since it
	 * would close it. The playbook runs before the generic order on every tick, so without this the chain was closed by the next cast it chose.
	 */
	static final List<String> DAMAGE_CHAINS = List.of("A_CHAINA_1TH", "A_CHAINA_2TH", "A_CHAINC_1TH", "A_CHAINC_2TH", "A_CHAIND_1TH", "A_CHAINE_1TH");

	/**
	 * Where the heals of a bard that is its group's healer go: chain B under 70 %, Soothing Hymn under 40 %, Chorus of Blessing with three under 70 %, the
	 * heal over time on the tank under 80 %.
	 */
	static final Bars HEALER = new Bars(70, 40, 70, 80);
	/** And of one that seconds a cleric: only what the cleric has not got to. */
	static final Bars SECOND = new Bars(50, 30, 50, 60);
	/** The two bars Chorus of Blessing counts the group under, which {@link Situation} counts for it. */
	static final int HURT_BAR_HIGH = 70, HURT_BAR_LOW = 50;
	/** Health under which a bard alone heals itself in a fight, and out of one. */
	static final int SOLO_FIGHT_HEAL_BELOW = 60, SOLO_REST_HEAL_BELOW = 80;
	/** Health under which a heal chain already open is carried on: a link spent on somebody whole is mana spent on nothing. */
	static final int CHAIN_ON_BELOW = 90;
	/** How many of the group must be under the bar for Chorus of Blessing, the group heal. */
	static final int BLESSING_HURT_NEEDED = 3;
	/** How many must carry a physical weakening for Purging Paean, the group's cleanse, rather than Purifying Paean on one. */
	static final int GROUP_CLEANSE_NEEDED = 2;
	/** Mana under which the bard opens chain F on itself, and members under which Resonant Hymn, the group's mana, is worth the second link. */
	static final int MANA_BELOW = 50;
	/** Mana under which a healer of the group is given Resonant Counterpoint. */
	static final int HEALER_MANA_BELOW = 30;
	/** Health under which Mvt. 4: Winter, the emergency shield, is raised. */
	static final int WINTER_BELOW = 30;
	/** Health under which Hymn of Thanksgiving follows Remove Shock rather than Chorus of Fortitude. */
	static final int THANKSGIVING_BELOW = 70;
	/** Health under which a lone bard sleeps everything round it with Requiem, and how many enemies within its 7 m that takes. */
	static final int REQUIEM_BELOW = 50, REQUIEM_ENEMIES = 2;
	static final float REQUIEM_REACH = 7f;
	/** Health under which a lone bard leaves. */
	static final int FLEE_BELOW = 25;
	/** What Minstrel's Flair and Symphony of Destruction each cost, and what Aria of Hope costs. */
	static final int ABILITY_DP = 2000, ARIA_DP = 4000;
	/** How many of the group under 30 % make Aria of Hope worth its divine power. */
	static final int ARIA_HURT_NEEDED = 2, ARIA_BELOW = 30;
	/** How far Cherubim Call reaches, the {@code first_target_range} the data gives it. */
	static final float RESURRECTION_RANGE = 25f;
	/** How far a bard looks for what is hitting it: its own reach. */
	static final float ENEMY_RADIUS = 25f;
	/** How long after a stun the bard raises Mvt. 1: Spring against the next one. */
	static final long JUST_CONTROLLED_MILLIS = 5_000;

	/** The bars of one way of healing, in percent of health. */
	record Bars(int chain, int hymn, int blessing, int rejuvenation) {
	}

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET,
		/** An enemy engaged and not held, which the controls are for. */
		SECOND,
		/** The most hurt of the group, the bard included. */
		HURT,
		/** The group's tank. */
		TANK,
		/** A member carrying a physical weakening. */
		DEBUFFED,
		/** A dead member this bard is the one to raise. */
		DEAD,
		/** A healer of the group short of mana. */
		HEALER_LOW_ON_MANA
	}

	/** What the playbook may cast; claimed, kept from the generic order, unless it says otherwise. */
	enum Move {
		// the stances, and the upkeep
		EXULTATION("BA_PLAYINGSTYLESCHANGEA", Aim.SELF),
		IMPASSION("BA_PLAYINGSTYLESCHANGEB", Aim.SELF),
		/** Mvt. 2: Summer: fear, sleep and paralysis resisted for half an hour. */
		SUMMER("BA_SONGOFBRAVE", Aim.SELF),
		/** Mvt. 3: Autumn: silence and stagger resisted for half an hour. */
		AUTUMN("BA_SONGOFFIRM", Aim.SELF),
		PROTECTIVE_ODE("AR_SONGOFSHIELD", Aim.SELF),
		/** An hour of 15 % more health, on each member: cast by the upkeep, which walks the group. */
		ETUDE("BA_SONGOFSTAMINA", Aim.SELF),
		// heals
		SOOTHING_MELODY("AR_SONGOFHEAL", Aim.HURT),
		SOOTHING_COUNTERPOINT("BA_SONGOFEMERGENTHEAL", Aim.HURT),
		SOOTHING_FINALE("BA_SONGOFGREATHEAL", Aim.HURT),
		JOYOUS_CAROL("BA_SONGOFWARMTH", Aim.HURT),
		/** Charged for five seconds: the big heal, never begun while something hits the bard. */
		SOOTHING_HYMN("BA_SONGOFMASSHEAL", Aim.HURT),
		HYMN_OF_REJUVENATION("BA_SONGOFREGENERATION", Aim.TANK),
		/** The group healed and cleansed. */
		CHORUS_OF_BLESSING("BA_SONGOFBLESS", Aim.SELF),
		CHERUBIM_CALL("BA_SONGOFRESURRECTION", Aim.DEAD),
		// cleanses, physical weakening only
		PURIFYING_PAEAN("BA_SONGOFPURIFY", Aim.DEBUFFED),
		PURGING_PAEAN("BA_MASSDISPEL", Aim.SELF),
		// mana
		RESONATING_MELODY("AR_SONGOFRELAX", Aim.SELF),
		/** Chain F's second link: the group's mana within 25 m. */
		RESONANT_HYMN("BA_SONGOFSERENITY", Aim.SELF),
		RESONANT_COUNTERPOINT("BA_SONGOFREFRESHMIND", Aim.HEALER_LOW_ON_MANA),
		// defence
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		HYMN_OF_THANKSGIVING("BA_SONGOFAPPRECIATION", Aim.SELF),
		CHORUS_OF_FORTITUDE("BA_SONGOFRESISTANCE", Aim.TARGET),
		/** Mvt. 1: Spring: twelve seconds of stun, stumble and spin resisted. */
		SPRING("BA_FOCUSDEFENCE", Aim.SELF),
		/** Mvt. 4: Winter: a shield, a heal and mana, the emergency. */
		WINTER("BA_SANCTUARY", Aim.SELF),
		/** Lowers the bard's hate on the target. */
		ODE_TO_FRIENDSHIP("BA_SONGOGPEACE", Aim.TARGET),
		/** A random jump and a dispel: cast in the flight only. */
		SONICPORTATION("BA_SONGOFDIMENSIONDOOR", Aim.SELF),
		// control
		FREESTYLE("BA_LULLABY", Aim.SECOND),
		CAPTIVATE("AR_SONGOFROOT", Aim.SECOND),
		STAGGERED_REST("BA_DELAYPARALYZE", Aim.SECOND),
		COMPOSERS_BAR("BA_SONGOFBIND", Aim.SECOND),
		/** A sleep on six within 7 m round the bard. */
		REQUIEM("AR_SONGOFREST", Aim.SELF),
		/** Damage and a silence: on a caster first, and otherwise one of the generic order's attacks. */
		QUAVER("BA_SONGOFSOULEXTINCTION", Aim.TARGET, false),
		/** Strips a buff from the target: against a player. */
		HALF_STOP("BA_SONGOFMANAREVERSE", Aim.TARGET),
		/** The target's attack lowered, once it has turned on the bard. */
		DELUSIONAL_DIRGE("BA_SONGOFILLUSION", Aim.TARGET),
		// the charged songs, five seconds each, never begun under attack
		FIERY_REQUIEM("BA_REQUIEM", Aim.TARGET),
		BLAZING_REQUIEM("BA_ILLUSIONREQUIEM", Aim.TARGET),
		DRAGON_SONG("BA_SONGOFMENTALICBLASTER", Aim.TARGET),
		HARPISTS_POD("BA_SONGOFGRAVITY", Aim.TARGET),
		/** Opens chain E, which the generic order then carries on with Sonic Splash. */
		FIERY_DESCANT("AR_SONGOFBATTLE", Aim.TARGET),
		// divine power
		MINSTRELS_FLAIR("BA_SONGOFANGER", Aim.TARGET),
		SYMPHONY_OF_DESTRUCTION("BA_SONGOFDESTROY", Aim.SELF),
		ARIA_OF_HOPE("BA_SONGOFHOPE", Aim.SELF);

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

	/** The controls, best first: the longest hold first, Freestyle's fifteen seconds of sleep. */
	static final List<Move> CONTROL_ORDER = List.of(Move.FREESTYLE, Move.CAPTIVATE, Move.STAGGERED_REST, Move.COMPOSERS_BAR);
	/** The charged songs, strongest first. */
	static final List<Move> CHARGED_ORDER = List.of(Move.FIERY_REQUIEM, Move.BLAZING_REQUIEM, Move.DRAGON_SONG, Move.HARPISTS_POD, Move.FIERY_DESCANT);

	/** Everything the decisions look at, filled field by field: there are too many for a constructor to read well, and a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		/** Which stances the bard knows: they are stigmas, and it may wear one, both or neither. */
		boolean knowsExultation = true, knowsImpassion = true;
		int hp = 100, mp = 100, dp;
		boolean inTeam, inFight, controlled, justControlled, clericInGroup;
		boolean exultationUp, impassionUp, summerUp = true, autumnUp = true, odeUp = true, springUp, winterUp;
		/** The chains: B's first and second links, F's first, Remove Shock's, and whether any chain at all is open. */
		boolean healChain1Open, healChain2Open, manaChainOpen, shockChainOpen, anyChainOpen, damageChainOpen;
		/** The most hurt of the group, the bard included, or 100 when nobody is hurt; how many are under 70, 50 and 30. */
		int hurtHp = 100, hurtUnder70, hurtUnder50, hurtUnder30;
		/** The tank's health, or -1 without one; whether it carries the heal over time already, or alone whether the bard does. */
		int tankHp = -1;
		boolean tankHasRejuvenation;
		/** Physical weakening: on the bard, and how many of the group carry one. */
		boolean selfDebuffed;
		int debuffedCount;
		/** Members other than the bard under the mana bar, and whether a healer of the group is nearly dry. */
		int membersLowOnMana;
		boolean healerLowOnMana;
		boolean mateToRaise;
		/** Whether something is hitting the bard, which a charged song does not survive. */
		boolean assailed;
		/** The target: on the bard, not yet turned on anybody, a caster, a player, an elite or a boss, carrying Delusional Dirge already. */
		boolean targetOnMe, targetIdle, targetCasts, targetIsPlayer, targetElite, dirgeOnTarget;
		boolean secondToControl, severalEnemies;
		int enemiesWithin7m;

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
			s.knowsExultation = knowsExultation;
			s.knowsImpassion = knowsImpassion;
			s.hp = hp;
			s.mp = mp;
			s.dp = dp;
			s.inTeam = inTeam;
			s.inFight = inFight;
			s.controlled = controlled;
			s.justControlled = justControlled;
			s.clericInGroup = clericInGroup;
			s.exultationUp = exultationUp;
			s.impassionUp = impassionUp;
			s.summerUp = summerUp;
			s.autumnUp = autumnUp;
			s.odeUp = odeUp;
			s.springUp = springUp;
			s.winterUp = winterUp;
			s.healChain1Open = healChain1Open;
			s.healChain2Open = healChain2Open;
			s.manaChainOpen = manaChainOpen;
			s.shockChainOpen = shockChainOpen;
			s.anyChainOpen = anyChainOpen;
			s.damageChainOpen = damageChainOpen;
			s.hurtHp = hurtHp;
			s.hurtUnder70 = hurtUnder70;
			s.hurtUnder50 = hurtUnder50;
			s.hurtUnder30 = hurtUnder30;
			s.tankHp = tankHp;
			s.tankHasRejuvenation = tankHasRejuvenation;
			s.selfDebuffed = selfDebuffed;
			s.debuffedCount = debuffedCount;
			s.membersLowOnMana = membersLowOnMana;
			s.healerLowOnMana = healerLowOnMana;
			s.mateToRaise = mateToRaise;
			s.assailed = assailed;
			s.targetOnMe = targetOnMe;
			s.targetIdle = targetIdle;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetElite = targetElite;
			s.dirgeOnTarget = dirgeOnTarget;
			s.secondToControl = secondToControl;
			s.severalEnemies = severalEnemies;
			s.enemiesWithin7m = enemiesWithin7m;
			return s;
		}
	}

	/** The people a move may be aimed at, which the situation only says exist. Kept apart so the decisions can be made without a world. */
	record Targets(Creature target, Creature second, Player hurt, Player tank, Player debuffed, Player dead, Player healerLowOnMana) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the bard alone decides on this skill. Takes the template so that every template in the game can be put through it. */
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
		return false; // a harp is played, not swung: with nothing to cast the bard waits for its next song
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && bot.getLifeStats().getHpPercentage() < FLEE_BELOW;
	}

	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.SONICPORTATION.group))
			log.debug("Bard {} casts {} as it flees", bot.getName(), Move.SONICPORTATION);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public boolean prepare(Player bot) {
		Reading reading = readingOf(bot, null, false);
		if (perform(bot, reading, decideUpkeep(reading.situation())))
			return true;
		return etudeForTheGroup(bot, reading.situation());
	}

	@Override
	public boolean tend(Player bot) {
		return heal(bot);
	}

	@Override
	public boolean heal(Player bot) {
		Reading reading = readingOf(bot, null, false);
		return performFirst(bot, reading, BardPlaybook::decideSupport);
	}

	/** Its defences, then its heals and the group's, then the fight: a support's heals come before its damage. */
	@Override
	public boolean act(Player bot, Creature target) {
		Reading reading = readingOf(bot, target, true);
		return performFirst(bot, reading, BardPlaybook::decideDefence) || performFirst(bot, reading, BardPlaybook::decideSupport)
			|| performFirst(bot, reading, BardPlaybook::decideOffence);
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/**
	 * @return The stance the bard should be in, or null when it knows neither: healing in a group with no living cleric near, damage otherwise. Not on a
	 *         member's health: a stance that followed it would be switched every twelve seconds of a hard fight, a cast spent each time.
	 */
	static Move wantedStance(Situation s) {
		boolean heal = s.inTeam && !s.clericInGroup;
		Move wanted = heal ? Move.EXULTATION : Move.IMPASSION;
		if (knows(s, wanted))
			return wanted;
		Move other = wanted == Move.EXULTATION ? Move.IMPASSION : Move.EXULTATION;
		return knows(s, other) ? other : null;
	}

	private static boolean knows(Situation s, Move stance) {
		return stance == Move.EXULTATION ? s.knowsExultation : s.knowsImpassion;
	}

	private static boolean isUp(Situation s, Move stance) {
		return stance == Move.EXULTATION ? s.exultationUp : s.impassionUp;
	}

	/** @return The stance to turn on now, or null: it is already the right one, or a chain is open, which any other cast would close. */
	static Move stanceToTurnOn(Situation s) {
		Move stance = wantedStance(s);
		if (stance == null || isUp(s, stance) || s.anyChainOpen || !s.ready.contains(stance))
			return null;
		return stance;
	}

	/** Before a fight: the stance, the two half hour movements, the shield. Etude is cast by the upkeep, which walks the group. */
	static Move decideUpkeep(Situation s) {
		Move stance = stanceToTurnOn(s);
		if (stance != null)
			return stance;
		if (!s.summerUp && s.ready.contains(Move.SUMMER))
			return Move.SUMMER;
		if (!s.autumnUp && s.ready.contains(Move.AUTUMN))
			return Move.AUTUMN;
		if (!s.odeUp && s.ready.contains(Move.PROTECTIVE_ODE))
			return Move.PROTECTIVE_ODE;
		return null;
	}

	/** What the bard does about being held, hit and nearly dead: asked first in a fight, since a held bard can cast nothing else. */
	static Move decideDefence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.shockChainOpen) {
			if (s.hp < THANKSGIVING_BELOW && ready.contains(Move.HYMN_OF_THANKSGIVING))
				return Move.HYMN_OF_THANKSGIVING;
			if (ready.contains(Move.CHORUS_OF_FORTITUDE))
				return Move.CHORUS_OF_FORTITUDE;
		}
		if (s.justControlled && !s.springUp && ready.contains(Move.SPRING))
			return Move.SPRING;
		if (s.hp < WINTER_BELOW && !s.winterUp && ready.contains(Move.WINTER))
			return Move.WINTER;
		if (!s.inTeam && s.hp < REQUIEM_BELOW && s.enemiesWithin7m >= REQUIEM_ENEMIES && ready.contains(Move.REQUIEM))
			return Move.REQUIEM;
		if (s.inTeam && s.targetOnMe && ready.contains(Move.ODE_TO_FRIENDSHIP))
			return Move.ODE_TO_FRIENDSHIP;
		return null;
	}

	/**
	 * The heals, the resurrection, the cleanses and the mana, in a fight and out of one. A heal chain already open is carried on first, since it closes in three
	 * seconds and with any other cast.
	 */
	static Move decideSupport(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return null;
		if (s.healChain2Open && s.hurtHp < CHAIN_ON_BELOW) {
			if (ready.contains(Move.SOOTHING_FINALE))
				return Move.SOOTHING_FINALE;
			if (ready.contains(Move.JOYOUS_CAROL))
				return Move.JOYOUS_CAROL;
		}
		if (s.healChain1Open && s.hurtHp < CHAIN_ON_BELOW && ready.contains(Move.SOOTHING_COUNTERPOINT))
			return Move.SOOTHING_COUNTERPOINT;
		if (s.manaChainOpen && (s.mp < MANA_BELOW || s.membersLowOnMana > 0) && ready.contains(Move.RESONANT_HYMN))
			return Move.RESONANT_HYMN;
		Move heal = s.inTeam ? groupHeal(s) : soloHeal(s);
		if (heal != null)
			return heal;
		if (!s.inFight && s.mateToRaise && ready.contains(Move.CHERUBIM_CALL))
			return Move.CHERUBIM_CALL;
		if (s.debuffedCount >= GROUP_CLEANSE_NEEDED && ready.contains(Move.PURGING_PAEAN))
			return Move.PURGING_PAEAN;
		if (s.debuffedCount > 0 && ready.contains(Move.PURIFYING_PAEAN))
			return Move.PURIFYING_PAEAN;
		if ((s.mp < MANA_BELOW || s.membersLowOnMana >= 2) && ready.contains(Move.RESONATING_MELODY))
			return Move.RESONATING_MELODY;
		if (s.healerLowOnMana && ready.contains(Move.RESONANT_COUNTERPOINT))
			return Move.RESONANT_COUNTERPOINT;
		return null;
	}

	private static Move groupHeal(Situation s) {
		Set<Move> ready = s.ready;
		Bars bars = s.clericInGroup ? SECOND : HEALER;
		int hurtForBlessing = bars.blessing() == HURT_BAR_HIGH ? s.hurtUnder70 : s.hurtUnder50;
		if (hurtForBlessing >= BLESSING_HURT_NEEDED && ready.contains(Move.CHORUS_OF_BLESSING))
			return Move.CHORUS_OF_BLESSING;
		if (s.hurtHp < bars.hymn() && !s.assailed && ready.contains(Move.SOOTHING_HYMN))
			return Move.SOOTHING_HYMN;
		if (s.hurtHp < bars.chain() && ready.contains(Move.SOOTHING_MELODY))
			return Move.SOOTHING_MELODY;
		if (s.tankHp >= 0 && s.tankHp < bars.rejuvenation() && !s.tankHasRejuvenation && ready.contains(Move.HYMN_OF_REJUVENATION))
			return Move.HYMN_OF_REJUVENATION;
		return null;
	}

	/** A bard alone heals itself: chain B, or the heal over time when the chain is cooling down. */
	private static Move soloHeal(Situation s) {
		if (s.hp >= (s.inFight ? SOLO_FIGHT_HEAL_BELOW : SOLO_REST_HEAL_BELOW))
			return null;
		if (s.ready.contains(Move.SOOTHING_MELODY))
			return Move.SOOTHING_MELODY;
		if (!s.inFight && !s.assailed && s.ready.contains(Move.SOOTHING_HYMN))
			return Move.SOOTHING_HYMN;
		return !s.tankHasRejuvenation && s.ready.contains(Move.HYMN_OF_REJUVENATION) ? Move.HYMN_OF_REJUVENATION : null;
	}

	/**
	 * The fight once the bard and its group are looked after: the stance and the shield, the divine power, the controls, the songs aimed at what the target
	 * is, then the charged songs while nothing hits the bard. The damage chains are the generic order's, after this answers null.
	 */
	static Move decideOffence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled || s.damageChainOpen)
			return null;
		Move stance = stanceToTurnOn(s);
		if (stance != null)
			return stance;
		if (!s.odeUp && ready.contains(Move.PROTECTIVE_ODE))
			return Move.PROTECTIVE_ODE;
		Move divine = decideDivinePower(s);
		if (divine != null)
			return divine;
		if (s.secondToControl && (s.inTeam || s.severalEnemies)) {
			for (Move move : CONTROL_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.targetCasts && ready.contains(Move.QUAVER))
			return Move.QUAVER;
		if (s.targetIsPlayer && ready.contains(Move.HALF_STOP))
			return Move.HALF_STOP;
		if (s.targetOnMe && !s.dirgeOnTarget && ready.contains(Move.DELUSIONAL_DIRGE))
			return Move.DELUSIONAL_DIRGE;
		// five seconds of charge that a hit cancels: alone, the opener while the target has not noticed it; otherwise only while nothing hits the bard
		if (!s.inTeam && s.targetIdle || !s.assailed) {
			for (Move move : CHARGED_ORDER) {
				if (ready.contains(move))
					return move;
			}
		}
		return null;
	}

	/**
	 * Aria of Hope when two of the group are nearly dead; Symphony of Destruction on an elite or a boss with the group; Minstrel's Flair otherwise, keeping
	 * in a group what Aria of Hope costs.
	 */
	static Move decideDivinePower(Situation s) {
		Set<Move> ready = s.ready;
		if (s.inTeam && s.hurtUnder30 >= ARIA_HURT_NEEDED && s.dp >= ARIA_DP && ready.contains(Move.ARIA_OF_HOPE))
			return Move.ARIA_OF_HOPE;
		if (s.inTeam && s.targetElite && s.dp >= ABILITY_DP && ready.contains(Move.SYMPHONY_OF_DESTRUCTION))
			return Move.SYMPHONY_OF_DESTRUCTION;
		int kept = s.inTeam && ready.contains(Move.ARIA_OF_HOPE) ? ARIA_DP : 0;
		if (s.dp >= ABILITY_DP + kept && ready.contains(Move.MINSTRELS_FLAIR))
			return Move.MINSTRELS_FLAIR;
		return null;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// Casting
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** What the bot saw: the facts the decisions weigh, and the people they may be about. */
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
		if (move == null)
			return false;
		Targets targets = reading.targets();
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> targets.target();
			case SECOND -> targets.second();
			case HURT -> targets.hurt();
			case TANK -> targets.tank() != null ? targets.tank() : reading.situation().inTeam ? null : bot;
			case DEBUFFED -> targets.debuffed();
			case DEAD -> targets.dead();
			case HEALER_LOW_ON_MANA -> targets.healerLowOnMana();
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Bard {} casts {} at {} ({}% health, {}% mana)", bot.getName(), move, aimedAt.getName(), reading.situation().hp,
				reading.situation().mp);
		return cast;
	}

	/** Etude on the bard and every member near it that lacks it: an hour of health, cast before a fight. Not a pure decision, since it walks the group. */
	private static boolean etudeForTheGroup(Player bot, Situation s) {
		if (!s.ready.contains(Move.ETUDE))
			return false;
		List<Player> everybody = new ArrayList<>();
		everybody.add(bot);
		everybody.addAll(BotGroupManager.membersToTendTo(bot));
		for (Player member : everybody) {
			if (!BotSkillManager.isCovered(bot, member, Move.ETUDE.group) && BotSkillManager.tryCastGroup(bot, member, Move.ETUDE.group)) {
				log.debug("Bard {} casts {} on {}", bot.getName(), Move.ETUDE, member.getName());
				return true;
			}
		}
		return false;
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
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		s.inTeam = team != null;
		s.inFight = fighting || bot.getAi() != null && bot.getAi().isInState(AIState.FIGHT)
			|| s.inTeam && BotGroupManager.enemiesFightingTheGroupWithin(bot, ENEMY_RADIUS) > 0;
		s.hp = bot.getLifeStats().getHpPercentage();
		s.mp = bot.getLifeStats().getMpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.knowsExultation = BotSkillManager.knows(bot, Move.EXULTATION.group);
		s.knowsImpassion = BotSkillManager.knows(bot, Move.IMPASSION.group);
		s.exultationUp = BotSkillManager.isUp(bot, Move.EXULTATION.group);
		s.impassionUp = BotSkillManager.isUp(bot, Move.IMPASSION.group);
		s.summerUp = BotSkillManager.isUp(bot, Move.SUMMER.group);
		s.autumnUp = BotSkillManager.isUp(bot, Move.AUTUMN.group);
		s.odeUp = BotSkillManager.isUp(bot, Move.PROTECTIVE_ODE.group);
		s.springUp = BotSkillManager.isUp(bot, Move.SPRING.group);
		s.winterUp = BotSkillManager.isUp(bot, Move.WINTER.group);
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		BotCombatMemory memory = BotCombatMemory.of(bot);
		s.justControlled = memory != null && !s.controlled && System.currentTimeMillis() - memory.lastControlledAt() < JUST_CONTROLLED_MILLIS;
		s.healChain1Open = BotSkillManager.isChainOpen(bot, HEAL_CHAIN_1);
		s.healChain2Open = BotSkillManager.isChainOpen(bot, HEAL_CHAIN_2);
		s.manaChainOpen = BotSkillManager.isChainOpen(bot, MANA_CHAIN);
		s.shockChainOpen = BotSkillManager.isChainOpen(bot, SHOCK_CHAIN);
		s.anyChainOpen = OPEN_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		s.damageChainOpen = DAMAGE_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));

		// the group: who is hurt, the tank, who carries a physical weakening, who is short of mana
		boolean cleanses = s.ready.contains(Move.PURIFYING_PAEAN) || s.ready.contains(Move.PURGING_PAEAN);
		Player hurt = bot, tank = null, debuffed = null, healerLowOnMana = null;
		s.hurtHp = s.hp;
		countHurt(s, s.hp);
		s.selfDebuffed = cleanses && BotSkillManager.hasDispellableDebuff(bot, 1, CleanseReach.PHYSICAL);
		if (s.selfDebuffed) {
			s.debuffedCount++;
			debuffed = bot;
		}
		Player groupTank = BotRole.groupTank(bot.getCurrentTeam());
		for (Player mate : BotGroupManager.membersToTendTo(bot)) {
			int mateHp = mate.getLifeStats().getHpPercentage();
			countHurt(s, mateHp);
			if (mateHp < s.hurtHp) {
				s.hurtHp = mateHp;
				hurt = mate;
			}
			if (mate.getPlayerClass() == PlayerClass.CLERIC)
				s.clericInGroup = true;
			BotRole role = BotRole.of(mate);
			if (mate.equals(groupTank)) {
				tank = mate;
				s.tankHp = mateHp;
				s.tankHasRejuvenation = BotSkillManager.isCovered(bot, mate, Move.HYMN_OF_REJUVENATION.group);
			}
			int mateMp = mate.getLifeStats().getMpPercentage();
			if (mateMp < MANA_BELOW)
				s.membersLowOnMana++;
			if (role == BotRole.HEALER && mateMp < HEALER_MANA_BELOW && healerLowOnMana == null)
				healerLowOnMana = mate;
			if (cleanses && BotSkillManager.hasDispellableDebuff(mate, 1, CleanseReach.PHYSICAL)) {
				s.debuffedCount++;
				if (debuffed == null)
					debuffed = mate;
			}
		}
		s.healerLowOnMana = healerLowOnMana != null;
		if (!s.inTeam)
			s.tankHasRejuvenation = BotSkillManager.isCovered(bot, bot, Move.HYMN_OF_REJUVENATION.group);
		Player dead = s.inTeam && s.ready.contains(Move.CHERUBIM_CALL) ? BotGroupManager.mateToResurrect(bot, RESURRECTION_RANGE) : null;
		s.mateToRaise = dead != null;
		s.assailed = BotGroupManager.enemyHitting(bot, ENEMY_RADIUS) != null;

		// the target, and what else is fighting
		Creature second = null;
		if (target != null) {
			s.targetOnMe = bot.equals(target.getTarget());
			s.targetIdle = target instanceof Npc && !target.getAggroList().isHating(bot); // a player keeps no aggro list to read
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			s.targetElite = target instanceof Npc npc && isEliteOrBoss(npc.getObjectTemplate().getRating());
			s.dirgeOnTarget = BotSkillManager.isOnTarget(bot, target, Move.DELUSIONAL_DIRGE.group);
			second = SorcererPlaybook.secondToControl(bot, target);
			s.secondToControl = second != null;
			int[] engaged = { 0, 0 };
			bot.getKnownList().forEachNpc(npc -> {
				if (npc.isDead() || !bot.isEnemy(npc) || !npc.getAggroList().isHating(bot) && !npc.equals(target))
					return;
				engaged[0]++;
				if (PositionUtil.getDistance(bot, npc) <= REQUIEM_REACH)
					engaged[1]++;
			});
			s.severalEnemies = engaged[0] > 1;
			s.enemiesWithin7m = engaged[1];
		}
		return new Reading(s, new Targets(target, second, hurt, tank, debuffed, dead, healerLowOnMana));
	}

	private static void countHurt(Situation s, int hp) {
		if (hp < HURT_BAR_HIGH)
			s.hurtUnder70++;
		if (hp < HURT_BAR_LOW)
			s.hurtUnder50++;
		if (hp < ARIA_BELOW)
			s.hurtUnder30++;
	}

	static boolean isEliteOrBoss(NpcRating rating) {
		return rating == NpcRating.ELITE || rating == NpcRating.HERO || rating == NpcRating.LEGENDARY;
	}
}
