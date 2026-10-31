# Mesh defects: what to correct for the meshes to be as exact as they can be

Status: done (1 November): options A and C are on by default and deployed since 31 October (section F); options B, E and F are closed, not taken; the Morheim cave is fixed by hand and checked by a tester; the long tail of cuts is dispersed and mostly not a mesh fault. Places that wait for a tester's note (Altgard's cave, the Fire Temple, Pandaemonium's dock) are in [step-links-plan.md](../plans/step-links-plan.md).

Written on 30 October 2026 after a tester reported bots that cannot get past the entrance of a cave in Morheim (and said the same happens in a cave of
Altgard). The planner proved the way shut, in 55 ms, so the fault is in the mesh and not in the search budget. Everything below is measured unless a
line says it is a lead.

## How it was measured

`NavmeshTool <map> fieldbreaks` (new, `tool/FieldBreaks.java`): every pair of monster spawn spots within a bot's look-around radius (25 m across, 8 m up),
not a sample, planned on the map's mesh; the pairs the planner proves apart are grouped by the two stretches of ground they stand on, and for each group
the place where the two come closest and the models placed there are listed. A stretch of fewer than 100 cells is an **islet**. `fieldreach` (older)
samples 400 pairs and prints ten. Output per map: `<scratchpad>/breaks/<map>.txt`; `220100000` is a copy of `210090000` and counts once.

On 20 maps: **818 pairs refused**, 335 end on an islet, 159 lie between two stretches of 100 cells and more, the rest are not placed on two regions
(and `210070000`, `220080000` ran out of memory at 1.4 GB in the step that rebuilds the geometry).

## A. Defects shown

1. **The ramp at the cave entrance in Morheim (220020000), about 776 342.** The ground at the entrance (z 517.9 at 772 332), a fragment of the ramp
   (region of 645 cells, z 519.3 at 774 337) and the cave (437 000 cells, z 522.4 and up) are three pieces that do not join. One placed model covers the ramp,
   `bu_d_outpostcave_02b.cgf`, with faces of 46 to 58 degrees. Raising the building slope limit of the map to 62 degrees (as for Teminon, `MapSlopes`) made
   two surfaces walkable and **did not join the pieces**: the route is still "No route exists". Around the ramp 18 to 60 of the 169 cells within 3 m
   are blocked. Found with `NavmeshTool <map> whyblocked <x> <y> <z>`, which says which step of the generator takes the ground at a spot:
   (a) the ramp cells are removed by the **erosion** by a body's width (one cell, 0.5 m, in the open world; none in the instances), and a mesh built
   with `-Dnavmesh.erode=0` joins the ramp foot to the cave (a route of 47 m); (b) the 18 m between the spot where the bots stand (766 314) and the ramp
   foot (771 330) are shut at the **walls** step by the steep faces of `na_d_icerock_b_04d.cgf` (columns 767 318 and 768 322), and that stays shut
   without erosion. Morheim without erosion: pairs refused 205 to 19, but the entrance route still fails.
2. **Islets under monsters: 335 pairs.** A spawn spot falls on a patch of a few cells (1 to 17 at Morheim) that props isolate: tents
   (`bu_d_lehpartent_02b` alone: 6 breaks, 38 pairs at Morheim), fires, pots, barrels, sacks, fences. The large ground is 1 to 3 m away.
   Done on 30 October (`BotPathFinder.findPath`, the mesh untouched): a goal on an islet is aimed past, at the nearest large ground within 4 m. Pairs
   refused 818 to 562 over 20 maps; Morheim 205 to 81, of which 40 are refused both ways and 41 only from the monster's islet, which a bot never starts
   from; the cuts between large grounds stay at 159. Left: the islets with no large ground within 4 m, such as Altgard's (`whyblocked` there: the
   walls step takes 331 walkable surfaces within 6 m to 194, the erosion to 77).
3. **The plateau of 210030000: 40 pairs.** 2055 cells joined to the main ground at 5.1 m across and 4.0 m up, at the foot of `na_l_rockybig_03b.cgf`. Decide
   whether it is a cliff (then bots must leave those monsters alone) or a missing link.
