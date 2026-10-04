# Flight

How bots get off the ground, in the order the work can actually be verified. Design doc: nothing here is built yet.

Flight is the one capability that gates the most — the Abyss, aerial PvP, gliding shortcuts, a handful of quest routes — but the reason to start it
now is smaller and measurable, and it is the one below.

## What it buys first, measured

A map with `GLIDE` puts npcs on terraces, ledges and rooftops, because a player reaches them through the air. The civic plan drops those places today:
nobody is housed where nothing walks off, which is correct for a bot that only walks and wrong about the world. Crossing the dropped places against
the game's own fly zones says plainly what they are:

| map | places dropped | inside a `ZoneType.FLY` zone |
|---|---:|---:|
| Eltnen | 12 of 152 | **11** |
| Verteron | 5 of 123 | 2 |

Eltnen's marooned village at 268 2730 — where seventeen residents once lived unable to reach a shop or each other — sits inside
`FLYINGZONESHAPE1_4_210020000`, a polygon 470 m across whose ceiling is 230 m above its floor. The world was not describing broken ground. It was
describing somewhere you fly to.

So the first milestone that pays for itself is not the Abyss. It is a bot that can rise 30 m onto a terrace and come down again.

## What the engine already does

More than expected, and all of it callable server side without a packet.

- **`FlyController`** (`controllers/FlyController.java`): `startFly(broadcast, ignoreCooldown)`, `endFly(broadcast)`, `switchToGliding()`,
  `onStopGliding()`. `startFly` sets `FlyState.FLYING` and `CreatureState.FLYING`, broadcasts `SM_EMOTION(FLY)`, and starts the fp drain itself.
- **Its refusals are explicit**, which is what a bot needs: daeva (level 10), inside `ZoneType.FLY` and not `NO_FLY`, no `AbnormalState.NOFLY`, not
  polymorphed, no shop open. A 10 s `FLY_REUSE_TIME` cooldown, skippable by argument.
- **The flight point economy runs on its own.** `PlayerLifeStats.triggerFpReduce` schedules a 1 s task: 1 fp/s inside a fly zone, 2 outside, and
  gliding in a zone halves the period. Restore is +3 every 6 s. Base flight time is 60 (`gameserver.base.flytime`), so **60 s of flight, 120 s of
  gliding, and two minutes to refill from empty**. At 0 fp the task calls `endFly` by itself.
- **Speed is already right.** `PlayerGameStats.getMovementSpeed` returns `FLY_SPEED` whenever the owner is in a flying state, so a bot that interpolates
  by `getMovementSpeedFloat()` flies at flight speed with no change.
- **Onlookers are already provided for.** `SM_MOVE` writes the movement mask, including `MovementMask.GLIDE` and its glide flag, and `EmotionType` has
  `FLY`, `LAND`, `START_GLIDE` and `STOP_GLIDE`.

## What it does not do, and what that forces

**Falling is entirely client side.** `CM_MOVE` is what calls `updateFalling`/`stopFalling`, and `MovementMask.FALL` is set by the acting client. No
server code moves anything downwards. A bot whose fp runs out at 200 m therefore does not fall — it hangs there, out of fp, unable to take off again,
for ever. **Landing has to be flown, under power, and the fp to do it has to be reserved before taking off.** This is the single most expensive thing
to get wrong, and it is the same shape as every trap in [engine-traps.md](engine-traps.md): what a real player's client does by itself, the server has
to do for a bot.

**Vertical movement does not exist in the bot's mover.** `BotMoveController.moveToDestination` interpolates x and y and then replaces z with
`groundZ(...)`, a geo probe 2 m either side of the interpolated height. That line is exactly what must not run while flying.

**There is no air navigation and there does not need to be much.** The navmesh describes surfaces and flight is a volume, which sounds like a second
mesh. It is not, for what is wanted here: `GeoService.getClosestCollision(creature, x, y, z, ...)` casts the ray that answers "is this stretch of air
clear", and `BotPathFinder.nearestGround` already answers "where would I land". Straight line plus one ray covers a terrace, a ledge and a rooftop.
A volume mesh is an Abyss question, not a terrace question, and it is deliberately last here.

