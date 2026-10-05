package com.aionemu.gameserver.playerbot.economy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.drop.Drop;
import com.aionemu.gameserver.model.drop.DropGroup;
import com.aionemu.gameserver.model.drop.NpcDrop;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.QuestTemplate;
import com.aionemu.gameserver.model.templates.globaldrops.GlobalDropItem;
import com.aionemu.gameserver.model.templates.globaldrops.GlobalDropWorld;
import com.aionemu.gameserver.model.templates.globaldrops.GlobalRule;
import com.aionemu.gameserver.model.templates.goods.GoodsList;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.quest.QuestCategory;
import com.aionemu.gameserver.model.templates.quest.QuestItems;
import com.aionemu.gameserver.model.templates.quest.Rewards;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate.TradeTab;

/**
 * Where a piece of gear comes from, and therefore whether a bot may have it and whose side it belongs to.
 * <p>
 * Dressing a bot used to ask the item template two questions as a stand-in for "could a player be handed this": does it require an armour or weapon
 * mastery, and does it declare its own level restrictions. Both reject the gear that matters most.
 * <p>
 * <b>Every accessory fails the first.</b> Rings, earrings, necklaces, belts and head pieces sit in {@code ArmorType.ACCESSORY}, which needs no
 * mastery at all, so {@code SkillData.getMasterySkills} answers with an empty set and the piece is read as npc costume. No bot has ever worn a ring.
 * <p>
 * <b>The coin vendor tier fails the second.</b> The level 36 fabled armour bought with silver coins — "Eltnen Sun Legionary Boots" and its kind —
 * carries no {@code restrict} attribute whatsoever, so it declares no level restrictions and asks level one of every class, exactly like the test
 * gear the question was written to catch.
 * <p>
 * So this asks where the thing comes from instead, reading the same four places a player would reach it through. An item that is a quest reward, on
 * a vendor's shelf or in a drop table is gear; anything else falls back on the old test, which still serves for ordinary levelling drops that are in
 * none of these tables.
 */
public class BotGearSources {

	/**
	 * @param race
	 *          Whose side it belongs to, or {@code PC_ALL} when no source said or two sources disagreed.
	 * @param fromLevel
	 *          The lowest level at which a character can reach it, or 0 when nothing says.
	 */
	private record Source(Race race, int fromLevel) {

		Source mergedWith(Source other) {
			// Two sources that name different sides cancel out rather than one winning: a piece sold by both factions' vendors is genuinely neutral,
			// and guessing between them is how a bot ends up in armour its body cannot draw.
			Race merged = race == other.race ? race : Race.PC_ALL;
			return new Source(merged, Math.min(fromLevel, other.fromLevel));
		}
	}

	/** Built once, on the first question asked, because every holder it reads must have finished loading. */
	private static volatile Map<Integer, Source> sources;

	private BotGearSources() {
	}

	/** @return true if this is gear a player could actually come by, which is what tells it from npc costume and development leftovers. */
	public static boolean isReachable(ItemTemplate template) {
		return index().containsKey(template.getTemplateId());
	}

	/**
	 * @return The lowest level at which a character can come by this, or 0 when no source says. Only quests state it; a vendor sells to anyone who
	 *         can pay and a drop falls for whoever kills the thing.
	 */
	public static int reachableFrom(ItemTemplate template) {
		Source source = index().get(template.getTemplateId());
		return source == null ? 0 : source.fromLevel();
	}

	/**
	 * Keeps a bot out of the other faction's gear, which its body has no model for and so simply does not draw.
	 * <p>
	 * Reported from in game as a cleric of thirty with only its head and arms visible, wearing a "Defeated Guardian's Hauberk". The piece is not
	 * Asmodian as far as this server is concerned: <b>no armour template in the whole table declares a race, and all 12995 weapon templates declare
	 * {@code PC_ALL}</b>, so {@code ItemTemplate.getRace()} has nothing to say about either. The faction lives only in the client model name.
	 *
	 * @return true if this belongs to the side the bot is not on.
	 */
	public static boolean isOtherFaction(Player bot, ItemTemplate template) {
		Race race = factionOf(template);
		return race.isAsmoOrEly() && race != bot.getRace();
	}

	/**
	 * @return Whose side a piece belongs to, or {@code PC_ALL} when it belongs to neither or nothing says.
	 *         <p>
	 *         The source is asked first and believed when it answers, because a quest that only Elyos may take and a vendor only Asmodians can reach
	 *         settle this outright. Only when no source names a side is the model name read.
	 */
	public static Race factionOf(ItemTemplate template) {
		Source source = index().get(template.getTemplateId());
		if (source != null && source.race().isAsmoOrEly())
			return source.race();
		return factionInModelName(template.getCName());
	}