4. **Scattered cuts, about 120 pairs.** Underwater stairs (`bu_ru_ldf5under_waterstair*`, 7 and 4 pairs), snow cliffs (`na_d_snowrock_cliff*`),
   a snow tent (`bu_d_lehpartentsnow_01a`, 12 pairs), `walk_obstacle4_08m` (14 pairs on `210040000`: an authored no-walk volume, which is meant).
   There is no model that dominates: each is its own case.

## B. Leads, not yet checked

5. **Searches given up for budget, not refused**: 84 pairs on `210040000`, 55 on `220080000`, 25 on `210050000`. A planner matter more than a mesh one; see
   whether the mesh has zones where the search blows up.
6. **Spots without ground under them** ("without footing"): 110 on `220080000`, 61 on `600100000`, 21 on `210020000`, 20 on `220070000`. The data's, or holes.
7. **Detours the audit reports**, such as 438 m for 25 m (17.6 times) from 417 2448 on Morheim and 208 m for 26 m: shortcuts the mesh does not see.
8. **Walls crossed.** The audit finds stretches on foot through a placed model at a body's height: `bu_d_df2opground_18a.cgf` and `bu_l_ruinmark_02b.cgf` on Morheim.
9. **The 28 "could not move" positions of the window of 30 October** (19:37 to 20:07), among them 2327 728 for two bots. `NavmeshTool <map> stuck <file>`
   says whether each is on footing or inside geometry.

## C. Not looked at yet

10. The 45 dungeon meshes (`NavmeshTool <map> reach` and `gaps` exist; not rerun).
11. `210070000` and `220080000`, with more memory and the game server stopped.
12. The cave of Altgard: no coordinates, and the probe at the guessed spot found no placed model. A `//bot note` on the spot would give them.
13. Glide, flight and ride links (`glidecheck`, `flights`, `rides`).
14. What spawns after the generation (doors, objects), which only the probes cover.

## E. Options for the erosion (30 October)

The erosion by a body's width (one cell, 0.5 m, in the open world; none in the instances) is the largest cause of refused pairs on the maps measured: with
`-Dnavmesh.erode=0` Morheim goes from 205 refused pairs to 19, `210040000` from 81 to 20. It also lets routes through walls: `110010000` goes from 0 to 34
stretches through a placed model at the audit, `210040000` from 2 to 10 on long routes, `210050000` from 6 to 14 (the reason it was added on 18 October, for
the citadel of Verteron and the castle of Teminon). So zero everywhere is measured and is not the answer. Nothing below is measured yet; the order of
preference is a guess.

| | Principle | For | Against or unknown |
|---|---|---|---|
| **A. Erosion that keeps passages** | Erode everywhere, then give back the cells taken that joined two grounds joined before. | Walls stay kept off; a passage comes back only where one is needed. | To write and measure. A passage given back may run close to a wall. |
| **B. Erosion as a cost, not a hole** (not taken) | Eroded cells stay walkable at a high cost, like `Avoidance`: the planner avoids them while another way exists. | No passage is lost. | String pulling may cut a corner along a wall, so it would have to refuse those cells; one more state per cell in the file and in memory. |
| **C. Erode only against what it protects** | Erode next to the walls of buildings and castles, not next to small props (tents, pots, barrels) or ledges. | Goes at the cause of the islets: props isolate patches of ground. | Models must be told apart (a tent is a `bu_` like a house); not known to hold on every map. |
| **D. Per map or per zone** | A list of maps or boxes where the erosion is 0, as `MapSlopes` and `DroppedFaces` do. | Simple, safe, aimed. Settles the Morheim cave at once. | By hand, for every case; nothing finds the cases. |
| **E. Finer grid** (not taken) | Cells of 25 cm instead of 50, so the same one cell is 25 cm. | Fewer passages lost. | Four times the memory, on a machine where the meshes already take 570 MB. Probably out. |
| **F. Two layers** (not taken) | The eroded mesh for ordinary routes, the raw ground as a second try when there is none. | No passage lost. | Twice the storage; the second try may cross walls unnoticed. |

