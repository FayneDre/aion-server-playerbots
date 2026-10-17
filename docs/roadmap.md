# What is left to do

Written to be picked up cold. For what already works and why it was built that way, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population.md). For what this engine does to a bot that no documentation warns about, [engine-traps.md](engine-traps.md)
and [world-and-data-traps.md](world-and-data-traps.md).

Each section says what the work is *for*, what it needs, and what blocks it. The order follows the project's priorities (PvE, wild PvP, broker, shops,
legions, RvR), then value per unit of effort inside each.

## Where things stand

Last reviewed 2026-10-18 (patch `patch-2026-10-18`). **Built and running**: the population (pool, director, civic and field presence, each side woken
for its own grounds), combat with class skills, group roles, playbooks for the templar, chanter, ranger and cleric, the marking and target rules, group
positioning, the death routine, gear and stigmas, flight in its five milestones (the fifth crosses the Abyss by air), navmesh planning on 24
open-world maps with glide links, flight links on Reshanta and a long-walk planner, 45 dungeon meshes, steering vetted by the mesh (steps aside
14689 -> 469 per half hour on 800 bots, rescue teleports 403 -> 79, none in a player's sight), the test harness (`//bot squad`, notes, the command
inbox, patch tags). **Watched with a bot-only squad**: group fights at level 24 in Eltnen and 40 in Heiron (the cleric's heals, the chanter in melee,
no fear and no wide area in a group), and a squad of level 40 crossing Reshanta's upper layer by air, together, to its goal. **Not watched in game**:
group resurrection, the cleric facing a debuff it cannot remove, positioning against a boss, anything in a dungeon. **Not started**: bots in
dungeons, wild PvP, the broker and shops, legions, speech, gathering, RvR.

## Dungeons and instances

**The largest player-facing value in the project**: a solo player can run group content. Everything else makes the world look alive; this makes it
playable alone, and it is the next thing worth doing.

What exists: group roles, four class playbooks, positioning, marks, a squad of bots that fights as a group, and **45 dungeon meshes** with glide links,
each put through a reach check ([dungeon-navmesh-plan.md](dungeon-navmesh-plan.md)). What is missing:

- **Instance entry for a connectionless player.** The group's instance is created for the leader and the members are teleported in, the same
  `TeleportService` door bots already use. Not tried.
- **The server opening an instance mesh** (milestone 6 of the dungeon plan): the meshes exist on disk, nothing loads one for a running instance yet.
- **Doors, lifts and floors** the open world never asked about: what the testers reported is in the dungeon plan.
- **Bosses**: a bot that simply fights correctly clears most low-level instances; mechanics are a later refinement, not a prerequisite. Positioning
  against a boss has never been seen, because the only Elyos boss in reach outdoors stands off the mesh.
- **Levels**: no level 50 bots for the Theobomos instances and Dark Poeta (asked by a tester on 9 October).

## Group play

What is left once roles, playbooks and positioning exist:

- **The other classes' playbooks**: gladiator, assassin, sorcerer, spirit master, bard, gunner, aethertech. The pattern is set (`ClassPlaybook`, rules by
  skill group, a data test that checks every group against the skill templates); each is a plan from the guide's document, a playbook and a test.
  **Spirit master, aethertech, bard and gunner come first**: on Reshanta's upper layer they died 36 times of 40 in half an hour, four bots against
  the other 21's four deaths, on the generic rotation.
- **Damage waits for the tank to hold.** It focuses the tank's target, but opens at the same instant, so the first blow can pull the mob off.
  `AggroList.getTarget(MOST_HATED)` can answer "the tank holds it".
- **Composition**: the director should offer a balanced group, which needs the pool stocked by role as well as by level (see the director below).
- **Nobody forms a group.** Bots join a player's or a `//bot squad`; two bots in a field never party with each other.
- **Tester points still open**: the templar that "drops the fight" (needs the tester's log or the time and the bot's name; a grouped bot no longer
  breaks off from any fight since 17 October, which may be it), group resurrection, the cleric against an assassin's rune ([cleric-plan.md](cleric-plan.md)).

