package com.aionemu.gameserver.playerbot.addon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ATTACK_STATUS.TYPE;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/**
 * Counts the damage and the healing each player gives, for the combat meter addon: the client's own events cannot say who healed, the server
 * can.
 * <p>
 * Only players are counted, and a bot only while it is in a team with a player, so the bots of an empty world cost one test per hit. A summon's or
 * a servant's numbers go to its master. Damage is what the target lost, healing what it gained, and the rest of a heal (the part above full health) is
 * overhealing. Per second figures are over the time in combat: an action less than {@link #COMBAT_GAP_MILLIS} after the previous one adds the time
 * between them, a later one only a second, so a pause between two fights does not lower them.
 */
public final class CombatMeter {

	private static final Logger log = LoggerFactory.getLogger(CombatMeter.class);

	static final long COMBAT_GAP_MILLIS = 5000;
	private static final long FIRST_SECOND_MILLIS = 1000;
	private static final int PRUNE_ABOVE = 500;
	private static final long PRUNE_IDLE_MILLIS = 30 * 60 * 1000;

	private static final Map<Integer, Tally> tallies = new ConcurrentHashMap<>();

	private CombatMeter() {
	}

	/** Called by the health's own code in the middle of a hit, which a fault of the meter must never stop: a fault is logged and the hit goes on. */
	public static void onDamage(Creature target, Creature attacker, int skillId, int amount, TYPE type) {
		if (amount <= 0 || type == TYPE.USED_HP)
			return;
		try {
			Player source = countedSource(attacker);
			if (source != null && source != target)
				tallyOf(source).add(skillId, amount, 0, 0);
		} catch (RuntimeException e) {
			log.error("Combat meter: damage of skill {} by {} not counted", skillId, attacker, e);
		}
	}

	/** @param amount the health the target gained; {@code wanted} what the heal would have given it without its maximum */
	public static void onHeal(Creature target, Creature effector, int skillId, int amount, int wanted) {
		if (skillId == 0 || wanted <= 0)
			return;
		try {
			Player source = countedSource(effector);
			if (source != null)
				tallyOf(source).add(skillId, 0, amount, Math.max(0, wanted - amount));
		} catch (RuntimeException e) {
			log.error("Combat meter: healing of skill {} by {} not counted", skillId, effector, e);
		}
	}

	/** The members whose numbers a viewer sees: the online members of its team, or itself alone. */
	static List<Player> membersSeenBy(Player viewer) {
		TemporaryPlayerTeam<?> team = viewer.getCurrentTeam();
		return team == null ? List.of(viewer) : team.getOnlineMembers();
	}

	/** Starts every member the viewer sees over from zero: the meter is the team's, so the reset is too. */
	static void reset(Player viewer) {
		for (Player member : membersSeenBy(viewer))
			tallies.remove(member.getObjectId());
	}

	/** The numbers of the members a viewer sees, as the page reads them. */
	static String json(Player viewer) {
		StringBuilder json = new StringBuilder("{\"viewer\":").append(quote(viewer.getName())).append(",\"members\":[");
		boolean first = true;
		for (Player member : membersSeenBy(viewer)) {
			if (!first)
				json.append(',');
			first = false;
			Tally tally = tallies.get(member.getObjectId());
			json.append(tally == null ? Tally.empty(member.getName()) : tally.json(member.getName()));
		}
		return json.append("]}").toString();
	}

	private static Player countedSource(Creature creature) {
		if (creature == null || !(creature.getMaster() instanceof Player player))
			return null;
		if (!player.isBot())
			return player;
		TemporaryPlayerTeam<?> team = player.getCurrentTeam();
		if (team != null)
			for (Player member : team.getOnlineMembers())
				if (!member.isBot())
					return player;
		return null;
	}

	private static Tally tallyOf(Player player) {
		Tally tally = tallies.get(player.getObjectId());
		if (tally != null)
			return tally;
		if (tallies.size() > PRUNE_ABOVE) {
			long idleSince = System.currentTimeMillis() - PRUNE_IDLE_MILLIS;
			tallies.values().removeIf(old -> old.lastMillis() < idleSince);
		}
		return tallies.computeIfAbsent(player.getObjectId(), id -> new Tally());
	}

	static String quote(String text) {
		StringBuilder quoted = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\')
				quoted.append('\\').append(c);
			else if (c < 0x20)
				quoted.append(String.format("\\u%04x", (int) c));
			else
				quoted.append(c);
		}
		return quoted.append('"').toString();
	}

	/** One player's numbers since its team's last reset, whole and by skill. */
	private static final class Tally {

		private final Map<Integer, long[]> bySkill = new HashMap<>(); // damage, healing, overhealing, actions
		private long damage, healing, overhealing, combatMillis, lastMillis;

		synchronized void add(int skillId, int damage, int healing, int overhealing) {
			long now = System.currentTimeMillis();
			long sinceLast = now - lastMillis;
			combatMillis += lastMillis != 0 && sinceLast < COMBAT_GAP_MILLIS ? sinceLast : FIRST_SECOND_MILLIS;
			lastMillis = now;
			this.damage += damage;
			this.healing += healing;
			this.overhealing += overhealing;
			long[] skill = bySkill.computeIfAbsent(skillId, id -> new long[4]);
			skill[0] += damage;
			skill[1] += healing;
			skill[2] += overhealing;
			skill[3]++;
		}

		synchronized long lastMillis() {
			return lastMillis;
		}

		synchronized String json(String name) {
			List<Map.Entry<Integer, long[]>> skills = new ArrayList<>(bySkill.entrySet());
			skills.sort(Comparator.comparingLong((Map.Entry<Integer, long[]> e) -> e.getValue()[0] + e.getValue()[1]).reversed());
			StringBuilder json = new StringBuilder();
			json.append("{\"name\":").append(quote(name)).append(",\"damage\":").append(damage).append(",\"healing\":").append(healing)
				.append(",\"overhealing\":").append(overhealing).append(",\"seconds\":").append(combatMillis / 1000.0).append(",\"skills\":[");
			for (int i = 0; i < skills.size(); i++) {
				long[] skill = skills.get(i).getValue();
				json.append(i == 0 ? "" : ",").append("{\"name\":").append(quote(skillName(skills.get(i).getKey()))).append(",\"damage\":").append(skill[0])
					.append(",\"healing\":").append(skill[1]).append(",\"overhealing\":").append(skill[2]).append(",\"actions\":").append(skill[3]).append('}');
			}
			return json.append("]}").toString();
		}

		static String empty(String name) {
			return "{\"name\":" + quote(name) + ",\"damage\":0,\"healing\":0,\"overhealing\":0,\"seconds\":0,\"skills\":[]}";
		}

		private static String skillName(int skillId) {
			if (skillId == 0)
				return "Auto-attack";
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(skillId);
			return template == null || template.getName() == null ? "Skill " + skillId : template.getName();
		}
	}
}
