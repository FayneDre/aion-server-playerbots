package com.aionemu.gameserver.playerbot.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.ItemData;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
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
				<item_template id="187000000" name="Darrinerk's Wing Feather" level="30" cName="wing_light_30a" mask="4812" item_group="WING" quality="RARE" price="800000"/>
				<item_template id="187000002" name="Midnight Wing Feather" level="30" cName="wing_dark_30a" mask="4812" item_group="WING" quality="RARE" price="800000"/>
				<item_template id="187000066" name="Darina's Wing" level="20" cName="wing_d_n_l2_h_20a" mask="4812" item_group="WING" quality="LEGEND" price="1"/>
				<item_template id="187000061" name="Gauon's Wings" level="20" cName="wing_n_l2_h_20a" mask="4812" item_group="WING" quality="LEGEND" price="1"/>
				<item_template id="187000018" name="Storm Wings" level="55" cName="wing_rudra_u1_50a" mask="4812" item_group="WING" quality="UNIQUE" price="1"/>
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

	@Test
	void wingsNameTheirSideInTheirModel() {
		assertEquals(Race.ELYOS, BotGearFit.wingSide(piece(187000000)));
		assertEquals(Race.ASMODIANS, BotGearFit.wingSide(piece(187000002)));
		assertEquals(Race.ASMODIANS, BotGearFit.wingSide(piece(187000066)), "the side comes first in the model: d before n");
		assertEquals(Race.ELYOS, BotGearFit.wingSide(piece(187000061)));
		assertEquals(Race.PC_ALL, BotGearFit.wingSide(piece(187000018)), "a dungeon's wings are anyone's");
	}

	private static ItemTemplate piece(int id) {
		return items.getItemTemplate(id);
	}
}
