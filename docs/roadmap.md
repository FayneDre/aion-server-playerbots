# Roadmap: what is done and what is left, by theme

One page by theme (7 November), what is left detailed on 15 November. Themes in the project's priority order, tooling first; **Done** in a line with
where it is explained ([built.md](built.md)), **Left** in the order to do it, with its plan, its backlog or "no plan yet". Index: [README.md](README.md);
limits: [known-limits.md](known-limits.md). States: **validated** (seen in game by a tester), **built, unverified**, **not started**.

## The themes at a glance

| # | Theme | Where it stands | First thing left |
|---|---|---|---|
| 0 | Feedback, tooling and releases | release milestones 1 to 6 validated; the launcher installs the server from nothing | the first stable pair (milestone 7) |
| 1 | Navigation | validated on 26 maps and 45 dungeons; four places wait for a note | the four places a tester is to walk |
| 2 | Population and the living world | validated; lot A built (the guard, one tile budget, the density in the launcher) | a rule: bots yield to real players |
| 3 | Classes and combat | six playbooks validated; the fight's decisions out of `PlayerBotAI`, with tests | spirit master, aethertech, bard and gunner playbooks |
| 4 | Groups and companions | validated (Noschana, composition, duels, friends, trades) | playing with one's bots: orders, dice, one's characters |
| 5 | Flight and the Abyss | validated (milestones 1 to 5) | the last flying-with-a-player milestone, waiting for a tester |
| 6 | Capitals and social life | validated (town day, stores, chat); the combat meter in game | personalities and true rumours (lot I) |
| 7 | Gear | dressed at creation with flasks; only wings follow the level | gear that keeps up with the level |
| 8 | Wild PvP and rifts | built, unverified (milestones 0 to 2b, off by default) | a watch in game with the property on |
| 9 | Broker | validated in game (15 November) | prices and goods adjusted from the tester's list |
| 10 | Player shops, gathering, crafting | not started | player shops |
| 11 | Legions | not started | answer a legion's invitation |
| 12 | RvR and sieges | not started; the supply of high-level bots designed | after the rest is stable |

## 0. Feedback, tooling and releases

**Done**: the test harness, two upstream merges (28 October, 13 November), the measuring and mesh-checking tools, about 600 tests on every deploy, the
release process (one branch, pre-releases in two languages, the launcher updating itself and the server) and the install from nothing (portable
MySQL and Java, tried on a clean machine on 14 November) ([architecture/how-it-works.md](architecture/how-it-works.md),
[empty-machine-install-plan.md](architecture/history/empty-machine-install-plan.md)). Published last: server `v0.1.0-beta.8`, launcher `v0.3.0-beta.13`.

**Left**:
1. **Feedback as it comes**: the tester reports while playing; each plan's Status line says what has been seen ([feedback/](feedback/README.md)).
2. **Milestone 7, the first stable pair**: a server and a launcher pre-release validated in game together, tagged stable in both repositories
   ([release-process-plan.md](architecture/plans/release-process-plan.md)); the launcher's install from nothing then installs that pair.
3. **The next upstream merge**, as soon as `4.8` moves, then `tools/sync-install.ps1`: the sooner, the smaller.
4. **What the fight does to the world is untested**: the chase, the casts and the flight ([known-limits.md](known-limits.md), Tests).
5. **The emulator's own gaps met while playing** (a quest that cannot be finished, an npc missing, a boss that does not act): each fixed in a commit
   of its own, offered upstream when it is theirs, so merges stay small. No plan yet.

## 1. Navigation

**Done**: a generated navmesh for 26 open-world maps and 45 dungeon geometries, A\* with glide, ride and flight links, one tile budget for every map,
meshes checked against what people walked ([how-it-works.md](navigation/how-it-works.md)).

**Left** ([backlog.md](navigation/backlog.md)):
1. **Four places waiting for a tester's walk**: the Airship Dock of Pandaemonium, Altgard's cave, the Fire Temple of Morheim, and Pandaemonium's broker
   and warehouse district ([step-links-plan.md](navigation/plans/step-links-plan.md)).
2. **Obstacles and slopes** (backlog point 2, reopened by a note): not started.
3. **Dungeons, with the Draupnir report** (backlog point 6), then **every dungeon mesh walked**: only Noschana's has been.
4. **The maps and instances that have no mesh**: counted first (the data holds more instances than our 45 geometries), then generated and checked
   the same way. No plan yet.
5. **Lifts and moving platforms** ridden by the bots, a ceiling of passengers per platform: what closes Theobomos Lab (72 %) and Rentus (77 and 72 %)
   ([living-world-plan.md](population/plans/living-world-plan.md), B2).
6. **Ground that refused a bot remembered**, then **prepared corridors** for journeys across Reshanta's 3 km (B1, B3).
7. **Route planning's processor cost**: 6 to 7 cores of 16 for 1430 bots on 5 November. Searches cut into slices of a few milliseconds and shared out,
   so the cost is spread rather than queued; measured with `measure.sh`. No plan yet.

## 2. Population and the living world

