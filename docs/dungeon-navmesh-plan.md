# Dungeon navmeshes: plan

Status: milestones 0 to 5 done (45 meshes built, 9 copied, every one put through `reach`), plus glide links and a steeper slope for three stairs; on 19 October every mesh measured again and 20 built again with floors the generator had dropped (see "Measured again, 19 October"); 6, the server opening an instance mesh, seen in the logs (Nochsana), bots being in one: they follow their player in since 18 October (`BotInstances`). Sixteen maps measured again on the evening of 11 October with the long-walk planner rebuilt (table below): Illuminary Obelisk, 310100000, Lower Udas, Steel Rake, Dredgion and Dragon Lord's Refuge are now at 91 % or more; on the 19th the three left under 80 % (Esoterrace, Theobomos, Rentus) were looked into: windstreams, a lift and a way up not in the data, none the generator's fault. Next: portal links. Nothing here is watched in game. Scope: the **PvE dungeons and raids for a group**, every version; **solo dungeons are out**: the goal is
bots that fight in a party, and a solo instance holds one player (23 of the first 77, Haramel among them; `instance_cooltimes.xml` gives one member for each). That leaves the 54 ids of the first
table in [instance-navmesh-list.md](instance-navmesh-list.md), which come down to **45 distinct geometries**: a mesh is built once and serves the ids that share it. Arenas, warfronts, housing
and tests are out for now. Putting a bot in an instance is not this plan's: `BotInstances` does it, since 18 October.

The generator and its checks are [navmesh-plan.md](navmesh-plan.md)'s. This file is only what a dungeon asks that the open world did not.

## What is different about a dungeon

- **Interiors, on several floors.** The rough route had no height, which is how a bot went straight down through a fortress platform; the levelled route fixed that for the Eltnen fortress
  ([navigation-stuck-plan.md](navigation-stuck-plan.md)). A dungeon is that case everywhere, so the first mesh is looked at before the others are made.
- **Doors and gates.** `data/static_data/staticdoors` holds the doors a dungeon opens and closes, and the stuck plan already notes that a door counted as a wall cuts a mesh in two. A boss
  gate that is closed until the boss dies is a real wall for a bot too, so some of a dungeon being unreachable from its entrance is right and not a fault. Which doors count as open is the
  question to settle on the first mesh.
- **No obelisk to audit from.** The existing obelisk check has nothing to start from. What a dungeon has is **entry points** (`portals/portal_loc.xml`, by `world_id`) and **spawns**
  (`spawns/Instances/<id>_<name>.xml`, 97 files), which is a better test: from the entry, can a bot walk to where the monsters stand?
- **Sizes that lie.** Three of the 54 declare 2048 m for a dungeon much smaller (Lower Udas Temple, Beshmundir Temple, Tiamat Stronghold). The grid comes from `world_size`, so they cost the memory and time of an open map. Measured before the batch, not guessed.

## Milestones, in verifiable order

0. **Can a mesh serve several ids?** *Done: yes. `Navmesh.open` finds the file by the map id and only reads the id stored inside it into a field nothing uses, so `<id>.nav` can be copied under the other ids.*  Read `NavmeshService`: if the id is only the file name, copying `<id>.nav` is enough, and the batch builds 45 meshes and copies 9. If an id is written inside,
   the generator takes a list of ids for one geometry. Done when it is settled and written here.
1. **One small interior, looked at.** *Done for six, see "The pilot". Time: six dungeons in 82 s, a mesh of 0.6 MB for the smallest.*  Right Wing Chamber (300090000, 12 KB of geometry, 512 m), then Dredgion (300110000, 67 KB). Run `tools/navmesh.ps1 <id>`, open the images, run `audit` and
   `components`. Decide the door question and the floor question here. Done when the images and the numbers say the mesh is walkable, written with the figures as for Sanctum.
2. **The reachability check.** *Built: `NavmeshTool <id> reach` and `tools\navmesh.ps1 <ids> -Reach`, with `InstanceReach` and three unit tests of the file reading. A spot is moved onto the nearest walkable ground within 3 m before it is asked about, and one with none is counted apart ("no footing") and not held against the mesh. Exit code 1 when anything was not reached.*  A new `NavmeshTool <id> reach`: from each entry point of the id to every spawn of its instance files, how many are joined to the entry and by how long a route, and the
   ones that are not, with their coordinates. The unreachable ones are then either a closed door (expected, noted) or a fault in the mesh. Done when it runs on the two meshes of milestone 1.
3. **The large ones, alone.** *Done as part of the batch: Lower Udas Temple 43 s and 8 MB, Beshmundir Temple 31 s and 48 MB, Tiamat Stronghold 13 s and 4 MB. None needed more than the heap the tool already takes.*  The three oversized, one at a time with the heap the open maps use, noting time and memory. If one does not fit, the grid is cropped to the geometry's own extent
   instead of `world_size`.
