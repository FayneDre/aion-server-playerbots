package admincommands;

import java.util.Arrays;
import java.util.function.Function;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUESTION_WINDOW;
import com.aionemu.gameserver.playerbot.BotCommands;
import com.aionemu.gameserver.playerbot.BotPopulator;
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
			populate <count> [race] - Creates inhabitants for this map, asleep. The director brings in as many as it currently wants.
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
			pool [mapId|here] - What the last population review found: awake, total and wanted, split between villages and countryside.
			  A line per map, or just the one asked for.
			fly <name> [height] - Flies the bot straight up, holds it there and lands it again. Default 25 m, capped at 60 and at its fly zone's ceiling.
			flyto <name> - Flies the bot to where you stand, over whatever is in the way. Within 300 m and inside its own fly zone.
			land <name> - Brings a flying bot down now.
			list - Lists all currently spawned bots.
			clear - Deletes your own bot characters. Staff clear the whole world, which then has to be populated again.
			See docs/bot-commands.md for what each one does.
			""");
	}

	@Override
	public void execute(Player admin, String... params) {
		// Refused rather than half obeyed: with the system off there is no save sweep and no director, so a bot spawned from here would run
		// unmanaged and lose whatever it did at the next shutdown.
		if (!PlayerBotConfig.ENABLE) {
			sendInfo(admin, "Playerbots are disabled. Set gameserver.playerbot.enable to true and restart.");
			return;
		}
		if (params.length == 0) {
			sendInfo(admin);
			return;
		}

		switch (params[0].toLowerCase()) {
			case "create" -> create(admin, params);
			case "populate" -> populate(admin, params);
			case "delete" -> withName(admin, params, name -> BotCommands.delete(name, admin));
			case "load" -> withName(admin, params, name -> BotCommands.describeLoadedBot(name));
			case "spawn" -> withName(admin, params, name -> BotCommands.spawn(name, admin));
			case "despawn" -> withName(admin, params, name -> BotCommands.despawn(name));
			case "duel" -> withName(admin, params,
				name -> BotCommands.acceptRequest(name, SM_QUESTION_WINDOW.STR_DUEL_DO_YOU_ACCEPT_REQUEST));
			case "attack" -> withName(admin, params, name -> BotCommands.attack(name, admin));
			case "stop" -> withName(admin, params, name -> BotCommands.stopAttacking(name));
			case "come" -> withName(admin, params, name -> BotCommands.come(name, admin));
			case "fly" -> withName(admin, params, name -> BotCommands.fly(name, params.length > 2 ? params[2] : null));
			case "flyto" -> withName(admin, params, name -> BotCommands.flyTo(name, admin));
			case "land" -> withName(admin, params, name -> BotCommands.land(name));
			case "auto" -> withName(admin, params, name -> BotCommands.toggleAutonomy(name));
			case "sell" -> withName(admin, params, name -> BotCommands.sell(name));
			case "regear" -> withName(admin, params, name -> BotCommands.regear(name));
			case "despawnall" -> sendInfo(admin, BotCommands.despawnAll());
			case "clear" -> sendInfo(admin, BotCommands.clear(admin, argument(params, 1)));
			case "bag" -> withName(admin, params, name -> BotCommands.describeInventory(name));
			case "nav" -> sendInfo(admin, BotCommands.describeNavmeshes());
			case "kind" -> kind(admin, params);
			case "number" -> sendInfo(admin, BotCommands.count(argument(params, 1), admin));
			case "list" -> sendInfo(admin, BotCommands.listSpawnedBots());
			case "pool" -> sendInfo(admin, BotCommands.describePool(argument(params, 1), admin));
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
		sendInfo(admin, BotCommands.setKind(params[1], resident));
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
		sendInfo(admin, BotCommands.create(params[1], params[2], level, params[4], admin));
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
		// No race unless one is typed, in which case every place names its own: Asmodae is Asmodian ground whoever is standing on it, and reading
		// the race off the commander put a village of Elyos in Morheim, where every guard in sight is hostile to them. On contested ground, where the
		// map has no single answer, each fort answers for itself — Teminon Elyos, Primum Asmodian — so one command populates both sides correctly.
		// A race given here still overrides the lot, which is what you want when seeding an invasion.
		sendInfo(admin, BotPopulator.populate(count, admin.getWorldId(), params.length > 2 ? params[2] : null));
	}

	/**
	 * @return Everything typed from that word on, joined, or null when nothing was typed. Map names have spaces in them, so "number Altgard" and
	 *         "number Gelkmaros Plateau" both mean what they say. A bare "here" is for the service to resolve, once, for every command that takes a map.
	 */
	private static String argument(String[] params, int from) {
		return params.length > from ? String.join(" ", Arrays.copyOfRange(params, from, params.length)) : null;
	}

	private void withName(Player admin, String[] params, Function<String, String> action) {
		if (params.length < 2)
			sendInfo(admin, "Please provide a character name");
		else
			sendInfo(admin, action.apply(params[1]));
	}
}
