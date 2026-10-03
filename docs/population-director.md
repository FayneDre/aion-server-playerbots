# The pool and the director

How a region is kept holding the people it should. Split out of [population.md](population.md), which it was
outgrowing.

Every bot character is in the pool; only some of them are in the world. The pool is not a thing anybody maintains — it is the world-owned residents in
`playerbot_characters` that are not in the set of spawned bots, so there is nothing to keep in step and nothing to go stale.

The **director** reviews every region every 30 seconds: what it holds, against what it should hold at this moment. The difference is handed to a lane of
its own, one thread, because loading a character is some twenty blocking database round trips and a batch of them on a tick thread would stop the bots
thinking.

**Only the countryside breathes**, and the reason is geometric rather than a matter of tuning. A player arrives at a bind obelisk, in a town — where this
module deliberately puts bots' bind points too. Every villager's home is one place's centre with fifteen metres of scatter, so while that player stands
there all of them are inside the ninety five metres a client is told about. A village thinned while nobody watched would therefore be refilled in plain
sight of whoever came back to it, and emptied again the moment they left — the one place a person actually looks at would be the one place that never
holds still. So settlements are a fixed cast, and the hunting grounds — spread apart by construction, nowhere near a bind point — are what fills and
empties.

| situation | share of the countryside awake |
|---|---:|
| a real player on the map | all of it, at the busy density |
| the five minutes after they leave | the same, decaying rather than snapping back |
| a quiet map, somebody playing elsewhere | a fifth |
| nobody online anywhere | a twentieth |

That last row was going to be zero — "only the economy stays up" — and zero is wrong: a sleeping bot earns no experience, and this model is a *flow*, so
a world asleep overnight comes back to the population it had on its first day. A twentieth keeps the careers moving at a cost nobody is observing.

Two rules, because neither can be repaired after the fact. **Nobody watches a departure**: a sleep asks the bot's own known list, which is exactly the set
of clients that would be told rather than an estimate of who is near, and a refusal costs nothing — the region is already as full as it should be, and
the next review asks again. Arrivals used to ask the same question and no longer do. It was the wrong question for this game: on any server people log
in and appear where they stand, in front of whoever is there, and nobody reads it as a fault. It was also the one refusal that could not resolve itself
— a villager's home is the village, and a client is told about everything within 95 m, so while a player stands in a place there is no unseen spot
anywhere in it. Verteron held 67 of the 84 it wanted and refused the same 17 arrivals every half minute for as long as somebody stayed in the citadel.
And **the director never picks the moment**: it decides that a region holds too many, the bot decides whether it may go now, and in a fight, dead,
mid-journey or grouped with a real player it stays.

**And it removes the need to travel.** A bot that outgrows Poeta logs out and logs back in at Verteron. Nobody watches a ten-minute walk, and a walk can
get stuck. Following a *player* between regions is a different matter and is cheap — see [roadmap.md](roadmap.md) under Flight.

What the director still does not do — stocking, pre-filling a map before a player arrives, and rehousing a bot that outgrew its region — is in
[roadmap.md](roadmap.md).
