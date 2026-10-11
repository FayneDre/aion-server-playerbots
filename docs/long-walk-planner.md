# The long walk: rough route, then stretches

Status: built and measured offline on 11 October (obelisk audit and sixteen dungeons); not yet watched in game.

A route longer than 150 m, or a short one the fine search gives up on, is planned in two passes (`BotPathFinder.findLongPath`): a rough route over the 4 m
grid, then a fine search from one guide point to the next, about 40 m apart. Everything below is about the rough route promising what the fine search can
deliver: when it does not, a stretch of 40 m runs out of 150 000 nodes and the whole journey gives up.

## What a node of the rough route is

A node is a 4 m cell **and a stretch of ground in it**: the walkable surfaces of the cell joined to each other by a step, found by a union over its 64 columns
the first time a search reaches the cell (`levelsOf`, kept for that search only, about 250 bytes a cell). At most 16 stretches a cell, the largest; the rest are
crumbs. Two stretches of neighbouring cells are joined when some cell of the first, along their border, has a neighbour in the second within a step
(`levelsMeet`). A guide point is a walkable cell of the stretch the route went through, the one nearest the middle of the cell, and the fine search aims at it
within 3 m of height.

What it replaced, and what each change fixed:

| Before | Fault | Where it showed |
|---|---|---|
| A level was a band of heights within 1.5 m | Two terraces a metre apart with a wall between were one level | 310100000: a 36 m stretch was 359 m round |
| Neighbouring levels joined when their heights were within 1.5 m | A bank or a low wall along the border was crossed | Esoterrace: 82 m across, 517 m round |
| A guide point was the middle of a band, snapped at any height | It landed on the floor above when its own was not walkable there | Esoterrace stretch 20 aimed at z 275 and got z 312 |
| The search kept to the cells the grid lists a region in | The grid lists a region only where it covers a fair part of the cell | Altgard lost a route from two obelisks |
| When no levelled route existed, the older search ran | It routed over any region both ends shared | Danuar: the plane at height zero under the map |

The older search (the grid of regions, no heights) now runs only when the levelled one could not answer: out of nodes, or an end with no stretch in its cell.

**A budget of 1.5 s.** Without the region filter, a search for a cliff top from its foot went through the whole ground of Eltnen before it could say
there was no way up: 12 to 18 seconds, on a bot tick thread, measured in the server the evening it was deployed. Out of time, it now answers "gave up"
without falling back to the older search. Timed against the planner of the morning on 19 slow searches taken from the server's log: the same ones give up
in both, in 35 s against 34 in all, and the worst of them takes 2.3 s instead of 18. Searches over a second are logged with their ends
(`Path search on map … took … ms`).

## Glides after a walk that gave up

`findPath` asked the glide links only after the walk *proved* there was no way. On a large stretch of ground the walk never proves it, it runs out of room
first: 310100000 had 206 of its 207 spots within walking and gliding of its arrival and reached 80 %. The glides are now asked after any empty walk, and the
answer is a proof only when both searches proved it.

## Measured

- **Obelisk audit**, every open-world map, against the code before: 151 routes found, 18 refused, 38 undecided became 173, 18 and 16. No obelisk lost a route.
  It takes 4 min 30 instead of 3 min 40, mostly routes that now finish instead of giving up early.
- **Dungeons**: see the table in [dungeon-navmesh-plan.md](dungeon-navmesh-plan.md).

## Tools for the next one

`-Dnavmesh.pathDebug=true` with `NavmeshTool <map> path ...` prints each stretch that failed, where its ends were snapped and how many nodes it spent.
`-Dnavmesh.maxNodes=2000000` then says how long the way round really is. The root `pom.xml` skips the tests unless `-Dmaven.test.skip=false` is given.
