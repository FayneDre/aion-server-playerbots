# Open world navigation: plan

Status: milestones 0 and 1 done on 11 October (fights given up 53 % to 0 in ten minutes); 2 written on 24 maps on 24 October (575k links), after they had been lost on 18 October; what is left is under "Open after 24 October". Written on 11 October, after the dungeon work rebuilt the long-walk planner ([long-walk-planner.md](long-walk-planner.md)) and the
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

**Done on 11 October.** `.\tools\navmesh.ps1 <maps> -Field` (`FieldReach`) pairs the monster spawns of a map that are within 25 m and 8 m of each other,
which is what a bot hunting at one picks next, and routes 400 of them: **94 to 100 % reached on every open-world map** (Heiron 94, Verteron, Morheim and
Beluslan 95, Eltnen 96, Altgard and Inggison 97, Cygnea, Ishalgen, Gelkmaros and Levinshor 99, Poeta and Brusthonin 100). Theobomos and Reshanta keep their
monsters in other files and give no pairs. So the mesh is not why half the fights were given up. `.\tools\nav-stats.ps1 [-Since HH:mm]` reads the log:
over forty minutes, 4 129 fights started and 1 880 given up (46 %), **1 345 of them by one bot**. It was not out of reach of anything: walking home from an
obelisk 600 m away, it picked the monsters beside it, which `chase()` refuses in the same millisecond as being further than 60 m from its anchor, and the
log said "cannot reach".

### 1. Do not go back to what could not be reached (a day)

- The time a target is left alone grows with each failure on it, ten seconds then two minutes then ten, instead of ten seconds every time.
- Once three targets in a row have failed, the bot leaves the spot: roams, or goes back to its anchor.
- `BotTargetSelector` checks whether two places share ground (`BotPathFinder.shareGround`, already cheap) before offering a target. A monster on a ledge
  the bot's ground does not join is not offered at all.

Verified by `nav-stats`: the give-ups per fight fall, and no bot gives up more than a handful of times in ten minutes.

