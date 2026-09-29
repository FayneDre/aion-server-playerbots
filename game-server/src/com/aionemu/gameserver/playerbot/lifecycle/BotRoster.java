package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.dao.PlayerDAO;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.dao.ServerVariablesDAO;

/**
 * The two things the bot system remembers about a character between runs: whether it belongs in the world, and whether it is a resident.
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

	private BotRoster() {
	}

	/**
	 * Records exactly who is in the world, which is the set of bots the next start puts back.
	 * <p>
	 * The whole set is written rather than the one bot that changed: the caller holds the truth, and a single statement that says "these and nobody
	 * else" cannot drift from it the way a sequence of additions and removals can.
	 */
	public static void remember(Set<String> characterNames) {
		try (Connection con = DatabaseFactory.getConnection()) {
			boolean autoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try {
				try (PreparedStatement clear = con.prepareStatement("UPDATE `playerbot_characters` SET `in_world` = 0")) {
					clear.executeUpdate();
				}
				try (PreparedStatement mark = con.prepareStatement(upsert("in_world"))) {
					for (String characterName : characterNames) {
						int playerId = PlayerDAO.getPlayerIdByName(characterName);
						if (playerId == 0) {
							log.warn("Cannot remember {}: no character by that name", characterName);
							continue;
						}
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
			log.error("Could not remember the roster of " + characterNames.size() + " bot(s)", e);
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
