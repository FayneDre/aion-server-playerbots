package com.aionemu.chatserver.network.gameserver.clientpackets;

import java.nio.ByteBuffer;

import com.aionemu.chatserver.model.ChannelType;
import com.aionemu.chatserver.model.Race;
import com.aionemu.chatserver.network.gameserver.GsClientPacket;
import com.aionemu.chatserver.network.gameserver.GsConnection;
import com.aionemu.chatserver.service.ChatService;

/**
 * A line said in a channel by a character the game server drives, which has no chat client of its own: the game server's playerbots.
 */
public class CM_BOT_MESSAGE extends GsClientPacket {

	private String channelType;
	private int raceId;
	private String channelMeta;
	private int botId;
	private String name;
	private String text;

	public CM_BOT_MESSAGE(ByteBuffer buf, GsConnection connection) {
		super(buf, connection, 0x04);
	}

	@Override
	protected void readImpl() {
		channelType = readS();
		raceId = readD();
		channelMeta = readS();
		botId = readD();
		name = readS();
		text = readS();
	}

	@Override
	protected void runImpl() {
		ChannelType type = ChannelType.getByIdentifier(channelType);
		if (type != null)
			ChatService.getInstance().sendBotMessage(type, Race.getById(raceId), channelMeta, botId, name, text);
	}
}
