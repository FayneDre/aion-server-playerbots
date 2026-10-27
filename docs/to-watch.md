# What is built and not yet watched in game

Status: opened on 27 October 2026 from the roadmap's item 0, the plans' Status lines and every patch note's "À tester" since `patch-2026-10-19`.
**11 open, against a limit of 10**: past ten, what is here is verified before anything new is built (agreed 27 October).

How it is kept:
- A line keeps its number for good, and a number is never given again: the list was renumbered once and a validation was read against the wrong lines.
- A line comes in when a patch ships a behaviour nobody has seen, with where to look and the log line that proves it. A line goes out when it has been
  seen working (it then goes into the plan's Status) or seen failing (it then becomes a fix, and comes back with the fix).
- **Squad** means a bot-only squad can show it with no player (`tools/bot-command.ps1`, recipes in
  [test-harness-plan.md](architecture/test-harness-plan.md)); the rest needs someone in game. `//bot debug combat` adds the per-cast lines.
- **Patch** is the October patch that shipped it (`25-2` is `patch-2026-10-25-2`). The log lines are written as the code writes them, `{}` for what changes; `tools/measure.sh` counts several of them over a window.

## Combat and groups

| # | What | Where, how | Proof in the log | Squad | Patch |
|---|---|---|---|---|---|
| 3 | Templar, chanter and ranger playbooks with a player in the group | A group fight, then an instance | debug per cast; no `gives up` round the ranger | partly | 16 to 19 |
| 5 | Positioning against a boss (`AUTO` places only there) | An instance boss: none outdoors stands on the mesh | `Positioning {}: {} of {} samples well placed` | no | 16 |
| 6 | Duels, friends, trades with a companion, `//bot give` | Anywhere, a companion of one's own | `Bot {} confirms its trade with {}: ...` | no | 19-2 |

## Following a player

| # | What | Where, how | Proof in the log | Squad | Patch |
|---|---|---|---|---|---|
| 8 | A bot cut off in an instance (teleporter, lift, windstream) is put beside its player in about 13 s | Dredgion, Esoterrace, Steel Rake | `Bot {} could not move at {} {}, cut off from {}, and is put beside them at ...` | no | 19 |
| 9 | Bots waiting five minutes for a player who logged out inside, then leaving | Log out in an instance | `Bot {} has waited {} min in map {}, {}, and leaves` | no | 18 |
| 10 | Followers copy a leader's glide, with wings drawn (`GLIDE` mask) | Glide off a ledge with a group | `Bot {} glides after its leader from {} to {} through {} point(s)` | no | 20 |
| 11 | Flying with a player: landing beside them, the void, Primum's rings, fighting in the air, scrolls read | The plan's "How to verify" ([flying-with-a-player-plan.md](flight/flying-with-a-player-plan.md)) | `Bot {} lands at ...`, `is not following {} into the air: {}` | no | 19-3 |
| 12 | A group taking a transport flight or a teleport with its player | A teleporter npc with bots in the group | `Bot {} leaves map {} behind {}, who has gone to map {}` | no | 23 |

## The world

| # | What | Where, how | Proof in the log | Squad | Patch |
|---|---|---|---|---|---|
| 15 | A cleric no longer seems to jump to the monster at a fight's end | A group's cleric, watched | none: the client's view only | no | 25-2 |
| 18 | Stores renewed within the hour, open at night, a rider vanishing as the boat leaves | Sanctum | `Bot {} renews its store, {} unsold line(s) taken away` | no | 20 |
| 19 | The three chat channels over an hour: variety, pace, a false line | A character of 10+ in a capital | `Bot {} says in trade: {}` (and region, find group) | no | 24-2 |

## Seen, and moved out

A line moved out says when, by whom (the tester, the user, a squad) and what was seen.

- **Cleric heals on the corrected thresholds, the heal over time from a fight's start, the channels landing**: validated in game by the user on 27 October 2026.
- **Gladiator and sorcerer playbooks, solo and grouped; `//bot gate`**: validated in game by the user on 27 October 2026.
- **Group resurrection**: validated in game by the user on 27 October 2026.
- **Wings on bots from level 30, power shards burning in a fight**: validated in game by the user on 27 October 2026.
- **Reshanta: bots flying from island to island, none stuck at an edge**: validated in game by the user on 27 October 2026.
- **A bot invited from over 50 m is brought, and sent home when it leaves the group**: validated in game by the user on 27 October 2026.
- **A bot steps off lava before resting**: validated in game by the user on 27 October 2026.
- **Capitals: no bot frozen, circling or vanishing in sight**: validated in game by the user on 27 October 2026.
