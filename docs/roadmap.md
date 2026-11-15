# Roadmap: what is done and what is left, by theme

Rewritten on 7 November 2026 as one page by theme, brought up to date on 15 November. The themes are in the order of the project's priorities (PvE
first, then wild PvP, the broker, shops, legions, RvR), with the tooling that serves them all first; inside a theme, **Done** says what works and
where it is explained, **Left** is in the order to do it. The detail of each item is in its theme's backlog or plan, linked; every document is
indexed in [README.md](README.md), and what each topic does, a paragraph a topic, is in [built.md](built.md). What the project lives with is in
[known-limits.md](known-limits.md).

Words used for a state: **validated** (seen working in game by a tester), **built, unverified** (the code is there, nobody has watched it in game),
**not started**.

## The themes at a glance

| # | Theme | Where it stands | First thing left |
|---|---|---|---|
| 0 | Feedback, tooling and releases | release milestones 1 to 6 validated; the launcher installs the server from nothing (14 November) | the first stable pair (milestone 7) |
| 1 | Navigation | validated on 26 maps and 45 dungeons; four places wait for a note | the four places a tester is to walk |
| 2 | Population and the living world | validated; lot A built (the guard, one tile budget, the density in the launcher) | a rule: bots yield to real players |
| 3 | Classes and combat | six playbooks validated; the fight's decisions out of `PlayerBotAI`, with tests | spirit master, aethertech, bard and gunner playbooks |
| 4 | Groups and dungeons | validated (Noschana, composition); companion interactions unverified | watch duels, friends and trades with a companion |
| 5 | Flight and the Abyss | validated (milestones 1 to 5) | the last flying-with-a-player milestone, waiting for a tester |
| 6 | Capitals and social life | validated (town day, stores, chat on 1 November); the combat meter in game | a decision on the in-game shop |
| 7 | Gear | dressed at creation with flasks; only wings follow the level | gear that keeps up, through the broker or crafting |
| 8 | Wild PvP and rifts | built, unverified (milestones 0 to 2b, off by default) | a watch in game with the property on |
| 9 | Broker | built, unverified (milestones 0 to 4 and the buyer) | a watch in game in both capitals |
| 10 | Player shops, gathering, crafting | not started | player shops |
| 11 | Legions | not started | answer a legion's invitation |
| 12 | RvR and sieges | not started; the supply of high-level bots designed | after the rest is stable |

## 0. Feedback, tooling and releases

**Done**: the test harness (notes in the log, feedback zips, the command inbox, patch tags: [test-harness-plan.md](architecture/history/test-harness-plan.md));
the module structure ([module-structure-plan.md](architecture/history/module-structure-plan.md)); two upstream merges, 69 commits on 28 October
([upstream-merge-plan.md](architecture/history/upstream-merge-plan.md)) and 49 on 13 November, checked in game and measured
([fresh-install-readiness-plan.md](architecture/plans/fresh-install-readiness-plan.md), milestone 7); the measuring and mesh-checking tools
(`measure.sh`, `compare-measures.ps1`, golden routes); about 600 game-server tests run on every deploy; release milestones 1 to 6 (pre-releases with
notes in French and English, the compatibility check, the launcher updating itself and the server); one branch, `main`, every version a tag on it
(14 November). The install from nothing: the schemas in the bundle and `apply-sql.ps1 -Create`, MySQL run as a program with a dump on every stop,
the portable MySQL (29 MB) and the Java runtime (55 MB) as release assets, the launcher installing all of it, tried on a clean machine in Windows
Sandbox and uninstalled from both ends on 14 November, then the development machine itself reinstalled from nothing and a backup restored onto it
([empty-machine-install-plan.md](architecture/history/empty-machine-install-plan.md), [database.md](architecture/database.md)). Published last:
server `v0.1.0-beta.8`, launcher `v0.3.0-beta.13` (15 November).

