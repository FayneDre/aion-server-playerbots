# Capitals: how they live today

Status: reference, written on 28 October 2026 from the plans of 20 to 24 October, all built and looked over in game ([history/](history/)).
What is on hold (duels on a square, pets, the day's rhythm) is in [roadmap.md](../roadmap.md).

Sanctum and Pandaemonium have the same three populations as any map ([../population/how-it-works.md](../population/how-it-works.md)), with a day of
their own instead of hunting, private stores, and visitors from the rest of the world.

## A capital day

`TownRoutine`: spells of 5 minutes, the same every day and starting at each resident's own moment: home 20 %, sitting 10 %, an errand to a town
service (broker, warehouse, shops) 35 %, a visit to another part of town 35 %. No hunting in a capital. Sanctum is reached through its rides
(the Sky Canal lift, the lift of the Artisans Hall, the boat of the Outer Dock, `RideLinks`), which the mesh does not join by itself.
About 40 characters in sight keep 40 fps (the crowd test of 20 October), which sets every cap below.

## Private stores

- **Who** (`BotStores`, [history/bot-stores-plan.md](history/bot-stores-plan.md)): eight in ten residents of level 20 and over, chosen by object id,
  so the same characters are the merchants every day. A rota (`TownRoutine.onRota`) keeps a window of 44 of them open, 40 stores at most a map,
  going round the town once a day.
- **Where**: markets of their own, from a tester's notes (four in Pandaemonium, six in Sanctum), as a grid of stalls 6 m apart on ground joined to the
  market's middle; a map with none puts stalls within 15 m of a broker or a warehouse. A merchant with no way to its stall gives its turn for 6 hours.
- **What**: themed stock generated at the keeper's level, up to 8 lines, renewed every 60 to 90 minutes, what stayed unsold two hours taken away.
  Six categories ([history/bot-store-goods-plan.md](history/bot-store-goods-plan.md)): coins and medals, upgrade items (enchantment stones, the
  evolution stone, the serum), standard and ancient manastones, the class's own gear and jewellery, blue at least.
- **Price** (`BotStorePricing`): from the template, the quality and the keeper's temperament, at broker prices for the goods that have them, cheaper
  the longer a line stays unsold. A sale goes through the engine's own store, so the kinah moves and `exchange.log` has the line.
- During its shift a keeper does nothing else; it refuses duels and shuts its store to join a group, as a player would.
- Bots never buy, from players or from the broker (dropped on 23 October). Should it come back: `BrokerService.getAveragePrice` divides by zero on an
  empty broker.

## Visitors

High levels live where their monsters are (the Abyss, Inggison, Gelkmaros), so a capital's own residents are mostly under 30 and from 45 to 49.
`BotVisitors`, reviewed every 5 seconds, wakes **sleepers** of the faction at the capital's arrival point while a real player is
in it, up to 15 at a time, their level drawn in the five-level band, from 10 to the top, that the town holds fewest of. A visitor spends 20 to 40 minutes on the
town routine, then falls asleep out of sight and starts at home on its next spawn. Its home never moves: the column `visiting_from` (added on start
by `SchemaUpdater`) files it under its own map, so no rule rehouses it in town, and it keeps no store. No awake bot is ever taken from its region.

## Speech

Bots talk in the capitals by **shout** (`BotSpeech`: store adverts every 2 to 4 minutes a capital, calls for a group every 3 to 6, a speaker within
80 m of a real player and quiet 30 minutes after) and, everywhere, in the **chat channels** ([../social/how-it-works.md](../social/how-it-works.md)).
`//bot speech off` silences both.

## Limits

- The Protectors Hall floor is missing from Sanctum's mesh, so its terrace stays empty.
- No ambience beyond the routine: talking circles and waves were tried and dropped; duels on a square and pets are on hold.
