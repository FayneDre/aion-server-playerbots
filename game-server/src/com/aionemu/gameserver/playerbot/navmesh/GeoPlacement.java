package com.aionemu.gameserver.playerbot.navmesh;

import com.aionemu.gameserver.geoEngine.math.Matrix3f;
import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * One instance of a {@link GeoModel} placed on a map, as stored in {@code data/geo/<mapId>.geo}.
 *
 * @param type 0 for static world geometry, non zero for despawnable entities (doors, town objects, houses).
 * @param level Town level for town objects, 0 otherwise.
 */
public record GeoPlacement(String modelName, Vector3f position, Matrix3f rotation, Vector3f scale, byte type, short id, byte level) {

	/** @return The model's local coordinates transformed into world coordinates. */
	public Vector3f toWorld(float x, float y, float z) {
		Vector3f local = new Vector3f(x * scale.x, y * scale.y, z * scale.z);
		return rotation.mult(local, local).addLocal(position);
	}
}
