package com.aionemu.gameserver.playerbot.navmesh;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a {@link Heightfield} out in the compact form {@link Navmesh} reads back.
 * <p>
 * Offline half of the format, kept apart from the runtime half so the server never carries generation code it cannot use.
 * <p>
 * Each tile is compressed on its own and listed in a directory at the head of the file. That is what lets the server read a tile without reading the
 * map: compressing the file as a whole would have saved a little space and cost the ability to load anything less than all of it.
 */
public class NavmeshWriter {

	private NavmeshWriter() {
	}

	public static Path write(int mapId, Heightfield field) throws IOException {
		Path file = Navmesh.fileOf(mapId);
		Files.createDirectories(file.getParent());
		int tilesX = (field.width() + Navmesh.TILE_SIZE - 1) / Navmesh.TILE_SIZE;
		int tilesY = (field.height() + Navmesh.TILE_SIZE - 1) / Navmesh.TILE_SIZE;
		int tileCount = tilesX * tilesY;

		float zOrigin = floorOf(field);
		float zStep = stepFor(field, zOrigin);
		byte[][] compressedTiles = new byte[tileCount][];
		for (int tileY = 0; tileY < tilesY; tileY++)
			for (int tileX = 0; tileX < tilesX; tileX++)
				compressedTiles[tileY * tilesX + tileX] = compress(tile(field, tileX, tileY, zOrigin, zStep));

		// the regions of each coarse cell rather than a bit: the rough grid has to know what the fine grid keeps apart, or it routes across it
		Heightfield.CoarseRegions coarseRegions = field.coarseRegions(field.regions(BotPathFinder.STEP_TOLERANCE));
		byte[] coarse = new byte[(coarseRegions.offsets().length + coarseRegions.ids().length) * 4];
		// offsets then ids, and how many offsets there are follows from the map's size, so the block carries no length of its own
		ByteBuffer.wrap(coarse).asIntBuffer().put(coarseRegions.offsets()).put(coarseRegions.ids());
		try (OutputStream out = Files.newOutputStream(file)) {
			ByteBuffer header = ByteBuffer.allocate(Navmesh.MAGIC.length() + 4 * 10 + coarse.length + tileCount * 8);
			header.put(Navmesh.MAGIC.getBytes());
			header.putInt(mapId).putInt(field.width()).putInt(field.height()).putFloat(Heightfield.CELL_SIZE).putInt(tilesX).putInt(tileCount);
			header.putInt(Heightfield.COARSE_FACTOR).putInt(coarse.length).putFloat(zOrigin).putFloat(zStep);
			header.put(coarse);
			int offset = 0;
			for (byte[] compressed : compressedTiles) {
				header.putInt(offset).putInt(compressed.length);
				offset += compressed.length;
			}
			out.write(header.array());
			for (byte[] compressed : compressedTiles)
				out.write(compressed);
		}
		return file;
	}

	/** @return The tile's payload, or an empty array when nothing stands in it. */
	private static byte[] tile(Heightfield field, int tileX, int tileY, float zOrigin, float zStep) throws IOException {
		int originX = tileX * Navmesh.TILE_SIZE, originY = tileY * Navmesh.TILE_SIZE;
		byte[] counts = new byte[Navmesh.TILE_SIZE * Navmesh.TILE_SIZE];
		int[] rowOffsets = new int[Navmesh.TILE_SIZE + 1];
		int surfaces = 0;

		for (int localY = 0; localY < Navmesh.TILE_SIZE; localY++) {
			rowOffsets[localY] = surfaces;
			for (int localX = 0; localX < Navmesh.TILE_SIZE; localX++) {
				int cellX = originX + localX, cellY = originY + localY;
				if (cellX >= field.width() || cellY >= field.height())
					continue;
				int count = Math.min(field.columnEnd(cellX, cellY) - field.columnStart(cellX, cellY), Navmesh.MAX_SURFACES_PER_COLUMN);
				counts[localY * Navmesh.TILE_SIZE + localX] = (byte) count;
				surfaces += count;
			}
		}
		rowOffsets[Navmesh.TILE_SIZE] = surfaces;
		if (surfaces == 0)
			return new byte[0];

		long[] walkable = new long[(surfaces + 63) / 64];
		short[] heights = new short[surfaces];
		int index = 0;
		for (int localY = 0; localY < Navmesh.TILE_SIZE; localY++) {
			for (int localX = 0; localX < Navmesh.TILE_SIZE; localX++) {
				int cellX = originX + localX, cellY = originY + localY;
				if (cellX >= field.width() || cellY >= field.height())
					continue;
				int end = field.columnStart(cellX, cellY) + (counts[localY * Navmesh.TILE_SIZE + localX] & 0xFF);
				for (int i = field.columnStart(cellX, cellY); i < end; i++) {
					heights[index] = quantize(field.surfaceAt(i), zOrigin, zStep);
					if (field.isWalkable(i))
						walkable[index >> 6] |= 1L << index;
					index++;
				}
			}
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(surfaces);
			for (int rowOffset : rowOffsets)
				out.writeInt(rowOffset);
			out.write(counts);
			for (short z : heights)
				out.writeShort(z);
			for (long bits : walkable)
				out.writeLong(bits);
		}
		return bytes.toByteArray();
	}

	private static byte[] compress(byte[] payload) throws IOException {
		if (payload.length == 0)
			return payload;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length / 2);
		try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_SPEED))) {
			out.write(payload);
		}
		return bytes.toByteArray();
	}

	/** Heights below zero or above the engine's 2048 m ceiling are not real ground, so clamping them loses nothing. */
	private static short quantize(float z, float zOrigin, float zStep) {
		return (short) Math.max(0, Math.min(Navmesh.Z_LEVELS, Math.round((z - zOrigin) / zStep)));
	}

	/**
	 * @return Metres per quantisation step for this map: the finest the format offers, unless the map is too tall for a short to reach its ceiling at
	 *         that, in which case just coarse enough that it does.
	 *         <p>
	 *         Kaldor is the map that needed it — floating islands spanning more than the 3276 m a short covers at the finest step, so its upper
	 *         surfaces were all written at the ceiling. Deciding the step from the map rather than in advance means no map can overflow, however
	 *         vertical, and the ordinary ones keep the resolution they had.
	 */
	private static float stepFor(Heightfield field, float zOrigin) {
		float highest = zOrigin;
		for (int i = 0; i < field.surfaceCount(); i++)
			highest = Math.max(highest, field.surfaceAt(i));
		return Math.max(Navmesh.FINEST_Z_STEP, (highest - zOrigin) / Navmesh.Z_LEVELS);
	}

	/**
	 * @return The height the map's quantised zero stands for: its lowest surface, rounded down onto a quantisation step so nothing is lost to the
	 *         rounding itself.
	 *         <p>
	 *         Never above zero, so a map that lies entirely above sea level keeps the origin it always had and its mesh is unchanged by this.
	 */
	private static float floorOf(Heightfield field) {
		float lowest = 0;
		for (int i = 0; i < field.surfaceCount(); i++)
			lowest = Math.min(lowest, field.surfaceAt(i));
		return (float) Math.floor(lowest / Navmesh.FINEST_Z_STEP) * Navmesh.FINEST_Z_STEP;
	}
}
