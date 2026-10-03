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
like in game within an hour of levelling being turned on. **A villager is exempt**: it lives where the director posted it, not where the ground
suits it, and a village square has no level worth comparing against. Ungated, the two rules moved the same bot back and forth for ever — the
director filling village posts nobody lived at, this evicting every one of them on the next tick, a region asking for arrivals and departures in the
same breath with the village count stuck at 11 of 25.

**And it leaves a home that keeps killing it.** Three deaths inside ten minutes and the bot moves, with no theory of why. This is the backstop under
every rule that is meant to prevent that — the level a ground is worth, the fights a bot may start, the ones it breaks off from — because each of those
is a judgement read from data, and a judgement read from data is wrong somewhere.

**Ground is judged strictly upwards, loosely downwards.** Four either way sent a bot to work ground four over its head while it refused any fight more
than three above it — it stood in a camp it could attack nothing in, and the camp attacked it. Downwards the question is whether a fight is worth
having; upwards it is whether the bot survives, and two is what the measurement supports: about 45% of a region's ground open at the bottom of its
band, 80% in the middle, all of it at the top, across all thirteen outdoor regions. It costs no population, only where each resident wanders.

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

Moved to [population-director.md](population-director.md).

## Standing room

Every bot of a camp used to be given the same point to walk to and the same five metre tolerance for having arrived, so they settled on top of one
another — a pile of characters with a ring of corpses around it, which reads as a spawner rather than as people working a ground. Each now has its own
spot a few metres out, derived from its object id rather than drawn at random: a random spot would move every time the bot came back, and the camp
would reshuffle itself whenever anybody returned to it. The same bot stands in the same place all its life.

The spread is kept inside the anchor tolerance, so a bot on its own spot is still *at* its anchor and nothing that asks that question has to learn a
second one.

## Flasks, and the errand that buys them

A bot used to walk to a shop only to sell, so it could drink only what it had picked up, and what it fights drops next to no flasks. It now buys mana
and life potions while it is there, from the purse it has carried since stigmas, and one down to half its stock makes the trip for that reason alone.

It takes the strongest flask it may use, not the cheapest: the walk is the expensive part, not the kinah. The price is left to the engine, so the
order is offered in full and then as one of each rather than a copy of that sum being kept here to drift.

**The flasks were unreachable a second way.** Every drinkable restores through `procmphealinstant` or `prochealinstant`; `MPHEAL` and `HEALINSTANT`
belong to a healer's spells. The bot read the spell effects, so it could not match a potion at all — not even a looted one. Both sets are read now.

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

Every new character is given kinah, ten thousand per level, because the engine charges 25 000 to socket a stigma and a bot earns only by selling loot —
one created at level fifty has never earned a coin. **This mints kinah**, which costs nothing while bots trade only with the engine and is the thing to
look at again the day they trade with players.

## What it costs

Measured on 45 bots: the whole server used **0.116 core** — 0.73% of a 16-core machine — and 1260 MB of a 2560 MB heap, with **83,286 npcs** already
alive in the world. A bot is one of those creatures plus a skill list, an inventory and a decision tick, so per-bot memory is not the constraint and
never was.

**The settled figure is two thousand across the 28 maps worth populating**, and the three densities above are what produce it. None of them moves on
its own: raising the countryside empties the villages at a fixed total, and that is arithmetic rather than a fault in the model.

**Two thousand is the quiet figure, not the number of characters**, and the difference is the whole arithmetic of the pool. The busy countryside is
roughly twice the quiet one — Poeta asks for 19 inhabitants quiet and **37** with a player on it — so the pool must be stocked at the busy plan or the
surge has nobody to draw on. Created at 19, measured in game: a player arrived, the target rose to 37, the director had nobody left to wake, and a map
with a player on it was no busier than an empty one. So the database holds about **3700 characters** for a quiet population of 2000. Characters are
cheap, a row and an inventory; it is the decision ticks that cost, and attention is what governs those.

| | bots | note |
|---|---:|---|
| **settled, quiet** | **~2000** | 960 Elyos, 1036 Asmodian, 279 on contested ground |
| characters in the database | ~3700 | the busy plan, so the surge has somebody to wake |
| reachable today | ~1780 | Reshanta's 217 wait on flight navigation |
| capitals and annexes | 20% | it was 29% before the countryside had a share of its own |
| Poeta | 19 quiet, 37 busy | a small starter valley's honest share of a populated world |

The faction split comes out even on its own, because the world was built in mirror: Poeta 45 civilians ↔ Ishalgen 53, Sanctum 250 ↔ Pandaemonium 266,
Theobomos 179 ↔ Brusthonin 181. No per-faction tuning, ever. The 76 bots between the two halves come from the field cap biting unevenly, not from the
model.

**Which side a bot is on is asked of the place, not of the map.** A civilian's tribe already says it — `GENERAL` is a friend of `PC` and
`GENERAL_DARK` of `PC_DARK` — so each settlement carries its own faction and a hunting ground takes it from the nearest one. A faction's region answers
the same everywhere and is unchanged; contested ground (Reshanta, Silentera, the Idian Depths, Kaldor, Levinshor, Panesterra) has no single answer, so
one `//bot populate` fills Teminon with Elyos and Primum with Asmodians. Balaurea is **not** contested, and the startup pass still covers the faction
regions only — Reshanta has no mesh and is the largest map in the game.

Giving the countryside its own share is also what took the weight off the capitals. Hunting country is on another scale from settlement —
Brusthonin's grounds cover 3.3 million m² against its villages' 76 thousand — so a share for the field moved people into the regions without making
any single place denser or thinner than before.

Four things make the number affordable, and the first is the one that matters:

1. **Adaptive ticks** — 1 s with a player in the known list, 3 s with one on the map, 10 s otherwise. Most of the world is unobserved most of the
   time, so this divides the dominant cost by four to six.
2. **Threads of their own**, which `BotScheduler` now gives them: ticks, route searches and arrivals each in their own lane, so neither the engine nor
   the bots can starve the other.
3. **Lazy mesh loading** — held only for maps that have somebody on them, which is what makes 28 maps fit.
4. **A larger heap, which is an installation step and not a repository change.** `game-server/dist/start.bat` ships `-Xmx2560m`, which is upstream's
   default and the right one for a machine of unknown size; a populated world wants **`-Xmx8192m`** and it is edited in the installed copy. Saves are
   staggered across their interval rather than fired in one burst, which needs no setting.

Per-place counts of 20 to 40 also keep the crowding term small: known-list scans are quadratic in the neighbourhood, and 40² is nothing. That is a
second reason the per-place framing is the right one.
