package com.aionemu.gameserver.model.team.common.events;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.TemporaryPlayerTeam;

/**
 * @author ATracer
 */
public abstract class ChangeLeaderEvent<T extends TemporaryPlayerTeam<?>> extends AbstractTeamPlayerEvent<T> {

	public ChangeLeaderEvent(T team, Player eventPlayer) {
		super(team, eventPlayer);
	}

	/**
	 * New leader either is null or should be online
	 */
	@Override
	public boolean checkCondition() {
		return eventPlayer == null || canLead(eventPlayer);
	}

	/**
	 * A bot is present enough to be rewarded and counted, but it has nobody to decide for: leading a group means answering invitations and setting
	 * loot rules, which it cannot do. So it is passed over, and a group of bots alone simply keeps the leader it has.
	 */
	private static boolean canLead(Player player) {
		return player.isOnline() && !player.isBot();
	}

	protected final void changeLeaderToNextAvailablePlayer() {
		team.applyOnMembers(member -> {
			if (canLead(member) && !member.equals(team.getLeader().getObject())) {
				changeLeaderTo(member);
				return false;
			}
			return true;
		});
	}

	protected abstract void changeLeaderTo(Player player);

}
