# What is left to do

Written to be picked up cold. For what already works and why it was built that way, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population.md). For what this engine does to a bot that no
documentation warns about, [engine-traps.md](engine-traps.md) and [world-and-data-traps.md](world-and-data-traps.md).

Each section below says what the work is *for*, what it needs, and what actually blocks it. The order is roughly by value per unit of effort, not by
ambition.

## The one blocker left

**Stigmas are never slotted.** Not cosmetic: a character past level 20 without stigma stones fights at a fraction of its strength, so every
measurement of bot combat above that level is measuring a crippled character. `BotEquipManager` leaves them out today
(`getEquippedItemsWithoutStigma`), which is right for the armour rule it sits in and is why nothing else noticed.

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

## Group play: tank, healer, damage

Bots currently have no notion of role, which is why a group of them fights like five soloists standing together. Role follows from `PlayerClass` — the
one place in this design where a fifteen-line authored table beats deriving it, because the data does not say what a class is *for*.

- **Tank**: engages first and holds aggro. The skills exist and are already classified — `ProvokerEffect` is read today for its *stance*, and it is
  the same effect a taunt carries. A tank must prefer them, not merely be allowed them.
- **Healer**: heals by danger rather than by lowest health, and keeps out of melee. The targeting hole that made every class "heal" its ally by
  bandaging itself is fixed ([engine-traps.md](engine-traps.md)), so this now starts from a working base.
- **Damage**: does not pull, waits for the tank to hold, and focuses the tank's target rather than the nearest thing.
- **Composition**: the director should be able to offer a balanced group, which means the pool has to be stocked by role as well as by level.

## Flight

Its own problem, and the one that gates the most.

Needed for crossing between the Abyss's islands, for gliding shortcuts, for aerial PvP, and for a handful of quest routes. What it needs is a second
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
