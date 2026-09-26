package com.aionemu.gameserver.playerbot.navmesh;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a {@link Heightfield} out in the compact form {@link Navmesh} reads back.
 * <p>
 * Offline half of the format, kept apart from the runtime half so the server never carries generation code it cannot use.
 */
public class NavmeshWriter {

	private NavmeshWriter() {
	}

	public static Path write(int mapId, Heightfield field) throws IOException {
		Path file = Navmesh.fileOf(mapId);
		Files.createDirectories(file.getParent());
		int tilesX = (field.width() + Navmesh.TILE_SIZE - 1) / Navmesh.TILE_SIZE;
		int tilesY = (field.height() + Navmesh.TILE_SIZE - 1) / Navmesh.TILE_SIZE;

		try (DataOutputStream out = new DataOutputStream(
			new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(file)), new Deflater(Deflater.BEST_SPEED)))) {
			out.write(Navmesh.MAGIC.getBytes());
			out.writeInt(mapId);
			out.writeInt(field.width());
			out.writeInt(field.height());
			out.writeFloat(Heightfield.CELL_SIZE);
			out.writeInt(tilesX);
			out.writeInt(tilesX * tilesY);
			for (int tileY = 0; tileY < tilesY; tileY++)
				for (int tileX = 0; tileX < tilesX; tileX++)
					writeTile(out, field, tileX, tileY);
		}
		return file;
	}

	private static void writeTile(DataOutputStream out, Heightfield field, int tileX, int tileY) throws IOException {
		int originX = tileX * Navmesh.TILE_SIZE, originY = tileY * Navmesh.TILE_SIZE;
		byte[] counts = new byte[Navmesh.TILE_SIZE * Navmesh.TILE_SIZE];
		char[] rowOffsets = new char[Navmesh.TILE_SIZE + 1];
		int surfaces = 0;

		for (int localY = 0; localY < Navmesh.TILE_SIZE; localY++) {
			rowOffsets[localY] = (char) surfaces;
			for (int localX = 0; localX < Navmesh.TILE_SIZE; localX++) {
				int cellX = originX + localX, cellY = originY + localY;
				if (cellX >= field.width() || cellY >= field.height())
					continue;
				int count = Math.min(field.columnEnd(cellX, cellY) - field.columnStart(cellX, cellY), 255);
				counts[localY * Navmesh.TILE_SIZE + localX] = (byte) count;
				surfaces += count;
			}
		}
		rowOffsets[Navmesh.TILE_SIZE] = (char) surfaces;

		out.writeInt(surfaces);
		if (surfaces == 0)
			return;
		for (char offset : rowOffsets)
			out.writeChar(offset);
		out.write(counts);

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
					heights[index] = quantize(field.surfaceAt(i));
					if (field.isWalkable(i))
						walkable[index >> 6] |= 1L << index;
					index++;
				}
			}
		}
		for (short z : heights)
			out.writeShort(z);
		for (long bits : walkable)
			out.writeLong(bits);
	}

	/** Heights below zero or above the engine's 2048 m ceiling are not real ground, so clamping them loses nothing. */
	private static short quantize(float z) {
		return (short) Math.max(0, Math.min(0xFFFF, Math.round(z / Navmesh.Z_STEP)));
	}
}