**Left**:
1. **Feedback as it comes**: the tester reports while playing; each plan's Status line says what has been seen ([feedback/](feedback/README.md)).
2. **Milestone 7, the first stable pair**: a server and a launcher pre-release validated in game together, their commits tagged stable in both
   repositories ([release-process-plan.md](architecture/plans/release-process-plan.md)); the launcher's install from nothing then installs that pair.
3. **The next upstream merge**, as soon as `4.8` moves, then `tools/sync-install.ps1` for the install and the SQL: the sooner, the smaller.
4. **What the fight does to the world is untested**: the decisions are in `FightGiveUps`, `FightVerdicts` and `SkillOrder` with their suites, the
   chase, the casts and the flight are not ([known-limits.md](known-limits.md), Tests).
5. **A backup kept in `Documents` after an uninstall, restored into a new install**: the path has never been tried.

## 1. Navigation

**Done**: a generated navmesh for 26 open-world maps and 45 dungeon geometries, A\* with glide, ride and flight links, a mover that probes only where
the mesh is silent ([how-it-works.md](navigation/how-it-works.md)); long journeys, the slide before a run, gliding with a player and parasitic moves,
closed on 1 November; meshes checked against what people walked ([verification-tools.md](navigation/verification-tools.md)); one tile budget for every
open map, 12 % of the heap, the idle maps' tiles given back first (8 November).

**Left** ([backlog.md](navigation/backlog.md)):
1. **Four places waiting for a tester's walk**: the Airship Dock of Pandaemonium, Altgard's cave, the Fire Temple of Morheim, and Pandaemonium's broker
   and warehouse district, proved shut from the town centre ([step-links-plan.md](navigation/plans/step-links-plan.md)).
2. **Obstacles and slopes** (backlog point 2, reopened by a note): not started.
3. **Dungeons, with the Draupnir report** (backlog point 6): not started.
4. **Lifts and moving platforms** ridden by the bots: what closes Theobomos Lab (72 %) and Rentus (77 and 72 %) ([living-world-plan.md](population/plans/living-world-plan.md), B2).
5. **Ground that refused a bot remembered**, then **prepared corridors** for journeys across Reshanta's 3 km (B1, B3).

## 2. Population and the living world

**Done**: a pool of inhabitants created asleep, a director that wakes and sleeps each region to its target, careers that move a bot's home as it grows,
villages always full, about 950 bots awake on 26 maps ([how-it-works.md](population/how-it-works.md)); the density modes (5 November). Lot A, memory
first ([population-director.md](population/population-director.md)): a guard that holds the wakes while the heap or route planning is past its mark,
bots of unplayed maps exchanged for arrivals where a player is, and the density chosen in the launcher (8 November); the machine's memory in use as a
third signal of the guard, its mark the person's (14 November).

**Left** ([backlog.md](population/backlog.md), [living-world-plan.md](population/plans/living-world-plan.md)):
1. **Bots yield to real players**: a design rule to settle before a settled 2000 (nothing yields to a person today).
2. **Deaths on a world made from nothing**: twice the live world's on five maps of the middle levels; the lead not yet compared is the anchors the
   director moved over days on the live world ([fresh-install-readiness-plan.md](architecture/plans/fresh-install-readiness-plan.md), milestone 5).
3. **The director, lot F**: a pool stocked by level band and role (F1), with pre-filling a map before a player arrives and moving a bot that
   outgrew its region across maps; a day on the clock (F2); sessions (F3).
4. **Careers on the roads, lot C**: travelling merchants (C1), road wardens (C2), caravans (C3).
5. **A density that finds its own level** (A4), once the rest stands.
6. By decision, left as they are: Reshanta's numbers (29 awake with nobody online) and its middle layer, not lived.

## 3. Classes and combat

