package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotCombatMemory;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * How a chanter plays, after the guide's "Aède 101". The plan, milestone by milestone, is {@code docs/classes/chanter-plan.md}.
 * <p>
 * Built so far: the seam (milestone 1), the mantras in the guide's order (2), the buffs that are the chanter's own to time (3): Protective Ward,
 * Block Curtain, Perfect Parry with Parrying Strike, Ascension Spell, Unstoppable, Swiftwing and the dispel, and the rationing of its heals (4): none for
 * a group, Stamina Restoration for itself, and alone a ladder from Healing Light to the critical sequence, and the resurrection of a mate (5), and the divine power (6): Divine Protection and Word of Wind, with Smite, Binding Word, Tremor and Stilling Word
 * never cast, and the stigmas the guide lists (8). The buffs for the group, Protectorate's Prayer
 * and its replacement, stay with the generic order, which already puts them on a mate that lacks them and knows that the two share an effect.
 */
final class ChanterPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(ChanterPlaybook.class);

	/** The six mantras, in the order they are learnt: Celerity, Shield, Revival, Wind, Invincibility, Hit. Their order of preference is {@link #SOLO_MANTRAS} and {@link #GROUP_MANTRAS}. */
	static final List<String> MANTRA_GROUPS = List.of("CH_CHANT_IMPROVEDMOVE", "CH_CHANT_IMPROVEDDEFEND", "CH_CHANT_REGENERATION", "CH_CHANT_WINDYWINGS",
		"CH_CHANT_INVINCIBLE", "CH_CHANT_IMPROVEDCRITICAL");
	/**
	 * The guide's order for a chanter alone: Hit, Invincibility, Revival, Celerity, Shield, Wind. Only three can run at once, so the order decides which
	 * three, and a chanter picks the first three it has learnt.
	 */
	static final List<String> SOLO_MANTRAS = List.of("CH_CHANT_IMPROVEDCRITICAL", "CH_CHANT_INVINCIBLE", "CH_CHANT_REGENERATION", "CH_CHANT_IMPROVEDMOVE",
		"CH_CHANT_IMPROVEDDEFEND", "CH_CHANT_WINDYWINGS");
	/** The same in a group, where Celerity, which the mates share, comes before Revival, which is the chanter's own healing. */
	static final List<String> GROUP_MANTRAS = List.of("CH_CHANT_IMPROVEDCRITICAL", "CH_CHANT_INVINCIBLE", "CH_CHANT_IMPROVEDMOVE", "CH_CHANT_REGENERATION",
		"CH_CHANT_IMPROVEDDEFEND", "CH_CHANT_WINDYWINGS");
	/**
	 * What a chanter never casts: the guide's Smite, Binding Word, Tremor and Stilling Word, and Healing Burst, an advanced stigma that heals a mate and so
	 * goes against a guide that has the chanter heal nobody in a group. Claimed, so that the generic order never takes them for what they look like.
	 */
	static final List<String> FORBIDDEN_GROUPS = List.of("CL_SMITE", "CH_SWORDBIND", "CH_TREMOR", "CH_ETERNALSEAL", "CH_SURPERIORHEAL");
	/** The stigmas the guide lists, by the sockets they fill: the regular ones (levels 20, 30 and 40), in the order it gives them. Word of Life, Annihilation, Leaping Flash. */
	static final List<String> REGULAR_STIGMAS = List.of("CH_IMPROVEDLIFESTREAM", "CH_TRIPLETHRUST", "CH_HARSHTHRUST");
	/** And the advanced ones (45, 50 and 55): Blessing of Rock, Disorienting Blow, Numbing Blow. */
	static final List<String> ADVANCED_STIGMAS = List.of("CH_BLESSPROTECT", "CH_SHOCKWAVE", "CH_SLOWCRASH");
	/** Health under which Word of Life is cast: the chanter alone, or each of the mates counted. */
	static final int WORD_OF_LIFE_BELOW_PERCENT = 60;
	/** How many of the group, the chanter included, must be under that for Word of Life to be worth its cooldown. Alone, the chanter is the one. */
	static final int WORD_OF_LIFE_MATES_NEEDED = 2;

	/** Health under which Protective Ward is raised: the guide's "if the chanter is taking a lot of damage". */
	static final int WARD_BELOW_PERCENT = 50;
	/** Health under which Block Curtain is raised, for the tank in a group and for the chanter alone. */
	static final int CURTAIN_BELOW_PERCENT = 30;
	/** The dispel Energy Discharge states, as its skill gives it: what it can reach is a debuff that asks for no more. */
	static final int DISPEL_LEVEL = 1;
	/** Health or mana under which Stamina Restoration is cast, alone or in a group. It is the heal the guide keeps for the chanter itself. */
	static final int STAMINA_BELOW_PERCENT = 60;
	/** Health under which a chanter alone is no longer fighting but mending: the guide's critical moment. */
	static final int CRITICAL_BELOW_PERCENT = 20;
	/** Health under which a chanter alone heals itself with Healing Light once the fight is over. */
	static final int HEAL_AT_REST_BELOW_PERCENT = 80;
	/** How far Light of Resurrection reaches, which is the {@code first_target_range} the data gives it. */
	static final float RESURRECTION_RANGE = 15f;
	/** The divine power Divine Protection and Word of Wind each cost, as the data states it for both. */
	static final int ABILITY_DP = 2000;
	/**
	 * The divine power a chanter must hold from level 31 for Divine Protection to be spent: two abilities' worth, so that spending one still leaves the
	 * other. Before level 31 there is nothing else to spend it on.
	 */
	static final int DP_FOR_BOTH = 2 * ABILITY_DP;
	/** The level at which Word of Wind is learnt, and from which Divine Protection is no longer cast on its own. */
	static final int WORD_OF_WIND_LEVEL = 31;
	/** The chain category Remove Shock opens, which Backshock asks for. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";

	/** Whom a move is aimed at. */
	enum Aim {
		/** The chanter itself, which for a party skill is also how it reaches the group. */
		SELF,
		/** What the chanter is fighting. */
		TARGET,
		/** The group mate that lies dead and that this chanter is the one to raise. */
		MATE
	}

	/**
	 * What the playbook may cast, and the skill group that names it; every level of a skill has its own id and the group is what they share.
	 * {@code claimed} is whether the generic order is told to leave the skill alone: Parrying Strike is a plain attack that the generic order may keep
	 * casting, and the playbook only adds the moment it follows Perfect Parry.
	 */
	enum Move {
		ENERGY_DISCHARGE("CH_RENEWEDVITALITY", Aim.SELF, true),
		PROTECTIVE_WARD("CH_PROTECTSELF", Aim.SELF, true),
		BLOCK_CURTAIN("CH_ANGELICWALL", Aim.SELF, true),
		PERFECT_PARRY("CH_CROSSPARRY", Aim.SELF, true),
		PARRYING_STRIKE("CH_PARRYINGSTRIKE", Aim.TARGET, false),
		ASCENSION_SPELL("CH_IMPROVEDPOWERATTACK", Aim.SELF, true),
		UNSTOPPABLE("CH_FRONTALATTACK", Aim.SELF, true),
		WORD_OF_LIFE("CH_IMPROVEDLIFESTREAM", Aim.SELF, true),
		STAMINA_RESTORATION("CH_CHAKRA", Aim.SELF, true),
		WORD_OF_QUICKNESS("CH_ZEST", Aim.SELF, true),
		RECOVERY_SPELL("CH_STIGMA_RECOVERWORD", Aim.SELF, true),
		WORD_OF_REVIVAL("CH_REJUVENATION", Aim.SELF, true),
		HEALING_LIGHT("CL_HEAL", Aim.SELF, true),
		LIGHT_OF_RESURRECTION("CL_REVIVE", Aim.MATE, true),
		WORD_OF_WIND("CH_PROVIDENCE", Aim.SELF, true),
		DIVINE_PROTECTION("CH_ABYSSGLORIA", Aim.SELF, true),
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF, true),
		BACKSHOCK("CH_SHOCKATTACK", Aim.TARGET, false),
		SWIFTWING("CH_CHANT_IMPROVEDFLY", Aim.SELF, true);

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
	 * Everything {@link #decide} looks at, so that it can be decided without a world.
	 *
	 * @param hurtCount How many of the chanter and the mates around it are under 60 % health, which Word of Life counts. Only read when it is ready.
	 * @param controlled Whether the chanter is stunned, knocked about, spun or held in the air right now: all it can cast is Remove Shock.
	 * @param shockChainOpen Whether Remove Shock was the last skill it used, which is the only moment Backshock can follow it.
	 * @param level The chanter's level, which decides whether Word of Wind is there to be kept the divine power for.
	 * @param dp The divine power it holds.
	 * @param mpPercent Mana, which Stamina Restoration restores as well as health.
	 * @param quicknessUp Whether Word of Quickness is on, the first step of the critical sequence.
	 * @param mending Whether the chanter began healing itself back to full and has not yet got there.
	 * @param mateToRaise Whether a dead mate is in reach that this chanter is the one to resurrect: nobody else in the group is to do it, and nobody is
	 *          already doing so.
	 * @param tankHpPercent The health of the group's tank, or -1 when there is none in reach (alone, or a group without one).
	 * @param debuffed Whether something is on the chanter that Energy Discharge can reach.
	 * @param wardUp Whether Protective Ward is already on. Asked because a shield cast over a shield is a swing wasted.
	 * @param ascensionUp Whether Ascension Spell is on. It lasts two minutes, which is how often the guide wants it renewed.
	 * @param unstoppableUp Whether Unstoppable is on.
	 * @param parryUp Whether Perfect Parry is on, which is the moment Parrying Strike is cast.
	 * @param ready The moves the chanter knows and that are off cooldown.
	 */
	record Situation(int hpPercent, int mpPercent, int level, int dp, boolean inTeam, int tankHpPercent, boolean debuffed, boolean wardUp, boolean ascensionUp,
		boolean unstoppableUp, boolean parryUp, boolean quicknessUp, boolean mending, boolean mateToRaise, int hurtCount, boolean controlled, boolean shockChainOpen, Set<Move> ready) {
	}

	@Override
	public List<String> mantraOrder(boolean inTeam) {
		return inTeam ? GROUP_MANTRAS : SOLO_MANTRAS;
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the chanter alone decides on this skill. Takes the template and not the bot so that every template in the game can be put through it. */
	static boolean claimsSkill(SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (FORBIDDEN_GROUPS.contains(group))
			return true;
		for (Move move : Move.values()) {
			if (move.claimed && move.group.equals(group))
				return true;
		}
		return false;
	}

	@Override
	public boolean tend(Player bot) {
		return perform(bot, null, decideAtRest(situationOf(bot)), false);
	}

	@Override
	public boolean act(Player bot, Creature target) {
		return perform(bot, target, decide(situationOf(bot)), true);
	}

	@Override
	public void onTakeOff(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.SWIFTWING.group))
			log.debug("Chanter {} casts {} as it takes off", bot.getName(), Move.SWIFTWING);
	}

	private static boolean perform(Player bot, Creature target, Move move, boolean inFight) {
		if (move == null)
			return false;
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> target;
			case MATE -> BotGroupManager.mateToResurrect(bot, RESURRECTION_RANGE);
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		// the sequence's first step is what says "until full", and the memory is kept with the bot: see BotCombatMemory.mendingToFull
		if (cast && inFight && isCriticalStep(move) && BotCombatMemory.of(bot) instanceof BotCombatMemory memory)
			memory.setMendingToFull(true);
		if (cast)
			log.debug("Chanter {} casts {} at {}% health", bot.getName(), move, bot.getLifeStats().getHpPercentage());
		return cast;
	}

	private static boolean isCriticalStep(Move move) {
		return move == Move.WORD_OF_QUICKNESS || move == Move.RECOVERY_SPELL || move == Move.WORD_OF_REVIVAL || move == Move.HEALING_LIGHT;
	}

	private static Situation situationOf(Player bot) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		boolean inTeam = bot.getCurrentTeam() != null;
		// asked only when the dispel is ready: reading the effect map costs a lock
		boolean debuffed = ready.contains(Move.ENERGY_DISCHARGE) && BotSkillManager.hasDispellableDebuff(bot, DISPEL_LEVEL);
		int hp = bot.getLifeStats().getHpPercentage();
		// asked only when the spell is ready: it walks the group
		boolean mateToRaise = inTeam && ready.contains(Move.LIGHT_OF_RESURRECTION) && BotGroupManager.mateToResurrect(bot, RESURRECTION_RANGE) != null;
		int hurtCount = ready.contains(Move.WORD_OF_LIFE) ? hurtAround(bot, hp) : 0;
		boolean mending = false;
		if (BotCombatMemory.of(bot) instanceof BotCombatMemory memory) {
			if (hp >= 100 || inTeam)
				memory.setMendingToFull(false); // done, or it is a group's chanter now, which heals nobody but itself with Stamina Restoration
			mending = memory.isMendingToFull();
		}
		return new Situation(hp, bot.getLifeStats().getMpPercentage(), bot.getLevel(), bot.getCommonData().getDp(), inTeam, inTeam ? tankHealth(bot) : -1, debuffed,
			BotSkillManager.isUp(bot, Move.PROTECTIVE_WARD.group), BotSkillManager.isUp(bot, Move.ASCENSION_SPELL.group),
			BotSkillManager.isUp(bot, Move.UNSTOPPABLE.group), BotSkillManager.isUp(bot, Move.PERFECT_PARRY.group),
			BotSkillManager.isUp(bot, Move.WORD_OF_QUICKNESS.group), mending, mateToRaise, hurtCount, BotSkillManager.isCrowdControlled(bot),
			BotSkillManager.isChainOpen(bot, SHOCK_CHAIN), ready);
	}

	/** @return How many of the chanter and the group mates near it are under the bar Word of Life is cast at. */
	private static int hurtAround(Player bot, int ownHealth) {
		int hurt = ownHealth < WORD_OF_LIFE_BELOW_PERCENT ? 1 : 0;
		for (Player mate : BotGroupManager.membersToTendTo(bot)) {
			if (mate.getLifeStats().getHpPercentage() < WORD_OF_LIFE_BELOW_PERCENT)
				hurt++;
		}
		return hurt;
	}

	/** @return true if enough of the group is hurt for Word of Life: the chanter alone is enough alone, and two are needed in a group. */
	private static boolean wordOfLifeIsNeeded(Situation situation) {
		return situation.hurtCount() >= (situation.inTeam() ? WORD_OF_LIFE_MATES_NEEDED : 1);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	/** @return The health of the group's tank, bot or player, or -1 when the group has none that is alive. */
	private static int tankHealth(Player bot) {
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		for (Player member : team.getMembers()) {
			if (!member.equals(bot) && !member.isDead() && BotRole.of(member) == BotRole.TANK)
				return member.getLifeStats().getHpPercentage();
		}
		return -1;
	}

	/**
	 * What the chanter does about itself while nobody is fighting it: the dispel, and nothing else, since every other move here belongs to a fight.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decideAtRest(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.debuffed() && ready.contains(Move.ENERGY_DISCHARGE))
			return Move.ENERGY_DISCHARGE;
		if (ready.contains(Move.WORD_OF_LIFE) && wordOfLifeIsNeeded(situation))
			return Move.WORD_OF_LIFE;
		if (ready.contains(Move.STAMINA_RESTORATION) && needsStamina(situation))
			return Move.STAMINA_RESTORATION;
		if (situation.mateToRaise() && ready.contains(Move.LIGHT_OF_RESURRECTION))
			return Move.LIGHT_OF_RESURRECTION;
		// alone, and only alone: in a group the chanter's heals are kept for nobody, as the guide says
		if (!situation.inTeam() && ready.contains(Move.HEALING_LIGHT) && (situation.hpPercent() < HEAL_AT_REST_BELOW_PERCENT || situation.mending()))
			return Move.HEALING_LIGHT;
		return null;
	}

	private static boolean needsStamina(Situation situation) {
		return situation.hpPercent() < STAMINA_BELOW_PERCENT || situation.mpPercent() < STAMINA_BELOW_PERCENT;
	}

	/**
	 * The critical sequence for a chanter alone, as the guide gives it: quicken the casting, then Recovery Spell, then Word of Revival on itself, then
	 * Healing Light for as long as it takes. Each step is taken when it is ready, so a spell on cooldown is skipped and the one after it is cast.
	 */
	private static Move criticalStep(Situation situation) {
		Set<Move> ready = situation.ready();
		if (ready.contains(Move.WORD_OF_QUICKNESS) && !situation.quicknessUp())
			return Move.WORD_OF_QUICKNESS;
		if (ready.contains(Move.RECOVERY_SPELL))
			return Move.RECOVERY_SPELL;
		if (ready.contains(Move.WORD_OF_REVIVAL))
			return Move.WORD_OF_REVIVAL;
		return ready.contains(Move.HEALING_LIGHT) ? Move.HEALING_LIGHT : null;
	}

	/**
	 * Picks what the chanter does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * The dispel leads because what it removes is what is stopping the rest. Block Curtain, the last resort, comes next, then the Ward. After the defences
	 * the buffs that are kept up through the fight, and last the pairing the guide asks for: Perfect Parry, and Parrying Strike the moment it is on.
	 * Each of these is a cast, and the engine resets a chain whenever another skill is cast, so each is spent only when it is due: the two minute
	 * buff once in two minutes, the ward when it is needed. That is as much of "do not break the chain" as can be said without reading the chain.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		Set<Move> ready = situation.ready();
		// A chanter that cannot act cannot cast: the engine refuses every skill but the few that evade, and Remove Shock is one of them. So control is
		// answered with it or not at all, as for the templar.
		if (situation.controlled())
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		// Before anything else, because the chain is the most fragile thing here: it lasts a second and a half, and any other skill cast in between closes it
		if (situation.shockChainOpen() && ready.contains(Move.BACKSHOCK))
			return Move.BACKSHOCK;
		if (situation.debuffed() && ready.contains(Move.ENERGY_DISCHARGE))
			return Move.ENERGY_DISCHARGE;
		if (ready.contains(Move.BLOCK_CURTAIN) && curtainIsNeeded(situation))
			return Move.BLOCK_CURTAIN;
		if (ready.contains(Move.PROTECTIVE_WARD) && !situation.wardUp() && situation.hpPercent() < WARD_BELOW_PERCENT)
			return Move.PROTECTIVE_WARD;
		if (ready.contains(Move.WORD_OF_LIFE) && wordOfLifeIsNeeded(situation))
			return Move.WORD_OF_LIFE;
		if (ready.contains(Move.STAMINA_RESTORATION) && needsStamina(situation))
			return Move.STAMINA_RESTORATION;
		// a chanter alone does not carry on fighting at a fifth of its health: it holds its ground and mends, and keeps mending until it is whole
		if (!situation.inTeam() && (situation.hpPercent() < CRITICAL_BELOW_PERCENT || situation.mending())) {
			Move step = criticalStep(situation);
			if (step != null)
				return step;
		}
		// a mate has died: the guide has the chanter quicken the group's casting first, then raise them
		if (situation.mateToRaise() && ready.contains(Move.LIGHT_OF_RESURRECTION)) {
			if (ready.contains(Move.WORD_OF_QUICKNESS) && !situation.quicknessUp())
				return Move.WORD_OF_QUICKNESS;
			return Move.LIGHT_OF_RESURRECTION;
		}
		Move divine = divinePowerMove(situation);
		if (divine != null)
			return divine;
		if (ready.contains(Move.ASCENSION_SPELL) && !situation.ascensionUp())
			return Move.ASCENSION_SPELL;
		if (ready.contains(Move.UNSTOPPABLE) && !situation.unstoppableUp())
			return Move.UNSTOPPABLE;
		if (situation.parryUp() && ready.contains(Move.PARRYING_STRIKE))
			return Move.PARRYING_STRIKE;
		if (!situation.parryUp() && ready.contains(Move.PERFECT_PARRY) && ready.contains(Move.PARRYING_STRIKE))
			return Move.PERFECT_PARRY;
		return null;
	}

	/**
	 * Where the divine power goes, which the guide calls the best use of it. Word of Wind whenever it is ready and paid for. Divine Protection before level
	 * 31 whenever it is ready and paid for, and from 31 only with two abilities' worth in hand, so that the Wind that comes back every half hour is
	 * never short of what it costs.
	 */
	private static Move divinePowerMove(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.dp() < ABILITY_DP)
			return null;
		if (ready.contains(Move.WORD_OF_WIND))
			return Move.WORD_OF_WIND;
		boolean spare = situation.level() < WORD_OF_WIND_LEVEL || situation.dp() >= DP_FOR_BOTH;
		return spare && ready.contains(Move.DIVINE_PROTECTION) ? Move.DIVINE_PROTECTION : null;
	}

	/** @return true if the group's tank is going down, or in a group with none, or alone, the chanter itself. */
	private static boolean curtainIsNeeded(Situation situation) {
		int health = situation.inTeam() && situation.tankHpPercent() >= 0 ? situation.tankHpPercent() : situation.hpPercent();
		return health < CURTAIN_BELOW_PERCENT;
	}
}
