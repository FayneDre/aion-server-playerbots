package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ground a real player has walked, one point a line (x y z) in {@code data/navmesh/<map>.walked}, written by {@link WalkedTrails} while the server runs and read by the
 * generator, which treats every such point as ground that bodies stand on whatever the geometry says.
 * <p>
 * A hole in the data, a rock the mesh takes for a wall and a passage a body's width narrower than the erosion all look the same to the generator, and a person walking through them is
 * the one proof there is. Written on 31 October 2026, after four rounds of a tester's notes in Morheim's cave each answered with a box written by hand.
 */
public final class WalkedGround {

	/** A step that is a walk: the player moved at least this far across between two samples, in metres. */
	static final float MIN_STEP = 1.5f;
	/** ... and at most this far (a run for two seconds is 12 m; more is a teleport), and at most this much up or down (a fall, a lift, a glide is not a walk). */
	static final float MAX_STEP = 12f, MAX_RISE = 1.2f;

	private WalkedGround() {
	}

	public static Path fileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".walked");
	}

	/** The points of the data's own patrol routes that the mesh cut, written by {@code NavmeshTool <map> walkers write}: ground a creature was meant to walk, kept apart from what players walked. */
	public static Path patrolsFileOf(int mapId) {
		return Path.of("data/navmesh", mapId + ".patrols");
	}

	/** @return the points of this map, players' and patrols', empty when nobody has walked it and no patrol route was cut. */
	public static List<float[]> read(int mapId) throws IOException {
		List<float[]> points = new ArrayList<>(read(fileOf(mapId)));
		points.addAll(read(patrolsFileOf(mapId)));
		return points;
	}

	/** @return the points of one file. */
	public static List<float[]> read(Path file) throws IOException {
		List<float[]> points = new ArrayList<>();
		if (!Files.exists(file))
			return points;
		for (String line : Files.readAllLines(file)) {
			String[] f = line.trim().split(" +");
			if (f.length != 3)
				continue;
			try {
				points.add(new float[] { Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]) });
			} catch (NumberFormatException e) {
				// a line cut by a crash: the rest of the file is good
			}
		}
		return points;
	}

	/** @return true if the move between two samples of one player is a walk on the ground, which is what makes the second a point of walked ground. */
	public static boolean isAWalk(float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
		double across = Math.hypot(toX - fromX, toY - fromY);
		return across >= MIN_STEP && across <= MAX_STEP && Math.abs(toZ - fromZ) <= MAX_RISE;
	}

	/** @return the line of the file for one point. */
	public static String line(float x, float y, float z) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f%n", x, y, z);
	}
}
