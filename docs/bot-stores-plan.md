# Bots' private stores

**Status: in progress, milestone 2 of [living-capitals-plan.md](living-capitals-plan.md).** Designed and built 20 October 2026; not yet watched in
game. Decisions taken by the user the same day: stock generated at the keeper's level, the prices below, and (milestone 5) bots buying from
players' stores.

## The engine already allows it

The private store is generic: `PrivateStoreService.createStoreWithItems(player, TradePSItem[])`, then `openPrivateStore(player, name)`.

- A player clicking the bot gets `SM_PRIVATE_STORE` through `PlayerController.onDialogSelect(BUY)`.
- A purchase goes `CM_BUY_ITEM` → `sellStoreItem`, which asks `seller.isOnline()`: true for a bot, since that means "is present". The kinah moves and
  `EXCHANGE_LOG` has the line.
- The store's name is written in `SM_PLAYER_INFO`, so a newcomer sees it.
- The engine allows **at most 10 lines**, one race only, items tradeable and not equipped, and no store while moving, flying, fighting or seated.
- While a store is open, `PlayerRestrictions` refuses skills and item use. A teleport closes the store; **leaving the world does not**, so the bot
  module closes it itself (`PlayerBotLeaveWorldService`).

## Who keeps a store, and when

- **The keepers.** Five in ten residents of level 20 and over on a town map, chosen by object id, so the same characters are the merchants every day.
- **Their hours.** A shift of 6 to 10 hours, starting at any hour and running past midnight, chosen by object id, so a town has stores open at every
  hour: the tester asked for merchants at all times. Day shifts came first (4 to 8 hours from 8:00 to 16:00): 36 merchants in Sanctum, 30 open at
  14:30 and none at night.
- **Renewed every hour or so** during a shift (`BotStores.renewIfDue`, 60 to 90 minutes by keeper): the store shuts for a moment, what has waited
  unsold for two hours is taken away for good, the shelf is stocked up to 8 lines and the store opens again. Also asked for by the tester; it is what
  bounds the generated goods, a keeper putting out 8 lines an hour at most.
- **The cap.** At most 40 stores open on a map: the crowd test of 20 October gives about 40 characters standing in sight above 40 fps.
- **During a shift** the resident walks to its stall, opens its store, and does nothing else: the tick returns at once (`BotDay.keepsStore`). It
  refuses duels (busy) and closes the store to join a group, as a player would.

## Where stores stand

**Sanctum has markets of its own** (`BotStores.MARKETS`), from a tester's notes of 20 October, after the stalls round the brokers and warehouses
put nearly every store in one hall: the street from the Exalted Path (1357 1512) by its middle (1400 1513) to the Elyos Square (1451 1511), 10 each;
the entrance of the Hall of Prosperity (1471 1442), 40; the hall itself (1403 1412), 30. A keeper tries its own market first, then the others.
**Pandaemonium** has four since 23 October, also from a tester's notes, 10 each, 12 m wide: the Vifrost Bridge (1609 1400), Market Street
(1393 1401), Pandaemonium Plaza (1300 1415) and by the Temple of Gold (1323 1481). Before them, its stalls were bunched in three spots: about 80 in
one hall, 35 in two others. The rule below holds for a map with no markets of its own.


Players put their stores near the broker and the warehouse. A stall is a spot within 15 m of a broker or a warehouse keeper (`TownServices`), on
walkable ground, at least 3 m from any other stall and any character, chosen from the keeper's object id so it is the same every day. Taken stalls are
registered per map (`BotStores`), so two keepers never share one.

## What a store sells

A store has **a theme**, as a player's does, which gives it its name. The theme is the keeper's, by object id.

| theme | lines | content |
|---|---|---|
| gear for a class | 6 to 8 | weapons and armour one class wears, of the keeper's faction, level within 5 of the keeper's |
| accessories | 6 to 8 | earrings, necklaces, rings, belts, same rules |
| stones | 5 to 8 | stacks of 5 to 20 of the band's manastones, and of 10 to 50 enchantment stones of grades from 30 under the keeper's level to 60 over |

Enchantment stones had a theme of their own at first: they come in grades about 30 apart, so such a store held one line. Stalls are looked for
round the floor in front of a keeper's counter (`BotVendorManager.frontOf`): round its feet, 27 of 31 Pandaemonium merchants found no stall or no way
to it on the first start, 20 October.

- **Quality** is drawn: green 70, blue 25, gold 5, never above the level's ceiling ([bot-gear.md](bot-gear.md)).
- **Only reachable, tradeable gear** (`BotGearSources`, `BotGearFit.isObtainable`, `BotEquipManager.isPlayerGear`, `ItemTemplate.isTradeable`).
- **Stock comes into the keeper's bag** through `ItemService.addItem`, as many lines as the bag has room for, up to 8 whenever the store
  opens or is renewed. The shelf is kept in memory; after a restart, what in the bag fits the keeper's
  theme goes back on it, as freshly stocked. Left out at first, it filled four Sanctum keepers' bags after a day of restarts, and they opened nothing.
- **Names are in French**, since the testers play in French: "Équipement templier 30-35", "Bijoux 40-45", "Pierres 25-35".
- **Quality is drawn for gear only**: stones are drawn evenly, since most manastones are common and a draw by quality kept landing on the one
  green among them.

## At what price (`BotStorePricing`)

The data's only price is the template's `price`. An npc buys at **20%** of it and sells at 100%; the price does not depend on quality.

- **Floor**: 1.25 × what an npc pays (0.25 × template), so nothing bought from a bot sells to an npc at a profit.
- **Gear**: what an npc pays × the quality factor: common 1.3, green 1.6, blue 2.5, gold 4, epic 6. A level 30 chestpiece (template 518 000): green
  165 000, blue 260 000, gold 415 000.
- **Manastones**: 300 × the stone's level × the quality factor. **Enchantment stones**: 50 × the grade. Their template prices are meaningless.
- **Temperament**: ±15% per keeper, by object id.
- **Unsold**: 10% less per full hour since the piece was stocked, down to the floor, which a piece reaches within a day. A restart counts
  the hours again from nought.
- **The shelf is neither worn nor sold to an npc** between two shifts (`BotStores.isOnShelf`, asked by `BotEquipManager` and `BotVendorManager`).
- Not built: the rise after a quick sale, which needs a hook on the sale itself.

## Checks

- A player buys from a bot in game; the kinah moves; `EXCHANGE_LOG` has the line.
- A row of stores with their names shown, measured for fps as the crowd test was.
- No keeper walks with its store open, and none is left with an open store after a sleep.
