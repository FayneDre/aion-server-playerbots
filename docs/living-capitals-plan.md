# Living capitals

**Status: in progress, milestone 0.** Analysis of 20 October 2026, from the code, the game database and a 2.5 hour log of the running server. The
three decisions below were taken the same day: stock generated at the keeper's level, the prices as written, and bots buying from players' stores.

What is asked: capitals that feel lived in, with people who **stay and are seen**, some of them keeping a private store while the broker is not done.
Sanctum and Pandaemonium have their civic inhabitants today, but those come and go.

## What the capitals hold today (measured 20 October, 10:18 to 12:48)

| | Sanctum | Pandaemonium |
|---|---:|---:|
| bots whose home is there (database) | 67 | 151 |
| awake | 62 | 134 |

- **The two capitals are not mirrored.** The model gives Sanctum 250 civilians and Pandaemonium 266, so 67 against 151 comes from somewhere else,
  the mesh or the places. To be explained first (milestone 0).
- **A third of a capital day does nothing.** The 196 awake capital residents drew FARMING 1543 times, LOITERING 1716 and WANDERING 1629. In a capital
  `BotDay.farm()` finds no ground to work and "hunts from here": nothing. What shows is loitering and wandering, in spells of 3 to 10 minutes, so a bot
  is never at the same spot twice. That is the "coming and going".
- **Fruitless flask trips every ten minutes.** 2806 shop trips is about 14 per bot, most selling and buying nothing: one resident walked the same 25 m
  to the same keeper nine times in 90 minutes. The keeper stocks no flasks, `FLASK_TRIP_COOLDOWN_MILLIS` reopens the errand, and nothing remembers
  that this shop has none. From afar it looks like life; it is a loop.
- **Purses**: a bot carries about 890 000 kinah on average (13.5 M at most), the money it was given for stigmas.

## A bot's store needs no core patch

The private store is generic: `PrivateStoreService.createStoreWithItems(player, TradePSItem[])`, then `openPrivateStore(player, name)`.

- A player clicking the bot gets `SM_PRIVATE_STORE` through `PlayerController.onDialogSelect(BUY)`.
- A purchase goes `CM_BUY_ITEM` → `sellStoreItem`, which asks `seller.isOnline()`: true for a bot, since that means "is present".
- The store's name is written in `SM_PLAYER_INFO`, so a newcomer sees it.
- The engine allows **at most 10 lines**, one race only, items tradeable and not equipped, and no store while moving, flying, fighting or seated on a
  chair.
- While a store is open, `PlayerRestrictions` refuses skills and item use, and a teleport closes the store. A bot keeping a store must try nothing
  else, and must close the store before doing anything.

## Decisions (taken 20 October: 1b, 2 as written, 3 yes)

1. **Where the goods come from.**
   - (a) Only what bots really loot: honest, but thin. A capital resident does not hunt, and field bots sell their drops in the field.
   - (b) Stock generated at the shopkeeper's level, filtered by `BotGearSources` (obtainable, of its faction, nothing vetoed): plentiful, but items
     come out of nothing.
   - **Recommended**: (b), capped per store and per day, then (a) as well once field bots carry their good drops home.
2. **The price level**: the quality factors below. Recommended: start there and let sales move prices.
3. **Do bots buy from players' stores?** It gives players a market, but brings in kinah from nowhere. Recommended: yes, capped per bot and per day,
   and only below the fair price defined below.

## What a bot sells

Ten lines at most, so a store has **a theme**, as a player's does, which also gives it its name.

| theme | content | source |
|---|---|---|
| gear for a class | 4 to 8 pieces of one armour type or two weapon types, the keeper's level ±5 | generated or loot |
| accessories | earrings, necklaces, rings, belts | generated or loot |
| manastones | 5 to 10 stacks of the band's stats | generated |
| enchantment stones | stacks of the band's grade | generated or loot |
| consumables | flasks, scrolls, food that no npc sells at that price | generated |
| materials | flux, ore, cloth, by the stack | loot (gathering later) |

Quality is a weighted draw: mostly green (RARE), some blue (LEGEND), a rare gold (UNIQUE), never over the level's ceiling in
[bot-gear.md](bot-gear.md). Store names come from a template per theme ("Plate for templars 30-35", "Cheap manastones"); their language, like the
speech's, is a choice to make with milestone 4.

## At what price

The data's only price is the template's `price`. An npc buys at **20%** of it (`gameserver.prices.vendor.sellmod = 20`) and sells at 100%. That price
does not depend on quality: a level 30 chestpiece is about 500 000 and a level 50 one about 3.5 M, green or gold alike. Hence:

- **Floor**: 1.25 × what an npc pays (0.25 × template). Any lower and a player buys from the bot and sells to an npc at a profit: a kinah tap.
- **Ceiling**: 0.9 × the npc's price, for anything an npc sells. Any higher and nobody buys.
- **Fair price**: for gear, the npc buy price × a quality factor: common 1.3, green 1.6, blue 2.5, gold 4, epic 6 (decision 2).
  - Level 30 chestpiece (template 518 000, an npc pays 104 000): green **165 000**, blue **260 000**, gold **415 000**.
  - Manastones, enchantment stones and materials have meaningless template prices (a manastone is worth 1000). They take a small **table by
    category and level** in the config instead.
