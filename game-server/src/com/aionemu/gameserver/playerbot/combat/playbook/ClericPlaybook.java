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
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotCombatMemory;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.combat.HealthRecord;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How a cleric plays, after the guide's "Clerc 101". The plan, milestone by milestone, is {@code docs/classes/history/cleric-plan.md}.
 * <p>
 * The guide's rule for a cleric is rationing: it heals who needs it with the spell that fits and no more, cleanses, and only then does damage. So the playbook
 * decides every heal and every buff of the class, and the generic order, which casts the strongest heal at one threshold for everybody, is told to leave them
 * alone ({@link #claimsSkill}). What it does not name, the damage spells in the main, stays with the generic order, apart from the damage over time spells, which
 * it casts first, and the ones it forbids.
 * <p>
 * Everything is a pure function of a {@link Situation}, decided by {@link #decideHeal}, {@link #decideRevive}, {@link #decideFight} and {@link #decideRest},
 * so that the thresholds are argued in a test and not found out in play.
 */
final class ClericPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(ClericPlaybook.class);

	/** Health above which nobody is healed: the guide's "no heal while the health is over 90 %". */
	static final int NO_HEAL_ABOVE_PERCENT = 90;
	/** Health under which a member of a group is healed with the standard heal. */
	static final int GROUP_HEAL_BELOW_PERCENT = 80;
	/** Health under which the big heal replaces the standard one, "the lowest threshold at the moment of decision takes the step". */
	static final int BIG_HEAL_BELOW_PERCENT = 60;
	/** Health under which the instant heal is cast in reaction in a group, and {@code SOLO_INSTANT_HEAL_BELOW_PERCENT} on itself alone. */
	static final int INSTANT_HEAL_BELOW_PERCENT = 50;
	static final int SOLO_INSTANT_HEAL_BELOW_PERCENT = 30;
	/** Health of the tank under which the seven second channelled heal is begun, before any other. */
	static final int CHANNEL_HEAL_BELOW_PERCENT = 30;
	/** Health of the tank under which its own heal comes before the area heals; above it, two members hurt are healed together first. */
	static final int TANK_FIRST_BELOW_PERCENT = 50;
	/** Health of the tank under which Saving Grace is put on it. */
	static final int SAVING_GRACE_BELOW_PERCENT = 60;
	/** Health of a mate that outranks the tank and the cleric themselves when they are not in real danger. */
	static final int URGENT_MATE_BELOW_PERCENT = 30;
	/** Health under which two members make the area heal worth its mana: the guide's "at least two members", read as hurt. */
	static final int AREA_HEAL_BELOW_PERCENT = 60;
	/** And the area heal that needs contact, kept for members that are worse off. */
	static final int CONTACT_HEAL_BELOW_PERCENT = 50;
	/** And the channelled area heal, for members about to die. */
	static final int CHANNEL_AREA_HEAL_BELOW_PERCENT = 30;
	/** Health under which a cleric that is hurt heals itself through an area heal on the others, when it has one. */
	static final int SELF_THROUGH_AREA_BELOW_PERCENT = 70;
	/** Health under which a cleric alone stops fighting and heals itself back to full. */
	static final int SOLO_HEAL_BELOW_PERCENT = 40;
	/** Health under which a cleric alone raises Blessed Shield, and then heals. */
	static final int SHIELD_BELOW_PERCENT = 30;
	/** Health under which a cleric alone spends Salvation, which costs divine power. */
	static final int SALVATION_BELOW_PERCENT = 20;
	/** Mana under which a cleric in a group spends Salvation on itself, and only below {@code SALVATION_GROUP_BEFORE_LEVEL}. */
	static final int SALVATION_GROUP_MANA_BELOW_PERCENT = 20;
	static final int SALVATION_GROUP_BEFORE_LEVEL = 31;
	/** Health of a cleric alone, after a fight, under which Healing Light is cast. */
	static final int SOLO_REST_BELOW_PERCENT = 90;
	/** Health a cleric alone must hold for Life Curtain, the guide's "above 70 %"; in a group it asks for 50 % health or 30 % mana. */
	static final int LIFE_CURTAIN_SOLO_ABOVE_PERCENT = 70;
	static final int LIFE_CURTAIN_GROUP_HEALTH_PERCENT = 50;
	static final int LIFE_CURTAIN_GROUP_MANA_PERCENT = 30;
	/** Mana under which Penance is raised, and health it needs to be paid for. */
	static final int PENANCE_BELOW_MANA_PERCENT = 50;
	static final int PENANCE_MIN_HEALTH_PERCENT = 50;
	/** Mana, and health of the tank, under which Sage's Wisdom is cast in a group. */
	static final int WISDOM_MANA_BELOW_PERCENT = 10;
	static final int WISDOM_TANK_BELOW_PERCENT = 10;
	/** Health of the cleric under which the chain after Remove Shock is Impervious Veil, and above which it is Storm of Vengeance. */
	static final int VEIL_BELOW_PERCENT = 30;
	/** Mana a cleric in a group must hold to spend any on damage or on cleansing a mate: the guide's 50 %. */
	static final int SPARE_MANA_PERCENT = 50;
	/** Health under which the cleric itself calls for Brilliant Protection, and what the tank must lose in 30 s for the group's: 200 %. */
	static final int PROTECTION_CLERIC_BELOW_PERCENT = 20;
	static final int PROTECTION_TANK_LOSS_PERCENT = 200;
	/** What members must lose in 30 s, and how many of them, for Acquittal. */
	static final int ACQUITTAL_LOSS_PERCENT = 100;
	static final int ACQUITTAL_MEMBERS = 2;
	/** The levels between which Acquittal is the group's emergency, and from which Brilliant Protection takes its place. */
	static final int ACQUITTAL_FROM_LEVEL = 31;
	static final int BRILLIANT_PROTECTION_LEVEL = 50;
	/** The divine power the three abilities cost, as the data states it. */
	static final int SALVATION_DP = 2000;
	static final int ACQUITTAL_DP = 2000;
	static final int BRILLIANT_PROTECTION_DP = 4000;
	/** How many allies must be dead, in an instance, for the area resurrection: "more than three". */
	static final int DEAD_FOR_AREA_REVIVE = 3;
	/** How far Light of Resurrection reaches, and the area one. */
	static final float RESURRECTION_RANGE = 15f;
	static final float AREA_RESURRECTION_RANGE = 30f;
	/** How far the cleric looks for an enemy to root, or one that is hitting it. */
	static final float ENEMY_RADIUS = 25f;
	/** How close, in metres, a monster must be for a cleric alone to answer it with a blow of its own. */
	static final float MELEE_RANGE = 3f;
	/** The dispel level the cleanses are tried with; the engine decides whether the effect is then strong enough to go. */
	static final int DISPEL_LEVEL = 3;
	/** How long after being held a cleric still counts as having just been, for Prayer of Focus. */
	static final long JUST_CONTROLLED_MILLIS = 4000;
	/** The chain category Remove Shock opens, which the two skills after it ask for. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";

	/**
	 * What a cleric never casts: Word of Destruction, Land's Bargain, which costs health for damage and which the guide says is
	 * "to be seen", and Reverse Condition, which swaps health and mana and which the tester called dangerous. Claimed, so that the generic order never takes
	 * them for what they look like. Also the two bandage skills every class knows, which would otherwise be what the generic heal falls back on.
	 */
	static final List<String> FORBIDDEN_GROUPS = List.of("PR_GODSVOICE", "PR_HOLYEXPLOSION", "PR_REVERSECONDITION", "MENDING", "AROMATHERAPY");
	/** The stigmas the guide lists ("Sigma"), by the sockets they fill: Saving Grace, Sympathetic Heal and Noble Grace, and not Reverse Condition. */
	static final List<String> REGULAR_STIGMAS = List.of("PR_RESTORELIFE", "PR_BINDINGHEAL", "PR_HEALINGGRACE");

	/** Whom a move is aimed at. */
	enum Who {
		SELF,
		/** The group's tank. */
		TANK,
		/** The most hurt member that is neither the tank nor the cleric. */
		MEMBER,
		/** A member of the group that is dead. */
		DEAD,
		/** What the cleric is fighting. */
		ENEMY,
		/** An enemy that is hitting the cleric. */
		ASSAILANT,
		/** An enemy that is not the group's main one. */
		OTHER_ENEMY
	}

	/**
	 * What the playbook may cast, and the skill group that names it; every level of a skill has its own id and the group is what they share. {@code claimed} is
	 * whether the generic order is told to leave the skill alone.
	 */
	enum Move {
		HEALING_LIGHT("CL_HEAL", true),
		REJUVENATION("PR_RAPIDREGENERATION", true),
		HEALING_GRACE("PR_GREATHEAL", true),
		FLASH_OF_RECOVERY("PR_FIRSTAID", true),
		HEALING_WIND("PR_MASSHEAL", true),
		LIGHT_OF_RECOVERY("PR_EMERGENTHEAL", true),
		SPLENDOR_OF_RECOVERY("PR_MASSEMERGENTHEAL", true),
		/** The two seven second channels, released at their top stage by the skill manager. */
		FLASH_OF_HEALING("PR_HOLYLIGHT", true),
		HEALING_SPLENDOR("PR_HOLYLIGHTWAVE", true),
		SAVING_GRACE("PR_RESTORELIFE", true),
		SYMPATHETIC_HEAL("PR_BINDINGHEAL", true),
		CLEANSE("PR_PURIFY", true),
		SPLENDOR_OF_PURIFICATION("PR_MASSDISPEL", true),
		SALVATION("PR_SECRETPRECEPT", true),
		ACQUITTAL("PR_HOLYGRAIL", true),
		BRILLIANT_PROTECTION("PR_HOLYCIRCLE", true),
		LIFE_CURTAIN("PR_CLOAKOFREMEDY", true),
		LIGHT_OF_RESURRECTION("CL_REVIVE", true),
		RESURRECTION_LOCI("PR_CALLRESURRECTION", true),
		BLESSING("CL_BLESSOFROCK", true),
		HAND_OF_REINCARNATION("PR_REVIVEHAND", true),
		SPLENDOR_OF_FLIGHT("PR_CIRCLEOFWINGS", true),
		PENANCE("PR_MEDITATION", true),
		SAGES_WISDOM("PR_SAGESWISDOM", true),
		PRAYER_OF_FOCUS("PR_FOCUSCASTING", true),
		BLESSED_SHIELD("PR_BLESSEDSHIELD", true),
		IMMORTAL_SHROUD("PR_INVINSIBLEWALL", true),
		AMPLIFICATION("PR_PREPAREHOLYWAR", true),
		REMOVE_SHOCK("ALL_SHOCKREFLECT", true),
		IMPERVIOUS_VEIL("PR_INVINSIBLEVEILL", true),
		STORM_OF_VENGEANCE("PR_THUNDERBOLT", true),
		ROOT("PR_ROOT", true),
		STABILITY("PR_STIGMA_SUGGESTION", true),
		HALLOWED_STRIKE("CL_HALLOWSWING", true),
		SLOWING_HAMMER("PR_RAISEREPENTANCE", true),
		/** The damage over time spells, which the playbook casts first but leaves to the generic order as well. */
		EARTHS_WRATH("PR_PURGATORY", false),
		AERO_SNARE("PR_HARSHPAIN", false),
		CHASTISE("PR_REPENTANCE", false);

		final String group;
		final boolean claimed;

		Move(String group, boolean claimed) {
			this.group = group;
			this.claimed = claimed;
		}
	}

	/** One move, aimed at one of the people the situation names. */
	record Decision(Move move, Who who) {
	}

	/** The damage over time spells, in the order they are cast. */
	static final List<Move> DAMAGE_OVER_TIME = List.of(Move.EARTHS_WRATH, Move.AERO_SNARE, Move.CHASTISE);
	/** What a cleric alone casts to heal itself back to full in a fight: the instant ones first, because a long cast is what gets interrupted. */
	static final List<Move> FIGHT_LADDER = List.of(Move.FLASH_OF_RECOVERY, Move.LIGHT_OF_RECOVERY, Move.HEALING_LIGHT);
	/** And out of one, where a cast costs nothing but mana. */
	static final List<Move> REST_LADDER = List.of(Move.HEALING_LIGHT, Move.HEALING_GRACE);

	/**
	 * Everything the decisions look at, so that they can be made without a world.
	 *
	 * @param hp The cleric's own health, in percent; {@code mp} its mana; {@code dp} its divine power.
	 * @param inFight Whether the bot is fighting. The same heals are cast out of a fight, with no concern for being hit.
	 * @param assailed Whether an enemy is hitting the cleric: the big heal is a three second cast and is not begun then.
	 * @param hasTank Whether the group has a tank in reach; {@code tankHp} its health.
	 * @param hasMember Whether there is a member that is neither the tank nor the cleric, the most hurt of whom has {@code memberHp}.
	 * @param hurtUnder60 How many of the group, the cleric included, are under the area heal's bar, {@code hurtUnder50} under the contact heal's and
	 *          {@code hurtUnder30} under the channelled one's.
	 * @param othersUnder90 How many members other than the cleric are hurt at all.
	 * @param debuffedCount How many of the group, the cleric included, carry something a cleanse can reach; {@code hasDebuffedMember} says whether one of
	 *          them is neither the cleric nor the tank.
	 * @param justControlled Whether the cleric was held a moment ago and no longer is, which is when Prayer of Focus is raised.
	 * @param hasMateToRaise Whether there is a dead member this cleric is the one to raise, in reach of Light of Resurrection: nobody else is to, and nobody is
	 *          already doing so.
	 * @param hasDeadMate Whether any dead member is in reach of the area resurrection, and {@code deadCount} how many there are.
	 * @param tankLoss What the tank lost over the last half minute, in percent of its health, and {@code membersLosing} how many members lost a whole bar.
	 * @param mending Whether a cleric alone began healing itself back to full and has not got there.
	 * @param hasOtherEnemy Whether an enemy of the group that is not its main one is fit to be rooted; {@code hasAssailant} whether one is hitting the cleric.
	 * @param onTarget The damage over time spells that are already on the target.
	 * @param ready The moves the cleric knows and that are off cooldown.
	 */
	record Situation(int hp, int mp, int level, int dp, boolean inTeam, boolean inFight, boolean assailed, boolean hasTank, int tankHp, boolean tankHasRejuvenation,
		boolean tankHasSavingGrace, boolean hasMember, int memberHp, int hurtUnder60, int hurtUnder50, int hurtUnder30, int othersUnder90, boolean selfHasRejuvenation, boolean penanceUp,
		boolean shieldUp, boolean shroudUp, boolean amplificationUp, boolean focusUp, boolean selfDebuffed, boolean tankDebuffed, boolean hasDebuffedMember,
		int debuffedCount, boolean controlled, boolean shockChainOpen, boolean justControlled, boolean hasMateToRaise, boolean hasDeadMate, int deadCount,
		boolean chanterInGroup, boolean inInstance, int tankLoss, int membersLosing, boolean mending, boolean hasOtherEnemy, boolean hasAssailant,
		boolean targetInMelee, Set<Move> onTarget, Set<Move> ready) {
	}

	/** The people a decision may name, which the situation only says exist. Kept apart so that the decisions can be made without a world. */
	record Targets(Player tank, Player member, Player debuffedMember, Player mateToRaise, Player deadMate, Creature otherEnemy, Creature assailant) {
	}

	/** What the bot saw: the facts the decisions weigh, and the people they may be about. */
	record Reading(Situation situation, Targets targets) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the cleric alone decides on this skill. Takes the template and not the bot so that every template in the game can be put through it. */
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
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? List.of() : REGULAR_STIGMAS;
	}

	@Override
	public boolean heal(Player bot) {
		Reading reading = readingOf(bot, null, false);
		return perform(bot, null, reading, decideHeal(reading.situation()));
	}

	@Override
	public boolean act(Player bot, Creature target) {
		Reading reading = readingOf(bot, target, true);
		Situation situation = reading.situation();
		Decision decision = decideFight(situation);
		if (perform(bot, target, reading, decision))
			return true;
		decision = decideHeal(situation);
		if (decision == null)
			decision = decideRevive(situation);
		if (decision == null)
			decision = decideUtility(situation);
		if (perform(bot, target, reading, decision))
			return true;
		// the servant comes before the spells that do damage over time, as the guide's opening has it
		if (!situation.controlled() && isWorthSpendingMana(situation) && BotSkillManager.trySummonServant(bot))
			return true;
		return perform(bot, target, reading, decideOffence(situation));
	}

	@Override
	public boolean tend(Player bot) {
		Reading reading = readingOf(bot, null, false);
		return perform(bot, null, reading, decideRest(reading.situation()));
	}

	@Override
	public boolean prepare(Player bot) {
		return upkeep(bot, readingOf(bot, null, false).situation());
	}

	@Override
	public void onTakeOff(Player bot) {
		if (bot.getCurrentTeam() != null && BotSkillManager.tryCastGroup(bot, bot, Move.SPLENDOR_OF_FLIGHT.group))
			log.debug("Cleric {} casts {} as it takes off", bot.getName(), Move.SPLENDOR_OF_FLIGHT);
	}

	private static boolean perform(Player bot, Creature target, Reading reading, Decision decision) {
		if (decision == null)
			return false;
		Situation situation = reading.situation();
		Targets targets = reading.targets();
		Creature aimedAt = switch (decision.who()) {
			case SELF -> bot;
			case TANK -> targets.tank();
			case MEMBER -> targets.member();
			case DEAD -> decision.move() == Move.LIGHT_OF_RESURRECTION ? targets.mateToRaise() : targets.deadMate();
			case ENEMY -> target;
			case ASSAILANT -> targets.assailant();
			case OTHER_ENEMY -> targets.otherEnemy();
		};
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, decision.move().group);
		if (cast && !situation.inTeam() && situation.inFight() && FIGHT_LADDER.contains(decision.move()) && BotCombatMemory.of(bot) instanceof BotCombatMemory memory)
			memory.setMendingToFull(true);
		if (cast)
			log.debug("Cleric {} casts {} at {} ({}% health, {}% mana)", bot.getName(), decision.move(), aimedAt.getName(), situation.hp(), situation.mp());
		return cast;
	}

	/**
	 * The buffs the cleric puts on itself and on every member near it that lacks them, out of a fight: the group buff, unless a chanter is in the group and has a
	 * better one on, and the revive buff. Not a pure decision, since it walks the group, and a short one: it asks of each in turn whether it is covered.
	 */
	private static boolean upkeep(Player bot, Situation situation) {
		List<Player> everybody = new ArrayList<>();
		everybody.add(bot);
		everybody.addAll(BotGroupManager.membersToTendTo(bot));
		for (Player member : everybody) {
			if (situation.ready().contains(Move.BLESSING) && !situation.chanterInGroup() && !BotSkillManager.isCovered(bot, member, Move.BLESSING.group)
				&& BotSkillManager.tryCastGroup(bot, member, Move.BLESSING.group))
				return true;
			if (situation.ready().contains(Move.HAND_OF_REINCARNATION) && !BotSkillManager.isCovered(bot, member, Move.HAND_OF_REINCARNATION.group)
				&& BotSkillManager.tryCastGroup(bot, member, Move.HAND_OF_REINCARNATION.group))
				return true;
		}
		return false;
	}

	private static boolean isWorthSpendingMana(Situation situation) {
		return !situation.inTeam() || situation.mp() >= SPARE_MANA_PERCENT;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The situation
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	private static Reading readingOf(Player bot, Creature target, boolean fighting) {
		TemporaryPlayerTeam<?> group = bot.getCurrentTeam();
		// a healer is in the fight whenever the group is, which is not the same as the bot being in the state of one: it may be doing nothing but heal, and then it is not
		// attacking. Without this a cleric out of that state raised the dead in the middle of a fight, and never kept the heal over time on the tank, which asks for one.
		boolean inFight = fighting || bot.getAi() != null && bot.getAi().isInState(AIState.FIGHT) || group != null && BotGroupManager.enemiesFightingTheGroupWithin(bot, ENEMY_RADIUS) > 0;
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		boolean inTeam = team != null;
		int hp = bot.getLifeStats().getHpPercentage();
		BotCombatMemory memory = BotCombatMemory.of(bot);

		Player tank = null, member = null, debuffedMember = null;
		int tankHp = -1, memberHp = -1, hurtUnder60 = hp < AREA_HEAL_BELOW_PERCENT ? 1 : 0;
		int hurtUnder50 = hp < CONTACT_HEAL_BELOW_PERCENT ? 1 : 0, hurtUnder30 = hp < CHANNEL_AREA_HEAL_BELOW_PERCENT ? 1 : 0, othersUnder90 = 0;
		boolean selfDebuffed = ready.contains(Move.CLEANSE) && BotSkillManager.hasDispellableDebuff(bot, DISPEL_LEVEL), tankDebuffed = false, chanter = false;
		int debuffedCount = selfDebuffed ? 1 : 0, tankLoss = 0, membersLosing = 0;
		boolean tankHasRejuvenation = false, tankHasSavingGrace = false;
		if (inTeam) {
			HealthRecord record = memory == null ? null : memory.healthRecord();
			long now = System.currentTimeMillis();
			List<Integer> ids = new ArrayList<>();
			if (record != null) {
				record.sample(now, bot.getObjectId(), hp);
				ids.add(bot.getObjectId());
			}
			for (Player mate : BotGroupManager.membersToTendTo(bot)) {
				int mateHp = mate.getLifeStats().getHpPercentage();
				if (mate.getPlayerClass() == PlayerClass.CHANTER)
					chanter = true;
				if (record != null) {
					record.sample(now, mate.getObjectId(), mateHp);
					ids.add(mate.getObjectId());
				}
				if (mateHp < AREA_HEAL_BELOW_PERCENT)
					hurtUnder60++;
				if (mateHp < CONTACT_HEAL_BELOW_PERCENT)
					hurtUnder50++;
				if (mateHp < CHANNEL_AREA_HEAL_BELOW_PERCENT)
					hurtUnder30++;
				if (mateHp < NO_HEAL_ABOVE_PERCENT)
					othersUnder90++;
				boolean debuffed = ready.contains(Move.CLEANSE) && BotSkillManager.hasDispellableDebuff(mate, DISPEL_LEVEL);
				if (debuffed)
					debuffedCount++;
				if (BotRole.of(mate) == BotRole.TANK && tank == null) {
					tank = mate;
					tankHp = mateHp;
					tankDebuffed = debuffed;
					tankHasRejuvenation = BotSkillManager.isCovered(bot, mate, Move.REJUVENATION.group);
					tankHasSavingGrace = BotSkillManager.isCovered(bot, mate, Move.SAVING_GRACE.group);
				} else {
					if (member == null || mateHp < memberHp) {
						member = mate;
						memberHp = mateHp;
					}
					if (debuffed && debuffedMember == null)
						debuffedMember = mate;
				}
			}
			if (record != null) {
				tankLoss = tank == null ? 0 : record.lossOverWindow(tank.getObjectId());
				membersLosing = record.membersWhoLostAtLeast(ids, ACQUITTAL_LOSS_PERCENT);
			}
		} else if (memory != null) {
			memory.healthRecord().clear();
		}
		boolean mending = false;
		if (memory != null) {
			if (hp >= 100 || inTeam)
				memory.setMendingToFull(false); // done, or it is a group's cleric now, which rations its heals by the group's need
			mending = memory.isMendingToFull();
		}
		// one look round for whatever is hitting the cleric, asked only when a skill that cares is ready
		Creature assailant = inFight && inTeam && (ready.contains(Move.HEALING_GRACE) || ready.contains(Move.STABILITY)) ? BotGroupManager.enemyHitting(bot, ENEMY_RADIUS) : null;
		boolean assailed = assailant != null;
		if (!ready.contains(Move.STABILITY))
			assailant = null;
		Creature otherEnemy = inFight && inTeam && target != null && ready.contains(Move.ROOT) ? enemyToRoot(bot, target) : null;
		Set<Move> onTarget = EnumSet.noneOf(Move.class);
		if (target != null) {
			for (Move move : DAMAGE_OVER_TIME) {
				if (ready.contains(move) && BotSkillManager.isOnTarget(bot, target, move.group))
					onTarget.add(move);
			}
		}
		boolean justControlled = memory != null && !BotSkillManager.isCrowdControlled(bot) && System.currentTimeMillis() - memory.lastControlledAt() < JUST_CONTROLLED_MILLIS;
		Player mateToRaise = inTeam && ready.contains(Move.LIGHT_OF_RESURRECTION) ? BotGroupManager.mateToResurrect(bot, RESURRECTION_RANGE) : null;
		int deadCount = inTeam && ready.contains(Move.RESURRECTION_LOCI) ? BotGroupManager.deadMembersWithin(bot, AREA_RESURRECTION_RANGE) : 0;
		Player deadMate = deadCount > 0 ? BotGroupManager.deadMemberWithin(bot, AREA_RESURRECTION_RANGE) : null;
		boolean inInstance = bot.getPosition().getWorldMapInstance() != null && bot.getPosition().getWorldMapInstance().getParent().isInstanceType();
		Situation situation = new Situation(hp, bot.getLifeStats().getMpPercentage(), bot.getLevel(), bot.getCommonData().getDp(), inTeam, inFight, assailed, tank != null,
			tankHp, tankHasRejuvenation, tankHasSavingGrace, member != null, memberHp, hurtUnder60, hurtUnder50, hurtUnder30, othersUnder90,
			BotSkillManager.isCovered(bot, bot, Move.REJUVENATION.group), BotSkillManager.isUp(bot, Move.PENANCE.group),
			BotSkillManager.isUp(bot, Move.BLESSED_SHIELD.group), BotSkillManager.isUp(bot, Move.IMMORTAL_SHROUD.group),
			BotSkillManager.isUp(bot, Move.AMPLIFICATION.group), BotSkillManager.isUp(bot, Move.PRAYER_OF_FOCUS.group), selfDebuffed, tankDebuffed, debuffedMember != null,
			debuffedCount, BotSkillManager.isCrowdControlled(bot), BotSkillManager.isChainOpen(bot, SHOCK_CHAIN), justControlled, mateToRaise != null, deadMate != null,
			deadCount, chanter, inInstance, tankLoss, membersLosing, mending, otherEnemy != null, assailant != null,
			target != null && PositionUtil.getDistance(bot, target) <= MELEE_RANGE, onTarget, ready);
		return new Reading(situation, new Targets(tank, member, debuffedMember, mateToRaise, deadMate, otherEnemy, assailant));
	}

	/** @return An enemy of the group, not the one being attacked, that is not already held or put to sleep or turned into something else. Null when there is none. */
	private static Creature enemyToRoot(Player bot, Creature main) {
		Creature other = BotGroupManager.otherEnemyOnTheGroup(bot, main, ENEMY_RADIUS);
		if (other == null)
			return null;
		for (AbnormalState state : new AbnormalState[] { AbnormalState.ROOT, AbnormalState.SLEEP, AbnormalState.DEFORM, AbnormalState.PARALYZE, AbnormalState.PETRIFICATION }) {
			if (other.getEffectController().isAbnormalSet(state))
				return null;
		}
		return other;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	private static Decision self(Move move) {
		return new Decision(move, Who.SELF);
	}

	/**
	 * What a cleric does when it is held, and in the instant after: the guide has nothing but Remove Shock while held, then the two skills that follow it, then
	 * Prayer of Focus. Asked first of all, since a cleric that is held can cast nothing else.
	 */
	static Decision decideFight(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled())
			return ready.contains(Move.REMOVE_SHOCK) ? self(Move.REMOVE_SHOCK) : null;
		if (situation.shockChainOpen()) {
			if (situation.hp() < VEIL_BELOW_PERCENT && ready.contains(Move.IMPERVIOUS_VEIL))
				return self(Move.IMPERVIOUS_VEIL);
			if (ready.contains(Move.STORM_OF_VENGEANCE))
				return new Decision(Move.STORM_OF_VENGEANCE, Who.ENEMY);
		}
		if (situation.justControlled() && !situation.focusUp() && ready.contains(Move.PRAYER_OF_FOCUS))
			return self(Move.PRAYER_OF_FOCUS);
		return null;
	}

	/**
	 * The heals, the cleanses and the divine power, in a fight or out of one, alone or in a group.
	 *
	 * @return The move, or null when nothing needs healing or the cleric cannot cast.
	 */
	static Decision decideHeal(Situation situation) {
		if (situation.controlled())
			return null;
		return situation.inTeam() ? decideGroupHeal(situation) : decideSoloHeal(situation);
	}

	private static Decision decideSoloHeal(Situation situation) {
		Set<Move> ready = situation.ready();
		int hp = situation.hp();
		if (situation.inFight()) {
			if (hp < SALVATION_BELOW_PERCENT && ready.contains(Move.SALVATION) && situation.dp() >= SALVATION_DP)
				return self(Move.SALVATION);
			if (hp < SHIELD_BELOW_PERCENT && ready.contains(Move.BLESSED_SHIELD) && !situation.shieldUp())
				return self(Move.BLESSED_SHIELD);
		}
		if (situation.selfDebuffed() && ready.contains(Move.CLEANSE))
			return self(Move.CLEANSE);
		// the guide's "when the health is under 40 %, heal to full": it goes on until it is, and the instant spells lead because a long cast is the one that is interrupted
		if (hp < SOLO_HEAL_BELOW_PERCENT || situation.mending()) {
			for (Move move : situation.inFight() ? FIGHT_LADDER : REST_LADDER) {
				// the instant heal has a 30 s cooldown and is kept for when the cleric is nearly dead
				if (ready.contains(move) && (move != Move.FLASH_OF_RECOVERY || hp < SOLO_INSTANT_HEAL_BELOW_PERCENT))
					return self(move);
			}
		}
		if (situation.inFight() && !situation.selfHasRejuvenation() && ready.contains(Move.REJUVENATION))
			return self(Move.REJUVENATION);
		if (!situation.inFight() && hp < SOLO_REST_BELOW_PERCENT && ready.contains(Move.HEALING_LIGHT))
			return self(Move.HEALING_LIGHT);
		if (situation.inFight() && hp >= LIFE_CURTAIN_SOLO_ABOVE_PERCENT && (hp < NO_HEAL_ABOVE_PERCENT || situation.mp() < NO_HEAL_ABOVE_PERCENT)
			&& ready.contains(Move.LIFE_CURTAIN))
			return self(Move.LIFE_CURTAIN);
		return null;
	}

	private static Decision decideGroupHeal(Situation situation) {
		Set<Move> ready = situation.ready();
		int hp = situation.hp();
		if (situation.inFight() && situation.level() >= BRILLIANT_PROTECTION_LEVEL && situation.dp() >= BRILLIANT_PROTECTION_DP && ready.contains(Move.BRILLIANT_PROTECTION)
			&& (hp < PROTECTION_CLERIC_BELOW_PERCENT || situation.tankLoss() >= PROTECTION_TANK_LOSS_PERCENT))
			return self(Move.BRILLIANT_PROTECTION);
		if (situation.inFight() && situation.level() >= ACQUITTAL_FROM_LEVEL && situation.level() <= BRILLIANT_PROTECTION_LEVEL && situation.dp() >= ACQUITTAL_DP
			&& ready.contains(Move.ACQUITTAL) && situation.membersLosing() >= ACQUITTAL_MEMBERS)
			return self(Move.ACQUITTAL);

		if (situation.inFight() && situation.level() < SALVATION_GROUP_BEFORE_LEVEL && situation.mp() < SALVATION_GROUP_MANA_BELOW_PERCENT
			&& situation.dp() >= SALVATION_DP && ready.contains(Move.SALVATION))
			return self(Move.SALVATION);

		Who who = whoNeedsAHeal(situation);
		if (who == null)
			return decideGroupUpkeep(situation);
		int target = who == Who.TANK ? situation.tankHp() : who == Who.SELF ? hp : situation.memberHp();
		if (who == Who.TANK && target < SAVING_GRACE_BELOW_PERCENT && ready.contains(Move.SAVING_GRACE) && !situation.tankHasSavingGrace())
			return new Decision(Move.SAVING_GRACE, Who.TANK);
		// the area heals go first while the tank holds half its health, the tank's own heal first below that
		Decision single = decideSingleHeal(situation, who, target), area = decideAreaHeal(situation);
		boolean tankFirst = who == Who.TANK && target < TANK_FIRST_BELOW_PERCENT;
		Decision first = tankFirst ? single : area, then = tankFirst ? area : single;
		if (first != null)
			return first;
		if (then != null)
			return then;
		// a cleric that is hurt heals itself by healing the others, which an area heal does
		if (who == Who.SELF && hp < SELF_THROUGH_AREA_BELOW_PERCENT && situation.othersUnder90() >= 1 && ready.contains(Move.HEALING_WIND))
			return self(Move.HEALING_WIND);
		Decision hot = decideHealOverTime(situation);
		if (hot != null)
			return hot;
		if (ready.contains(Move.HEALING_LIGHT))
			return new Decision(Move.HEALING_LIGHT, who);
		if (who != Who.SELF && ready.contains(Move.SYMPATHETIC_HEAL))
			return new Decision(Move.SYMPATHETIC_HEAL, who);
		if (ready.contains(Move.FLASH_OF_RECOVERY))
			return new Decision(Move.FLASH_OF_RECOVERY, who);
		return null;
	}

	/** The heals aimed at one, the lowest threshold first: the channel under 30 % (the tank only), the instant heal under 50 %, the big heal under 60 %. */
	private static Decision decideSingleHeal(Situation situation, Who who, int target) {
		Set<Move> ready = situation.ready();
		if (who == Who.TANK && target < CHANNEL_HEAL_BELOW_PERCENT && ready.contains(Move.FLASH_OF_HEALING))
			return new Decision(Move.FLASH_OF_HEALING, Who.TANK);
		if (target < INSTANT_HEAL_BELOW_PERCENT && ready.contains(Move.FLASH_OF_RECOVERY))
			return new Decision(Move.FLASH_OF_RECOVERY, who);
		if (target < BIG_HEAL_BELOW_PERCENT && !situation.assailed() && ready.contains(Move.HEALING_GRACE))
			return new Decision(Move.HEALING_GRACE, who);
		return null;
	}

	/** The area heals, for two members hurt or more, the lowest threshold first: the channel under 30 %, the contact heal under 50 %, the wind under 60 %. */
	private static Decision decideAreaHeal(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.hurtUnder30() >= 2 && ready.contains(Move.HEALING_SPLENDOR))
			return self(Move.HEALING_SPLENDOR);
		if (situation.hurtUnder50() >= 2 && ready.contains(Move.SPLENDOR_OF_RECOVERY))
			return self(Move.SPLENDOR_OF_RECOVERY);
		if (situation.hurtUnder60() >= 2 && ready.contains(Move.HEALING_WIND))
			return self(Move.HEALING_WIND);
		return null;
	}

	/** The heal over time kept on the tank in a fight, from its first moment and again as it ends, and on the cleric after Penance has taken its health. */
	private static Decision decideHealOverTime(Situation situation) {
		Set<Move> ready = situation.ready();
		if (!ready.contains(Move.REJUVENATION))
			return null;
		if (situation.inFight() && situation.hasTank() && !situation.tankHasRejuvenation())
			return new Decision(Move.REJUVENATION, Who.TANK);
		if (situation.penanceUp() && !situation.selfHasRejuvenation() && situation.hp() < NO_HEAL_ABOVE_PERCENT)
			return self(Move.REJUVENATION);
		return null;
	}

	/**
	 * @return Who is healed next: the tank and the cleric are the same rank and the lower of the two goes first, then the others, and a mate that is nearly dead
	 *         goes before either of them while they are not in real danger. Null when nobody is under the bar.
	 */
	static Who whoNeedsAHeal(Situation situation) {
		boolean tankNeeds = situation.hasTank() && situation.tankHp() < GROUP_HEAL_BELOW_PERCENT;
		boolean selfNeeds = situation.hp() < GROUP_HEAL_BELOW_PERCENT;
		boolean memberNeeds = situation.hasMember() && situation.memberHp() < GROUP_HEAL_BELOW_PERCENT;
		if (memberNeeds && situation.memberHp() < URGENT_MATE_BELOW_PERCENT && (!tankNeeds || situation.tankHp() >= SAVING_GRACE_BELOW_PERCENT - 10)
			&& (!selfNeeds || situation.hp() >= SAVING_GRACE_BELOW_PERCENT - 10))
			return Who.MEMBER;
		if (tankNeeds && selfNeeds)
			return situation.tankHp() <= situation.hp() ? Who.TANK : Who.SELF;
		if (tankNeeds)
			return Who.TANK;
		if (selfNeeds)
			return Who.SELF;
		return memberNeeds ? Who.MEMBER : null;
	}

	/** What a cleric in a group does when nobody needs a heal: keep the heal over time on the tank, cleanse, and spend the mana it has to spare. */
	private static Decision decideGroupUpkeep(Situation situation) {
		Set<Move> ready = situation.ready();
		Decision hot = decideHealOverTime(situation);
		if (hot != null)
			return hot;
		if (situation.debuffedCount() >= 2 && ready.contains(Move.SPLENDOR_OF_PURIFICATION))
			return self(Move.SPLENDOR_OF_PURIFICATION);
		if (situation.selfDebuffed() && ready.contains(Move.CLEANSE))
			return self(Move.CLEANSE);
		if (situation.tankDebuffed() && ready.contains(Move.CLEANSE))
			return new Decision(Move.CLEANSE, Who.TANK);
		if (situation.hasDebuffedMember() && situation.mp() >= SPARE_MANA_PERCENT && ready.contains(Move.CLEANSE))
			return new Decision(Move.CLEANSE, Who.MEMBER);
		if (situation.inFight() && ready.contains(Move.LIFE_CURTAIN) && (situation.hp() >= LIFE_CURTAIN_GROUP_HEALTH_PERCENT || situation.mp() >= LIFE_CURTAIN_GROUP_MANA_PERCENT)
			&& (situation.hp() < NO_HEAL_ABOVE_PERCENT || situation.mp() < NO_HEAL_ABOVE_PERCENT))
			return self(Move.LIFE_CURTAIN);
		return null;
	}

	/**
	 * Raising the dead. Light of Resurrection only out of a fight, on a member nobody else is to raise: no cleric of the guide does it while a chanter is in the
	 * group. The area one is the guide's exception for a group that has lost most of its members in an instance, "foutu pour foutu".
	 */
	static Decision decideRevive(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled())
			return null;
		if (situation.inInstance() && situation.deadCount() > DEAD_FOR_AREA_REVIVE && situation.hasDeadMate() && ready.contains(Move.RESURRECTION_LOCI))
			return new Decision(Move.RESURRECTION_LOCI, Who.DEAD);
		if (!situation.inFight() && situation.hasMateToRaise() && ready.contains(Move.LIGHT_OF_RESURRECTION))
			return new Decision(Move.LIGHT_OF_RESURRECTION, Who.DEAD);
		return null;
	}

	/** The utilities of a fight: Penance, Sage's Wisdom, the shields, Amplification, and the two that deal with an enemy that is not the main one. */
	static Decision decideUtility(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled())
			return null;
		if (ready.contains(Move.PENANCE) && !situation.penanceUp() && situation.mp() < PENANCE_BELOW_MANA_PERCENT && situation.hp() >= PENANCE_MIN_HEALTH_PERCENT)
			return self(Move.PENANCE);
		if (ready.contains(Move.SAGES_WISDOM) && wisdomIsWanted(situation))
			return self(Move.SAGES_WISDOM);
		if (ready.contains(Move.IMMORTAL_SHROUD) && !situation.shroudUp())
			return self(Move.IMMORTAL_SHROUD);
		if (ready.contains(Move.AMPLIFICATION) && !situation.amplificationUp())
			return self(Move.AMPLIFICATION);
		// an enemy that has picked the cleric: look at it and make it forget, then go back to the group's target and the heals
		if (situation.inTeam() && situation.hasAssailant() && ready.contains(Move.STABILITY))
			return new Decision(Move.STABILITY, Who.ASSAILANT);
		if (situation.inTeam() && situation.hasOtherEnemy() && ready.contains(Move.ROOT))
			return new Decision(Move.ROOT, Who.OTHER_ENEMY);
		return null;
	}

	private static boolean wisdomIsWanted(Situation situation) {
		if (!situation.inTeam())
			return true;
		return situation.mp() < WISDOM_MANA_BELOW_PERCENT || situation.hasTank() && situation.tankHp() < WISDOM_TANK_BELOW_PERCENT;
	}

	/** The cleric's own damage, which is the damage over time spells first, and a blow in melee for a cleric alone that something has reached. */
	static Decision decideOffence(Situation situation) {
		Set<Move> ready = situation.ready();
		if (situation.controlled() || !isWorthSpendingMana(situation))
			return null;
		for (Move move : DAMAGE_OVER_TIME) {
			if (ready.contains(move) && !situation.onTarget().contains(move))
				return new Decision(move, Who.ENEMY);
		}
		if (!situation.inTeam() && situation.targetInMelee()) {
			if (ready.contains(Move.HALLOWED_STRIKE))
				return new Decision(Move.HALLOWED_STRIKE, Who.ENEMY);
			if (ready.contains(Move.SLOWING_HAMMER))
				return new Decision(Move.SLOWING_HAMMER, Who.ENEMY);
		}
		return null;
	}

	/** What a cleric does out of a fight: raise the dead, mend itself, cleanse. The buffs are {@link #upkeep}. */
	static Decision decideRest(Situation situation) {
		Decision decision = decideRevive(situation);
		if (decision != null)
			return decision;
		return decideHeal(situation);
	}
}
