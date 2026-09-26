# Bot navigation

How bots move, why it is reactive rather than planned, and what it cannot do.

## The constraint

`geoEngine` answers only *"is this line blocked?"* and *"how high is the ground here?"*. There is no graph, no region decomposition, no neighbour query — so **A\* and every other path planner are out of reach** without building a navmesh first. The only paths in the engine are hand-authored waypoints (`spawnengine/WalkerGroup.java`) and flight routes.

Navigation is therefore **reactive steering**: walk as far towards the goal as the ground allows, sidestep what blocks the way, and give up when that stops working. This is a deliberate first stage, chosen over a navmesh so the rest of the bot system could be tested in game. Its failure modes are known and bounded, not accidental — see [Limits](#limits).

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

Nothing in the geo API can be cast lower, so this cannot be fixed by probing differently — it needs collision data the engine does not expose for movement, which means the navmesh. Engine NPCs never hit this because they follow authored waypoints. It is a hard limit of the current approach, not a tuning problem.

`BotGeoHelper.walkableCorridor` therefore casts three probes: the centre one, plus two deviated by `atan(BOT_RADIUS / reach)` so they end up ±0.5 m aside at the far end. The shortest of the three wins. Cost is 3× a probe, paid once per leg, not per tick.

## Legs

`findMovementCollision` recurses one metre at a time, so its cost grows with distance. Routes are cut into legs of at most `MAX_LEG_DISTANCE` (25 m): on `MOVE_ARRIVED`, `BotMoveController.continueToGoal()` probes again and starts the next leg. The client sees continuous movement, since a new `SM_MOVE` goes out before the previous leg's stop.

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

- No planning: anything needing more than one sidestep to get around (buildings, ravines, U-shaped walls) is abandoned by design.
- Chases are bounded by time and leash, so a fleeing target escapes.
- Bots do not use the Z axis: no jumping, no flying, and targets more than 8 m above or below are ignored.
- The client may still disagree briefly on narrow ground props; the corridor probe makes it rare, not impossible.

Lifting these means building a navmesh (offline, from the geo data) and running A\* over it. The interfaces above are the seam where that would land: `BotGeoHelper` would become a path source, and `BotMoveController` would walk the resulting waypoints unchanged.
