# Dungeon navmeshes: plan

Status: milestones 0 and 2 done, 1 half done (six pilot meshes built and measured, with what they show below); 3 to 6 not started. Scope decided on 2026-10-10: the **PvE dungeons and raids**, in every version (normal, hard, legion barracks). That is the 77 ids of the first
table in [instance-navmesh-list.md](instance-navmesh-list.md), which come down to **63 distinct geometries**: a mesh is built once and serves the ids that share it. Arenas, warfronts, housing
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
- **Sizes that lie.** Six of the 77 declare 2048 or 3072 m for a dungeon much smaller (Lower Udas Temple, Beshmundir Temple, Taloc's Hollow, Tiamat Stronghold, Sliver of Darkness, Space of
  Oblivion). The grid comes from `world_size`, so they cost the memory and time of an open map. Measured before the batch, not guessed.

## Milestones, in verifiable order

0. **Can a mesh serve several ids?** *Done: yes. `Navmesh.open` finds the file by the map id and only reads the id stored inside it into a field nothing uses, so `<id>.nav` can be copied under the other ids.*  Read `NavmeshService`: if the id is only the file name, copying `<id>.nav` is enough, and the batch builds 63 meshes and copies 14. If an id is written inside,
   the generator takes a list of ids for one geometry. Done when it is settled and written here.
1. **One small interior, looked at.** *Done for six, see "The pilot". Time: six dungeons in 82 s, a mesh of 0.6 MB for the smallest.*  Right Wing Chamber (300090000, 12 KB of geometry, 512 m), then Dredgion (300110000, 67 KB). Run `tools/navmesh.ps1 <id>`, open the images, run `audit` and
   `components`. Decide the door question and the floor question here. Done when the images and the numbers say the mesh is walkable, written with the figures as for Sanctum.
2. **The reachability check.** *Built: `NavmeshTool <id> reach` and `tools\navmesh.ps1 <ids> -Reach`, with `InstanceReach` and three unit tests of the file reading. A spot is moved onto the nearest walkable ground within 3 m before it is asked about, and one with none is counted apart ("no footing") and not held against the mesh. Exit code 1 when anything was not reached.*  A new `NavmeshTool <id> reach`: from each entry point of the id to every spawn of its instance files, how many are joined to the entry and by how long a route, and the
   ones that are not, with their coordinates. The unreachable ones are then either a closed door (expected, noted) or a fault in the mesh. Done when it runs on the two meshes of milestone 1.
3. **The large ones, alone.** The six oversized, one at a time with the heap the open maps use, noting time and memory. If one does not fit, the grid is cropped to the geometry's own extent
   instead of `world_size`.
4. **The batch.** The 63 geometries, smallest first, each followed by `audit` and `reach`, into a table in [instance-navmesh-list.md](instance-navmesh-list.md): built, median wander, share of spawns
   reached. Meshes that fail are listed with the reason, not hidden.
5. **The copies**, per milestone 0, and the same table for the ids they serve.
6. **Restart the game server** and check that a mesh opens: it is read when the first bot is on its map, so nothing shows until a bot is in an instance. The reading is tested without one.

## The pilot

Six dungeons, built and put through `reach`, before and after one change to the generator: **in an instance, every placement the engine can put up or take down is left out**
(`GeoPlacement.type` other than 0: the doors a script opens, barricades, boxes, statues), because the open or shut state of a dungeon is a fact of a run and a mesh with them as walls
shuts the party's own way in. The open world is unchanged. Share of the monsters' spawn spots reached from the arrival point:

| Dungeon | Before | After | What is left |
|---|---|---|---|
| Nochsana Training Camp (300030000) | 54 % | **98 %** | |
| Udas Temple (300150000) | 69 % | **91 %** | |
| Haramel (300200000) | 66 % / 3 % | 74 % / 3 % | the second portal is not an arrival point: the list holds two per map |
| Asteria Chamber (300050000) | 0 % | 0 % | the arrival platform is 2 m above everything around it, so walking down is a step the finder refuses (1.1 m over) |
| Dark Poeta (300040000) | 0 % | 0 % | both arrival points are in sealed pockets (377 and 7700 cells): a plateau ringed by steep ground and by authored no-walk volumes, 1178 of them in this map |
| Right Wing Chamber (300090000) | 0 % | 0 % | a static gate model in the geometry shuts the hall the party arrives in; the "Stone Gate" next to it is a portal (`ai="portal"`), not a door |

So the policy fixed what it could (doors and props that come and go) and left three different faults, none of which a rule about doors reaches:

- **A drop.** Players walk off a platform two metres up; the path finder allows a step of about 0.9 m either way. Allowing a longer way down in instances only (no fall damage under a few metres)
  would join Asteria. To decide: how far, and whether a bot should be told it is a drop.
- **Authored no-walk volumes** used as boundaries, in Dark Poeta by the hundred. They are right to keep bots out of the places they fence; they are wrong where they fence the way in.
- **Static geometry that is a closed door** (the Right Wing gate). Nothing in the data says so; only the arrival and the spawns do, which is what `reach` is for.

## Open questions

1. **Drops**: allowed in instances, and to what height?
2. **No-walk volumes in the arrival area**: ignored within some distance of an arrival point, or per map by hand? A ring around the arrival yard is the usual shape.
3. **Arrival points**: `portal_loc` holds every portal of a map, not only the ones a party appears at. Is there a data file for the arrival point (the instance's start position) to use instead?
4. **Heightmaps**: 31 of the 77 carry one, often a file shared by several ids and flat; the rest are meshes alone. Both kinds have worked so far, and the first mesh of each deserves its images looked at.
