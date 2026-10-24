package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.Race;

/**
 * Which faction an instance is called for in Find Group, read off its id, with the pairs of faction instances the data holds.
 */
class BotChatTest {

	@Test
	void eachFactionsOwnInstancesAreItsAlone() {
		assertEquals(Race.ELYOS, BotChat.sideOf(310090000), "Indratu Fortress");
		assertEquals(Race.ASMODIANS, BotChat.sideOf(320080000), "Draupnir Cave");
		assertEquals(Race.ELYOS, BotChat.sideOf(310110000), "Theobomos Lab");
		assertEquals(Race.ASMODIANS, BotChat.sideOf(320130000), "Adma Stronghold");
		assertEquals(Race.PC_ALL, BotChat.sideOf(300040000), "Dark Poeta, shared");
		assertEquals(Race.PC_ALL, BotChat.sideOf(301230000), "Illuminary Obelisk, shared");
	}
}
