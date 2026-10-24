package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.templates.item.ItemQuality;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Every manastone of the game put through {@link BotManastones}: a stone a store sells always has a price, so a stat the guide's classes forget shows up
 * here rather than as a stone nobody stocks, and the guide's examples come out at the guide's prices. Reads the item file directly and is skipped
 * without it.
 */
class BotManastonesDataTest {

	private static final File ITEMS = new File("data/static_data/items/item_templates.xml");
	private static final Pattern MANASTONE = Pattern.compile("<item_template [^>]*item_group=\"(SPECIAL_)?MANASTONE\".*?</item_template>", Pattern.DOTALL);

	private static List<ItemTemplate> stones;

	@BeforeAll
	static void readTheStones() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(ITEMS.isFile(), "no game data in this checkout");
		StringBuilder xml = new StringBuilder("<item_templates>");
		Matcher matcher = MANASTONE.matcher(Files.readString(ITEMS.toPath(), StandardCharsets.UTF_8));
		while (matcher.find())
			xml.append(matcher.group());
		ItemData data = JAXBUtil.deserialize(new StringReader(xml.append("</item_templates>").toString()), ItemData.class);
		stones = data.getItemTemplates().stream().toList();
		assertTrue(stones.size() > 700, "only " + stones.size() + " manastones read");
	}

	@Test
	void everyStoneAStoreSellsHasAPrice() {
		Set<String> unpriced = new TreeSet<>();
		for (ItemTemplate stone : stones) {
			if (BotManastones.isSold(stone) && BotManastones.rangeOf(stone) == null)
				unpriced.add(stone.getName());
		}
		assertEquals(Set.of(), unpriced, "stones whose stat no class of the guide places");
	}

	@Test
	void greenSinglesBlueCompositesAndAncientsAreSold() {
		for (ItemTemplate stone : stones) {
			if (stone.getItemGroup() == ItemGroup.MANASTONE && stone.getItemQuality() == ItemQuality.COMMON)
				assertFalse(BotManastones.isSold(stone), stone.getName() + ": nobody sold the common stones");
		}
		assertTrue(BotManastones.isSold(named("Manastone: Crit Strike +19 / HP +50")));
		assertTrue(BotManastones.isSold(named("Ancient Manastone: HP +105")));
	}

	@Test
	void theGuidesExamplesComeOutAtTheGuidesPrices() {
		assertEquals(new BotStorePricing.Range(25_000_000, 55_000_000), BotManastones.rangeOf(named("Manastone: Crit Strike +19 / HP +50")), "tier two");
		assertEquals(new BotStorePricing.Range(60_000_000, 110_000_000), BotManastones.rangeOf(named("Manastone: Attack +5 / Crit Strike +9")), "tier one");
		assertEquals(new BotStorePricing.Range(8_000_000, 20_000_000), BotManastones.rangeOf(named("Manastone: Crit Strike +19 / Parry +14")), "tier three");
		assertEquals(new BotStorePricing.Range(10_000_000, 20_000_000), BotManastones.rangeOf(named("Ancient Manastone: HP +105")), "the guide's best");
		assertEquals(new BotStorePricing.Range(10_000_000, 20_000_000), BotManastones.rangeOf(named("Ancient Manastone: Magic Boost +35")), "over the best");
		assertEquals(new BotStorePricing.Range(300_000, 600_000), BotManastones.rangeOf(named("Ancient Manastone: Attack +5")), "a green ancient");
	}

	@Test
	void underLevelSixtyThePriceHalvesEveryTenLevels() {
		ItemTemplate sixty = single("PHYSICAL_CRITICAL", 60), thirty = single("PHYSICAL_CRITICAL", 30);
		assertEquals(new BotStorePricing.Range(1_500_000, 4_500_000), BotManastones.rangeOf(sixty));
		assertEquals(BotManastones.rangeOf(sixty).halved(3), BotManastones.rangeOf(thirty), "a level 30 stone at an eighth");
	}

	private static ItemTemplate named(String name) {
		return stones.stream().filter(stone -> stone.getName().equals(name) && stone.getCName() != null && !stone.getCName().contains("event")).findFirst()
			.orElseThrow(() -> new AssertionError(name + " is not in the data"));
	}

	private static ItemTemplate single(String stat, int level) {
		return stones.stream().filter(stone -> stone.getItemGroup() == ItemGroup.MANASTONE && stone.getLevel() == level && BotManastones.isSold(stone)
			&& stone.getModifiers().size() == 1 && stone.getModifiers().getFirst().getName().name().equals(stat)).findFirst()
			.orElseThrow(() -> new AssertionError("no green " + stat + " stone of level " + level));
	}
}