	/**
	 * Reads the side off the client model name.
	 * <p>
	 * The marker is a single letter standing alone between underscores: {@code d} is Asmodian, {@code n} and {@code g} are Elyos, {@code u} and
	 * {@code e} belong to neither. <b>It sits at no fixed position</b>, which is what the first version of this got wrong: it took the third segment
	 * for armour and the second for everything else, and accessories are armour that names itself in one word, so {@code ring_n_c_21a} was read as
	 * {@code c} and every ring came out factionless. The four shapes in the table are {@code ch_torso_d_n_c1_light_30a}, {@code harp_d_n_r1_16n},
	 * {@code ring_n_c_21a} and {@code ac_hat_d_n_c1_10a} — third, second, second and third.
	 * <p>
	 * Scanning for the first marker rather than counting to it handles all four, and is safe because no prefix word anywhere in the table is a single
	 * letter. Measured over every item whose English name says Elyos or Asmodian, leaving out the npc and test names no bot can reach: <b>3432 read
	 * correctly and 4 wrongly</b>, the four being level 60 "Elyos Daevanion" pieces whose model name says {@code d}. That is an error in the data
	 * rather than in the rule, and special-casing four items would cost more than it saves.
	 */
	private static Race factionInModelName(String cName) {
		if (cName == null)
			return Race.PC_ALL;
		for (String segment : cName.split("_")) {
			switch (segment) {
				case "d":
					return Race.ASMODIANS;
				case "n", "g":
					return Race.ELYOS;
				case "u", "e":
					return Race.PC_ALL; // abyss and shared models, which belong to neither side
			}
		}
		return Race.PC_ALL;
	}

	private static Map<Integer, Source> index() {
		Map<Integer, Source> built = sources;
		if (built == null) {
			synchronized (BotGearSources.class) {
				built = sources;
				if (built == null)
					sources = built = build();
			}
		}
		return built;
	}

	private static Map<Integer, Source> build() {
		Map<Integer, Source> found = new HashMap<>();
		addQuestRewards(found);
		addVendorStock(found);
		addNamedDrops(found);
		addChestDrops(found);
		LoggerFactory.getLogger(BotGearSources.class).info("Bots can reach {} piece(s) of gear", found.size());
		return found;
	}

	/**
	 * Every quest but the seasonal ones.
	 * <p>
	 * The plan was the campaign alone — the missions, which the client paints yellow — on the grounds that it is the one line of quests every
	 * character follows and there are a few hundred of them against several thousand ordinary quests. Counted rather than assumed, that leaves 899
	 * pieces of gear out of 4348, and <b>it drops the very tier this was written for</b>: the level 36 fabled armour comes from "Mamaki Patrol",
	 * which is category {@code IMPORTANT}. Narrowing bought nothing a map of four thousand entries needed, and cost the gear.
	 * <p>
	 * Events are the one category left out, because a bot would be wearing the reward of a quest that only exists for two weeks in December.
	 */
	private static void addQuestRewards(Map<Integer, Source> found) {
		for (QuestTemplate quest : DataManager.QUEST_DATA.getQuestTemplates()) {
			if (quest.getCategory() == QuestCategory.EVENT)
				continue;
			Source source = new Source(playerRaceOf(quest.getRacePermitted()), Math.max(0, quest.getMinlevelPermitted()));
			for (Rewards rewards : quest.getRewards())
				addQuestItems(found, source, rewards);
			addQuestItems(found, source, quest.getExtendedRewards());
			// The class specific choices are kept on the quest rather than on its rewards, one list per class, and they are where the gear mostly is:
			// a mission hands a templar a shield and a sorcerer an orb from the same step.
			for (PlayerClass playerClass : PlayerClass.values()) {
				for (QuestItems item : quest.getSelectableRewardByClass(playerClass))
					add(found, item.getItemId(), source);
			}
		}
	}

	private static void addQuestItems(Map<Integer, Source> found, Source source, Rewards rewards) {
		if (rewards == null)
			return;
		addAll(found, source, rewards.getRewardItem());
		addAll(found, source, rewards.getSelectableRewardItem());
	}

	private static void addAll(Map<Integer, Source> found, Source source, List<QuestItems> items) {
		if (items == null)
			return;
		for (QuestItems item : items)
			add(found, item.getItemId(), source);
	}

	/**
	 * Everything on a shelf, which is how the bronze, silver and gold coin gear is reached.
	 * <p>
	 * The seller settles the faction and settles it properly: a vendor standing in Sanctum is Elyos and so is everything on its shelf, whatever the
	 * model name happens to say. A seller of neither race — the few neutral traders — claims nothing and leaves the model name to decide.
	 */
	private static void addVendorStock(Map<Integer, Source> found) {
		for (TradeListTemplate tradeList : DataManager.TRADE_LIST_DATA.getTradeListTemplate().values()) {
			NpcTemplate seller = DataManager.NPC_DATA.getNpcTemplate(tradeList.getNpcId());
			Source source = new Source(seller == null ? Race.PC_ALL : playerRaceOf(seller.getRace()), 0);
			for (TradeTab tab : tradeList.getTradeTablist()) {
				GoodsList goods = DataManager.GOODSLIST_DATA.getGoodsListById(tab.getId());
				if (goods == null || goods.getItemIdList() == null)
					continue;
				for (Integer itemId : goods.getItemIdList())
					add(found, itemId, source);
			}
		}
	}

