package com.aionemu.gameserver.playerbot.navmesh;

import java.util.List;
import java.util.Map;

/**
 * Faces of the geometry that the game's data files as walls and that are not walls in game, left out of a map's mesh one by one.
 * <p>
 * Each is a flat vertical face of one model, named by the model and the plane it lies in, in world coordinates, and each is here because a player
 * walked where it stands. Theobomos Lab: the room of Queen Arachne, the dungeon's first boss and the one whose key opens the rest, is cut from the
 * entrance by a face of {@code bu_l_laboratory_room_06f} 24 m wide and 13 m tall across x = 301.5, hanging 0.7 m over the floor, with spiders spawned
 * on both sides of it; the tester walks into that room first (19 October). Nothing in the data tells such a face from a real wall, so it is listed.
 */
final class DroppedFaces {

	/** How far from its plane a vertex may lie and still be on it, in metres. */
	private static final float ON_THE_PLANE = 0.05f;

	/** A vertical plane, {@code x = at} or {@code y = at}, of one model: a triangle of that model with its three corners on it is left out. */
	record Plane(String model, boolean alongX, float at) {

		boolean holds(float ax, float ay, float bx, float by, float cx, float cy) {
			return alongX ? on(ax) && on(bx) && on(cx) : on(ay) && on(by) && on(cy);
		}

		private boolean on(float coordinate) {
			return Math.abs(coordinate - at) <= ON_THE_PLANE;
		}
	}

	private static final Map<Integer, List<Plane>> DROPPED = Map.of(310110000, List.of(new Plane("bu_l_laboratory_room_06f", true, 301.5f)));

	private DroppedFaces() {
	}

	/** @return The planes to leave out of the placed model, none for nearly all. */
	static List<Plane> of(int mapId, String modelName) {
		List<Plane> planes = DROPPED.get(mapId);
		if (planes == null)
			return List.of();
		return planes.stream().filter(plane -> modelName.contains(plane.model())).toList();
	}
}
