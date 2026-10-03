package com.aionemu.gameserver.playerbot.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.playerbot.combat.BotSkillManager;
import com.aionemu.gameserver.playerbot.social.BotRole;
import com.aionemu.gameserver.skillengine.effect.AbstractHealEffect;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.EffectType;
import com.aionemu.gameserver.skillengine.model.SkillLearnTemplate;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.services.item.ItemFactory;

/**
 * Puts stigma stones in a bot's sockets.
 * <p>
 * Not an improvement but the difference between a character and a cripple: past level twenty a build is its stigmas, and a bot without them fights at
 * a fraction of what its level says. Every measurement of bot combat above that level was of a character missing half of itself — which is why the
 * roadmap called this the one blocker left, and why the field reports of bots being torn apart by things their own level were never going to be
 * explained by gear alone.
 * <p>
 * Nothing here reimplements the rules. The sockets, their count, the kinah and the skills they grant all belong to {@code StigmaService}, which the
 * ordinary {@code Equipment.equipItem} path already calls; this only decides <b>which</b> stones and hands them over. The one thing it must get right
 * by itself is the socket, because {@code ItemGroup.STIGMA} names all six at once and the engine refuses a mask naming more than two.
 */
public class BotStigmaFitter {

	/** Lowest level at which any stigma exists, and where the skill tree search starts. */
	private static final int FIRST_STIGMA_LEVEL = 20;
	/** Sockets of either kind. Asking for more is pointless: three is the most the engine ever opens. */
	private static final int MAX_SOCKETS = 3;
	/**
	 * How many refusals in a row mean there is no point trying more. A refusal is usually "the sockets are full", which the engine answers without
	 * saying so, but it is also "not enough kinah" and "this stone is not for you". Stopping on the first would give up on a bot whose best stone
	 * happens to be unusable; not stopping at all would create a hundred items to throw away.
	 */
	private static final int REFUSALS_BEFORE_GIVING_UP = 3;

	/** Which stone grants a given skill. One pass over the item table, which holds 394 stigmas, and it never changes afterwards. */
	private static volatile Map<Integer, ItemTemplate> stoneBySkill;

	private BotStigmaFitter() {
	}

	/**
	 * Fills what sockets the bot has open.
	 * <p>
	 * The count is never asked for. {@code StigmaService.getPossibleStigmaCount} knows it and is private, and copying its table here would be a
	 * second set of rules to keep in step — so stones are offered until the engine stops taking them, which is the same answer arrived at honestly.
	 *
	 * @return How many stones went in.
	 */
	public static int fit(Player bot) {
		if (bot.getLevel() < FIRST_STIGMA_LEVEL)
			return 0;
		int worn = socket(bot, candidates(bot, false), ItemSlot.STIGMA1, ItemSlot.STIGMA2, ItemSlot.STIGMA3);
		worn += socket(bot, candidates(bot, true), ItemSlot.ADV_STIGMA1, ItemSlot.ADV_STIGMA2, ItemSlot.ADV_STIGMA3);
		if (worn > 0)
			LoggerFactory.getLogger(BotStigmaFitter.class).debug("Bot {} sockets {} stigma(s)", bot.getName(), worn);
		return worn;
	}

	/** Offers stones to one family of sockets until they stop being taken. */
	private static int socket(Player bot, List<ItemTemplate> stones, ItemSlot... slots) {
		int worn = 0, refused = 0;
		for (ItemTemplate stone : stones) {
			if (worn >= MAX_SOCKETS || refused >= REFUSALS_BEFORE_GIVING_UP)
				break;
			// One refusal after something has already gone in means the sockets are full, and nothing else does: the stones are all of the same tier
			// and the same price, so what changed between the two attempts can only be the room. Without this a character of 20 to 29, which has one
			// socket, filled it and then offered three more stones it could never wear — on every spawn, for the rest of its life.
			if (worn > 0 && refused > 0)
				break;
			long slot = freeSlot(bot, slots);
			if (slot == 0)
				break; // every socket of this kind is full, whatever the engine would have said
			if (wear(bot, stone, slot))
				worn++;
			else
				refused++;
		}
		return worn;
	}

	/**
	 * Creates one stone and offers it to a socket.
	 * <p>
	 * No appearance packet, unlike the gear the outfitter puts on: a stigma is not worn anywhere a client can see. A refused stone is taken back out
	 * of the bag, for the same reason a refused weapon is — left there it is dead weight for the rest of the character's life.
	 */
	private static boolean wear(Player bot, ItemTemplate stone, long slot) {
		Item item = ItemFactory.newItem(stone.getTemplateId());
		item.setSoulBound(true);
		if (bot.getInventory().add(item) == null)
			return false;
		if (bot.getEquipment().equipItem(item.getObjectId(), slot) != null)
			return true;
		BotEquipManager.discard(bot, item);
		return false;
	}

	private static long freeSlot(Player bot, ItemSlot... slots) {
		for (ItemSlot slot : slots) {
			if (!bot.getEquipment().isSlotEquipped(slot.getSlotIdMask()))
				return slot.getSlotIdMask();
		}
		return 0;
	}

