# Plan: what a player does with a bot

Status: milestones 0 to 3 built on 19 October (`BotRequests`, `BotTrades`, `//bot give`), not yet watched in game. Priority 5 of [roadmap.md](../roadmap.md): trading with one's companion, friends and duels. The list came
from the testers' "missing features in game"; cohorts have a plan of their own, [cohort-plan.md](cohort-plan.md).

## What the engine gives, and what a bot lacks

Every one of these goes through **one mechanism**: a question window. The engine puts a `RequestResponseHandler` on the asked player's
`ResponseRequester` under the window's message id (`STR_EXCHANGE_DO_YOU_ACCEPT_EXCHANGE`, `STR_BUDDYLIST_ADD_BUDDY_REQUEST`,
`STR_DUEL_DO_YOU_ACCEPT_REQUEST`, `STR_PARTY_DO_YOU_ACCEPT_INVITATION`, `STR_FORCE_DO_YOU_ACCEPT_INVITATION`) and sends a `SM_QUESTION_WINDOW` that
a client answers. A bot has no client: `PacketSendUtility.sendPacket` drops every packet to a player without a connection.

What already answers one: `BotGroupManager.acceptPendingInvite` (group and alliance, any inviter, on the decision tick), `BotEquipManager` (the
soul-binding question), `//bot duel` (any question id, by hand). The pattern works: `respond(id, 1)` on a tick, `false` when nothing was asked.

What is missing to answer **well**: the bot cannot tell **who** asks. `RequestResponseHandler.requester` is private. A trade from a stranger, a duel
in the middle of a fight, a friend request from the other faction (refused by the engine already) all look the same.

| Feature | Engine entry | Today |
|---|---|---|
| Trade | `CM_EXCHANGE_REQUEST` puts the question; `ExchangeService` then `lockExchange`, `confirmExchange`; `Exchange.isLocked()`, `isConfirmed()` | Never answered: the request waits until it is replaced |
| Friend | `CM_FRIEND_ADD` puts the question; accepting calls `SocialService.makeFriends`, which writes both lists to the database | Never answered |
| Duel | `DuelService.onDuelRequest` puts the question; a duel ends on `loseDuel` | `//bot duel` answers it; once hit, the bot fights back (`BotAttackManager` asks the engine whether a duellist is still a target) |

## Design

- **One reading of the question.** A small core patch: `RequestResponseHandler.getRequester()`, and `ResponseRequester.requesterOf(int messageId)`
  returning it without removing anything. The same kind of patch as `Creature.setAi()`: an accessor, no behaviour changed.
- **One decision, a pure function.** `BotRequests.answer(kind, situation)` returns accept, refuse or leave, from a record: who asks (the owner, a team
  mate, a stranger, a bot), whether the bot is fighting, dead, in an instance, its level against the asker's. Tested like the playbooks are.
- **One place that answers**, on the decision tick, as `acceptPendingInvite` does today, which it absorbs. A request left unanswered for
  `REQUEST_PATIENCE` (about 5 s, the time a person takes to read it) is refused rather than left to block the next one: a bot that never answers
  makes the asker's own window say "is being asked a question" to everybody after them.
- **A refusal is visible**: the engine already tells the asker (`STR_EXCHANGE_HE_REJECTED_EXCHANGE`, `TARGET_DENIED`, the duel's own message).

## Milestones, in verifiable order

### 0. Who asks (half a day)

The two accessors, `BotRequests` with its record and its test, the tick answering through it. Group and alliance invitations keep today's answer
(always yes). Verified by the test, then by `//bot debug` showing the asker and the answer for an invitation.

### 1. Duels (half a day)

Accept a duel from a player of the same faction, when the bot is alive, not fighting, not in an instance, and within 10 levels of the asker. Refuse
otherwise. Once accepted the bot attacks the asker at once rather than waiting to be hit. After a duel (won or lost) a bot refuses another from the same
player for a minute, so it cannot be farmed for the duel achievements. **Verified in game**: ask a field bot, see it accept and fight; ask one in a
fight, see the refusal.

### 2. Friends (an hour or two)

Accept every friend request the engine lets through (it already refuses the other faction, a blocked player, a full list). A bot shows online while
awake and offline while asleep in the pool, which is the truth. A bot never asks. **Verified in game**: add a bot, see it in the list, online; let the
director put it to sleep, see it go offline.

### 3. Trading with one's companion (two days)

Only with the bot's **owner** (`PlayerBotAI.isOwned`, the owner's id); anyone else is refused.

- **Giving to the companion.** The bot accepts, adds nothing, locks once its owner has locked, confirms once its owner has confirmed. What it receives
  goes to its bag. An owned bot never dresses from its bag ([bot-gear.md](../gear/bot-gear.md)), on purpose, so what it is given is marked as given and
  `BotEquipManager` may put it on if it is better and the class can wear it; nothing else in the bag changes status.
- **Taking from the companion.** A trade window has no way to say "give me that". The proposal: `//bot give <bot> <item name|all>` while the trade
  is open puts the named items (or everything that is not worn) on the bot's side, then it locks and confirms after its owner, as above.
- **Kinah** the same way: `//bot give <bot> kinah <amount>`.

**Decided** (19 October): only a companion trades, and only with its owner; a field bot or a recruit refuses every trade.

**Verified in game**: give the companion a weapon better than its own, see it wear it; take back two items and some kinah with `//bot give`.

## Open questions

None left: the one about who may trade was answered on 19 October (milestone 3).
