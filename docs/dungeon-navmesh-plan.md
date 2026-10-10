# Dungeon navmeshes: plan

Status: milestones 0 to 5 done on 2026-10-10 (45 meshes built, 9 copied, every one put through `reach`; results in "The batch"); 6, the server opening a mesh, not done. 21 of the 54 dungeons reach 90 % or more of their spawns from the arrival. Scope decided on 2026-10-10: the **PvE dungeons and raids for a group**, in every version (normal, hard, legion barracks). **Solo dungeons are out**: a mesh is for
bots that fight in a party, and a solo instance holds one player (23 of the first 77, Haramel among them; `instance_cooltimes.xml` gives one member for each). That leaves the 54 ids of the first
table in [instance-navmesh-list.md](instance-navmesh-list.md), which come down to **45 distinct geometries**: a mesh is built once and serves the ids that share it. Arenas, warfronts, housing
and tests are out for now. Putting a bot in an instance is out as well: no code does it, and the meshes do not wait for it.

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

What is left:

- **Dark Poeta**: both arrivals are still in sealed pockets (377 and 7700 cells). The yard is ringed by steep terrain as well as by walls; the rule above did not open it.
- **Right Wing Chamber**: a static gate model in the geometry shuts the hall the party arrives in. The "Stone Gate" next to it is a portal (`ai="portal"`), not a door.
- **The unreached tail in the others** (2 to 27 % of the spots) is not looked at yet. Some will be real (an arena nobody can enter, a boss room behind a gate), some a fault; `reach` prints the first eight with a
  command to plan each route.

## The batch

All 54 ids, from the best arrival point of each (81 routes, since several have more than one). Full figures per dungeon are in the table of [instance-navmesh-list.md](instance-navmesh-list.md).

| Reached from the arrival | Dungeons |
|---|---|
| 90 % and over (21) | Nochsana, Asteria, Chamber of Roah, Kysis, Miren and Krotan Chambers and their six barracks, Udas Temple, Beshmundir Temple, Ophidan Bridge, both Danuar Reliquaries, Aetherogenetics Lab, Indratu Fortress, Fire Temple, Padmarashka's Cave |
| 50 to 89 % (9) | Lower Udas Temple, Chantra and Terath Dredgion, Abyssal Splinter, Rentus Base and its occupied version, Azoturan Fortress, Theobomos Lab, Adma Stronghold |
| 1 to 49 % (15) | Sulfur Tree Nest, Left Wing Chamber, Steel Rake, Esoterrace, Tiamat Stronghold, both Dragon Lord's Refuges, Eternal Bastion, The Hexway, Sauro Supply Base, both Danuar Sanctuaries, Drakenspire Depths, Draupnir Cave, Alquimia Research Center |
| 0 % (5) | Dark Poeta, Right Wing Chamber, Unstable Abyssal Splinter, both Illuminary Obelisks |
| No arrival point found (4) | Dredgion, Idgel Dome, both "Lucky" copies: their arrival is not in `portal_template2.xml`, and is probably put by the instance's Java handler |

**What the failures look like.** The arrival's own region, from `seams`, says which kind:

- **Sealed yard** (the region holds a few hundred to ten thousand cells and touches nothing): Dark Poeta (380 and 7700 cells), Right Wing Chamber (2387), Steel Rake (132 and more), Unstable Abyssal
  Splinter (about 2000), Adma Stronghold (919). The yard is shut by static geometry, a gate model or a wall, and the arrival rules do not open it.
- **A big region and a tail left over** (84 thousand to 400 thousand cells, with thousands of cells that miss by a step): Tiamat Stronghold, Sauro Supply Base, Eternal Bastion, Lower Udas Temple, Rentus
  Base, Azoturan Fortress and the other middle band. Raising the step tolerance to 1.5 m, tried on ten of them, adds 1 to 20 % to the region and no more, so the tail is not steps taken a little too high:
  it is gated ground, lifts and the rest. Some of it is right to leave unreached.

So the meshes are good for the first group, usable on the reachable part for the second, and not for the rest until the seals are looked at map by map. The step tolerance and the 3 m drop have both
been measured and neither is the answer.

## Open questions

1. **Which dungeons are bots to run first?** The 21 at 90 % or more are ready as they are: the three chambers and their barracks (six of them, three geometries), Udas Temple, Beshmundir, Ophidan Bridge,
   Fire Temple and the Reliquaries. Looking at the other 33 is work with no bot waiting for it, so it is better done for the ones chosen than for all.
2. **The four without an arrival**: read the start position from the instance handlers (`portToStartPosition` and the like), or route from the largest region instead?
3. **Opening a mesh in the server (milestone 6)** has not been tried: a mesh is read when the first bot is on its map, and no bot is in an instance yet. The format is the open world's, so the risk is low.
4. **Disk**: `data/navmesh` is 2.1 GB, most of it the debugging images the tool writes beside each mesh. The meshes themselves are the `.nav` files; the images can be deleted.
