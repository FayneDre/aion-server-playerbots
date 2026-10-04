# What is left to do

Written to be picked up cold. For what already works and why it was built that way, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population.md). For what this engine does to a bot that no
documentation warns about, [engine-traps.md](engine-traps.md) and [world-and-data-traps.md](world-and-data-traps.md).

Each section below says what the work is *for*, what it needs, and what actually blocks it. The order is roughly by value per unit of effort, not by
ambition.

## No blocker left

**Stigmas are now socketed** — `BotStigmaFitter`. It was the last one, and it mattered because a character past level 20 without stigma stones fights
at a fraction of its strength, so every measurement of bot combat above that level was of a crippled character. What it needed turned out to be four
things, none of them optional: the stigma quest marked complete (1929 / 2900, exactly as the ascension quest already was, since the socket count is
**zero** until it is), stones chosen from the class's own skill tree, kinah to pay the engine's charge, and `StigmaService.onPlayerLogin` on the way
into the world — without that last one the whole thing would have worked once and gone silent at the first restart.

The three that stood here before were cleared in `0118bfe9e` and this file had not caught up: names gained a middle syllable so the space is no longer
390, `BotPlaces` tests `GENERAL_DARK` as well as `GENERAL` so the Asmodian faction has settlements, and the roster carries an `owner_id` that the
director is required to leave alone.

## What the director still does not do

The pool is built: inhabitants are created asleep, and the director wakes and sleeps the countryside to match each region's target without anybody seeing
it ([population.md](population.md)). Three pieces of it were deliberately left out.

**Stocking.** *"Do I have a level-16 Asmodian scout?"* — and growing one if not, plus retiring the oldest at the cap and freeing their names. Without it a
population is whatever `//bot populate` created once; with it the flow in [population.md](population.md) becomes real, and this is where the pool meets
progression. What exists is the depth: `populate` now creates the **busy** plan rather than the quiet one (`b5a0cbd10`), so the surge has sleepers to
wake instead of asking for people nobody had made.

**Pre-filling a map before a player arrives.** The window exists: a teleport knows its destination before the client finishes loading, and the account's
character list is known while somebody is still on the selection screen. It is also what would let **settlements** sleep, which they currently cannot —
the director can only breathe the countryside, because a village is all within sight of the obelisk a player lands on.

**Moving a bot that outgrew its region — across maps.** Within a map it already happens: `BotDay.moveOutIfOutgrown` rehomes a bot whose level has
drifted from the ground it lives on, and writes the new home to the roster. What is missing is the other half, for a bot that has outgrown the whole
region: `BotPacing` stops it at the top of the region's band, which is documented as a stopgap. The director should find it a region its new level
belongs to, which the pool makes cheap — a sleep and a wake somewhere else, and nobody watches a ten-minute walk.

## Dungeons and instances

**The largest player-facing value in the whole project**: a solo player can run group content. Everything else makes the world look alive; this makes
it playable alone.

Needs group roles (below) first, and instance entry for a connectionless player — the group's instance is created for the leader and the members are
teleported in, which is the same `TeleportService` door bots already use. Boss mechanics can wait: a bot that simply fights correctly clears most
low-level instances, and the ones it cannot are a later refinement rather than a prerequisite.

## Group play: tank, healer, support, damage

**Roles exist** — `BotRole`, the one authored table in the module, because the data says what a skill does and never says what a class is *for*. There
are four rather than three: a chanter states 197 physical damage against 17 magical, and filing it under healer left it watching full health bars.
What each role now does is in [combat-skills.md](combat-skills.md). What is left below is the part that needs more than a role to decide.

Corrected while building it: a taunt is **not** `ProvokerEffect`, which this file and the skill notes both assumed. `ProvokerEffect` installs a proc.
The taunts are the 24 skills carrying `BOOSTHATE`, of which the enemy-targeted ones are the real article.

What is left:

- **Damage waits for the tank to hold.** It focuses the tank's target now, but it opens at the same instant the tank does, so the first blow can still
  pull the mob off. Needs a notion of the tank having established aggro, which `AggroList.getTarget(MOST_HATED)` can answer.
- **Composition**: the director should be able to offer a balanced group, which means the pool has to be stocked by role as well as by level. This is
  the same stocking gap listed under the director above, with one more axis.
- **Nobody forms a group.** Bots join a player's; two bots standing in a field never party with each other, so a pure-bot group is only ever something
  a player assembles.

## Flight

Its own problem, and the one that gates the most.

Planned in milestones, with what the engine already provides and what it leaves to the server: [flight-plan.md](flight-plan.md).