**Done**: a pool created asleep, a director that wakes each region to its target, careers that move a bot's home, villages always full, about 950 bots
on 26 maps, the density modes and the memory guard (heap, route planning, the machine's memory) ([how-it-works.md](population/how-it-works.md)).

**Left** ([backlog.md](population/backlog.md), [living-world-plan.md](population/plans/living-world-plan.md)):
1. **Bots yield to real players**: a design rule to settle before a settled 2000 (nothing yields to a person today).
2. **The director, lot F**: a pool stocked by level band and role (F1), pre-filling a map before a player arrives, moving a bot that outgrew its region
   across maps, retiring the oldest at the cap; a day on the clock (F2); sessions, a bot leaving and coming back as a player does (F3).
3. **Careers on the roads, lot C**: travelling merchants selling what the region looted (C1), road wardens who clear, heal and defend (C2), caravans (C3).
4. **Bots that do not all play alike**: a rank drawn once, from clumsy to excellent, read by its playbook (reaction time, skill choice) and by its
   gear; with the temperaments of lot I2, the two make a bot a person. No plan yet.
5. **A density that finds its own level** (A4), once the rest stands.
6. By decision, left as they are: Reshanta's numbers (29 awake with nobody online) and its middle layer, not lived.

## 3. Classes and combat

**Done**: combat in two ticks from the group's aggro, the death routine, retreat, marks, group positioning, six playbooks validated (templar, chanter,
cleric, ranger, gladiator, sorcerer), the fight's decisions as pure rules with their suites ([combat/how-it-works.md](combat/how-it-works.md)).

**Left**:
1. **Spirit master, aethertech, bard and gunner playbooks first**: on Reshanta's upper layer they died 36 times of 40 in half an hour, against four
   deaths for the other 21. Then the **assassin** (runes and chains). Each from the guide's document, with its data test.
2. **Threat**: damage waits for the tank's first blows, and pauses when its threat nears the tank's (about 85 %) until it falls back (about 70 %);
   today it opens at the same instant and can pull the mob off (`AggroList.getTarget(MOST_HATED)`).
3. **The tank's tools**: a pull from range on the player's order, the monster brought back to the group; an area taunt once loose monsters reach a
   count; never taking a monster off another tank.
4. **The healer's priorities**: cleanses by severity (sleep, fear, paralysis first), resurrection after a fight and in one when a skill allows it.
5. **Bosses' mechanics**: areas avoided by shape (circle, ring, cone, line) from the cast the bot sees, interrupts shared out in the group, profiles
   for the named encounters of the dungeons played; positioning against a boss was validated on 27 October.
6. **After a wipe**: rest, the way back to the bodies or the bind point, the group gathered again before the next pull.
7. **Two tester points still open**: the templar that "drops the fight" (needs a log or a time and a name), the cleric against an assassin's rune.

## 4. Groups and companions

**Done**: invitations, following, instances with the player (Noschana), a bot cut off put beside its player after 13 s, groups filled from the Find
Group window, duels, friends and trades with a companion (validated 15 November) ([social/how-it-works.md](social/how-it-works.md)).

**Left** ([backlog.md](social/backlog.md)):
1. **Playing with one's bots** ([group-options-plan.md](social/plans/group-options-plan.md)): the starting level (1, or 10 at the Ascension); bots
   rolling the dice for what their class wears; the orders follow, stay, guard and passive; the marks kill first and keep asleep, and the client's
   own numbered marks followed; one's own characters brought into the group as bots (`//bot alt`), their progress kept.
2. **Companions made on demand**: a class and the player's level chosen, the companion kept between sessions and called back by name; saved teams that
   bring their missing members online with their roles and orders. No plan yet.
3. **Formations** around the player (behind, line, wedge, circle), held while walking and broken by a fight. No plan yet.
4. **The player's hand on a companion's gear**: its bag, an item put on, a gear policy (what it earned only, or made at a quality and level),
   enchanting and extraction by its own kinah. No plan yet; it shares its rules with theme 7.
5. **Hiring a world bot** for a time, paid in kinah from the player, at a price by its level: it fights as a companion, then goes home. No plan yet.
6. **Cohorts**: a bot in an alliance acting as a member of its own group of six ([cohort-plan.md](social/plans/cohort-plan.md)).
7. **Composition, lot E1**: the Recruit Group Members tab and alliances; bots posting instance runs in the Find Group window, a ceiling per tab,
   and whispering a player whose post lacks their role; bots entering the instance queues themselves and running an instance among themselves.
8. **Bots group with each other** in the field (lot E2): today they never do.
9. **Level 50 bots** for the Theobomos instances and Dark Poeta, asked on 9 October; awaiting another test server's feedback.
10. **Doors, lifts and windstreams used by a bot itself**, only if the 13 s wait proves too long; then **the arenas**, instances of their own.

## 5. Flight and the Abyss

**Done**: take-off, landing, cruise and flown descents, following a flying player, Reshanta crossed by air and lived on both layers, 120 of its 134
places ([flight/how-it-works.md](flight/how-it-works.md)).

