# Crossing open air: plan

Status: milestones 1 to 5 built 17 and 18 October (patch `patch-2026-10-18`): Reshanta crossed by air and lived on both layers, a squad crossed the upper layer; still open are the playbooks of the classes that die there and long journeys across the whole map; 6 not started. See [roadmap.md](roadmap.md).

## What is seen

Reshanta (400010000) is an archipelago. Its mesh holds **16210 islands** of walkable ground (`NavmeshTool 400010000 components`): about fifteen
large ones, of 450000 to 600000 cells (some 350 m across), a ring that joins a few of them round the centre, and hundreds of crumbs. The gaps between
large islands are tens to a few hundred metres of air, with nothing to walk on.

What that does to the ten residents populated there (17 October, about two hours, one awake at a time): 17 "has no route", 10 hunting grounds given up
as unreachable, 4 wandering trips refused, 2 rescues. The obelisk audit fails both of Reshanta's obelisks, which stand on separate islands. The map is
populated so thinly because there is little a resident can do on one island.

## Why, from the code

- **The route search only walks and glides.** A glide link is one way, downward, and at most 20 cells (10 m) out from an edge (`GlideLinks.REACH_CELLS`):
  it frees a fortress terrace, not a gap of 150 m between two islands at the same height.
- **Flight exists but cannot be planned.** `BotFlight` flies a terrace hop (`flyTo`, straight line) and an ambient trip (`cruiseOrWhyNot`: climb,
  level run, descent), both capped at 300 m (`MAX_JOURNEY`) and both asked only when the mesh has proved there is no walk. Neither is part of a route:
  a destination two islands away, or one whose straight line crosses a third, has no answer.
- **The bot's fly checks know fly zones only.** `insideAFlyZone`, `ceilingFor` and `staysInAFlyZone` read `ZoneType.FLY` zones, and Reshanta has none:
  the Abyss allows flight on the whole map by a map flag (`world_maps.xml` flags `FLY`, `WorldMap.isFlightAllowed`, which puts every player in a FLY zone
  on entry). Asked today, every take-off on Reshanta is refused as "not standing in a fly zone".
- **The void kills.** `death_level="500"`: a body below z 500 dies. Islands stand around z 1500 to 3000; a flight must never dip that low, and a bot
  out of flight points must never be left in the air (nothing falls server side, see [flight-plan.md](flight-plan.md)).

## Milestones, in verifiable order

0. **The measure.** The figures above, per half hour with the Abyss populated wider, and the obelisk audit. Judged again after 4.
1. **Flight on a map that allows it everywhere.** `BotFlight` reads the map flag as one fly zone the height of the map: a take-off, a run and a
   landing are allowed anywhere on Reshanta; the ceiling is `MAX_HEIGHT` above the higher end, and no point of a flight goes under the death level plus
   a margin. Verifiable: `//bot flyto` on Reshanta flies, and fly-zone maps behave as before (their unit tests and a flight in Eltnen).
2. **Flight links, written offline.** `NavmeshTool <map> flights` pairs the large islands (a size threshold like the glide rules) whose edges come
   within a flight's reach, and keeps a few links a pair: a take-off cell on one edge, a landing cell on the other, both ways. Written to its own file
   beside the mesh and the glide links, read on the next start, like them. Verifiable offline: how many pairs, the file's weight, and the obelisk audit
   counting links as edges (both Reshanta obelisks should reach the large islands).
3. **Routes that fly.** The route search takes a flight link as an edge like a glide, costed by its time in the air plus the take-off and the landing,
   and a hop never longer than a flight's points pay for with a reserve. Only for a daeva on a map that allows flight. Verifiable offline:
   `NavmeshTool 400010000 path` between two islands returns a route with a flight leg; across three islands, two.
4. **Flying the leg.** On reaching a take-off waypoint the move controller hands the body to `BotFlight`, which flies the link as a cruise (climb,
   level run, descent), each stretch asked of the game's geometry first, the way `cruiseOrWhyNot` does; blocked at every height, the link is refused for
   that bot and the route planned again without it. Short of points, the bot waits on the edge until it has them. Landed, the route goes on.
   Verifiable in game: a bot sent across (inbox `teleport` and an errand), the log naming the take-off, the landing and the points spent.
5. **The Abyss lived in.** Reshanta populated wider (residents on each large island), measured against 0: routes refused, grounds given up, rescues,
   bots in the air at once, and a squad crossing. Fortress and artifact zones (sieges) are left out of the landings; a crossing into the other
   faction's ground is refused until wild PvP is a decision rather than an accident.
6. **Gliding on the way down** (left out of milestone 4 of [flight-plan.md](flight-plan.md) for this): a long descent glides and costs half the points.
   Only if the measure of 5 shows hops short of points.

Done when: on Reshanta, a resident reaches a hunting ground on another island by air, routes refused fall to a tenth of 0's, and no bot is ever left
in the air or killed by the void.

## Built so far (17 October)

- **1:** `BotFlight.fliesEverywhere` reads `WorldMap.isFlightAllowed`, and on such a map `insideAFlyZone` is any point above `floorOf` (death level plus
  100 m) and `ceilingFor` is the height asked for. Only the Abyss, Panesterra, Azoturan and test maps carry the flag. A take-off on Reshanta flew 25 m
  up and back in 13 s for 13 fp.
- **2:** `FlightLinks` samples the edges of islands of 2000 surfaces or more, one per 4 m square, and pairs them across up to 180 m and 200 m of height,
  three a pair and direction, 40 m apart. Both ends must be **open up to the lowest run** (15 m above the higher end): the first two files had links
  landing under an overhang and under the island they left, and every height was blocked in game. `NavmeshTool 400010000 flights`: 20926 links, 36 s,
  needs the server stopped (about 3 GB of heap). The obelisk audit passes both of Reshanta's obelisks; a route between them, 3 km apart, still runs out
  of the 2 s budget.
