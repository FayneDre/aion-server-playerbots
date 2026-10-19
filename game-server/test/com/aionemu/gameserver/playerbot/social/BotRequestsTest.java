package com.aionemu.gameserver.playerbot.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.playerbot.social.BotRequests.Asker;
import com.aionemu.gameserver.playerbot.social.BotRequests.Kind;
import com.aionemu.gameserver.playerbot.social.BotRequests.Situation;

/**
 * Who a bot says yes to, question by question.
 */
class BotRequestsTest {

	private static Situation ask(Kind kind, Asker asker) {
		return new Situation(kind, asker, false, false, false, 0, false);
	}

	@Test
	void anInvitationIsTakenFromAnybody() {
		for (Asker asker : Asker.values()) {
			assertTrue(BotRequests.accepts(ask(Kind.GROUP, asker)));
			assertTrue(BotRequests.accepts(ask(Kind.ALLIANCE, asker)));
		}
	}

	@Test
	void aFriendRequestIsTakenFromAPlayerOnly() {
		assertTrue(BotRequests.accepts(ask(Kind.FRIEND, Asker.PLAYER)));
		assertTrue(BotRequests.accepts(ask(Kind.FRIEND, Asker.OWNER)));
		assertFalse(BotRequests.accepts(ask(Kind.FRIEND, Asker.BOT)));
	}

	@Test
	void aDuelIsTakenWhenTheBotIsFreeAndTheFightIsFair() {
		assertTrue(BotRequests.accepts(ask(Kind.DUEL, Asker.PLAYER)));
		assertTrue(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, false, BotRequests.DUEL_LEVEL_GAP, false)));
		assertTrue(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, false, -BotRequests.DUEL_LEVEL_GAP, false)));
		assertFalse(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, false, BotRequests.DUEL_LEVEL_GAP + 1, false)), "too strong");
		assertFalse(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, false, -BotRequests.DUEL_LEVEL_GAP - 1, false)), "too weak");
		assertFalse(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, true, false, 0, false)), "in a fight or dead");
		assertFalse(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, true, 0, false)), "in an instance");
		assertFalse(BotRequests.accepts(new Situation(Kind.DUEL, Asker.PLAYER, false, false, false, 0, true)), "duelled this player lately");
		assertFalse(BotRequests.accepts(ask(Kind.DUEL, Asker.BOT)));
	}

	@Test
	void onlyACompanionTradesAndOnlyWithItsOwner() {
		assertTrue(BotRequests.accepts(new Situation(Kind.TRADE, Asker.OWNER, true, false, false, 0, false)));
		assertFalse(BotRequests.accepts(new Situation(Kind.TRADE, Asker.PLAYER, true, false, false, 0, false)), "a stranger, to a companion");
		assertFalse(BotRequests.accepts(new Situation(Kind.TRADE, Asker.PLAYER, false, false, false, 0, false)), "an inhabitant trades with nobody");
		assertFalse(BotRequests.accepts(new Situation(Kind.TRADE, Asker.OWNER, true, true, false, 0, false)), "not in a fight");
	}
}
