package com.aionemu.gameserver.playerbot.social;

import java.util.Comparator;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.network.chatserver.ChatServer;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/**
 * What bots say in the chat channels: Region, Trade and Find Group. See docs/chat-channels-plan.md.
 * <p>
 * A bot has no client and so no chat session: the chat server says its line for it ({@code SM_CS_BOT_MESSAGE}), in the channel of the bot's own race,
 * which a player of the other race never reads.
 */
public final class BotChat {

	/** The channels a bot speaks in, by the type the client names them with. */
	public enum Channel {
		REGION("public"), TRADE("trade"), FIND_GROUP("partyFind");

		private final String type;

		Channel(String type) {
			this.type = type;
		}
	}

	/** What a Find Group request carries in place of a map: the channel is one a race, whatever this says. */
	private static final String FIND_GROUP_META = "PF1";

	private BotChat() {
	}

	/**
	 * @return The identifier a client in the bot's place would ask the channel for: for Region and Trade the map's client name followed by its channel
	 *         number, {@code DC11} for the first channel of Pandaemonium ({@code DC1}), as read in game on 24 October.
	 */
	static String metaOf(Channel channel, Player bot) {
		if (channel == Channel.FIND_GROUP)
			return FIND_GROUP_META;
		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(bot.getWorldId());
		return map.getCName() + bot.getInstanceId();
	}

	/**
	 * Has the bot say the line in the channel of its race. Nothing happens when the chat server is down or nobody reads the channel.
	 *
	 * @return false when the chat server is not connected.
	 */
	static boolean say(Player bot, Channel channel, String text) {
		if (!ChatServer.getInstance().isUp())
			return false;
		ChatServer.getInstance().sendBotMessage(channel.type, bot.getRace().getRaceId(), metaOf(channel, bot), bot.getObjectId(), bot.getName(), text);
		return true;
	}

	/** @return What {@code //bot chat} answers, having had the bot nearest the caller, of its race and on its map, say a line in the channel. */
	public static String command(Player admin, String channelName, String text) {
		Channel channel = switch (channelName == null ? "" : channelName.toLowerCase()) {
			case "region" -> Channel.REGION;
			case "trade" -> Channel.TRADE;
			case "group" -> Channel.FIND_GROUP;
			default -> null;
		};
		if (channel == null)
			return "Usage: //bot chat <region|trade|group> [text]";
		Player speaker = World.getInstance().getAllPlayers().stream()
			.filter(player -> player.isBot() && player.isSpawned() && player.getRace() == admin.getRace() && player.getWorldId() == admin.getWorldId())
			.min(Comparator.comparingDouble(player -> PositionUtil.getDistance(player, admin))).orElse(null);
		if (speaker == null)
			return "No bot of your faction on this map.";
		String line = text != null ? text : "Test du canal " + channelName.toLowerCase() + ", " + speaker.getName() + " au rapport.";
		if (!say(speaker, channel, line))
			return "The chat server is not connected: set gameserver.chatserver.enable and the chat password.";
		return speaker.getName() + " says in " + channelName.toLowerCase() + " (" + metaOf(channel, speaker) + "): " + line;
	}
}
