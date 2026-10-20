# Living capitals: what milestones 0 and 1 found

The measurements and fixes behind milestones 0 and 1 of [living-capitals-plan.md](living-capitals-plan.md), 20 October 2026.

## What the capitals held (10:18 to 12:48)

- **Not mirrored**: 67 bots lived in Sanctum against 151 in Pandaemonium, though the model gives them 250 and 266 civilians.
- **A third of a capital day did nothing**: 196 residents drew FARMING 1543 times. `BotDay.farm()` finds nothing to hunt in a city.
- **Spells of 3 to 10 minutes**, so a resident was never at the same place twice: the "coming and going".
- **Fruitless flask trips**: about 14 shop trips per bot, one resident walking 25 m to a keeper with no flasks nine times in 90 minutes.
- **Purses**: about 890 000 kinah per bot on average.

## Sanctum's missing half

- The director wanted 62 for Sanctum: 18 of its 34 places were dropped at startup, 17 as islands. Its three centre bridges
  (`bu_l_centerbridge_01a/02b/03c`) carry `WALK | PHYSICAL_SEE_THROUGH`, which the engine collides with and the generator read as no-walk volumes.
- A building's walk-flagged solid part is now a floor (`HeightfieldBuilder`), as it already was in instances. The authored volumes
  (`nowalk_obstacle*`, `walk_obstacle4_100m`) keep their flags. Sanctum's main island went from 213 498 to 357 130 cells; Sanctum kept 24 of 34
  places and wanted 96, and `populate 110010000 0` created the 38 characters it lacked.
- Pandaemonium and 710010000 were regenerated too. Pandaemonium still loses the same 4 of 36 places, which are elsewhere. Poles, tents and colliders on
  12 more maps carry such parts, and those maps were left alone.

## Rides (`RideLinks`)

The tester found three rides the geometry does not hold: a lift at the Sky Canal, a lift in the middle of the Artisans Hall up to the Protectors Hall
(the terrace at z 811), and a boat from the Airship Dock to the Outer Dock (the southern places at z 520 to 530). Their ends come from `//bot note`:

| ride | from | to | time |
|---|---|---|---|
| boat | 1669 1497 543 | 1791 2005 518 | ~180 s |
| Artisans Hall lift | 1867 1507 591 | 1867 1508 805 | ~20 s |
| Sky Canal lift (left out) | 1692 1615 567 | 1777 1616 593 | ~20 s |

- `NavmeshTool <map> rides` puts each end on the largest stretch within 15 m and writes `<map>.ride`; `navmesh.ps1` rewrites it with the mesh.
- The planner chains rides like glides, and anyone may take one. The body is hidden as it boards (`HIDE20`, as a hiding skill), waits the ride's
  time, and is shown at the arrival (`BotMoveController.rideAcross`); the AI decides nothing meanwhile. The mainland takes in what a ride joins.
- The Sky Canal lift is a short cut inside one stretch: the obelisk walks into the city since the bridges (726 m).
- **Watched**: Sanctum keeps 29 of 34 places and wants 116. An Outer Dock resident took the boat by itself to sell in the city; a bot sent with
  `goto` from the Artisans Hall walked, boarded and landed. The tester saw a rider appear "out of nowhere", which led to the hiding.
- **Open**: the Artisans Hall lift lands in a closed hall of 506 surfaces. The Protectors Hall floor (`bu_l_davaholl_holl_01a`, flat) is almost
  absent from the mesh, in fragments of 18 cells under its places, so the terrace stays empty.

### A regression found with them

Short hops are planned on the bot's own thread only on a map without links, and the rides gave Sanctum links: every few steps of 200 residents went
to the four route threads, 13 000 waits and 260 late answers a minute, a `goto` waiting over three minutes. Only air links count now
(`Navmesh.hasAirLinks`): 47 waits and no late answer a minute after the fix.

## The crowd test

On the tester's machine, in Sanctum, bots brought within 15 m with their autonomy off, standing still, no store name over them:

| bots in sight | fps |
|---:|---:|
| 0 | 60 |
| 25 | 60 |
| 50 | about 45 |
| 75 | about 35 |
| 97 | about 30, dips to 27-28 |

So about **40 stores** within sight keep the frame rate over 40.

## The capital day (`TownRoutine`)

- On a town map (`BotPresence.isTown`, the population model's own classification) residents follow spells of 5 minutes: home 30, sitting 15, an
  errand 30, a visit 25, decided from the resident's id and the time of day, so the same every day.
- Errands go to a keeper found by its dialog (`TownServices`), at a spot of the resident's own within 6 m: shops 25, broker 20, warehouse 15,
  crafting 15, teleporter 10, stigma master 8, soul healer 7.
- Quarters of an hour on the clock came first: the tester saw nobody move, and 0 to 2 of 117 Sanctum residents were moving. Spells starting at
  each resident's own moment: 5 to 17 moving, and the tester found it "much better".
- No capital bot has drawn FARMING since. Silentera's single villager follows the routine too: the director's plan gives Silentera no hunter.
