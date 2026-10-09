package com.aionemu.gameserver.playerbot.combat.playbook;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotGroupManager;
import com.aionemu.gameserver.playerbot.social.BotMarks;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.Properties;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;

/**
 * How a templar plays, after the guide's "Templier 101". The plan, milestone by milestone, is {@code docs/templar-plan.md}.
 * <p>
 * The exclusions are the guide's "do not use" list: Stubborn Spirit, Bodyguard, and area attacks in a group. The defensives hold in a group and alone alike, which the guide confirmed. The aggro rules are a group's business: a templar alone has nobody to
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
	/** The chain category Remove Shock opens, which Refresh Spirit asks for. */
	static final String SHOCK_CHAIN = "ALL_CHAINA_1TH";
	/** Health under which Refresh Spirit is worth following Remove Shock with. It heals a quarter, so above this it would mostly be wasted. */
	static final int REFRESH_SPIRIT_BELOW_PERCENT = 75;
	/**
	 * How long after being controlled Unwavering Devotion still counts as an answer to it. It is cast once the templar is free again, and a few seconds is
	 * a stun that has just ended; much longer and it would be raised against something that is no longer a threat.
	 */
	static final long RECENTLY_CONTROLLED_MILLIS = 8000;
	/**
	 * Skills the guide says never to use, by group. Stubborn Spirit is a stance that switches itself off the moment the templar casts anything else, and
	 * Bodyguard takes a party member's damage on the templar, which "will cause more problems than it solves" until every class is in. Claimed without
	 * being cast, so that the generic order, which would take either for a buff, leaves them alone.
	 */
	static final Set<String> FORBIDDEN_GROUPS = Set.of("KN_MOVINGSTANCE", "KN_GRANDPROTECTION");
	/**
	 * Health under which Barricade of Steel is raised. The guide lists it as a defensive cooldown without a number, so this is the generic bar a bot spends
	 * its defensives at; to be confirmed with him.
	 */
	static final int BARRICADE_BELOW_PERCENT = 70;
	/** The stigmas the guide wants, in the order it lists them: first the three regular sockets, then the three advanced ones. */
	static final List<String> REGULAR_STIGMAS = List.of("KN_HIGHPROVOKE", "KN_REFLECTSHIELD", "KN_THUNDERBLADE");
	static final List<String> ADVANCED_STIGMAS = List.of("KN_RECOVER", "KN_SENTINEL", "KN_DESTRUCTSHIELD");
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
		REMOVE_SHOCK("ALL_SHOCKREFLECT", Aim.SELF, false),
		REFRESH_SPIRIT("KN_PROTECTPROUD", Aim.SELF, false),
		EMPYREAN_ARMOR("KN_STONEBODY", Aim.SELF, false),
		IRON_SKIN("KN_IRONBODY", Aim.SELF, false),
		BARRICADE_OF_STEEL("KN_REFLECTSHIELD", Aim.SELF, false),
		UNWAVERING_DEVOTION("WA_STEADINESS", Aim.SELF, false),
		PROVOKING_ROAR("KN_MASSIVEPROVOKE", Aim.SELF, true),
		INCITE_RAGE("KN_HIGHPROVOKE", Aim.LOOSE_ENEMY, true),
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
	 *          costs nothing but the tick, and the guide says a templar's mana is negligible.
	 * @param armorUp Whether Empyrean Armor's buff is on the templar, which is what Iron Skin is kept for when it is not.
	 * @param inTeam Whether the templar is in a group, which is what the aggro rules need.
	 * @param enemyInRoarRange Whether an enemy that is fighting the group is close enough for Provoking Roar to take hold of it.
	 * @param enemyLoose Whether an enemy is hitting a group mate rather than the templar.
	 * @param controlled Whether the templar is stunned, knocked about, spun or held in the air right now.
	 * @param recentlyControlled Whether it was, a moment ago and no longer.
	 * @param shockChainOpen Whether Remove Shock was the last skill it used, which is the only moment Refresh Spirit can follow it.
	 * @param devotionUp Whether Unwavering Devotion's resistance is already on the templar.
	 * @param barricadeUp Whether Barricade of Steel is already switched on. It is a toggle, and casting a toggle that is on switches it off.
	 */
	record Situation(int level, int hpPercent, int dp, boolean armorUp, boolean inTeam, boolean enemyInRoarRange, boolean enemyLoose,
		boolean controlled, boolean recentlyControlled, boolean shockChainOpen, boolean devotionUp, boolean barricadeUp, Set<Move> ready) {
	}

	@Override
	public boolean claims(Player bot, SkillTemplate template) {
		for (Move move : Move.values()) {
			// the group's moves are the generic order's own while there is no group, so a templar alone still swings Capture as the damage it is
			if (move.group.equals(template.getGroup()))
				return !move.groupOnly || bot.getCurrentTeam() != null;
		}
		if (isForbidden(template.getGroup()))
			return true;
		// Area attacks in a group: the templar's own roar is the one that is wanted and is a move above. The rest hit things nobody asked to be hit, and pull
		// them onto a group that is holding one target.
		Properties properties = template.getProperties();
		return bot.getCurrentTeam() != null && properties != null && isAreaAttack(properties.getTargetRelation(), properties.getTargetType());
	}

	/**
	 * @return true if the skill's group is one the guide says never to cast. Null-safe, because most skill templates have no group at all and an immutable
	 *         {@code Set.of} refuses to be asked about null: asking it through the claim check threw on every decision tick of every templar.
	 */
	static boolean isForbidden(String group) {
		return group != null && FORBIDDEN_GROUPS.contains(group);
	}

	/** @return true if the skill is aimed at an area of enemies, which is what the guide advises against in a group. */
	static boolean isAreaAttack(TargetRelationAttribute relation, TargetRangeAttribute range) {
		return relation == TargetRelationAttribute.ENEMY && range == TargetRangeAttribute.AREA;
	}

	@Override
	public List<String> preferredStigmas(boolean advanced) {
		return advanced ? ADVANCED_STIGMAS : REGULAR_STIGMAS;
	}

	@Override
	public boolean act(Player bot, Creature target) {
		BotMarks.markWeakestIfNone(bot);
		Creature loose = BotGroupManager.enemyLooseOnAMate(bot);
		Situation situation = situationOf(bot, loose != null);
		// The guide says a templar never needs to rest for mana. This is how that gets checked: a line whenever it is low in a fight, to be read over a long
		// instance before anyone decides the rest rule may be skipped for a tank.
		if (bot.getLifeStats().getMpPercentage() < BotSkillManager.MANA_RESERVE_PERCENT)
			log.debug("Templar {} is at {}% mana in a fight", bot.getName(), bot.getLifeStats().getMpPercentage());
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
		long lastControlledAt = bot.getAi() instanceof PlayerBotAI ai ? ai.lastControlledAt() : 0;
		boolean controlled = BotSkillManager.isCrowdControlled(bot);
		boolean recentlyControlled = !controlled && lastControlledAt != 0 && System.currentTimeMillis() - lastControlledAt <= RECENTLY_CONTROLLED_MILLIS;
		return new Situation(bot.getLevel(), bot.getLifeStats().getHpPercentage(), bot.getCommonData().getDp(),
			BotSkillManager.isUp(bot, Move.EMPYREAN_ARMOR.group), inTeam, enemyInRoarRange, enemyLoose, controlled, recentlyControlled,
			BotSkillManager.isChainOpen(bot, SHOCK_CHAIN), BotSkillManager.isUp(bot, Move.UNWAVERING_DEVOTION.group), BotSkillManager.isUp(bot, Move.BARRICADE_OF_STEEL.group), ready);
	}

	/**
	 * Picks what the templar does with this moment of a fight, most urgent first, or nothing.
	 * <p>
	 * Staying alive comes first, because a dead tank holds nothing. Hand of Healing is the last resort and so leads once it applies; Empyrean Armor
	 * comes before Iron Skin because it heals as well as protects, and Iron Skin is what covers the stretch while Armor is on cooldown or has worn
	 * off. Then the aggro: the roar whenever it is ready, since it is what keeps every monster in reach on the templar, and the single target
	 * taunts only for a monster that has got loose, which is what the guide saves them for. Of the three, the slowest to come back goes first: Incite Rage (a stigma, a minute), then Capture, then Taunt, which is therefore the quickest to be ready again for the next one. Chastisement is damage and only ever uses what is left over.
	 *
	 * @return The move, or null when the generic order should carry on.
	 */
	static Move decide(Situation situation) {
		Set<Move> ready = situation.ready();
		boolean hasDp = situation.dp() >= ABILITY_DP;
		// A templar that cannot act cannot cast: the engine refuses every skill but the few that evade, and Remove Shock is one of them. So control is
		// answered with it or not at all, and nothing below it is worth deciding.
		if (situation.controlled())
			return ready.contains(Move.REMOVE_SHOCK) ? Move.REMOVE_SHOCK : null;
		// Before anything else, because the chain is the most fragile thing here: any other skill cast in between closes it
		if (situation.shockChainOpen() && ready.contains(Move.REFRESH_SPIRIT) && situation.hpPercent() < REFRESH_SPIRIT_BELOW_PERCENT)
			return Move.REFRESH_SPIRIT;
		if (ready.contains(Move.HAND_OF_HEALING) && hasDp && situation.hpPercent() < HAND_OF_HEALING_BELOW_PERCENT)
			return Move.HAND_OF_HEALING;
		if (ready.contains(Move.EMPYREAN_ARMOR) && situation.hpPercent() < EMPYREAN_ARMOR_BELOW_PERCENT)
			return Move.EMPYREAN_ARMOR;
		if (ready.contains(Move.IRON_SKIN) && situation.hpPercent() < IRON_SKIN_BELOW_PERCENT && !situation.armorUp())
			return Move.IRON_SKIN;
		if (ready.contains(Move.BARRICADE_OF_STEEL) && !situation.barricadeUp() && situation.hpPercent() < BARRICADE_BELOW_PERCENT)
			return Move.BARRICADE_OF_STEEL;
		if (situation.recentlyControlled() && !situation.devotionUp() && ready.contains(Move.UNWAVERING_DEVOTION))
			return Move.UNWAVERING_DEVOTION;
		if (situation.inTeam()) {
			if (ready.contains(Move.PROVOKING_ROAR) && situation.enemyInRoarRange())
				return Move.PROVOKING_ROAR;
			if (situation.enemyLoose()) {
				if (ready.contains(Move.INCITE_RAGE))
					return Move.INCITE_RAGE;
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
