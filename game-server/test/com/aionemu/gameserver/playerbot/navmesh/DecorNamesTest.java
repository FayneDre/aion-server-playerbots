package com.aionemu.gameserver.playerbot.navmesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DecorNamesTest {

	private static boolean decor(String file) {
		return HeightfieldBuilder.isDecor("levels/common/dark/props/" + file);
	}

	@Test
	void potsBarrelsFiresAndTentsAreDecor() {
		assertTrue(decor("pr_l_cavepot_01a.cgf"));
		assertTrue(decor("pr_d_winebarrel_01a.cgf"));
		assertTrue(decor("pr_d_firepot_01a_fire.cgf"));
		assertTrue(decor("bu_d_lehpartent_02b.cgf"), "a tent is a bu_ like a house, and decor all the same");
		assertTrue(decor("bu_d_lehpartentsnow_01a.cgf"));
	}

	@Test
	void propsThatAreStructureAreNot() {
		assertFalse(decor("pr_d_wall_set_01a.cgf"), "a wall set is a wall");
		assertFalse(decor("pr_d_hoborpillar_01a.cgf"), "and a pillar a pillar");
		assertFalse(decor("pr_l_minicover_08h.cgf"));
		assertFalse(decor("pr_d_fence_02a.cgf"));
		assertFalse(decor("pr_l_prison_pole_05_a_01a.cgf"), "the bars of a prison");
	}

	@Test
	void buildingsAndNaturalModelsAreNot() {
		assertFalse(decor("bu_d_icetemplestair_01a.cgf"));
		assertFalse(decor("na_l_rockybig_03b.cgf"));
		assertFalse(decor("bu_ly_lycanhouse_01a.cgf"));
	}
}
