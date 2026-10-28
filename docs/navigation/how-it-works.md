# Navigation: how it works today

Status: reference, checked against the code on 28 October 2026. What is left is in [backlog.md](backlog.md) and
[plans/openworld-navigation-plan.md](plans/openworld-navigation-plan.md); why each piece is the way it is, with its numbers, is in `history/`.

## The layers

| Layer | Classes | Knows | Owns |
|---|---|---|---|
| Mesh | `navmesh/Navmesh`, `NavmeshService` | The static geometry sampled every 25 cm, on ground eroded by a body's width, plus glide, ride and flight links | Which ground joins which |
| Planner | `navmesh/BotPathFinder`, `SearchSpace` | The mesh | Where to go: the waypoints |
| Mover | `movement/BotMoveController` | The route in hand, the body's position | Walking it, leg by leg |
| Probes | `movement/BotGeoHelper` | What a ray one metre off the ground hits, and the gatherables it can see | The gaps the mesh does not cover |
| AI | `ai/PlayerBotAI`, `BotChase`, `BotDay`, `BotRescue` | What the bot wants | Where it asks to go, and what to do when refused |

Navigation is the bottom layer: `navmesh` and `movement` import nothing from `ai`, `combat` or `world` (`ModuleLayersTest`). What they need to
ask above them goes through two hooks installed at startup: `NavmeshService.Host` (is this ground too strong for the bot, a siege ground, can it cross,
the route threads) and `BotFlight.Crew`.

**The rule all the bugs of this system came from blurring: a planned waypoint is walked straight, without probes.** The search only stepped between
walkable cells and string pulling kept a segment only because walking it stays on walkable ground; rays miss what is under a metre and flag what the
route already went round. The probes serve only where the mesh has nothing to say: a map with no file, the last stretch to a creature (it stands
where it likes), and objects spawned after generation. One exception on purpose: a bot the probes see walled in on every side while it holds a
planned waypoint walks the plan, since that is what being wedged inside geometry looks like from inside.

## The mesh

- Generated offline (`tools/navmesh.ps1`, [history/navmesh-plan.md](history/navmesh-plan.md)): a grid of walkable surfaces, several per column, for 26
  open-world maps and 45 dungeon geometries serving the 54 group dungeons ([instance-navmesh-list.md](instance-navmesh-list.md)).
- **Tiles of 64 cells, read when first touched and given back**: opening a map reads its tile directory only, and each map keeps at most
  `TILE_BUDGET_BYTES` (32 MB) of tiles. A whole map would be about 117 MB; a camp needs four tiles. The tiles are the largest part of the heap
  (about 570 MB with 26 maps open, 27 October), which is why the budget is per map and why a global one is the first memory lever.