	/**
	 * The named drops, which is where the solo instance loot lives — the level 17 and level 37 accessories a character is expected to come out of
	 * Haramel and Kromede's Trial wearing.
	 */
	private static void addNamedDrops(Map<Integer, Source> found) {
		for (GlobalRule rule : DataManager.GLOBAL_DROP_DATA.getAllRules()) {
			if (rule.getDropItems() == null)
				continue;
			Source source = new Source(factionOf(rule), 0);
			for (GlobalDropItem item : rule.getDropItems())
				add(found, item.getId(), source);
		}
	}

	/** A rule may name the side outright; failing that, a rule that only runs in one faction's home worlds is that faction's. */
	private static Race factionOf(GlobalRule rule) {
		if (rule.getRestrictionRace() != null)
			return rule.getRestrictionRace() == GlobalRule.RestrictionRace.ELYOS ? Race.ELYOS : Race.ASMODIANS;
		if (rule.getGlobalRuleWorlds() == null)
			return Race.PC_ALL;
		Race claimed = Race.PC_ALL;
		for (GlobalDropWorld world : rule.getGlobalRuleWorlds().getGlobalDropWorlds()) {
			Race side = switch (world.getWorldDropType()) {
				case ELYSEA -> Race.ELYOS;
				case ASMODAE -> Race.ASMODIANS;
				default -> Race.PC_ALL; // the abyss, the instances and Balaurea are nobody's home
			};
			if (side == Race.PC_ALL)
				return Race.PC_ALL; // one shared world is enough to make the whole rule shared
			claimed = claimed == Race.PC_ALL || claimed == side ? side : Race.PC_ALL;
		}
		return claimed;
	}

	/**
	 * The chests, which global rules deliberately skip.
	 * <p>
	 * Walked from the npc side because {@code CustomDrop} is keyed by npc and offers no way to iterate what it holds. Asking it once per npc template
	 * costs one map lookup each at startup and leaves upstream's file untouched, which is worth more here than the tidier loop.
	 */
	private static void addChestDrops(Map<Integer, Source> found) {
		for (NpcTemplate npc : DataManager.NPC_DATA.getNpcData()) {
			NpcDrop drop = DataManager.CUSTOM_NPC_DROP.getNpcDrop(npc.getTemplateId());
			if (drop == null || drop.getDropGroup() == null)
				continue;
			for (DropGroup group : drop.getDropGroup()) {
				if (group.getDrop() == null)
					continue;
				Source source = new Source(playerRaceOf(group.getRace()), 0);
				for (Drop item : group.getDrop())
					add(found, item.getItemId(), source);
			}
		}
	}

	/** @return The race when it is one a character can be, {@code PC_ALL} otherwise. Npc races and nulls claim nothing. */
	private static Race playerRaceOf(Race race) {
		return race != null && race.isAsmoOrEly() ? race : Race.PC_ALL;
	}

	/** Gear only. Every one of these tables is mostly consumables, quest tokens and crafting materials, and none of that is ever worn. */
	private static void add(Map<Integer, Source> found, int itemId, Source source) {
		ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if (template == null || !(template.isWeapon() || template.isArmor()) || wearsAnNpcModel(template))
			return;
		found.merge(template.getTemplateId(), source, Source::mergedWith);
	}

	/**
	 * Keeps npc costume and development leftovers out, which being listed somewhere does not.
	 * <p>
	 * This is the part the old mastery-and-restrictions test was right about, and dropping it wholesale let the costume back in by another door: a
	 * vendor really does stock "Asmodian NPC Common Chain Head", a level 1 piece that asks nothing of anybody, and with the head slot now being
	 * dressed a bot would put it on in preference to nothing. That is the village in guard uniform all over again.
	 * <p>
	 * The model name says it outright, and <b>only the model name</b>. Counted over the 29350 pieces of gear these four tables list: 874 carry an
	 * {@code npc} segment and 53 a {@code test} one. Two other markers looked like candidates and are deliberately left in — of the 132 pieces
	 * carrying {@code cash} and the 155 carrying {@code event}, most declare proper level restrictions, because {@code world_cash_*} is the level 65
	 * gear sold for real money and it is real gear with real stats. Excluding by marker without counting first would have thrown it away.
	 */
	private static boolean wearsAnNpcModel(ItemTemplate template) {
		if (template.getCName() == null)
			return false;
		for (String segment : template.getCName().split("_")) {
			if (segment.equalsIgnoreCase("npc") || segment.equalsIgnoreCase("test"))
				return true;
		}
		return false;
	}
}
