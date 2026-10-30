# Mesh defects: what to correct for the meshes to be as exact as they can be

Status: in progress: audit of 30 October done (open world, monster pairs), no correction applied yet.

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
   are blocked. Not found yet: whether erosion by the body's width eats a narrow ramp, or a ceiling or wall of the model blocks it.
   Needed: a report of why each cell of a column is blocked (erosion, headroom, a wall, a no-walk volume).
2. **Islets under monsters: 335 pairs.** A spawn spot falls on a patch of a few cells (1 to 17 at Morheim) that props isolate: tents
   (`bu_d_lehpartent_02b` alone: 6 breaks, 38 pairs at Morheim), fires, pots, barrels, sacks, fences. The large ground is 1 to 3 m away. Two corrections to
   try, each measured against the 335: snap a monster's spot to the large ground within 3 m when a bot aims at it, or join an islet to the large ground
   next to it in the mesh. The first leaves the mesh alone.
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

## D. What it takes to call a mesh better

15. **A regression check before any correction**: on every open-world map, the pairs refused (`fieldbreaks`), the audit, and the replay of the journeys given up
    in a measured window (`NavmeshTool replay`), before and after. A rule written for one cave opens cliffs elsewhere: `MapSlopes` already says so for the dungeons.
16. **A target that can be measured**: no pair refused that is not a real cliff. Zero refusals is not the aim, since some monsters stand where nobody walks.

Suggested order: 2, 1, 5 and 6, 15 before any of them is merged, then 3 and 4. A mesh of one map is generated in 30 seconds (Morheim) in a folder of its own with
`data/geo` and `data/static_data` linked; the whole open world takes about 25 minutes with the game server stopped.
