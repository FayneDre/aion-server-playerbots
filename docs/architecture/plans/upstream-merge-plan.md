# Merging the upstream into the bot branch

Status: in progress: trial merge done on 28 October 2026, the real merge on branch `merge/upstream-2026-10`.

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