4. **The batch.** *Done: 45 built in 982 s, 254 MB in all (the slowest, Danuar Reliquary, 139 s, with the two Theobomos fortresses next).*  The 45 geometries, smallest first, each followed by `audit` and `reach`, into a table in [instance-navmesh-list.md](instance-navmesh-list.md): built, median wander, share of spawns
   reached. Meshes that fail are listed with the reason, not hidden.
5. **The copies** *(done: nine files copied, and `reach` run on each id with its own arrival)*, per milestone 0, and the same table for the ids they serve.
6. **Restart the game server** and check that a mesh opens: it is read when the first bot is on its map, so nothing shows until a bot is in an instance. The reading is tested without one.

## The pilot

Six dungeons, built and put through `reach` (five of them in scope). Two rules in the generator are specific to instances: every placement the engine can put up or take down is left out
(`GeoPlacement.type` other than 0: doors a script opens, barricades, boxes, statues), because the open or shut state of a dungeon is a fact of a run and a mesh with them as walls
shuts the party's own way in; and the invisible `collision_wall` pieces and authored no-walk volumes **within 20 m of an arrival point** are left out, since they are the fence the designers
stood round the arrival yard. The arrival points are the portal locations that a portal npc leads to (`portal_template2.xml`), not every location of the map. The open world is unchanged.
Share of the monsters' spawn spots reached from the arrival point:

| Dungeon | Plain generator | With the instance rules |
|---|---|---|
| Nochsana Training Camp (300030000) | 54 % | **98 %** |
| Asteria Chamber (300050000) | 0 % | **100 %** |
| Udas Temple (300150000) | 69 % | **91 %** |
| Dark Poeta (300040000) | 0 % | 0 % (both arrivals) |
| Right Wing Chamber (300090000) | 0 % | 0 % |

Haramel, a sixth pilot at 73 %, is a solo dungeon and out of scope; its mesh was deleted.

**A rule that was tried and removed.** Letting a body drop up to 3 m between neighbouring cells, which the first reading of Asteria suggested (its arrival platform is 2 m above the floor), joined
nothing that the arrival-yard rule did not already join: Asteria reaches 100 % with no drop at all. And it cost: Haramel (then still a pilot) fell from 73 % to 45 %, Nochsana from 98 % to 96 %, Udas Temple from 91 % to 89 %, since
a path finder allowed to step off every ledge has far more ground to search and gives up sooner. Measured at 0, 1.5 and 3 m; 0 kept.

What the pilot left, and the later passes settled: Dark Poeta and Right Wing Chamber are now at 100 % (see the second pass), and the unreached tail of the others is what the third pass and the table below deal with.

## The final instance policy (second pass)

In an instance the generator leaves out: every placement that is not static (doors, barricades, anything a script puts up or takes down), every `collision_wall` model, every authored no-walk volume, and the erosion by a body's width (`navmesh.instanceErode`, default 0). The open world keeps all of it. Measured against the first pass: Right Wing Chamber 0 to 100 %, Dark Poeta 100 % from its second arrival, Alquimia 34 to 89, Sauro 27 to 80, Dragon Lord's Refuge 80, Rentus Base 78.

## Glide links (third pass)

Draupnir Cave reached 3 %, Esoterrace 2 %, Steel Rake 45 %, Eternal Bastion 35 %: the party arrives on a platform far above the ground it is going to, and the only way down is to jump, which is what the `GLIDE` flag on those maps is for. Walking never joins them, and neither does a drop of any height between neighbouring cells (measured with `NavmeshTool <map> flood <drop> <cells>`). A fall of any height out to ten metres from an edge did: Draupnir 98 %, Esoterrace 66 %, Eternal Bastion 100 %.

- **`GlideLinks`** finds them when the mesh is written, for instance maps with `GLIDE` only: an edge (a walkable cell with a neighbour holding no walkable ground within a step), then every column out to 10 m, landing on the highest *walkable* ground at least 1 m below (a tree or a rail on the way does not stop it), in another stretch of ground of at least 40 cells; on Esoterrace, whose way down goes rock to rock, every stretch counts (`EVERY_STRETCH`, its file grows from 3 to 12 MB). At most 24 per pair of stretches, 8 m apart. Stored in `<mapId>.glide` beside the `.nav`, which does not change.
- **`GlideRoutes`** plans with them when the walking search finds nothing, whether it proved there is no way or ran out of room: on a large stretch of ground the walk never proves it. A breadth first search over the stretches, then the walking route to each edge. The start and the goal each list the stretches of the 4 m cell and its eight neighbours, the floor nearest in height first (a tower lists thirty); two seconds at most. On a map with glides every route is planned off the bot's tick thread. The air between the ends is checked against the game's geometry in the server (`NavmeshService.isAirClear`), and not in the offline tools, which therefore overestimate.
- **Not done:** the mover walks a glide as a straight leg with the height interpolated, which is the best guess and is untested. A glide is a waypoint in `Route.glides()` that nothing reads yet.

