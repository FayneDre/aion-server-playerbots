# Walking into scenery: plan

Status: not started; measured 16 October, plan awaiting approval. Follows the open section of [navigation-stuck-plan.md](navigation-stuck-plan.md)
("into an obstacle, then teleported behind it"), which this replaces as the place the work is tracked.

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

## Risks

- **More planning.** Milestone 2 asks for more routes; short ones are cheap, but chasing many targets at once is the case to watch (CPU, and the
  memory the server already has little of on this machine).
- **The mesh is not the geometry.** A 0.5 m grid misses what is thinner, and props placed at run time are not in it. Milestone 4 is where that shows.
- **Maps without a mesh** keep today's behaviour.
