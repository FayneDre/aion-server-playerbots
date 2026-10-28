# Walking into scenery: plan

Status: done; milestones 0 to 4 built, what is left is point 2 of the navigation backlog.

Milestones 0 and 1 built 16 October, 2 mostly, the at-home part of 3; the route loop and most rescue teleports fixed 17 October (steps aside
14689 -> 444, rescues 403 -> 111 per half hour on 800 bots). 3 and 4 built 17 October (no headway 708 -> 15); 3 unverified with a player online. Follows the open section of [navigation-stuck-plan.md](navigation-stuck-plan.md)
("into an obstacle, then teleported behind it"), which this replaces as the place the work is tracked.

**18 October, walls themselves.** Bots noted (`//bot note`) walking into house walls in the Verteron citadel and into Teminon's castle were all on
planned routes, no step aside: the mesh let them through. The generator eroded walkable ground **by column**, and a wall's column nearly always keeps a
walkable surface elsewhere (the ground under a floor, the top of the wall, a roof), so the floor beside a wall was never shaved and a thin wall at an
angle stayed one cell thick, which a diagonal step crossed. Erosion is now **per surface** (`Heightfield.erode`, `HeightfieldBuilder`) and every
open-world mesh was generated again. Measured offline with `NavmeshTool <map> path` (each stretch tested against the solid walls at 0.5, 1 and 1.5 m),
`audit` (routes on foot through a wall) and `reachfrom`: the noted routes clean, Teminon's npcs 99 of 101 reached from its obelisk against 97, the
Verteron citadel 89 of 108 as before. Two path finder rules were tried and dropped (no diagonal between two shut corners; no single rise over 0.6 m):
they cost passages, and the second shut fifteen of Teminon's npcs. The "steps aside" that remain are this module's own rule keeping 1.5 m from
gatherables (`BotGeoHelper.gatherableClearance`): 976 of 1011 in 47 minutes on 18 October. The client does collide with a gatherable, a player
cannot walk through one (checked in game the same day), so those steps aside are the right ones.

