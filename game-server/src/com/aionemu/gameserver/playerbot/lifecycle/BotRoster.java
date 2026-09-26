package com.aionemu.gameserver.playerbot.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.dao.ServerVariablesDAO;

/**
 * Remembers which bots belong in the world, so a restart puts back what was there rather than an empty map.
 * <p>
 * The roster is the set of names, nothing more: where each bot stands, what it carries and what it has learned are already saved with the character
 * itself, so a restored bot comes back exactly where it left off. That is also why the roster is written on every change rather than at shutdown —
 * a shutdown that never runs is precisely the case this exists for.
 * <p>
 * It is kept out of the {@code players} table's {@code online} column on purpose. That column means "has a client connected", which no bot ever
 * does, and borrowing it would put a second, contradictory answer next to {@code Player.isOnline()} — the kind of disagreement that has already cost
 * this project a day.
 */
public class BotRoster {

	private static final Logger log = LoggerFactory.getLogger(BotRoster.class);
	private static final String VARIABLE = "playerbot.roster";
	private static final String SEPARATOR = ",";

	private BotRoster() {
	}

	public static void remember(Set<String> characterNames) {
		ServerVariablesDAO.store(VARIABLE, String.join(SEPARATOR, characterNames));
	}

	/** @return The names remembered by the last {@link #remember}, in the order they were written. Empty when nothing was ever stored. */
	public static Set<String> restore() {
		String stored = load();
		if (stored == null || stored.isBlank())
			return Set.of();
		return new LinkedHashSet<>(Arrays.asList(stored.split(SEPARATOR)));
	}

	/**
	 * Reads the variable back. {@code ServerVariablesDAO} can store any value but only reads numbers back, its string loader being private, so the
	 * one query lives here instead of widening the engine's api for a single caller.
	 */
	private static String load() {
		try (Connection con = DatabaseFactory.getConnection();
				 PreparedStatement stmt = con.prepareStatement("SELECT `value` FROM `server_variables` WHERE `key` = ?")) {
			stmt.setString(1, VARIABLE);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next())
					return rs.getString("value");
			}
		} catch (SQLException e) {
			log.error("Could not read the bot roster", e);
		}
		return null;
	}
}
