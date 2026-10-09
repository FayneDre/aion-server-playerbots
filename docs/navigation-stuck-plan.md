# Bots stuck in scenery, then teleported home

Status: analysed 2026-10-09. Milestones 2 and 3 built and unit tested, not yet watched in game (`BotLanding`, the levelled rough route, binding to a reachable
obelisk); milestone 4 (the obelisk audit) built and run once.
The cause is **not** the quality of the mesh in the camps; milestones below.
Reported from in game: a bot inside a wall at the Verteron fortress, a bot sunk into the floor of the Eltnen fortress, both followed by a teleport.

## What the logs say

The server logs every rescue (`could not move at all from X Y and is put back home`) and every resurrection (`revived at X Y Z`). Over 5 to 9 October:
**1171 rescues, 5278 resurrections.** Rescues per day fell from about 380 (6 October) to under 10 per session (9 October), so the old causes were mostly
fixed; what remains is concentrated, not scattered. Bots were assigned to maps through the database (`players.world_id`).

The rescues cluster on a handful of spots, and **every one of those spots is a resurrection point (an obelisk)**:

| Spot (map) | Where | Resurrections nearby | Rescues nearby |
|---|---|---|---|
| Verteron fortress, 2320 1800 | upper floor, z 195 | 81 | about 140 |
| Eltnen fortress, 2410 2750 | | 131 | about 150 |
| Eltnen, 1940 2010 | | 119 | about 60 |
| Verteron, 1760 890 and Eltnen, 280 2740 | | 201 | about 70 |
| Verteron, 2340 810 (Tursin) | | **3328** | about 50 |

So a bot is not wandering into scenery in the camps. It is **put down by the engine at an obelisk and cannot walk away from it**. Most obelisks are fine
(Tursin: 50 rescues in 3328 resurrections); the fortress ones fail nearly every time, and a bot rescued is sent home by teleport, which is the
teleport that was seen.

## Why the fortress obelisk traps a bot

`NavmeshTool 210030000 path` from the courtyard (2380 1802 z 109) to the obelisk (2319 1802 z 195) answers *no route exists*, in both directions. The
obelisk floor is a pocket the mesh does not join to the rest of the map (`components`: a 96 by 86 m island apart from the main one). Players reach it
by stairs, so **the mesh is missing a connection**: stairs steeper or taller than the step rule, a door counted as a wall, or a landing the generator
treats as blocked. The Eltnen fortress shows the same thing with a second variant: of the reports there, about 40% are where the map has **no footing
at all**, so the bot is inside geometry, which is the screenshot of a bot sunk into the floor (the bind coordinate sits inside a solid).

Measured with `NavmeshTool <map> stuck <file>` (new): of the rescues, 607 of 690 on Verteron and 312 of 432 on Eltnen are on ground the mesh calls walkable
(pockets the bot cannot leave), the rest inside geometry. Homes are not the problem: 122 of 134 resident homes are on each map's main island.

## What it is not

- Not the camps. Rescues in the open field are a few a day.
- Not flight: three takeoffs in the log.
- Not the reactive steering, which only shows up after the bot is already in a pocket.
- Not a reason to wait for the Abyss meshes, but it is a reason to fix the generator **before** generating Reshanta, which has fortresses and stacked floors.

## Second cause, found while testing the first: the rough route has no height

From the Eltnen fortress obelisk (2418 2756, z 365) the planner reaches some places (317 m walked for 146) and gives up on others, although the rough
pass "finds a way". The rough grid labels each 4 m cell with the **regions** whose ground passes under it, but not at which height. A column that holds
both a fortress platform and the ground below it carries the same region twice, so the rough route happily goes straight down through the platform, and
the fine search cannot follow: the bot is on the upper level and the guide points are on the lower. Refinement fails, the planner gives up, and the bot
**walks straight at its goal** while it plans, which is how it ends wedged in a wall. Every multi-level structure has this problem: fortresses, and the
stacked islands of the Abyss, which is why it must be fixed before Reshanta.

## Milestones

1. **Say where and why** (small, not built). Add map, z, the island and the nearest obelisk to the rescue line, so the next report names its cause.
   `NavmeshTool <map> stuck <file>` exists to compare.
