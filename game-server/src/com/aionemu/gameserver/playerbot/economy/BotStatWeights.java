package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.stats.calc.functions.StatFunction;
import com.aionemu.gameserver.model.stats.calc.functions.StatRateFunction;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.playerbot.social.BotRole;

/**
 * How much a piece of gear is worth to a particular class, read from what it actually grants.
 * <p>
 * Gear used to be ranked on its item level, with quality to separate two of the same level. That is a proxy, and it was honest about being one, but
 * it cannot tell a ring that gives a gladiator crit from a ring of the same level that gives it nothing — and once seven accessory places are being
 * filled, almost every choice is between pieces of the same level. The data states what a piece grants: {@code ItemTemplate.getModifiers()} is a list
 * of stats and amounts, and the only thing missing is which of them this class wants.
 * <p>
 * Which it wants is knowledge about playing the game and exists in no file, exactly like {@code BotGearFit.weaponsOfTrade} and {@code BotRole}.
 * The orders below come from somebody who plays these classes.
 */
public class BotStatWeights {

	/**
	 * Fifty percent against a target with no crit defence, past which more physical crit buys nothing. Beyond it the stat is worth zero and whatever
	 * the piece offers next decides, which is what a player does: you stop stacking crit and start stacking what you were going to stack anyway.
	 */
	private static final int PHYSICAL_CRIT_SOFT_CAP = 500;
	/** Magical accuracy decides whether a debuff lands, which barely matters until the content starts resisting them. */
	private static final int MAGICAL_ACCURACY_MATTERS_FROM = 50;

	/**
	 * What a point of each stat is worth against a point of any other.
	 * <p>
	 * Without this a weighted sum is nonsense, because the game hands out these stats in wildly different sizes: the median piece of levelling gear
	 * that gives HP gives 117 of it and the median piece that gives physical attack gives 16. Summing them raw makes every HP piece beat every attack
	 * piece whatever the class wants, which is the item-level proxy again wearing a disguise.
	 * <p>
	 * The numbers are the measured median amount on gear of level 20 to 50 — the band where a bot actually chooses — so dividing by them puts every
	 * stat on the same scale: "one typical piece's worth". Measured rather than guessed, over 100k modifiers.
	 * <p>
	 * This map is also the whitelist. A stat that is not in it scores nothing, which keeps the resistances, the pvp ratios and the oddities out of a
	 * judgement that has no business weighing them.
	 */
	private static final Map<StatEnum, Integer> TYPICAL_AMOUNT = Map.ofEntries(Map.entry(StatEnum.PHYSICAL_CRITICAL, 31),
		Map.entry(StatEnum.PHYSICAL_ATTACK, 16), Map.entry(StatEnum.PHYSICAL_ACCURACY, 60), Map.entry(StatEnum.PHYSICAL_DEFENSE, 106),
		Map.entry(StatEnum.MAGICAL_CRITICAL, 9), Map.entry(StatEnum.MAGICAL_ATTACK, 14), Map.entry(StatEnum.MAGICAL_ACCURACY, 28),
		Map.entry(StatEnum.BOOST_MAGICAL_SKILL, 26), Map.entry(StatEnum.MAGICAL_RESIST, 59), Map.entry(StatEnum.MAXHP, 117),
		Map.entry(StatEnum.MAXMP, 181), Map.entry(StatEnum.EVASION, 80), Map.entry(StatEnum.PARRY, 33), Map.entry(StatEnum.BLOCK, 61),
		Map.entry(StatEnum.HEAL_BOOST, 11), Map.entry(StatEnum.CONCENTRATION, 7), Map.entry(StatEnum.FLY_TIME, 13),
		// The percentages. Their typical size is measured the same way and means the same thing, so they weigh against the amounts without any
		// special pleading — the median piece that carries movement speed carries 22% of it, and one that carries 28% is worth proportionally more.
		Map.entry(StatEnum.SPEED, 22), Map.entry(StatEnum.FLY_SPEED, 8), Map.entry(StatEnum.ATTACK_SPEED, 17),
		Map.entry(StatEnum.BOOST_CASTING_TIME, 9), Map.entry(StatEnum.DAMAGE_REDUCE, 40), Map.entry(StatEnum.BOOST_HATE, 18));

