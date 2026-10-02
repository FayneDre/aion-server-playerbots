package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.dao.ServerVariablesDAO;

/**
 * What the bot system remembers about a character between runs: whether it belongs in the world, who owns it, whether it is a resident, and where
 * it lives.
 * <p>
 * That is all: where each bot stands, what it carries and what it has learned are already saved with the character itself, so a restored bot comes
 * back exactly where it left off. It is written on every change rather than at shutdown, because a shutdown that never runs is precisely the case it
 * exists for.
 * <p>
 * It lives in its own table keyed by character id, for two reasons. The first is that {@code server_variables} holds a {@code varchar(30)} — a list
 * of names fits three bots and is then truncated <i>by the database</i>, which is how a population of forty five came back empty. The second is that
 * a foreign key onto {@code players} removes the row with the character, so deleting a bot can never leave a name behind to be looked for at the
 * next start.
 * <p>
 * It is deliberately not the {@code players.online} column: that one means "has a client connected", which no bot ever does, and borrowing it would
 * put a second, contradictory answer next to {@code Player.isOnline()}.
 */
public class BotRoster {

	private static final Logger log = LoggerFactory.getLogger(BotRoster.class);
	/** Orders left for the next start. Short values, and genuinely server settings, so these stay in {@code server_variables}. */
	public static final String CLEAR_ORDER = "playerbot.order.clear";
	public static final String POPULATE_ORDER = "playerbot.order.populate";

	private static final String UPSERT = "INSERT INTO `playerbot_characters` (`player_id`, `%s`) VALUES (?, ?) "
		+ "ON DUPLICATE KEY UPDATE `%s` = VALUES(`%s`)";
	private static final String NAMES_WHERE = "SELECT p.`name` FROM `playerbot_characters` b JOIN `players` p ON p.`id` = b.`player_id` WHERE b.`%s` = 1";

	/**
	 * One of the world's own inhabitants, as the population director needs to know it: which character, which map, and the place it belongs to.
	 * <p>
	 * No level, and not by oversight. There is no level column — a character's level is derived from its experience, and {@code old_level} is written
	 * only on a real client's logout, so it reads zero for every bot ever created. Nor is one needed: the level band of a region is already settled by
	 * the home the character was given, so a home is the stronger answer to the same question.
	 */
	public record Resident(int playerId, String name, int worldId, Vector3f home) {
	}

	private BotRoster() {
	}

	/**
	 * Records exactly who is in the world, which is the set of bots the next start puts back.
	 * <p>
	 * The whole set is written rather than the one bot that changed: the caller holds the truth, and a single statement that says "these and nobody
	 * else" cannot drift from it the way a sequence of additions and removals can.
	 * <p>
	 * By character id, because the caller has the characters. Taking names meant looking each one up again — one query per bot, on every call, for
	 * an answer the caller was holding: populating a map of forty five ran a thousand of them.
	 */
	public static void remember(Collection<Integer> playerIds) {
		try (Connection con = DatabaseFactory.getConnection()) {
			boolean autoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try {
				try (PreparedStatement clear = con.prepareStatement("UPDATE `playerbot_characters` SET `in_world` = 0")) {
					clear.executeUpdate();
				}
				try (PreparedStatement mark = con.prepareStatement(upsert("in_world"))) {
					for (int playerId : playerIds) {
						mark.setInt(1, playerId);
						mark.setInt(2, 1);
						mark.addBatch();
					}
					mark.executeBatch();
				}
				con.commit();
			} catch (SQLException e) {
				con.rollback();
				throw e;
			} finally {
				con.setAutoCommit(autoCommit);
			}
		} catch (SQLException e) {
			log.error("Could not remember the roster of " + playerIds.size() + " bot(s)", e);
		}
	}

	/** @return The names of the bots that were in the world when the roster was last written. Empty when there were none. */
	public static Set<String> restore() {
		return namesFlagged("in_world");
	}

