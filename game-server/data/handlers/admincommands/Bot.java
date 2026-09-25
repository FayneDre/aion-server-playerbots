package admincommands;

import java.util.function.Function;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;
import com.aionemu.gameserver.playerbot.PlayerBotService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * @author FayneDre
 */
public class Bot extends AdminCommand {

	public Bot() {
		super("bot", "Controls playerbots.", """
			load <characterName> - Loads a bot character from the database without spawning it.
			spawn <characterName> - Loads a bot character and spawns it next to you.
			despawn <characterName> - Removes a spawned bot from the world.
			duel <characterName> - Makes the bot accept your pending duel request.
			attack <characterName> - Makes the bot attack your target, or you if you have none.
			stop <characterName> - Makes the bot stop attacking.
			auto <characterName> - Toggles autonomy (fights on its own) on and off.
			list - Lists all currently spawned bots.
			""");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			sendInfo(admin);
			return;
		}

		switch (params[0].toLowerCase()) {
			case "load" -> withName(admin, params, name -> PlayerBotService.getInstance().describeLoadedBot(name));
			case "spawn" -> withName(admin, params, name -> PlayerBotService.getInstance().spawn(name, admin));
			case "despawn" -> withName(admin, params, name -> PlayerBotService.getInstance().despawn(name));
			case "duel" -> withName(admin, params,
				name -> PlayerBotService.getInstance().acceptRequest(name, SM_QUESTION_WINDOW.STR_DUEL_DO_YOU_ACCEPT_REQUEST));
			case "attack" -> withName(admin, params, name -> PlayerBotService.getInstance().attack(name, admin));
			case "stop" -> withName(admin, params, name -> PlayerBotService.getInstance().stopAttacking(name));
			case "auto" -> withName(admin, params, name -> PlayerBotService.getInstance().toggleAutonomy(name));
			case "list" -> sendInfo(admin, PlayerBotService.getInstance().listSpawnedBots());
			default -> sendInfo(admin);
		}
	}

	private void withName(Player admin, String[] params, Function<String, String> action) {
		if (params.length < 2)
			sendInfo(admin, "Please provide a character name");
		else
			sendInfo(admin, action.apply(params[1]));
	}
}
