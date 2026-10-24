package com.aionemu.gameserver.network.chatserver.serverpackets;

import com.aionemu.gameserver.network.chatserver.ChatServerConnection;
import com.aionemu.gameserver.network.chatserver.CsServerPacket;

/**
 * A line a playerbot says in a chat channel. A bot has no client and so no chat session; the chat server says the line for it, to whoever reads the
 * channel of the bot's own race.
 */
public class SM_CS_BOT_MESSAGE extends CsServerPacket {

	private final String channelType;
	private final int raceId;
	private final String channelMeta;
	private final int botId;
	private final String name;
	private final String text;

	/**
	 * @param channelType The channel's type as the client names it: {@code public} (Region), {@code trade}, {@code partyFind}.
	 * @param channelMeta The map's identifier for Region and Trade, as the client sends it ({@code DC11}); anything for Find Group.
	 */
	public SM_CS_BOT_MESSAGE(String channelType, int raceId, String channelMeta, int botId, String name, String text) {
		super(0x04);
		this.channelType = channelType;
		this.raceId = raceId;
		this.channelMeta = channelMeta;
		this.botId = botId;
		this.name = name;
		this.text = text;
	}

	@Override
	protected void writeImpl(ChatServerConnection con) {
		writeS(channelType);
		writeD(raceId);
		writeS(channelMeta);
		writeD(botId);
		writeS(name);
		writeS(text);
	}
}
