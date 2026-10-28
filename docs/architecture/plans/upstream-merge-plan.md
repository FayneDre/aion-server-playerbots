# Merging the upstream into the bot branch

Status: in progress: merged, tested and deployed on 28 October 2026 (branch `merge/upstream-2026-10`, 442 tests); to be checked in game, then measured.

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

`tools/sync-install.ps1 -From <first parent of the merge>` does the three first on request and shows them without `-Apply`. Its dry run of 28 October:
48 data and configuration files to replace or remove, 47 libraries to add and 23 to remove over the three servers, 22 lines of SQL. Two files were
edited in the install and are left for a hand merge: the chat server's `network.properties` (the game server password; upstream added an
`auth_timeout_seconds` line) and the game server's `logback.xml` (three DEBUG loggers for the chanter and ranger playbooks, left from a debugging
session; the merged file has none).

The SQL also holds a migration of every player's skills to level 1 (upstream stopped using the skill's grade as its level), which bots take
with everyone else. New server settings to know: at most 2 characters per computer (`multi_client.max_clients_per_computer`; bots have no
connection and are not counted), and a client that stalls or does not authenticate in 60 s is disconnected.

## Deployed on 28 October

Backups in `D:\Jeux\Aionackup-before-upstream-merge-2026-10-28` (the three servers' libs and config, and `aion_gs.sql`, dumped with the server stopped).
Order: build, stop the three servers, dump, upstream's SQL, `sync-install.ps1 -Apply`, the two files edited in the install merged by hand, `deploy.ps1
-SkipBuild`, `start-server.ps1`. Met on the way: the dump needs `--skip-lock-tables --no-tablespaces` for this database user; the chat and login
servers load their own copy of the commons jar, which nothing copied (now `sync-install.ps1` does, with the login server's jar); and a jar built in a
worktree names the main checkout's revision (`08a087a`), so the revision is only right after the merge is rebuilt in the main checkout.
Started with no ERROR line, 959 bots in the world, the game server connected to the login and chat servers.
