package com.aionemu.gameserver.playerbot.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.templates.QuestTemplate;
import com.aionemu.gameserver.model.templates.quest.QuestDrop;
import com.aionemu.gameserver.model.templates.quest.QuestKill;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.playerbot.combat.BotTargetSelector;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Where the game itself sends a character of a given level, read out of the quest data.
 * <p>
 * A bot used to pick what to fight by proximity, which is why one of level three was measured farming grey creatures in a valley meant for eights,
 * and why a populated map read as a hunting ground rather than a region. It has no idea where a character of its level is supposed to be.
 * <p>
 * The game does, and says so in plain data. {@code quest_data.xml} holds 8043 quests; every one of them carries a minimum level, 93% carry a zone,
 * and between them they name <b>4282 distinct npc ids</b> as things to kill or to collect from. Read as an itinerary rather than as quests — which
 * npcs, at which level, in which region — that answers "where should somebody of level four be, and what should they be killing" for the price of a
 * file read. No quest handlers, no quest state, nothing persistent to break.
 * <p>
 * The quest machinery is deliberately not used. Each of Aion's quests is a bespoke script; teaching a bot to understand arbitrary objectives is a
 * chantier without end, and a player watching a bot cannot tell whether its experience came from a quest or from a monster. What they can see is
 * where it stands and what it fights, and that is exactly what this provides.
 */
public class BotQuestGrounds {

	private static final Logger log = LoggerFactory.getLogger(BotQuestGrounds.class);

	/** How far above and below its own level a bot will take quest targets, so a level has somewhere to go even where the data is thin. */
	private static final int LEVEL_WINDOW = 3;
	/** Spots this close together describe one place worth walking to, matching how settlements and hunting grounds are clustered. */
	private static final float GATHERING_RADIUS = 50f;
	/** A cluster has to hold this many spawns to be worth a journey; below it, a bot arrives at one creature and stands about. */
	private static final int GROUND_SIZE = 4;

	private static final Map<String, List<Vector3f>> groundsByBand = new ConcurrentHashMap<>();
	/**
	 * Held while the quest data is read, because reading it is not a read.
	 * <p>
	 * {@code QuestKill.getNpcIds()} moves its ids into a second list the first time it is called and nulls the first — so two threads asking at once
	 * leaves one of them clearing a list the other has already taken away. Nothing in the engine meets this, because quest handlers reach a template
	 * one player at a time; the bots meet it immediately, with eight tick threads scanning 8043 templates for different level bands at once. Measured
	 * in game as a null pointer on the very first populated map.
	 * <p>
	 * One lock around the whole scan rather than a defence at each call site: once a band has been read, every {@code QuestKill} it touched is in its
	 * settled state and is safe for everyone for the rest of the run, so the lock is held a few milliseconds per band and never again.
	 */
	private static final Object questScan = new Object();

	private BotQuestGrounds() {
	}

	/**
	 * @return A place on this map where the quests of that level send a character, near the given spot and within {@code range}, or null if the data
	 *         names none. The caller falls back to an ordinary hunting ground, which is what happens on a map the quests barely touch.
	 * @param pick Which of the candidates to take. Fixed to the bot rather than drawn, so one keeps going back to the same grounds instead of
	 *          crossing the region each time its occupation is renewed.
	 */
	public static Vector3f groundFor(int worldId, Race race, int level, Vector3f from, float range, int pick) {
		String band = worldId + "/" + race + "/" + level;
		List<Vector3f> grounds = groundsByBand.get(band);
		if (grounds == null) {
			// Deliberately not computeIfAbsent: that would hold a map bin while taking the scan lock, and another thread holding the scan lock and
			// landing on the same bin would deadlock the pair of them.
			synchronized (questScan) {
				grounds = groundsByBand.get(band);
				if (grounds == null) {
					grounds = locate(worldId, race, level);
					groundsByBand.put(band, grounds);
				}
			}
		}
		List<Vector3f> within = new ArrayList<>();
		for (Vector3f ground : grounds) {
			if (PositionUtil.getDistance(from.x, from.y, ground.x, ground.y) <= range)
				within.add(ground);
		}
		return within.isEmpty() ? null : within.get(Math.floorMod(pick, within.size()));
	}

