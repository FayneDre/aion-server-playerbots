package com.aionemu.gameserver.dao;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;

/**
 * Brings the database up to the schema this build expects, by adding what is missing.
 * <p>
 * The contract until now was that an administrator creates the database from {@code aion_gs.sql} and applies {@code update.sql} by hand after every
 * pull. That works for the person who wrote the update and nobody else: somebody who clones the repository, builds it and runs it gets an exception
 * from a table or a column they were never told about, at the moment they least expect it. The build already knows what the schema should be — it
 * ships with it — so it can say what is missing and add it.
 * <p>
 * <b>It only ever adds.</b> Missing tables are created and missing columns appended; nothing is dropped, narrowed, retyped or reordered, and a
 * column whose definition has merely changed is left alone and reported. That is the line between a convenience and a thing that eats data: it can
 * leave a database out of date, which is visible and recoverable, and it cannot destroy one.
 */
public class SchemaUpdater {

	private static final Logger log = LoggerFactory.getLogger(SchemaUpdater.class);
	private static final String REFERENCE = "/sql/aion_gs.sql";
	private static final Pattern TABLE = Pattern.compile("CREATE TABLE `(\\w+)` \\((.*?)\\n\\) ([^;]*);", Pattern.DOTALL);
	private static final Pattern COLUMN = Pattern.compile("^\\s*`(\\w+)` (.+?),?$");

	private SchemaUpdater() {
	}

	/** Compares the database against the schema this build ships with, and adds whatever it lacks. Safe to call on every start. */
	public static void update() {
		String reference;
		try (InputStream in = SchemaUpdater.class.getResourceAsStream(REFERENCE)) {
			if (in == null) {
				log.warn("No reference schema in this build, cannot check the database against it");
				return;
			}
			reference = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("Could not read the reference schema", e);
			return;
		}

		int tablesAdded = 0, columnsAdded = 0;
		try (Connection con = DatabaseFactory.getConnection(); Statement statement = con.createStatement()) {
			// while creating: the reference file is in no particular order, so a table can name one that has not been made yet
			statement.executeUpdate("SET FOREIGN_KEY_CHECKS = 0");
			try {
				Matcher tables = TABLE.matcher(reference);
				while (tables.find()) {
					String name = tables.group(1);
					if (!exists(con, name)) {
						log.info("Creating the missing table {}", name);
						statement.executeUpdate("CREATE TABLE `" + name + "` (" + tables.group(2) + "\n) " + tables.group(3));
						tablesAdded++;
						continue;
					}
					columnsAdded += addMissingColumns(con, statement, name, tables.group(2));
				}
			} finally {
				statement.executeUpdate("SET FOREIGN_KEY_CHECKS = 1");
			}
		} catch (SQLException e) {
			log.error("Could not bring the database up to date", e);
			return;
		}
		if (tablesAdded > 0 || columnsAdded > 0)
			log.info("Database brought up to date: {} table(s) and {} column(s) added", tablesAdded, columnsAdded);
	}

	/**
	 * Appends the columns the reference has and the database does not.
	 * <p>
	 * Appends, never inserts in place: column order is nothing a query depends on, while {@code AFTER} would require knowing that the column it names
	 * is itself present, on a database that is by definition out of date.
	 */
	private static int addMissingColumns(Connection con, Statement statement, String table, String body) throws SQLException {
		Map<String, String> wanted = columnsIn(body);
		List<String> present = columnNames(con, table);
		int added = 0;
		for (Map.Entry<String, String> column : wanted.entrySet()) {
			if (present.contains(column.getKey().toLowerCase()))
				continue;
			log.info("Adding the missing column {}.{}", table, column.getKey());
			statement.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN `" + column.getKey() + "` " + column.getValue());
			added++;
		}
		return added;
	}

	/** @return The columns declared in a CREATE TABLE body, by name. Keys, constraints and indexes are not columns and are skipped. */
	private static Map<String, String> columnsIn(String body) {
		Map<String, String> columns = new LinkedHashMap<>();
		for (String line : body.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("PRIMARY KEY") || trimmed.startsWith("KEY") || trimmed.startsWith("UNIQUE") || trimmed.startsWith("CONSTRAINT")
				|| trimmed.startsWith("INDEX") || trimmed.startsWith("FOREIGN KEY"))
				continue;
			Matcher column = COLUMN.matcher(line);
			if (column.matches())
				columns.put(column.group(1), column.group(2).trim());
		}
		return columns;
	}

	private static boolean exists(Connection con, String table) throws SQLException {
		try (ResultSet tables = con.getMetaData().getTables(con.getCatalog(), null, table, new String[] { "TABLE" })) {
			return tables.next();
		}
	}

	private static List<String> columnNames(Connection con, String table) throws SQLException {
		List<String> names = new ArrayList<>();
		DatabaseMetaData metaData = con.getMetaData();
		try (ResultSet columns = metaData.getColumns(con.getCatalog(), null, table, null)) {
			while (columns.next())
				names.add(columns.getString("COLUMN_NAME").toLowerCase());
		}
		return names;
	}
}
