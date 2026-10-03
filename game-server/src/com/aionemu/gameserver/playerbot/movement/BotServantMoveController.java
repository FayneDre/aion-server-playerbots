package com.aionemu.gameserver.playerbot.movement;

import com.aionemu.gameserver.controllers.movement.SummonMoveController;
import com.aionemu.gameserver.model.gameobjects.Summon;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Walks a bot's servant, which nothing on the server does by itself.
 * <p>
 * A summon's movement belongs to its master's client: it walks the pet and reports where it went through {@code CM_SUMMON_MOVE}. The server side of
 * that is {@link SummonMoveController}, whose only method is empty, so a bot's spirit was called up and then stood exactly where it appeared for the
 * rest of its life. Two earlier attempts to fix this hung the engine's {@code FollowSummonTaskAI} on the bot, which fires {@code MOVE_VALIDATE} at an
 * ai that is never started — {@code VisibleObjectSpawner} only starts one for siege weapons — and would have moved the summon through that empty
 * method anyway.
 * <p>
 * {@link com.aionemu.gameserver.controllers.movement.PlayableMoveController PlayableMoveController} already implements the right interpolation,
 * speed and packets, behind a private {@code isControlled()} that only allows server driven movement under fear or confuse. This overrides the two
 * public methods that consult it — the same gate, and the same way through it, as {@link BotMoveController} uses for the bot itself.
 * <p>
 * <b>No pathfinding, deliberately.</b> A servant never navigates: it walks where its master has just walked, and the master does the planning, so the
 * ground between them was walkable a second ago. Straight line interpolation with ground sampling is the whole requirement. The engine already holds
 * the backstop for where that is not enough — a servant more than fifty metres from its master is released — so a spirit stuck behind a wall goes
 * away rather than being stranded, and the bot calls up another the next time it is standing still.
 */
public class BotServantMoveController extends SummonMoveController {

	/**
	 * The ground is sampled on a timer rather than every tick, exactly as the bot's own controller does: sampling less often makes the servant
	 * follow a straight line between two heights and then jump to the real one, which reads as a stutter on sloped ground.
	 */
	private static final long GEO_Z_UPDATE_INTERVAL = 200;

	private long nextGeoZUpdate;

	public BotServantMoveController(Summon owner) {
		super(owner);
	}

	/** Sets the servant walking to a spot. Replaces a route in progress, since the only destination it ever has is wherever its master is now. */
	public void headFor(float x, float y, float z) {
		setNewDirection(x, y, z, PositionUtil.getHeadingTowards(owner.getX(), owner.getY(), x, y));
		startMovingToDestination();
	}

	public void stop() {
		if (isInMove())
			abortMove();
	}

	@Override
	public void startMovingToDestination() {
		updateLastMove();
		if (!owner.canPerformMove() || !started.compareAndSet(false, true))
			return;
		setAndSendStartMove(owner);
		// Registration goes through the controller rather than straight to the task manager, because its onStartMove also updates the zone the
		// servant is in. PlayerMoveTaskManager and not MoveTaskManager: the latter asks the creature's ai whether it has arrived and fires move
		// events at it, and a summon's ai is never started, so there is nothing there to ask. Arrival is this servant's own business anyway — it is
		// following something that moves, so there is no fixed destination to have reached.
		owner.getController().onStartMove();
	}

	@Override
	public void moveToDestination() {
		if (!owner.canPerformMove()) {
			if (started.compareAndSet(true, false)) {
				setAndSendStopMove(owner);
				owner.getController().onStopMove();
			}
			updateLastMove();
			return;
		}

		float x = owner.getX(), y = owner.getY(), z = owner.getZ();
		float distance = (float) PositionUtil.getDistance(x, y, z, getTargetX2(), getTargetY2(), getTargetZ2());
		if (distance < 0.01f) {
			updateLastMove();
			return;
		}

		float speed = StatFunctions.adjustStatByMovementModifier(owner, StatEnum.SPEED, owner.getGameStats().getMovementSpeedFloat());
		long millisElapsed = System.currentTimeMillis() - getLastMoveUpdate();
		float distancePassed = Math.min(speed * millisElapsed / 1000f, distance);
		float fraction = distancePassed / distance;

		float newX = (getTargetX2() - x) * fraction + x;
		float newY = (getTargetY2() - y) * fraction + y;
		// The known list is refreshed as it walks, for the same reason the bot's is: an aggressive monster re-checks its aggro when a creature enters
		// its known list and never again, so a servant walking past one with a fixed list is a servant nothing ever notices.
		World.getInstance().updatePosition(owner, newX, newY, groundZ(newX, newY, (getTargetZ2() - z) * fraction + z), heading, true);
		updateLastMove();
	}

	/** Snaps the interpolated height to the ground, so the servant follows slopes. Most maps have no heightmap, where the interpolation stands. */
	private float groundZ(float x, float y, float interpolatedZ) {
		long now = System.currentTimeMillis();
		if (now < nextGeoZUpdate)
			return interpolatedZ;
		nextGeoZUpdate = now + GEO_Z_UPDATE_INTERVAL;

		float geoZ = GeoService.getInstance().getZ(owner.getWorldId(), x, y, interpolatedZ + 2, interpolatedZ - 2, owner.getInstanceId());
		return Float.isNaN(geoZ) ? interpolatedZ : geoZ;
	}
}
