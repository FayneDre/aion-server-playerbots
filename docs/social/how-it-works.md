# Social: how bots deal with players today

Status: reference, written on 28 October 2026 from the code and the plans in [history/](history/) and [plans/](plans/). Classes in `social/`
unless named otherwise.

## Groups

- **Invited like anyone**: a bot answers the question window it cannot see on its next decision tick, from anyone (`BotRequests`). One invited from
  over 50 m is brought to the player.
- **Following is the anchor**: a grouped bot points its camp anchor at its own slot round the leader every tick (`BotGroupManager.formationSpot`:
  world directions, ordered by object id, a little off an exact circle), so every rule it has (camp, return, leash) travels with the group. It has
  no initiative of its own: it fights what the group fights ([../combat/how-it-works.md](../combat/how-it-works.md)), runs no errands, and is never
  taken out of the world while grouped.
- **Where the player goes**: into an instance, a bot is registered with it and put beside its player (`BotInstances`), out again with them, put at
  the exit when left alone inside; a player who takes a flight transporter, a windstream, a teleporter or a scroll is joined on arrival (`BotTransports`), and one cut off by a lift or a door is put beside them after about 13 s by the rescue;
  one who glides is copied (`LeaderGlides`).
- **The leader is never a bot**: leading means answering invitations and setting loot; a group with no player left is left. Marks set by the leader
  are read by the playbooks (`BotMarks`).
- **The Find Group window** (`BotRecruiter` at the top of the module, `RecruitPlan`): every 5 s the Instance Groups offers are read and filled with a tank, a
  healer and a support before damage, at the player's level give or take two (four if nobody of the role is that close), woken beside the player
  from the pool or taken from the free bots; a recruit no longer in the group, or never in it after 30 s, goes home. Needs
  `gameserver.instance_group.form_anywhere = true`.
- **Squads** (`BotSquads`, `//bot squad`): groups of bots alone, for tests; their leader pulls and waits for them.

## Requests

`BotRequests.answer(kind, situation)` is one pure decision for every question a player can ask: accept, refuse or leave, from who asks (the owner,
a team mate, a stranger, a bot), whether the bot is fighting, dead or in an instance, its level against the asker's. A question left unanswered is
refused after about 5 s, so it never blocks the next asker. Covered: group invitations, **duels**, **friend requests**, **trades with one's
companion** (`economy/BotTrades`, `//bot give`); the last three are built and not yet watched in game
([plans/player-interactions-plan.md](plans/player-interactions-plan.md)).

## Chat

- **Shouts** in the capitals (`BotSpeech`, [../capitals/how-it-works.md](../capitals/how-it-works.md)).
- **The Region, Trade and Find Group channels** (`BotChat`, `BotChatLines`, [history/chat-channels-plan.md](history/chat-channels-plan.md)): the
  game server hands a line to the chat server (`CM_BOT_MESSAGE`, a small additive packet on both sides), which finds the channel of the bot's race,
  builds a sender with no connection and broadcasts it. The speaker is always a real bot of the faction, awake, so its name can be whispered to,
  inspected and invited. One faction never reads the other's lines.
- **What is said is true**: a Trade line names a piece on the speaker's own shelf at its store's price; a Find Group line, the speaker's class,
  level, role and an instance its level admits; a Region line, the map's places and creatures, the time of day, small talk, sometimes a question
  another bot answers 4 to 15 s later. French, built from opening, content and closing lists, with the abbreviations players use.
- **Pace**: a line every 20 to 40 s per map and faction in each channel (Region only on a map a real player of the faction is on); never the same
  bot twice in 20 minutes, nor the same content in 30. `//bot speech off` silences everything.
- Needs `gameserver.chatserver.enable = true` and its password in `mygs.properties` (upstream ships it off).

## Limits

- No cohorts ([plans/cohort-plan.md](plans/cohort-plan.md)), no legions yet ([../roadmap.md](../roadmap.md)).
- A bot never starts a request of its own (no invitation, duel or trade offered to a player); whispers go unanswered.
