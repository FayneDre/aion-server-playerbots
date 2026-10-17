# What is left to do

Written to be picked up cold. For what already works and why it was built that way, see [built.md](built.md). For how a world gets its
inhabitants, [population.md](population.md). For what this engine does to a bot that no documentation warns about, [engine-traps.md](engine-traps.md)
and [world-and-data-traps.md](world-and-data-traps.md).

Each section says what the work is *for*, what it needs, and what blocks it. The order follows the project's priorities (PvE, wild PvP, broker, shops,
legions, RvR), then value per unit of effort inside each.

## Where things stand

Last reviewed 2026-10-18 (patch `patch-2026-10-18`). **Built and running**: the population (pool, director, civic and field presence), combat with
class skills, group roles, playbooks for the templar, chanter, ranger and cleric, the marking and target rules, group positioning, the death routine,
gear and stigmas, flight (five milestones, the fifth crossing the Abyss), navmesh planning on 24 open-world maps with glide links and a long-walk planner, 45 dungeon meshes,
steering vetted by the mesh (steps aside 14689 -> 469 per half hour on 800 bots, rescue teleports 403 -> 79, none in a player's sight), the test
harness (`//bot squad`, notes, the command inbox, patch tags). **Watched with a bot-only squad** (level 24 in Eltnen, level 40 in Heiron): group fights,
the cleric's heals, the chanter in melee, no fear and no wide area in a group. **Not watched in game**: group resurrection, the cleric facing a debuff it
cannot remove, positioning against a boss, anything in a dungeon. **Not started**: bots in dungeons, wild PvP, the broker and shops, legions, speech,
gathering, RvR.

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
- **Damage waits for the tank to hold.** It focuses the tank's target, but opens at the same instant, so the first blow can pull the mob off.
  `AggroList.getTarget(MOST_HATED)` can answer "the tank holds it".
- **Composition**: the director should offer a balanced group, which needs the pool stocked by role as well as by level (see the director below).
- **Nobody forms a group.** Bots join a player's or a `//bot squad`; two bots in a field never party with each other.
- **Tester points still open**: the templar that "drops the fight" (needs the tester's log or the time and the bot's name), group resurrection, the
  cleric against an assassin's rune ([cleric-plan.md](cleric-plan.md)).

## What the director still does not do

The pool is built: inhabitants are created asleep, and the director wakes and sleeps the countryside to match each region's target unseen. Three
pieces were deliberately left out.

- **Stocking.** *"Do I have a level-16 Asmodian scout?"*, growing one if not, retiring the oldest at the cap and freeing their names. Without it a
  population is whatever the populate setting created once. `populate` creates the busy plan, so a surge has sleepers to wake.
- **Pre-filling a map before a player arrives.** A teleport knows its destination before the client loads, and the character list is known on the
  selection screen. It is also what would let settlements sleep, which they cannot: a village is all within sight of the obelisk a player lands on.
- **Moving a bot that outgrew its region, across maps.** Within a map `BotDay.moveOutIfOutgrown` rehomes it; past the region's ceiling `BotPacing`
  stops it, a documented stopgap. A sleep and a wake on another map is cheap with the pool.

A region's band now has two parts where its monsters do (`BotPlaces.isLivedAt`, 17 October): Theobomos and Brusthonin were repopulated with 20 to 30
in their middle band, which a tester reported as filled with 45 to 49.

## Wild PvP and rifts

Priority two, much closer than RvR: it needs none of the mass coordination. Rifts are the door to enemy territory and the data is there
(`spawns/Rifts`). The work: a bot deciding to invade, using a rift, hunting players rather than mobs, and knowing when to go home. `PvpService` no
longer throws when a bot kills a real player. The one prerequisite is **visible movement**, so a bot can be intercepted, which the mesh now gives on
every open-world map with steering that no longer walks through scenery.

## Economy

**Selling works**: 37 sales in 38 trips on the night of 2 October, keepers found among the living npcs, refused shops remembered, trade within 8 m
(17 October). A player's own bot never sells, buys, dresses from, identifies or binds what is in its bag: one sold the stored gear of its owner's
other characters (16 October report, [bot-gear.md](bot-gear.md)).

Next is the **broker** (`BrokerService`), on the same "find the right npc" mechanism, then **player shops**. **Gathering and crafting** would feed the
broker and make the countryside look used; they are not started.

## Legions

Priority five. Bots forming and filling legions, which mostly falls out of group play plus a reason to gather.

## Flight, the Abyss, RvR

**Bots fly** ([flight-plan.md](flight-plan.md)): take off and land, reach terraces, fly when the mesh proves there is no walk, follow a flying leader,
glide down a planned glide link, and **cross the Abyss** by flight links between islands ([abyss-crossing-plan.md](abyss-crossing-plan.md)): Reshanta
is lived on both its layers, refused routes 336 to 41 a half hour, no death in the void. Left: playbooks for the classes that die there (spirit master,
aethertech, bard, gunner), and gliding on the way down (milestone 6, only if flights run short of points).

Reshanta is a levelling region (834 civilians, more than Sanctum and Pandaemonium together, median monster 33); both its obelisks now pass the audit
through the flight links. **RvR and sieges** come next on it; their supply line is designed (bots at the level cap become the elders the endgame needs,
[population.md](population.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).

## Gaps nobody had raised

- **Nobody speaks.** No region chat, no call for a group, no word at a trade. Cheap, and high value per line of code.
- **Bots do not yield to real players.** `BotTargetRegistry` stops two bots claiming one mob, but nothing yields to a person. Forty bots stripping a
  spawn or a gathering node make a worse server than none; this is a design rule to settle before a settled 2000.
- **Gear does not keep up.** Bots are dressed at creation (enchanted, socketed, with stigmas) and wear what they loot; crossing 1 to 65 needs buying
  or crafting. Existing gunners keep their cannon until `//bot regear all` (two pistols since 17 October).
- **220 tests, 24 classes of them on the bot module**, run on every deploy: the director, route planning, steering vetting, the four playbooks and a sweep of every skill
  template. The combat half of `PlayerBotAI` is still untested and unsplit, which is why a mistake there is silent rather than loud. Decisions written
  as pure functions of a situation record are what made the suites possible; keep writing them that way.

## Known limits, in order of how much they will bite

1. **How many characters a client tolerates in one place is unmeasured**, the one ceiling this project does not control and the one a player feels.
2. **Memory**: about 800 awake bots fill a 2560 MB heap (90% old generation), which is why the two housing maps (Oriel, Pernon) are not populated.
3. **Only maps with a mesh are planned on.** 24 open-world maps and 45 dungeons have one (`tools/navmesh.ps1 <mapId>`, then `-Obelisks`). The
   Cloister of Kaisinel cannot (no heightmap and almost no geometry); Panesterra lacks a heightmap too.
4. **The mesh is not the geometry**: props placed at run time (gatherables, doors) are not in it, and a 0.5 m grid misses what is thinner. What is left
   of the steps aside are those props ([obstacle-steering-plan.md](obstacle-steering-plan.md)).
5. **Walkable ground comes in islands**, and a route between two does not exist: `NavmeshTool <mapId> components` says so. Fortress obelisks are the
   usual case; 10 of 88 obelisks fail the audit ([navigation-stuck-plan.md](navigation-stuck-plan.md)).
6. **A crash loses at most 5 minutes** of what bots did.
7. **Bots flee only from a lost fight** (`retreatIfLosing`); otherwise they heal, drink and shield themselves, and wait out the soul sickness at a
   spot of their own after a death ([bot-careers.md](bot-careers.md)).

## Done and moved

The population churn of 2026-10-04 ([population-churn-followup.md](population-churn-followup.md)), the stigma blocker, names, Asmodian settlements and
the roster's `owner_id` (see [built.md](built.md)), and the walking-into-scenery plan of 16 and 17 October
([obstacle-steering-plan.md](obstacle-steering-plan.md)).