2. **Never leave a bot in a trap** (bot side, built). `world/BotLanding` asks the mesh, with the bot's anchor as the goal, and moves the bot to ground it can
   walk away from: onto another floor when its own is a pocket, onto the nearest floor when it is inside geometry. Used after every resurrection (next
   tick) and in the stuck rescue before any teleport home. No list of bad spots; it holds on every map that has a mesh, Reshanta included once generated.
   Checked on the real Verteron mesh: the fortress obelisk is moved to the courtyard (2298 1781, z 109) in 85 ms; ordinary obelisks are left alone.
3. **Give the rough route a height** (planner, built). A rough-route node is now a coarse cell **and a level of ground in it**, read from the fine grid
   when the cell is first reached, so **the `.nav` format is unchanged and no map is regenerated**. Falls back to the old search when no levelled route
   exists. Measured on the real meshes: Eltnen fortress obelisk to a resident's home, *gave up* before, now 564 m in 153 ms; the other fortress obelisk to
   Morilen's home, 1579 m in 598 ms. Route audit over 250 m, routes found out of 300 tries: Eltnen 120 to 133, Verteron 81 to 85, Poeta 120 to 121;
   nothing found before is lost. Cost: the newly found routes include long detours (up to 2.5 km for 250 m), real winding descents or cliffs the old planner
   never reached, which callers may want to cap. Unit test `theRoughRouteStaysOnThePlatform...`, which fails against the old search.
   Also built: bots are bound to **the nearest obelisk they can walk back from** (`BotPlaces.nearestReachableObelisk`: shared ground, then one route
   search, a search that gave up does not rule one out), so a fortress obelisk with no way out is simply not chosen.
4. **Audit every obelisk of every mesh** (built). `tools/navmesh.ps1 -Obelisks [-MapId n]`, or `NavmeshTool obelisks [map]` with the server libraries on the
   classpath, reads the obelisks from the spawn files and the bind point list, and checks each: a floor under it, being on the map's largest ground, and a
   route to the three nearest other obelisks of its map. It fails an obelisk with no floor, or with every route proved not to exist (a search that ran out
   of budget fails nothing), and exits 1 so a script can refuse a mesh. Run it after generating any map. First run, 3 minutes over all 24 meshes:
   **88 obelisks, 78 ok, 10 fail**. The Eltnen fortress obelisks pass now (they failed before the levelled route). The failures:

   | Map | Obelisks failing | Remark |
   |---|---|---|
   | 210030000 Verteron | 1 of 5 | the fortress floor, 2319 1802 z 195, the case that started this |
   | 210040000, 210050000, 210070000 | 1 each | not yet looked at; none of these maps is populated today |
   | 220080000 | 2 of 7 | one is inside geometry (1575 136 z 187) |
   | 600090000, 600100000 | both of 2 | two obelisks that cannot reach each other: probably separate islands, which would be right and not a fault |

   The three first rows matter when those maps are populated; bots there are protected by the landing check and by binding to a reachable obelisk, but a
   map whose own obelisk is a trap says something about the mesh or the data. Look at each with `seams` before settling.
5. **Then** the other new meshes. Reshanta is built (98 MB); its audit lists two failing obelisks, one per faction, on separate islands: a false alarm for
   a map whose obelisks are meant to be apart, to be settled when flight can cross.

## The Verteron pocket is not a rule that is too strict

Tried with `NavmeshTool 210030000 seams 2319.2 1802.3 195.3` (new: near misses between regions, and what a looser rule would do): the floor under the
obelisk stays a pocket of about 7000 cells with a step tolerance up to 1.5 m, with a walkable slope up to 55°, and with **doors treated as open**. At 65°
it grows to 23000 cells, still not the map. So the floor is closed by geometry, or reached by something the static data does not hold (a lift, a
teleporter, a gate that is a dynamic object). Not a generator fault to fix by loosening a rule; bots are protected from it by milestone 2 and by the
obelisk choice, and milestone 4 will name every obelisk that has this problem. Open: how a player gets there.

## Side finding

3328 of 5278 resurrections are at the Tursin obelisk: that ground kills bots faster than any other. Not a navigation fault, and the new waiting spot
after a death (`bot-careers.md`) helps, but it deserves its own look.
