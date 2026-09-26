# Navmesh plan

Replacing reactive steering with real path planning. **N0 and N1 done**, the rest is the design.

## Why

[navigation-prototype.md](navigation-prototype.md) walks as far towards a goal as a ray allows, sidesteps, and gives up. In play it fails three ways, all observed:

- **Obstacles under a metre** (a log, a branch) — every geo probe is raised `COLLISION_CHECK_Z_OFFSET` (1 m) at both ends, so the server walks through what the client blocks, and the correction reads as a teleport.
- **Concave geometry** — a dead end or a U-shaped wall makes reactive steering bounce between two detours until the anti-stuck timeout fires.
- **Anything beyond one sidestep** — buildings, ravines, and any route to a town. This is what blocks the vendor run, and later RvR.

A navmesh fixes all three, because the route is known before the first step.

## The data is already there

An earlier note in navigation-prototype.md claimed the engine does not expose the collision data needed. That was wrong: the **ray API** cannot be cast low, but the **geometry** is fully loaded. `GeoWorldLoader` reads:

| File | Content |
|---|---|
| `data/geo/models.mesh` | Named triangle meshes: vertices, indices, material id, collision intentions |
| `data/geo/<mapId>.geo` | Placements of those models: position, 3x3 rotation, scale, despawnable type |
| `data/geo/<mapId>.png` | Terrain heightmap (16 bit) and material map |

159 MB total, 78 maps with a heightmap. Everything the client collides with is in there, at full resolution — including the log.

**`CollisionIntention.WALK`** ("Walk/NoWalk obstacles") is worth noting: the client authored explicit no-walk volumes, and the engine only uses them in `WalkManager.RANDOM_WALK_GEO_FLAGS` for npc random walk. Bot probes use `DEFAULT_COLLISIONS`, which excludes them. Generation must include WALK.

## Approach: walkable spans, not polygons

Recast-style polygon navmesh generation (voxelise, region-grow, contour, triangulate) is the industry answer and a large amount of subtle code. **Stop after the first half.** Rasterise each map into columns of walkable spans, then run A\* over the grid.

- **Simpler**: no contour tracing, no polygon merging, no half-edge bookkeeping.
- **Debuggable**: a span grid renders as an image, so a generation bug is visible rather than inferred.
- **Cheap enough**: a 2 km map at 0.5 m cells is 4000x4000 columns. One walkable height per column is ~16 MB as floats, and multi-level maps only add spans where floors overlap.

The cost is path quality: grid A\* produces staircase routes. Funnel smoothing (string pulling) over the resulting corridor fixes that, and is far less code than polygon generation.

### Generation

Offline, as a `main` reusing `GeoWorldLoader` so it reads exactly what the server reads:

1. Load one map's meshes, placements and terrain.
2. For each column on a 0.5 m grid, cast a downward ray and record every surface hit: that is the span list. Terrain gives the base surface, meshes give floors, roofs and props.
3. Mark a span walkable when its slope is ≤ 45° (matching `findMovementCollision`), it has at least ~2 m of headroom, and no WALK volume covers it.
4. Link neighbouring spans where the step is ≤ ~0.5 m, so stairs connect and ledges do not.
5. Write a compact binary per map into `data/navmesh/<mapId>.nav`.

Rays are independent, so the whole thing parallelises. Only the maps in use need generating at first — Poeta (210010000) is the test bed.

### Runtime

- Load lazily, per map, on first use. Keep it out of the startup path.
- `BotPathFinder.findPath(from, to)` — A\* over spans, then funnel smoothing into a waypoint list.
- `BotMoveController` walks waypoints **unchanged**: legs, arrival, stuck detection and the client packets all stay as they are. This is the seam the current design was built around.
- `BotGeoHelper` keeps its corridor probe for the last few metres and for dynamic obstacles (gatherables, other players), which no static mesh can know about.

## Milestones

| | Goal | Verification |
|---|---|---|
| N0 | Offline tool loads one map's geometry and reports triangle and placement counts | **Done.** 20028 meshes and 419707 entities on 151 maps, plus 919 town level clones, matches the server's 20028 and 420626 exactly |
| N1 | Rasterise one map to spans, dump images | **Done.** Poeta is 6144x6144 columns holding 39.4 M surfaces, rasterized in 1.4 s. The height image shows its valleys, ridges and river, the structure image shows its buildings clustered in the built up area |
| N2 | Walkability rules (slope, headroom, WALK volumes) | The known log and the known dead end appear as blocked |
| N3 | Binary format, write and read back | Round trip is identical, load time under a second |
| N4 | A\* plus funnel smoothing, `//bot path <x> <y>` prints the route | A route around a building, not through it |
| N5 | `BotMoveController` follows a planned route | A bot walks around the log instead of into it |
| N6 | Long route inside a map, then the vendor run | A bot reaches a town npc and comes back |

## Running it

```powershell
.	ools
avmesh.ps1 210010000
.	ools
avmesh.ps1 all
```

It runs from the server directory against the deployed jar, so deploy after changing the generator. `GeoDataReader` duplicates the format knowledge of `GeoWorldLoader` on purpose, because the offline tool cannot pull in `DataManager` and the world model. `all` exists to catch format drift: its totals must keep matching the server's startup log.

## Risks

1. **Memory, confirmed by N1.** Poeta needs ~160 MB of surfaces and ~150 MB of column offsets, and it is a small map. The offsets array is the problem: one int per column whether or not anything is there. N3 must store columns sparsely, and generation already needs `-Xmx4g`.
2. **The span format is a new file format to maintain.** Version it from the first byte, and keep the generator able to rebuild everything.
3. **Multi-level geometry** (bridges, buildings, the Abyss) is where span linking gets subtle. N1's per-level images are what makes this debuggable.
4. **Dynamic obstacles stay unsolved by the mesh** — players, npcs, gatherables, doors. The existing reactive layer keeps handling the last few metres.
5. **Flight.** Aion is three dimensional and a ground navmesh ignores it. Out of scope here; flying bots are a separate design.
