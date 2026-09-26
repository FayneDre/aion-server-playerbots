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
			create <name> <class> <level> <templateName> - Creates a new bot character, copying account, race, gender and looks from an existing one.
			load <characterName> - Loads a bot character from the database without spawning it.
			spawn <characterName> - Loads a bot character and spawns it next to you.
			despawn <characterName> - Removes a spawned bot from the world.
			despawnall - Removes every spawned bot from the world.
			duel <characterName> - Makes the bot accept your pending duel request.
			attack <characterName> - Makes the bot attack your target, or you if you have none.
			stop <characterName> - Makes the bot stop attacking.
			come <characterName> - Makes the bot walk to your position.
			auto <characterName> - Toggles autonomy (fights on its own) on and off.
			bag <characterName> - Lists what the bot is carrying.
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
			case "create" -> create(admin, params);
			case "load" -> withName(admin, params, name -> PlayerBotService.getInstance().describeLoadedBot(name));
			case "spawn" -> withName(admin, params, name -> PlayerBotService.getInstance().spawn(name, admin));
			case "despawn" -> withName(admin, params, name -> PlayerBotService.getInstance().despawn(name));
			case "duel" -> withName(admin, params,
				name -> PlayerBotService.getInstance().acceptRequest(name, SM_QUESTION_WINDOW.STR_DUEL_DO_YOU_ACCEPT_REQUEST));
			case "attack" -> withName(admin, params, name -> PlayerBotService.getInstance().attack(name, admin));
			case "stop" -> withName(admin, params, name -> PlayerBotService.getInstance().stopAttacking(name));
			case "come" -> withName(admin, params, name -> PlayerBotService.getInstance().come(name, admin));
			case "auto" -> withName(admin, params, name -> PlayerBotService.getInstance().toggleAutonomy(name));
			case "despawnall" -> sendInfo(admin, PlayerBotService.getInstance().despawnAll());
			case "bag" -> withName(admin, params, name -> PlayerBotService.getInstance().describeInventory(name));
			case "list" -> sendInfo(admin, PlayerBotService.getInstance().listSpawnedBots());
			default -> sendInfo(admin);
		}
	}

	private void create(Player admin, String[] params) {
		if (params.length < 5) {
			sendInfo(admin, "Usage: //bot create <name> <class> <level> <templateName>");
			return;
		}
		int level;
		try {
			level = Integer.parseInt(params[3]);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Level must be a number");
			return;
		}
		sendInfo(admin, PlayerBotService.getInstance().create(params[1], params[2], level, params[4]));
	}

	private void withName(Player admin, String[] params, Function<String, String> action) {
		if (params.length < 2)
			sendInfo(admin, "Please provide a character name");
		else
			sendInfo(admin, action.apply(params[1]));
	}
}
