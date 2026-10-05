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

**Bots fly.** Four of the five milestones in [flight-plan.md](flight-plan.md) are built and were validated in game on 2026-10-04: a bot takes off and
lands under its own power, the flying is a mode of the move controller on the engine's own movement tick, it can be sent to a terrace no walker
reaches, it falls back to flying when the mesh *proves* there is no walking route, it follows a flying player while grouped, and every way a flight can
be interrupted ends it with a reason in the log. What that bought at once: the civic plan keeps a place that shares a fly zone with a mainland place
within flight range, so Eltnen's marooned village is back and Verteron's two with it.

**What is left is the fifth milestone, and it is the Abyss's problem rather than flight's**: a route through open air over a gap with no ground under
it. Everything so far is a straight line plus one `getClosestCollision`, which answers a terrace and cannot answer a crossing. Gliding belongs with it,
and is deliberately not built: server side it is nearly free, but it needs a new packet shape to validate and is unusable for the one case that would
benefit, since a gliding body loses height continuously and cannot hold station.

Note what is *not* blocked by flight, and never was: moving a bot between maps. `TeleportService.teleportTo` takes any world id and bots already call
it, so the claim that crossing maps needs teleporters and flight paths was simply wrong.

## The Abyss

**A levelling region, not just endgame**, and excluding it was a mistake. Measured: Reshanta holds **834 civilians — more than Sanctum and
Pandaemonium together** — against 4352 hostiles, median level 33 and ninth decile 50. It is one of the ways a character crosses 25 to 50.

Less blocked than it looks, and less than it was. The mesh labels regions **per surface** rather than per column, which is exactly what stacked
islands need, so civic and field presence on each island is ordinary work. For population the director's teleport already covers the crossing between
islands, and bots now fly — what is missing for gameplay is only the part flight cannot do yet, a route through open air over a gap. Before any of it,
**Reshanta's mesh, which has not been generated**: it is the largest map in the game, so budget the generation rather than assuming it is one more run
of the tool.

## Wild PvP and rifts

Priority two, and much closer than RvR — it needs none of the mass coordination.

Rifts are the door to enemy territory and the data is already there (`spawns/Rifts`). The work is: a bot deciding to invade, using a rift, hunting
players rather than mobs on the other side, and knowing when to go home. The engine side is clear — `PvpService` no longer throws when a bot kills a
real player.

The one honest prerequisite is **visible movement**: a bot that teleports into position cannot be intercepted, and being interceptable is most of what
makes open PvP worth anything. That is intra-zone navigation in contested ground, which the mesh already does.

## RvR and sieges

The far goal. It waits on Reshanta — its mesh, and a flight that can cross between islands — rather than on flight itself, which exists now.

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
- **Gear was half a character, and is now whole.** Accessories, the heroic and fabled tiers, enchantment and manastones were all missing, and the
  first two had been filtered out by the npc-costume test rather than left out on purpose. All six steps are written and deployed — see
  [bot-gear.md](bot-gear.md) and [bot-gear-upgrades.md](bot-gear-upgrades.md). What is **not** done is seeing it in game: every path runs through
  `//bot regear` or `//bot populate`, so none of it has been watched on a live character. Left deliberately out of scope, as the source document
  asks: two-handed weapon fusion, Idian stones, +15 evolution, re-evaluation and conditioning.
- **One test suite on roughly 11000 lines**, much of it concurrent: `BotDirectorTest`, seven cases over the population review, which runs on every
  deploy. Everything else is untested, which is why the combat half of `PlayerBotAI` has not been split — a mistake there is silent rather than loud.
  What made that one suite possible is worth copying: `BotDirector.sort` takes its two dependencies as arguments and deals in ids and places, so its
  conclusion is decidable without a world.

## After the population churn of 2026-10-04

The churn is fixed, and so is most of what it uncovered — see [population-director.md](population-director.md) and the float trap in
[world-and-data-traps.md](world-and-data-traps.md). What the follow-up found, and where it stopped:

**The equality audit the float trap called for is done.** Five more comparisons put a coordinate that had been to the database against one the plan
holds, and every one answered no. `reachAt` was the expensive one: it returned its 12 m fallback instead of a village's real reach, so villagers
scattered over a twelfth of their ground and stood on the obelisk — mean distance from home 8.3 m against 16 once the question is asked by nearness.
One predicate now, `BotPlaces.isSamePlace`, and no exact place equality is left in the module.