**Done on 11 October**, in another order than written: the cause was the leash, not the ground. The target is now picked with the camp's leash as well
(`isOffLeash`, the rule `chase()` already applied), a target refused for that is logged as "leaves … alone: it is beyond its camp", a monster given up on
again is left alone 10 s, then 2 min, then 10 min, and three give-ups in a row send the bot back to its camp. Ten minutes after a restart, against the ten
after the one before: fights given up 594 to **0**, fights started 1 127 to 548 (the loop's starts gone, the real fights the same), 28 targets left as
beyond the camp, errands, routes refused and steps aside unchanged. The check of shared ground before offering a target was not needed: `fieldreach` says
the neighbouring monsters are reachable, and none was given up on.

### 2. Glide links in the open world (two days, plus a mesh build that is the user's call)

`GlideLinks` already finds them, as it does in the instances. Only `MapSize.isInstance` keeps them out of the open world. Opening them up lets a bot jump
down a cliff instead of walking round it, and leave the ground apart from the rest that 10 of the 12 failing obelisks stand on (one more has no route, one is inside geometry).

- Measure first, with no rebuild: `NavmeshTool <map> flood 250 20` on the maps with failing obelisks gives what falls would reach.
- File size and memory: a glide file is held for each map in use, and Esoterrace's grew from 3 to 12 MB once every stretch counted. Measure them on Eltnen
  and Altgard before deciding the rule for the rest.
- The air check (`NavmeshService.isAirClear`) and the mover's glide leg are already there. Neither has been watched in game: the first time a bot glides
  is a test the tester should see.

Verified by the obelisk audit (fewer failures, none new) and `fieldreach`.

**Measured on 11 October, nothing written** (`NavmeshTool <map> glidecheck` builds the ground and the links in memory; a `.glide` file on disk would be read
by the server at once). With the instances' rule, stretches of 40 surfaces or more:

| Map | Links | File | Failing obelisks freed |
|---|---|---|---|
| Verteron | 25 741 | 0.8 MB | 1 of 1 |
| Altgard | 60 018 | 1.9 MB | none failing; 2 apart from the main ground join it |
| Heiron | 83 876 | 2.7 MB | 1 of 1 |
| Eltnen | 87 718 | 2.8 MB | none failing; 1 apart joins |
| Kaldor | 177 274 | 5.7 MB | 2 of 2 |
| Reshanta | 187 863 | 6.0 MB | 0 of 2: floating islands, which only flight leaves |
| Levinshor | 196 143 | 6.3 MB | 2 of 2 |
| Inggison | 258 398 | 8.3 MB | 1 of 1 |
| Cygnea | 1 075 225 | 34.4 MB | its failure is not a ground question |
| Enshar | 1 245 202 | 39.8 MB | 1 of 1 |

Every stretch linked, as on Esoterrace, freed nothing more anywhere and weighed two to three times as much. **8 of the 12 failing obelisks would be freed**;
Reshanta's two need flight, Cygnea's has no route on its own ground, and Enshar's other one is inside geometry. Cygnea and Enshar are the problem: 140 000
and more stretches of ground, a million links each, and the links are held in the server's memory as objects. A cap is needed before they are written for
the open world, by fewer links per pair of stretches or links into large stretches only, measured the same way.

**The cap, measured the same evening** on the same nine maps (`glidecheck` now tries five rules side by side). Four links a pair instead of 24 saved a
tenth: the weight is in the number of pairs of stretches, not in the links of each. Landing only on large ground is what counts. The rule kept,
`GlideLinks.OPEN_WORLD`, glides from ground of 400 surfaces or more onto ground of 2000 or more, 4 links a pair: it frees all fifteen obelisks the instances'
rule frees, and weighs 0.1 MB (Verteron) to 4.8 MB (Cygnea), Enshar 3.5 instead of 39.8.

**Writing them**: `.\tools\navmesh.ps1 <maps> -Glides` (`GlideLinks.writeAlone`) builds the ground and writes the `.glide` alone, the `.nav` untouched, in
the half minute it takes to build the ground: the stretches are numbered by the same computation from the same geometry. The generator still writes links
for instances only. Nothing has been written for the open world yet: the first map is the user's call, then the obelisk audit and `nav-stats` measure it.

**Lost on 18 October.** Rebuilding every open-world mesh (`navmesh.ps1 -OpenWorld`) erased Verteron's links: writing a mesh removes an
open-world map's `.glide` (`NavmeshWriter` writes links for instances only), and the script asked which maps had links after the rebuild instead
of before. Fixed on 24 October; no open-world map has glide links until they are written again, and the measure of that day shows what it costs:
obelisks joined to nothing by walking, such as Altgard's, strand the bots that die near them.

**Written for Verteron on 11 October.** 4 266 links, 0.13 MB; its stranded obelisk gets 3 routes of 3. The first glides were walked off the edge at a run,
reported from in game at the Ardus Shrine camp; the mover now opens the wings (`switchToGliding`, the emote that starts a glide), flies the leg down at
8 m/s and folds them on landing (`onStopGliding`), and only a daeva is given a glide. Five bots resurrected on the plateau glided 85 m down to the camp and
fought there. Watching it showed something else: 29 bots died in the same millisecond, the bot threads having all waited 75 s on Heiron's places being
worked out inside `computeIfAbsent`. They are now worked out outside the lock, and the searches made slow by the long-walk planner have a budget.

**The places at startup.** Working them out still took 0.5 to 42 s a map, 4 min 30 in all: 178 searches over a second (312 s), most of them giving up
between two spots of one place while its entrance was chosen (`BotPlaces.anchorOf`, ten tries a place). The entrances are now kept in
`cache/playerbot/anchors-<map>.txt` and chosen again only when the map's `.nav` or `.glide` changes (`AnchorCache`), which also stops a search out of
time from moving a place, and its residents' homes with it, between two restarts.

**Seen in game the same evening, at Ardus Shrine.** The resurrection rescue (`BotLanding`) judged the plateau a trap by walking alone and put the bots
86 m below, among the creatures, still sick: ten in twelve minutes, dying again. A daeva on ground that glides down to its anchor (`glidesJoin`, the
links and the coarse grid, no search) is now left there. And a link runs from the edge to the first ground below within 10 m, so a glide was 85 m down
for 14 m out, drawn by the clients down the cliff face: one steeper than 2.5 to 1 now circles down over its landing (49 legs there, about 35 s), each
leg checked against the game's geometry, and is driven like a climb. Three bots glided down that way after the restart; how it looks is the tester's call.

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

## Open after 24 October

Measured per 30 minutes with a player in game (`bot-measures/measure.sh`), after glide links on 24 maps, stranded villages retired, refusals
resting on give-ups or pockets no longer counted, and the walking search moved to plain arrays. Rescues fell from about 90 to 42; what is left, by priority:

1. **Searches out of time in game.** Offline, the same give-ups find their route within budget; in game, under load, they run out of the 3 s. To measure
   first: time spent waiting for one of the four route threads, the same bot asking again for a destination already refused, goals on a rock top.
2. **Steps aside rose to 976** in 30 minutes: milestone 6 above, the walk against the plan.
3. **Day plans given up, about 586** in 30 minutes: which step gives up, and whether it is the same place for many bots.
4. **Deaths near obelisks with strong monsters** (Pyrirwyn in Morheim): a bot sent home beside monsters above its level.
5. **The obelisk audit again** (`navmesh.ps1 -Obelisks`) now that glide links are written, and retiring hunting grounds refused by three bots or more.

## Rules carried over from the dungeons

- Measure before and after every change, and present the figures before starting anything long.
- No mesh is built again without the user's go-ahead. A good mesh is not built again at all.
- Tests run only with `-Dmaven.test.skip=false`. The jar under `game-server/target` is not rebuilt while a measurement uses it.
