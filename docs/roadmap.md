# Roadmap: what is left to do, all of it

Rewritten on 15 November 2026. The first three sections are the whole picture: every theme and what is left in it, the order to do it in, what
is not done by decision. Then each theme in detail: themes 0 to 4 below, 5 to 12 in [roadmap-world.md](roadmap-world.md). What already works is in
[built.md](built.md), what the project lives with in [known-limits.md](known-limits.md), every document in [README.md](README.md). States:
**validated** (seen in game by a tester), **built, unverified**, **not started**.

## Everything that is left, by theme

Themes in the project's priority order (PvE, wild PvP, the broker, shops, legions, RvR), tooling first. The items are named here; the numbers are
those of the theme's detail.

| # | Theme | Where it stands | What is left, in order |
|---|---|---|---|
| 0 | Tooling and releases | install from nothing done; release milestones 1-6 validated | 1 feedback as it comes; 2 the next upstream merge; 3 the emulator's gaps met in play |
| 1 | Navigation | validated on 26 maps and 45 dungeons | 1 four places to walk; 2 obstacles and slopes; 3 dungeons walked; 4 the group instances without a mesh; 5 lifts; 6 learned ground; 7 corridors |
| 2 | Population | validated; memory guarded | 1 road careers: merchants, wardens, caravans; 2 the director: a pool stocked by level and role |
| 3 | Combat | six playbooks validated | 1 four playbooks, then the assassin; 2 threat; 3 the tank's tools; 4 the healer's priorities; 5 bosses; 6 after a wipe |
| 4 | Groups and companions | validated (dungeons, composition, duels, friends, trades) | 1 formations; 2 a companion's gear in the player's hand; 3 cohorts; 4 bots group together; 5 doors and arenas |
| 5 | Flight and the Abyss | validated (milestones 1-5) | nothing, by decision |
| 6 | Capitals and social life | validated (town day, stores, chat, combat meter) | 1 personalities; 2 true rumours; 3 contested named monsters; 4 bots that take the first step; 5 bots that answer; 6 pages in game, GM tools |
| 7 | Gear | dressed at creation, loot worn | nothing, by decision |
| 8 | Wild PvP and rifts | built, unverified, off by default | 1 a watch in game; 2 hunting grounds, rifts, measure; 3 limits on a fight; 4 rift raids; 5 raids the world answers; 6 grudges |
| 9 | Broker | validated (15 November) | 1 prices and goods adjusted; 2 the measure |
| 10 | Player shops | not started | 1 player shops run by bots |
| 11 | Legions | not started | 1 answer an invitation; 2 bot legions born of who played together |
| 12 | RvR and sieges | not started | after the rest is stable |

## The order to do it in

1. **Finish and steady what exists** (now): the upstream merge (0.2), the four places a tester walks (1.1), the four playbooks and the assassin
   (3.1), threat, the tank, the healer (3.2 to 3.4), the PvP watch (8.1), the broker's prices (9.1). Several wait on a
   tester's time; the playbooks do not. Navigation goes on beside, in its own order: obstacles, dungeons, the group instances' meshes, then lifts,
   learned ground and corridors (1.2 to 1.7).
2. **The living world**, in the order decided on 5 November ([living-world-plan.md](population/plans/living-world-plan.md), its lots in brackets):
   the limits on a fight, rift raids, raids the world answers (8.3 to 8.5, D1 to D3); bots grouping
   together (4.4, E2); merchants, wardens, caravans (2.1, C1 to C3); personalities, then rumours and contested named monsters,
   bots that take the first step (6.1 to 6.4, I); the director (2.2, F1). The pages in game (6.6, H) go beside any of them.
3. **When the rest stands**: bot legions (11.2, G1), grudges (8.6, D4), player shops (10), then RvR (12).

## Not done, by decision

Quests for world bots and companions; patching the client's executables; rewards of our own invention (tickets, passes, account bonuses); changes
to monster strength; a market other than the native broker; freezing the maps nobody is on; another mesh format; a decision engine other than the
playbooks. On 15 November: a stable release pair for now (pre-releases go on); tests of what the fight does to the world, nothing
wrong being seen in game; bots yielding to real players; a day on the clock and bot sessions (F2, F3); a density that finds its own level (A4); slicing route searches for small processors (the guard already holds wakes while planning waits); an in-game shop; gathering and crafting by bots; bots seeking, buying or enchanting their own gear; bots trading at the
broker in their own name; companions made on demand and saved teams; hiring a world bot; bots entering instance queues themselves; bots posting in the Find Group window (E1); level 50 bots; mentors in Poeta and Ishalgen; in flight, the Terminon camp entrance, the stalled ambient flights and gliding on the way down; a rank of play
per bot; two old tester points (the templar that "drops the fight", the cleric against an assassin's rune); the group options (a starting level of 10, bots rolling the dice, stay, guard and passive orders, kill and sleep marks, one's own
characters brought in as bots).

## 0. Tooling and releases

**Done**: the test harness, two upstream merges (28 October, 13 November), the measuring and mesh-checking tools, about 600 tests on every deploy, the
release process (one branch, pre-releases in two languages, the launcher updating itself and the server) and the install from nothing (portable
MySQL and Java, tried on a clean machine on 14 November) ([architecture/how-it-works.md](architecture/how-it-works.md),
[empty-machine-install-plan.md](architecture/history/empty-machine-install-plan.md)). Published last: server `v0.1.0-beta.8`, launcher `v0.3.0-beta.13`.

