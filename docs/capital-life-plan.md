# Capital life: visitors, speech, ambience

**Status: in progress.** Visitors (part 1) built on 23 October, deployed and not yet watched in game: `BotVisitors`, the column
`visiting_from` (added by `SchemaUpdater` on start), `PlayerBotService.wakeVisitor`, `PlayerBotEnterWorldService.returnFromVisit`, and in `BotDay`
the place in town, the way out and no store. Speech and ambience not started. Planned for milestones 3 to 5 of
[living-capitals-plan.md](living-capitals-plan.md); the user's answers are at the end.

## 1. Visitors (milestone 3)

**Why.** Capital residents are made at the level of the map's monsters: Pandaemonium holds 18 bots of 50 and over among 135, 5 from 30 to 44, and
a tester saw no high levels in town. More than 270 bots of 50+ live in the Abyss, Inggison and Gelkmaros, and never come.

**What the engine already gives.** `PlayerBotService.wakeBeside` brings a sleeper into the world on another map than its own; `BotRecruiter`
keeps a recruit's home map in memory and teleports it home when released; `BotDay.isAwayFromHome` already stops a recruit's home being
rewritten where it stands.

**The trap.** The director files every resident under the map its character is saved on (`BotRoster.Resident.worldId`, the `players` row). A
visitor saved in the capital by the 5 minute sweep, or by a crash, would be read as a capital resident whose home is on no place of the capital,
and `evictFromNowhere` would rehome it there. Recruits never sleep away from home; visitors will.

**Steps.**

1. **Where a visitor comes from, kept in the database.** A column `visiting_from` in `playerbot_characters` (0 when at home), written when the
   visitor arrives and cleared when it is back. The roster files a visitor under that map; `PlayerBotEnterWorldService` starts a visitor saved
   in the capital at its home, as it does a resident that strayed. A migration script, like the one that added `owner_id`.
2. **`BotVisitors`**, reviewed with the director every 30 seconds, for Sanctum and Pandaemonium: while a real player is in the capital and fewer
   than 15 are visiting, wake one sleeper of the faction at the capital's arrival point (its teleporter's landing, where players appear).
   The level is drawn where the town is thinnest: the capital's residents and visitors counted in 5 level bands from 10 to the top of the
   pool, the emptiest band first.
3. **The visit.** The town routine as residents live it (`TownRoutine`: sit, an errand, a visit to a quarter), for 20 to 40 minutes. A visitor
   keeps no store (`BotDay.isMerchantHere`), is not rehomed (`isAwayFromHome`), and is not counted by the director in the capital.
4. **The way home.** It walks to the capital's teleporter and is teleported to its home map at its home (`TeleportService`, as a recruit), in
   sight of whoever watches, as a player leaves. Back home, the director counts it again and puts it to sleep if its region is full.
5. **Measure.** Visitors by band and by capital in the review's log line; `//bot pool` shows them. In game: 50+ seen in both capitals, nobody's
   home moved (`playerbot_characters.home_*` unchanged after a day).

**Cost.** 15 more bots awake per capital: memory (about 800 awake fill the 2560 MB heap today) and the client's ceiling in a crowd (40 fps with
about 40 characters in sight, measured 20 October).

### Watched on 23 October, and what is open

- Fifteen arrive within 15 to 20 s of a player entering (loading is one character at a time on the lifecycle lane), level 55 to 65 for the most part.
- Fixed the same evening: the director slept visitors seconds after they came (`sleep` now refuses them); they arrived at half health and sat at
  the teleporter (now rested); a spell at home stood them at the teleporter (now a visit); all fifteen set off to sell at once and waited minutes
  for routes (visitors no longer sell); they went to the one quarter within 250 m of the Vifrost Bridge (now anywhere in town) and set off as a
  convoy (now 15 to 150 s looking round first).
- **Found with `Visit trace:` and `//bot debug all`: visitors asked for their routes and waited over four minutes** ("one is on its way"). A
  long route goes to the four route threads, and a bot still waiting after `PLAN_PATIENCE` (5 s) asked the same journey again, every 5 s:
  once the threads fell behind, the queue grew faster than they emptied it, for every bot on every map. Now a journey never asks again while
  its search is queued or running (`BotMoveController.planInFlight`). The `Visit trace:` lines were removed once it was.
