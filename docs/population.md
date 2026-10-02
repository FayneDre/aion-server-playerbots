# How a world gets its inhabitants

The model, and the arithmetic it rests on. For what is left to build, see [roadmap.md](roadmap.md); for the model this replaces, see
[built.md](built.md) under "Retired: residents and adventurers".

## The problem, stated properly

A living world needs a **stationary distribution of levels** across its regions. A character that levels is **non-stationary** — it climbs and leaves.
A fixed cast cannot be both, which is why the first answer was to freeze a bot's level, and why that answer produced two species of bot instead of a
population.

The way out is not stationary individuals but a stationary **flow**. Real servers keep a starter valley alive because new characters arrive at the
bottom as fast as old ones leave the top. That makes the system calculable:

> **population of a band = arrival rate × dwell time**

45 inhabitants in Poeta with a 20-hour dwell needs 2.25 births an hour. Nothing has to be tuned by watching.

It also forces one conclusion worth stating plainly: **genuine progression plus permanently alive low regions means creating characters for ever, or
recycling them.** There is no third option. A bot at the cap becomes an elder for the contested zones and the broker; beyond what those need, the
oldest are deleted and their names freed.

## One kind of bot

A bot levels. It is not a resident or an adventurer; it is a character with a career. Three things hold that career together, and the third is
temporary.

**Its pace.** `Rates.XP_HUNTING` multiplies by the `BOOST_HUNTING_XP_RATE` stat, so `BotPacing` sets it per character with no core patch — the same
door `EnchantEffect` uses. Set to **1%**, which is what a measurement gave and not what two guesses did.

**Its address.** A bot outgrows where it lives. Poeta reads as Akarios 3 and its camps at 5, 6 and 7 — the valley steepens as you walk away from the
village — so when a bot's level drifts more than two from its home's, it moves to a place that fits. Only the home moves; everything written against
the home or the anchor follows by itself. Without this a bot reached seven while still keeping house on ground worth three, which is what it looked
like in game within an hour of levelling being turned on.

**A ceiling, for now.** A bot stops gaining experience at the top of its region's band — Poeta tops out at nine. This is a stopgap and should be
deleted: what *should* happen is that the director finds it a region its new level belongs to. What must not happen meanwhile is a starter valley
quietly filling with characters that have outgrown it.

Bots a **player** created carry an owner and are never touched by the director — not logged out, not moved, not retired.

## Three kinds of presence, three different sources

The single biggest error in the first model was having *one* notion of population, fed by how many monsters a map holds. Measured consequence: the
rule gave Brusthonin **311** inhabitants for its 27 civilians, while Sanctum — where players actually gather — got 28.

| presence | rule | why that source |
|---|---|---|
| **civic** | one inhabitant per **1300 m²** of settlement ground, 940 in a capital | the ground a place covers *is* how busy the world meant it to be |
| **field** | one hunter per **40000 m²** of hunting country, at most 60 a map | follows the country, and the director raises it where players are |
| **transit** | not allocated | the occupation draw already sends residents walking between settlements |

**By the ground, not by the npc count.** Counting npcs was the first answer and a measurement was needed to see why it fails. Akarios holds 21
civilians spread over a 42 m radius — 262 m² each — so one bot per npc put 19 more into the same village and they read as packed because they were.
Sanctum meanwhile covers 155000 m² of settlement against Poeta's 10670, fourteen times the ground, and the ratio gave it three times the people. One
setting made the village crowded and the capital deserted at the same time, which is what counting the wrong thing looks like.

**Per place, not per map.** Computing per map and then sharing out compresses twice: the big place is crushed and the small one gets a floor it has not
earned. Measured at the old setting: a 4-npc roadside camp drew 7 inhabitants while Sanctum's main plaza drew 9. Two errors in opposite directions at
the same setting is the signature of a wrong shape, not a wrong value.

Map categories exist because npc density and player interest diverge, and no derived formula fixes that. Oriel and Pernon hold **1624 civilians each**
— as many as seven Sanctums — and nobody lives there; people pass through.

