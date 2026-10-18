package com.aionemu.gameserver.playerbot.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * The glides a group's leader has just made, as its followers saw them: where the leader left the ground, the way it went through the air, and where it
 * came down.
 * <p>
 * The precomputed glide links reach ten metres out from an edge, which covers a drop off a ledge and not a player's glide across a valley: in Nochsana
 * the leader glided down and the group, finding no walk, took the long way round or none. A follower does what a person following does instead,
 * and goes where the leader went. Each follower looks at its leader on its own tick, so the leader is seen about once a second per follower, and
 * the way through the air is the positions seen in the air, in order.
 */
public final class LeaderGlides {

	private static final Logger log = LoggerFactory.getLogger(LeaderGlides.class);

	/** How long after its landing a glide is still worth following: longer, and the group has long since found its own way down or been brought. */
	private static final long FRESH_MILLIS = 120_000;
	/** A glide that came down less than this far below where it started is not a way down anywhere, and is not followed. */
	public static final float MIN_DROP = 4f;

	/** One glide: its number among the leader's, where it left the ground, the points seen in the air, and the landing, which is the last of them. */
	public record Glide(int number, Vector3f takeoff, List<Vector3f> path, long landedAt) {

		public Vector3f landing() {
			return path.get(path.size() - 1);
		}
	}

	private static final class Watch {

		private Vector3f lastOnGround;
		private Vector3f takeoff;
		private List<Vector3f> inTheAir;
		private Glide last;
		private int count;
	}

	private static final Map<Integer, Watch> watches = new ConcurrentHashMap<>();

	private LeaderGlides() {
	}

	/**
	 * Looks at the leader and notes what it is doing: on the ground, in the air on a glide, or just come down from one.
	 *
	 * @return The leader's last finished glide while it is still fresh and went down, or null.
	 */
	public static Glide observe(Player leader) {
		Watch watch = watches.computeIfAbsent(leader.getObjectId(), id -> new Watch());
		synchronized (watch) {
			Vector3f here = new Vector3f(leader.getX(), leader.getY(), leader.getZ());
			if (leader.isInGlidingState()) {
				if (watch.inTheAir == null) {
					watch.inTheAir = new ArrayList<>();
					watch.takeoff = watch.lastOnGround != null ? watch.lastOnGround : here;
				}
				Vector3f previous = watch.inTheAir.isEmpty() ? null : watch.inTheAir.get(watch.inTheAir.size() - 1);
				if (previous == null || !previous.equals(here))
					watch.inTheAir.add(here);
			} else if (!leader.isFlying()) {
				if (watch.inTheAir != null) {
					List<Vector3f> path = watch.inTheAir;
					path.add(here);
					if (watch.takeoff.getZ() - here.getZ() >= MIN_DROP) {
						watch.last = new Glide(++watch.count, watch.takeoff, List.copyOf(path), System.currentTimeMillis());
						log.info("{} glided from {} {} {} to {} {} {}, seen at {} point(s) in the air", leader.getName(), Math.round(watch.takeoff.getX()),
							Math.round(watch.takeoff.getY()), Math.round(watch.takeoff.getZ()), Math.round(here.getX()), Math.round(here.getY()),
							Math.round(here.getZ()), path.size() - 1);
					}
					watch.inTheAir = null;
				}
				watch.lastOnGround = here;
			}
			Glide glide = watch.last;
			return glide != null && System.currentTimeMillis() - glide.landedAt() < FRESH_MILLIS ? glide : null;
		}
	}
}
