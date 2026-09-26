package com.aionemu.gameserver.playerbot.navmesh;

/**
 * One collision mesh as stored in {@code data/geo/models.mesh}: a triangle soup plus what it collides with.
 *
 * @param vertices Coordinates, three floats per vertex.
 * @param indices Vertex indices, three per triangle.
 * @param collisionIntentions Bit mask of {@link com.aionemu.gameserver.geoEngine.collision.CollisionIntention}.
 */
public record GeoModel(String name, float[] vertices, int[] indices, byte materialId, byte collisionIntentions) {

	public int triangleCount() {
		return indices.length / 3;
	}
}