- **Temperament**: ±15% per shopkeeper, from its object id like its occupations, so the same bot is always a little dearer or cheaper.
- **Prices follow sales.** Unsold after an hour, an item drops 10% an hour down to the floor. Sold inside ten minutes, it comes back 10% dearer, up to
  twice the fair price. The market settles with no tuning by hand.
- **The takings go to the keeper's purse**, which can pay for its own gear: the roadmap's "gear does not keep up".

`BrokerService.getAveragePrice` would be a better reference once the broker holds listings, but it **divides by zero** when it holds none
(`counter = 0`). A trap for the broker phase; not used here.

## Where stores stand

Players put their stores near the broker and the warehouse. Those spots are found from the npcs holding these functions, among the living npcs, as
`BotVendorManager` finds keepers. A spot is:

- walkable ground with clearance in the mesh, 3 m from the next store;
- never in a passage under about 4 m wide, so a row of keepers walls nothing off;
- derived from the bot's object id, like standing room ([population.md](population.md)), so a keeper is found at the same place every day.

## Milestones

0. **Measure and explain.**
   - Sanctum at 67 against Pandaemonium at 151. **Explained 20 October.** The director wanted 62 against 134: Sanctum drops 18 of its 34 places at
     startup, 17 of them as islands. Its three centre bridges (`bu_l_centerbridge_01a/02b/03c`) carry `WALK | PHYSICAL_SEE_THROUGH`, which the engine
     collides with but the mesh generator read as no-walk volumes. That cut off the city's eastern half: island 339, 140 000 cells and seven places
     at z 590. A building's walk-flagged solid part is now a floor, as it already was in instances (`HeightfieldBuilder`). The authored volumes
     (`nowalk_obstacle*`, `walk_obstacle4_100m`) carry the same flags and stay no-walk. Sanctum regenerated: the main island went from 213 498 to
     357 130 cells. After a restart Sanctum kept **24 places of 34** (16 before) and wanted **96** (62 before); `populate 110010000 0` created
     the 38 characters it lacked, and 96 were awake. Still lost, for other reasons: the terrace at z 811 (52 townsfolk, probably reached by a
     teleporter), the southern group at z 520 to 530, the closed arena, and two small places. Pandaemonium and 710010000 were regenerated too
     (their obelisks pass): Pandaemonium still loses the same 4 of 36 places, which are not near its walk-flagged entrance floor, and 710010000
     loses 3 of 76, with no earlier figure to compare against. Poles, tents and colliders on 12 more maps carry such parts, and those maps were
     left as they were.
   - The fruitless flask trip. **Fixed 20 October**: a trip made for flasks alone only goes to a keeper who sells one the bot may drink
     (`BotVendorManager.findVendor`, `forFlasks`). Deployed; the count of trips is to be measured again.
   - Count in game how many characters and stores a client tolerates in one square: the roadmap's first limit, never measured, and a market is
     where it bites first.
1. **A capital day.**
   - Occupations of their own in a capital: no FARMING, longer spells.
   - Errands to the capital's functions (warehouse, broker, stigma master, soul healer, teleporter, crafting tables), each a short stop.
   - Sitting (`EmotionType.SIT`) on steps and benches.
   - Check: a resident is found at the same places at the same times, and no capital bot logs FARMING.
2. **Private stores.**
   - A SHOPKEEPING occupation of 1 to 4 hours, held by a share of the residents of level 20 and over.
   - The keeper walks to its spot, opens its themed store, and then stands with a slow tick (30 s: nothing to decide but sold out or closing time).
   - It restocks at closing time.
   - Check: a player buys from a bot in game, the kinah moves, and EXCHANGE_LOG has the line.
3. **Ambience.**
   - Groups of two to four facing one another, with emotes.
   - A rare wave or bow when a player stops in front of a bot.
   - Bots duelling on a square: duels exist, and onlookers are life.
   - Cosmetic pets and costumes, so a crowd is not all combat gear.
4. **Speech.**
   - Store adverts in the region channel ("Selling plate 30-35, see my store"), one every few minutes per capital, not per bot.
   - Calls for a group, echoing the offers `BotRecruiter` fills.
   - Cheap and highly visible, noisy if overdone: a cap per channel.
5. **Bots as buyers.** They browse players' stores and buy what sits under the fair price, inside a daily cap (decision 3). The broker comes next, on
   the same price rule.
6. **The day's rhythm.**
   - Fuller in the evening, emptier at night, by the server's clock.
   - Arrivals and departures through the teleporter and the obelisk, where a player can see them, rather than out of thin air.

## Later, to be decided

- **A companion keeps a store for its owner** (`//bot shop`): the absent merchant of official servers. It sells the owner's goods, never its own, at the
  owner's prices. The one exception to "an owned bot never sells" ([bot-gear.md](bot-gear.md)).
- **Crafters at the tables**, with the animation. Real crafting would stock the stores; it belongs to the economy phase.
- **A legion recruiting on the square**, once legions exist.

## Risks

- **Memory and the client.** 150 bots a capital is already the cap. A keeper costs almost no ticks but still a character in sight, so the client
  ceiling of milestone 0 decides.
- **Economy.** Generated stock and buying bots create items and kinah. Everything goes through the engine's own paths, so EXCHANGE_LOG traces it, and
  the daily caps are the valve.
- **A frozen store.** If a bot keeps a store, everything that moves it (rescue, director, sleep, group invitation) must close the store first.
  Otherwise `PlayerRestrictions` refuses the move without saying why.
