# What is left to do

Written to be picked up cold. For what already works and why it was built that way, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population.md). For what this engine does to a bot that no
documentation warns about, [engine-traps.md](engine-traps.md) and [world-and-data-traps.md](world-and-data-traps.md).

Each section below says what the work is *for*, what it needs, and what actually blocks it. The order is roughly by value per unit of effort, not by
ambition.

## Where things stand

Last reviewed 2026-10-09. **Built and running**: the population (pool, director, civic and field presence), combat with class skills, group roles, the
templar playbook, the marking and target rules, group positioning, the death routine, gear and stigmas, flight (four milestones of five), navmesh
planning on 24 maps. **Built and not yet watched in game**: everything about groups — templar, marks, positioning, the cleric's heal-first order — the
convalescence spot after a death, and `//bot create` dressing a new bot. **Not started**: dungeons, wild PvP, the broker and shops, legions, speech,
gathering, the Abyss and RvR. The next thing worth doing is not code: play a grouped session and read the log (`//bot positioning off` then `on` gives
the before and after).

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

Built since, each with its own document and each unit tested, none yet seen in game: the **templar** as the guide plays it
([templar-plan.md](templar-plan.md)), the **skull and weakest-enemy target rules**, **group positioning** — front, behind, back, heal range, and out of
other packs' aggro ([group-positioning-plan.md](group-positioning-plan.md)) — and a **cleric** that heals first and spends only spare mana on damage.
Player-owned bots are never regeared, enchanted or socketed by the server; they may only put on a piece they find that beats their own.

What is left:

- **Other classes' playbooks.** The templar is the first and the pattern is set (`ClassPlaybook`, rules by skill group, a test that checks every group
  against the data). The guide has documents for the rest; each is a plan, a playbook and a data test.
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
- **Gear does not keep up.** Bots are dressed at creation — `//bot create` as well as `//bot populate`, with enchantment, manastones and stigmas — and
  wear what they loot. Crossing 1 to 65 needs buying or crafting.
- **Gear was half a character, and is now whole.** Accessories, the heroic and fabled tiers, enchantment and manastones were all missing, and the
  first two had been filtered out by the npc-costume test rather than left out on purpose. All six steps are written and deployed — see
  [bot-gear.md](bot-gear.md) and [bot-gear-upgrades.md](bot-gear-upgrades.md). What is **not** done is seeing it in game: every path runs through
  `//bot regear` or `//bot populate`, so none of it has been watched on a live character. Left deliberately out of scope, as the source document
  asks: two-handed weapon fusion, Idian stones, +15 evolution, re-evaluation and conditioning.
- **89 tests in 9 classes on roughly 20000 lines**, all run on every deploy: the population review (`BotDirectorTest`), route planning on a
  synthetic ground (`BotPathFinderTest`), and the group work — the templar's rules and a sweep of every skill template in the game
  (`TemplarPlaybookTest`, `TemplarPlaybookDataTest`), marks, stigma ranking, spots and aggro zones. The combat half of `PlayerBotAI` is still
  untested and unsplit, which is why a mistake there is silent rather than loud. What made the suites possible is worth copying: decisions are pure
  functions of a situation record, so a conclusion is decidable without a world — and a data test feeds the code what the server feeds it, which is
  what the one fault that reached a live server (a null skill group) needed.

## After the population churn of 2026-10-04

Done, and moved to [population-churn-followup.md](population-churn-followup.md): the equality audit, the unreachable places and their flight exception, the review under test, the watchdog.

## Known limits, in order of how much they will bite

0. **Bots resurrect into pockets the mesh does not join** — fortress obelisks mostly — and are teleported home when they cannot walk off. Built and unit tested, not yet
   watched in game: a landing check, a rough route that keeps to its level, binding to a reachable obelisk ([navigation-stuck-plan.md](navigation-stuck-plan.md)); the
   obelisk audit exists (`tools/navmesh.ps1 -Obelisks`: 10 of 88 fail, listed in the plan) and is to be run on every new mesh, Reshanta first.

1. **Only maps with a generated mesh are planned on.** 28 maps are worth populating and **25 meshes exist** (`data/navmesh/*.nav`). `tools/navmesh.ps1
   <mapId>`, then `-Obelisks` ([navigation-stuck-plan.md](navigation-stuck-plan.md)). **Sanctum** (110010000) has one since 2026-10-09, built from placed meshes
   alone because the data ships no heightmap for it (the generator now accepts that, sizing the grid from `world_maps.xml`): 308 of its 352 npc spots stand on
   ground. **The Cloister of Kaisinel** (110020000) cannot have one: its geometry is 10 KB and its ground is a heightmap the data does not hold, so the
   mesh came out with 304 routable cells and was thrown away (a nearly empty mesh is worse than none: every route it answers is a refusal). Reshanta and the
   four Panesterra fields lack a heightmap too and can be tried the same way; Reshanta needs a 8192 by 8192 grid, so budget the memory. A mesh is not a
   population: a map has inhabitants only where `//bot populate` or the populate setting has put some, and the meshes open lazily.
2. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the mesh does not answer a bot can still wedge itself.
3. **Walkable ground comes in islands**, and a route between two of them does not exist. `NavmeshTool <mapId> components` says so.
4. **How many characters a client tolerates in one place is unmeasured**, and it is the one ceiling this project does not control. It is also the only
   one a player feels directly.
5. **A crash loses at most 5 minutes** of what bots did.
6. **Bots flee only from a lost fight.** `retreatIfLosing` breaks off from an outmatched or outnumbered fight and walks home; otherwise they heal, drink and
   shield themselves. After a death a bot waits out the soul sickness at a spot of its own off the obelisk ([bot-careers.md](bot-careers.md)).
