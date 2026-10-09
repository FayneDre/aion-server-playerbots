package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * How a templar plays, after the guide's "Templier 101". The plan, milestone by milestone, is {@code docs/templar-plan.md}.
 * <p>
 * So far only the defensives: when to spend Hand of Healing, Empyrean Armor and Iron Skin, and what to do with the divine power that Empyrean
 * Chastisement and Hand of Healing both draw on. They hold in a group and alone alike, which is what the guide confirmed.
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

	/** What the playbook may cast, and the skill group that names it; every level of a skill has its own id and the group is what they share. */
	enum Move {
		HAND_OF_HEALING("KN_DIVINEHAND", false),
		EMPYREAN_ARMOR("KN_STONEBODY", false),
		IRON_SKIN("KN_IRONBODY", false),
		EMPYREAN_CHASTISEMENT("KN_ABYSALJUDGEMENT", true);

		final String group;
		final boolean aimedAtTheEnemy;

		Move(String group, boolean aimedAtTheEnemy) {
			this.group = group;
			this.aimedAtTheEnemy = aimedAtTheEnemy;
		}
	}

	/**
	 * Everything {@link #decide} looks at, so that it can be decided without a world.
	 *
	 * @param ready The moves the templar knows and that are off cooldown. Mana is not in here: the engine refuses a cast it cannot pay for, which
	 *          costs nothing but the tick, and the guide's document says a templar's mana is negligible.
	 * @param armorUp Whether Empyrean Armor's buff is on the templar, which is what Iron Skin is kept for when it is not.
	 */
	record Situation(int level, int hpPercent, int dp, boolean armorUp, Set<Move> ready) {
	}

	private static final Set<String> CLAIMED_GROUPS = Arrays.stream(Move.values()).map(move -> move.group).collect(Collectors.toSet());

	@Override
	public boolean claims(SkillTemplate template) {
		return CLAIMED_GROUPS.contains(template.getGroup());
	}

	@Override
	public boolean act(Player bot, Creature target) {
		Situation situation = situationOf(bot);
		Move move = decide(situation);
		if (move == null)
			return false;
		boolean cast = BotSkillManager.tryCastGroup(bot, move.aimedAtTheEnemy ? target : bot, move.group);
		if (cast)
			log.debug("Templar {} casts {} at {}% health with {} DP", bot.getName(), move, situation.hpPercent(), situation.dp());
		return cast;
	}

	private static Situation situationOf(Player bot) {
		Set<Move> ready = EnumSet.noneOf(Move.class);
		for (Move move : Move.values()) {
			if (BotSkillManager.isReady(bot, move.group))
				ready.add(move);
		}
		return new Situation(bot.getLevel(), bot.getLifeStats().getHpPercentage(), bot.getCommonData().getDp(),
			BotSkillManager.isUp(bot, Move.EMPYREAN_ARMOR.group), ready);
	}

	/**
	 * Picks what the templar does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * The order follows what each is for. Hand of Healing is the last resort and so comes first once it applies. Empyrean Armor comes before Iron
	 * Skin because it heals as well as protects, and Iron Skin is what covers the stretch while Armor is on cooldown or has worn off. Chastisement
	 * is damage and only ever uses what is left over.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		boolean hasDp = situation.dp() >= ABILITY_DP;
		if (situation.ready().contains(Move.HAND_OF_HEALING) && hasDp && situation.hpPercent() < HAND_OF_HEALING_BELOW_PERCENT)
			return Move.HAND_OF_HEALING;
		if (situation.ready().contains(Move.EMPYREAN_ARMOR) && situation.hpPercent() < EMPYREAN_ARMOR_BELOW_PERCENT)
			return Move.EMPYREAN_ARMOR;
		if (situation.ready().contains(Move.IRON_SKIN) && situation.hpPercent() < IRON_SKIN_BELOW_PERCENT && !situation.armorUp())
			return Move.IRON_SKIN;
		if (situation.ready().contains(Move.EMPYREAN_CHASTISEMENT) && hasDp && situation.level() <= LAST_CHASTISEMENT_LEVEL)
			return Move.EMPYREAN_CHASTISEMENT;
		return null;
	}
}