**Done**: combat in two ticks with targets read from the group's aggro, the death routine, retreat from a lost fight, marks, group positioning
([combat/how-it-works.md](combat/how-it-works.md)); six playbooks validated on 27 October: templar, chanter, cleric, ranger, gladiator, sorcerer
([classes/how-it-works.md](classes/how-it-works.md)); the fight's decisions moved out of `PlayerBotAI` into pure rules with their suites, no behaviour
changed (9 to 13 November, [fight-decisions-plan.md](combat/history/fight-decisions-plan.md)).

**Left**:
1. **Spirit master, aethertech, bard and gunner playbooks first**: on Reshanta's upper layer they died 36 times of 40 in half an hour, against four
   deaths for the other 21. Then the **assassin**. Each from the guide's document, with its data test.
2. **Damage waits for the tank to hold**: today it opens at the same instant and can pull the mob off (`AggroList.getTarget(MOST_HATED)`).
3. **Two tester points still open**: the templar that "drops the fight" (needs a log or a time and a name), the cleric against an assassin's rune.
4. **Bosses' mechanics**: a later refinement, not a prerequisite; positioning against a boss was validated on 27 October.

## 4. Groups and dungeons

**Done**: invitations, following, instances entered and left with the player (Noschana), a bot cut off from its player put beside it after 13 s, groups
filled from the Find Group window with a tank, a healer and a support ([social/how-it-works.md](social/how-it-works.md)), all validated on 27 October;
45 dungeon meshes; duels, friends and trades with a companion built on 19 October; a bot in a team earns at its players' membership, every account
premium (7 November).

**Left** ([backlog.md](social/backlog.md)):
1. **Watch duels, friends and trades with a companion** in game: built, unverified ([player-interactions-plan.md](social/plans/player-interactions-plan.md)).
2. **Playing with one's bots**: the starting level, bots rolling the dice, orders and marks, one's own characters; not started
   ([group-options-plan.md](social/plans/group-options-plan.md)). The launcher shows each once the server has it.
3. **Cohorts**: a bot in an alliance acting as a member of its own group of six; not started ([cohort-plan.md](social/plans/cohort-plan.md)).
4. **Composition**: the Recruit Group Members tab, alliances, and bots posting in the Find Group window themselves (lot E1).
5. **Bots group with each other** in the field (lot E2): today they never do.
6. **Level 50 bots** for the Theobomos instances and Dark Poeta, asked on 9 October; awaiting another test server's feedback.
7. **Doors, lifts and windstreams used by a bot itself**, only if the 13 s wait proves too long.
8. **The arenas**: instances of their own, after the dungeons.

## 5. Flight and the Abyss

**Done**: take-off, landing, cruise and flown descents, following a flying player (milestones 1 to 5 validated), Reshanta's islands crossed by air and
lived on both layers, 120 of its 134 places ([flight/how-it-works.md](flight/how-it-works.md)).

**Left** ([backlog.md](flight/backlog.md)):
1. **Flying with a player, milestone 6**: waits for a time and a bot name from the tester ([flying-with-a-player-plan.md](flight/plans/flying-with-a-player-plan.md)).
2. **Ambient flights stall on fly-zone maps** (a leg handed over again 14 to 100 times in forty minutes): not looked into.
3. **The new playbooks of theme 3**, which are what the Abyss's deaths come down to.
4. **Gliding on the way down** (Abyss crossing milestone 6), only if flights run short of points ([abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md)).

## 6. Capitals and social life

**Done**: a town day in the capitals, 40 bot stores a capital at broker prices, high-level visitors, shouted adverts ([capitals/how-it-works.md](capitals/how-it-works.md));
the bots' chat in French or English with jokes and reactions, and the stores' consumables, validated on 1 November. Lot H's first two: pages served to
the client's browser (H1) and an interface addon (H2), with the combat meter, a team's damage and healing per skill (7 and 8 November,
[addon-pages.md](architecture/addon-pages.md)); a staff member's modes switched from the launcher through the admin channel (14 November).

