# Bots stuck in scenery, then teleported home

Status: analysed 2026-10-09. Milestone 2 built (`BotLanding`, every map with a mesh), not yet watched in game; milestones 3 and 4 (the generator) not started.
The cause is **not** the quality of the mesh in the camps; milestones below.
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

## Second cause, found while testing the first: the rough route has no height

From the Eltnen fortress obelisk (2418 2756, z 365) the planner reaches some places (317 m walked for 146) and gives up on others, although the rough
pass "finds a way". The rough grid labels each 4 m cell with the **regions** whose ground passes under it, but not at which height. A column that holds
both a fortress platform and the ground below it carries the same region twice, so the rough route happily goes straight down through the platform, and
the fine search cannot follow: the bot is on the upper level and the guide points are on the lower. Refinement fails, the planner gives up, and the bot
**walks straight at its goal** while it plans, which is how it ends wedged in a wall. Every multi-level structure has this problem: fortresses, and the
stacked islands of the Abyss, which is why it must be fixed before Reshanta.

## Milestones

1. **Say where and why** (small, not built). Add map, z, the island and the nearest obelisk to the rescue line, so the next report names its cause.
   `NavmeshTool <map> stuck <file>` exists to compare.
2. **Never leave a bot in a trap** (bot side, built). `world/BotLanding` asks the mesh, with the bot's anchor as the goal, and moves the bot to ground it can
   walk away from: onto another floor when its own is a pocket, onto the nearest floor when it is inside geometry. Used after every resurrection (next
   tick) and in the stuck rescue before any teleport home. No list of bad spots; it holds on every map that has a mesh, Reshanta included once generated.
   Checked on the real Verteron mesh: the fortress obelisk is moved to the courtyard (2298 1781, z 109) in 85 ms; ordinary obelisks are left alone.
3. **Give the rough route a height** (generator and planner). Store, per coarse cell and region, the height of that region's ground there, and only let the
   rough search step between cells whose heights are within what a body can climb. Needs a change to the `.nav` coarse section, then regeneration of
   every mesh. Test with `path` from the Eltnen obelisk to 2380 2562 (today: gave up) and from the Verteron one.
4. **Audit every obelisk of every mesh** and keep it as a step of `tools/navmesh.ps1`: obelisk on a floor joined to the rest of the map, and a route
   out of it to a point 150 m away. A mesh that fails it does not ship.
5. **Then** Reshanta and the other new meshes, with the fixed generator.

Open questions for milestone 3: whether the Verteron fortress stairs are authored as steps or as a ramp (the pocket there is the same family of fault:
a level the mesh does not join to the one below), and whether the bind coordinate is where a player lands or only where the NPC stands.

## Side finding

3328 of 5278 resurrections are at the Tursin obelisk: that ground kills bots faster than any other. Not a navigation fault, and the new waiting spot
after a death (`bot-careers.md`) helps, but it deserves its own look.
