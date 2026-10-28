# Merging the upstream into the bot branch

Status: in progress: merged, compiled and tested on branch `merge/upstream-2026-10` (28 October 2026, 442 tests); not deployed.

`4.8` was 69 commits behind `beyond-aion/aion-server` (the last one of 28 October, 1515 files). A trial merge in a throwaway worktree gave 10 small
conflicts out of 30 files touched on both sides, and a compile that fails on 7 changes of interface. Nothing pushed without the user's decision.

## What the trial showed

| Upstream change | Where the module breaks | Kind |
|---|---|---|
| `performBuyFromShop` signature | `BotVendorManager` (5 places) | mechanical |
| `getTradeTablist` renamed | `BotGearSources`, `BotChat` | mechanical |
| appearance fields renamed (`setHeight`, `getChinJut`, `getJawHeigh`) | `BotAppearance` | mechanical |
| `ZoneData.getZones` signature | `BotPlaces` | mechanical |
| `isFlightAllowed` / `isFly` became a zone attribute (`ZoneAttributes.FLY`) | `BotFlight`, `NavmeshService` | mechanical |
| per-map death levels removed (`getDeathLevel`) | `BotFlight`, three places | to design: a table of our own |
| `MultiClientingService` rewritten as `MultiClientService` | conflict: keep upstream's, put back the guard for connectionless bots | merge |
| login server moved to Netty | nothing in the code | check that the client connects |

Falling is still the client's (`updateFalling` runs on move packets), so the rule that every descent is flown holds.

## Steps

1. `4.8` caught up on `upstream/4.8` (a fast-forward: it carries nothing of ours); branch `merge/upstream-2026-10` in a worktree from `feature/playerbots`.
2. Merge; each conflict resolved in favour of upstream with our lines put back, the choices noted in the commit.
3. Compile; the mechanical changes, then the death levels with the user's word on the rule.
4. All tests; a test for the flight floor rule.
5. Deploy and restart, with the user's OK; the log read for errors.
6. In game, by the user: the client connects, a flight and a landing, a purchase from a merchant, a group with a bot.
7. Fifteen minutes to settle, thirty measured, compared with the baselines of 27 October and the step 5 report: the new reference for navigation.
8. Merged into `feature/playerbots`, the worktree and branch removed.

## Found while merging: what the install needs

`deploy.ps1` copies the two jars and the handlers; the rest of the install is a copy made once. The merge changes all of it:

- **Libraries** (`libs/` of each server): Netty 4.2 replaces 3.10 (login and chat servers move to it, and the game server gains it), jakarta
  replaces javax for XML binding, and Hikari, MySQL connector, logback, slf4j, quartz and fastjson2 are all newer. 19 jars to add to the game server's
  folder, 16 to each of the other two, and the superseded ones to remove. `mvn dependency:copy-dependencies` lists them.
- **Data and configuration**: 35 files of `data/static_data` (`world_maps.xml` and its schema among them) and 9 configuration files changed. The
  installed ones are copies of our old repository version, so those that were never edited locally can be replaced by the new ones.
- **The database**: `game-server/sql/update.sql` carries the statements upstream added (`players.pvp_exp`, `player_recipes.production_limit`, two
  `player_appearance` columns renamed, `house_scripts` compressed). A backup first, then the statements, before the new server starts.
- **Logs** now roll by size (100 MB, 5 files) from `logging.properties`.
