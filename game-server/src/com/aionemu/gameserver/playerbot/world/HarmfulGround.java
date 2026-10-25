package com.aionemu.gameserver.playerbot.world;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.materials.MaterialSkill;
import com.aionemu.gameserver.model.templates.materials.MaterialTemplate;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Ground that hurts whoever stands on it: lava, a poisoned pool, a fortress's aetheric field. The engine casts the material's skill on the body
 * every few seconds from what is under its feet ({@code TerrainZoneCollisionMaterialActor} for the terrain, a {@code MaterialZoneHandler} for a
 * mesh). Nothing told a bot so, and one was seen sitting down to rest in the lava of Theobomos's Sulfur Swamp after fighting the hoverstones over it,
 * the burn taking back what the rest gave.
 */
public final class HarmfulGround {

	/** How far from the body to look for ground that does not hurt, in metres: off a lava flow, not across the map. */
	private static final float[] RADII = { 4, 8, 12, 18, 25 };
	private static final int DIRECTIONS = 8;

	private HarmfulGround() {
	}

	/**
	 * @return true if the bot stands on ground whose material casts a hostile skill on it. A mesh's material is entered as a zone only when it has a
	 *         skill for the body, so being in one is taken as hurt: the few that heal are a fountain the bot loses nothing by stepping out of.
	 */
	public static boolean isUnder(Player bot) {
		return bot.getController().hasTask(TaskId.ZONE_MATERIAL_ACTION) || hurtsAt(bot, bot.getX(), bot.getY(), bot.getZ());
	}

	/** @return true if the terrain at the spot is of a material that casts a hostile skill on the bot. */
	static boolean hurtsAt(Player bot, float x, float y, float z) {
		GeoService geo = GeoService.getInstance();
		if (!geo.worldHasTerrainMaterials(bot.getWorldId()))
			return false;
		int materialId = geo.getTerrainMaterialAt(bot.getWorldId(), x, y, z, bot.getInstanceId());
		MaterialTemplate material = materialId == 0 ? null : DataManager.MATERIAL_DATA.getTemplate(materialId);
		if (material == null)
			return false;
		for (MaterialSkill skill : material.getSkills()) {
			if (!skill.getTarget().matches(bot))
				continue;
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(skill.getId());
			if (template != null && template.getProperties() != null && template.getProperties().getTargetRelation() == TargetRelationAttribute.ENEMY)
				return true;
		}
		return false;
	}

	/**
	 * @return The nearest ground round the bot, by the mesh, whose terrain does not hurt it, nearest ring first. Null when the map has no mesh or
	 *         nothing near enough is safe. A mesh's material cannot be asked of a spot, only of a body standing in it, so the bot may arrive on another
	 *         patch of it and look again.
	 */
	public static Vector3f safeGroundNear(Player bot) {
		int start = Math.floorMod(bot.getObjectId(), DIRECTIONS); // bots burning together do not all pick the same side
		for (float radius : RADII) {
			for (int i = 0; i < DIRECTIONS; i++) {
				double angle = 2 * Math.PI * ((start + i) % DIRECTIONS) / DIRECTIONS;
				float x = bot.getX() + (float) Math.cos(angle) * radius, y = bot.getY() + (float) Math.sin(angle) * radius;
				Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), x, y, bot.getZ());
				if (ground != null && !hurtsAt(bot, ground.getX(), ground.getY(), ground.getZ()))
					return ground;
			}
		}
		return null;
	}
}
