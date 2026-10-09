package com.aionemu.gameserver.playerbot;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.PlayerBotConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.lifecycle.BotRoster;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotEnterWorldService;
import com.aionemu.gameserver.playerbot.world.BotDirector;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLeaveWorldService;
import com.aionemu.gameserver.playerbot.lifecycle.PlayerBotLoader;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.world.World;

/**
 * Entry point of the playerbot system: bots are real {@link Player} objects driven by server side AI instead of a client connection.
 */
public class PlayerBotService {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotService.class);

	/**
	 * How often spawned bots are written to the database. Bots now gain levels, skills and loot unattended over hours, and nothing else writes them:
	 * the engine's own {@code PeriodicSaveService} handles legion warehouses only, and real players are saved when they log out — a door a bot never
	 * uses. A clean shutdown still saves them; this is what stands between a crash and a day of progress.
	 */
	private static final long SAVE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
	/** How often a slice of the population is written. The interval divided by this is the number of slices, so each bot is still saved every 5 min. */
	private static final long SAVE_SWEEP_MILLIS = TimeUnit.SECONDS.toMillis(10);

	private final Map<Integer, Player> spawnedBots = new ConcurrentHashMap<>();

	public static PlayerBotService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	/**
	 * Puts the world back the way it was and starts saving it. Called once, from {@code GameServer}, after the world is loaded.
	 * <p>
	 * Switched off, nothing here runs: no save sweep, no standing orders, no roster restored and no director. The characters stay in the database
	 * untouched, which is the whole promise of the setting — turning bots off must not be a way to lose them.
	 */
	public void onStartUp() {
		if (!PlayerBotConfig.ENABLE) {
			log.info("Playerbots are disabled, so none is put back into the world. Their characters are left in the database untouched.");
			return;
		}
		BotScheduler.getInstance().scheduleAtFixedRate(this::saveDue, SAVE_SWEEP_MILLIS, SAVE_SWEEP_MILLIS);
		runStandingOrders();
		restoreRoster();
		warmUpPlaces();
		// Last, and it used to be first. The director reviews what each region holds against what it should hold, and both of the steps above change
		// that answer wholesale: populating a fresh installation builds a navmesh and creates thousands of characters across eighteen maps, which
		// takes minutes. Started first, the director's opening review fired into a world a tenth of the way up and began correcting a figure that was
		// still moving — on the same threads doing the populating.
		BotDirector.getInstance().start();
	}

	/**
	 * Reads every open map's places before anything needs them.
	 * <p>
	 * Finding them is cheap and proving each one can be walked to is not: the ground under every place is walked outwards until it runs out or proves
	 * big enough, some hundred and fifty times on a region. Left to the first caller, that bill lands wherever the first bot happens to ask — a tick
	 * thread, with forty other bots waiting behind it. Queued here it lands on the lifecycle lane, which exists to block, and ahead of the director's
	 * first review because that review runs on the same lane and the lane keeps its order.
	 */
	private void warmUpPlaces() {
		for (WorldMapTemplate map : DataManager.WORLD_MAPS_DATA) {
			if (BotPopulator.raceOfMap(map.getMapId()) == null)
				continue; // an instance, or contested ground, which is populated by command
			int worldId = map.getMapId();
			BotScheduler.getInstance().enterOrLeaveWorld(() -> BotPlaces.warmUp(worldId));
		}
	}

	/**
	 * Puts back the bots that were in the world when it last went down, so a restart is not a depopulation.
	 */
	private void restoreRoster() {
		Set<String> roster = BotRoster.restore();
		if (roster.isEmpty())
			return;
		int restored = 0;
		int left = 0;
		for (String characterName : roster) {
			// Already in the world, because populating a map on this very startup both creates a bot and puts it there. Asking to load it again
			// returns null, exactly as a deleted character does, and reading that null as "gone" printed forty five warnings about losing characters
			// that were standing right there. A null is not a reason.
			if (findSpawnedBot(characterName) != null) {
				restored++;
				continue;
			}
			// A character of somebody's own comes back when they ask for it and not before. The roster exists so the world is the world again after a
			// restart, and a player's character is not part of the world in that sense — putting it back would be the server playing it, which is the
			// one thing it must not do with somebody else's character. It leaves the roster here, so nothing goes looking for it again.
			if (BotRoster.isSomebodysOwn(characterName)) {
				left++;
				continue;
			}
			Player bot = loadAvailableBot(characterName);
			if (bot == null) {
				log.warn("Bot {} is on the roster but no longer exists, dropping it", characterName);
				continue;
			}
			try {
				PlayerBotEnterWorldService.enterWorld(bot);
				spawnedBots.put(bot.getObjectId(), bot);
				restored++;
			} catch (RuntimeException e) {
				log.error("Could not restore bot " + characterName, e);
			}
		}
		rememberRoster(); // drops whatever could not be restored, so a broken name is not retried every restart
		log.info("Restored {} of {} bot(s) from the roster, leaving {} belonging to players", restored, roster.size(), left);
	}

	/**
	 * Carries out a clearing or a populating left for the next start, then forgets it.
	 * <p>
	 * Both are the work of one command typed in game, and that is still how a person does it. This exists because populating is a job done <b>for</b>
	 * a server rather than <b>in</b> it: it wants a map with nobody on it, it takes a while, and whoever orders it may well not have a character
	 * standing there — the person maintaining this one has no client at all. An order left in {@code server_variables} is carried out on the next
	 * start and cleared, so it can never run twice.
	 */
	private void runStandingOrders() {
		if (BotRoster.takeOrder(BotRoster.CLEAR_ORDER) != null)
			log.info("Standing order: {}", BotCommands.clear(null, null));
		BotPopulator.populateConfiguredMaps();
	}

	/**
	 * Saves every bot that is due, a slice at a time.
	 * <p>
	 * Each bot is still written every {@link #SAVE_INTERVAL_MILLIS}, but not all of them in the same instant. Saving the lot at once is a burst of as
	 * many statements as there are bots — fifteen hundred of them on one connection, every five minutes, while the same database is answering real
	 * players. Spreading them over the interval costs nothing and turns the burst into a trickle.
	 * <p>
	 * Which bot is due is decided by its own object id rather than by a queue: the ids are already spread, so the arithmetic does the scattering for
	 * free and nothing has to be kept in step with bots arriving and leaving.
	 */
	private void saveDue() {
		long now = System.currentTimeMillis();
		long slices = SAVE_INTERVAL_MILLIS / SAVE_SWEEP_MILLIS;
		long currentSlice = now / SAVE_SWEEP_MILLIS % slices;
		for (Player bot : spawnedBots.values()) {
			if (Math.floorMod(bot.getObjectId(), slices) != currentSlice)
				continue;
			save(bot);
		}
	}

	void save(Player bot) {
		try {
			PlayerService.storePlayer(bot);
		} catch (RuntimeException e) {
			log.error("Could not save bot " + bot.getName(), e);
		}
	}

	/** Puts a bot that has just entered the world on the books, and remembers it for the next start. */
	void register(Player bot) {
		spawnedBots.put(bot.getObjectId(), bot);
		rememberRoster();
	}

	/** @return Every bot in the world at this moment. A view, not a copy: it follows bots arriving and leaving. */
	Collection<Player> spawnedBots() {
		return Collections.unmodifiableCollection(spawnedBots.values());
	}

	/** The roster is written on every change rather than at shutdown, because a shutdown that never runs is the case it exists for. */
	void rememberRoster() {
		BotRoster.remember(spawnedBots.keySet());
	}

	/**
	 * Takes a bot out of the world, and answers whether this call was the one that did it.
	 * <p>
	 * The claim is the removal. Every way a bot can leave — an operator's command, the director putting it to sleep, the shutdown — runs through here,
	 * and more than one of them can pick the same character at the same moment. Removing from the set first means exactly one of them proceeds, where
	 * removing and then leaving without looking at the result had both of them saving the character and firing its departure twice.
	 */
	boolean takeOutOfWorld(Player bot) {
		if (spawnedBots.remove(bot.getObjectId()) == null)
			return false; // somebody else is already taking it out
		try {
			PlayerBotLeaveWorldService.leaveWorld(bot);
			return true;
		} catch (RuntimeException e) {
			log.error("Could not take bot " + bot.getName() + " out of the world", e);
			return false;
		}
	}

	/**
	 * @return The bot of that character id if it is in the world, or null if it is in the pool. Which is the whole of what "asleep" means — there is
	 *         no flag and nothing to keep in step, only a character that is in the world or a character that is not.
	 */
	public Player spawnedBot(int playerId) {
		return spawnedBots.get(playerId);
	}

	/**
	 * Brings one of the world's own inhabitants into the world, at the place it lives.
	 * <p>
	 * Runs on the lifecycle lane, never on a tick thread: loading a character is some twenty blocking database round trips before the first navmesh
	 * lookup.
	 * <p>
	 * <b>Whether anybody is watching is not asked.</b> It was, on the reasoning that a character materialising in front of somebody is worse than an
	 * empty field, and the reasoning was wrong about the game it is in: on any server people log in, and they appear where they log in, in front of
	 * whoever is standing there. Nobody reads that as a fault because it is what the world does.
	 * <p>
	 * It was also the one refusal that could never resolve itself. A villager's home is the village and a client is told about everything within 95 m,
	 * so while a player stands in a place there is no unseen spot anywhere in it: Verteron held 67 of the 84 it wanted and refused the same 17
	 * arrivals every half minute for as long as somebody stayed in the citadel. Departures still ask — see {@link #sleep} — and that asymmetry is the
	 * point rather than an oversight: a refused departure costs nothing, because the region is already as full as it should be and the next review
	 * will ask again, while a refused arrival leaves the region short for exactly as long as the player stays.
	 *
	 * @return false if the bot cannot be brought in now, which is never final: the next review asks again.
	 */
	public Change wake(BotRoster.Resident resident) {
		Vector3f spot = BotPopulator.scatterAround(resident.worldId(), resident.home());
		Player bot = loadAvailableBot(resident.name());
		if (bot == null)
			return Change.UNAVAILABLE; // deleted, or still on its way out of the world; either way not this review's business
		try {
			bot.setPosition(World.getInstance().createPosition(resident.worldId(), spot.getX(), spot.getY(), spot.getZ(), (byte) 0, 0));
			PlayerBotEnterWorldService.enterWorld(bot);
			spawnedBots.put(bot.getObjectId(), bot);
			BotRoster.setInWorld(resident.playerId(), true);
			return Change.DONE;
		} catch (RuntimeException e) {
			log.error("Could not wake bot " + resident.name(), e);
			return Change.UNAVAILABLE;
		}
	}

	/**
	 * Takes one of the world's own inhabitants out of the world, if this is a moment at which it may go.
	 * <p>
	 * Three refusals, in the order that matters. A bot somebody owns is not the world's to move at all. A bot anybody can see does not vanish, and the
	 * question is asked of its own known list, which is precisely the set of clients that would be told — not an estimate of who is near. Only then is
	 * the bot itself asked, because agreeing to go is irreversible: see {@link PlayerBotAI#tryRetire()}.
	 *
	 * @return false if the bot stays, which is never final.
	 */
	public Change sleep(Player bot) {
		if (!(bot.getAi() instanceof PlayerBotAI ai) || ai.isOwned())
			return Change.UNAVAILABLE;
		if (bot.getKnownList().streamPlayers().anyMatch(other -> !other.isBot()))
			return Change.SEEN;
		if (!ai.tryRetire())
			return Change.BUSY;
		if (!takeOutOfWorld(bot))
			return Change.UNAVAILABLE;
		BotRoster.setInWorld(bot.getObjectId(), false);
		return Change.DONE;
	}

	/**
	 * Why a change to the population did or did not happen, in either direction.
	 * <p>
	 * Three refusals rather than one boolean, because the reasons are not interchangeable to anybody reading the log. Being <b>seen</b> now only ever
	 * holds a departure back — arrivals no longer ask, since people appearing is what a server looks like. Being <b>busy</b> is a bot in a fight or on
	 * a road, which passes on its own within a tick or two. Only <b>unavailable</b> is a surprise. Saying "refused" and leaving the cause to be guessed
	 * is how a server with nobody on it came to report that a dozen departures had been refused for being watched.
	 */
	public enum Change {
		DONE, SEEN, BUSY, UNAVAILABLE
	}

	/**
	 * Takes every bot out of the world on the way down, leaving the roster alone so the next start puts them back.
	 * <p>
	 * Not {@link #despawnAll()}: that one is an operator emptying the world on purpose, and clearing the roster is the whole point of it. Going
	 * through it here would mean every restart came back to an empty map.
	 */
	public void onShutdown() {
		// Before the count is read, let alone acted on: emptying the world walks the set of bots in it and then clears the set, so a bot the director
		// brings in between those two steps is left standing in a world nothing holds a reference to any more.
		BotScheduler.getInstance().stopEnteringAndLeaving();
		log.info("Despawning {} bot(s) for shutdown", spawnedBots.size());
		despawnEverything();
	}

	/** @return How many bots were taken out. Leaves the roster untouched; the caller decides what it now means. */
	int despawnEverything() {
		int count = spawnedBots.size();
		spawnedBots.values().forEach(bot -> {
			try {
				PlayerBotLeaveWorldService.leaveWorld(bot);
			} catch (RuntimeException e) {
				log.error("Could not despawn bot " + bot.getName(), e);
			}
		});
		spawnedBots.clear();
		return count;
	}

	Player loadAvailableBot(String characterName) {
		if (World.getInstance().getPlayer(characterName) != null)
			return null;
		return PlayerBotLoader.load(characterName);
	}

	Player findSpawnedBot(String characterName) {
		return spawnedBots.values().stream().filter(bot -> bot.getName().equalsIgnoreCase(characterName)).findFirst().orElse(null);
	}

	private static class SingletonHolder {

		private static final PlayerBotService INSTANCE = new PlayerBotService();
	}
}