## Milestones

Each one is verifiable on its own, and the verification is a measurement in the log, not a server that starts.

**1. Take off, hover, land, on command. Done.** `//bot fly <name> [height]` holds the bot still for the take-off animation, climbs on a slant to 25 m,
hovers four seconds and flies back down to the spot it left. Measured over five consecutive flights: 11.8 s each, 9.0 m/s, 11 fp of the 18 reserved,
landing within 20 cm of the take-off height, no errors. The fp for the descent are reserved before the take-off, and a flight the engine ends from
underneath — fp gone, a zone left, an effect landed — is still flown down rather than left hanging, since nothing falls server side.

What it cost was six rounds of being watched from the ground, every one of them a different way of speaking the client's movement protocol wrongly;
they are written up in [engine-traps.md](engine-traps.md). The two that were this server's own fault are worth naming here: the body was quoted *walk*
speed, because `getMovementSpeed` answers from a cascade of states and the bot was still in `WALK_MODE`; and the flight loop ran on the bot pool, where
its first step waited 7.8 seconds behind eighty decision ticks. Moving a body is not thinking, and walking never used that pool either.

**2. Flight in the mover.** `BotMoveController` learns a flying mode: z is interpolated towards the target instead of being snapped to the ground, and
arrival is three dimensional. Half of it is already there — the mover announces flight legs and refuses to walk a body that is in the air — and what is
left is folding the flight's own stepping loop into the one the engine ticks. Verified by a bot flying a fixed leg at a fixed height with no drift.

**3. A short hop to a place a walker cannot reach.** The one the measurement above asks for: from ground, up to a terrace, inside one fly zone, with
`getClosestCollision` refusing the leg if the air is not clear and `nearestGround` picking the touchdown. This is where the civic filter loosens — a
place inside a fly zone stops being dropped once a bot can get to it, which is a one line change to a rule that is already written and already
measured, so the before and after is 11 places on Eltnen.

**4. The flight state machine, properly.** Flying against gliding, the 10 s cooldown, what interrupts a flight (death, teleport, `NOFLY`, polymorph,
a fight starting), and what a bot does when fp runs low mid-journey: land early, at the nearest ground, rather than at the destination. Verified by
forcing each interruption on one bot and reading what it did.

**5. Volume navigation, and only then the Abyss.** Reshanta's islands need a route through open air over a gap with no ground under it, which is the
only case the straight line plus ray cannot answer, and it also needs Reshanta's mesh, which has not been generated. Budget it separately; see
[roadmap.md](roadmap.md).

## Traps to expect, named in advance

- **Two animations in one instant.** Taking off is an animation, and so is drawing a weapon. The existing `holdAnimation` spacing applies, and flight
  adds two more pairs to it.
- **State kept in two places.** `FlyState` and `CreatureState` are both set by `startFly` and both cleared by `endFly`, and a bot that reads one of
  them will eventually read the one that drifted. Derive from the controller, as `isTravelling()` already does for movement.
- **A refusal that is a question, or a substitution.** `startFly` returns false for six different reasons and sends the player a system message for
  each — which goes nowhere for a bot. Whatever drives flight has to ask its own questions before calling it, or it will retry a refusal in a loop,
  exactly as the buff loop did.
- **`AuditLogger` is watching.** `SummonController.attackTarget` audits a summon swinging faster than its attack speed, and `CM_MOVE`'s path audits a
  fly cooldown skipped. Nothing audits a server-driven flight today, but anything that starts to look like speed is worth checking against
  `StatCapUtil`, which caps `FLY_SPEED` at 16000 for anybody who is not staff.
- **A fly zone is a volume with a floor and a ceiling.** `FLYINGZONESHAPE2_210020000` runs from z 16 to 316; a bot that climbs past the ceiling leaves
  the zone, which doubles its fp drain and makes `canFly` refuse the next take-off. The zone bounds are data and must be read, not guessed.
