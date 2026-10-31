package com.aionemu.gameserver.playerbot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WalkedTrailsTest {

	@Test
	void theCellOfAPointIsAMetreOfGroundAndHeight() {
		assertEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(10.9f, 20.1f, 5.8f));
		assertNotEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(11.2f, 20.7f, 5.1f));
		assertNotEquals(WalkedTrails.cellOf(10.2f, 20.7f, 5.1f), WalkedTrails.cellOf(10.2f, 20.7f, 7.1f));
	}
}
