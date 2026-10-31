# Checking the meshes against what a tester walked

**Status:** built, unverified: the walked-ground recorder has not run on a live server yet; the golden routes, the note's route lines and the patrol-route check have.

Written on 31 October 2026, after four notes about Morheim's cave each answered by a box written by hand, one of them shutting what the one before had opened.
Three tools, each for a way that kept costing a round trip with the tester.

## 1. Golden routes: `tools/golden-routes.txt`

Routes a tester walked on foot, one a line: `map x1 y1 z1 x2 y2 z2 longestWalkInMetres   # what it is`. `NavmeshTool golden <file> [map...]` plans each **both ways** (a fix that
opens a passage one way and shuts the other is how one breaks) and fails on a route that is shut or longer than its limit.

- `tools/check-golden-routes.ps1 [-Maps 220020000]` runs it on the installed meshes; the server may be running.
- `tools/navmesh.ps1` runs it after it builds meshes, for the maps it built, and says so in red before the meshes are deployed.
- A note that is fixed adds its line. A route that fails today is not listed; it is named in the file's header (the one-cell pillar in Morheim, until walked ground covers it).

## 2. The note says what the planner makes of the walk

`//bot note` used to give positions; the diagnosis was then four commands by hand. It now adds, for each bot of the note on the player's map, a line

    BOT NOTE #n | <bot> to the player: walk of 166 m for 3 m in a line (x55.3), 18 waypoint(s), on ground that leads somewhere

(or `search gave up`, `no way proved`, `goal cut off`, and whether the bot stands on a pocket), and one for the player's own ground. A walk many times the straight line between
two spots a few metres apart is the signature of a wall between them: look at it with `NavmeshTool <map> slice`, `whyblocked`, `blockers`.

## 3. Walked ground: `data/navmesh/<map>.walked`

`WalkedTrails` (started with the bot service) samples every real player every two seconds; a player on the ground who has moved 1.5 to 12 m across and less than 1.2 m up or down since
the last sample leaves a point (x y z), one per metre of ground and height. Bots, flights, glides, teleports, falls and the dead leave none. No name is written.

The generator (`HeightfieldBuilder`) reads the file: a patch of floor 1.5 m across goes under each point, so a hole in the data has a floor, and at the end the surfaces round each point
(one cell, within 1.2 m of its height) that were ground at the sampling are given back whatever the head room, the walls and the erosion made of them. It prints
`Gave back the ground real players walked: N surfaces`. Tried on the Morheim pillar: eleven points down from it, and the route that was refused is 6 m.

- The file is only read when a mesh is built: walking changes nothing until `tools/navmesh.ps1` runs again (then the glide links are written again as ever).
- It grows with the places walked, about a line per square metre: keep it, it is data, not a log. A point written by mistake (a lift, a mount) leaves a floor where nobody walks; delete its line.
- Only what players walk is learned. What a bot should do but no player does (a glide from a cliff) is not.

## 4. Patrol routes: `NavmeshTool <map> walkers`

The data holds routes the designers had creatures walk (`npc_walker`, 5 887 routes, 103 000 steps a few metres apart; a map's are the `walker_id` of its spawn files). Each step is ground
that was meant to be walked, so two steps no short walk joins (three times the distance plus 8 m) are a cut. Unlike a scan of every pair of close points, which is mostly walls
of houses, the routes are almost free of false positives: of about 63 000 steps on the 22 open-world maps, **270 are cut (0.4 %)**, 110 places, 76 of them flat (a hole, a rock
lip, a corridor the erosion shut: the cave of Morheim was this) and 34 steeper than 20 degrees (a slope the mesh climbs only up to 45, 62 in Morheim's zone).

- `tools/check-walker-routes.ps1` runs it on every map of `tools/walker-routes-baseline.txt` and fails when one has more cut steps than its number there. Lower a number when a fix
  makes it true.
- Where the cuts are: Heiron 39, Morheim 43, Beluslan 36, Altgard 34 (a descent from 254 to 203 between 1600 and 1720 in x, 1735 and 1820 in y, probably the tester's cave),
  Eltnen 16 (256 steps with no ground: swimmers and fliers), Reshanta 16. A zone for Altgard's descent opened 7 of its 34 and was not kept: the steps at 44 to 56 degrees still
  fail, and the box is large enough to open cliffs; it wants a note from the tester at the entrance first.
- A step with no ground within reach of its height is counted apart (flying and swimming creatures), and is no failure.
- **Treating the cuts**: `NavmeshTool <map> walkers write` writes, along each cut step that stands on the ground (within 1 m of its height at both ends; a floor under a flier's route would be a ceiling
  over the ground below it), a point every half metre into `data/navmesh/<map>.patrols`, which the generator reads like the walked ground of players. `tools/patrol-ground.ps1` does it twice per map
  (a pass shows more ground that needs joining) and builds with the links; the server must be stopped. Tried on 14 maps on 31 October: 270 cut steps became 12, Morheim 43 to 0, Altgard 34 to 1,
  Heiron 39 to 1, Beluslan 36 to 1; the pairs of neighbouring monsters reached did not move (Inggison 2 309 before and after). **Test meshes need their `.glide` files**, written with `NavmeshTool <map> glides`:
  without them a map reads 24 pairs worse for nothing that was done to it, which looked like a regression until measured so.
- The `.patrols` files are data of the server, not of the repository: run the script again on a new install, and after changes to the generator the meshes keep them.

## What is still by hand

`MapOpenings` (a rock model that is no wall in a box, a floor patch, an unshaved box) holds Morheim's cave as of 31 October. Walked ground should make new entries unnecessary; once it
has run in the cave, the entries can be taken out one by one while `check-golden-routes.ps1` still passes.