	/**
	 * What the percentages are worth, which is not a matter of class identity the way the amounts are.
	 * <p>
	 * They are kept out of the per-class order on purpose. Attack speed is not what makes a gladiator a gladiator — it is good for everything that
	 * swings, in the same measure — so threading it into an ordered list would push the stats that <b>do</b> define a class down a rank for no reason.
	 * They are universally good, in degrees that depend on only two things: whether the character hits or casts, and whether it wants to be hit.
	 */
	private static float rateWeight(Player bot, StatEnum stat) {
		boolean hits = bot.getPlayerClass().isPhysicalClass();
		return switch (stat) {
			case ATTACK_SPEED -> hits ? 0.9f : 0.2f;
			case BOOST_CASTING_TIME -> hits ? 0.2f : 0.9f;
			// Not cosmetic and not a luxury: a bot that cannot keep up with its group is a bot that is not in the fight, which is most of what the
			// movement and flight work was for. Worth about as much as a class's second stat.
			case SPEED -> 0.6f;
			case FLY_SPEED -> 0.4f;
			case DAMAGE_REDUCE -> BotRole.of(bot) == BotRole.TANK ? 0.8f : 0.4f;
			// The one stat whose sign is a matter of taste. A tank wants to be hit and everything else wants not to be, so the weight is negated for
			// the classes that would rather the monster looked elsewhere — a robe's -30% hostility is then worth as much to a sorcerer as a
			// breastplate's +30% is to a templar.
			case BOOST_HATE -> wantsHostility(bot) ? 0.5f : -0.5f;
			default -> UNRANKED;
		};
	}

	/**
	 * @return Whether this class would rather the monster hit it.
	 *         <p>
	 *         Not {@code BotRole}, and the difference is the gladiator. It is filed under damage there, correctly — putting two classes on the taunts
	 *         means two bots pulling the same monster in opposite directions — but it still wears plate and still picks things up when the templar
	 *         loses them, so hostility on its armour is wanted rather than tolerated. The aethertech is the same shape in chain.
	 */
	private static boolean wantsHostility(Player bot) {
		return switch (bot.getPlayerClass()) {
			case TEMPLAR, GLADIATOR, RIDER -> true;
			default -> false;
		};
	}

	/**
	 * What each place in a class's order is worth. Steep at the top and shallow after, because the first two stats are what the class is built around
	 * and the rest are tie-breakers.
	 */
	private static final float[] BY_RANK = { 1f, 0.7f, 0.5f, 0.35f, 0.25f, 0.2f };
	/** Anything the class did not ask for but that is still a stat worth having. Small, so it never outvotes a stat the class did ask for. */
	private static final float UNRANKED = 0.1f;

