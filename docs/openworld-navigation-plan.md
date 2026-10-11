# Open world navigation: plan

Status: not started. Written on 11 October, after the dungeon work rebuilt the long-walk planner ([long-walk-planner.md](long-walk-planner.md)) and the
obelisk audit went from 151 routes found to 173 with none lost. Each milestone below is verifiable on its own, in this order, and none needs the next.

## Where it stands

- **The planner** reaches far more than it did, and still gives up on 16 of the audit's 207 routes. 12 obelisks fail outright: islands, and one inside
  geometry. Every map of the open world allows gliding, and no open-world mesh has glide links: they are written for instances only.
- **In game**, ten minutes after a restart with 149 bots: 1 127 fights started, 594 "cannot reach … and gives up", 335 "steps aside", 334 arrivals.
  The give-ups are not spread: **one bot made 404 of them**, picking three unreachable monsters in turn. A target that could not be reached is left alone
  for `UNREACHABLE_MILLIS`, ten seconds, and three of them make a loop that never ends. 36 bots of 149 gave up at least once.
- **The data** for ways that are not walked is already in the server: `portals/portal_template2.xml` (things used in place: levers, anchors, doors),
  `npc_teleporter.xml` and `teleport_location.xml` (teleport statues and flight transporters), `windstreams/windstreams.xml`, `rift/rift_locations.xml`.
  Nothing reads them for a bot.

## Milestones

### 0. Measure (an afternoon)

The dungeons moved because every change was measured before and after. The open world needs the same two numbers.

- **Offline**: `NavmeshTool <map> fieldreach`: from each obelisk and each settlement of the map, how many of the monster spawns in the level band of
  that settlement a bot can reach, and how many searches gave up. Built on `InstanceReach` (the reach and the "any arrival" total) and the obelisk audit.
- **In game**: `tools/nav-stats.ps1` reads the server log and prints, per map and per bot, fights started, give-ups, the targets given up on most,
  steps aside, arrivals and routes refused. The ten minutes above are the first baseline.

Verified when both run on Poeta, Eltnen and Altgard and their figures are written here.

### 1. Do not go back to what could not be reached (a day)

- The time a target is left alone grows with each failure on it, ten seconds then two minutes then ten, instead of ten seconds every time.
- Once three targets in a row have failed, the bot leaves the spot: roams, or goes back to its anchor.
- `BotTargetSelector` checks whether two places share ground (`BotPathFinder.shareGround`, already cheap) before offering a target. A monster on a ledge
  the bot's ground does not join is not offered at all.

Verified by `nav-stats`: the give-ups per fight fall, and no bot gives up more than a handful of times in ten minutes.

### 2. Glide links in the open world (two days, plus a mesh build that is the user's call)

`GlideLinks` already finds them, as it does in the instances. Only `MapSize.isInstance` keeps them out of the open world. Opening them up lets a bot jump
down a cliff instead of walking round it, and leave the ground apart from the rest that 10 of the 12 failing obelisks stand on (one more has no route, one is inside geometry).

- Measure first, with no rebuild: `NavmeshTool <map> flood 250 20` on the maps with failing obelisks gives what falls would reach.
- File size and memory: a glide file is held for each map in use, and Esoterrace's grew from 3 to 12 MB once every stretch counted. Measure them on Eltnen
  and Altgard before deciding the rule for the rest.
- The air check (`NavmeshService.isAirClear`) and the mover's glide leg are already there. Neither has been watched in game: the first time a bot glides
  is a test the tester should see.

Verified by the obelisk audit (fewer failures, none new) and `fieldreach`.

### 3. Portal links: using things to get somewhere (a week)

This is the same work as Steel Rake's levers and the Danuar teleporters ([dungeon-navmesh-plan.md](dungeon-navmesh-plan.md)), done once for both.

- **A link** is "walk to this object, use it, arrive there": its position from the spawn files, its destination from the portal and teleport lists.
  It is stored beside the mesh, like glide links, and `GlideRoutes` becomes the planner for every link that is not a walk.
- **Using it**: the bot targets the object and uses it the way a player does, through the same path as `CM_USE_ITEM` (`handleUseItemFinish` in the instance
  handlers). Statues and transporters may cost kinah and ask for a dialog choice, which a bot can answer from the teleport list.
- **In the open world** it gives long journeys: across a map by its statues, between maps by its transporters, and the wind streams.

Verified offline: Steel Rake and the Danuar Sanctuaries reached from their entrance alone, with no other arrival counted. In game: one bot taken by a lever
on the Steel Rake, and one bot sent across Eltnen by statue.

### 4. Flight where it is allowed

Reshanta (`FLY`) and the flight zones of Inggison and Gelkmaros. A body in the air needs no mesh, only a straight line checked against the geometry, and
[flight-plan.md](flight-plan.md) has its first two milestones done. Its own milestones apply; this plan only orders it after 3.

### 5. The rough grid written with the mesh (two days, and every open-world mesh built again)

The stretches of ground of each 4 m cell are worked out by every search that crosses it (`levelsOf`), which is most of why the audit takes 4 min 30
instead of 3 min 40. Written into the mesh file, with the border joins, they would cost nothing at search time, and could be kept between searches so that
the routes between two settlements are planned once.

Verified by the obelisk audit: the same routes, in less time. Needs every open-world mesh built again, which is two hours and the user's call.

### 6. The route walked against the route planned

335 steps aside in ten minutes say the walk does not always follow the plan. Logging, for each route, how far the bot ended from where it was going and how
long it took against what the plan said, would show where the mover and the mesh disagree. That is a measurement first, and fixes after.

## Rules carried over from the dungeons

- Measure before and after every change, and present the figures before starting anything long.
- No mesh is built again without the user's go-ahead. A good mesh is not built again at all.
- Tests run only with `-Dmaven.test.skip=false`. The jar under `game-server/target` is not rebuilt while a measurement uses it.
