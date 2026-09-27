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
2b. **Separately**, mark every column any geometry *crosses*, walls included. This is not the same question as step 2 and must not share its code — see [The mistake that cost a day](#the-mistake-that-cost-a-day).
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

## The generator audits itself

A mesh cannot be judged by looking at it. The canopy fault generated cleanly, every total looked plausible, the images looked right, and it surfaced weeks later as "the bot takes the long way round". That complaint is a number, and a number can be checked before anyone plays.

Every generation now ends with an audit, and `NavmeshTool <mapId> audit` runs it alone. It plans 300 routes between walkable points 25 m apart: over that distance a straight line is usually the right answer, so a high median means the map is blocking ground that is not really blocked. Pairs more than 12 m apart in height are skipped, since cliffs and roofs legitimately have no short way across and would drown the signal.

Poeta, after the fix:

```
Audit of 279 routes over 25 m: median 1,00x straight line, 90th percentile 1,07x, 8 beyond 1,5x, 21 unreachable
  4,6x: 121 m for 27 m   NavmeshTool 210010000 path 2401 737 2389 759
```

The worst offenders are printed as ready-made `path` commands, so the next step is a paste away, and `inspect` on a point from that route names what sits above it. A median past 1.25x prints a warning naming the likely cause. The seed is the map id, so two runs of the same map are comparable.

The audit runs **twice**, at 25 m and at 250 m, because those are not the same question and the first cannot answer the second. A short route is one fine search; a long one is planned on the rough grid and refined stretch by stretch, and that refinement can fail everywhere while every short route on the map still reads as perfect.

It did exactly that. Poeta audited at a median of 1.00x and was declared healthy while **no bot could plan its way across it**:

```
Audit of 280 routes over  25 m: median 1,00x, 90th percentile 1,07x,  20 unreachable
Audit of 122 routes over 250 m: median 1,06x, 90th percentile 1,69x, 178 unreachable
```

Seven per cent unreachable up close, **fifty-nine per cent across the map**. Residents could reach the camp next door and never the village.

The cause was the rough grid disagreeing with the fine one. A 4 m coarse cell counted as routable when a quarter of its ground was walkable — a bit, nothing more — so a rough route crossed freely between two stretches of ground the fine grid keeps apart, refinement failed at the crossing, and the journey died after the search budget rather than after the question.

**The rough grid now carries a region, not a bit.** Every column is labelled with the stretch of walkable ground it belongs to, joining neighbours exactly as the path finder steps between them; each coarse cell takes the region holding most of it; and a rough route may only move between cells of the same region. That turns the rough grid from a hint into a promise — a rough route now stays inside one stretch of ground, and a stretch is by construction something a body can walk across.

The labelling lives in `Heightfield.regions`, where both the generator and the island report read it. Two routines answering that question separately is how the mesh and the engine came to disagree in the first place.

Measured on the four settlements of Poeta, twelve ordered journeys:

| | before | after |
|---|---|---|
| journeys that plan | **1 of 12** | **6 of 12** |
| time when they fail | 800–1100 ms | 90–300 ms |

Akarios village went from unreachable in both directions to 434 m from one camp and 853 m from another, planned in under a hundred milliseconds. What still fails is honest: the mushroom camp sits on a patch of ground 39 by 48 metres that touches nothing else, so there is no route to give.

A caveat on the long audit: it samples any walkable surface, rooftops and ledges included, so its unreachable share counts places no bot would start from. Sampling only from the larger regions would make that number mean what it looks like it means.

This is the check that was missing. **A future map is not finished when it generates; it is finished when its audit is boring — at every range.**

## Two questions, two answers, one routine — again

Blocking walls needed a second look almost as soon as it worked. `Heightfield.block` clears **every** surface of a column, which is exactly right for the no-walk volumes it was written for: those forbid a place outright. Reusing it for solid geometry made a tree's leaves, twenty-seven metres up, delete the ground beneath the trunk.

Measured on the spot a player was standing: the mesh called it `BLOCKED`, with surfaces at z=120.7 (the grass), z=148.2 and z=148.5 (the canopy). A route across that clearing took **55 metres to cover 19**, looping round open grass. After the fix it is 19 metres in two waypoints.

Two changes, both from notions the file already had:

- **An obstacle only clears the surfaces it stands in the way of.** A body on a surface at `z` fills up to `z + AGENT_HEIGHT`; the obstacle must reach into that space and rise more than a step above it. Anything wholly above is a roof to walk under.
- **A wall is steep, not merely tall.** Reading "tall" swept in the broad, gently sloped faces of canopies and roofs, whose footprint then blocked a wide disc of good ground. Steepness is measured against the same 45° `MAX_SLOPE_COSINE` the engine lets a body climb.

Walkable surfaces: 24,292,915 with no blocking at all, 22,923,050 with the whole-column version, **24,070,512** now. Walls still block — about 222,000 surfaces — and trees no longer do.

The lesson is the one below, word for word, and it took a second helping to land: **a routine that answers one question will be reused for another that merely sounds the same.** "Forbid this column" and "something solid stands here" are not the same sentence.

## The mistake that cost a day

Step 2 answers *"what can a body stand on?"*. A triangle contributes a surface to each column whose centre falls inside its footprint — and a vertical triangle has no footprint, so it contributes nothing. That is correct: nobody stands on a wall.

The same routine was then reused to answer *"what blocks a body?"*. It is the wrong question for it, and the failure is total rather than partial: **a fence, a palisade, a railing, the side of a crate are all walls, all vertical, all silently dropped.** Poeta carries 1007 parts flagged `WALK, PHYSICAL_SEE_THROUGH` — its camp barricades — and not one of them blocked anything.

The generated map therefore described a world without fences, while the engine's own raycasts described one with them. Every navigation symptom chased for a day came from that single disagreement: routes plotted straight through palisades, bots running along a fence unable to reach a point three metres beyond it, "walled in" reported while a perfectly good route sat in hand, bots wedging themselves into crates and snapping back. Worse, it was self-concealing — the reactive layer refused the impossible legs and made the bot merely *unreliable* instead of obviously broken, which is why it read as a hundred small bugs rather than one big one.

Two rules follow, both now in the code:

- **Obstruction is rasterised by footprint *and* by edge walking**, so geometry too thin to contain any cell centre still blocks the cells it crosses. For solids, only faces rising more than `STEPPABLE_RISE` (0.6 m) count, or every staircase in the game becomes a wall.
- **The collision mask is taken from `CollisionIntention.DEFAULT_COLLISIONS`, never restated.** It was restated once, as physical and doors, on the assumption that see-through volumes do not stop a body. They do. Binding to the engine's own constant is what stops the two from ever drifting again.

**Any change to the generator means regenerating every map.** A stale `.nav` file is indistinguishable from a correct one until a bot walks into something.

## Risks

1. **Memory, addressed in N3.** The generator's own heightfield still needs `-Xmx4g`, but that is offline. At runtime tiles are read as they are walked into, so a camp costs a few megabytes rather than the 117 MB a whole map holds. Sparse storage was the wrong answer: with 1.04 surfaces per column the grid is dense, and the cost was the bookkeeping, not the data.
2. **The span format is a new file format to maintain.** Version it from the first byte, and keep the generator able to rebuild everything.
3. **Multi-level geometry** (bridges, buildings, the Abyss) is where span linking gets subtle. N1's per-level images are what makes this debuggable.
4. **Dynamic obstacles stay unsolved by the mesh** — players, npcs, gatherables, doors. The existing reactive layer keeps handling the last few metres.
5. **Walkable ground comes in islands.** `NavmeshTool <mapId> components` colours them and says which one a spot is on. Poeta's playable valley is one island of 1071 by 1313 m; the rest of the map is other valleys, genuinely cut off by ground steeper than the 45° the engine itself refuses. A route between two islands does not exist, and no amount of searching will find one — checking this first saves hours, as it did here: a supposed pathfinding failure turned out to be a destination with no walkable ground at all.
6. **A rough way through is not a promise.** The coarse grid calls a 4 m cell routable on a quarter of its ground, so it crosses places the detailed grid does not: a stream, a ledge, a gap erosion closed. Refinement therefore skips guide points it cannot reach, and the whole plan is bounded by a 250 ms deadline, because it runs on a movement thread and a hopeless route must fail fast rather than eventually. Beyond roughly 300 m that fragmentation wins, and the answer is fixed waypoints between regions rather than more search.
7. **Flight.** Aion is three dimensional and a ground navmesh ignores it. Out of scope here; flying bots are a separate design.
