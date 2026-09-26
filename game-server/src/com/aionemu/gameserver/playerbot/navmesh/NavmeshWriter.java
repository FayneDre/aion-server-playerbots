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

		byte[][] compressedTiles = new byte[tileCount][];
		for (int tileY = 0; tileY < tilesY; tileY++)
			for (int tileX = 0; tileX < tilesX; tileX++)
				compressedTiles[tileY * tilesX + tileX] = compress(tile(field, tileX, tileY));

		try (OutputStream out = Files.newOutputStream(file)) {
			ByteBuffer header = ByteBuffer.allocate(Navmesh.MAGIC.length() + 4 * 6 + tileCount * 8);
			header.put(Navmesh.MAGIC.getBytes());
			header.putInt(mapId).putInt(field.width()).putInt(field.height()).putFloat(Heightfield.CELL_SIZE).putInt(tilesX).putInt(tileCount);
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
	private static byte[] tile(Heightfield field, int tileX, int tileY) throws IOException {
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
					heights[index] = quantize(field.surfaceAt(i));
					if (field.isWalkable(i))
						walkable[index >> 6] |= 1L << index;
					index++;
				}
			}
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(surfaces);
			for (char rowOffset : rowOffsets)
				out.writeChar(rowOffset);
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
	private static short quantize(float z) {
		return (short) Math.max(0, Math.min(0xFFFF, Math.round(z / Navmesh.Z_STEP)));
	}
}
