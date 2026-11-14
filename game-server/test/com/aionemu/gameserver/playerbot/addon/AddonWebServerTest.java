package com.aionemu.gameserver.playerbot.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.configs.administration.AdminConfig;

/**
 * The character an addon page names, read from what the client's browser sends.
 */
class AddonWebServerTest {

	@BeforeAll
	static void defaultTags() {
		AdminConfig.NAME_TAGS = new String[] { "%s", "»JDev«%s", "»Dev«%s", "»JEM«%s",
			"»EM«%s", "»JGM«%s", "»GM«%s", "»SGM«%s", "»Admin«%s" };
	}

	@Test
	void aPlayerNameIsTakenAsItIs() {
		assertEquals("Nemesis", AddonWebServer.characterName("Nemesis"));
	}

	@Test
	void anAdministratorsTagAsTheClientSendsItIsDropped() {
		// the query an administrator's combat meter sent, from the game server's log
		String sent = URLDecoder.decode("%EF%BF%82%EF%BE%BBAdmin%EF%BF%82%EF%BE%AB%EF%BF%AE%EF%BE%81%EF%BE%8ANemesis", StandardCharsets.UTF_8);
		assertEquals("Nemesis", AddonWebServer.characterName(sent));
	}

	@Test
	void aTagInPlainCharactersIsDroppedToo() {
		assertEquals("Nemesis", AddonWebServer.characterName("»GM«Nemesis"));
	}
}
