# Bots stuck in scenery, then teleported home

Status: analysed 2026-10-09, nothing fixed yet. The cause of most cases is known and is **not** the quality of the mesh in the camps; milestones below.
Reported from in game: a bot inside a wall at the Verteron fortress, a bot sunk into the floor of the Eltnen fortress, both followed by a teleport.

## What the logs say

The server logs every rescue (`could not move at all from X Y and is put back home`) and every resurrection (`revived at X Y Z`). Over 5 to 9 October:
**1171 rescues, 5278 resurrections.** Rescues per day fell from about 380 (6 October) to under 10 per session (9 October), so the old causes were mostly
fixed; what remains is concentrated, not scattered. Bots were assigned to maps through the database (`players.world_id`).

The rescues cluster on a handful of spots, and **every one of those spots is a resurrection point (an obelisk)**:

| Spot (map) | Where | Resurrections nearby | Rescues nearby |
|---|---|---|---|
| Verteron fortress, 2320 1800 | upper floor, z 195 | 81 | about 140 |
| Eltnen fortress, 2410 2750 | | 131 | about 150 |
| Eltnen, 1940 2010 | | 119 | about 60 |
| Verteron, 1760 890 and Eltnen, 280 2740 | | 201 | about 70 |
| Verteron, 2340 810 (Tursin) | | **3328** | about 50 |

So a bot is not wandering into scenery in the camps. It is **put down by the engine at an obelisk and cannot walk away from it**. Most obelisks are fine
(Tursin: 50 rescues in 3328 resurrections); the fortress ones fail nearly every time, and a bot rescued is sent home by teleport, which is the
teleport that was seen.

## Why the fortress obelisk traps a bot

`NavmeshTool 210030000 path` from the courtyard (2380 1802 z 109) to the obelisk (2319 1802 z 195) answers *no route exists*, in both directions. The
obelisk floor is a pocket the mesh does not join to the rest of the map (`components`: a 96 by 86 m island apart from the main one). Players reach it
by stairs, so **the mesh is missing a connection**: stairs steeper or taller than the step rule, a door counted as a wall, or a landing the generator
treats as blocked. The Eltnen fortress shows the same thing with a second variant: of the reports there, about 40% are where the map has **no footing
at all**, so the bot is inside geometry, which is the screenshot of a bot sunk into the floor (the bind coordinate sits inside a solid).

Measured with `NavmeshTool <map> stuck <file>` (new): of the rescues, 607 of 690 on Verteron and 312 of 432 on Eltnen are on ground the mesh calls walkable
(pockets the bot cannot leave), the rest inside geometry. Homes are not the problem: 122 of 134 resident homes are on each map's main island.

## What it is not

- Not the camps. Rescues in the open field are a few a day.
- Not flight: three takeoffs in the log.
- Not the reactive steering, which only shows up after the bot is already in a pocket.
- Not a reason to wait for the Abyss meshes, but it is a reason to fix the generator **before** generating Reshanta, which has fortresses and stacked floors.

## Milestones

1. **Say where and why** (small). Add map, z, the island and the nearest obelisk to the rescue line, so the next report names its cause. Keep
   `NavmeshTool stuck` to compare.
2. **Never revive into a pocket** (bot side, independent of the mesh, fixes it for every map at once). After `bindRevive`, if there is no route from the
   bot to its anchor, or the spot has no footing, put it on the nearest ground that does have a route to its anchor. One teleport at the moment of
   resurrection, which is already one, instead of a stuck bot and a second one ten seconds later. Also bind bots to the nearest obelisk **that has a route**.
3. **Join the stairs** (mesh). Find why the fortress upper floors are cut off: test the stair geometry against the step and slope rules, and doors.
   Fix the generator, regenerate the populated maps, re-run `components` and `path` on every obelisk of every mesh as an audit (obelisk on the main island).
4. **Audit obelisks on all 24 meshes** and keep it as a check in `tools/navmesh.ps1`, so a new map cannot ship with a bot trap.
5. **Then** Reshanta and the other new meshes, with the fixed generator.

Open questions for milestone 3: whether the obelisk coordinate is where a player lands or only where the NPC stands (the bind point may differ from the
NPC), and whether the stairs of a fortress are authored as steps or as a ramp.

## Side finding

3328 of 5278 resurrections are at the Tursin obelisk: that ground kills bots faster than any other. Not a navigation fault, and the new waiting spot
after a death (`bot-careers.md`) helps, but it deserves its own look.
