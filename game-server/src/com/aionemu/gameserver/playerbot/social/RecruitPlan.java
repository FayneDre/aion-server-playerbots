package com.aionemu.gameserver.playerbot.social;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.aionemu.gameserver.model.PlayerClass;

/**
 * Who a group that is recruiting for an instance should be given: which roles, and which character for each.
 * <p>
 * A group needs one tank and one healer before anything else, and a support after them if a seat is left; the rest is damage. The recruiter counts
 * as what their class is, so a cleric posting an offer is given a tank and no second healer. Nothing here reads the world: the situation is passed
 * in, which is what lets the rules be tested.
 */
public final class RecruitPlan {

	/** The roles a group cannot do without, in the order they are filled when there are fewer seats than wishes. */
	private static final List<BotRole> ESSENTIAL = List.of(BotRole.TANK, BotRole.HEALER, BotRole.SUPPORT);

	/**
	 * The classes asked for each role, best first. A support is a chanter before a bard, and damage is a ranger, a gladiator, an assassin or a sorcerer
	 * before the others: those have a playbook, while the spirit master, the gunner and the aethertech fight on the generic rotation and died 36 times
	 * of 40 in half an hour on Reshanta's upper layer. A starting class has no role in a group and is never asked for.
	 */
	private static final List<PlayerClass> TANKS = List.of(PlayerClass.TEMPLAR);
	private static final List<PlayerClass> HEALERS = List.of(PlayerClass.CLERIC);
	private static final List<PlayerClass> SUPPORTS = List.of(PlayerClass.CHANTER, PlayerClass.BARD);
	private static final List<PlayerClass> DAMAGE = List.of(PlayerClass.RANGER, PlayerClass.GLADIATOR, PlayerClass.ASSASSIN, PlayerClass.SORCERER,
		PlayerClass.SPIRIT_MASTER, PlayerClass.GUNNER, PlayerClass.RIDER);

	/** A character that could be recruited: who, of what class, at what level. */
	public record Candidate(int playerId, PlayerClass playerClass, int level) {
	}

	private RecruitPlan() {
	}

	/**
	 * @param present The roles of everyone already in the group, the recruiter included.
	 * @param seats How many places are left to fill.
	 * @return One role per seat: the essential roles the group lacks, in order, then damage.
	 */
	public static List<BotRole> rolesWanted(Collection<BotRole> present, int seats) {
		List<BotRole> wanted = new ArrayList<>(Math.max(0, seats));
		for (BotRole role : ESSENTIAL) {
			if (wanted.size() < seats && !present.contains(role))
				wanted.add(role);
		}
		while (wanted.size() < seats)
			wanted.add(BotRole.DAMAGE);
		return wanted;
	}

	/** @return The classes that fill the role, best first. */
	public static List<PlayerClass> classesFor(BotRole role) {
		return switch (role) {
			case TANK -> TANKS;
			case HEALER -> HEALERS;
			case SUPPORT -> SUPPORTS;
			case DAMAGE -> DAMAGE;
		};
	}

	/**
	 * @param candidates Characters of the right faction, already inside the level window.
	 * @param classesInGroup The classes already in the group, so damage is spread over several classes rather than three of the same.
	 * @param level The level the group plays at, which the nearest candidate is closest to.
	 * @return The best candidate for the role, or null if none plays it.
	 */
	public static Candidate choose(Collection<Candidate> candidates, BotRole role, Set<PlayerClass> classesInGroup, int level) {
		List<PlayerClass> classes = classesFor(role);
		return candidates.stream().filter(c -> classes.contains(c.playerClass()))
			.min(Comparator.<Candidate>comparingInt(c -> classesInGroup.contains(c.playerClass()) ? 1 : 0)
				.thenComparingInt(c -> classes.indexOf(c.playerClass()))
				.thenComparingInt(c -> Math.abs(c.level() - level))
				.thenComparingInt(Candidate::playerId))
			.orElse(null);
	}
}
