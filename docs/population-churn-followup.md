# After the population churn of 2026-10-04

Moved out of [roadmap.md](roadmap.md), which is for what is left: everything below is done, and kept for the reasoning and the numbers.

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

