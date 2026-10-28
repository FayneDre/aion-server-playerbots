# Flying with a player: plan

Status: in progress: milestones 1 to 5 validated, 6 waits for a time and a bot name from the tester.

Milestones 1 to 5 built on 19 October and shipped in `patch-2026-10-19-3`, validated in game on 27 October. Milestone 6 waits for a
time and a bot name from the tester.

The tester's report of 19 October, seven points, all about flight and the Abyss. This plan maps each one to its cause in the code and in that evening's
log (`server_console.log`, player Sithis, grouped bot Xanirel), then to what was changed. Background: [flight-plan.md](../history/flight-plan.md) and
[abyss-crossing-plan.md](abyss-crossing-plan.md).

## What was reported, and why

| # | Reported | Cause |
|---|---|---|
| 1 | Grouped bots land as soon as the player lands, wherever they are: far behind, among the monsters | `BotFlight.followOn` called `comeDown` the moment the leader stopped flying: straight down from where the bot was |
| 2 | Bots do not take the flight accelerators (the rings) | an escort holds a spot 4 m off and cuts corners between re-aims, so it never crosses a ring's plane; nothing looked for rings |
| 3 | Bots use no speed or stat scrolls and no wind serums | nothing used them; 89 of 1870 bots carry the starter scrolls, none carry a serum; vendors sell only the Lesser Wind Serum, no scrolls |
| 4 | A grouped bot that falls into the Abyss's void does not die and is put back on the player | 20:54:35, the leader glided off a ledge and landed 7 s later; the bot, over the void, found no ground: `floorUnder` "landed" it in mid air (`has no ground under it ... stays where it is`), and 13 s later the rescue put it beside the player |
| 5 | Bots do not attack in flight | `decide()` returned at once for a body in the air, and `fight()` only knew walking: station, chase |
| 6 | Enemy bots stop the PvP fight when the player takes off | a flying target has no floor to route to: the chase failed or broke the leash (21:08:06, Morear "leaves Sithis alone: it is beyond its camp") |
| 7 | A bot fell into the void at the Terminon Training Camp, stuck at its entrance, then teleported inside | not found: the only in-group rescue of the evening is the incident of point 4, 460 m from the camp, and the log has no rescue at the camp itself |

## Milestones

**1. Landing where the leader landed. Built.** When the leader lands, the escort flies to a spot 4 m behind them on the ground (`rejoin`): level to
above it and down when that air is clear, straight at it under an arch or a roof, and the old descent only when neither is clear, the spot is past
300 m or the points are short (the reserve kept for exactly this). Watch for `follows <player> down to` in the log, and for no more
`has no ground under it`.

**2. The void kills. Built.** A flight that must end with nothing under it looks first for the nearest ground its points carry it to, in rings of 15 m
below it (`toNearestGround`): a player out of breath over the void makes for the nearest island. With nowhere to go on a map that flies everywhere,
the body falls at 60 m/s to under the death level and `finish` lets the engine's own rule kill it (`die()`, as `ZoneLevelService` does for a player).
Watch for `falls into the void` then `fell into the void after`. A map without a void keeps the old behaviour, logged.

**3. Serums and scrolls. Built, supply open.** `BotPotionManager.tryFlightPotion` drinks a wind serum in the air under half the flight points (once a
second at most, the serum's cooldown spaces the rest); `tryScroll(stat)` reads a scroll that raises a stat unless an effect already does: flight
speed on any take-off that goes somewhere, run speed when a grouped bot on foot is 30 m behind its leader, attack speed (weapon classes) or casting
speed (the rest) when a group fight is coming. Vendors' Lesser Wind Serums are bought on every shop visit by a daeva, and never sold as junk.

**Where scrolls come from: handed over, decided 19 October.** No vendor sells them (crafted, broker, rewards), so buying them would make no sense:
`BotOutfitter.stockScrolls` gives 20 of each of the four kinds (running, flight, attack and casting speed), the strongest the bot's level may read
(lesser from 10, plain from 20, greater from 30), at creation and at `//bot regear`, topping up rather than adding. Bots already in the world get
theirs with `//bot regear all`.

**4. Rings. Built for the escort.** The escort watches its leader's last stretch with the engine's own test (`FlyRing.isCrossed`) and, when the
leader went through a ring, flies through its centre in the same direction before re-aiming; the engine's observer gives the bot Wings of Aether as
for anybody. Watch for `follows <player> through the ring`. Not done: crossings planned on Reshanta do not route through rings.

**5. Fighting in the air. Built.** Two cases, one mechanism: the flight holds the body within reach of a target (`BotFlight.engage`), at 80 % of the
weapon's reach for weapon classes, 15 m for casters, at least 2 m over a target on the ground, re-aimed every 1.5 m; it holds still while a cast is
under way. The attack loop swings and casts from there (`fightInTheAir`), without stations or chases.

- *Escort (point 5)*: `fightFromTheAir` runs in the decision tick of a bot following its leader through the air: mates' care first, a healer stays
  beside the leader, the others take the group's target or whatever attacks them.
- *Pursuit (point 6)*: a bot fighting alone whose enemy player flies out of reach casts at them when something reaches, and otherwise takes off after
  them (`tryPursue`, under the usual flight conditions: daeva, fly zone, half its points). When the player lands the bot lands beside them and the
  fight goes on; when the fight ends the bot comes down. A grouped bot does not pursue: it follows its leader.

Not known yet, to watch in game: skills that move the body (charges, leaps) used in the air; a healer's cast-time heals while it moves beside its
leader; whether a ground monster can hit a bot hovering 2 m above it as it would a player.

**6. The Terminon Training Camp entrance (point 7).** Needs the time and the bot's name if it was not the 20:54 incident. The log has a separate
hotspot 100 m south of the camp (15 rescues of residents at 2990-3030 905-935 in four hours): residents whose hunting grounds are on another island.

## How to verify, in one session

A player in the Abyss with one or two bots in the group: fly ahead and land at a camp (1), glide off a ledge over the void (1, 2), fly through
Primum's or Teminon's rings (4), attack a flying monster or an enemy from the air (5), and, alone, provoke an enemy bot then take off (6). Then:
`grep -a "follows .* down to\|falls into the void\|through the ring\|wind serum\|after .* who flew out of its reach" server_console.log`.