## Stairs modelled as ramps

The Miren Chamber and its two barracks were cut off at their first room by a staircase whose collision is a single face at about 61 degrees, over the 45 the generator allows. `MapSlopes` allows 62 on those three maps and 45 everywhere else (a slope for every dungeon was measured: Rentus 78 to 83, Udas 91 to 93, but Lower Udas 68 to 55 with more searches giving up). Miren Barracks 0 to 100 %. Reshanta's bases are the same trap in the open world, and for buildings only: Teminon's stairs are faces of about 57 degrees (`bu_ab_lightsp_castle_*`), so `MapSlopes.buildingDegrees` gives the map's `bu_` models 62 and leaves its rock at 45 (17 October: routes between Teminon's civilians 29 of 34 to 34 of 34, Primum's 34 of 34).

## The batch, 11 October

| Reached from the arrival | Dungeons |
|---|---|
| 90 % and over (29) | Dark Poeta, Asteria, Sulfur Tree Nest, Roah, Left and Right Wing, Miren and Krotan chambers and their four barracks, Eternal Bastion, both Danuar Reliquaries, Indratu, Padmarashka, Nochsana, Kysis chamber and barracks, Ophidan Bridge, Hexway, Fire Temple, Beshmundir, Aetherogenetics, Udas Temple, Steel Rake 90, Draupnir Cave 90 |
| 50 to 89 % (14) | Alquimia 89, Adma 87, Abyssal Splinter 83, Tiamat 82, Dragon Lord's Refuge 80, Azoturan 80, Sauro 79, Rentus 78 and its occupied version 71, Lower Udas 67, Theobomos 67, Terath Dredgion 62, Chantra Dredgion 56, Dredgion 51 |
| 1 to 49 % (6) | Drakenspire 41, Anguished Dragon Lord's Refuge 20, Unstable Abyssal Splinter 4, Esoterrace 2, both Danuar Sanctuaries 2 |
| 0 % (2) | Both Illuminary Obelisks |
| No spawn to measure (3) | Idgel Dome and the two "Lucky" copies |

## Measured again, 11 October evening

No mesh was built again but Esoterrace's. What moved the figures was the measurement and the planner:

- **The arrivals.** `InstanceReach` read only `<portal_path loc_id=..>` and missed the entrances written `<portal_path dialog=.. loc_id=..>`: Unstable Abyssal Splinter was measured from three inner pads instead of its entrance. The Danuar Sanctuaries' hall reaches its wings by teleporters coded in the handler, whose landings are now arrivals too, and every map with several arrivals gets a line "from any of its arrivals".
- **The long walk** (see [long-walk-planner.md](long-walk-planner.md)): the rough route took shortcuts the fine search could not follow, and gave up where the walk round was 500 m.
- **Copies**: `-Dungeons` skipped a copy whose `.nav` existed even when its source had been rebuilt since; the Illuminary Obelisk copy kept glide links from before they were fixed.

| Dungeon | Morning | Evening |
|---|---|---|
| Illuminary Obelisk (both) | 0 | 97 |
| 310100000 | 80 | 99 |
| Lower Udas | 67 | 97 |
| Steel Rake (any arrival) | 90 | 94 |
| Dredgion, Terath, Chantra | 51, 62, 56 | 91, 89, 88 |
| Tiamat (300510000) | 82 | 88 |
| Unstable Abyssal Splinter | 4 | 87 |
| Danuar Sanctuaries (any arrival) | 2 | 76, 74 |
| Esoterrace | 2 | 65 (a fall of any height reaches 66) |
| Dragon Lord's Refuge | 80 | 100 |
| Rentus, occupied Rentus | 78, 71 | 78, 70 |

Rentus has a deck of 54 monsters 9.5 m above anything walked to, and nothing placed explains how a player gets up there. Occupied Rentus lost 2 spots of 206 with the last change to the planner, not explained yet.

**What the tester says about the 40 to 50 band** (Discord, 10 October): Draupnir and Theobomos have a lift near the end and a platform phase in the middle; Theobomos and Adma need an interaction with the door to the boss (he teleports the bots to himself once it is open); Dredgion has one start for each faction and goes down plateau by plateau, which is what `DredgionInstance.portToStartPosition` says (two starts, now the arrivals of the three Dredgions); Adma is only large. **None of the lifts, doors and phases is in the mesh.**

