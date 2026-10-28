# The pool, and the residents it replaced

Status: done; moved out of built.md on 28 October 2026. The model as it stands is in [../how-it-works.md](../how-it-works.md) and
[../population-director.md](../population-director.md); this is what building it cost.

## The pool

Inhabitants are created asleep and the director wakes them, which is what makes a world of two thousand something that can be tried rather than only
calculated — every bot used to be awake from the moment it was created. The model and the numbers are in [population.md](../how-it-works.md); what the building
of it cost is here.

**Attention could not be applied to the whole population, and the reason was geometric.** The design scaled every region's target by how much it was being
watched. That cannot work for settlements: a player arrives at a bind obelisk in a town, every villager's home is one centre with fifteen metres of
scatter, and so the whole village sits inside the ninety five metres a client is told about. A village thinned while nobody looked could never be refilled
while somebody stood in it — the one place a person looks at would be the one place the director was unable to fix. Settlements became a fixed cast and the
countryside became the part that breathes. It also happens to be the arrangement the data already had: settlement density never depended on whether a map
was busy, only the hunting grounds did.

**Three things were wrong before any of this and only became visible once something other than an operator decided when a bot leaves.** `delete()` despawns
with `FADE_OUT`, so every departure was watched; the departure did not leave its group, so a player kept a ghost in their party window; and `cancel(false)`
does not stop a tick that has begun, so a bot could be removed from the world in the middle of deciding something and then try to walk. All three are in
[engine-traps.md](../../traps/engine-traps.md) or fixed at the source.

**Creating a character asleep nearly dressed it in nothing.** Dressing used to happen after entering the world, and the comment said why: equipping was
believed to ask whether its wearer was spawned. It does not — a broadcast to a non-spawned object reaches its empty known list and stops. The real
dependency was the opposite way round and quieter: the outfitter refuses armour to a character that does not hold its mastery, and a character read back
from the database holds level-1 skills whatever its level, because teaching happened on the way in ([world-and-data-traps.md](../../traps/world-and-data-traps.md)).
Teach, then dress.

**Verified in game, on Poeta.** A cleared map repopulated to 36 inhabitants over **29 distinct homes** — against 16, twelve of them in Akarios, before
`populate` learned to fill the places still short. A player standing on the map took the target to 37, and the pool filled in waves of 25: 23 arrived,
then 10, then nothing, with **the same two refused at every review** because the player was stood on the ground those two live on. The region holds two
short, on purpose, for as long as somebody is looking at the place that is missing them. That is the whole rule, observed rather than reasoned about.

**A bot is busy most of the time, which is what paces departures.** Emptying a map measured 4 to 6 leaving a review against 15 to 21 put off as "in a
fight or on a road" — so the rate is set by how often bots are idle, not by the budget of 25, which is almost never the binding constraint. A surplus of
27 took three or four minutes to go, with nobody watching. `tryRetire` is what produces that, and it is the right trade: no bot is ever taken out of the
world mid-swing.

**The surge had nobody to draw on, and only the game showed it.** `populate` created the *quiet* plan, because that is what `population.md` calls the
establishment — "what gets created and kept". But the busy countryside is twice as dense, so on Poeta a player's arrival raised the target from 19 to 37
against a pool of 19: fourteen awake, five asleep, and a map with a player on it no busier than an empty one. Two things were wrong at once. The pool has
to be stocked at the busy plan, and the countryside has to be counted **per map** rather than per place — thirteen homes were spread over fifty one
hunting grounds, so the plan kept asking for hunters at grounds nobody lived at while inhabitants slept at grounds already worked. A village is a place a
player walks into; a hunting ground is interchangeable with the next one.

**The pool is a query, not a collection.** Which characters exist is the database's answer; a copy of it kept in memory is a second truth to drift. So the
pool is the world-owned residents that are not in the set of spawned bots, and `in_world` went from a full-table rewrite on every change to one row per
arrival — "these and nobody else" is the safer statement for a population created once and the wrong one for a loop running every thirty seconds.

## Retired: residents and adventurers

A bot used to be one of two things, and a resident gained no experience so that its region kept inhabitants of its own level. It worked, and it was
the wrong answer: it produced two species, and a resident was not really a character. Replaced by one kind of bot that levels and travels, with a
stationary *flow* rather than stationary individuals — see [population.md](../how-it-works.md).

What the model got right and kept: a level belongs to a region rather than to a camp, a bot has a home that is not simply the nearest place, and
settlements are read from the world rather than authored. What it got wrong is recorded in [roadmap.md](../../roadmap.md) under the density rule.