**A measured (31 October)**, `Heightfield.keepPassages`, off by default, `-Dnavmesh.keepPassages=4` (stretches of at least 4 cells; the way back may cross 8 cells, `navmesh.passageReach`).
Refused pairs both ways and stretches through a wall, as the generator's audit counts them:

| 9 maps (Morheim, Altgard, `220040000`, `210040000`, `210050000`, `220070000`, `210020000`, `210030000`, `220050000`) | Refused pairs | Wall stretches |
|---|---|---|
| Erosion 1 (today) | 365 | 28 |
| Erosion 0 | 184 | 92 |
| A, no step across a wall corner | 227 | 36 |
| **A, with the corner rule (kept)** | **266** | **29** |

Without the corner rule A reopened 7 stretches through the store walls of Sanctum's upper floor (`col_storebase_wall_07g.cgf`, a step across a corner the erosion
had shut on purpose); the rule (a diagonal step only when both neighbours beside it were ground before the erosion) costs about 40 refusals and brings the walls back.
On the 23 maps the version without the rule gave 624 to 464 refusals and 49 to 76 wall stretches. Strong on `210040000` (81 to 32), Altgard (27 to 20), `220040000`
(54 to 37); nothing on `220080000` (194 to 182, the map with most of the refusals left), `220010000` (8 to 8). Not run on the two largest maps with the rule. Not yet seen in game.

**Caveat on every figure of the erosion and A runs.** The meshes of those runs were generated in a folder of their own, without the map's `.glide` file, and every
open-world map has one: a pair a bot reaches by gliding was counted as refused. `220080000` gives 194 refused pairs that way and **61** with the server's glide links
(the first figure was read as "the map with most of the refusals"; it is not). The comparison of the variants under the same condition stands, and the wall
stretches (counted by the audit during generation, with no glide) are not touched, but the absolute refusal counts are too high where glides matter. The glide links are
numbered by the regions of the mesh they were made for, so a variant's mesh needs its own (`NavmeshTool <map> glides`) before the pairs are counted: to redo.

**C written (31 October)**, `-Dnavmesh.decor=true`, off by default: the models called `pr_*` (props) or containing `tent` are decor; the ground that only their walls
took (`Heightfield.blockSpanByDecor`) is no edge for the erosion, so the ground beside a barrel or a tent is not shaved, while the decor's own ground stays shut.
The rule is by name and may be too wide (a `pr_` that is a fence); the figures below say. Measured with each variant's own glide links, Morheim:

| Morheim | Refused pairs (both ways) | Wall stretches (short / long) | Islets |
|---|---|---|---|
| Today | 40 | 0 / 2 | 21 |
| C | 33 | 0 / 4 | 15 |
| A | 35 | 0 / 2 | 16 |
| A + C | 33 | 0 / 3 | 15 |

`tools/navmesh.ps1 -OpenWorld` generates the meshes with both since 31 October (`-Plain` for the old erosion); the glide and flight links are written again after each mesh.

**The way into the Morheim cave, found (31 October).** The entrance route does not take the ramp at 776 342: it goes south-west, west, **over a hill** (733 329 at z 531, 733 333 at z 538)
and down to the cave (790 375). It exists only if the ground is climbed up to about 62 degrees (`-Dnavmesh.slope=62`; 55 gives a walk of 3 330 m for 66 m, 50 and 45 none), which for the whole map
opens four more stretches through a gate, a prop and two trees far from the cave. So `MapSlopes` has **zones**: a box of a map with its own limit, here Morheim, x 725 to 800, y 295 to 385,
62 degrees (`ZONES`; the cell tests of the generator and the step the path finder takes use it). Measured on Morheim with its own glide links: entrance route of 10 points, 137 m for 66 m;
refused pairs 40 to 35; wall stretches 0/2 to 0/1; the four stretches of the whole-map slope are gone. **A mesh made with a zone must be generated again** (`tools/navmesh.ps1 220020000`, then its glides
and flights; `-OpenWorld` does all): the path finder takes its step from `MapSlopes.tangent`, so the old file and the new code disagree until then.

