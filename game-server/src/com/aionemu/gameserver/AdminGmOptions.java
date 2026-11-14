package com.aionemu.gameserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.administration.AdminConfig;
import com.aionemu.gameserver.model.gameobjects.player.CustomPlayerState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureVisualState;
import com.aionemu.gameserver.utils.chathandlers.ChatProcessor;
import com.aionemu.gameserver.world.World;

/**
 * The staff modes the launcher offers a staff member's character in game, each switched by the game master command a player would type, so the command's
 * own rules apply: its access level, its messages, what it sends. They last until the character leaves the game, as the commands' do; what a staff member
 * has at each login is {@code gameserver.administration.login.execute_commands}.
 */
final class AdminGmOptions {

	private static final Logger log = LoggerFactory.getLogger(AdminGmOptions.class);

	/** A mode: its name on the channel, how to read it, and the command lines that turn it on and off. */
	enum Option {
		INVUL("invul", Player::isInvulnerable, "//invul", "//invul"),
		INVIS("invis", p -> p.isInVisualState(CreatureVisualState.GM_INVISIBLE), "//invis", "//invis"),
		SEE("see", p -> p.getSeeState() >= 2, "//see", "//see"),
		NEUTRAL("neutral", p -> p.isInCustomState(CustomPlayerState.NEUTRAL_TO_EVERYONE), "//enemy none", "//enemy cancel"),
		NOCOOLDOWN("nocooldown", p -> p.isInCustomState(CustomPlayerState.NO_SKILL_COOLDOWN_MODE), "remove_skill_delay_all 0", "remove_skill_delay_all 1"),
		FREEFLY("freefly", p -> p.isInCustomState(CustomPlayerState.FREE_FLY_MODE), "freefly 1", "freefly 0");

		private final String id;
		private final Predicate<Player> isOn;
		private final String on, off;

		Option(String id, Predicate<Player> isOn, String on, String off) {
			this.id = id;
			this.isOn = isOn;
			this.on = on;
			this.off = off;
		}

		static Optional<Option> named(String id) {
			return Arrays.stream(values()).filter(o -> o.id.equals(id)).findFirst();
		}
	}

	private AdminGmOptions() {
	}

	/**
	 * One {@code key value} line each: {@code access} (the account's level), {@code tag} (1 when staff names carry their tag), then each mode, 1 or 0.
	 */
	static String state(String[] words) {
		Player player = staffInGame(words[1]);
		if (player == null)
			return "error not in game";
		List<String> lines = new ArrayList<>(List.of("ok", "access " + player.getAccount().getAccessLevel(), "tag " + (AdminConfig.CUSTOMTAG_ENABLE ? 1 : 0)));
		for (Option option : Option.values())
			lines.add(option.id + " " + (option.isOn.test(player) ? 1 : 0));
		return String.join("\n", lines);
	}

	/** {@code gm-set <character id> <mode> <0|1>}: the mode as asked, or an error when the command left it as it was (its access level refused it). */
	static String set(String[] words) {
		Optional<Option> option = Option.named(words[2]);
		if (option.isEmpty())
			return "error unknown mode " + words[2];
		if (!words[3].equals("0") && !words[3].equals("1"))
			return "error 0 or 1 expected";
		Player player = staffInGame(words[1]);
		if (player == null)
			return "error not in game";
		boolean wanted = words[3].equals("1");
		if (option.get().isOn.test(player) != wanted) {
			String line = wanted ? option.get().on : option.get().off;
			if (line.startsWith("//"))
				ChatProcessor.getInstance().handleChatCommand(player, line);
			else
				ChatProcessor.getInstance().handleConsoleCommand(player, line);
		}
		if (option.get().isOn.test(player) != wanted)
			return "error not allowed";
		log.info("Admin channel: {} {} for {}", option.get().id, wanted ? "on" : "off", player.getName());
		return "ok";
	}

	private static Player staffInGame(String characterId) {
		int objectId;
		try {
			objectId = Integer.parseInt(characterId);
		} catch (NumberFormatException e) {
			return null;
		}
		Player player = World.getInstance().getPlayer(objectId);
		return player == null || player.isBot() || !player.isOnline() || !player.isStaff() ? null : player;
	}
}
