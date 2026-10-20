package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.DialogAction;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;

/**
 * Where a town's services stand: the shops, the broker, the warehouse, the crafting masters, the teleporter, the stigma master, the soul healer.
 * <p>
 * Read off the living npcs by what their dialog offers, as {@code BotVendorManager} finds shop keepers, rather than from a list of names: Sanctum has 71
 * shop keepers, 3 brokers, 4 warehouse keepers, 7 crafting masters, 6 teleporters, one stigma master and one soul healer, and Pandaemonium its own,
 * none of them named in any setting. Kept per map once found: a keeper stands where it was put.
 */
public final class TownServices {

	/** A kind of service, by the dialog action that offers it. */
	public enum Service {
		SHOP(DialogAction.BUY),
		BROKER(DialogAction.OPEN_VENDOR),
		WAREHOUSE(DialogAction.DEPOSIT_CHAR_WAREHOUSE),
		CRAFTING(DialogAction.COMBINE_SKILL_LEVELUP),
		TELEPORTER(DialogAction.AIRLINE_SERVICE),
		STIGMA(DialogAction.OPEN_STIGMA_WINDOW),
		SOUL_HEALER(DialogAction.RECOVERY);

		private final int dialogAction;

		Service(int dialogAction) {
			this.dialogAction = dialogAction;
		}
	}

	private static final Map<Integer, Map<Service, List<Vector3f>>> byMap = new ConcurrentHashMap<>();

	private TownServices() {
	}

	/**
	 * @param seed Which one of the keepers of that kind: the same seed always names the same one.
	 * @return Where a keeper of that service stands on the bot's map, or null when the map has none.
	 */
	public static Vector3f keeperOf(Player bot, Service service, int seed) {
		List<Vector3f> keepers = servicesOn(bot).get(service);
		return keepers == null || keepers.isEmpty() ? null : keepers.get(Math.floorMod(seed, keepers.size()));
	}

	private static Map<Service, List<Vector3f>> servicesOn(Player bot) {
		return byMap.computeIfAbsent(bot.getWorldId(), id -> {
			Map<Service, List<Vector3f>> found = new EnumMap<>(Service.class);
			bot.getPosition().getWorldMapInstance().forEachNpc(npc -> {
				NpcTemplate template = npc.getObjectTemplate();
				if (!npc.isSpawned() || template == null)
					return;
				for (Service service : Service.values()) {
					if (template.supportsAction(service.dialogAction))
						found.computeIfAbsent(service, s -> new ArrayList<>()).add(new Vector3f(npc.getX(), npc.getY(), npc.getZ()));
				}
			});
			// in the order the spawns came, which is not the same from one start to the next: sorted, so a seed names the same keeper every day
			found.values().forEach(keepers -> keepers.sort((a, b) -> a.x != b.x ? Float.compare(a.x, b.x) : Float.compare(a.y, b.y)));
			return found;
		});
	}
}
