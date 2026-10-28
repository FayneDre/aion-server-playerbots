# Flight: how it works today

Status: reference, written on 28 October 2026 from the plans and the code. What is left is in [plans/](plans/) and in [roadmap.md](../roadmap.md),
"Flight, the Abyss, RvR"; why each piece is the way it is, with its numbers, is in [history/flight-plan.md](history/flight-plan.md).

## What the engine does, and what it leaves to the client

- **The engine flies a body**: `FlyController.startFly` / `endFly` / `switchToGliding`, with explicit refusals (a daeva from level 10, inside a
  `FLY` zone and not `NO_FLY`, no `NOFLY` state, not polymorphed, no shop open, 10 s between take-offs). Flight points drain by themselves: 60 s of
  flight, 120 s of gliding, two minutes to refill from empty. Speed switches to flight speed by itself.
- **Falling is entirely the client's**: no server code moves a body down. A bot out of points at height hangs there for ever. So **every descent
  is flown under power, its cost reserved before the take-off**, and a flight that has to end early comes straight down onto the ground beneath.
  This is the one rule of the whole topic.

## Who does what

| Class | Does |
|---|---|
| `movement/BotFlight` | Decides the phases (take off, climb, cruise, descend, land) and the refusals; moves nothing itself. |
| `movement/BotMoveController` | Flies the body on the engine's movement tick, in three dimensions and without the ground probe. |
| `combat/BotFlightCrew` | What a bot does for itself in the air (a wind serum under half its points, fighting): asked through `BotFlight.Crew`. |
| `ai/LeaderGlides` | A follower copies its leader's glide: where it left the ground and the points seen in the air. |
| `navmesh/GlideLinks`, `FlightLinks` | The links the planner chains (see [../navigation/how-it-works.md](../navigation/how-it-works.md)). |

## When a bot leaves the ground

1. **A route through a link**: the planner's best route goes off an edge (a glide link) or across open air between islands (a flight link, Reshanta).
   The mover hands the crossing to `BotFlight.flyCrossing`, waits on the edge while short of points or in the cooldown, and asks for the rest of
   the journey once landed. Links found blocked in game are left out and the chains worked out again.
2. **A trip worth flying**: a bot wandering of its own accord flies a trip of 25 to 300 m about half the time, when it starts and ends inside a fly zone:
   a climb to 15, 25 or 45 m above the higher end, a level run, a descent, each stretch tested by a ray and every 20 m of the run inside a fly zone
   (`cruiseOrWhyNot`). Every refusal is a quiet walk, logged with its reason. `gameserver.playerbot.ambient.flight` turns it off.
3. **Following a player**: a grouped bot takes off when its leader does, lands 4 m behind where the leader lands (`rejoin`), passes the rings the
   leader passes (`FlyRing.isCrossed`), and fights from the air beside them. A bot fighting alone takes off after an enemy player who flies out of reach.
4. **Getting out**: a rescue may fly a bot off ground it cannot walk from ([../navigation/how-it-works.md](../navigation/how-it-works.md)); a flight
   with nothing below it looks for the nearest ground its points reach, in rings of 15 m.
5. **By command**: `//bot fly`, `//bot flyto` ([../bot-commands.md](../bot-commands.md)).

Only a daeva flies, and of its own accord never in a fight and only with half its points; every flight that is interrupted (another
map, death, the points, a stun) ends with its reason in the log.

## Reshanta

The Abyss is flown everywhere (`WorldMap.isFlightAllowed`): above the death level plus 100 m is a fly zone. Its 16 000 islands are joined by
**flight links** (edges of islands of 2000 surfaces or more, up to 180 m apart and 200 m of height, 19 510 links). The mainland of each side is the
islands a flight joins both ways, holding 5 places or more (`BotPlaces.mainlandsByAir`); nobody lives in a fortress's zone or on an island only the
other faction lives on, and no link lands there. A route past about 1.2 km still runs out of the planning budget, so residents work within 250 m.

## Limits

- Bots rarely glide on their own: only when the best route goes through a glide link ([../navigation/backlog.md](../navigation/backlog.md), point 4).
- Islands above or below a bot with no link between them stay out of reach; flying there is refused over 300 m or under a roof.
- No windstreams, no flight transporters, no aerial PvP of their own; the classes that die in the Abyss have no playbook there yet.
- Milestone 6 of each open plan: [plans/abyss-crossing-plan.md](plans/abyss-crossing-plan.md) (long journeys across the whole map),
  [plans/flying-with-a-player-plan.md](plans/flying-with-a-player-plan.md) (the Terminon training camp entrance, waiting on the tester).