Needed for crossing between the Abyss's islands, for gliding shortcuts, for aerial PvP, for a handful of quest routes — and, less obviously, for a
share of the ordinary countryside: every map with `GLIDE` puts npcs on terraces and ledges that no walker reaches, and the civic plan has to drop
those places today — 12 of Eltnen's 152, 5 of Verteron's 123, and 235 townsfolk between them with nobody to keep them company. **Eleven of Eltnen's
twelve stand inside a `ZoneType.FLY` zone**, which is the game's own data saying what they are. What it needs is a second
navigation answer: the mesh describes **surfaces**, and flight is a volume. Plus the flight state machine — flying against gliding, flight time,
landing, and what happens when time runs out over a gap.

Note what is *not* blocked by it: moving a bot between maps. `TeleportService.teleportTo` takes any world id and bots already call it, so the claim
that crossing maps needs teleporters and flight paths was simply wrong.

## The Abyss

**A levelling region, not just endgame**, and excluding it was a mistake. Measured: Reshanta holds **834 civilians — more than Sanctum and
Pandaemonium together** — against 4352 hostiles, median level 33 and ninth decile 50. It is one of the ways a character crosses 25 to 50.

Less blocked than it looks. The mesh labels regions **per surface** rather than per column, which is exactly what stacked islands need, so civic and
field presence on each island is ordinary work. What is missing is the crossing *between* islands — for population the director's teleport covers it,
and for gameplay it needs flight — and, before any of that, **Reshanta's mesh, which has not been generated**. It is the largest map in the game, so
budget the generation rather than assuming it is one more run of the tool.

## Wild PvP and rifts

Priority two, and much closer than RvR — it needs none of the mass coordination.

Rifts are the door to enemy territory and the data is already there (`spawns/Rifts`). The work is: a bot deciding to invade, using a rift, hunting
players rather than mobs on the other side, and knowing when to go home. The engine side is clear — `PvpService` no longer throws when a bot kills a
real player.

The one honest prerequisite is **visible movement**: a bot that teleports into position cannot be intercepted, and being interceptable is most of what
makes open PvP worth anything. That is intra-zone navigation in contested ground, which the mesh already does.

## RvR and sieges

The far goal, and the only section that genuinely waits on flight, because Reshanta is where it happens.

Its supply line is already designed: bots that reach the level cap become the elders the endgame needs, which is what turns the upward drift from a
problem into a feature ([population.md](population.md)). Panesterra's five battlefields hold **zero civilians** — they are not places to live, so
they take combatants only, and only during a siege.

## Economy

Selling works, and now in practice as well as in principle. It did not: shop spots were read from spawn data — where an npc was *placed*, not where
it stands — and 79 of 87 trips ended at an empty spot. Repaired in `baf18e121`, which asks the map for its living shop keepers, refuses a shop it
cannot walk to, and remembers the refusal. Measured over the night of 2 October: **37 sales in 38 trips, and not one "found no shop keeper"**.

Next is the broker (`BrokerService`), then player shops. The broker is built on the same "find the right npc" mechanism, which is now worth
building on.

## Legions

Priority five. Bots forming and filling legions, which mostly falls out of group play plus a reason to gather.

## Gaps nobody had raised

- **Nobody speaks.** A world where no character ever says anything is uncanny in a way that is hard to name and easy to feel. Occasional region chat,
  shouting for a group, answering a trade — cheap, and high value per line of code.
- **Bots do not yield to real players.** `BotTargetRegistry` stops two bots claiming the same mob but nothing yields to a *person*. A player who
  cannot farm because forty bots took the spawns, or whose gathering nodes are always stripped, has a worse server than one with no bots at all. This
  becomes serious at a settled 2000 and it is a design rule, not a tuning value.
- **No gathering or crafting.** It would feed the broker and make the countryside look used rather than merely fought over.
- **Gear does not keep up.** Bots are dressed at creation and wear what they loot. Crossing 1 to 65 needs buying or crafting.
- **Zero automated tests** on roughly 10400 lines, much of it concurrent — the repo has a test layout and other modules use it, the bot module
  simply has none. This is why the combat half of `PlayerBotAI` has not been split: a mistake there is silent rather than loud.

## After the population churn of 2026-10-04

The churn is fixed, and so is most of what it uncovered — see [population-director.md](population-director.md) and the float trap in
[world-and-data-traps.md](world-and-data-traps.md). What the follow-up found, and where it stopped:

**The equality audit the float trap called for is done.** Five more comparisons put a coordinate that had been to the database against one the plan
holds, and every one of them answered no. `reachAt` was the expensive one: it returned its 12 m fallback instead of a village's real reach, so
villagers scattered over a twelfth of the ground meant for them and stood on the obelisk. Measured on Eltnen, mean distance from home 8.3 m and
furthest 13.5, against 16 and 37 once the question is asked by nearness. `levelAt` returned the region's middle level — the very constant it was
written to stop `moveOutIfOutgrown` testing. One predicate now, `BotPlaces.isSamePlace`, and no exact place equality is left in the module.

