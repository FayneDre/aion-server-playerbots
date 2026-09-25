package admincommands;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.PlayerBotService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * @author FayneDre
 */
public class Bot extends AdminCommand {

	public Bot() {
		super("bot", "Controls playerbots.", """
			load <characterName> - Loads a bot character from the database without spawning it.
			""");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			sendInfo(admin);
			return;
		}

		if (params[0].equalsIgnoreCase("load")) {
			if (params.length < 2) {
				sendInfo(admin, "Please provide a character name");
				return;
			}
			sendInfo(admin, PlayerBotService.getInstance().describeLoadedBot(params[1]));
		} else {
			sendInfo(admin);
		}
	}
}
