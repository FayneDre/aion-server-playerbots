# Bot navigation

How bots move: what plans the way, what walks it, and where each one must not be trusted.

## Who decides what

`geoEngine` answers only *"is this line blocked?"* and *"how high is the ground here?"*. There is no graph and no neighbour query, so path planning needs a navmesh built beforehand — that is [navmesh-plan.md](navmesh-plan.md), and it is done.

Two layers now share the work, and **the whole class of bugs this system has produced came from blurring the line between them**:

| | Knows | Owns |
|---|---|---|
| **Navmesh** (`BotPathFinder`) | The world's static geometry, sampled every 25 cm, on ground already eroded by a body's width | Where to go: the waypoints |
| **Reactive probing** (`BotGeoHelper`) | Only what a ray one metre off the ground reports, plus spawned objects it can see | The gaps the mesh cannot cover |

`BotMoveController.startNextLeg` applies that split literally. **Walking to a planned waypoint, it walks straight there** — the search only ever stepped between walkable cells and string pulling only kept the segment because a straight walk down it stays on walkable ground, so re-deriving that from raycasts can only make it worse. They miss anything shorter than a metre, so the bot walks into it; and they flag what the route already went around, so the bot abandons a good plan to sidestep into geometry nobody planned for. Both were observed, repeatedly, before the split was made explicit.

The probes are used where the mesh genuinely has nothing to say: **maps with no generated file**, the **last stretch to a living creature** (which stands where it likes, not on a waypoint), and **objects spawned after generation** (gatherables). One safety net crosses the line on purpose: a bot the probes report as walled in on every side, while holding a planned waypoint, walks the plan anyway. That is what being wedged inside geometry looks like from the inside, and the server drives the bot's position, so trusting the mesh is what frees it.

## The two geo primitives, and which one to use

| Method | Behaviour | Use for |
|---|---|---|
| `GeoService.getClosestCollision` | Straight ray between two points | Line of sight |
| `GeoService.findMovementCollision(creature, angleDegrees, maxDistance)` | Walks the ground in 1 m steps, ignoring inclines ≤ 45° | **Walking** |

Using the first one for movement is the mistake to avoid: on any slope the terrain itself intersects the straight ray, so the bot reports an obstacle and stops at every gentle hill. `findMovementCollision` follows the ground and only reports real obstacles (trees, walls, inclines over 45°). It already backs off 0.5 m from the contact point, so the returned point is stood in safely.

Its angle is in **degrees**, matching `PositionUtil.calculateAngleFrom(x1, y1, x2, y2)` — not the byte heading used by packets.

## Corridor probing: why one ray is not enough

A geo probe is an infinitely thin line; the **client** collides a body-sized capsule. A post the ray passes 20 cm from blocks the client but not the server. The server then keeps walking the bot while the player's screen holds it against the obstacle, and the authoritative position sent on arrival snaps it forward — indistinguishable from a teleport.

**Obstacles under a metre are invisible to the server.** `GeoMap.COLLISION_CHECK_Z_OFFSET` is 1, and both `findMovementCollision` and `getClosestCollision` raise their ray by it at both ends, so a log or a branch lying on the ground passes underneath every probe the engine offers. The client still collides with it. The server walks the bot through while the client holds it back, and the next position packet snaps the model forward.

**Gatherables are not in the geo data at all.** They are spawned objects, so no ray reports them, yet the client collides with them. `gatherableClearance` handles them without geo: the bot already knows the ones around it, so each leg is clamped against any gatherable within 1.5 m of its path.

Nothing in the geo API can be cast lower, so this cannot be fixed by probing differently. The geometry itself is loaded in full, so the fix is to use it directly rather than through rays: see [navmesh-plan.md](navmesh-plan.md). Engine NPCs never hit this because they follow authored waypoints. It is a hard limit of the current approach, not a tuning problem.

`BotGeoHelper.walkableCorridor` therefore casts three probes: the centre one, plus two deviated by `atan(BOT_RADIUS / reach)` so they end up ±0.5 m aside at the far end. The shortest of the three wins. Cost is 3× a probe, paid once per leg, not per tick.

## Legs

On `MOVE_ARRIVED`, `BotMoveController.continueToGoal()` moves on to the next waypoint. The client sees continuous movement, since a new `SM_MOVE` goes out before the previous leg's stop.

Only **unplanned** legs are cut at `MAX_LEG_DISTANCE` (25 m), because `findMovementCollision` recurses a metre at a time and its cost grows with distance. A planned waypoint is walked in one go however far it is, which is also what makes the walk look decided: every leg boundary is a heading change, and a bot that changes heading every 25 m reads as hesitant.

## Sidestepping