Tools: `-Dungeons` leaves the meshes that exist alone and `-Force` builds them again; `-Reach` measures; `NavmeshTool <map> flood <drop> <cells>`, `gaps` and `blockers x y z` are for finding out why one fails. A mesh set takes about two hours to build and as long to measure, the meshes are not in git, and a server needs `.\tools\navmesh.ps1 -Dungeons` once.

## Measured again, 19 October

Every mesh measured as it stood (unchanged since the 11th but Drakenspire, Theobomos and Adma, never measured again: 47, 74 and 92 %). `reach` now says
where the spots with no footing stand against the floors of their column, and leaves out the spawns marked `aerial_spawn` (flyers on a path through the
air: 73 of Rentus's 338, on a map nobody flies on).

**A floor the generator dropped.** A model part that is a no-walk volume *and* solid (intention 136, see-through and no-walk) was filed with the no-walk
volumes, which an instance drops, so the floor went with them although the engine collides with it. Drakenspire's second stage stands on such floors and
bridges. Kept as solid, in instances only; the 20 geometries holding such a part were built again (the old meshes are in
`D:\Jeux\Aion\navmesh-backup-2026-10-19`):

| Dungeon | Before | After |
|---|---|---|
| Steel Rake | 94 %, 23 with no footing | **99 %**, 2 |
| Danuar Sanctuary, Seized | 74, 76 % | **94, 95 %** |
| Dredgion, Chantra, Terath | 91, 88, 89 % | **96, 97, 99 %** |
| Sauro Supply Base | 80 % | **91 %** |
| Drakenspire | 31 of 66, 25 with no footing | 47 of 88, 3; **84 of 88 in ground the glide links join** |
| Lower Udas | 97 % | 96 % |
| the other thirteen | | within a spot |

Dragon Lord's Refuge counts 13 spots instead of 5: the 8 new ones are perched 246 m up at the corners of the map, not fights. The open world files the
same parts the same way and has not been looked at.

**The bots' side.** A grouped bot that cannot walk to its player in an instance is put beside them at any distance (`PlayerBotAI.freeItself`); before, past
200 m, it was sent to its home's coordinates on the instance's map. A teleporter, a lever, a lift or a door the player's key opens is then crossed by the
player, and the bot joins them about 13 s later. Not watched in game.

**The three left under 80 %, looked into.** None is a fault of the generator; each is a way up or across that is not a walk.

- **Esoterrace, 65 → 99 %.** It is crossed by its four windstreams. `reach` now counts where each one sets its rider down (`windstreams.xml` and the
  end of its `flypath_template.xml` path) as an arrival, dropped onto the floor under it within 50 m: from the third one's landing alone, 95 %.
- **Theobomos Lab, 74 %.** The boss room is behind a face of the room model across its doorway and is entered by the portal in front of it (730177, already
  an arrival). The upper wing (33 of 351 spots) is 10 m over the main floor with nothing between: the lift the tester spoke of. The Queen Arachne room
  (16) is cut by a face of `bu_l_laboratory_room_06f` hanging 0.7 m over the floor, with spiders spawned on both sides of it: likely not a wall in game,
  left as it is until a player says so. Built again in memory with today's generator, the same.
- **Rentus, 77 and 72 %.** Kuhara's floor (z 210), a mezzanine (241) and Vasharti's deck (252) are three stretches with nothing walkable between them,
  even at a 62° slope; door 54 is a wall box (`idyun_dummy_room_30`) and no lift or teleporter is in the data. **The tester answered** (19 October,
  seen in game): a lift appears when Kuhara dies. It is engine-moved geometry, filed as a door (Kuhara's death changes doors 43 and 150, whose
  models are slabs that move up between their two states), so it is in no mesh; a bot reaches the deck by being put beside its player.

## Open questions

1. **Portal links.** Steel Rake is rooms joined by things to use (`portal_use` in `portal_template2.xml`: elevator levers, the anchor, the brig, the captain's cabin), the Danuar Sanctuaries by teleporters. A link "walk to the object, use it, arrive there", read from the portal list and the spawns, would let the planner route through them; the bot must also learn to use the object.
2. **Doors, lifts and platform phases** are not in any mesh. A door that a boss fight opens can be walked through when it is open and not when it is shut; a lift is a one way link of another kind than a glide. Draupnir, Theobomos and Adma ask for them.
3. **Walking a glide** (`BotMoveController`) has not been tried in game. The server does open an instance mesh (milestone 6): Nochsana's twice in the logs of 18 October.
4. **Drakenspire, Theobomos, Adma** are the maps left that a group of level 40 to 50 plays and nothing today has improved; Rentus's upper deck is a question for the tester. A glide link down to the plane at height zero under a map (region 1 on the Danuar Sanctuaries) is a fall nobody survives and should not be offered.
5. **Disk**: `data/navmesh` is 2 GB, most of it the debugging images the tool writes beside each mesh. The meshes are the `.nav` and `.glide` files.