None of them settles everything alone. The erosion is one of the causes, with the walls step (a rock such as `na_d_icerock_b_04d.cgf` at the Morheim entrance),
cuts that are real cliffs (`210030000`) and authored no-walk volumes (`walk_obstacle4_08m`), which are meant.


**Options B, E and F: closed on 1 November, not taken.** B (erosion as a cost) wants one more state per cell in the file and in memory, was never measured, and may cut corners along walls. E (cells of 25 cm) multiplies cells, memory (about 570 MB for 26 maps now, about 2 GB then, on a 2560 MB heap shared with 1000 bots and the auction house), the 1.8 GB of meshes on disk and the search a route costs, and every link file would be written again; it fixes nothing that A does not. F (two layers) doubles the storage, pays a second search for each goal that has no route (most of the given-up plans are such goals) and is the way bots went through walls before the erosion (34 stretches through a model on Poeta without it, 0 with). A (passages given back) and C (decor spared) are on, D (boxes without erosion) holds the Morheim cave, and a ramp by hand answers the rest, each checked in game or by a golden route.
## F. What came of it (31 October)

- **Deployed**: A (passages given back, minimum 4 cells) and C (decor spared) by default, `navmesh.ps1 -Plain` for the old meshes; the 26 open-world meshes were built again with their links; the islet fix; slope zones (Morheim's hill).
- **Offline** (7 maps, glide links written for each): pairs of neighbouring monsters refused 154 today, 111 with C, 92 with A and C (-40 %); wall stretches 22 to 26. In game, 30 minute windows moved little:
  day plans given up 347, 317, 347, 317; routes refused 528, 501, 659, 551. Variance is large and one report before is no band; the gain is in the places a tester walked, not in the totals.
- **The cave of Morheim** took four notes: a rock lip shutting the mouth (`MapOpenings`), a floor the data does not hold between the mouth and the corridor (a patch of floor), the erosion shutting a one-cell
  corridor and the patch once the cave was no stretch of its own (A only joins stretches that are cut off, never a short cut: boxes the erosion leaves alone). The routes are in `tools/golden-routes.txt`.
- **What is left of the 75 journeys cut in the 13:25 window** (317 given up): dispersed (no goal more than 3 times), 18 over 400 m, mostly monsters on ground that is a real cliff, islets, or a bot standing in a pocket;
  the islands of Kaldor (600090000: 7 981 islets, no chain of glides) are right. The replay overestimates: it glides in cities where a bot may not.
- **The biggest lever found was not the mesh**: 131 "long" give-ups a window, 33 sampled and replayed: 8 found at 3 s, 17 at 6 s, 21 at 30 s (`levelledBudget` is the one that matters). Budgets doubled.
- **Still open**: Altgard's cave (needs a note), the plateau of 210030000, 220070000's plateau (1654 2723), the "no ground" goal of 210050000 (1557 275), 20 spots without footing in 220070000, 220010000, options B, E and F, and the monsters that hit a bot from ground it cannot reach (`handleAttack` answers them whoever they are). The 45 dungeons are untouched by A and C: no erosion in an instance.

## D. What it takes to call a mesh better

15. **A regression check before any correction**: on every open-world map, the pairs refused (`fieldbreaks`), the audit, and the replay of the journeys given up
    in a measured window (`NavmeshTool replay`), before and after. A rule written for one cave opens cliffs elsewhere: `MapSlopes` already says so for the dungeons.
16. **A target that can be measured**: no pair refused that is not a real cliff. Zero refusals is not the aim, since some monsters stand where nobody walks.

Suggested order: 2, 1, 5 and 6, 15 before any of them is merged, then 3 and 4. A mesh of one map is generated in 30 seconds (Morheim) in a folder of its own with
`data/geo` and `data/static_data` linked; the whole open world takes about 25 minutes with the game server stopped.