**Left** ([backlog.md](flight/backlog.md)):
1. **Flying with a player, milestone 6**: waits for a time and a bot name from the tester ([flying-with-a-player-plan.md](flight/plans/flying-with-a-player-plan.md)).
2. **Ambient flights stall on fly-zone maps** (a leg handed over again 14 to 100 times in forty minutes): not looked into.
3. **The new playbooks of theme 3**, which are what the Abyss's deaths come down to.
4. **Gliding on the way down** (Abyss crossing milestone 6), only if flights run short of points ([abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md)).

## 6. Capitals and social life

**Done**: a town day in the capitals, 40 bot stores a capital, high-level visitors, shouted adverts, the bots' chat in French or English with jokes
and reactions ([capitals/how-it-works.md](capitals/how-it-works.md)); pages in the client's browser and an interface addon with the combat meter
([addon-pages.md](architecture/addon-pages.md)); a staff member's modes switched from the launcher.

**Left**:
1. **A world that talks about itself, lot I**: personalities (I2), then true rumours (I1) and contested named monsters (I3, which needs E2)
   ([living-world-plan.md](population/plans/living-world-plan.md)).
2. **Bots that answer when spoken to**: a whisper or a line naming a bot answered in its own words, a request in chat ("invite me", "come") acted on
   when it can be. No plan yet.
3. **The rest of lot H**: a page of one's companions (roster, roles, orders, gear), GM tools in game (H3), and what else is used while playing (H4:
   teleport, one's characters, the world map, orders, a tester's note); a world map needs an admin channel command (`world`) the launcher reads too.

## 7. Gear

**Done**: bots dressed at creation (enchanted, socketed, with stigmas), wearing what they loot, wings renewed with the level, junk sold, the best of
what they own worn, life and mana flasks from level 10 ([gear/how-it-works.md](gear/how-it-works.md)).

**Left** ([capitals/backlog.md](capitals/backlog.md)):
1. **Gear that keeps up** from 1 to 65: each bot works out the best gear for its stage now and then, and gets it by buying at the broker or a vendor,
   by crafting (theme 10), or by hunting the monster that drops it.
2. **Enchanting and extraction** by bots themselves, with a budget of their own kinah.

## 8. Wild PvP and rifts

**Done**: where PvP really is, a player as a target, defence and PvP behaviour, built, unverified; `gameserver.playerbot.pvp.enable` is off by default
([wild-pvp-plan.md](combat/plans/wild-pvp-plan.md)).

**Left**:
1. **A watch in game** with the property on: no bot has met a player yet.
2. **Hunting grounds in PvP zones** (milestone 3), then **rifts** (4), then the **measure** (5).
3. **Lot D** of [living-world-plan.md](population/plans/living-world-plan.md): limits on a fight (D1: pursuit distance and time, the strength of each
   side, attackers per target, a level band), rift raids (D2), raids the world answers (D3), and last grudges (D4).

## 9. Broker

**Done**: a house the server stocks, one per race, the seller, settling and relisting, and a buyer of what players list, seen working in game on 15
November ([auction-house-plan.md](capitals/plans/auction-house-plan.md)).

**Left**:
1. **Prices and goods adjusted** from the tester's list.
2. **Bots as traders**: what a bot loots and does not wear listed at the broker in its own name, what it needs bought there with its own kinah, at
   prices read from the sales already made; it feeds theme 7.
3. The **measure** (milestone 7): heap, tick cost, listings against targets, prices against the guide's.

## 10. Player shops, gathering, crafting

**Done**: bots sell their junk to merchants ([capitals/backlog.md](capitals/backlog.md)).

**Left**:
1. **Player shops** run by bots.
2. **Gathering**: the professions learned, gathering points worked in the field and in flight, which makes the countryside look used.
3. **Crafting**: recipes learned, work orders done, materials gathered or bought; what is made sold at the broker or worn. No plan yet for 2 and 3.
4. Travelling merchants opening a store on the road (lot C1).

## 11. Legions

**Not started.** **Left**:
1. **Answering a legion's invitation** (asked in game on 19 October), and its rank's permissions respected.
2. **Bot legions born of who played together** (lot G1), named from a list written for it, which needs bots grouping with each other first (theme 4).

## 12. RvR and sieges

**Not started**, the stretch goal. Its supply line is designed: bots at the level cap become the elders the endgame needs
([population/how-it-works.md](population/how-it-works.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).

## The living world plan and the group options, in their own order

Decided on 5 November and spread above by theme. The [living-world-plan.md](population/plans/living-world-plan.md) proposes: 1. A1, A3, A2 (built 8
November); 2. B1; 3. D1 to D3; 4. E1, E2; 5. C1 to C3; 6. I2, then I1 and I3; 7. F1 to F3; 8. B2 and B3, unless dungeons are brought forward; 9. G1,
D4, A4 when the rest stands; H beside any of them. The [group-options-plan.md](social/plans/group-options-plan.md) stands apart, each point small.
**Left out by decision**: quests for world bots and companions, patching the client's executables, rewards of our own invention (tickets, passes,
account bonuses), changes to monster strength, a market other than the native broker, freezing the maps nobody is on, another mesh format, a decision
engine other than the playbooks, an in-game shop (15 November).
