# Dungeon navmeshes: plan

Status: plan only, nothing built. Scope decided on 2026-10-10: the **PvE dungeons and raids**, in every version (normal, hard, legion barracks). That is the 77 ids of the first
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

0. **Can a mesh serve several ids?** Read `NavmeshService`: if the id is only the file name, copying `<id>.nav` is enough, and the batch builds 63 meshes and copies 14. If an id is written inside,
   the generator takes a list of ids for one geometry. Done when it is settled and written here.
1. **One small interior, looked at.** Right Wing Chamber (300090000, 12 KB of geometry, 512 m), then Dredgion (300110000, 67 KB). Run `tools/navmesh.ps1 <id>`, open the images, run `audit` and
   `components`. Decide the door question and the floor question here. Done when the images and the numbers say the mesh is walkable, written with the figures as for Sanctum.
2. **The reachability check.** A new `NavmeshTool <id> reach`: from each entry point of the id to every spawn of its instance files, how many are joined to the entry and by how long a route, and the
   ones that are not, with their coordinates. The unreachable ones are then either a closed door (expected, noted) or a fault in the mesh. Done when it runs on the two meshes of milestone 1.
3. **The large ones, alone.** The six oversized, one at a time with the heap the open maps use, noting time and memory. If one does not fit, the grid is cropped to the geometry's own extent
   instead of `world_size`.
4. **The batch.** The 63 geometries, smallest first, each followed by `audit` and `reach`, into a table in [instance-navmesh-list.md](instance-navmesh-list.md): built, median wander, share of spawns
   reached. Meshes that fail are listed with the reason, not hidden.
5. **The copies**, per milestone 0, and the same table for the ids they serve.
6. **Restart the game server** and check that a mesh opens: it is read when the first bot is on its map, so nothing shows until a bot is in an instance. The reading is tested without one.

## Open questions

1. **Doors**: treated as open, as closed, or per door by what opens it? The data says which doors exist, and the mesh cannot say which a bot may pass. The default proposed: open all of them, with the
   reach report naming the gates, since a bot with no way to open a door would be better told by the route than by a wall.
2. 14 of the 77 carry a terrain heightmap (Nochsana Training Camp, Dark Poeta, Asteria Chamber, Beshmundir Temple, Haramel, Esoterrace, Sealed Danuar Mysticarium, The Eternal Bastion, Ophidan Bridge, Raksang Ruins, Sauro Supply Base, Idgel Dome, Lucky Ophidan Bridge, Padmarashka's Cave), the rest only placed meshes. Does the generator's terrain path matter for those, or only the meshes? The first mesh of each kind should be looked at.