## What the director still does not do

The pool is built: inhabitants are created asleep, and the director wakes and sleeps the countryside to match each region's target unseen. Since 17
October a hunter is woken for a ground of the plan nobody works, the nearest sleeper of that ground's side, and a few a review move from a ground
worked twice to an empty one: before, the field was woken in the pool's order, and Reshanta's upper layer was never woken at all. Three pieces were
deliberately left out.

- **Stocking.** *"Do I have a level-16 Asmodian scout?"*, growing one if not, retiring the oldest at the cap and freeing their names. Without it a
  population is whatever the populate setting created once. `populate` creates the busy plan, so a surge has sleepers to wake.
- **Pre-filling a map before a player arrives.** A teleport knows its destination before the client loads, and the character list is known on the
  selection screen. It is also what would let settlements sleep, which they cannot: a village is all within sight of the obelisk a player lands on.
- **Moving a bot that outgrew its region, across maps.** Within a map `BotDay.moveOutIfOutgrown` rehomes it; past the region's ceiling `BotPacing`
  stops it, a documented stopgap. A sleep and a wake on another map is cheap with the pool.

A region's band has two parts where its monsters do (`BotPlaces.isLivedAt`): Theobomos and Brusthonin were repopulated with 20 to 30 in their middle
band. **Reshanta's numbers are left as they are by decision** (18 October): 29 awake with nobody online (its villages and one hunter), 85 with a player
on it. Counting each set of islands as a region of its own is the way to raise them when wanted.

## Wild PvP and rifts

Priority two, much closer than RvR: it needs none of the mass coordination. Rifts are the door to enemy territory and the data is there
(`spawns/Rifts`). The work: a bot deciding to invade, using a rift, hunting players rather than mobs, and knowing when to go home. `PvpService` no
longer throws when a bot kills a real player. The one prerequisite is **visible movement**, so a bot can be intercepted, which the mesh now gives on
every open-world map with steering that no longer walks through scenery. In the Abyss a crossing refuses the other side's islands for now: that is
the switch to turn when wild PvP is a decision rather than an accident.

## Economy

**Selling works**: 37 sales in 38 trips on the night of 2 October, keepers found among the living npcs, refused shops remembered, trade within 8 m,
and the keeper's own floor where it stands a storey away (17 October, Teminon). No shop on another layer of the map is gone to; fortress shops are
kept, by decision. A player's own bot never sells, buys, dresses from, identifies or binds what is in its bag ([bot-gear.md](bot-gear.md)).

Next is the **broker** (`BrokerService`), on the same "find the right npc" mechanism, then **player shops**. **Gathering and crafting** would feed the
broker and make the countryside look used; they are not started.

## Legions

Priority five. Bots forming and filling legions, which mostly falls out of group play plus a reason to gather.

## Flight, the Abyss, RvR

**Bots fly** ([flight-plan.md](flight-plan.md)): take off and land, reach terraces, fly when the mesh proves there is no walk, follow a flying leader,
glide down a planned glide link, and **cross the Abyss** by flight links between islands ([abyss-crossing-plan.md](abyss-crossing-plan.md)). Reshanta
is lived on both its layers: 120 of its 134 places, Teminon's and Primum's stairs walked (a 62 degree slope for its buildings), residents three levels
above their place and nowhere near a hostile elite, nobody in a fortress, no landing in one, on the other side's ground or beside a creature the bot
cannot fight. Refused routes 336 to 41 a half hour; no death in the void, no crossing refused in flight. What is left:

- **The playbooks above**, which are what the Abyss's deaths come down to.
- **The Abyss is dangerous on foot**, and is left so: a squad meets level 65 Balaur elites between landings and dies to them. Kept from the walk as
  well, they made the search give up (726 of them on the upper layer).
