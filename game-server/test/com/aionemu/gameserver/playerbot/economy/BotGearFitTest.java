package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.utils.xml.JAXBUtil;

/**
 * The armour type of helmets and who carries a shield, on pieces a store of gladiator gear was seen selling, copied from the item data.
 */
class BotGearFitTest {

	private static ItemData items;

	@BeforeAll
	static void readThePieces() throws Exception {
		items = JAXBUtil.deserialize(new StringReader("""
			<item_templates>
				<item_template id="125000938" name="Blue Sky Headband" level="32" cName="rb_head_n_l1_32a" mask="37118" item_group="HEAD" quality="LEGEND" price="262800"/>
				<item_template id="125001553" name="Bloody Flame Helm" level="40" cName="pl_head_n_u1_40a" mask="37118" item_group="HEAD" quality="UNIQUE" price="1591350"/>
				<item_template id="125000744" name="Shadow Hat" level="40" cName="lt_head_n_r1_40a" mask="37118" item_group="HEAD" quality="RARE" price="994600"/>
				<item_template id="125000001" name="A Hat" level="40" cName="ac_head_n_r1_40a" mask="37118" item_group="HEAD" quality="RARE" price="994600"/>
				<item_template id="115000722" name="Tantuka's Shield" level="39" cName="shield_n_r2_39b" mask="37118" item_group="SHIELD" quality="RARE" price="821950"/>
				<item_template id="110600471" name="Rank 4 Elyos Breastplate" level="41" cName="pl_torso_n_r1_c_41a" mask="37118" item_group="PL_TORSO" quality="RARE" price="1143800"/>
			</item_templates>
			"""), ItemData.class);
	}

	@Test
	void aHelmetWeighsWhatItsModelIsMadeOf() {
		assertEquals(BotGearFit.armourWeight(piece(110600471)), BotGearFit.armourWeight(piece(125001553)), "a plate helm weighs what a breastplate does");
		assertTrue(BotGearFit.armourWeight(piece(125000744)) < BotGearFit.armourWeight(piece(125001553)), "leather under plate");
		assertTrue(BotGearFit.armourWeight(piece(125000938)) < BotGearFit.armourWeight(piece(125000744)), "a robe's headband under leather");
		assertEquals(0, BotGearFit.armourWeight(piece(125000001)), "a hat any class wears has no type");
	}

	@Test
	void onlyTheTemplarAndTheClericCarryAShield() {
		assertTrue(BotGearFit.isShield(piece(115000722)));
		assertFalse(BotGearFit.isShield(piece(125001553)));
		assertTrue(BotGearFit.carriesAShield(PlayerClass.TEMPLAR));
		assertTrue(BotGearFit.carriesAShield(PlayerClass.CLERIC));
		assertFalse(BotGearFit.carriesAShield(PlayerClass.GLADIATOR), "a polearm takes both hands");
		assertFalse(BotGearFit.carriesAShield(PlayerClass.ASSASSIN), "two blades");
		assertFalse(BotGearFit.carriesAShield(PlayerClass.CHANTER), "a staff");
	}

	private static ItemTemplate piece(int id) {
		return items.getItemTemplate(id);
	}
}
