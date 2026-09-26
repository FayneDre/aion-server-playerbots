package com.aionemu.gameserver.playerbot.navmesh;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.geoEngine.math.Matrix3f;
import com.aionemu.gameserver.geoEngine.math.Vector3f;

/**
 * Reads the collision geometry straight from {@code data/geo}, without starting a server.
 * <p>
 * The navmesh generator runs offline, where {@code GeoWorldLoader} cannot: it needs {@code DataManager}, zone services and the whole world model.
 * This reader parses the same two formats and nothing else. It is therefore a **duplicate of the format knowledge** in
 * {@link com.aionemu.gameserver.geoEngine.GeoWorldLoader}, which is the reference — re-read it after every upstream merge, and compare the counts
 * this reader reports against the server's own startup log.
 */
public class GeoDataReader {

	public static final Path GEO_DIR = Path.of("data/geo");

	private GeoDataReader() {
	}

	/**
	 * Reads every collision mesh. Names containing {@code |} are aliases sharing one geometry, and are expanded into one entry each, exactly as the
	 * server does.
	 */
	public static Map<String, List<GeoModel>> readModels() throws IOException {
		Map<String, List<GeoModel>> models = new HashMap<>();
		try (FileChannel channel = FileChannel.open(GEO_DIR.resolve("models.mesh"))) {
			MappedByteBuffer geo = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			while (geo.hasRemaining()) {
				String name = readName(geo, geo.getShort());
				List<GeoModel> parts = new ArrayList<>();
				int modelCount = geo.get() & 0xFF;
				for (int i = 0; i < modelCount; i++)
					parts.add(readModel(geo, name));
				for (String alias : name.split("\\|"))
					models.put(alias, parts);
			}
		}
		return models;
	}

	private static GeoModel readModel(ByteBuffer geo, String name) throws IOException {
		int vertexCount = geo.getShort() & 0xFFFF;
		int verticesBytes = vertexCount * 3 * 4; // three floats per vertex, four bytes each
		float[] vertices = new float[vertexCount * 3];
		FloatBuffer vertexBuffer = geo.slice(geo.position(), verticesBytes).asFloatBuffer();
		vertexBuffer.get(vertices);
		geo.position(geo.position() + verticesBytes);

		int faceCount = geo.getShort() & 0xFFFF;
		byte indexSize = geo.get();
		int facesBytes = faceCount * 3 * indexSize;
		int[] indices = new int[faceCount * 3];
		switch (indexSize) {
			case 1 -> {
				ByteBuffer byteIndices = geo.slice(geo.position(), facesBytes);
				for (int i = 0; i < indices.length; i++)
					indices[i] = byteIndices.get() & 0xFF;
			}
			case 2 -> {
				ShortBuffer shortIndices = geo.slice(geo.position(), facesBytes).asShortBuffer();
				for (int i = 0; i < indices.length; i++)
					indices[i] = shortIndices.get() & 0xFFFF;
			}
			default -> throw new IOException("Index size " + indexSize + " is not supported");
		}
		geo.position(geo.position() + facesBytes);

		return new GeoModel(name, vertices, indices, geo.get(), geo.get());
	}

	/**
	 * Reads where each model sits on the given map.
	 *
	 * @return An empty list if that map has no geometry file, which is normal for prisons and test maps.
	 */
	public static List<GeoPlacement> readPlacements(int mapId) throws IOException {
		Path file = GEO_DIR.resolve(mapId + ".geo");
		if (!Files.isRegularFile(file))
			return List.of();

		List<GeoPlacement> placements = new ArrayList<>();
		try (FileChannel channel = FileChannel.open(file)) {
			MappedByteBuffer geo = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			while (geo.hasRemaining()) {
				String name = readName(geo, geo.getShort());
				Vector3f position = new Vector3f(geo.getFloat(), geo.getFloat(), geo.getFloat());
				Matrix3f rotation = new Matrix3f();
				for (int row = 0; row < 3; row++)
					for (int column = 0; column < 3; column++)
						rotation.set(row, column, geo.getFloat());
				Vector3f scale = new Vector3f(geo.getFloat(), geo.getFloat(), geo.getFloat());
				placements.add(new GeoPlacement(name, position, rotation, scale, geo.get(), geo.getShort(), geo.get()));
			}
		}
		return placements;
	}

	/** @return The ids of every map with a geometry file, sorted. */
	public static List<Integer> listMapIds() throws IOException {
		try (var files = Files.list(GEO_DIR)) {
			return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".geo"))
				.map(name -> Integer.parseInt(name.substring(0, name.length() - 4))).sorted().toList();
		}
	}

	private static String readName(ByteBuffer geo, int length) {
		byte[] name = new byte[length];
		geo.get(name);
		return new String(name);
	}
}
