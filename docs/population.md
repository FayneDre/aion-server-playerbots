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

A bot levels and changes region. It is not a resident or an adventurer; it is a character with a career.

Bots a **player** created carry an owner and are never touched by the director — not logged out, not moved, not retired.

## Three kinds of presence, three different sources

The single biggest error in the first model was having *one* notion of population, fed by how many monsters a map holds. Measured consequence: the
rule gave Brusthonin **311** inhabitants for its 27 civilians, while Sanctum — where players actually gather — got 28.

| presence | rule | why that source |
|---|---|---|
| **civic** | one bot per civilian npc, **per place**, capped by map category | a settlement's npc count *is* how busy the world meant it to be |
| **field** | `4 + 6 × players of that band on the map` | follows people, not monsters |
| **transit** | 2 per map | one traveller you pass is worth ten motionless farmers |

**Per place, not per map.** Computing per map and then sharing out compresses twice: the big place is crushed and the small one gets a floor it has not
earned. Measured at the old setting: a 4-npc roadside camp drew 7 inhabitants while Sanctum's main plaza drew 9. Two errors in opposite directions at
the same setting is the signature of a wrong shape, not a wrong value.

Map categories exist because npc density and player interest diverge, and no derived formula fixes that. Oriel and Pernon hold **1624 civilians each**
— as many as seven Sanctums — and nobody lives there; people pass through. Three lines saying "these are services" beat a formula that is elegantly
wrong.

| category | how it is recognised | cap |
|---|---|---:|
| capitals | civilians ≫ hostiles, no level band | 150 |
| capital annexes | small, no band | as measured |
| field regions | civilians, hostiles and a band | 90 |
| contested | a band of 54-65 | 90 |
| service towns | thousands of civilians, no band | 30 |
| battlefields | **zero** civilians | none — combatants only, during a siege |

## The pool and the director

Every bot character is in the pool; only a fraction is in the world. The roster table already has the `in_world` column — what was missing was
somebody to decide it.

The **director** runs every few minutes: demand per map and per band from the rules above, multiplied by attention, then it connects bots from the pool
to close the gap and disconnects the surplus. Never within sight of a player, and it pre-fills a player's capital and last map before they arrive, so
they never watch a region assemble itself.

| situation | attention |
|---|---:|
| a real player on the map | 1.0 |
| capital with no player | 0.6 |
| ordinary map, no player | 0.2 |
| nobody online anywhere | ~0 — only the economy stays up |

Its second job is stocking: *"do I have a level-16 Asmodian scout?"* If not, one has to be grown. This is where the pool meets progression.

**And it removes the need to travel.** A bot that outgrows Poeta logs out and logs back in at Verteron. Nobody watches a ten-minute walk, and a walk
can get stuck. Following a *player* between regions is a different matter and is cheap — see [roadmap.md](roadmap.md) under Flight.

## Pacing progression

`Rates.XP_HUNTING` multiplies by the `BOOST_HUNTING_XP_RATE` **stat**, so a bot's pace is adjustable per character with no core patch. Dwell time per
band is therefore the control knob, and the arithmetic at the top says what each setting produces.

Where a bot hunts comes from `quest_data.xml` read as an **itinerary**, not as quests: 8043 quests, every one carrying a minimum level, 93% carrying a
zone, and 4282 distinct npc ids between them. That answers "where does the game send a character of level 4, and what should it kill" as a file read —
no handlers, no quest state. Quests that *gate* progression are completed outright, as the ascension quest already is.

## What it costs

Measured on 45 bots: the whole server used **0.116 core** — 0.73% of a 16-core machine — and 1260 MB of a 2560 MB heap, with **83,286 npcs** already
alive in the world. A bot is one of those creatures plus a skill list, an inventory and a decision tick, so per-bot memory is not the constraint and
never was.

| target | bots | note |
|---|---:|---|
| everything, full density | ~2200 | 28 maps; ~1100 per faction |
| **recommended** | **~1500** | the whole game at density 0.68 |
| the old rule, extrapolated | 1923 | and misallocated: see above |

The faction split comes out even on its own — 747 against 756 at 1500 — because the world was built in mirror: Poeta 45 ↔ Ishalgen 53, Sanctum 250 ↔
Pandaemonium 266, Theobomos 179 ↔ Brusthonin 181. No per-faction tuning, ever.

Four things make the number affordable, and the first is the one that matters:

1. **Adaptive ticks** — 1 s with a player in the known list, 3 s with one on the map, 10 s otherwise. Most of the world is unobserved most of the
   time, so this divides the dominant cost by four to six.
2. **A thread pool of their own.** Bot ticks currently share the server's 16 scheduled threads with respawns, effects, sieges and saves. At 1500 bots a
   burst of ticks would delay the engine itself. Bots must never be able to starve the world.
3. **Lazy mesh loading** — held only for maps that have somebody on them, which is what makes 28 maps fit.
4. **`-Xmx8192m`** instead of 2560, and saves staggered across their interval instead of fired in one burst.

Per-place counts of 20 to 40 also keep the crowding term small: known-list scans are quadratic in the neighbourhood, and 40² is nothing. That is a
second reason the per-place framing is the right one.