- **Long journeys**: 17 of 20 random upper-layer journeys of 300 m to 1 km are planned offline, in 0.15 to 3.3 s; across the whole map (3 km) the
  5 s budget still runs out. The layers are not joined to one another (a flight link climbs 200 m, the layers are 1200 m apart).
- **The middle layer** (the Abyss core, mostly the Divine Fortress) is not lived, by decision.
- **Gliding on the way down** (milestone 6), only if flights run short of points; they have not so far.

**RvR and sieges** come next on Reshanta; their supply line is designed (bots at the level cap become the elders the endgame needs,
[population.md](population.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).

## Gaps nobody had raised

- **Nobody speaks.** No region chat, no call for a group, no word at a trade. Cheap, and high value per line of code.
- **Bots do not yield to real players.** `BotTargetRegistry` stops two bots claiming one mob, but nothing yields to a person. Forty bots stripping a
  spawn or a gathering node make a worse server than none; this is a design rule to settle before a settled 2000.
- **Gear does not keep up.** Bots are dressed at creation (enchanted, socketed, with stigmas) and wear what they loot; crossing 1 to 65 needs buying
  or crafting. Existing gunners keep their cannon until `//bot regear all` (two pistols since 17 October).
- **Ambient flights stall on fly-zone maps**: "flight has not moved, its leg is handed over again" comes up 14 to 100 times in forty minutes, mostly
  at heights of 100 to 300 on the open world maps. Older than the Abyss work; not looked into.
- **230 tests, 26 classes of them on the bot module**, run on every deploy: the director, route planning (flight routes included), steering vetting,
  the four playbooks and a sweep of every skill template. The combat half of `PlayerBotAI` is still untested and unsplit, which is why a mistake there
  is silent rather than loud. Decisions written as pure functions of a situation record are what made the suites possible; keep writing them that way.

## Known limits, in order of how much they will bite

1. **How many characters a client tolerates in one place is unmeasured**, the one ceiling this project does not control and the one a player feels.
2. **Memory**: about 800 awake bots fill a 2560 MB heap (90% old generation), which is why the two housing maps (Oriel, Pernon) are not populated.
   The machine has little left besides: a long background wait of the tooling was killed for low memory on 17 October.
3. **Only maps with a mesh are planned on.** 24 open-world maps and 45 dungeons have one (`tools/navmesh.ps1 <mapId>`, then `-Obelisks`); only
   Reshanta has flight links (`NavmeshTool <mapId> flights`, with the server stopped). The Cloister of Kaisinel cannot (no heightmap and almost no
   geometry); Panesterra lacks a heightmap too.
4. **The mesh is not the geometry**: props placed at run time (gatherables, doors) are not in it, and a 0.5 m grid misses what is thinner. What is left
   of the steps aside are those props ([obstacle-steering-plan.md](obstacle-steering-plan.md)).
5. **Walkable ground comes in islands**, and a route between two does not exist: `NavmeshTool <mapId> components` says so. Fortress obelisks are the
   usual case ([navigation-stuck-plan.md](navigation-stuck-plan.md)); Reshanta's two pass the audit through the flight links.
6. **A crash loses at most 5 minutes** of what bots did. A clean stop takes seconds since 18 October (it took 510 s while a dead bot searched for a
   spot round Primum's obelisk); `stop-server.ps1` waits up to 900 s.
7. **Bots flee only from a lost fight** (`retreatIfLosing`), never in a group; otherwise they heal, drink and shield themselves, and wait out the soul
   sickness at a spot of their own after a death ([bot-careers.md](bot-careers.md)).

## Done and moved

The population churn of 2026-10-04 ([population-churn-followup.md](population-churn-followup.md)), the stigma blocker, names, Asmodian settlements and
the roster's `owner_id` (see [built.md](built.md)), the walking-into-scenery plan of 16 and 17 October
([obstacle-steering-plan.md](obstacle-steering-plan.md)), and flight milestone 5, the Abyss crossing of 17 and 18 October
([abyss-crossing-plan.md](abyss-crossing-plan.md)).
