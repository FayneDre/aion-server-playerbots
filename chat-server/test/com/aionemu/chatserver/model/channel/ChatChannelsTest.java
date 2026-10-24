package com.aionemu.chatserver.model.channel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.chatserver.model.ChannelType;
import com.aionemu.chatserver.model.ChatClient;
import com.aionemu.chatserver.model.Race;

/**
 * What {@link ChatChannels#find} hands a line said for a playerbot: the channel a reader of the bot's own race joined, never one of the other race,
 * never one of another map, and nothing at all where nobody joined. The identifiers are the ones a 4.8 client sent on 24 October: the map's code,
 * then the game server's id behind a separator ({@code @public_DC11.1.AION.KOR}).
 */
class ChatChannelsTest {

	private static final int GAME_SERVER = 1;

	@Test
	void aRegionLineReachesOnlyItsOwnRaceOnItsOwnMap() {
		Channel pandaemonium = join(Race.ASMODIANS, "public_DC1");
		assertSame(pandaemonium, ChatChannels.find(ChannelType.REGION, GAME_SERVER, Race.ASMODIANS, "DC1"));
		assertNull(ChatChannels.find(ChannelType.REGION, GAME_SERVER, Race.ELYOS, "DC1"), "the other race");
		assertNull(ChatChannels.find(ChannelType.REGION, GAME_SERVER, Race.ASMODIANS, "DF1A"), "another map");
	}

	@Test
	void aTradeLineReachesOnlyItsOwnRaceOnItsOwnMap() {
		Channel sanctum = join(Race.ELYOS, "trade_LC1");
		assertSame(sanctum, ChatChannels.find(ChannelType.TRADE, GAME_SERVER, Race.ELYOS, "LC1"));
		assertNull(ChatChannels.find(ChannelType.TRADE, GAME_SERVER, Race.ASMODIANS, "LC1"), "the other race");
		assertNull(ChatChannels.find(ChannelType.TRADE, GAME_SERVER, Race.ELYOS, "LF1"), "another map");
	}

	@Test
	void aFindGroupLineReachesTheWholeRaceAndOnlyIt() {
		Channel elyos = join(Race.ELYOS, "partyFind_PF");
		assertSame(elyos, ChatChannels.find(ChannelType.LFG, GAME_SERVER, Race.ELYOS, "PF"));
		Channel asmodians = join(Race.ASMODIANS, "partyFind_PF");
		assertNotSame(elyos, asmodians);
		assertSame(asmodians, ChatChannels.find(ChannelType.LFG, GAME_SERVER, Race.ASMODIANS, "PF"));
	}

	@Test
	void nothingIsCreatedForALineNobodyReads() {
		assertNull(ChatChannels.find(ChannelType.REGION, GAME_SERVER, Race.ELYOS, "LF3"));
		assertNull(ChatChannels.find(ChannelType.REGION, GAME_SERVER, Race.ELYOS, "LF3"), "still none after asking");
	}

	/** @return The channel a client of that race is given for the identifier, as its own request would. */
	private static Channel join(Race race, String typeAndMeta) {
		ChatClient reader = new ChatClient(race.ordinal() + 100, null, "account", "Reader", race, (byte) 0);
		String identifier = "@\u0001" + typeAndMeta + "\u0001" + GAME_SERVER + "." + race.getRaceId() + ".AION.KOR";
		return ChatChannels.getOrCreate(reader, identifier);
	}
}