	/** Reads the npcs the quests of this level name, finds where they stand on this map, and keeps the places where several of them do. */
	private static List<Vector3f> locate(int worldId, Race race, int level) {
		Set<Integer> targets = targetNpcs(race, level);
		if (targets.isEmpty())
			return List.of();

		List<List<Vector3f>> clusters = new ArrayList<>();
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsByWorldId(worldId)) {
			if (!targets.contains(group.getNpcId()))
				continue;
			// Not every quest is for one person, and the data does not say which are: the category tells a mission from a task and never mentions a
			// group. What the target is, does. A group quest names elites, so reading the itinerary without this sent a lone bot exactly where the
			// game sends five players — which is where they were found being torn apart.
			NpcTemplate template = DataManager.NPC_DATA.getNpcTemplate(group.getNpcId());
			if (template == null || BotTargetSelector.needsAGroup(template))
				continue;
			for (SpawnTemplate spawn : group.getSpawnTemplates())
				addToCluster(clusters, new Vector3f(spawn.getX(), spawn.getY(), spawn.getZ()));
		}
		List<Vector3f> grounds = new ArrayList<>();
		for (List<Vector3f> cluster : clusters) {
			if (cluster.size() >= GROUND_SIZE)
				grounds.add(centreOf(cluster));
		}
		log.debug("Map {}: the quests of level {} for {} name {} npc(s), standing in {} place(s) worth working", worldId, level, race, targets.size(),
			grounds.size());
		return List.copyOf(grounds);
	}

	/**
	 * @return The npcs the quests around this level send a character after.
	 *         <p>
	 *         Both ways a quest names a creature are read: {@code quest_kill} says to kill it, {@code quest_drop} says to take something off it, and
	 *         either way it is somewhere a character of that level is meant to be. The race filter matters — {@code PC_ALL} is open to everybody, and
	 *         anything else would send an Asmodian after Elyos quest targets.
	 */
	private static Set<Integer> targetNpcs(Race race, int level) {
		Set<Integer> targets = new HashSet<>();
		for (QuestTemplate quest : DataManager.QUEST_DATA.getQuestTemplates()) {
			if (Math.abs(quest.getMinlevelPermitted() - level) > LEVEL_WINDOW)
				continue;
			Race permitted = quest.getRacePermitted();
			if (permitted != null && permitted != Race.PC_ALL && permitted != race)
				continue;
			if (quest.getQuestDrop() != null) {
				for (QuestDrop drop : quest.getQuestDrop()) {
					if (drop.getNpcId() != null)
						targets.add(drop.getNpcId());
				}
			}
			if (quest.getQuestKill() != null) {
				for (QuestKill kill : quest.getQuestKill())
					targets.addAll(kill.getNpcIds());
			}
		}
		return targets;
	}

	private static void addToCluster(List<List<Vector3f>> clusters, Vector3f spot) {
		for (List<Vector3f> cluster : clusters) {
			Vector3f centre = centreOf(cluster);
			if (PositionUtil.getDistance(centre.x, centre.y, spot.x, spot.y) <= GATHERING_RADIUS) {
				cluster.add(spot);
				return;
			}
		}
		clusters.add(new ArrayList<>(List.of(spot)));
	}

	private static Vector3f centreOf(List<Vector3f> cluster) {
		float x = 0, y = 0, z = 0;
		for (Vector3f spot : cluster) {
			x += spot.x;
			y += spot.y;
			z += spot.z;
		}
		return new Vector3f(x / cluster.size(), y / cluster.size(), z / cluster.size());
	}
}