**Left**:
1. **The in-game shop**: the server has none (the 4.8 client asks for a web shop token upstream answers empty). A decision first: a small web shop,
   or the old packets if the client still reads them ([social/backlog.md](social/backlog.md)).
2. **A world that talks about itself, lot I**: personalities (I2), then true rumours (I1) and contested named monsters (I3, which needs E2)
   ([living-world-plan.md](population/plans/living-world-plan.md)).
3. **The rest of lot H**: GM tools in game (H3), and what else is used while playing (H4: teleport, one's characters, the world map, orders, a
   tester's note).

## 7. Gear

**Done**: bots dressed at creation (enchanted, socketed, with stigmas), wearing what they loot, wings renewed with the level, junk sold, the best of what
they own worn, life and mana flasks handed over at creation and regear from level 10 and drunk ([gear/how-it-works.md](gear/how-it-works.md)).

**Left**: **gear that keeps up** from 1 to 65, through buying or crafting, so it follows themes 9 and 10 ([capitals/backlog.md](capitals/backlog.md)).

## 8. Wild PvP and rifts

**Done**: milestones 0 (where PvP really is), 1 (a player as a target), 2 and 2b (defence and PvP behaviour), built, unverified; the property
`gameserver.playerbot.pvp.enable` is off by default ([wild-pvp-plan.md](combat/plans/wild-pvp-plan.md)).

**Left**:
1. **A watch in game** with the property on: no bot has met a player yet.
2. **Hunting grounds in PvP zones** (milestone 3), then **rifts** (4), then the **measure** (5).
3. **Lot D** of [living-world-plan.md](population/plans/living-world-plan.md): limits on a fight (D1), rift raids (D2), raids the world answers (D3),
   and last grudges (D4).

## 9. Broker

**Done**: a house the server stocks, one per race: the census, the door, the house characters, the seller, settling and relisting, and a buyer of what
players list, built, unverified; the seller is on by default ([auction-house-plan.md](capitals/plans/auction-house-plan.md)).

**Left**: 1. **the watch in game** (milestone 5): categories filled, prices believable, a purchase that works, in both capitals; 2. the buyer's watch;
3. the **measure** (milestone 7): heap, tick cost, listings against targets, prices against the guide's.

## 10. Player shops, gathering, crafting

**Done**: bots sell their junk to merchants ([capitals/backlog.md](capitals/backlog.md)). **Left**: 1. **player shops** run by bots; 2. **gathering and
crafting**, which would feed the broker and make the countryside look used; 3. travelling merchants opening a store on the road (lot C1).

## 11. Legions

**Not started.** **Left**: 1. **answering a legion's invitation** (asked in game on 19 October); 2. **bot legions born of who played together** (lot G1),
which needs bots grouping with each other first (theme 4).

## 12. RvR and sieges

**Not started**, the stretch goal. Its supply line is designed: bots at the level cap become the elders the endgame needs
([population/how-it-works.md](population/how-it-works.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).

## The living world plan and the group options, in their own order

Decided on 5 November and spread above by theme. The [living-world-plan.md](population/plans/living-world-plan.md) proposes this order across
themes: 1. A1, then A3, A2 after the density histogram (all three built on 8 November); 2. B1; 3. D1 to D3; 4. E1, E2; 5. C1 to C3; 6. I2, then I1
and I3; 7. F1 to F3; 8. B2 and B3, unless dungeons are brought forward; 9. G1, D4, A4 when the rest stands; H1 and H2 beside any of them (built). The
[group-options-plan.md](social/plans/group-options-plan.md) (starting level, dice, orders and marks, one's own characters) stands apart, each point
small. Left out by decision: quests for world bots, patching the client's executables, rewards of our own invention, changes to monster strength, a
market other than the native broker, freezing the maps nobody is on, another mesh format, a decision engine other than the playbooks, machines below
8 GB of memory as a target.
