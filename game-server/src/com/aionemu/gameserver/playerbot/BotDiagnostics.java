package com.aionemu.gameserver.playerbot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.GSConfig;
import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.playerbot.social.BotSquads;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * What a test session needs from the server besides the bots themselves: a mark in the log where something looked wrong, the revision being tested, and
 * the detail of every cast on demand. The plan is {@code docs/test-harness-plan.md}.
 */
public final class BotDiagnostics {

	private static final org.slf4j.Logger log = LoggerFactory.getLogger(BotDiagnostics.class);

	/** The word every line of a note starts with, which is what {@code tools/collect-feedback.ps1} finds them by. */
	public static final String NOTE_MARK = "BOT NOTE";
	/** How far from the caller a bot is described when the caller is in no group. */
	private static final float NOTE_RADIUS = 30;
	/**
	 * The loggers {@code //bot debug} raises, by scope. Combat is where the casts are logged, one line each with its target, and the AI is where a fight is
	 * started and given up; together they are what a fight looked like from the inside.
	 */
	private static final Map<String, List<String>> DEBUG_SCOPES = Map.of(
		"combat", List.of("com.aionemu.gameserver.playerbot.combat", "com.aionemu.gameserver.playerbot.ai"),
		"all", List.of("com.aionemu.gameserver.playerbot"));

	private static final AtomicInteger notes = new AtomicInteger();
	/** The levels the raised loggers had before, so that turning debug off gives back what {@code logback.xml} set rather than clearing it. */
	private static final Map<String, Level> levelsBeforeDebug = new HashMap<>();

	private BotDiagnostics() {
	}

	/**
	 * Writes a note to the log: the text, where the caller stands, and the state of every bot in the caller's group, or near the caller when there is none.
	 * A tester types it the moment something looks wrong, so the minute that matters can be found in a log of eighteen megabytes.
	 *
	 * @param caller Who typed it, or null when it came from the command inbox: the note then describes every bot in a squad, which is what a test run
	 *          without a client is watching.
	 * @return The answer for the chat window, with the note's number so a report can quote it.
	 */
	public static String note(String text, Player caller) {
		int number = notes.incrementAndGet();
		String where = caller == null ? "no caller" : String.format("%s on map %d instance %d at %.0f %.0f %.0f", caller.getName(), caller.getWorldId(),
			caller.getInstanceId(), caller.getX(), caller.getY(), caller.getZ());
		log.info("{} #{} \"{}\" by {}, positioning {}", NOTE_MARK, number, text == null ? "" : text, where, PlayerBotConfig.POSITIONING);
		List<Player> described = caller == null ? squadMembers() : botsAround(caller);
		for (Player bot : described)
			log.info("{} #{} | {}", NOTE_MARK, number, describe(bot));
		return "Note #" + number + " written to the log with " + described.size() + " bot(s) described.";
	}

	private static List<Player> squadMembers() {
		return PlayerBotService.getInstance().spawnedBots().stream().filter(bot -> BotSquads.isSquad(bot.getCurrentTeam())).toList();
	}

	/** @return The bots in the caller's group, or those within {@value #NOTE_RADIUS} m of the caller when it has none. */
	private static List<Player> botsAround(Player caller) {
		List<Player> bots = new ArrayList<>();
		TemporaryPlayerTeam<?> team = caller.getCurrentTeam();
		if (team != null) {
			for (Player member : team.getMembers()) {
				if (member.isBot())
					bots.add(member);
			}
			return bots;
		}
		caller.getKnownList().forEachPlayer(player -> {
			if (player.isBot() && PositionUtil.isInRange(caller, player, NOTE_RADIUS))
				bots.add(player);
		});
		return bots;
	}

	/** @return One bot's state in one line: who it is, its health, what it is doing and to what, and what that is doing in turn. */
	static String describe(Player bot) {
		StringBuilder line = new StringBuilder(String.format("%s %s %s lv%d hp %d%% mp %d%% dp %d, %s", bot.getName(), bot.getPlayerClass(),
			BotRole.of(bot), bot.getLevel(), bot.getLifeStats().getHpPercentage(), bot.getLifeStats().getMpPercentage(), bot.getCommonData().getDp(),
			bot.isDead() ? "DEAD" : bot.getAi().getState()));
		if (bot.getCastingSkill() != null)
			line.append(", casting ").append(bot.getCastingSkill().getSkillTemplate().getName());
		VisibleObject target = bot.getTarget();
		if (target != null) {
			line.append(String.format(", target %s at %.1f m", target.getName(), PositionUtil.getDistance(bot, target)));
			if (target instanceof Creature creature) {
				line.append(String.format(" (hp %d%%", creature.getLifeStats().getHpPercentage()));
				if (creature.getTarget() != null)
					line.append(", on ").append(creature.getTarget().getName());
				line.append(')');
			}
		}
		line.append(String.format(", at %.1f %.1f %.1f", bot.getX(), bot.getY(), bot.getZ()));
		return line.toString();
	}

	/** @return The revision, branch and build date the server runs, which is what a report names so the fix it is about can be found. */
	public static String version() {
		return "Server " + GameServer.versionInfo.getBuildInfo(GSConfig.TIME_ZONE_ID) + ".";
	}

	/**
	 * Reads or switches the detailed log of the bot module: every cast with its target, every fight started and given up. Lasts until the next restart.
	 *
	 * @param scope "combat", "all", "off", or null to read it.
	 */
	public static synchronized String debug(String scope) {
		if (scope == null)
			return levelsBeforeDebug.isEmpty() ? "Bot debug logging is off." : "Bot debug logging is on for " + String.join(", ", levelsBeforeDebug.keySet()) + ".";
		String wanted = scope.toLowerCase();
		if (wanted.equals("on"))
			wanted = "combat";
		if (!wanted.equals("off") && !DEBUG_SCOPES.containsKey(wanted))
			return "Usage: //bot debug [combat|all|off]";
		// whatever was raised before goes back first, so switching from one scope to another does not leave the first one up
		levelsBeforeDebug.forEach((name, level) -> logger(name).setLevel(level));
		levelsBeforeDebug.clear();
		if (wanted.equals("off")) {
			log.info("Bot debug logging switched off");
			return "Bot debug logging is off.";
		}
		for (String name : DEBUG_SCOPES.get(wanted)) {
			Logger logger = logger(name);
			levelsBeforeDebug.put(name, logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}
		log.info("Bot debug logging switched on for {}", wanted);
		return "Bot debug logging is on for " + wanted + ", until the next restart. Every cast is now in the log.";
	}

	private static Logger logger(String name) {
		return (Logger) LoggerFactory.getLogger(name);
	}
}