- Measured 10 minutes after that fix, over all bots: routes found per 10 minutes 3500-4700 -> 8233, refused 63-88 -> 277, shops unreachable
  15-40 -> 85, town spells refused 21-25 -> 70, rescues 1-4 -> 22. Bots that stood "on their way" for good behind duplicate requests now get
  their answer, and the refusals they hid are counted and rescued. **Next: the destinations that cannot be reached** (sell trips first, then
  errands), with the reachable-floor rule visits have. Visitors walk since the fix: 32 routes, 28 arrivals in their first minutes.
- Navigation in towns, measured the same evening over all ~930 bots, 10 minutes after startup: 3500 to 4700 routes found, 63 to 88 refused
  (2 %), 106 to 124 searches over a second (capitals and 220050000, 210060000, 210070000, 220020000), 15 to 40 shops unreachable, 1 to 4
  rescues. The first 8 minutes after a restart are three times that. Leads, in order: spread the start of the bots over a few minutes; give
  sell trips and errands the floor-that-can-be-reached rule visits have (`BotPlaces.visitSpotOf`); have slow searches give up sooner.
- Town places are visited at the lowest floor a walker reaches (`visitSpotOf`): a place's centre is a townsperson's height, sometimes a roof.
  Errands look up to 12 m round a keeper when 6 m is crowded: Pandaemonium's brokers and warehouses stand in its markets.

## 2. Speech (milestone 4)

**The obstacle.** The Region, Trade and Find Group channels are the chat server's (`chat-server`, `ChannelType`): a client joins them itself, and
a bot has no chat server session. What the game server offers a bot is **say** (white) and **shout** (orange), heard by the players whose client
knows the bot, about 95 m round it.

**Steps.**

1. **Store adverts by shout.** A keeper at its stall shouts its store: "Vends équipement templier 30-35, venez voir ma boutique !", from a few
   templates per theme, in French. One advert every 3 to 5 minutes per market, never the same keeper twice in 30 minutes, and none while no
   player is in the capital.
2. **Calls for a group**, by shout near the obelisk: a resident of the right level announces what it would join ("Templier 45 cherche groupe
   pour Theobomos Lab"), and accepts a player's invitation as bots already do (`BotRequests`). Only what a bot can honour.
3. **A cap per capital** on every line, and a `//bot` switch to silence speech for a session.

## 3. Ambience (milestone 5)

**What the engine gives.** An emote is `SM_EMOTION` broadcast round the character, which a bot can send as `CM_EMOTION` does for a client;
duels go through `DuelService` (`onDuelRequest`, `confirmDuelWith`), which bots already answer when a player asks.

**Steps, in this order.**

1. **Groups of two to four.** A new town spell, `GATHER`: residents whose spell falls together meet at a square, stand in a circle facing its
   middle and play talking emotes every 10 to 30 seconds. The emote ids are to be read from the client data first.
2. **A wave or a bow.** A player stopping within 5 m in front of an idle resident gets one, one time in five, once per player per 10 minutes.
3. **Duels on a square.** Two residents of close level duel, at most one duel per capital every 15 minutes, three to five onlookers in a ring.
   Bot against bot is new: both sides fight a player that is a bot.
4. **Pets**, after a look at the data: which toy pets are obtainable, and what summoning one needs (a pet list in the database). Possibly
   left for later.

## Decisions (23 October)

1. **Speech by shout and say only**, heard round the market: the chat server's channels are not wanted.
2. **Visitors only while a real player is in the capital**, 15 at a time, **all at once**: a player often only passes through, and one visitor
   every half minute filled the town after they had left. Looked at every 5 seconds; with nobody there for 5 minutes, the visits end.
3. **A visitor leaves by the teleporter**, in sight, as a player does.
4. **Ambience**: groups, waves, duels, then pets. Costumes are dropped.