**Same day, later: what the shaving cost.** The erosion reached its diagonal neighbours, a square, so against a wall at an angle it took 0.7 m a side
and shut a two metre doorway of the citadel (a quest npc's shop, at 1649 1455); it is a disc of a body's radius now, and every mesh was built again.
The mesh also stopped joining ground through closed siege gates, which split Gelkmaros and emptied places by the hundred: the fix was the place
filter's, not the mesh's (see [population-churn-followup.md](../../population/history/population-churn-followup.md)). And a keeper's own floor is no longer where a customer
stands when the bot's ground does not lead to it: rescues after a sale had doubled (`BotErrands.spotBeside`).

## What is seen

Bots walk into walls, rails and props, slide sideways along them, cross a rail "in two steps aside", and are now and then teleported home or onto
nearby ground. Reported by the user as too frequent and breaking immersion.

## What the log says (16 October, 14:50 to 15:24, about 83 bots, no player)

| Event | Count | Per minute |
|---|---|---|
| `steps aside` (a sidestep picked by the reactive layer) | 747 | 22 |
| `makes no headway ... gives up moving` | 30 | 0.9 |
| `walled in` | 6 | |
| `could not move ... put back home` or `put on walkable ground` (a teleport) | 14 | 0.4 |
| routes planned / refused | 4572 / 61 | |

- **It is everywhere, not a spot:** 64 of the 83 bots stepped aside at least once. The densest 20 m squares are the Verteron fortress (2320-2340,
  1780-1800), 1900 1980-2000, and Tursin (2360-2380, 820), which are also where bots are most numerous.
- **The sidesteps are short:** 399 under 2 m, 356 from 2 to 4 m, 8 longer. A short sidestep across a rail reads as a teleport.
- **Some rescues teleport a bot onto itself:** "put back home, which is where it already was", four times for one bot in half an hour.

## Why, from the code

Movement has two layers ([navigation-prototype.md](navigation-prototype.md)):

1. **Planned waypoints** from the navmesh, walked as given. String pulling only keeps a straight stretch when `BotPathFinder.hasClearLine` says it
   stays on walkable ground the whole way: these are sound.
2. **Everything else is reactive:** the last stretch to a live target (`aimAtNextWaypoint` ends on the target itself, "no waypoint the mesh vetted"),
   any leg on a map without a mesh, and every detour. `BotGeoHelper` probes the way with `GeoService.findMovementCollision`, rays raised about a metre
   off the ground, and **never asks the mesh**. Rays that high do not see a rail, a low wall, a step or a prop's base, so the bot walks into them; when
   they do see something, `detourPointToward` picks a point 2 to 8 m to the side at 40, 75 or 110 degrees, checked by the same rays, and walks there.

So the sidestep is both the cause of the collisions (it goes where the rays are blind) and their look (a body jerking sideways). The fix is to make the
mesh, which already knows rails, walls and props, decide the short moves too.

## Milestones, in verifiable order

0. **The measure, repeatable.** `tools/nav-stats.ps1` gains steps aside per bot and hour, the 20 m squares they cluster in, and teleports split into
   "seen by a player" and not. The figures above are the "before". Each milestone below is judged on the same window length with the same population.
1. **The mesh vets every short move.** On a map with a mesh, a reactive leg or a detour point is taken only if `hasClearLine` (exposed through
   `NavmeshService`) says the straight walk to it stays on walkable ground. Rays stay for what the mesh cannot know: spawned objects and gatherables.
   Expected: steps aside through rails and walls gone; the count of sidesteps may stay high, so milestone 2 matters.
2. **No reactive leg where the mesh can plan one.** When the straight walk to a live target is not clear, plan a route to it (the search is cheap at
   a few metres and already runs for short journeys on the calling thread), and re-plan as it moves, as `REPLAN_DISTANCE` already does. The reactive
   layer is left for maps with no mesh and for a mesh refusal. Expected: steps aside down by most of their number.
3. **No teleport anybody sees.** The rescues (home, walkable ground, out of a pocket) wait for no real player to have the bot in sight, as the
   director already waits to put one to sleep (`PlayerBotService.sleep`, `SEEN`); meanwhile the bot walks to the ground the rescue would have used when
   the mesh joins it. A rescue onto where the bot already stands does nothing and is not logged as one.
4. **The hot spots, one by one.** `NavmeshTool <map> path` between the positions the log names at the Verteron fortress, 1900 1990 and Tursin, to tell
   a mesh that disagrees with the geometry (a thin wall, a door, a prop missing from the generation) from steering. Fixed in the generator where it is
   the mesh.

Done when, over the same half hour: steps aside under a tenth of today's, no teleport while a player is within sight, no-headway under one in ten
minutes.

## Built so far (16 October)

- **0:** `nav-stats.ps1` prints steps aside per minute, by kind of leg (the log line now ends with it: planned, last stretch, awaiting a plan, no
  route, no mesh), by length, by bot and by 20 m square, and rescue teleports with how many a player saw (`(seen by a player)` on the line) and how
  many landed where the bot already was. The server writes decimals with a comma on this machine; the script reads both.
- **1:** `BotPathFinder.clearDistance` (the first metre forgiven, for a body pressed into a wall), `NavmeshService.clearDistance`, and
  `BotGeoHelper.withinMesh` cutting every reactive leg and every sidestep down to it; the goal's last metre is left to the rays.
- **2, first step:** `BotMoveController.planAroundObstacle`: an unplanned leg the way ahead of which is shut asks for a route once per place and
  destination before any sidestep, keeping the journey's avoidance.
- **Found by the first five minutes after:** 169 of 208 steps aside were on planned legs, 129 of them towards a waypoint 0.5 to 1 m away: a step
  under a metre counted as blocked, so a waypoint that close was walked round, not to. `isTheGoal` lets such a step through.
- **Seen in the "before" log, for 4:** of 7031 steps aside over five hours, 938 were one bot (Rasalon) on the upper floor of the Verteron fortress,
  heading for a route that starts with a glide and is planned again every second. Also 218 steps aside headed for the spot the bot stood on.

## The wide sample (16 October, evening)

Every open world map with a mesh populated except the two housing maps (their 557 villagers never sleep and did not fit a 2560 MB heap): 24 maps,
about 800 bots awake with nobody online. Half hours, same population:

| | 21:25 | 22:22 | 23:14 | 17 Oct 09:50 | 10:55 |
|---|---:|---:|---:|---:|---:|
| steps aside | 14689 (13422 awaiting a plan) | 2951 (2390 last stretch) | 514 (511 planned, something spawned) | 444 (440 planned, something spawned) | 469 |
| no headway | 708 | 95 | 83 | 76 | 15 |
| walled in | 12 | 11 | 0 | 0 | 0 |
| rescue teleports | 403 | 379 | 388 (none seen by a player) | 111 (none seen) | 79 |
| routes planned | 16794 | 37461 | 45625 | 22616 | 23334 |
| server CPU | 9.2 cores | 5.5 cores | 5.9 cores | 3.7 cores | 3.9 cores |

- **Awaiting a plan:** on a map with glide links every plan is answered on another thread; a bot asked again on every call (the route not in hand
  being empty), 16794 routes in half an hour, and stepped aside blind meanwhile. Now it waits, standing, and asks again only after 5 s.
- **CPU:** `BotDay.restingSpot` ran up to 24 searches per spell of loitering; it is kept per bot and place, and searched from the place only.
- **A rescue onto where the bot stands** no longer teleports: the bot was not stuck, its errand was, and it is forgotten instead.
- **Last stretch:** a goal off the walkable ground (a keeper behind a counter, a creature on a rock) had a bot pacing beside it and planning twice a
  second; once the mesh has planned from there, the bot stops as near as it gets for 10 s. Shops now trade within 8 m (the engine allows 10, it was 5).

- **The route loop (17 October):** a probe on `stop()` and `haltForCast()` named the caller: `BotSkillManager.cast` halted the walk for a buff
  before the engine refused it, so the route was thrown away on every tick (one bot about 600 times in ten minutes for one Stone Skin cast). The
  walk is halted now only for a cast `Skill.canUseSkill` accepts. The route's log line names where the bot asked from and why it planned again.
  Most routes planned by one bot in the half hour: 1442 before, 45 after.
- **Rescues (17 October):** 1046 of 1352 over two hours came within half a minute of a refused route to a farming ground. Farming only moves the
  anchor and was never given up, so the hunt was refused until the anti-stuck rescue teleported a bot that was not stuck.
  `BotDay.anchorRefused` gives the ground up on the first refusal and brings the anchor home, as loitering and wandering already did.
- **3 (17 October):** a bot a real player has in sight is not lifted: it drops its errand and the rescue waits for nobody to be looking. A bot in a
  player's group is always lifted, seen or not, since staying stuck behind them is worse; when no floor joined to the leader's is near it, it is put
  beside the leader instead of waiting for ever (it still waits for a leader in the air). Not yet watched with a player online.
- **4 (17 October):** neither hot spot of the 09:50 half hour was the mesh. The Sanctum one (147 of 226 "could not move") was one bot refused
  everything at its own door, where `NavmeshTool path` plans in 20 ms: the move controller kept refusals earned elsewhere, so every rescue now
  calls `forgetRefusals`. The Gelkmaros shops (14 of 76 no headway) were the measure: a route planned again partway kept the old waypoint's
  distance as its baseline and gave up 5 s later on ground the mesh and `blockers` both call open; each new planned waypoint, off a detour, is
  a new baseline. The Verteron fortress steps aside are props spawned in the way, which the mesh is right not to know.
- **Found by the review (17 October):** "as near as it gets" was an arrival for ever: a bot sent to sell stood out of a keeper's trade range,
  re-arriving every ten seconds, and did nothing else (22 bots for 40 minutes). The second time from the same spot for the same goal is a refusal.
- **Left:** milestone 3 watched with a player in game.
## Risks

- **More planning.** Milestone 2 asks for more routes; short ones are cheap, but chasing many targets at once is the case to watch (CPU, and the
  memory the server already has little of on this machine).
- **The mesh is not the geometry.** A 0.5 m grid misses what is thinner, and props placed at run time are not in it. Milestone 4 is where that shows.
- **Maps without a mesh** keep today's behaviour.