**Left**:
1. **Feedback as it comes**: the tester reports while playing; each plan's Status line says what has been seen ([feedback/](feedback/README.md)).
2. **The next upstream merge**, then `tools/sync-install.ps1`: `4.8` has moved by 9 commits to 14 November (Rentus' Kuhara, the siege fortress
   bosses, revival after a deadly fall, a chat channel leak, decomposable sets, manastones); the sooner, the smaller.
3. **The emulator's own gaps met while playing** (a quest that cannot be finished, an npc missing, a boss that does not act): each fixed in a commit
   of its own, offered upstream when it is theirs, so merges stay small. No plan yet.

## 1. Navigation

**Done**: a generated navmesh for 26 open-world maps and 45 dungeon geometries, A\* with glide, ride and flight links, one tile budget for every map,
meshes checked against what people walked ([how-it-works.md](navigation/how-it-works.md)).

**Left** ([backlog.md](navigation/backlog.md)):
1. **Four places waiting for a tester's walk**: the Airship Dock of Pandaemonium, Altgard's cave, the Fire Temple of Morheim, and Pandaemonium's broker
   and warehouse district ([step-links-plan.md](navigation/plans/step-links-plan.md)).
2. **Obstacles and slopes** (backlog point 2, reopened by a note): not started.
3. **Dungeons, with the Draupnir report** (backlog point 6), then **every dungeon mesh walked**: only Noschana's has been.
4. **The group instances that have no mesh** (counted on 15 November: 81 of the data's 161 maps have none, most of them solo instances, PvP
   arenas and battlefields, test maps or fields without a heightmap): the PvE instances for more than one player, Empyrean Crucible (6), Nightmare
   Circus (6, two maps), Shugo Imperial Tomb and the Shugo Emperor's Vault (3), Infinity Shard and Stonespear Reach (12, which need cohorts),
   generated with `tools/navmesh.ps1` and checked like the 45 others. No plan yet.
5. **Lifts and moving platforms** (B2): a link "board here, ride, step off there" timed from when the platform is at each end, a ceiling of passengers
   per platform; it closes Theobomos Lab (72 %), Rentus (77 and 72 %) and the Pandaemonium dock, and would end the 13 s wait beside a player.
6. **Ground that refused a bot remembered** (B1): a mesh cell refused to two different bots on two different days becomes blocked for the planner,
   kept in a file per map, listed by `//bot nav learned`, expired after a week unless confirmed, and fed back to the mesh generator.
7. **Prepared corridors** (B3) between the places of the plan, built offline and checked by the golden routes, for journeys across Reshanta's 3 km.

## 2. Population and the living world

**Done**: a pool created asleep, a director that wakes each region to its target, careers that move a bot's home, villages always full, about 950 bots
on 26 maps, the density modes and the memory guard (heap, route planning, the machine's memory) ([how-it-works.md](population/how-it-works.md)).

**Left** ([backlog.md](population/backlog.md), [living-world-plan.md](population/plans/living-world-plan.md)):
1. **Careers on the roads, lot C**:
   - **Travelling merchants** (C1): a bot walks between two villages of its region and opens a store when a player stops within a few metres, closing
     it when they leave. Its stock is what the region's bots really looted and sold to it, finite and renewed by their hunting: a merchant from a
     dangerous region carries rarer goods, and a player learns where to look.
   - **Road wardens** (C2): bots on a beat between two places, spaced out, who clear monsters near the road, heal or shield a player of their
     faction in trouble, and fight the invader in a PvP zone. They are the first defenders lot D needs.
   - **Caravans** (C3): a merchant escorted by two or three wardens, from one village to the next at set hours, announced in Region chat. In a PvP
     zone the other faction's bots may attack it: an event that happens whether a player is there or not, and one a player can defend or raid.
2. **The director, lot F1**: a pool stocked by level band and role, so a missing tank or healer at a level is grown rather than left missing; a
   map pre-filled before a player arrives; a bot that outgrew its region moved across maps; the oldest retired at the cap.
3. By decision, left as they are: Reshanta's numbers (29 awake with nobody online) and its middle layer, not lived.

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
4. **The healer's priorities**: cleanses by severity (sleep, fear, paralysis first, where today the cleric takes them as it finds them), and
   resurrection in a fight when a skill allows it (after one, the cleric and the chanter already raise a mate within 15 m).
5. **Bosses' mechanics**: areas avoided by shape (circle, ring, cone, line) from the cast the bot sees, interrupts shared out in the group, profiles
   for the named encounters of the dungeons played; positioning against a boss was validated on 27 October.
6. **After a wipe**: rest, the way back to the bodies or the bind point, the group gathered again before the next pull; today each bot starts
   again alone from its obelisk.

## 4. Groups and companions

**Done**: invitations, following, instances with the player (Noschana), a bot cut off put beside its player after 13 s, groups filled from the Find
Group window, duels, friends and trades with a companion (validated 15 November) ([social/how-it-works.md](social/how-it-works.md)).

**Left** ([backlog.md](social/backlog.md)):
1. **Formations** around the player (behind, line, wedge, circle), held while walking and broken by a fight. No plan yet.
2. **The player's hand on a companion's gear**: its bag shown, an item put on by the player, enchanting and extraction paid from the companion's kinah
   on the player's order. No plan yet.
3. **Cohorts**: a bot in an alliance acting as a member of its own group of six ([cohort-plan.md](social/plans/cohort-plan.md)).
4. **Bots group with each other** in the field (lot E2): pairs and trios hunting together, met in the countryside like players; a small group also
   survives what kills a lone bot. Today they never do; lots G1 and I3 need it.
5. **Doors, lifts and windstreams used by a bot itself**, only if the 13 s wait proves too long; then **the arenas**, instances of their own.
