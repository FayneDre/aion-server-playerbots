package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.playerbot.BotScheduler;
import com.aionemu.gameserver.world.World;

/**
 * Writes down where real players walk: every two seconds each one standing on the ground, moved a walk's step since the last sample, is a point of {@link WalkedGround} if no point
 * of that metre of ground (and height) is known yet. Positions only, no name: the file says where ground is, not who stood on it.
 * <p>
 * Bots are left out, for what a bot walks is what the mesh already says. A flight, a glide, a teleport and a lift are no walk and leave no point.
 */
public final class WalkedTrails {

	private static final Logger log = LoggerFactory.getLogger(WalkedTrails.class);
	private static final long SAMPLE_MILLIS = 2000;

	private static final Map<Integer, Set<Long>> known = new ConcurrentHashMap<>();
	private static final Map<Integer, float[]> lastSeen = new ConcurrentHashMap<>();

	private WalkedTrails() {
	}

	public static void start() {
		BotScheduler.getInstance().scheduleAtFixedRate(WalkedTrails::sample, SAMPLE_MILLIS, SAMPLE_MILLIS);
		log.info("Writing the ground real players walk to {}", Path.of("data/navmesh").toAbsolutePath());
	}

	private static void sample() {
		try {
			World.getInstance().forEachPlayer(player -> {
				if (!player.isBot() && player.isOnline())
					look(player);
			});
		} catch (RuntimeException e) {
			log.warn("Walked ground not written this time: {}", e.toString());
		}
	}

	private static void look(Player player) {
		float[] before = lastSeen.put(player.getObjectId(), new float[] { player.getX(), player.getY(), player.getZ(), player.getWorldId() });
		if (before == null || before[3] != player.getWorldId() || player.isDead() || player.isFlying() || player.isInFlyingState() || player.isInGlidingState())
			return;
		if (!WalkedGround.isAWalk(before[0], before[1], before[2], player.getX(), player.getY(), player.getZ()))
			return;
		int mapId = player.getWorldId();
		if (!known.computeIfAbsent(mapId, WalkedTrails::loaded).add(cellOf(player.getX(), player.getY(), player.getZ())))
			return;
		try {
			Files.writeString(WalkedGround.fileOf(mapId), WalkedGround.line(player.getX(), player.getY(), player.getZ()), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("Walked ground of map {} not written: {}", mapId, e.toString());
		}
	}

	/** A metre of ground and a metre of height: the points of a map are kept at about this spacing, whatever the number of walks. */
	static long cellOf(float x, float y, float z) {
		return ((long) Math.floor(x) << 40) ^ (((long) Math.floor(y) & 0xFFFFFF) << 16) ^ ((long) Math.floor(z) & 0xFFFF);
	}

	private static Set<Long> loaded(int mapId) {
		Set<Long> cells = ConcurrentHashMap.newKeySet();
		try {
			for (float[] point : WalkedGround.read(mapId))
				cells.add(cellOf(point[0], point[1], point[2]));
		} catch (IOException e) {
			log.warn("Walked ground of map {} not read: {}", mapId, e.toString());
		}
		return cells;
	}
}