- **Links**, in files beside the mesh, joining ground the walk cannot: **glide links** (`GlideLinks`, off an edge, landing within ten metres; in the
  open world only from small ground onto large, four a pair, 575 000 on 24 maps), **ride links** (`RideLinks`, Sanctum's lifts and boat, ends placed
  by a tester), **flight links** (`FlightLinks`, Reshanta's islands, edge to edge within what one flight pays). Whether the air is clear is asked of the
  game's geometry when the bot flies it, not stored.
- **Avoidance** (`Avoidance`) is a cost, not a hole: ground of monsters too strong for the bot, a siege ground, is crossed when there is no other way.

## Planning

- **Under 150 m** (`LONG_DISTANCE`), on a map without air links, a route is planned inline in tens of milliseconds. Longer, or on a map with glides or
  flights, it goes to the **route threads** (half the tick threads: four of eight here, `BotScheduler`) while the bot walks straight at its goal,
  and is picked up at the end of the current leg. A
  search waiting in the queue is never asked again for the same journey; one lost for a minute (`PLAN_LOST`) is.
- **A\* over the fine grid** (`MAX_NODES` 150 000, `PLAN_BUDGET_MILLIS` 3 s), then string pulling to a handful of waypoints. A long route, or a short
  one the fine search gives up on, is planned in **two passes** ([history/long-walk-planner.md](history/long-walk-planner.md)): a rough route over 4 m
  cells and the stretches of ground in each (1.5 s, `navmesh.levelledBudget`), then fine searches between guide points about 40 m apart. Glide links
  are asked after any empty walk. All three budgets are system properties (`navmesh.planBudget`, `navmesh.levelledBudget`, `navmesh.glideBudget`).
- **Proofs are cheaper than routes**: a question that only wants to know whether a place is reachable (a shop, an errand) searches 300 ms at most;
  spots round an anchor are asked as a walk capped at three times the distance plus 20 m. Searches over a second are logged with their ends
  (`Path search on map … took … ms`).
- **On a loaded server the 3 s run out**: of 125 long journeys given up on 25 October, 57 were found offline at the same budget. Walking the stretches
  already planned when time runs out is the next step ([backlog.md](backlog.md), point 3).

## Walking

- **A plan is kept, not redone**: `moveToPoint` plans only with no route in hand or when the destination drifted over 2 m (`REPLAN_DISTANCE`). The
  two ways round an obstacle usually cost within metres of each other, and planning every call made bots walk back and forth between them. A
  follower keeps walking its route within 8 m of drift (`FOLLOW_DRIFT`) and takes a new one up from where it has got to (`resumeIndex`).
- **Legs**: on `MOVE_ARRIVED` the next waypoint starts before the previous stop is sent, so the client sees one movement. A planned leg is walked in
  one go however long; only unplanned legs are cut at 25 m. Height comes from geo twice a second, the interpolated value kept where a map has none.
- **Engine plumbing**: the body is ticked by `MoveTaskManager` (which removes it on arrival, so every leg registers it again), `isDestinationReached`
  is overridden, stopping always sends a packet, and `Creature.moveController` is a core patch (`setMoveController`). See
  [../architecture/moving-a-body.md](../architecture/moving-a-body.md).
- **Off the mesh** (`BotGeoHelper`): `findMovementCollision` walks the ground in 1 m steps (never the straight `getClosestCollision`, which every
  slope intersects), three rays half a body apart, and a step aside of 2, 4 then 8 m at 40, 75 then 110 degrees, on the side remembered from the last
  leg. A sidestep is held until reached. Every reactive leg is cut to the mesh's edge (`withinMesh`), and an unplanned leg whose way is shut asks for
  a route once before any step aside (`planAroundObstacle`). Gatherables are kept 1.5 m from a leg (`gatherableClearance`).

## When it does not get there

From the bottom up, each bound feeding the next:

1. **No headway**: under 1 m gained in 5 s abandons the route (`checkProgress`); the destination is refused for 30 s. A re-route to nearly the
   same goal continues the journey rather than resetting the watch.
2. **Given up three times** on one destination (`GIVE_UPS_BEFORE_REFUSING`): left alone for five minutes.
3. **The AI's refusals**: a chase lasts 15 s and keeps within the leash of the anchor (`BotChase`); a target not reached is ignored for 10 s; a day
   plan whose place cannot be reached puts it in `BotDay.unreachable` (for good, today, proof or not: point 3 of the backlog).
4. **Rescue** (`BotRescue.freeItself`): 12 refusals in a row mean the bot is wedged where it stands, 6 destinations given up from one spot that the
   spot is the fault. It is stepped out of a pocket, put beside its leader, flown, or taken home; in a player's sight only a step of 12 m at most. A
   grouped bot within 200 m of its leader is waiting, not stuck (a player flown off to a ledge). A bot whose every journey fails four times in five minutes is freed the same way (`LostTrail`). About 40 a half hour on 900 bots.

## What the figures are

`tools/measure.sh` and `nav-stats.ps1` count, per half hour: day plans given up, routes refused, steps aside (by kind of leg), could not move at all,
rescues (seen by a player or not), routes with a glide, flights and crossings. Two windows of the same code differ by up to 30 %, so a change is
measured against two baselines (`compare-measures.ps1`); the evening of 27 October is in [backlog.md](backlog.md).

## Limits

- Only maps with a file are planned on; elsewhere every leg is reactive, and anything needing more than one step aside is given up by design.
- The last metres to a creature are reactive, and so is what the probes cannot see under a metre off the mesh.
- A bot never travels to another map on its own: it follows its player into an instance or is put beside them.
- Gliding is chosen only when the best route goes through a link, so bots rarely glide on their own (backlog, point 4).
- Flight is in [../flight/](../flight/); dungeons differ in no rule but in corridors, stairs and stacked floors (backlog, point 6).