	/**
	 * Tells a bot that lives somewhere from one that is passing through.
	 * <p>
	 * A <b>resident</b> is part of a place: it is fixed at the level of the region it inhabits and gains no experience, so that region keeps
	 * inhabitants who belong to it. Left to progress, every bot drifts upwards and the low zones empty — Poeta is a region of levels one to eight,
	 * and five bots reached thirteen in a day, farming grey mobs in a valley meant for beginners.
	 * <p>
	 * An <b>adventurer</b> is a character: it levels, it can be grouped with, and one day it will travel. There are meant to be few of them.
	 */
	public static boolean isResident(String characterName) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT b.`resident` FROM `playerbot_characters` b "
					 + "JOIN `players` p ON p.`id` = b.`player_id` WHERE p.`name` = ?")) {
			stmt.setString(1, characterName);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next() && rs.getBoolean(1);
			}
		} catch (SQLException e) {
			log.error("Could not read whether " + characterName + " is a resident", e);
			return false;
		}
	}

	public static void setResident(String characterName, boolean resident) {
		int playerId = PlayerDAO.getPlayerIdByName(characterName);
		if (playerId == 0) {
			log.warn("Cannot set the kind of {}: no character by that name", characterName);
			return;
		}
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement(upsert("resident"))) {
			stmt.setInt(1, playerId);
			stmt.setInt(2, resident ? 1 : 0);
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Could not record the kind of " + characterName, e);
		}
	}

	public static Set<String> residents() {
		return namesFlagged("resident");
	}

	/**
	 * Every inhabitant the world owns, awake or asleep, which is the pool the population director draws on.
	 * <p>
	 * Deliberately not held in memory. Which characters exist is the database's answer and nothing else's, and a copy of it kept alongside would be a
	 * second truth to go stale — see {@code docs/engine-traps.md}. Which of these is in the world right now is a different question, answered by the
	 * set of spawned bots, so "the pool" is this list minus that set rather than a thing either of them has to maintain.
	 * <p>
	 * Both halves of the ownership test are applied, not just the obvious one. {@code owner_id = 0} alone would let through a character sitting on a
	 * <b>real account</b> that happens to have a row here, and logging one of those in is the server playing somebody's character — the one thing it
	 * must never do. So the account id is tested as well, exactly as {@link #isSomebodysOwn} does.
	 *
	 * @return One entry per character, in no particular order. Empty when nothing has been populated, or when the query fails — a director that is
	 *         told the world is empty does nothing, which is the right way for this to fail.
	 */
	public static List<Resident> pool() {
		List<Resident> residents = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT p.`id`, p.`name`, p.`world_id`, b.`home_x`, b.`home_y`, b.`home_z` "
					 + "FROM `playerbot_characters` b JOIN `players` p ON p.`id` = b.`player_id` "
					 + "WHERE b.`resident` = 1 AND b.`owner_id` = 0 AND p.`account_id` >= ?")) {
			stmt.setInt(1, PlayerBotCreationService.BOT_ACCOUNT_ID_BASE);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					float x = rs.getFloat(4), y = rs.getFloat(5), z = rs.getFloat(6);
					// A resident with no home has nowhere to be woken to, so it is not part of the pool. It can still be spawned by hand.
					if (x == 0 && y == 0)
						continue;
					residents.add(new Resident(rs.getInt(1), rs.getString(2), rs.getInt(3), new Vector3f(x, y, z)));
				}
			}
		} catch (SQLException e) {
			log.error("Could not read the pool of the world's own inhabitants", e);
		}
		return residents;
	}

	/**
	 * Records whether one bot is in the world, for the next start to put it back.
	 * <p>
	 * One row, where {@link #remember} rewrites the whole table. That is the difference between a population being created once and a population being
	 * adjusted every half minute: "these and nobody else" is the safer statement, but as a loop it is a full-table write lock competing with the save
	 * sweep and with whatever real players are doing.
	 */
	public static void setInWorld(int playerId, boolean inWorld) {
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement stmt = con.prepareStatement(upsert("in_world"))) {
			stmt.setInt(1, playerId);
			stmt.setBoolean(2, inWorld);
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Could not record whether bot " + playerId + " is in the world", e);
		}
	}

	/**
	 * @return The object id of the player who made this bot, or 0 if the world did.
	 *         <p>
	 *         This is what separates a character somebody made for themselves from one the server made to populate a region. A bot with an owner is
	 *         theirs: the population director must never log it out, move it, level it or retire it, and nobody else may delete it. A bot with no
	 *         owner belongs to the world and is the director's to manage.
	 */
	public static int ownerOf(String characterName) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT b.`owner_id` FROM `playerbot_characters` b "
					 + "JOIN `players` p ON p.`id` = b.`player_id` WHERE p.`name` = ?")) {
			stmt.setString(1, characterName);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			log.error("Could not read who owns " + characterName, e);
			return 0;
		}
	}

	/**
	 * @return true when this character is somebody's own rather than one of the world's inhabitants.
	 *         <p>
	 *         Two things make it so, and both have to be asked because they arrive by different doors. A character <b>on a real account</b> is a
	 *         person's, full stop: the server's own bots live on reserved accounts from {@code BOT_ACCOUNT_ID_BASE} upwards precisely so that they can
	 *         never be confused with a player's characters. That covers the ordinary case of spawning a character you made yourself in game, which
	 *         records no owner anywhere and which nothing else would recognise. A recorded <b>owner</b> covers the other case: a bot made with
	 *         {@code //bot create}, which lives on a reserved account and still belongs to whoever asked for it.
	 *         <p>
	 *         Asked once as the ai is built, so the two halves are one query.
	 */
	public static boolean isSomebodysOwn(String characterName) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT p.`account_id`, COALESCE(b.`owner_id`, 0) FROM `players` p "
					 + "LEFT JOIN `playerbot_characters` b ON b.`player_id` = p.`id` WHERE p.`name` = ?")) {
			stmt.setString(1, characterName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next())
					return false;
				return rs.getInt(1) < PlayerBotCreationService.BOT_ACCOUNT_ID_BASE || rs.getInt(2) != 0;
			}
		} catch (SQLException e) {
			log.error("Could not read whether " + characterName + " belongs to anybody", e);
			// The world's answer, because it is the reversible one: a character wrongly left to the world is paced and capped until the next restart,
			// where one wrongly taken from it is quietly dropped out of the population and never replaced.
			return false;
		}
	}

	/** Records who a bot belongs to. 0 gives it to the world. */
	public static void setOwner(String characterName, int ownerId) {
		int playerId = PlayerDAO.getPlayerIdByName(characterName);
		if (playerId == 0) {
			log.warn("Cannot set the owner of {}: no character by that name", characterName);
			return;
		}
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement(upsert("owner_id"))) {
			stmt.setInt(1, playerId);
			stmt.setInt(2, ownerId);
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Could not record the owner of " + characterName, e);
		}
	}

	/**
	 * Records where a bot lives.
	 * <p>
	 * Kept with the character rather than worked out again from the map, because working it out again is what put every bot in the same village. A
	 * home derived from the bot's id and the list of settlements has no memory of where the bot was actually put down, so the moment it went
	 * loitering it walked to whichever village the formula named — and the formula named the biggest one for most of them.
	 */
	public static void setHome(String characterName, Vector3f home) {
		int playerId = PlayerDAO.getPlayerIdByName(characterName);
		if (playerId == 0) {
			log.warn("Cannot set the home of {}: no character by that name", characterName);
			return;
		}
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("INSERT INTO `playerbot_characters` (`player_id`, `home_x`, `home_y`, `home_z`) "
					 + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE `home_x` = VALUES(`home_x`), `home_y` = VALUES(`home_y`), `home_z` = VALUES(`home_z`)")) {
			stmt.setInt(1, playerId);
			stmt.setFloat(2, home.getX());
			stmt.setFloat(3, home.getY());
			stmt.setFloat(4, home.getZ());
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Could not record the home of " + characterName, e);
		}
	}

	/** @return Where this bot lives, or null when it has never been given a home. */
	public static Vector3f homeOf(String characterName) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT b.`home_x`, b.`home_y`, b.`home_z` FROM `playerbot_characters` b "
					 + "JOIN `players` p ON p.`id` = b.`player_id` WHERE p.`name` = ?")) {
			stmt.setString(1, characterName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next())
					return null;
				float x = rs.getFloat(1), y = rs.getFloat(2), z = rs.getFloat(3);
				return x == 0 && y == 0 ? null : new Vector3f(x, y, z);
			}
		} catch (SQLException e) {
			log.error("Could not read the home of " + characterName, e);
			return null;
		}
	}

	/**
	 * Reads an order left for this start and forgets it, so it is carried out once and never again.
	 *
	 * @return What the order said, or null when none was left.
	 */
	public static String takeOrder(String order) {
		String value = load(order);
		if (value == null || value.isBlank())
			return null;
		ServerVariablesDAO.store(order, "");
		return value.trim();
	}

	private static Set<String> namesFlagged(String column) {
		Set<String> names = new LinkedHashSet<>();
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement(String.format(NAMES_WHERE, column));
				 ResultSet rs = stmt.executeQuery()) {
			while (rs.next())
				names.add(rs.getString("name"));
		} catch (SQLException e) {
			log.error("Could not read the bots flagged " + column, e);
		}
		return names;
	}

	/** The column name never comes from outside this class, so formatting it into the statement is safe and keeps one query instead of three. */
	private static String upsert(String column) {
		return String.format(UPSERT, column, column, column);
	}

	/**
	 * Reads a server variable back. {@code ServerVariablesDAO} can store any value but only reads numbers back, its string loader being private, so
	 * the one query lives here instead of widening the engine's api for a single caller.
	 */
	private static String load(String variable) {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT `value` FROM `server_variables` WHERE `key` = ?")) {
			stmt.setString(1, variable);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next())
					return rs.getString("value");
			}
		} catch (SQLException e) {
			log.error("Could not read " + variable, e);
		}
		return null;
	}
}
