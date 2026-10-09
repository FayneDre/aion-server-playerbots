package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSizeTest {

	@Test
	void readsTheSizeOfTheMapAskedForAndNotItsNeighbours(@TempDir Path directory) throws IOException {
		Path list = directory.resolve("world_maps.xml");
		Files.writeString(list, """
			<world_maps>
				<map id="110010000" cName="LC1" name="Sanctum" name_id="400437" water_level="16" world_type="ELYSEA" world_size="3072" drop_type="NONE"/>
				<map id="110020000" cName="LC2" name="Cloister of Kaisinel" water_level="16" world_size="2048" drop_type="NONE"/>
				<map id="400010000" cName="Ab1" name="Reshanta" world_size="4096"/>
			</world_maps>
			""");

		assertEquals(3072, MapSize.of(list, 110010000));
		assertEquals(2048, MapSize.of(list, 110020000));
		assertEquals(4096, MapSize.of(list, 400010000));
		assertEquals(0, MapSize.of(list, 999), "a map the list does not name has no size");
	}
}
