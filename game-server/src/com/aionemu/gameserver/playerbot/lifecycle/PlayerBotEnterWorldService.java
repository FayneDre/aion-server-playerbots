package com.aionemu.gameserver.playerbot.lifecycle;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.BindPointPosition;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.playerbot.ai.PlayerBotAI;
import com.aionemu.gameserver.playerbot.movement.BotMoveController;
import com.aionemu.gameserver.playerbot.navmesh.Heightfield;
import com.aionemu.gameserver.playerbot.navmesh.NavmeshService;
import com.aionemu.gameserver.playerbot.world.BotPlaces;
import com.aionemu.gameserver.services.SkillLearnService;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/**
 * Brings a bot into the world, mirroring the state building parts of {@link com.aionemu.gameserver.services.player.PlayerEnterWorldService} while
 * skipping everything that talks to a client connection.
 * <p>
 * Reviewed against PlayerEnterWorldService as of commit 558677569. Re-diff it after every upstream merge.
 */
public class PlayerBotEnterWorldService {

	/** How far a resident may be found from home before a restart puts it back. Further than any errand it has business running. */
	private static final float STRAYED_TOO_FAR = 150f;

	private PlayerBotEnterWorldService() {
	}

	/**
	 * Places the bot next to the given player and spawns it, so real clients in range can see it.
	 */
	public static void enterWorld(Player bot, Player nextTo) {
		bot.setPosition(World.getInstance().createPosition(nextTo.getWorldId(), nextTo.getX() + 2, nextTo.getY(), nextTo.getZ(), nextTo.getHeading(),
			nextTo.getInstanceId()));
		enterWorld(bot);
	}

	/**
	 * Spawns the bot where it was last saved. {@code PlayerService.getPlayer} has already read that position into the character, so a bot put back
	 * this way resumes exactly where it stood, and its ai anchors its camp there on the spawn event.
	 */
	public static void enterWorld(Player bot) {
		// before anything else: it is what makes the engine treat this character as present despite having no connection
		bot.setBot();
		goHome(bot); // before the ground check, so a strayed resident is put back and then stood on solid ground there
		bindToNearestObelisk(bot);
		standOnGround(bot);
		// a resident is fixed at the level of the place it inhabits: left to progress, every bot drifts upwards and the low regions empty
		bot.getCommonData().setNoExp(BotRoster.isResident(bot.getName()));
		learnMissingSkills(bot);
		applyPassiveSkillEffects(bot);
		bot.setAi(new PlayerBotAI(bot));
		bot.setMoveController(new BotMoveController(bot));
		World.getInstance().storeObject(bot);
		bot.getLifeStats().updateCurrentStats();
		World.getInstance().spawn(bot);
		bot.getController().onEnterWorld();
		// PlayerController never fires AI events, so the bot AI would stay in AIState.CREATED and ignore everything
		bot.getAi().onGeneralEvent(AIEventType.SPAWNED);
	}

	/**
	 * Moves the bot onto ground a body fits on, if it is not already.
	 * <p>
	 * A saved position can be inside the geometry — walked into by the reactive layer, scattered there before the mesh was consulted, or saved while
	 * the map said something else. Such a bot is not merely misplaced, it is unroutable: nothing walkable is within reach, so every journey it ever
	 * attempts is refused and it spends its life asking. Done on entering the world so that a bot stuck once is freed the next time it spawns, rather
	 * than needing to be found and moved by hand.
	 */
	/**
	 * Binds the bot at the obelisk nearest its home, the way a player binds where they work.
	 * <p>
	 * Without it a bot has no bind point at all, and dying sends it to its race's starting location — the far end of the region from the ground it
	 * lives on, which it then has to walk all the way back across. Done on entering the world, so bots made before this get one too, and redone each
	 * time because a bind point is not saved with the character.
	 */
	private static void bindToNearestObelisk(Player bot) {
		Vector3f home = BotRoster.homeOf(bot.getName());
		if (home == null)
			return;
		Vector3f obelisk = BotPlaces.nearestObelisk(bot.getWorldId(), home.getX(), home.getY());
		if (obelisk != null)
			bot.setBindPoint(new BindPointPosition(bot.getWorldId(), obelisk.getX(), obelisk.getY(), obelisk.getZ(), (byte) 0));
	}

