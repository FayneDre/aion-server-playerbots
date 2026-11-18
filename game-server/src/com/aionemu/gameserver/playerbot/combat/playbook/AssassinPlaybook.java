package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.attack.AggroTarget;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.skillengine.effect.AbnormalState;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * How an assassin plays. Written without a guide, as the aethertech's was: every rule comes from the skill data or is the module's own judgement of
 * how the class is played, and the plan says which ({@code docs/classes/plans/assassin-plan.md}).
 * <p>
 * A melee damage dealer with two weapons whose damage is its runes: carvers raise the rune on the target a level at a time, up to five, and a burst
 * spends it for damage multiplied by the level it had, a tenth of it at none and one and a half times at five ({@code signet_data_templates.xml}). The
 * generic order reads no rune and burst whatever was ready; here every carver and burst is the playbook's, a rune is carved to five and burst there.
 * The chains, some of whose links carve too, stay the generic order's, and nothing but a defence is cast while one is open.
 */
final class AssassinPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(AssassinPlaybook.class);

	/**
	 * What an assassin never casts: Searching Eye and Advanced Eye, sights of the hidden nothing asks for yet, and Wind Walk, a hide bought with 2000
	 * divine power that a bot has no use for.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("AS_SIXSENSE_L", "AS_SIXSENSE_H", "AS_WINDWALK");
	/** The regular stigmas: Sigil Strike, Rune Knife, Eye of Wrath, Oath of Accuracy, Shadow Walk. The module's judgement, not a guide's. */
	static final List<String> REGULAR_STIGMAS = List.of("AS_CARVESIGNETCHAIN", "AS_SIGNETWAVE", "AS_VISIOUSEYE", "AS_HITMANMIND", "AS_STIGMA_SHADOWWALK");
	/** The advanced ones: Agony Rune, Lightning Slash, Apply Lethal Venom, Quickening Doom, Explosive Rebranding, Shimmerbomb. */
	static final List<String> ADVANCED_STIGMAS = List.of("AS_CHAINSIGNETBURST", "AS_FLASHSLASH", "AS_EXPLOSIONPOISON", "AS_VENOMSLASH",
		"AS_RESIGNETASSAULT", "AS_WIDENEWBLINDINGBURST");

	/**
	 * The chains the generic order carries on once open, every category a link follows or repeats: A (Swift Edge, Soul Slash), N (Surprise Attack, Side
	 * Strike), I (Counterattack, Whirlwind Slash), J (Assassination, Killing Spree three times), E (Fang Strike, Beast Kick), P (Dash Attack), D (Rune
	 * Carve) and F (every burst, which Agony Rune follows).
	 */
	static final List<String> FOLLOWED_CHAINS = List.of("SRA_CHAINA_1TH", "SRA_CHAINA_2TH_1", "SRA_CHAINN_1TH", "SRA_CHAINN_2TH", "SRA_CHAINI_1TH",
		"SRA_CHAINJ_1TH", "SRA_CHAINJ_2TH", "SRA_CHAINE_1TH", "SRA_CHAINE_2TH", "SRA_CHAINP_1TH", "SRA_CHAIND_1TH", "SRA_CHAINF_1TH");
	/** The chain Remove Shock opens, which Shadow Illusion and Cyclone Slash follow. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";

	/** The highest rune, which a burst is kept for; the one it is spent at by an assassin that knows no carver reaching five; the cap of the others. */
	static final int RUNE_FULL = 5, RUNE_EARLY = 3, LOW_CAP = 3;
	/** The target's health under which a rune is burst at any level above none: it will die before the rune grows. */
	static final int FINISH_BELOW = 15;
	/** Health under which Blood Rune is the burst, for the health it drains. */
	static final int BLOOD_RUNE_BELOW = 60;
	/** Health under which Focused Evasion, Slayer Form and, alone after Remove Shock, Shadow Illusion are raised. */
	static final int EVASION_BELOW = 50, SLAYER_BELOW = 40, ILLUSION_BELOW = 30;
	/** Health under which a lone assassin leaves. */
	static final int FLEE_BELOW = 25;
	/** What Divine Strike costs, and the dirks and Spiral Slash. */
	static final int STRIKE_DP = 2000, DIRK_DP = 4000;
	/** How many enemies on a lone assassin make its cooldowns worth spending, and how many on it make it blind them. */
	static final int ENEMIES_WORTH_A_BURST = 3, ENEMIES_ON_IT = 2;
	/** How far is out of a melee's reach, how near Beast Leap reaches, how wide "behind" is, and how far it looks. */
	static final float MELEE_REACH = 6f, LEAP_REACH = 7f, BEHIND_DEGREES = 60f, ENEMY_RADIUS = 25f;

	/** Whom a move is aimed at. */
	enum Aim {
		SELF, TARGET
	}

	/** What the playbook may cast. Every one but the two openers and Cyclone Slash is claimed, kept from the generic order. */
	enum Move {
		// carvers: cap 5, then cap 3
		FANG_STRIKE("AS_TIGERFANG", Aim.TARGET),
		/** Carves to five, and adds hate: alone only. */
		RIPCLAW_STRIKE("AS_TIGERBEAT", Aim.TARGET),
		/** Carves to five round the target, with a poison and a snare. */
		MASSACRE("AS_GENOCIDALSWORD", Aim.TARGET),
		RUNE_CARVE("AS_CARVESIGNET", Aim.TARGET),
		/** Carves at 20 m. */
		RUNE_KNIFE("AS_SIGNETWAVE", Aim.TARGET),
		// bursts
		SIGNET_SILENCE("AS_SILENTBURST", Aim.TARGET),
		/** Drains health. */
		BLOOD_RUNE("AS_DRAINBURST", Aim.TARGET),
		/** Bursts, then carves two levels again. */
		EXPLOSIVE_REBRANDING("AS_RESIGNETASSAULT", Aim.TARGET),
		PAIN_RUNE("AS_SIGNETBURST", Aim.TARGET),
		RUNE_BURST("AS_SIGNETFLARE", Aim.TARGET),
		BINDING_RUNE("AS_HEAVENSEDGE", Aim.TARGET),
		/** The next skill harder: cast before a burst. */
		KILLERS_EYE("AS_STRIKINGINTENTION", Aim.SELF),
		// the opening, from hiding; not claimed, the generic order casts them otherwise
		/** Damage from behind. */
		SURPRISE_ATTACK("SC_ASSAULTSTABBER", Aim.TARGET),
		/** Appears behind the target and stuns it. */
		AMBUSH("AS_BLINDSIDE", Aim.TARGET),
		// coatings and cooldowns
		APPLY_DEADLY_POISON("AS_STUNINGINTENTION", Aim.SELF),
		APPLY_LETHAL_VENOM("AS_EXPLOSIONPOISON", Aim.SELF),
		DEADLY_FOCUS("AS_RAIDSTANCE", Aim.SELF),
		FLURRY("AS_QUICKMOVE", Aim.SELF),
		OATH_OF_ACCURACY("AS_HITMANMIND", Aim.SELF),
		SENSORY_BOOST("AS_SENSEBOOST", Aim.SELF),
		EYE_OF_WRATH("AS_VISIOUSEYE", Aim.SELF),
		SCOUNDRELS_BOND("AS_BACKFOCUS", Aim.SELF),
		/** Attack up, a defence down: alone, on an ordinary monster. */
		DEADLY_ABANDON("AS_STABSTANCE", Aim.SELF),
		// divine power; a bot knows its faction's dirk alone
		TRINIELS_DIRK("AS_DEATHBRINGER", Aim.TARGET),
		VAIZELS_DIRK("AS_SOULBREAK", Aim.TARGET),
		SPIRAL_SLASH("AS_SPIRALSTAB", Aim.TARGET),
		DIVINE_STRIKE("AS_DIVINEASSAULT", Aim.TARGET),
		// hate
		/** Lowers its hate on the target. */
		CALMING_WHISPER("AS_BREEZEOFCALM", Aim.TARGET),
		/** Blinds round it and turns them away. */
		SHIMMERBOMB("AS_WIDENEWBLINDINGBURST", Aim.SELF),
		// defence
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF),
		/** Three seconds hidden, after Remove Shock. */
		SHADOW_ILLUSION("AS_SHADOWILLUSION", Aim.SELF),
		/** Carves to five, after Remove Shock; not claimed, a link of the generic order's too. */
		CYCLONE_SLASH("AS_STORMSLASH", Aim.TARGET),
		/** Every blow dodged, every spell resisted. */
		FOCUSED_EVASION("SC_WHISPEROFWIND", Aim.SELF),
		AETHERTWISTING("AS_WHISPEROFGALE", Aim.SELF),
		SPELLDODGING("AS_ALERTSTANCE", Aim.SELF),
		/** Cleanses snares, and the throw in the air. */
		BREAK_AWAY("AS_RUNNERSSTANCE", Aim.SELF),
		/** Cleanses roots and snares, with a dash of 15 m forward. */
		FLASH_OF_SPEED("AS_SHADOWDODGE", Aim.SELF),
		SLAYER_FORM("AS_DEFENDROAR", Aim.SELF),
		EVASIVE_BOOST("AS_REPEATEVADE", Aim.SELF),
		// control
		SEARCHING_STRIKE("AS_BACKBLOW", Aim.TARGET),
		QUICKENING_DOOM("AS_VENOMSLASH", Aim.TARGET),
		/** Only on a stunned target, as the data asks. */
		SHADOWFALL("AS_SHADOWDROP", Aim.TARGET),
		/** Only on a target thrown in the air. */
		CRASHING_WIND_STRIKE("AS_HELLSBLADE", Aim.TARGET),
		/** A stun and a jump back. */
		BEAST_LEAP("AS_TUMBLING", Aim.TARGET);

		final String group;
		final Aim aim;

		Move(String group, Aim aim) {
			this.group = group;
			this.aim = aim;
		}
	}

	/** The moves the generic order keeps casting as it always did: the openers and a chain link. */
	static final Set<Move> UNCLAIMED = EnumSet.of(Move.SURPRISE_ATTACK, Move.AMBUSH, Move.CYCLONE_SLASH);
	/** The carvers that reach five, best first. */
	static final List<Move> FULL_CARVERS = List.of(Move.FANG_STRIKE, Move.MASSACRE, Move.RIPCLAW_STRIKE);
	/** The carvers that stop at three. */
	static final List<Move> LOW_CARVERS = List.of(Move.RUNE_CARVE, Move.RUNE_KNIFE);
	/** The bursts with no reason of their own, best first. */
	static final List<Move> PLAIN_BURSTS = List.of(Move.EXPLOSIVE_REBRANDING, Move.PAIN_RUNE, Move.RUNE_BURST, Move.BINDING_RUNE);
	/** Every burst. */
	static final Set<Move> BURSTS = EnumSet.of(Move.SIGNET_SILENCE, Move.BLOOD_RUNE, Move.EXPLOSIVE_REBRANDING, Move.PAIN_RUNE, Move.RUNE_BURST,
		Move.BINDING_RUNE);
	/** The cooldowns spent on what is worth them, the longest lasting first. */
	static final List<Move> BURST_ORDER = List.of(Move.APPLY_LETHAL_VENOM, Move.SCOUNDRELS_BOND, Move.OATH_OF_ACCURACY, Move.SENSORY_BOOST,
		Move.DEADLY_FOCUS, Move.FLURRY, Move.EYE_OF_WRATH);

	/** Everything the decisions look at, filled field by field: a test sets only its own. */
	static final class Situation {

		Set<Move> ready = EnumSet.noneOf(Move.class);
		int hp = 100, dp;
		boolean inTeam, controlled, assailed;
		/** How many enemies have it as the one they hate most. */
		int attackersOnMe;
		boolean poisonUp = true, killersEyeUp, hidden;
		/** Whether it has learnt a carver that reaches five, ready or not: under level 16 it has none, and bursts at three. */
		boolean knowsFullCarver = true;
		boolean followedChainOpen, shockChainOpen;
		boolean rooted, snared;
		/** The rune on the target, and the target's health. */
		int rune, targetHp = 100;
		/** The target: on the assassin, a caster, a player, an elite or a boss, a boss, stunned, in the air, the bot behind it, out of reach, a melee player near. */
		boolean targetOnMe, targetCasts, targetIsPlayer, targetElite, targetBoss, targetStunned, targetAerial, behindTarget, targetOutOfReach,
			targetWithinLeap;
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
			s.attackersOnMe = attackersOnMe;
			s.poisonUp = poisonUp;
			s.killersEyeUp = killersEyeUp;
			s.hidden = hidden;
			s.knowsFullCarver = knowsFullCarver;
			s.followedChainOpen = followedChainOpen;
			s.shockChainOpen = shockChainOpen;
			s.rooted = rooted;
			s.snared = snared;
			s.rune = rune;
			s.targetHp = targetHp;
			s.targetOnMe = targetOnMe;
			s.targetCasts = targetCasts;
			s.targetIsPlayer = targetIsPlayer;
			s.targetElite = targetElite;
			s.targetBoss = targetBoss;
			s.targetStunned = targetStunned;
			s.targetAerial = targetAerial;
			s.behindTarget = behindTarget;
			s.targetOutOfReach = targetOutOfReach;
			s.targetWithinLeap = targetWithinLeap;
			s.enemiesEngaged = enemiesEngaged;
			return s;
		}
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		return claimsSkill(template);
	}

	/** @return true if the assassin alone decides on this skill. Takes the template so that every template in the game can be put through it. */
	static boolean claimsSkill(SkillTemplate template) {
		String group = template.getGroup();
		if (group == null)
			return false;
		if (FORBIDDEN_GROUPS.contains(group))
			return true;
		for (Move move : Move.values()) {
			if (!UNCLAIMED.contains(move) && group.equals(move.group))
				return true;
		}
		return false;
	}

	@Override
	public boolean flees(Player bot, int attackers) {
		return bot.getCurrentTeam() == null && bot.getLifeStats().getHpPercentage() < FLEE_BELOW;
	}

	/** Focused Evasion as it breaks off: every blow dodged while it runs. Its hides ask to be out of a fight, which a bot running from one is not. */
	@Override
	public void onRetreat(Player bot) {
		if (BotSkillManager.tryCastGroup(bot, bot, Move.FOCUSED_EVASION.group))
			log.debug("Assassin {} casts {} as it flees", bot.getName(), Move.FOCUSED_EVASION);
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	/** Its defences, its hate, its coating, then the fight. */
	@Override
	public boolean act(Player bot, Creature target) {
		Situation s = situationOf(bot, target);
		return performFirst(bot, target, s, AssassinPlaybook::decideDefence) || performFirst(bot, target, s, AssassinPlaybook::decideHate)
			|| performFirst(bot, target, s, AssassinPlaybook::decideUpkeep) || performFirst(bot, target, s, AssassinPlaybook::decideOffence);
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The decisions
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** What the assassin does about being held, hit and nearly dead: asked first, and the one thing cast while a chain is open. */
	static Move decideDefence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled)
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		if (s.shockChainOpen) {
			if (!s.inTeam && s.hp < ILLUSION_BELOW && ready.contains(Move.SHADOW_ILLUSION))
				return Move.SHADOW_ILLUSION;
			if (ready.contains(Move.CYCLONE_SLASH))
				return Move.CYCLONE_SLASH;
		}
		if (s.assailed && s.hp < EVASION_BELOW && ready.contains(Move.FOCUSED_EVASION))
			return Move.FOCUSED_EVASION;
		if (s.assailed && s.hp < SLAYER_BELOW && ready.contains(Move.SLAYER_FORM))
			return Move.SLAYER_FORM;
		if (s.targetCasts && s.targetOnMe) {
			for (Move move : List.of(Move.AETHERTWISTING, Move.SPELLDODGING)) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.rooted && ready.contains(Move.FLASH_OF_SPEED))
			return Move.FLASH_OF_SPEED;
		if (s.snared) {
			for (Move move : List.of(Move.BREAK_AWAY, Move.FLASH_OF_SPEED)) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.attackersOnMe >= ENEMIES_ON_IT && ready.contains(Move.EVASIVE_BOOST))
			return Move.EVASIVE_BOOST;
		return null;
	}

	/** In a group, the hate it should not hold: Shimmerbomb with two on it, Calming Whisper when its target has turned to it. Alone, the blinding alone. */
	static Move decideHate(Situation s) {
		if (s.controlled || s.followedChainOpen)
			return null;
		if (s.attackersOnMe >= ENEMIES_ON_IT && s.ready.contains(Move.SHIMMERBOMB))
			return Move.SHIMMERBOMB;
		if (s.inTeam && s.targetOnMe && s.ready.contains(Move.CALMING_WHISPER))
			return Move.CALMING_WHISPER;
		return null;
	}

	/** The poison on its blades, kept on in a fight; not while a chain is open, which any other cast would close. */
	static Move decideUpkeep(Situation s) {
		if (s.controlled || s.followedChainOpen || s.hidden)
			return null;
		return !s.poisonUp && s.ready.contains(Move.APPLY_DEADLY_POISON) ? Move.APPLY_DEADLY_POISON : null;
	}

	/** @return true if the target is worth the cooldowns: an elite, a boss or a player, or three enemies on an assassin alone. */
	static boolean worthABurst(Situation s) {
		return s.targetElite || s.targetIsPlayer || !s.inTeam && s.enemiesEngaged >= ENEMIES_WORTH_A_BURST;
	}

	/** @return The rune a burst waits for: five, or three for an assassin that knows no carver reaching five. */
	static int burstAt(Situation s) {
		return s.knowsFullCarver ? RUNE_FULL : RUNE_EARLY;
	}

	/** @return true if the rune on the target is worth bursting: at the level a burst waits for, or above none on a target about to die. */
	static boolean runeRipe(Situation s) {
		return s.rune >= burstAt(s) || s.rune > 0 && s.targetHp < FINISH_BELOW;
	}

	/**
	 * The fight once the assassin is looked after, while no chain is open: the opening from hiding, the divine power and the cooldowns on what is worth
	 * them, a ripe rune burst (Killer's Eye first), a rune carved, the controls. The chains are the generic order's.
	 */
	static Move decideOffence(Situation s) {
		Set<Move> ready = s.ready;
		if (s.controlled || s.followedChainOpen)
			return null;
		if (s.hidden) {
			if (s.behindTarget && !s.targetOutOfReach && ready.contains(Move.SURPRISE_ATTACK))
				return Move.SURPRISE_ATTACK;
			return ready.contains(Move.AMBUSH) ? Move.AMBUSH : null;
		}
		Move power = divinePower(s);
		if (power != null)
			return power;
		if (worthABurst(s)) {
			for (Move move : BURST_ORDER) {
				if (ready.contains(move))
					return move;
			}
		} else if (!s.inTeam && !s.targetElite && !s.targetIsPlayer && s.enemiesEngaged <= 1 && ready.contains(Move.DEADLY_ABANDON))
			return Move.DEADLY_ABANDON;
		Move burst = runeRipe(s) ? burst(s) : null;
		if (burst != null)
			return !s.killersEyeUp && ready.contains(Move.KILLERS_EYE) ? Move.KILLERS_EYE : burst;
		Move carver = carver(s);
		if (carver != null)
			return carver;
		return control(s);
	}

	/** @return The dirk on a boss or a player at 4000, Spiral Slash on a player out of reach at 4000, Divine Strike on what is worth it at 2000; or null. */
	static Move divinePower(Situation s) {
		Set<Move> ready = s.ready;
		if (s.dp >= DIRK_DP && (s.targetBoss || s.targetIsPlayer)) {
			for (Move dirk : List.of(Move.TRINIELS_DIRK, Move.VAIZELS_DIRK)) {
				if (ready.contains(dirk))
					return dirk;
			}
		}
		if (s.dp >= DIRK_DP && s.targetIsPlayer && s.targetOutOfReach && ready.contains(Move.SPIRAL_SLASH))
			return Move.SPIRAL_SLASH;
		if (s.dp >= STRIKE_DP && worthABurst(s) && ready.contains(Move.DIVINE_STRIKE))
			return Move.DIVINE_STRIKE;
		return null;
	}

	/** @return The burst for this moment: Signet Silence on a caster, Blood Rune when hurt, else the plainest one ready; or null. */
	static Move burst(Situation s) {
		Set<Move> ready = s.ready;
		if (s.targetCasts && ready.contains(Move.SIGNET_SILENCE))
			return Move.SIGNET_SILENCE;
		if (s.hp < BLOOD_RUNE_BELOW && ready.contains(Move.BLOOD_RUNE))
			return Move.BLOOD_RUNE;
		for (Move move : PLAIN_BURSTS) {
			if (ready.contains(move))
				return move;
		}
		for (Move move : List.of(Move.SIGNET_SILENCE, Move.BLOOD_RUNE)) {
			if (ready.contains(move))
				return move;
		}
		return null;
	}

	/**
	 * @return The carver for the rune on the target, or null when it is full: one that reaches five first (Ripclaw Strike alone, since it adds hate;
	 *         Massacre with two or more engaged, being an area), one that stops at three only under three, where it still raises the rune.
	 */
	static Move carver(Situation s) {
		if (s.rune >= RUNE_FULL)
			return null;
		Set<Move> ready = s.ready;
		for (Move move : FULL_CARVERS) {
			if (move == Move.RIPCLAW_STRIKE && s.inTeam || move == Move.MASSACRE && s.enemiesEngaged < 2)
				continue;
			if (ready.contains(move))
				return move;
		}
		if (s.rune < LOW_CAP) {
			for (Move move : LOW_CARVERS) {
				if (ready.contains(move))
					return move;
			}
		}
		return null;
	}

	/** @return A stun on a caster or a player, what the data allows on one stunned or in the air, Beast Leap on a player within reach; or null. */
	static Move control(Situation s) {
		Set<Move> ready = s.ready;
		if (s.targetStunned && ready.contains(Move.SHADOWFALL))
			return Move.SHADOWFALL;
		if (s.targetAerial && ready.contains(Move.CRASHING_WIND_STRIKE))
			return Move.CRASHING_WIND_STRIKE;
		if ((s.targetCasts || s.targetIsPlayer) && !s.targetStunned) {
			for (Move move : List.of(Move.SEARCHING_STRIKE, Move.QUICKENING_DOOM)) {
				if (ready.contains(move))
					return move;
			}
		}
		if (s.targetIsPlayer && s.targetWithinLeap && ready.contains(Move.BEAST_LEAP))
			return Move.BEAST_LEAP;
		return null;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// Casting
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	/** One of the decisions above, asked again without a move the engine refused, until one goes through or it has nothing more to say. */
	private interface Decider {
		Move decide(Situation s);
	}

	private static boolean performFirst(Player bot, Creature target, Situation situation, Decider decider) {
		Situation s = situation;
		for (int tries = 0; tries < Move.values().length; tries++) {
			Move move = decider.decide(s);
			if (move == null)
				return false;
			if (perform(bot, target, s, move))
				return true;
			s = s.without(move);
		}
		return false;
	}

	private static boolean perform(Player bot, Creature target, Situation s, Move move) {
		Creature aimedAt = move.aim == Aim.SELF ? bot : target;
		if (aimedAt == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Assassin {} casts {} at {} (rune {}, {}% health)", bot.getName(), move, aimedAt.getName(), s.rune, s.hp);
		return cast;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------------------------
	// The situation
	// ------------------------------------------------------------------------------------------------------------------------------------------------------

	private static Situation situationOf(Player bot, Creature target) {
		Situation s = new Situation();
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				s.ready.add(move);
		}
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		s.inTeam = team != null;
		s.hp = bot.getLifeStats().getHpPercentage();
		s.dp = bot.getCommonData().getDp();
		s.controlled = BotSkillManager.isCrowdControlled(bot);
		s.poisonUp = BotSkillManager.isUp(bot, Move.APPLY_DEADLY_POISON.group);
		s.killersEyeUp = BotSkillManager.isUp(bot, Move.KILLERS_EYE.group);
		s.hidden = bot.getEffectController().isAbnormalSet(AbnormalState.HIDE);
		s.knowsFullCarver = BotSkillManager.knows(bot, Move.FANG_STRIKE.group) || BotSkillManager.knows(bot, Move.RIPCLAW_STRIKE.group);
		s.followedChainOpen = FOLLOWED_CHAINS.stream().anyMatch(category -> BotSkillManager.isChainOpen(bot, category));
		s.shockChainOpen = BotSkillManager.isChainOpen(bot, SHOCK_CHAIN);
		s.rooted = bot.getEffectController().isAbnormalSet(AbnormalState.ROOT);
		s.snared = bot.getEffectController().isAbnormalSet(AbnormalState.SNARE);
		List<Player> members = team == null ? List.of(bot) : team.getMembers();
		int[] engaged = { 0 }, onMe = { 0 };
		bot.getKnownList().forEachNpc(npc -> {
			if (npc.isDead() || !npc.isSpawned() || !bot.isEnemy(npc) || PositionUtil.getDistance(bot, npc) > ENEMY_RADIUS)
				return;
			if (!npc.equals(target) && members.stream().noneMatch(m -> npc.getAggroList().isHating(m)))
				return;
			engaged[0]++;
			if (bot.equals(npc.getAggroList().getTarget(AggroTarget.MOST_HATED)))
				onMe[0]++;
		});
		s.enemiesEngaged = engaged[0];
		s.attackersOnMe = onMe[0];
		s.assailed = onMe[0] > 0;
		if (target != null) {
			s.rune = BotSkillManager.runeLevel(target);
			s.targetHp = target.getLifeStats().getHpPercentage();
			s.targetOnMe = bot.equals(target.getTarget());
			s.targetCasts = RangerPlaybook.kindOf(target) == RangerPlaybook.EnemyKind.CASTER;
			s.targetIsPlayer = target instanceof Player;
			NpcRating rating = target instanceof Npc npc ? npc.getObjectTemplate().getRating() : null;
			s.targetElite = rating != null && BardPlaybook.isEliteOrBoss(rating);
			s.targetBoss = rating == NpcRating.HERO || rating == NpcRating.LEGENDARY;
			s.targetStunned = target.getEffectController().isAbnormalSet(AbnormalState.STUN);
			s.targetAerial = target.getEffectController().isAbnormalSet(AbnormalState.OPENAERIAL);
			s.behindTarget = PositionUtil.isBehind(bot, target, BEHIND_DEGREES);
			double distance = PositionUtil.getDistance(bot, target);
			s.targetOutOfReach = distance > MELEE_REACH;
			s.targetWithinLeap = distance <= LEAP_REACH;
		}
		return s;
	}
}