	/**
	 * The stones this character could wear, best first.
	 * <p>
	 * What it may wear is the skill tree's answer and not ours: the stone carries no class restriction at all — every stigma states a required level
	 * of twenty for all seventeen classes — so the item table cannot be filtered and the tree has to be read instead. Which tier a stigma belongs to
	 * is stated there too, and nowhere else.
	 */
	private static List<ItemTemplate> candidates(Player bot, boolean advanced) {
		Set<Integer> seen = new HashSet<>();
		List<ItemTemplate> found = new ArrayList<>();
		for (int level = FIRST_STIGMA_LEVEL; level <= bot.getLevel(); level++) {
			for (SkillLearnTemplate learn : DataManager.SKILL_TREE_DATA.getTemplatesFor(bot.getPlayerClass(), level, bot.getRace())) {
				// a linked stigma is granted by the ones around it rather than socketed, so there is no stone to look for
				if (!learn.isStigma() || learn.isLinkedStigma() || learn.isAdvancedStigma() != advanced)
					continue;
				ItemTemplate stone = stoneFor(learn.getSkillId());
				if (stone != null && seen.add(stone.getTemplateId()))
					found.add(stone);
			}
		}
		found.sort(worthMostTo(bot));
		return found;
	}

	/**
	 * How a character of this role ranks its stigmas.
	 * <p>
	 * A tank's sockets are worth more spent on holding the mob than on damage it does not need, and a healer's on keeping people alive. For everyone
	 * else, and as the tie-break for all of them, the stated power that already orders every other skill this module casts — so no new judgement is
	 * invented here, only an order of preference over one that exists.
	 */
	private static Comparator<ItemTemplate> worthMostTo(Player bot) {
		BotRole role = BotRole.of(bot);
		return Comparator.comparingInt((ItemTemplate stone) -> switch (role) {
			case TANK -> grants(stone, EffectType.BOOSTHATE) ? 0 : 1;
			case HEALER -> heals(stone) ? 0 : 1;
			default -> 0;
		}).thenComparing(Comparator.comparingInt(BotStigmaFitter::power).reversed());
	}

	/** @return The strongest thing the data says any of this stone's skills does, which is how every other skill in this module is ranked. */
	private static int power(ItemTemplate stone) {
		int best = 0;
		for (SkillTemplate skill : grantedSkills(stone))
			best = Math.max(best, BotSkillManager.statedPower(skill));
		return best;
	}

	private static boolean grants(ItemTemplate stone, EffectType effect) {
		for (SkillTemplate skill : grantedSkills(stone)) {
			if (skill.hasAnyEffect(effect))
				return true;
		}
		return false;
	}

	private static boolean heals(ItemTemplate stone) {
		for (SkillTemplate skill : grantedSkills(stone)) {
			if (skill.getEffects() == null)
				continue;
			for (EffectTemplate effect : skill.getEffects().getEffects()) {
				if (effect instanceof AbstractHealEffect)
					return true;
			}
		}
		return false;
	}

	private static List<SkillTemplate> grantedSkills(ItemTemplate stone) {
		List<SkillTemplate> skills = new ArrayList<>();
		for (int group = 1; group <= stone.getStigma().getGainSkillGroups().length; group++) {
			List<SkillTemplate> ofGroup = stone.getStigma().getGainSkillsByGroup(group);
			if (ofGroup != null)
				skills.addAll(ofGroup);
		}
		return skills;
	}

	/** @return The stone that grants this skill, or null when none does. */
	private static ItemTemplate stoneFor(int skillId) {
		Map<Integer, ItemTemplate> index = stoneBySkill;
		if (index == null) {
			synchronized (BotStigmaFitter.class) {
				if (stoneBySkill == null)
					stoneBySkill = buildIndex();
				index = stoneBySkill;
			}
		}
		return index.get(skillId);
	}

	/**
	 * Reads the whole item table once and remembers which stone grants what.
	 * <p>
	 * The link is the skill's group rather than its id: a stone names {@code gain_skill_group1="FI_LOCKDOWNIMPACT"} and the skill template names the
	 * same group, so one lookup joins them. Going the other way — item to skill — is the only direction the data supports.
	 * <p>
	 * Every stone exists twice, as itself and as an inert copy — "Lockdown" and "Lockdown (Inert)", granting the same group — so one of the two has
	 * to be chosen. {@code chargeable} is what separates them, and in the direction opposite to what the word suggests: the ordinary stone is
	 * {@code chargeable="true"}, meaning it can be enchanted with a duplicate of itself, and the inert copy is false. The engine settles it beyond
	 * doubt in {@code StigmaService.addLinkedStigmaSkills}, which refuses the linked stigma unless all six sockets hold chargeable stones.
	 */
	private static Map<Integer, ItemTemplate> buildIndex() {
		Map<Integer, ItemTemplate> index = new HashMap<>();
		for (ItemTemplate template : DataManager.ITEM_DATA.getItemTemplates()) {
			if (!template.isStigma() || !template.getStigma().isChargeable())
				continue;
			for (int group = 1; group <= template.getStigma().getGainSkillGroups().length; group++) {
				List<SkillTemplate> skills = template.getStigma().getGainSkillsByGroup(group);
				if (skills == null)
					continue;
				for (SkillTemplate skill : skills)
					index.putIfAbsent(skill.getSkillId(), template);
			}
		}
		return index;
	}
}
