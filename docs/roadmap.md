# What is left to do

Written to be picked up cold; every document is indexed in [README.md](README.md). For what already works and why, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population/how-it-works.md). For what this engine does to a bot that no documentation warns about, [engine-traps.md](traps/engine-traps.md)
and [world-and-data-traps.md](traps/world-and-data-traps.md).

Each section says what the work is *for*, what it needs, and what blocks it. The order follows the project's priorities (PvE, wild PvP, broker, shops,
legions, RvR), then value per unit of effort inside each. The list just below is that order in one place; the sections give the detail.

## Next, by priority

0. **Feedback as it comes**: the user reports from the game while playing; each plan's Status line says what has been seen. On 27 October
   everything shipped up to `patch-2026-10-25-2` was validated in game, except duels, friends and trades with a companion.
1. **Upstream**: `4.8` is 69 commits behind `beyond-aion` (28 October). A trial merge: 10 small conflicts, and the per-map death levels
   removed upstream, which `BotFlight` reads; the user decides when. The module structure plan is done
   ([module-structure-plan.md](architecture/history/module-structure-plan.md), five steps, the last validated on 28 October).
2. **Navigation**, the user's priority: the six points in [navigation-backlog.md](navigation/backlog.md), long journeys first
   (agreed 25 October), then sliding, obstacles, gliding, parasitic moves, dungeons.
3. **A world alive with or without a player**, a lead to explore within the 2560 MB heap: density independent of who is online, sleepers living
   off-world, memory measured first and freed where it can be.
4. **The other classes' playbooks**: assassin, spirit master, bard, gunner, aethertech, from the guide's documents as the six before them.
   Damage that waits for the tank stays ours.
5. **Dungeons**: lifts and teleporters taken by the bots themselves, only if the 13 s wait proves too long; bosses' mechanics.
6. **The director**: a pool stocked by level and role, a bot moved to the map its level calls for.
7. **Cohorts** ([cohort-plan.md](social/plans/cohort-plan.md)): a bot in an alliance acting as a member of its own group of six.
8. **Wild PvP, rifts and the arenas.**
9. **Economy**: the broker, then player shops, then gathering and crafting.
10. **Legions**, accepting a legion's invitation first.
11. **RvR and sieges.**

## Where things stand

Last reviewed 2026-10-27. **Built and running**: the population (pool, director, civic and field presence, each side woken
for its own grounds, sent home to an obelisk of its own faction, rehomed when its place is dropped), combat with class skills, group roles, playbooks
for the templar, chanter, ranger, cleric, gladiator and sorcerer, ground spells, the marking and target rules, group positioning, the death
routine, gear and stigmas, merchants' wings and power shards, flight in its five milestones and flying with a player, navmesh planning on 26 open-world maps with their glide links,
flight links on Reshanta and a long-walk planner, 45 dungeon meshes, duels, friends and trades with a companion, steering vetted by the mesh,
capitals with a town routine, 40 bot stores each with broker prices, visitors of every level, shouted adverts and calls for a group, bots
speaking in the chat channels, and the test harness (`//bot squad`, notes, the command inbox, patch tags). **Watched with
a bot-only squad**: group fights at level 24 in Eltnen and 40 in Heiron, and a squad crossing Reshanta's upper layer by air. **Watched with a
player**: a Noschana run, Sanctum's town day, Pandaemonium's visitors and speech. **Validated in game on 27 October**: everything shipped up to `patch-2026-10-25-2`, the six playbooks, positioning against a
boss and flying with a player among it. **Not watched in game**: duels, friends and trades with a companion. **Not started**: wild PvP, the broker and player shops, legions, gathering, RvR.

## Dungeons and instances

**The largest player-facing value in the project**: a solo player can run group content. Everything else makes the world look alive; this makes it
playable alone, and it is the next thing worth doing.

What exists: group roles, six class playbooks, positioning, marks, a squad of bots that fights as a group, and **45 dungeon meshes** with glide links,
each put through a reach check ([dungeon-navmesh-plan.md](navigation/history/dungeon-navmesh-plan.md)). What is missing:

- **Instance entry: built and watched 18 October** (Noschana). A grouped bot follows its player into the instance and out again (`BotInstances`),
  revives at the entrance, and a bot saved inside starts at the exit; `BotMapChange` spawns a bot a map change would leave waiting for a client's
  `CM_LEVEL_READY`. The run showed bots slow to catch up and never gliding: they now keep a route within 8 m of a moving leader and copy the
  leader's glide from its takeoff (`LeaderGlides`). A player who logs out inside is waited for five minutes, then the bots leave the group and the instance (`BotInstances`). Both validated in game on 27 October.