When the direct way is blocked, `BotGeoHelper.detourPointToward` probes deviations of 40°, 75° then 110° on both sides and keeps the reachable point closest to the goal (allowing up to `DETOUR_MAX_LOSS` of lost ground, since going around is rarely a shortcut).

The chosen side is remembered in `BotMoveController.detourSide` and tried first on the next leg. Without that memory the bot alternates between two equally good detours and never leaves the spot.

## The safeguards (not optional)

Reactive steering **always** loses in concave geometry — a U-shaped corridor or a dead end makes it bounce between the same two detours forever. Three bounds keep that from becoming a frozen bot:

| Bound | Where | Rule |
|---|---|---|
| No headway | `BotMoveController.checkProgress` | Gaining less than 1 m on the goal for 5 s abandons the route and sets `isBlocked()`. A re-route to nearly the same goal, as a chase issues every second, continues the same journey instead of resetting this: otherwise the safeguard is disabled for the case that needs it most |
| Walled in | `BotMoveController.startNextLeg` | No direct way and no detour → give up immediately |
| Chase timeout / leash | `PlayerBotAI.chase` | 15 s per chase, and the target must stay within 40 m of the bot's anchor |

A target the bot failed to reach is ignored for 10 s (`PlayerBotAI.unreachableTargets`), otherwise the very next think tick picks the same unreachable mob again.

## A plan is kept, not redone

`moveToPoint` only plans when it has no route, or when the destination has drifted more than `REPLAN_DISTANCE` (2 m) from what the route in hand was planned for. Planning on every call looks harmless and is not: the two ways round an obstacle usually cost within a few metres of each other, so successive plans pick opposite sides and **the bot walks back and forth between them**. Observed, and unmistakable once seen.

Two ordering details in the same method are load bearing:

- `hasGoal` is set **before** planning. Any distance under `BotPathFinder.LONG_DISTANCE` (150 m) plans inline, and `offerRoute` discards a result that no longer matches the journey in hand — so with the old ordering, every plan made for a bot that was not already moving was thrown away as stale the instant it arrived. That is most first legs: after an arrival, after a stop, after any command.
- A synchronous result is adopted **in that same call**. It lands in `pendingRoute`, which is otherwise only read at the next leg boundary, so the opening stretch would walk blind past the very obstacles the plan had just mapped.

## Engine plumbing

- **`MoveTaskManager`, not `PlayerMoveTaskManager`.** Only the former calls `moveToDestination()` every 200 ms and then fires `MOVE_ARRIVED` (plus zone updates) or `MOVE_VALIDATE`. It **removes the creature on arrival**, so starting a new leg must re-register it — `isInMove()` alone is not proof it is still ticked.
- **`AITemplate.isDestinationReached()` returns `false`** by default, which would stop `MOVE_ARRIVED` from ever firing. `PlayerBotAI` overrides it.
- **`PlayableMoveController` already has the right interpolation**, but gates it behind a private `isControlled()` that only allows server-driven movement under fear or confuse. `BotMoveController` overrides the two public methods that consult it rather than patching core.
- **Stopping must send a packet.** Without `setAndSendStopMove`, clients keep extrapolating the bot past its real position.
- **Z is sampled from geo**, throttled to twice a second, and the interpolated value is kept when `getZ` returns `NaN` — only 78 of 161 maps ship a heightmap.
- **Second core patch**: `Creature.moveController` → `volatile` + `setMoveController()`, same reasoning as `setAi()`.

## Chase integration

`PlayerBotAI.attackTick` arbitrates between moving and fighting:

1. Casting → do nothing (a cast roots a real player too).
2. In weapon range → stop moving, then cast or auto-attack.
3. Otherwise → `chase()`, and give the target up if it returns false.

A new route is only issued once the target has moved `RETARGET_STEP` (3 m) from the current goal, and never more than once every `CHASE_REROUTE_INTERVAL` (600 ms): each route makes clients restart their interpolation, so re-routing on every attack tick turns a run into a stutter. Because the attack tick only runs at weapon speed (over a second), `handleMoveValidate` — which fires every 200 ms — is what stops the chase the moment the target comes into reach; otherwise the bot overshoots and runs through it.

Idle bots return to their **anchor**, set at spawn and moved by `//bot come`, so repeated chases do not slowly displace them.

## Limits

- **Only maps with a generated file are planned on.** Elsewhere every leg is reactive, and anything needing more than one sidestep — a building, a ravine, a U-shaped wall — is abandoned by design.
- Chases are bounded by time and leash, so a fleeing target escapes.
- Bots do not use the Z axis: no jumping, no flying, and targets more than 8 m above or below are ignored.
- The last few metres to a creature are reactive, so a mob standing tight against a low prop can still be approached badly.
- Crossing maps is not a navigation problem: it needs teleporters and flight paths, like a player.
