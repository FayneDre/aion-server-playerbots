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
they are written up in [moving-a-body.md](moving-a-body.md). The two that were this server's own fault are worth naming here: the body was quoted *walk*
speed, because `getMovementSpeed` answers from a cascade of states and the bot was still in `WALK_MODE`; and the flight loop ran on the bot pool, where
its first step waited 7.8 seconds behind eighty decision ticks. Moving a body is not thinking, and walking never used that pool either.

**2. Flight in the mover. Done.** `BotMoveController` has a flying mode: the same interpolation walking uses, in three dimensions and without the geo
probe that pins a body to the ground, on the movement tick the engine already runs. `BotFlight` decides the phases and moves nothing — it asks for a
leg, waits for it, asks for the next. Measured at 15.8 s for the round trip, 15 fp, no warnings.

The flight is also defended against the tick it now depends on: `stop()` refuses to interrupt a flight, `abortMove` ends it cleanly, a leg the engine
drops is handed over again, and a leg that makes no headway for a second and a half is handed over with a line in the log. The arrival test and the
removal test are the same test, which they had to be — see [moving-a-body.md](moving-a-body.md).

**3a. A short hop to a place a walker cannot reach. Done.** `//bot flyto <name>` flies a bot to where the commander stands — the only way to aim one at
a terrace without writing its coordinates down. Straight there when `getClosestCollision` says the air is clear, which is what flying to a terrace
looks like; over the top when it does not. The touchdown is `nearestGround`'s answer rather than the coordinates asked for. Measured over four
journeys of 34 to 85 m: 6.6 to 12.6 s, 6 to 12 fp, every landing within half a metre of the spot.

Every point of the path is checked against the fly zone before take-off, not only the two ends: a body that leaves one in flight is dumped out of
flight by `PlayerController.onLeaveFlyArea` **and written into the audit log as a suspected hack**, so a path that would leave is refused with the
coordinates where it would.

**3b. The civic filter loosens. Done.** A place the walking tests drop is kept when it shares a fly zone with a place that is on the mainland, within
the range a flight will go — which is the bot's own question, asked with the bot's own limit. Eltnen goes from 12 dropped to 10 and gets its marooned
village back; Verteron from 5 to 3. Each one is named in the log with the neighbour it would fly to and how far that is.

**Reached is not enough: a resident has to be able to leave**, which is why the neighbour is part of the test and not an afterthought. Underneath it,
`BotMoveController` now falls back to flying when the mesh *proves* there is no walking route — never on a search that merely gave up, or a slow search
would have bots taking off all over a map they could have walked. Measured on the first minutes after a restart: six flights of 33 to 214 m, every one
landed, no loops.

**3c. A bot follows a player into the air. Done.** The first thing anybody asked of flight, months before any of it existed: a grouped bot stayed on
the ground while its leader flew, because following is a walking route to wherever the leader is and wherever a flying leader is has no floor. It now
takes off behind them, holds a place a few metres off rather than their exact spot, and re-aims only when they have moved far enough to be worth a new
leg. It breaks off and lands under its own power when the leader lands, when it is left behind, when following would take it out of its fly zone, or at
fifteen flight points — more than a planned flight keeps back, because this one has no plan and a bot that cannot land itself is a bot left in the sky.

**What it cost was two rules about the rest of the module, both older than flight.** A bot only flies of its own accord with half its flight points
back, because "can I afford this flight" and "have I recovered" are different questions and a bot that had just landed out of fuel could afford a ten
metre hop straight back up. And **a bot in somebody's group is never given a lift home**: the anti-stuck rescue saw a bot that had landed on a ledge,
read "no walking route" as "stuck", and teleported it away from the player it was following — then, after three of those, moved its home 1200 m. Being
unable to walk, near the person you are following, is waiting.

**4. The flight state machine. Done, bar gliding.** Every way a flight can be interrupted now ends it with a reason in the log: another map, the body
moved more than 40 m in a tick by something that was not this flight, an `AbnormalState.NOFLY` effect — which *forbids* flight without ending one in
progress, so a bot under it kept its wings and its height — a polymorph that cannot fly, death, despawn, and the engine cutting the flight for want of
points. Running low mid-journey was already a landing straight down rather than at the destination.

The 10 s cooldown is now a named refusal rather than a silent one, and that is not cosmetic: `FlyController.startFly` writes a character that retries
inside it into the audit log as *"possibly using fly cooldown hack"*. A bot retrying in a loop was dirtying the GM log.

**Only the cooldown was verified in game**; the other four guards cannot be provoked from the server and are reasoned from the engine's code.

**Gliding is deliberately left out, and the reason is not its cost.** Server side it is nearly free — `switchToGliding` needs only a daeva that is not
polymorphed, costs no cooldown from an existing flight, and the halved drain falls out of `triggerFpReduce` by itself. What it would cost is a new
packet shape, the `GLIDE` mask bit and its flag, whose bits are also how npc gaits are encoded: several rounds of watching a bot from the ground. And
the gain is small here. On a descent it saves a point or two. On an escort, where doubling 45 seconds of endurance would matter, it cannot be used at
all: a gliding body loses height continuously, and holding a place beside a flying player is flight. It belongs with milestone 5, where a bot will have
reason to descend for a long time.

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
