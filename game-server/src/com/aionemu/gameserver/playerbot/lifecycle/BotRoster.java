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
 * The two lists the bot system keeps between runs: which bots belong in the world, and which of them are residents.
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
	private static final String ROSTER = "playerbot.roster";
	private static final String RESIDENTS = "playerbot.residents";
	private static final String SEPARATOR = ",";

	private BotRoster() {
	}

	public static void remember(Set<String> characterNames) {
		ServerVariablesDAO.store(ROSTER, String.join(SEPARATOR, characterNames));
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
		return residents().contains(characterName);
	}

	public static void setResident(String characterName, boolean resident) {
		Set<String> names = residents();
		if (resident ? !names.add(characterName) : !names.remove(characterName))
			return; // already what it should be
		ServerVariablesDAO.store(RESIDENTS, String.join(SEPARATOR, names));
	}

	public static Set<String> residents() {
		return namesIn(RESIDENTS);
	}

	/** @return The names remembered by the last {@link #remember}, in the order they were written. Empty when nothing was ever stored. */
	public static Set<String> restore() {
		return namesIn(ROSTER);
	}

	private static Set<String> namesIn(String variable) {
		String stored = load(variable);
		if (stored == null || stored.isBlank())
			return new LinkedHashSet<>();
		return new LinkedHashSet<>(Arrays.asList(stored.split(SEPARATOR)));
	}

	/**
	 * Reads the variable back. {@code ServerVariablesDAO} can store any value but only reads numbers back, its string loader being private, so the
	 * one query lives here instead of widening the engine's api for a single caller.
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