- **3:** a flight link is a `GlideLink` with `flight` set, chained by `GlideRoutes` like a glide and named in `Route.flights`. The planner's air test asks
  `BotFlight.crossing` (a climb, a level run and a descent at 15, 25 or 45 m above the higher end), and **links found blocked are left out and the
  chains worked out again** (up to 4 rounds): the fewest-hop chain was the only one offered, and one blocked crossing made the whole journey a proof of
  "no route".
- **4:** `BotMoveController.flyAcross` hands the crossing to `BotFlight.flyCrossing`, waits on the edge while short of points or inside the take-off
  cooldown, and the flight, once landed, asks for the rest of the journey itself (a bot sent by command has no decision tick to do it). Watched: Naronwyn,
  sent by `goto` from the Elyos island to 1250 850, walked 54 s to the edge, flew 56 m across and 191 m down in 25 s for 24 fp, and walked on to arrive.
- **5 (in progress):** the mainland of a map flown everywhere is every set of islands a flight joins both ways holding 5 places or more
  (`BotPlaces.mainlandsByAir`, `NavmeshService.joinedByFlight`): Reshanta keeps 120 of its 134 places in five sets, against 18 on one island. The east
  side (Teminon and 80 islands) meets the rest only through fortress 1131. Nobody lives in a fortress's zone and no link lands in one
  (`BotPlaces.isSiegeGround`, FORT zones only: an artifact's zone is where its effect reaches, and one of Reshanta's is the whole map), nor on an island
  only the other faction's people live on (`ownerOfGround`, asked through `AirTest.mayLand`). Rehousing keeps a resident on its own side's places: the
  director had moved nine Elyos residents into Primum. Populated to 86 (60 Elyos, 26 Asmodian), about 30 awake.
- **Measured (30 min, ~30 awake, 15 bot-hours):** 102 "has no route" (6.8 per bot-hour against 8.5), 32 grounds given up (2.1 against 5), 12 trips
  refused, 6 rescues (0.4 against 1), 36 crossings planned, 8 flown, none refused, no void death. **70 of the 102 are inside Teminon and 22 inside
  Primum**: their ground is dozens of small floors a few metres apart, joined by glides only, and a 13 m walk there runs out of the 2 s budget. Outside
  the bases, about 0.7 per bot-hour, the tenth asked for. A route past 1.2 km still runs out of the budget; residents work within 250 m.
- **The bases' ground** (after the measure): Teminon's stairs are building faces of about 57 degrees, read as walls; the map's buildings now walk up to
  62 (`MapSlopes.buildingDegrees`) and the mesh and flight links were written again (19510 links). Routes between the bases' civilians: Teminon 29 of 34
  to 34 of 34, Primum 34 of 34. The director wakes each side for its share of the field (it woke 26 Elyos and 3 Asmodians), swapping two a review.
- **The evening of 17 October:** places within a wander, shops and the death routine are measured in three dimensions (the upper layer stands over
  the bases); no shop more than 300 m above or below; a bot revived on another layer than its home is taken home (not in a group); an attacker is
  never beyond the camp; a spot search has a 1.5 s budget and obelisks on another layer are not searched (start 259 s to 132 s, stop 510 s to 3 s);
  the director wakes hunters for the grounds nobody works, by side; Abyss residents are made 3 levels above their place and live nowhere near an
  elite hostile to their side; chains of flights are ranked by distance; routes keep away from and never land beside a live creature above the
  level gap. Refused routes on the map: 336 to 41 a half hour. Still open: the upper layer's deaths (about one a bot a half hour before the level
  bonus, not measured since), long upper-layer journeys that still run out of the 5 s budget, and the squad crossing (one squad flew three crossings
  and was killed on landing by level 65 Balaur elites, which led to the live dangers).
- **Later that night:** live dangers are kept from landings only (kept from the walk too, a 900 m journey planned offline in 0.7 s gave up after
  7.5 s among 726 of them); a start stretch the body could not walk onto is dropped from the plan; a revived bot is taken home once recovered. Upper
  layer, 25 residents awake, 30 min: 35 refused routes, 1 rescue, 61 crossings flown, none refused, 40 deaths, 36 of them four bots of classes with
  no playbook (spirit master, aethertech, bard, gunner), most to ranx destructors; the other 21 died 4 times. Offline, 17 of 20 random upper-layer
  journeys of 300 m to 1 km are planned, in 0.15 to 3.3 s. The squad crossed twice by air, together, and arrived; it is killed by level 65 Balaur
  met on foot, which the Abyss is.
- **Seen on the way:** a bot teleported by the inbox to another map stays out of the world ("despawned objects cannot move"); the test used a bot already
  on Reshanta.

## Risks

- **Memory.** Building Reshanta's ground takes about 3 GB of heap, and the server holds 2.8 GB on a machine with 2.5 GB free while it runs: milestone
  2 needs the server stopped, once per map.
- **The geometry, not the mesh, decides whether air is clear**, and only the server can ask it. A link written offline is a candidate; every flight
  asks again, and a link that is never clear costs a refusal, not a crash into a rock.
- **PvP.** The Abyss is the other faction's ground too, with guards and fortresses. A route through it can land a bot in a fight it did not choose;
  the landings are filtered, and `retreatIfLosing` is what is left.
- **Load.** A route with flight links is planned on another thread, as glides are. Longer graphs mean longer searches; the time budget of the long
  walk (1.5 s) holds, and a search that runs out says "gave up", not "no route".
- **The audit log.** A flight faster than `FLY_SPEED`, or a take-off inside its cooldown, is written up as a hack; the existing guards in
  `BotFlight` apply unchanged.