Which category a map is in is still read from the map rather than from a list of ids: **a region whose civilians outnumber the creatures that gather
in packs is a town.** That separates Sanctum (250 against a handful) and Oriel (1624) from Poeta (45 against some hundreds) and Brusthonin (181
against thousands) on every map measured, and among towns sheer size tells a service from a home.

Measured after the change, on Poeta: **19 inhabitants — 6 in Akarios, one in each of the three camps, and ten spread over separate hunting grounds.**
The old rule gave 43, of which roughly 14 in Akarios and the rest scattered as ones over the countryside; the version before this one gave the
countryside four in total, which left Agher's farm, Cliona lake, Kales farm, the plains and the quarry all empty.

Only one cap is left, and not for density:

| category | how it is recognised | cap |
|---|---|---:|
| capitals | civilians ≫ hostiles, no level band | 150 |
| capital annexes | small, no band | as measured |
| field regions | civilians, hostiles and a band | 90 |
| contested | a band of 54-65 | 90 |
| service towns | thousands of civilians, no band | 30 |
| battlefields | **zero** civilians | none — combatants only, during a siege |

## The pool and the director

Every bot character is in the pool; only some of them are in the world. The pool is not a thing anybody maintains — it is the world-owned residents in
`playerbot_characters` that are not in the set of spawned bots, so there is nothing to keep in step and nothing to go stale.

The **director** reviews every region every 30 seconds: what it holds, against what it should hold at this moment. The difference is handed to a lane of
its own, one thread, because loading a character is some twenty blocking database round trips and a batch of them on a tick thread would stop the bots
thinking.

**Only the countryside breathes**, and the reason is geometric rather than a matter of tuning. A player arrives at a bind obelisk, in a town — where this
module deliberately puts bots' bind points too. Every villager's home is one place's centre with fifteen metres of scatter, so while that player stands
there all of them are inside the ninety five metres a client is told about: a village thinned while nobody watched could never be refilled until the
player left, and the one place a person looks at would be the one place the director cannot fix. So settlements are a fixed cast, and the hunting grounds
— spread apart by construction, nowhere near a bind point — are what fills and empties.

| situation | share of the countryside awake |
|---|---:|
| a real player on the map | all of it, at the busy density |
| the five minutes after they leave | the same, decaying rather than snapping back |
| a quiet map, somebody playing elsewhere | a fifth |
| nobody online anywhere | a twentieth |

That last row was going to be zero — "only the economy stays up" — and zero is wrong. A sleeping bot earns no experience, and this model is a *flow*: a
band holds what arrives times how long it stays. Put the whole world to sleep and no career advances overnight, so a server comes back each morning to
the population it had on its first day. A twentieth keeps the careers moving at a cost nobody is observing.

Two rules are worth stating as rules, because neither can be repaired after the fact:

- **Nobody ever watches it happen.** A wake is tested against the real players on that map at 150 m: the engine's own 95 m sight radius, plus room for
  somebody walking towards the spot. A sleep asks the bot's own known list, which is not an estimate of who is near but exactly the set of clients that
  would be told. A refused wake is simply not done, and the next review asks again.
- **The director never picks the moment.** It decides that a region holds too many; the bot decides whether it may go now. In a fight, dead, mid-journey
  or grouped with a real player, it stays.

**And it removes the need to travel.** A bot that outgrows Poeta logs out and logs back in at Verteron. Nobody watches a ten-minute walk, and a walk can
get stuck. Following a *player* between regions is a different matter and is cheap — see [roadmap.md](roadmap.md) under Flight.

Two jobs are designed and not built, and they are in [roadmap.md](roadmap.md): **stocking** — *"do I have a level-16 Asmodian scout?"*, and growing one
if not — and **pre-filling a map before a player arrives**, which is what would let settlements sleep too.

## Pacing progression

`Rates.XP_HUNTING` multiplies by the `BOOST_HUNTING_XP_RATE` **stat**, so a bot's pace is adjustable per character with no core patch. Dwell time is
therefore the control knob, and it is set to **1%** of the ordinary rate.

That number was guessed at a quarter and the guess was out by a factor of twenty five, which only a measurement could show. Two attempts to read it
from the database gave nothing usable: bots are saved on a five minute sweep, so a five minute window reads two saves or none, and a level read
afterwards says nothing about when it was reached. Logging each level at the moment it lands settles it in minutes.

