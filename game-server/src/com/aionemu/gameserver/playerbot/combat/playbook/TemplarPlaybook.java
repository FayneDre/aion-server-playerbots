package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * How a templar plays, after the guide's "Templier 101". The plan, milestone by milestone, is {@code docs/templar-plan.md}.
 * <p>
 * The defensives hold in a group and alone alike, which the guide confirmed. The aggro rules are a group's business: a templar alone has nobody to
 * protect and nothing to peel, and taunting the monster already hitting it is a wasted cast.
 */
final class TemplarPlaybook implements ClassPlaybook {

	private static final Logger log = LoggerFactory.getLogger(TemplarPlaybook.class);

	/** Health under which Hand of Healing is the answer: the ultimate heal, kept for a fight that is being lost. */
	static final int HAND_OF_HEALING_BELOW_PERCENT = 20;
	/** Health under which Empyrean Armor is worth casting. It restores a quarter of the health, so above this it would mostly be wasted. */
	static final int EMPYREAN_ARMOR_BELOW_PERCENT = 75;
	/** Health under which Iron Skin is cast, when Empyrean Armor is not already carrying the templar. */
	static final int IRON_SKIN_BELOW_PERCENT = 50;
	/** The divine power both Hand of Healing and Empyrean Chastisement cost, as the data states it for each. */
	static final int ABILITY_DP = 2000;
	/**
	 * The last level at which divine power goes on Empyrean Chastisement. From the next one it is kept, because the ultimate heal needs the same
	 * 2000 and a templar that has just spent it cannot be saved by it.
	 */
	static final int LAST_CHASTISEMENT_LEVEL = 30;
	/** How far Provoking Roar reaches from the templar, which is the {@code effective_range} the data gives it. */
	static final float ROAR_RANGE = 8f;

	/** Whom a move is aimed at. */
	enum Aim {
		/** The templar itself. */
		SELF,
		/** What the templar is fighting. */
		TARGET,
		/** The monster that has got loose and is hitting somebody else. */
		LOOSE_ENEMY
	}

	/** What the playbook may cast, and the skill group that names it; every level of a skill has its own id and the group is what they share. */
	enum Move {
		HAND_OF_HEALING("KN_DIVINEHAND", Aim.SELF, false),
		EMPYREAN_ARMOR("KN_STONEBODY", Aim.SELF, false),
		IRON_SKIN("KN_IRONBODY", Aim.SELF, false),
		PROVOKING_ROAR("KN_MASSIVEPROVOKE", Aim.SELF, true),
		CAPTURE("KN_STUNNINGSNACHER", Aim.LOOSE_ENEMY, true),
		TAUNT("WA_PROVOKE", Aim.LOOSE_ENEMY, true),
		EMPYREAN_CHASTISEMENT("KN_ABYSALJUDGEMENT", Aim.TARGET, false);

		final String group;
		final Aim aim;
		/** Whether the move only means something to a group, and is therefore the playbook's alone only while there is one. */
		final boolean groupOnly;

		Move(String group, Aim aim, boolean groupOnly) {
			this.group = group;
			this.aim = aim;
			this.groupOnly = groupOnly;
		}
	}

	/**
	 * Everything {@link #decide} looks at, so that it can be decided without a world.
	 *
	 * @param ready The moves the templar knows and that are off cooldown. Mana is not in here: the engine refuses a cast it cannot pay for, which
	 *          costs nothing but the tick, and the guide's document says a templar's mana is negligible.
	 * @param armorUp Whether Empyrean Armor's buff is on the templar, which is what Iron Skin is kept for when it is not.
	 * @param inTeam Whether the templar is in a group, which is what the aggro rules need.
	 * @param enemyInRoarRange Whether an enemy that is fighting the group is close enough for Provoking Roar to take hold of it.
	 * @param enemyLoose Whether an enemy is hitting a group mate rather than the templar.
	 */
	record Situation(int level, int hpPercent, int dp, boolean armorUp, boolean inTeam, boolean enemyInRoarRange, boolean enemyLoose,
		Set<Move> ready) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		for (Move move : Move.values()) {
			// the group's moves are the generic order's own while there is no group, so a templar alone still swings Capture as the damage it is
			if (move.group.equals(template.getGroup()))
				return !move.groupOnly || bot.getCurrentTeam() != null;
		}
		return false;
	}

	@Override
	public boolean act(Player bot, Creature target) {
		Creature loose = BotGroupManager.enemyLooseOnAMate(bot);
		Situation situation = situationOf(bot, loose != null);
		Move move = decide(situation);
		if (move == null)
			return false;
		Creature aimedAt = switch (move.aim) {
			case SELF -> bot;
			case TARGET -> target;
			case LOOSE_ENEMY -> loose;
		};
		boolean cast = BotSkillManager.tryCastGroup(bot, aimedAt, move.group);
		if (cast)
			log.debug("Templar {} casts {} at {}% health with {} DP", bot.getName(), move, situation.hpPercent(), situation.dp());
		return cast;
	}

	private static Situation situationOf(Player bot, boolean enemyLoose) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		boolean inTeam = bot.getCurrentTeam() != null;
		// asked only when the roar is ready: counting is a walk over everything the bot can see
		boolean enemyInRoarRange = inTeam && ready.contains(Move.PROVOKING_ROAR) && BotGroupManager.enemiesFightingTheGroupWithin(bot, ROAR_RANGE) > 0;
		return new Situation(bot.getLevel(), bot.getLifeStats().getHpPercentage(), bot.getCommonData().getDp(),
			BotSkillManager.isUp(bot, Move.EMPYREAN_ARMOR.group), inTeam, enemyInRoarRange, enemyLoose, ready);
	}

	/**
	 * Picks what the templar does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * Staying alive comes first, because a dead tank holds nothing. Hand of Healing is the last resort and so leads once it applies; Empyrean Armor
	 * comes before Iron Skin because it heals as well as protects, and Iron Skin is what covers the stretch while Armor is on cooldown or has worn
	 * off. Then the aggro: the roar whenever it is ready, since it is what keeps every monster in reach on the templar, and the single target
	 * taunts only for a monster that has got loose, which is what the guide saves them for. Of those two Capture goes first, because it is the slower
	 * to come back and Taunt is then ready again for the next one. Chastisement is damage and only ever uses what is left over.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		Set<Move> ready = situation.ready();
		boolean hasDp = situation.dp() >= ABILITY_DP;
		if (ready.contains(Move.HAND_OF_HEALING) && hasDp && situation.hpPercent() < HAND_OF_HEALING_BELOW_PERCENT)
			return Move.HAND_OF_HEALING;
		if (ready.contains(Move.EMPYREAN_ARMOR) && situation.hpPercent() < EMPYREAN_ARMOR_BELOW_PERCENT)
			return Move.EMPYREAN_ARMOR;
		if (ready.contains(Move.IRON_SKIN) && situation.hpPercent() < IRON_SKIN_BELOW_PERCENT && !situation.armorUp())
			return Move.IRON_SKIN;
		if (situation.inTeam()) {
			if (ready.contains(Move.PROVOKING_ROAR) && situation.enemyInRoarRange())
				return Move.PROVOKING_ROAR;
			if (situation.enemyLoose()) {
				if (ready.contains(Move.CAPTURE))
					return Move.CAPTURE;
				if (ready.contains(Move.TAUNT))
					return Move.TAUNT;
			}
		}
		if (ready.contains(Move.EMPYREAN_CHASTISEMENT) && hasDp && situation.level() <= LAST_CHASTISEMENT_LEVEL)
			return Move.EMPYREAN_CHASTISEMENT;
		return null;
	}
}
