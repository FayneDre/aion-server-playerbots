package com.aionemu.gameserver.playerbot.movement;

import com.aionemu.gameserver.controllers.movement.PlayerMoveController;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Moves a bot in a straight line towards a destination.
 * <p>
 * {@link com.aionemu.gameserver.controllers.movement.PlayableMoveController} already implements the right interpolation, but gates it behind a
 * private {@code isControlled()} that only allows server driven movement under fear or confuse. This subclass overrides the two public methods that
 * consult it. Registration goes to {@link MoveTaskManager} rather than PlayerMoveTaskManager, because only the former reports arrival and updates
 * zones.
 */
public class BotMoveController extends PlayerMoveController {

	private static final float ARRIVE_OFFSET = 0.5f;
	/** Ray casts are not free, so the ground is only sampled a few times per second rather than on every 200 ms tick. */
	private static final long GEO_Z_UPDATE_INTERVAL = 500;

	private long nextGeoZUpdate;

	public BotMoveController(Player owner) {
		super(owner);
	}

	/**
	 * Heads towards the given point, starting to move if idle.
	 */
	/**
	 * Heads towards the given point, stopping short of any obstacle in the way.
	 *
	 * @return false if an obstacle blocks the bot right away, in which case it does not move at all.
	 */
	public boolean moveToPoint(float x, float y, float z) {
		Vector3f reachable = BotGeoHelper.reachablePointToward(owner, x, y, z);
		if (!BotGeoHelper.isWorthMovingTo(owner, reachable))
			return false;
		moveToReachablePoint(reachable.getX(), reachable.getY(), reachable.getZ());
		return true;
	}

	private void moveToReachablePoint(float x, float y, float z) {
		boolean destinationChanged = x != getTargetX2() || y != getTargetY2() || z != getTargetZ2();
		setNewDirection(x, y, z, PositionUtil.getHeadingTowards(owner.getX(), owner.getY(), x, y));
		if (!started.get()) {
			startMovingToDestination();
		} else {
			// MoveTaskManager removes the bot on arrival, so re-register (idempotent) instead of assuming we are still ticked
			MoveTaskManager.getInstance().addCreature(owner);
			if (destinationChanged) // clients extrapolate between packets, so only resend when the destination actually moved
				PacketSendUtility.broadcastToSightedPlayers(owner, new SM_MOVE(owner));
		}
	}

	/**
	 * Ends the current movement and tells clients the authoritative position, so they stop extrapolating.
	 */
	public void stop() {
		MoveTaskManager.getInstance().removeCreature(owner);
		if (started.compareAndSet(true, false))
			setAndSendStopMove(owner);
	}

	public boolean isArrived() {
		return PositionUtil.getDistance(owner.getX(), owner.getY(), getTargetX2(), getTargetY2()) < ARRIVE_OFFSET;
	}

	@Override
	public void startMovingToDestination() {
		updateLastMove();
		if (!owner.canPerformMove() || !started.compareAndSet(false, true))
			return;

		owner.unsetState(CreatureState.WALK_MODE);
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_EMOTION(owner, EmotionType.RUN));
		setAndSendStartMove(owner);
		MoveTaskManager.getInstance().addCreature(owner);
	}

	@Override
	public void moveToDestination() {
		if (!owner.canPerformMove()) {
			if (started.compareAndSet(true, false))
				setAndSendStopMove(owner);
			updateLastMove();
			return;
		}

		float x = owner.getX();
		float y = owner.getY();
		float z = owner.getZ();
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
		World.getInstance().updatePosition(owner, newX, newY, groundZ(newX, newY, (getTargetZ2() - z) * fraction + z), heading, false);
		updateLastMove();
	}

	/**
	 * Snaps the interpolated height to the ground, so the bot follows slopes instead of sliding along a straight line through them. Most maps have no
	 * heightmap, in which case the interpolated value is kept rather than dropping the bot to zero.
	 */
	private float groundZ(float x, float y, float interpolatedZ) {
		long now = System.currentTimeMillis();
		if (now < nextGeoZUpdate)
			return interpolatedZ;
		nextGeoZUpdate = now + GEO_Z_UPDATE_INTERVAL;

		float geoZ = GeoService.getInstance().getZ(owner.getWorldId(), x, y, interpolatedZ + 2, interpolatedZ - 2, owner.getInstanceId());
		return Float.isNaN(geoZ) ? interpolatedZ : geoZ;
	}

	@Override
	public void abortMove() {
		// engine code calls abortMove blindly (stun, teleport, despawn), so removal must be idempotent
		MoveTaskManager.getInstance().removeCreature(owner);
		super.abortMove();
	}
}