**Places no walker can reach are dropped from the plan, by two tests that answer different halves of it.** A place is dropped when it does not carry
the walkable stretch its fellow places vote for — a coarse-grid lookup, which names the large separate islands — *or* when the ground it stands on runs
out inside 1000 m², which is a flood fill over the fine surfaces and names the pockets the grid's 4 m columns read wrong. Eltnen loses 12 of 152 and
Verteron 5 of 123, every one of them named in the log with its coordinates, because a count cannot be checked and a place dropped in error is a place
the map never populates again. The director then moves anybody left living at a place that no longer exists, because no other rule would: theirs is
neither short nor over-full.

Asking for a *route* instead was the obvious repair and does not work — at range every failure is honestly reported as a search that gave up, so 152
probes over Eltnen cost 73 seconds and proved nothing the grid had not. The fill costs 397 ms and proves what it claims. See
[world-and-data-traps.md](world-and-data-traps.md), which also records why the fill's bound had to be measured. Both tests run once per map on the
lifecycle lane at startup, ahead of the director's first review.

**This is a flight question wearing a navigation costume.** Those places are not broken ground. Eltnen, Verteron and Poeta all carry `GLIDE`, and a
terrace a player glides onto is an island only to something that walks. So the filter is a statement about what a bot can do, not about the map, and
the day bots glide it should loosen rather than go. Which also means flight buys more than the Abyss and aerial PvP: it buys back every terrace, ledge
and rooftop the world put npcs on and no bot can settle at.

**Posts nobody could fill were the same fault, not the geography.** Every map now fills its village posts exactly: Eltnen 32/32, Verteron 25/25 where
it had sat at 23, Ishalgen 9/9, with no departures and nothing put off. Eltnen's fortress being in the air turns out not to produce an unfillable post,
and with the plan now dropping the places no walker reaches, nothing is posted where it cannot live by guarantee rather than by luck.

**Bots still wedge, but it is no longer a pattern.** Rescues went from 30 in seven minutes, clustered eight deep on single spots, to 7 in eight minutes
at eight different places, with no house-moving at all. What is left is the staircase and citadel geometry seen in Verteron, which is the
reactive-steering limit below rather than anything the director does. Not separately diagnosed.

**The population review is under test, and the suite runs on deploy.** `BotDirector.sort` takes its two dependencies as arguments and deals in
character ids, so a review's conclusion is decidable from counts, ids and places alone. Seven cases, each a fault that actually happened; they caught
one more on the first run, where the countryside took a region's last sleeper ahead of an empty village post. `deploy.ps1` overrides the root pom's
skip for the one build whose output reaches a server — quoted, since PowerShell splits `-Dmaven.test.skip=false` on the dots.

**The stalled-fight watchdog was blaming the loop for the schedule.** Its warnings never arrived singly: seventeen at one and the same second,
thirty-three seconds after a start, and fifteen inside one second after the start before that, while the pool worked through a hundred wakes of some
twenty database round trips each. No exception had been thrown in either run and `attackTick` reschedules in a `finally`, so nothing was lost — the
swings were queued. It asks the `ScheduledFuture` now: still to run means a busy server and the fight is kept; run without scheduling a successor is
the fault the guard was written for and is unchanged. The congestion itself is untouched, and is a startup phenomenon — the director's budget is
deliberately unlimited while nobody is online.

**`BotDay.restingSpot`'s two searches per candidate are not worth changing.** Measured at 360 bot-thread samples: 7 in a path search, 6 of them the
route pool, `restingSpot` in none. Changing it would be optimising against a cost nobody can find.

## Known limits, in order of how much they will bite

1. **Only maps with a generated mesh are planned on.** 28 maps are worth populating and **24 meshes exist** (`data/navmesh/*.nav`, 23 distinct
   names — Idian Depths has one per faction). `tools/navmesh.ps1 <mapId>`. This no longer paces everything, but two of the absentees are named
   throughout these docs as if they were ready: **Sanctum** (110010000) and **Reshanta** (400010000) have no mesh, so no bot plans a route on
   either. A mesh is also not a population: only Poeta has inhabitants, and the others open lazily, on the first map that needs one.
2. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the mesh does not answer a bot can still wedge itself.
3. **Walkable ground comes in islands.** A route between two of them does not exist; `NavmeshTool <mapId> components` says so before you suspect the
   search.
4. **How many characters a client tolerates in one place is unmeasured**, and it is the one ceiling this project does not control. It is also the only
   one a player feels directly.
5. **A crash loses at most 5 minutes** of what bots did.
6. **Bots never flee.** They heal, drink and shield themselves, then die.