	/**
	 * Brings a resident that has strayed a long way back to where it lives.
	 * <p>
	 * A resident belongs to its place: that is the whole of what makes it one. Yet a bot saved halfway across the region resumes exactly there, keeps
	 * that spot as its anchor and works it for ever, so a population drifts a little further from its places at every restart — measured at a hundred
	 * and forty metres from home on average, and one villager six hundred away. A restart is the natural moment to put everyone back.
	 */
	private static void goHome(Player bot) {
		Vector3f home = BotRoster.homeOf(bot.getName());
		if (home == null || PositionUtil.getDistance(bot.getX(), bot.getY(), home.getX(), home.getY()) <= STRAYED_TOO_FAR)
			return;
		LoggerFactory.getLogger(PlayerBotEnterWorldService.class).info("Bot {} had strayed {} m from home and starts there instead", bot.getName(),
			Math.round(PositionUtil.getDistance(bot.getX(), bot.getY(), home.getX(), home.getY())));
		bot.setPosition(World.getInstance().createPosition(bot.getWorldId(), home.getX(), home.getY(), home.getZ(), bot.getHeading(),
			bot.getInstanceId()));
	}

	private static void standOnGround(Player bot) {
		Vector3f ground = NavmeshService.getInstance().groundNear(bot.getWorldId(), bot.getX(), bot.getY(), bot.getZ());
		if (ground == null || PositionUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), ground.getX(), ground.getY(), ground.getZ()) < Heightfield.CELL_SIZE)
			return; // no mesh for this map, or it is already standing where it should be
		LoggerFactory.getLogger(PlayerBotEnterWorldService.class).info("Bot {} was stuck in the scenery and starts at {} {} {} instead",
			bot.getName(), String.format("%.1f", ground.getX()), String.format("%.1f", ground.getY()), String.format("%.1f", ground.getZ()));
		bot.setPosition(World.getInstance().createPosition(bot.getWorldId(), ground.getX(), ground.getY(), ground.getZ(), bot.getHeading(),
			bot.getInstanceId()));
	}

	/**
	 * Teaches the bot everything its class learns by itself up to its level.
	 * <p>
	 * A real character collects these one level at a time from the moment it is created. A bot made at level twenty never lived through the first
	 * nineteen, so it never received any of them, and nothing since would: {@code onLevelChange} only teaches the levels actually gained. What was
	 * missing was not only damage — the armour and weapon masteries are learned at level one, and without them
	 * {@code Equipment.equipItem} refuses every piece of gear a bot ever loots, silently, for ever.
	 * <p>
	 * Done on entering the world rather than at creation so that bots made before this existed are mended the next time they spawn. Asking twice
	 * costs nothing: the service keeps only the highest rank of each skill.
	 */
	private static void learnMissingSkills(Player bot) {
		SkillLearnService.learnNewSkills(bot, 1, bot.getLevel());
	}

	/**
	 * Copy of PlayerEnterWorldService#activatePassiveSkillEffects, which is private. Without it the bot misses all stat bonuses from passive skills.
	 */
	private static void applyPassiveSkillEffects(Player bot) {
		for (PlayerSkillEntry skillEntry : bot.getSkillList().getAllSkills()) {
			SkillTemplate skillTemplate = DataManager.SKILL_DATA.getSkillTemplate(skillEntry.getSkillId());
			if (skillTemplate.isPassive())
				SkillEngine.getInstance().applyEffectDirectly(skillTemplate, skillEntry.getSkillLevel(), bot, bot);
		}
	}
}
