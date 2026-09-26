# Navmesh plan

Replacing reactive steering with real path planning. **N0 to N6 done** for navigation. The vendor run is half written, see [roadmap.md](roadmap.md).

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
3. Erode the result by a body's width. Skipping this was the one mistake that showed in game: without it a bot climbed a fallen trunk by its branches, because the grid only ever asked whether ground was flat, never whether a body fitted. Eroding removes narrow footholds by construction, with nothing to recognise.
4. Mark a span walkable when its slope is ≤ 45° (matching `findMovementCollision`), it has at least 2 m of headroom, and no WALK volume covers it. Slope comes from the triangle's own normal for meshes, and from the height gradient across the cell for terrain.
5. Link neighbouring spans where the step is ≤ ~0.5 m, so stairs connect and ledges do not.
6. Write a compact binary per map into `data/navmesh/<mapId>.nav`.

The format is tiled, 64 columns square. Three decisions carry it: heights are quantized to 5 cm, which halves them into unsigned shorts; each tile carries an offset per **row** instead of per column, turning 151 MB of offsets into 1 MB; and each tile is **compressed separately** behind a directory at the head of the file, so the server reads a tile without reading the map. Opening a map is then instant, and a bot working a camp holds four tiles instead of 117 MB.

Rays are independent, so the whole thing parallelises. Only the maps in use need generating at first — Poeta (210010000) is the test bed.

### Runtime

- Load lazily, per map, on first use. Keep it out of the startup path.
- Start and goal **snap outwards** to the nearest walkable cell, up to six metres. A player stands on a rock or against a wall more often than not, and without snapping "come here" simply found no route and fell back to walking blind. The last few metres are the reactive layer's job anyway.
- `BotPathFinder.findPath(from, to)` — A\* over spans, then string pulling into a waypoint list. String pulling replaced the funnel: without polygon portals there is nothing to funnel through, and dropping every waypoint a straight walk can skip gives the same result in a fraction of the code.
- It returns a `Route` that says whether the search **gave up** rather than proved the place unreachable. A bot must treat those differently: one means try another way, the other means stop trying.
- `BotMoveController` walks waypoints through its existing leg machinery: arrival, stuck detection and the client packets all stay as they were, and the controller only gained the notion of a current waypoint separate from the final destination. This is the seam the earlier design was built around, and it held.
- `BotGeoHelper` keeps its corridor probe for the last few metres and for dynamic obstacles (gatherables, other players), which no static mesh can know about.

## Milestones

| | Goal | Verification |
|---|---|---|
| N0 | Offline tool loads one map's geometry and reports triangle and placement counts | **Done.** 20028 meshes and 419707 entities on 151 maps, plus 919 town level clones, matches the server's 20028 and 420626 exactly |
| N1 | Rasterise one map to spans, dump images | **Done.** Poeta is 6144x6144 columns holding 39.4 M surfaces, rasterized in 1.4 s. The height image shows its valleys, ridges and river, the structure image shows its buildings clustered in the built up area |
| N2 | Walkability rules (slope, headroom, WALK volumes) | **Done.** 25.7 M of Poeta's 39.4 M surfaces are walkable, and the blocked ones trace its ridges and cliffs exactly. Its tree stumps, the kind of low prop the runtime probes cannot see at all, each block 8 to 15 cells and leave the ground under them unstandable for want of head room |
| N3 | Binary format, write and read back | **Done.** Poeta is a 38 MB file, written in 4.3 s, read back in 0.35 s into 117 MB of heap, with all 39.4 M surfaces compared and no mismatch |
| N4 | A\* plus string pulling, `NavmeshTool <mapId> path <x1> <y1> <x2> <y2>` draws the route | **Done.** A 20 m route between two houses bends around the first one instead of crossing its wall, in 8 ms. Routes of 50 to 100 m take 8 to 17 ms and come back as 2 to 4 waypoints |
| N5 | `BotMoveController` follows a planned route | **Done.** Bots walk around the fallen trunk they used to wedge themselves into, and a camp holds three or four tiles. Two fixes made it work: snapping start and goal to walkable ground, and eroding by a body's width |
| N6 | Long route inside a map, then the vendor run | **Navigation done.** A 1446 m crossing of Poeta plans in 1.4 s and walks 1708 m, 46 waypoints. Long journeys are planned off the movement threads, the bot setting off straight away and adopting the route at the end of its current leg. The vendor run itself is not started |

## Running it

```powershell
.	ools
avmesh.ps1 210010000
.	ools
avmesh.ps1 all
```

It runs from the server directory but against the jar built in this repository, and never writes into the server installation. Replacing a jar under a running JVM breaks it: classes load lazily, so anything not yet loaded is gone. That is a `NoClassDefFoundError` minutes later, in whatever task happens to need a new class first. `GeoDataReader` duplicates the format knowledge of `GeoWorldLoader` on purpose, because the offline tool cannot pull in `DataManager` and the world model. `all` exists to catch format drift: its totals must keep matching the server's startup log.

## Checking a result

`NavmeshTool <mapId> <x> <y>` prints one column, and `NavmeshTool <mapId> <text>` does the same around every prop whose model name contains that text. In game, `//coords` gives the position to check.

These are for validating the generator, not for surveying the world: one known obstacle is enough, because the same code rasterizes all of them.

## Risks

1. **Memory, addressed in N3.** The generator's own heightfield still needs `-Xmx4g`, but that is offline. At runtime tiles are read as they are walked into, so a camp costs a few megabytes rather than the 117 MB a whole map holds. Sparse storage was the wrong answer: with 1.04 surfaces per column the grid is dense, and the cost was the bookkeeping, not the data.
2. **The span format is a new file format to maintain.** Version it from the first byte, and keep the generator able to rebuild everything.
3. **Multi-level geometry** (bridges, buildings, the Abyss) is where span linking gets subtle. N1's per-level images are what makes this debuggable.
4. **Dynamic obstacles stay unsolved by the mesh** — players, npcs, gatherables, doors. The existing reactive layer keeps handling the last few metres.
5. **Walkable ground comes in islands.** `NavmeshTool <mapId> components` colours them and says which one a spot is on. Poeta's playable valley is one island of 1071 by 1313 m; the rest of the map is other valleys, genuinely cut off by ground steeper than the 45° the engine itself refuses. A route between two islands does not exist, and no amount of searching will find one — checking this first saves hours, as it did here: a supposed pathfinding failure turned out to be a destination with no walkable ground at all.
6. **A rough way through is not a promise.** The coarse grid calls a 4 m cell routable on a quarter of its ground, so it crosses places the detailed grid does not: a stream, a ledge, a gap erosion closed. Refinement therefore skips guide points it cannot reach, and the whole plan is bounded by a 250 ms deadline, because it runs on a movement thread and a hopeless route must fail fast rather than eventually. Beyond roughly 300 m that fragmentation wins, and the answer is fixed waypoints between regions rather than more search.
7. **Flight.** Aion is three dimensional and a ground navmesh ignores it. Out of scope here; flying bots are a separate design.
