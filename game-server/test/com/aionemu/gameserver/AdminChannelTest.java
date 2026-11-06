package com.aionemu.gameserver;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The launcher tells a server too old for it by the answer to {@code version}, and by the install's {@code manifest.json} while the server is stopped,
 * which {@code deploy.ps1} fills from the source line of {@link AdminChannel#API_LEVEL}.
 */
class AdminChannelTest {

	/** The pattern of tools/deploy.ps1: if this line is reworded, the manifest loses its level and the launcher takes the server for level 0. */
	private static final Pattern DEPLOY_PATTERN = Pattern.compile("public static final int API_LEVEL = (\\d+);");

	@Test
	void versionAnswersOneKeyAndValueALine() {
		assertEquals("ok\nversion v0.1.0-beta.2\nrevision abc1234\napi " + AdminChannel.API_LEVEL, AdminChannel.version("v0.1.0-beta.2", "abc1234"));
	}

	@Test
	void versionSaysUnknownForARevisionThatIsMissing() {
		assertTrue(AdminChannel.version(ServerRelease.DEV, null).contains("\nrevision unknown\n"));
	}

	@Test
	void aBuildWithNoReleaseIsDev() {
		assertEquals(ServerRelease.DEV, ServerRelease.read(null));
		assertEquals(ServerRelease.DEV, ServerRelease.read(" "));
		assertEquals("v0.1.0-beta.2", ServerRelease.read("v0.1.0-beta.2"));
	}

	@Test
	void deployReadsTheLevelFromTheSource() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/AdminChannel.java"));
		var matcher = DEPLOY_PATTERN.matcher(source);
		assertTrue(matcher.find(), "deploy.ps1 looks for: " + DEPLOY_PATTERN);
		assertEquals(AdminChannel.API_LEVEL, Integer.parseInt(matcher.group(1)));
	}
}
