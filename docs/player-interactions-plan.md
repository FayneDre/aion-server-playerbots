# Plan: what a player does with a bot

Status: not started; analysed on 19 October. Priority 5 of [roadmap.md](roadmap.md): trading with one's companion, friends, duels, cohorts
(alliances), speech and yielding to players. The list came from the testers' "missing features in game".

## What the engine gives, and what a bot lacks

Every one of these but speech goes through **one mechanism**: a question window. The engine puts a `RequestResponseHandler` on the asked player's
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
| Cohort | `STR_FORCE_DO_YOU_ACCEPT_INVITATION` | Accepted. Not checked: a bot follows the **alliance's** leader (`leaderToFollow`), not its own group's player; the formation is laid out for every member of the alliance; `BotRecruiter` fills groups only |
| Speech | `CM_CHAT_MESSAGE_WHISPER` sends `SM_MESSAGE` to the receiver; public chat goes through the known list | A bot hears nothing and says nothing |
| Yielding | `BotTargetSelector.isTakenByAnotherPlayer` | Leaves a mob that **targets** another player; not one a player has targeted and is walking to, nor a spot a player is hunting |

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
  goes to its bag. An owned bot never dresses from its bag ([bot-gear.md](bot-gear.md)), on purpose, so what it is given is marked as given and
  `BotEquipManager` may put it on if it is better and the class can wear it; nothing else in the bag changes status.
- **Taking from the companion.** A trade window has no way to say "give me that". The proposal: `//bot give <bot> <item name|all>` while the trade
  is open puts the named items (or everything that is not worn) on the bot's side, then it locks and confirms after its owner, as above.
- **Kinah** the same way: `//bot give <bot> kinah <amount>`.

**Decision for the user**: whether a bot that is not owned (a field bot, a recruit) may trade at all. The plan says no.

**Verified in game**: give the companion a weapon better than its own, see it wear it; take back two items and some kinah with `//bot give`.

### 4. Yielding to players (a day)

- A mob a real player **targets** is taken, as one that targets them already is: `BotTargetSelector` asks the players around it.
- A bot does not pick a mob within 15 m of a real player who is hunting (in a fight or with a mob targeted in the last 30 s) unless it is grouped with
  them: the patch a person is working is theirs.
- Gathering nodes come with gathering, which is not started.

**Verified** by a measure: with a player hunting on a busy ground for 10 minutes, the mobs a bot took within 15 m of them, before and after (the
debug log names each pick).

### 5. Speech (two to three days)

- **Hearing**: one core hook, in `CM_CHAT_MESSAGE_WHISPER`, handing a whisper to a bot to `BotSpeech.heard` instead of a socket that is not there.
  Public chat is not heard: nobody expects a stranger in a crowd to answer.
- **Saying**: `SM_MESSAGE` from the bot to the players who see it (normal chat), or to one (whisper), or to its group. Lines in French, in a data file
  (`config/playerbot/speech.txt`), several per occasion, picked at random, never the same twice in a row.
- **Occasions**, each rate-limited per bot and per place, so that 40 bots in a capital are a murmur and not a flood: joining a group ("salut"), a
  resurrection or a heal received in a group ("merci"), a duel's end, a trade done, a whisper (one short answer: busy, hunting, the name of its region),
  rarely a call for a group in region chat when a bot hunts near elites it cannot fight.
- **Not** a conversation: no language model, no memory of what was said.

**Verified in game**: whisper a bot and get an answer; group with three and hear one of them; count the lines in a capital over 10 minutes (a ceiling
to be set, of the order of one a minute for the whole crowd).

### 6. Cohorts (a day, after a measure)

Measure first, with `//bot debug` and a player leading an alliance of two groups of bots: whom each bot follows, where it stands, whom the healers
heal. Expected changes: follow the player of one's own group when the alliance's leader is in another, and lay the formation out per group. Then
`BotRecruiter` filling an alliance offer, if the Find Group window gives one for the alliance instances.

## Open questions

1. Whether a bot that is not owned may trade (milestone 3).
2. The French lines are the testers' to write or to review: they read them, the code only picks one.
3. Cohorts: which content the testers want them for (an alliance instance, a fortress) decides what milestone 6 checks.
