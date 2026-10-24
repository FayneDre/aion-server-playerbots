package com.aionemu.gameserver.playerbot.social;

import java.util.Comparator;
import java.util.function.Predicate;

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
	private static final String FIND_GROUP_META = "PF";

	private BotChat() {
	}

	/**
	 * @return The identifier a client in the bot's place would ask the channel for: for Region and Trade the map's client name, {@code DC1} for
	 *         Pandaemonium, as read in game on 24 October. The digit the log shows after it, {@code @public_DC11.1.AION.KOR}, is the game server's id
	 *         behind an invisible separator, not a channel number: {@code @job_Assassin1.1.AION.KOR} has it too.
	 */
	static String metaOf(Channel channel, Player bot) {
		if (channel == Channel.FIND_GROUP)
			return FIND_GROUP_META;
		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(bot.getWorldId());
		return map.getCName();
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

	/**
	 * @return What {@code //bot chat} answers, having had a bot say a line in the channel: the caller's target if it is a bot of the caller's race on
	 *         its map, the nearest such bot otherwise.
	 */
	public static String command(Player admin, String channelName, String text) {
		Channel channel = switch (channelName == null ? "" : channelName.toLowerCase()) {
			case "region" -> Channel.REGION;
			case "trade" -> Channel.TRADE;
			case "group" -> Channel.FIND_GROUP;
			default -> null;
		};
		if (channel == null)
			return "Usage: //bot chat <region|trade|group> [text]";
		Predicate<Player> maySpeak = player -> player.isBot() && player.isSpawned() && player.getRace() == admin.getRace()
			&& player.getWorldId() == admin.getWorldId();
		Player speaker = admin.getTarget() instanceof Player target && maySpeak.test(target) ? target
			: World.getInstance().getAllPlayers().stream().filter(maySpeak).min(Comparator.comparingDouble(player -> PositionUtil.getDistance(player, admin)))
				.orElse(null);
		if (speaker == null)
			return "No bot of your faction on this map.";
		String line = text != null ? text : "Test du canal " + channelName.toLowerCase() + ", " + speaker.getName() + " au rapport.";
		if (!say(speaker, channel, line))
			return "The chat server is not connected: set gameserver.chatserver.enable and the chat password.";
		return speaker.getName() + " says in " + channelName.toLowerCase() + " (" + metaOf(channel, speaker) + "): " + line;
	}
}
