# Plan: walking over steps of one to two metres

Status: in progress: step 1 (the census, `NavmeshTool <map> steps`) built and run on two maps on 31 October; the links themselves are not written.

## The problem

The mesh joins two cells when their heights differ by at most 0.15 m plus the slope over half a metre (`BotPathFinder.STEP_TOLERANCE`), about 0.9 m on a steep cell. A riser of one to two metres (a stair modelled as one block, a low ledge a player jumps) cuts one ground from the other. Three of the places the tester reported on 31 October are that: Pandaemonium's Airship Dock (a 1.07 m riser at the foot of the ramp, 2 m risers along the terrace), the east of Altgard (2 m, 13 590 edges) and the foot of the Fire Temple of Morheim (2 m, 132 edges).

## Step 1, the census (built)

`NavmeshTool <map> steps` lists, per 10 m square, the edges where two grounds of 400 cells or more meet along a riser of 0.65 to 2.1 m, how many, the heights, the sizes of the two grounds, and whether a walked point (the map's `.walked` and `.patrols` files) lies within 8 m. Read only.

Results, 31 October:

| Map | Squares | With a walked point within 8 m | The largest places |
|---|---|---|---|
| Pandaemonium 120010000 | 366 | about 20 (of 247 points read) | 376 edges at 1256 1375 (z 228 to 232); the terrace of the dock at 982 1196 (z 198 to 200, grounds of 527 539 and 15 198 cells, 292 edges) |
| Altgard 220030000 | 544 | **0** (336 points) | the plateau of the cave at 1636 to 1662, 1778 to 1841 (z 251.6 to 253.7): about 12 000 edges against a ground of 8.6 million cells |

**What it says**: risers pile up at 2.0 to 2.1 m, the cap of the census, so they are floors, terraces and ledges everywhere, and a blanket "walk over a step of up to 2 m" would open cliffs and floors of buildings by the hundred. Only a riser somebody has walked is known to be a step. The walked data is too thin for that: 247 points on Pandaemonium, one every 5 to 7 m (the recorder samples every 2 seconds), and none on Altgard's cave.

## What follows

1. **Denser walked ground**: sample every second (and on any change of heading over 30 degrees), keep the points of every player rather than the first on a map, and let the census count a walked edge by a point within 3 m. This makes the data the decision rests on.
2. **Step links** (walking, both ways, one per walked riser, of at most 2.1 m, between grounds of 400 cells or more) written with the glide links, read by the planner as a hop of the walking search. Only where a walked point or a golden route crosses the riser.
3. **By hand where nobody has walked**: Altgard's cave and the Fire Temple wait for the tester's notes (`//bot note` at both ends of the passage), each one a golden route first and a ramp or a link after.
4. **Checks**: `tools/check-golden-routes.ps1` (the three dock routes and the ten others) and the census itself before and after: the number of walked squares left cut.
