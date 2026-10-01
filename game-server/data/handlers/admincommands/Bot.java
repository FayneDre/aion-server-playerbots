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

	/** Creating characters hits the database once per bot, so a typo in the count must not lock the server up. */
	private static final int MAX_POPULATE = 20;

	public Bot() {
		super("bot", "Controls playerbots.", """
			create <name> <class> <level> <race> - Creates a new bot character of your own, with a face of its own.
			populate <count> [race] - Fills this map with bots of its own levels. 0 takes as many as the map asks for.
			delete <characterName> - Deletes one of your own bot characters from the database.
			load <characterName> - Loads a bot character from the database without spawning it.
			spawn <characterName> - Loads a bot character and spawns it next to you.
			despawn <characterName> - Removes a spawned bot from the world.
			despawnall - Removes every spawned bot from the world.
			duel <characterName> - Makes the bot accept your pending duel request.
			attack <characterName> - Makes the bot attack your target, or you if you have none.
			stop <characterName> - Makes the bot stop attacking.
			come <characterName> - Makes the bot walk to your position.
			auto <characterName> - Toggles autonomy (fights on its own) on and off.
			sell <characterName> - Sends the bot to sell right away, without waiting for a full bag.
			bag <characterName> - Lists what the bot is carrying.
			kind <characterName> [resident|adventurer] - Reads or sets whether the bot levels up. Residents stay at their region's level.
			nav - Lists the navmeshes loaded in memory.
			number [region] - Counts the bots on a map, by faction. Takes a map id or part of its name; yours by default.
			list - Lists all currently spawned bots.
			clear - Deletes your own bot characters. Staff clear the whole world, which then has to be populated again.
			See docs/bot-commands.md for what each one does.
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
			case "populate" -> populate(admin, params);
			case "delete" -> withName(admin, params, name -> PlayerBotService.getInstance().delete(name, admin));
			case "load" -> withName(admin, params, name -> PlayerBotService.getInstance().describeLoadedBot(name));
			case "spawn" -> withName(admin, params, name -> PlayerBotService.getInstance().spawn(name, admin));
			case "despawn" -> withName(admin, params, name -> PlayerBotService.getInstance().despawn(name));
			case "duel" -> withName(admin, params,
				name -> PlayerBotService.getInstance().acceptRequest(name, SM_QUESTION_WINDOW.STR_DUEL_DO_YOU_ACCEPT_REQUEST));
			case "attack" -> withName(admin, params, name -> PlayerBotService.getInstance().attack(name, admin));
			case "stop" -> withName(admin, params, name -> PlayerBotService.getInstance().stopAttacking(name));
			case "come" -> withName(admin, params, name -> PlayerBotService.getInstance().come(name, admin));
			case "auto" -> withName(admin, params, name -> PlayerBotService.getInstance().toggleAutonomy(name));
			case "sell" -> withName(admin, params, name -> PlayerBotService.getInstance().sell(name));
			case "despawnall" -> sendInfo(admin, PlayerBotService.getInstance().despawnAll());
			case "clear" -> sendInfo(admin, PlayerBotService.getInstance().clear(admin));
			case "bag" -> withName(admin, params, name -> PlayerBotService.getInstance().describeInventory(name));
			case "nav" -> sendInfo(admin, PlayerBotService.getInstance().describeNavmeshes());
			case "kind" -> kind(admin, params);
			case "number" -> sendInfo(admin, PlayerBotService.getInstance().count(params.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(params, 1, params.length)) : null, admin));
			case "list" -> sendInfo(admin, PlayerBotService.getInstance().listSpawnedBots());
			default -> sendInfo(admin);
		}
	}

	private void kind(Player admin, String[] params) {
		if (params.length < 2) {
			sendInfo(admin, "Usage: //bot kind <name> [resident|adventurer]");
			return;
		}
		Boolean resident = null;
		if (params.length >= 3) {
			if (params[2].equalsIgnoreCase("resident"))
				resident = true;
			else if (params[2].equalsIgnoreCase("adventurer"))
				resident = false;
			else {
				sendInfo(admin, "Usage: //bot kind <name> [resident|adventurer]");
				return;
			}
		}
		sendInfo(admin, PlayerBotService.getInstance().setKind(params[1], resident));
	}

	private void create(Player admin, String[] params) {
		if (params.length < 5) {
			sendInfo(admin, "Usage: //bot create <name> <class> <level> <race>");
			return;
		}
		int level;
		try {
			level = Integer.parseInt(params[3]);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Level must be a number");
			return;
		}
		sendInfo(admin, PlayerBotService.getInstance().create(params[1], params[2], level, params[4], admin));
	}

	private void populate(Player admin, String[] params) {
		if (params.length < 2) {
			sendInfo(admin, "Usage: //bot populate <count> [race]   fills this map with people of its own level");
			return;
		}
		int count;
		try {
			count = Integer.parseInt(params[1]);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Count must be a number");
			return;
		}
		if (count < 0 || count > MAX_POPULATE) {
			sendInfo(admin, "Count must be between 0 and " + MAX_POPULATE + ", or 0 for as many as the map asks for");
			return;
		}
		// the commander's own race when none is given: populating the map you are standing on with the other faction is never what was meant
		String race = params.length > 2 ? params[2] : admin.getRace().name();
		sendInfo(admin, PlayerBotService.getInstance().populate(count, admin.getWorldId(), race));
	}

	private void withName(Player admin, String[] params, Function<String, String> action) {
		if (params.length < 2)
			sendInfo(admin, "Please provide a character name");
		else
			sendInfo(admin, action.apply(params[1]));
	}
}