- **A bot cut off from its player** in an instance (a teleporter, a lever, a lift, a windstream, a door the player's key opens) is put beside them
  at any distance after about 13 s (`PlayerBotAI.freeItself`, 19 October); before, past 200 m, it went to its home's coordinates on the instance's
  map. Validated in game on 27 October.
- **The instance meshes** (54 files for the 45 geometries) are walked: Noschana's was. On 19 October floors the generator dropped were put back in 20
  of them (Steel Rake 99 %, the Danuar Sanctuaries 94 and 95 %, the Dredgions 96 to 99 %, Sauro 91 %), and Esoterrace reads 99 % once its
  windstreams' landings count as arrivals, and both Dragon Lord's Refuges 100 % once the teleporters their handler codes count too. Left under 80 %: **Theobomos Lab** (72 %, its lift after the elemental rooms; Queen Arachne's room, its
  first boss, walked to since a face that is no wall in game was left out) and **Rentus** (77 and 72 %: a lift that appears at Kuhara's death, the tester says, which no mesh holds).
- **Doors, lifts and windstreams** are not used by a bot itself: it is put beside its player instead. Links "walk to the object, use it, arrive
  there" are the next step if 13 s turns out too long ([dungeon-navmesh-plan.md](navigation/history/dungeon-navmesh-plan.md)).
- **Bosses**: a bot that simply fights correctly clears most low-level instances; mechanics are a later refinement, not a prerequisite. Positioning
  against a boss has never been seen, because the only Elyos boss in reach outdoors stands off the mesh.
- **Levels**: no level 50 bots for the Theobomos instances and Dark Poeta (asked by a tester on 9 October); they exist on another test server,
  whose feedback is awaited.

## Group play

What is left once roles, playbooks and positioning exist:

- **The other classes' playbooks**: assassin, spirit master, bard, gunner, aethertech. The pattern is set (`ClassPlaybook`, rules by
  skill group, a data test that checks every group against the skill templates); each is a plan from the guide's document, a playbook and a test.
  **Spirit master, aethertech, bard and gunner come first**: on Reshanta's upper layer they died 36 times of 40 in half an hour, four bots against
  the other 21's four deaths, on the generic rotation.
- **Damage waits for the tank to hold.** It focuses the tank's target, but opens at the same instant, so the first blow can pull the mob off.
  `AggroList.getTarget(MOST_HATED)` can answer "the tank holds it".
- **Composition: built and watched 18 October.** An offer in the Find Group window is filled with bots, a tank, a healer and a support first, at the
  instance's levels, counting the player's own role (`BotRecruiter`); recruits go home when released. Needs `form_anywhere` in the server's config. Left: the Recruit Group Members tab, alliances, and a pool stocked by role,
  since a missing templar or cleric at a level is a missing tank or healer (see the director below).
- **Bots never form a group of their own.** They join a player's, an offer's or a `//bot squad`; two bots in a field never party with each other.
- **Tester points still open**: the templar that "drops the fight" (needs the tester's log or the time and the bot's name; a grouped bot no longer
  breaks off from any fight since 17 October, which may be it), the cleric against an assassin's rune ([cleric-plan.md](classes/history/cleric-plan.md)).

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

## Asked in game, 19 October

The list "missing features in game", each with what stands today:

- **Trading with one's companion**: built 19 October, with its owner only; `//bot give` hands over the companion's side ([player-interactions-plan.md](social/plans/player-interactions-plan.md)).
- **Adding a bot as a friend**: built 19 October; a bot shows online while awake.
- **A legion's invitation**: not answered. The first step of the legions section below.
- **Cohorts** (alliances): a bot answers an alliance invitation but acts as if the whole alliance were its group ([cohort-plan.md](social/plans/cohort-plan.md)).
- **Duels**: built 19 October; a free bot within 10 levels accepts and attacks, and never asks for one.
- **The in-game shop does not work**, and is not the bots' work: the server has none. The 4.8 client opens a web shop with a token
  (`CM_GF_WEBSHOP_TOKEN_REQUEST` answers an empty one, a TODO upstream); the older shop packets are commented out and `config/ingameshop` is unused.
  Either a small web shop answering the token, or reviving the old packets if this client still reads them: a decision, then a project of its own.
- **The arenas** (four or five kinds): not started; they are instances of their own, after the dungeons.
- **A living world**: built 20 to 23 October, a town day and 40 bot stores a capital ([living-capitals-plan.md](capitals/history/living-capitals-plan.md)).

## Wild PvP and rifts

Priority two, much closer than RvR: it needs none of the mass coordination. Rifts are the door to enemy territory and the data is there
(`spawns/Rifts`). The work: a bot deciding to invade, using a rift, hunting players rather than mobs, and knowing when to go home. `PvpService` no
longer throws when a bot kills a real player. The one prerequisite is **visible movement**, so a bot can be intercepted, which the mesh now gives on
every open-world map with steering that no longer walks through scenery. In the Abyss a crossing refuses the other side's islands for now: that is
the switch to turn when wild PvP is a decision rather than an accident.

## Economy

**Selling works**: 37 sales in 38 trips on the night of 2 October, keepers found among the living npcs, refused shops remembered, trade within 8 m,
and the keeper's own floor where it stands a storey away (17 October, Teminon), unless that floor is a pocket behind a counter, where a bot
walked in and could not get out (18 October, the Verteron citadel). Since 18 October a trip walks to the keeper it chose, not the nearest one
seen, and to the floor in front of it rather than its feet: trips given up 269 -> 91 in the first 23 minutes. No shop on another layer of the map is gone to; fortress shops are
kept, by decision. A player's own bot never sells, buys, dresses from, identifies or binds what is in its bag ([bot-gear.md](gear/how-it-works.md)).

Next is the **broker** (`BrokerService`), on the same "find the right npc" mechanism, then **player shops**. **Gathering and crafting** would feed the
broker and make the countryside look used; they are not started.

## Legions

Priority five. Bots forming and filling legions, which mostly falls out of group play plus a reason to gather.

## Flight, the Abyss, RvR

**Bots fly** ([flight-plan.md](flight/history/flight-plan.md)): take off and land, reach terraces, fly when the mesh proves there is no walk, follow a flying leader,
glide down a planned glide link, and **cross the Abyss** by flight links between islands ([abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md)). Reshanta
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
[population.md](population/how-it-works.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).

## Gaps nobody had raised

- **The chat server is off upstream** (`gameserver.chatserver.enable = false`, no password): a server that wants channels, a tester's own
  included, sets both in its `mygs.properties` (or in `gameserver.properties` and `network.properties`).
- **Bots do not yield to real players.** `BotTargetRegistry` stops two bots claiming one mob, but nothing yields to a person. Forty bots stripping a
  spawn or a gathering node make a worse server than none; this is a design rule to settle before a settled 2000.
- **Gear does not keep up.** Bots are dressed at creation (enchanted, socketed, with stigmas) and wear what they loot; only wings are renewed with
  the level (25 October); crossing 1 to 65 needs buying or crafting. Existing gunners keep their cannon until `//bot regear all` (two pistols since 17 October).
- **Ambient flights stall on fly-zone maps**: "flight has not moved, its leg is handed over again" comes up 14 to 100 times in forty minutes, mostly
  at heights of 100 to 300 on the open world maps. Older than the Abyss work; not looked into.
- **397 tests on the game server**, run on every deploy. The combat half of `PlayerBotAI` is still untested and unsplit, so a mistake there is
  silent. Decisions written as pure functions of a situation record are what made the suites possible; keep writing them that way.

## Known limits, in order of how much they will bite

1. **How many characters a client tolerates in one place**: measured once on 20 October, in Sanctum on the tester's machine. It held 60 fps with
   25 standing in sight, about 45 with 50, 35 with 75 and 30 with 97 ([living-capitals-plan.md](capitals/history/living-capitals-plan.md)). This is the ceiling
   this project does not control and the one a player feels.
2. **Memory**: about 800 awake bots fill a 2560 MB heap (90% old generation), which is why the two housing maps (Oriel, Pernon) are not populated.
   The machine has little left besides: a long background wait of the tooling was killed for low memory on 17 October.
3. **Only maps with a mesh are planned on**: 26 open-world maps and 45 dungeons (`tools/navmesh.ps1 <mapId>`, then `-Obelisks`); flight links on Reshanta
   alone (`NavmeshTool <mapId> flights`, server stopped). The Cloister of Kaisinel and Panesterra have no heightmap.
4. **The mesh is not the geometry**: props placed at run time (gatherables, doors) are not in it. Walls are, at a body's width since 18 October
   (erosion per surface, every open-world mesh generated again; a test server runs `tools/navmesh.ps1 -OpenWorld` once). What is left of the steps aside is the berth round gatherables, which the client does
   need: a body does not walk through one (checked in game, 18 October; [obstacle-steering-plan.md](navigation/history/obstacle-steering-plan.md)).
5. **Walkable ground comes in islands**, and a route between two does not exist: `NavmeshTool <mapId> components` says so. Fortress obelisks are the
   usual case ([navigation-stuck-plan.md](navigation/history/navigation-stuck-plan.md)); Reshanta's two pass the audit through the flight links.
6. **A crash loses at most 5 minutes** of what bots did. A clean stop takes seconds since 18 October (it took 510 s while a dead bot searched for a
   spot round Primum's obelisk); `stop-server.ps1` waits up to 900 s.
7. **Bots flee only from a lost fight** (`retreatIfLosing`), never in a group; otherwise they heal, drink and shield themselves, and wait out the soul
   sickness at a spot of their own after a death ([bot-careers.md](population/bot-careers.md)).

## Done and moved

The population churn of 2026-10-04 ([population-churn-followup.md](population/history/population-churn-followup.md)), the stigma blocker, names, Asmodian settlements and
the roster's `owner_id` (see [built.md](built.md)), the walking-into-scenery plan of 16 and 17 October
([obstacle-steering-plan.md](navigation/history/obstacle-steering-plan.md)), and flight milestone 5, the Abyss crossing of 17 and 18 October
([abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md)).
