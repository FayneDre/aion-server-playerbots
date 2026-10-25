package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * Which power shards a bot buys and wears: the merchants' ones, read from the shard templates of the item data.
 */
class BotPowerShardsTest {

	private static ItemData items;

	@BeforeAll
	static void readTheShards() throws Exception {
		items = JAXBUtil.deserialize(new StringReader("""
			<item_templates>
				<item_template id="169000004" name="Lesser Power Shard" level="10" cName="battery_10" mask="4" item_group="POWER_SHARDS" quality="COMMON" price="15" weapon_boost="15"/>
				<item_template id="169000011" name="Ancient Power Shard" level="65" cName="battery_70" mask="4" item_group="POWER_SHARDS" quality="RARE" price="500" weapon_boost="45"/>
				<item_template id="169000001" name="+10 Attack Power Shard" level="1" cName="test_battery_01" mask="4" item_group="POWER_SHARDS" quality="COMMON" price="1" weapon_boost="10"/>
				<item_template id="169000006" name="Duaguru Power Shard" level="1" cName="battery_q_01" mask="4" item_group="POWER_SHARDS" quality="COMMON" price="1" weapon_boost="20"/>
				<item_template id="169001001" name="[Legion Reward] Lesser Power Shard" level="10" cName="rmc_battery_10" mask="4" item_group="POWER_SHARDS" quality="RARE" price="5" weapon_boost="15"/>
				<item_template id="169005001" name="[Event] Lesser Power Shard" level="10" cName="event_battery_10" mask="4" item_group="POWER_SHARDS" quality="COMMON" price="5" weapon_boost="15"/>
				<item_template id="169000012" name="Abyssal Power Shard" level="60" cName="abyssrank10_battery_60" mask="4" item_group="POWER_SHARDS" quality="RARE" price="367" weapon_boost="40"/>
			</item_templates>
			"""), ItemData.class);
	}

	@Test
	void onlyTheMerchantsShardsAreBought() {
		assertTrue(BotPowerShards.isOrdinary(shard(169000004)));
		assertTrue(BotPowerShards.isOrdinary(shard(169000011)), "battery_70 is the level 65 one");
		assertFalse(BotPowerShards.isOrdinary(shard(169000001)), "a test shard");
		assertFalse(BotPowerShards.isOrdinary(shard(169000006)), "a quest's");
		assertFalse(BotPowerShards.isOrdinary(shard(169001001)), "a legion reward");
		assertFalse(BotPowerShards.isOrdinary(shard(169005001)), "an event's");
		assertFalse(BotPowerShards.isOrdinary(shard(169000012)), "the abyss rank one");
	}

	private static ItemTemplate shard(int id) {
		return items.getItemTemplate(id);
	}
}
