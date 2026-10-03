package com.aionemu.gameserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.ExitCode;

/**
 * Shuts the server down when a file appears beside it, so a script can ask for a graceful stop.
 * <p>
 * The alternative was a real console CTRL+C, sent by attaching to the server's console from another process. That works, and it worked about half the
 * time: whether the event reaches the jvm depends on a console the script does not own, shared with the {@code cmd.exe} running {@code start.bat},
 * which answers the same event with a "terminate batch job" prompt of its own. Four stops in one evening, two of which ended in the process being
 * killed and the save lost. Nothing about that is diagnosable from outside, which is the real argument against it.
 * <p>
 * A file is none of those things. The server owns the check, it is one line to make by hand, and when it does not work the reason is visible in this
 * log. It runs the same {@link ShutdownHook} as every other exit, so players and bots are saved exactly as they would be.
 * <p>
 * <b>Not a remote control.</b> Writing the file requires write access to the server's own directory, which is already enough to replace the jar.
 */
public class ShutdownRequestWatcher {

	private static final Logger log = LoggerFactory.getLogger(ShutdownRequestWatcher.class);
	/** Relative to the working directory, which is the game-server folder: the same place the data and config are read from. */
	private static final Path REQUEST = Path.of("shutdown.request");
	private static final long POLL_MILLIS = 500;

	private ShutdownRequestWatcher() {
	}

	public static void start() {
		// A file left behind by a previous run would shut this one down the moment it started, so the slate is cleared before the watch begins and
		// not after the first poll.
		if (!delete())
			return;
		Thread.ofVirtual().name("shutdown-request-watcher").start(ShutdownRequestWatcher::watch);
		log.info("Watching for {} to shut down gracefully", REQUEST.toAbsolutePath());
	}

	private static void watch() {
		while (!Files.exists(REQUEST)) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		log.info("Shutdown requested through {}", REQUEST.toAbsolutePath());
		// Removed before the shutdown rather than after it: the hook can take a while over a world full of characters, and a file still lying there
		// when the server comes back up would stop it again.
		delete();
		GameServer.initShutdown(ExitCode.NORMAL, 0);
	}

	/** @return true if the file is gone, false if it is there and could not be removed, in which case watching would be a trap. */
	private static boolean delete() {
		try {
			Files.deleteIfExists(REQUEST);
			return true;
		} catch (IOException e) {
			log.error("Cannot remove {}, so no shutdown will be watched for", REQUEST.toAbsolutePath(), e);
			return false;
		}
	}
}