	/** What any class that hits wants, after whatever its own order puts first. */
	private static final List<StatEnum> PHYSICAL_TAIL = List.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.PHYSICAL_ATTACK, StatEnum.MAXHP,
		StatEnum.PHYSICAL_ACCURACY, StatEnum.EVASION);
	/** What any class that casts wants, likewise. */
	private static final List<StatEnum> MAGICAL_TAIL = List.of(StatEnum.MAGICAL_CRITICAL, StatEnum.BOOST_MAGICAL_SKILL, StatEnum.MAGICAL_ACCURACY,
		StatEnum.MAXHP, StatEnum.MAXMP);

	private static final Map<PlayerClass, List<StatEnum>> ORDERS = new ConcurrentHashMap<>();

	private BotStatWeights() {
	}

	/**
	 * @return What this piece is worth to this bot, in "typical pieces": a score of 1 means it gives about as much of the class's first stat as a
	 *         piece of its level usually gives. Zero for a piece that grants nothing the class cares about, which is a perfectly ordinary answer and
	 *         not a failure — a great many pieces grant only defence and resistances.
	 */
	public static float score(Player bot, ItemTemplate template) {
		// null, not an empty list, for a piece that grants nothing — and plenty of them do, power shards and the plainest starting gear among them.
		// Worth saying because the accessor reads like it returns a list: it returns the modifiers' list only when a modifiers element exists at all.
		List<StatFunction> modifiers = template.getModifiers();
		if (modifiers == null)
			return 0;
		List<StatEnum> order = orderFor(bot.getPlayerClass());
		float score = 0;
		for (StatFunction modifier : modifiers) {
			StatEnum stat = modifier.getName();
			Integer typical = TYPICAL_AMOUNT.get(stat);
			if (typical == null)
				continue;
			// A percentage is judged on its own terms, because it is good for reasons that have nothing to do with what a class is: see rateWeight.
			float weight = modifier instanceof StatRateFunction ? rateWeight(bot, stat) : rankWeight(order, stat);
			weight *= scaleForLevel(bot, stat);
			// <b>The sign the player sees, not the sign in the file.</b> Attack speed is stored as a reduction of the delay between swings, so a
			// weapon the client advertises as "+19% attack speed" carries the value -19, and every other stat here is stored the way it reads. Taken
			// at face value a bot would have gone looking for the slowest weapon it could find, and done it most deliberately for the classes that
			// care most. The engine states the direction itself and this is the same expression the item tooltip is built from.
			int asTheClientShowsIt = modifier.getValue() * stat.getSign();
			// A negative modifier is a real cost and counts as one: some gear buys attack with accuracy.
			score += weight * asTheClientShowsIt / (float) typical;
		}
		return score;
	}

	private static float rankWeight(List<StatEnum> order, StatEnum stat) {
		int rank = order.indexOf(stat);
		return rank < 0 ? UNRANKED : BY_RANK[Math.min(rank, BY_RANK.length - 1)];
	}

	/**
	 * @return Whether more physical crit would be wasted on this character.
	 *         <p>
	 *         Asked by the socketing, which stacks one stat across a piece's slots the way a player does and has to know when to stop and stack
	 *         something else. The cap lives here rather than there because it is the same number the ranking already stops at.
	 * @param alreadyPromised
	 *          Crit from stones chosen in this pass but not yet applied to the character.
	 */
	public static boolean physicalCritIsSaturated(Player bot, int alreadyPromised) {
		return bot.getGameStats().getMainHandPCritical().getCurrent() + alreadyPromised >= PHYSICAL_CRIT_SOFT_CAP;
	}

	/**
	 * The two rules that depend on the character rather than on the piece.
	 *
	 * @return What to multiply a stat's weight by for this bot, normally 1.
	 */
	private static float scaleForLevel(Player bot, StatEnum stat) {
		if (stat == StatEnum.PHYSICAL_CRITICAL && bot.getGameStats().getMainHandPCritical().getCurrent() >= PHYSICAL_CRIT_SOFT_CAP)
			return 0;
		if (stat == StatEnum.MAGICAL_ACCURACY && bot.getLevel() < MAGICAL_ACCURACY_MATTERS_FROM)
			return 0.2f;
		return 1;
	}

	/**
	 * @return The stats this class wants, best first.
	 *         <p>
	 *         Each class's own priorities come first and the generic order for its kind follows, so a stat nobody named still ranks above a stat
	 *         nobody wants. Duplicates fall out, which is why the set remembers its order.
	 *         <p>
	 *         Remembered per class, because this is asked once per modifier of every candidate piece while a shortlist is being sorted, and the
	 *         answer for a gladiator is the same gladiator after gladiator.
	 */
	private static List<StatEnum> orderFor(PlayerClass playerClass) {
		return ORDERS.computeIfAbsent(playerClass, BotStatWeights::buildOrderFor);
	}

	private static List<StatEnum> buildOrderFor(PlayerClass playerClass) {
		List<StatEnum> own = switch (playerClass) {
			// The crit comes first for every class that hits, and what follows it is what the class is for: a templar holds the line, a gladiator
			// pushes, and a chanter does a little of both while healing.
			case TEMPLAR -> List.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.BLOCK, StatEnum.MAGICAL_RESIST, StatEnum.MAXHP);
			case GLADIATOR -> List.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.PHYSICAL_ATTACK, StatEnum.PARRY, StatEnum.MAGICAL_RESIST);
			case ASSASSIN, RANGER -> List.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.PHYSICAL_ATTACK);
			case CHANTER -> List.of(StatEnum.PHYSICAL_CRITICAL, StatEnum.PARRY);
			// Mana and health ahead of anything offensive, deliberately: a cleric that runs dry or dies is a group wiped, and a cleric that does
			// less damage is a fight that takes longer. Survivability and fuel are its contribution.
			case CLERIC -> List.of(StatEnum.MAXMP, StatEnum.MAXHP, StatEnum.HEAL_BOOST);
			case SORCERER, SPIRIT_MASTER, BARD -> List.of(StatEnum.MAGICAL_ACCURACY, StatEnum.BOOST_MAGICAL_SKILL);
			// Nobody who wrote the orders plays these two, and saying so is better than inventing a preference. They take the generic order for
			// their kind, which the engine already settles: both cast.
			case GUNNER, RIDER -> List.of();
			default -> List.of(); // the starting classes, which are nobody's build and last nine levels
		};
		Set<StatEnum> order = new LinkedHashSet<>(own);
		order.addAll(playerClass.isPhysicalClass() ? PHYSICAL_TAIL : MAGICAL_TAIL);
		return new ArrayList<>(order);
	}
}
