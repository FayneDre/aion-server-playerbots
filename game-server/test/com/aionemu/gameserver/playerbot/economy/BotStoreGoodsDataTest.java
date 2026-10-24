package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.items.ItemMask;

/**
 * The goods of {@link BotStoreGoods}, held against the item data: each id exists, is what the guide meant by it, and can go in a store. The ids came
 * from the client, whose names are not the server's, so this is what says the two still agree. Reads the item file directly and is skipped without it.
 */
class BotStoreGoodsDataTest {

	private static final File ITEMS = new File("data/static_data/items/item_templates.xml");
	private static final Pattern TEMPLATE = Pattern.compile("<item_template id=\"(\\d+)\" name=\"([^\"]*)\"[^>]*?mask=\"(\\d+)\"[^>]*>");
	private static final Pattern GROUP = Pattern.compile("item_group=\"([A-Z_]+)\"");

	private record Template(String name, int mask, String group) {
	}

	private static final Map<Integer, Template> templates = new HashMap<>();

	@BeforeAll
	static void readTheItems() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(ITEMS.isFile(), "no game data in this checkout");
		Matcher matcher = TEMPLATE.matcher(Files.readString(ITEMS.toPath(), StandardCharsets.UTF_8));
		while (matcher.find()) {
			Matcher group = GROUP.matcher(matcher.group());
			templates.put(Integer.parseInt(matcher.group(1)), new Template(matcher.group(2), Integer.parseInt(matcher.group(3)), group.find() ? group.group(1) : ""));
		}
	}

	@Test
	void everyGoodExistsAndCanGoInAStore() {
		for (BotStoreGoods.Good good : allGoods()) {
			for (int id : good.ids()) {
				Template template = templates.get(id);
				assertNotNull(template, id + " is not in the item data");
				assertTrue((template.mask() & ItemMask.TRADEABLE) != 0, template.name() + " (" + id + ") is not tradeable, the engine refuses it in a store");
			}
		}
	}

	@Test
	void theUpgradeItemsAreTheOnesTheGuideMeant() {
		assertEquals(List.of("Alpha Enchantment Stone", "Beta Enchantment Stone", "Gamma Enchantment Stone", "Delta Enchantment Stone",
			"Epsilon Enchantment Stone", "Omega Enchantment Stone", "Amplification Stone", "Tempering Solution"),
			BotStoreGoods.UPGRADES.stream().map(good -> templates.get(good.ids().getFirst()).name()).toList());
	}

	@Test
	void theCoinsAndMedalsAreTheOnesTheGuideMeant() {
		for (BotStoreGoods.Good good : BotStoreGoods.COINS) {
			String name = templates.get(good.ids().getFirst()).name();
			for (int id : good.ids()) {
				assertEquals(name, templates.get(id).name(), "one faction's copy of " + name);
				assertTrue(List.of("COINS", "MEDALS").contains(templates.get(id).group()), name + " (" + id + ") is no coin or medal");
			}
			assertFalse(name.startsWith("Iron") || name.matches("(Platinum|Mithril|Ceramium) Medal"), name + " is left out by decision");
		}
	}

	@Test
	void everyRangeHasItsBottomUnderItsTop() {
		for (BotStoreGoods.Good good : allGoods()) {
			assertTrue(good.low() > 0 && good.low() <= good.high(), good.toString());
			assertTrue(good.minCount() > 0 && good.minCount() <= good.maxCount(), good.toString());
		}
	}

	private static List<BotStoreGoods.Good> allGoods() {
		return java.util.stream.Stream.concat(BotStoreGoods.UPGRADES.stream(), BotStoreGoods.COINS.stream()).toList();
	}
}