What one percent produces in hours has **not** been measured, and the arithmetic cannot be done on paper because of the cap in `Rates.XP_HUNTING`:

```java
Math.min(xp * rate, player.getCommonData().getExpNeed() * 0.2f)
```

The cap is applied *after* the rate, so it binds at the full rate and not at one percent — which makes the two regimes behave differently in kind, not
only in degree, and an extrapolation from one to the other wrong. Two readings taken an hour apart disagree by a factor of two to three for exactly
that reason, and neither is worth writing down.

What is measured is the direction, over nineteen bots on one map:

| | level gains |
|---|---:|
| at 25%, per bot | one every 1 to 4 min |
| at 1%, whole population, 16 min | **one** |

That is the honest state of it. The number of hours a region takes comes from watching it over days, not from a calculation, and until it is watched
the population model's arrival rate is an assumption rather than a figure.

**One percent is the floor.** `StatSetFunction` carries an int, and zero is not a slower pace but no experience at all. Anything slower than a day per
region has to come from the director retiring a bot out of the region, not from the bot earning less.

Where a bot hunts comes from `quest_data.xml` read as an **itinerary**, not as quests: 8043 quests, every one carrying a minimum level, 93% carrying a
zone, and 4282 distinct npc ids between them. That answers "where does the game send a character of level 4, and what should it kill" as a file read —
no handlers, no quest state. Quests that *gate* progression are completed outright, as the ascension quest already is.

## What it costs

Measured on 45 bots: the whole server used **0.116 core** — 0.73% of a 16-core machine — and 1260 MB of a 2560 MB heap, with **83,286 npcs** already
alive in the world. A bot is one of those creatures plus a skill list, an inventory and a decision tick, so per-bot memory is not the constraint and
never was.

**The settled figure is two thousand across the 28 maps worth populating**, and the three densities above are what produce it. None of them moves on
its own: raising the countryside empties the villages at a fixed total, and that is arithmetic rather than a fault in the model.

| | bots | note |
|---|---:|---|
| **settled** | **~2000** | 960 Elyos, 1036 Asmodian, 279 on contested ground |
| reachable today | ~1780 | Reshanta's 217 wait on flight navigation |
| capitals and annexes | 20% | it was 29% before the countryside had a share of its own |
| Poeta | 19 | a small starter valley's honest share of a populated world |

The faction split comes out even on its own, because the world was built in mirror: Poeta 45 civilians ↔ Ishalgen 53, Sanctum 250 ↔ Pandaemonium 266,
Theobomos 179 ↔ Brusthonin 181. No per-faction tuning, ever. The 76 bots between the two halves come from the field cap biting unevenly, not from the
model.

Giving the countryside its own share is also what took the weight off the capitals. Hunting country is on another scale from settlement —
Brusthonin's grounds cover 3.3 million m² against its villages' 76 thousand — so a share for the field moved people into the regions without making
any single place denser or thinner than before.

Four things make the number affordable, and the first is the one that matters:

1. **Adaptive ticks** — 1 s with a player in the known list, 3 s with one on the map, 10 s otherwise. Most of the world is unobserved most of the
   time, so this divides the dominant cost by four to six.
2. **A thread pool of their own.** Bot ticks currently share the server's 16 scheduled threads with respawns, effects, sieges and saves. At 1500 bots a
   burst of ticks would delay the engine itself. Bots must never be able to starve the world.
3. **Lazy mesh loading** — held only for maps that have somebody on them, which is what makes 28 maps fit.
4. **A larger heap, which is an installation step and not a repository change.** `game-server/dist/start.bat` ships `-Xmx2560m`, which is upstream's
   default and the right one for a machine of unknown size; a populated world wants **`-Xmx8192m`** and it is edited in the installed copy. Saves are
   staggered across their interval rather than fired in one burst, which needs no setting.

Per-place counts of 20 to 40 also keep the crowding term small: known-list scans are quadratic in the neighbourhood, and 40² is nothing. That is a
second reason the per-place framing is the right one.
