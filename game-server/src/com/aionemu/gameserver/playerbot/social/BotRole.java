package com.aionemu.gameserver.playerbot.social;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;

/**
 * What a class is <i>for</i> when it stands in a group.
 * <p>
 * The one authored table in this module, and deliberately so. Everything else about a bot's fighting is read from the skill data — which skills are
 * upkeep, which are kept in hand, which open a chain, which way a buff points — because the data knows and a list would rot. This is the exception
 * the design always expected: the data says what a skill does and never says what a <b>class</b> is meant to do with it. A templar and a gladiator
 * both hold a sword and a shield and both know a taunt; nothing in any file says that one of them is supposed to be hit and the other is not.
 * <p>
 * Fifteen lines rather than a derivation, and the alternative was tried on paper: a tank reads as "high defence", which is also a gladiator, a
 * chanter in plate and every templar-shaped thing in the game. The inference is weaker than the sentence.
 */
public enum BotRole {

	/** Holds what the group is fighting, and is meant to be the one taking the blows. */
	TANK,
	/** Keeps the group alive, and has no business in melee range. */
	HEALER,
	/** Heals when somebody is actually hurt, and fights the rest of the time. */
	SUPPORT,
	/** Damage, which is also every class nobody has a better word for. */
	DAMAGE;

	/**
	 * @return What this class does in a group.
	 *         <p>
	 *         {@code SUPPORT} is not padding. A chanter states 197 physical damage against 17 magical and a songweaver is built the same way: they
	 *         are classes that hit and happen to carry heals. Filed under {@code HEALER} they would spend a fight watching health bars that are
	 *         already full, which is the behaviour this whole exercise exists to stop.
	 *         <p>
	 *         A gladiator is {@code DAMAGE} and not a second tank. It can hold a mob, but putting two classes on the taunts means two bots fighting
	 *         each other for the same aggro, and the group has one tank. The aethertech is a tank, as the templar is, and a group holding both names
	 *         one of them: see {@link #groupTank}.
	 */
	public static BotRole of(PlayerClass playerClass) {
		return switch (playerClass) {
			case TEMPLAR, RIDER -> TANK;
			case CLERIC -> HEALER;
			case CHANTER, BARD -> SUPPORT;
			// Everything else, the six starting classes included. A character under ten is not in a group doing anything that needs a role, and
			// naming them here would only invite the question of what a WARRIOR is for before it has decided.
			default -> DAMAGE;
		};
	}

	public static BotRole of(Player bot) {
		return of(bot.getPlayerClass());
	}

	/**
	 * @return true if the creature is a tank, a player's or a bot's, named or not: a monster on it is being held, and a taunt would only drag it back
	 *         and forth between two tanks. What every rule that peels a monster off a mate asks of the mate first.
	 */
	public static boolean holdsAsTank(Creature creature) {
		return creature instanceof Player player && of(player) == TANK;
	}

	/** The tank classes in the order a group takes its tank from: the templar, watched in game, before the aethertech. */
	private static final List<PlayerClass> TANK_ORDER = List.of(PlayerClass.TEMPLAR, PlayerClass.RIDER);

	/** One member of a group as the choice of its tank sees it. */
	public record Seat(int objectId, PlayerClass playerClass, boolean dead) {
	}

	/**
	 * @return The member that tanks the group: a living templar if it has one, else a living aethertech, the lowest object id of each so that every
	 *         member names the same one whatever order it reads the group in. Null when no tank of the group is standing. Two tanks on the taunts would
	 *         pull the same enemy back and forth, so the other one fights as damage until this one dies.
	 */
	public static Seat groupTank(Collection<Seat> seats) {
		return seats.stream().filter(seat -> !seat.dead() && TANK_ORDER.contains(seat.playerClass()))
			.min(Comparator.comparingInt((Seat seat) -> TANK_ORDER.indexOf(seat.playerClass())).thenComparingInt(Seat::objectId)).orElse(null);
	}

	/** @return The member that tanks the team, bot or player, or null when it has none standing. */
	public static Player groupTank(TemporaryPlayerTeam<?> team) {
		if (team == null)
			return null;
		List<Player> members = team.getMembers();
		Seat tank = groupTank(members.stream().map(member -> new Seat(member.getObjectId(), member.getPlayerClass(), member.isDead())).toList());
		return tank == null ? null : members.stream().filter(member -> member.getObjectId() == tank.objectId()).findFirst().orElse(null);
	}

	/**
	 * @return true if the bot is the one holding what its group fights: a tank alone, or its group's tank. What every rule written for "the tank" asks,
	 *         rather than the role, which an aethertech beside a templar has too.
	 */
	public static boolean isGroupTank(Player bot) {
		if (of(bot) != TANK)
			return false;
		TemporaryPlayerTeam<?> team = bot.getCurrentTeam();
		return team == null || bot.equals(groupTank(team));
	}
}