**Places no walker can reach are dropped from the plan, by two tests that answer different halves of it.** A place is dropped when it does not carry
the walkable stretch its fellow places vote for — a coarse-grid lookup, which names the large separate islands — *or* when the ground it stands on runs
out inside 1000 m², which is a flood fill over the fine surfaces and names the pockets the grid's 4 m columns read wrong. Eltnen loses 12 of 152 and
Verteron 5 of 123, every one of them named in the log with its coordinates, because a count cannot be checked and a place dropped in error is a place
the map never populates again. The director then moves anybody left living at a place that no longer exists, because no other rule would: theirs is
neither short nor over-full. **Since bots fly, a place is put back when it shares a fly zone with a mainland place within flight range** — reached is
not enough, a resident has to be able to leave — which takes Eltnen to 10 dropped and Verteron to 3.

Asking for a *route* instead was the obvious repair and does not work: at range every failure is honestly reported as a search that gave up, so 152
probes over Eltnen cost 73 s and proved nothing the grid had not, against 397 ms for the fill. Why the fill's bound had to be measured is in
[world-and-data-traps.md](world-and-data-traps.md). Both tests run once per map at startup, on the lane that may block.

**It was a flight question wearing a navigation costume, and it has its answer.** Those places are not broken ground: Eltnen, Verteron and Poeta all
carry `GLIDE`, and a terrace a player glides onto is an island only to something that walks. The filter states what a bot can do, not what the map is
worth — so it loosened the day bots flew, rather than going away. Eleven of Eltnen's twelve dropped places stand inside a fly zone; two of them have a
mainland neighbour to fly to and are back in the plan. The other nine are a cluster of terraces with no neighbour in their zone: somewhere a bot could
reach and not leave, which is the fault the filter exists to prevent.

**Posts nobody could fill were the same fault, not the geography.** Every map now fills its village posts exactly — Eltnen 32/32, Verteron 25/25 where
it had sat at 23, Ishalgen 9/9 — and with the plan dropping the places nothing reaches, that holds by guarantee rather than by luck.

**Bots still wedge, but it is no longer a pattern.** Rescues went from 30 in seven minutes, clustered eight deep on single spots, to 7 in eight minutes
at eight different places. What is left is the staircase and citadel geometry in Verteron, a reactive-steering limit rather than anything the director
does. Two rules were added to that rescue when flight arrived: a bot in somebody's group is never given a lift home, and a stranding counts against a
bot's home only when it happened *at* that home.

**The population review is under test, and the suite runs on deploy.** `BotDirector.sort` takes its two dependencies as arguments and deals in
character ids, so a review's conclusion is decidable from counts, ids and places alone. Seven cases, each a fault that actually happened; they caught
one more on the first run, where the countryside took a region's last sleeper ahead of an empty village post. `deploy.ps1` overrides the root pom's
skip for the one build whose output reaches a server — quoted, since PowerShell splits `-Dmaven.test.skip=false` on the dots.

**The stalled-fight watchdog was blaming the loop for the schedule.** Its warnings never arrived singly — seventeen in one second, while the pool
worked through a hundred wakes of twenty database round trips each — and nothing had been lost: the swings were queued. It asks the `ScheduledFuture`
now, so a swing still to run is a busy server and the fight is kept. The congestion itself is untouched, and is a startup phenomenon: the director's
budget is deliberately unlimited while nobody is online.

**`BotDay.restingSpot`'s two searches per candidate are not worth changing.** Measured at 360 bot-thread samples: 7 in a path search, 6 of them the
route pool, `restingSpot` in none. Changing it would be optimising against a cost nobody can find.

## Known limits, in order of how much they will bite

1. **Only maps with a generated mesh are planned on.** 28 maps are worth populating and **24 meshes exist** (`data/navmesh/*.nav`, 23 distinct
   names — Idian Depths has one per faction). `tools/navmesh.ps1 <mapId>`. This no longer paces everything, but two of the absentees are named
   throughout these docs as if they were ready: **Sanctum** (110010000) and **Reshanta** (400010000) have no mesh, so no bot plans a route on
   either. A mesh is also not a population: a map has inhabitants only where `//bot populate` or the populate setting has put some, and the meshes
   open lazily, on the first map that needs one.
2. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the mesh does not answer a bot can still wedge itself.
3. **Walkable ground comes in islands**, and a route between two of them does not exist. `NavmeshTool <mapId> components` says so.
4. **How many characters a client tolerates in one place is unmeasured**, and it is the one ceiling this project does not control. It is also the only
   one a player feels directly.
5. **A crash loses at most 5 minutes** of what bots did.
6. **Bots never flee.** They heal, drink and shield themselves, then die.
