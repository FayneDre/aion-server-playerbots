# Roadmap: what is done and what is left, by theme

One page by theme (7 November), what is left detailed on 15 November; themes 5 to 12 are in [roadmap-world.md](roadmap-world.md). Themes in the
project's priority order, tooling first; **Done** in a line with where it is explained ([built.md](built.md)), **Left** in the order to do it, with its
plan, its backlog or "no plan yet". Index: [README.md](README.md); limits: [known-limits.md](known-limits.md). States: **validated** (seen in game by
a tester), **built, unverified**, **not started**.

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
| 7 | Gear | dressed at creation with flasks; left as it is by decision | nothing |
| 8 | Wild PvP and rifts | built, unverified (milestones 0 to 2b, off by default) | a watch in game with the property on |
| 9 | Broker | validated in game (15 November) | prices and goods adjusted from the tester's list |
| 10 | Player shops | not started | player shops |
| 11 | Legions | not started | answer a legion's invitation |
| 12 | RvR and sieges | not started; the supply of high-level bots designed | after the rest is stable |

**The order across themes**, decided on 5 November ([living-world-plan.md](population/plans/living-world-plan.md)): 1. lot A (built 8 November);
2. B1; 3. D1 to D3; 4. E1, E2; 5. C1 to C3; 6. I2, then I1 and I3; 7. F1 to F3; 8. B2 and B3, unless dungeons are brought forward; 9. G1, D4, A4 when
the rest stands; lot H beside any of them. The group options ([group-options-plan.md](social/plans/group-options-plan.md)) stand apart, each point small.

**Left out by decision**: quests for world bots and companions, patching the client's executables, rewards of our own invention (tickets, passes,
account bonuses), changes to monster strength, a market other than the native broker, freezing the maps nobody is on, another mesh format, a decision
engine other than the playbooks; on 15 November, an in-game shop, gathering and crafting by bots, bots seeking, buying or enchanting their own gear,
bots trading at the broker in their own name, companions made on demand and saved teams, hiring a world bot, bots entering instance queues themselves.

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
5. **Lifts and moving platforms** (B2): a link "board here, ride, step off there" timed from when the platform is at each end, a ceiling of passengers
   per platform; it closes Theobomos Lab (72 %), Rentus (77 and 72 %) and the Pandaemonium dock, and would end the 13 s wait beside a player.
6. **Ground that refused a bot remembered** (B1): a mesh cell refused to two different bots on two different days becomes blocked for the planner,
   kept in a file per map, listed by `//bot nav learned`, expired after a week unless confirmed, and fed back to the mesh generator.
7. **Prepared corridors** (B3) between the places of the plan, built offline and checked by the golden routes, for journeys across Reshanta's 3 km.
8. **Route planning's processor cost**: 6 to 7 cores of 16 for 1430 bots on 5 November. Searches cut into slices of a few milliseconds and shared out,
   so the cost is spread rather than queued; measured with `measure.sh`. No plan yet.

## 2. Population and the living world

**Done**: a pool created asleep, a director that wakes each region to its target, careers that move a bot's home, villages always full, about 950 bots
on 26 maps, the density modes and the memory guard (heap, route planning, the machine's memory) ([how-it-works.md](population/how-it-works.md)).

**Left** ([backlog.md](population/backlog.md), [living-world-plan.md](population/plans/living-world-plan.md)):
1. **Bots yield to real players**: a design rule to settle before a settled 2000. Today forty bots can strip a hunting ground a player came for.
2. **Careers on the roads, lot C**:
   - **Travelling merchants** (C1): a bot walks between two villages of its region and opens a store when a player stops within a few metres, closing
     it when they leave. Its stock is what the region's bots really looted and sold to it, finite and renewed by their hunting: a merchant from a
     dangerous region carries rarer goods, and a player learns where to look.
   - **Road wardens** (C2): bots on a beat between two places, spaced out, who clear monsters near the road, heal or shield a player of their
     faction in trouble, and fight the invader in a PvP zone. They are the first defenders lot D needs.
   - **Caravans** (C3): a merchant escorted by two or three wardens, from one village to the next at set hours, announced in Region chat. In a PvP
     zone the other faction's bots may attack it: an event that happens whether a player is there or not, and one a player can defend or raid.
3. **The director, lot F**: a pool stocked by level band and role, so a missing tank or healer at a level is grown rather than left missing, a map
   pre-filled before a player arrives, a bot that outgrew its region moved across maps, the oldest retired at the cap (F1); a day on the clock, the
   countryside fuller in the evening and thinner at four in the morning (F2); sessions, a bot leaving after an hour or a few and coming back later
   where it left, its place taken by a sleeper, so a player meets new faces without anyone appearing in sight (F3).
4. **Bots that do not all play alike**. No plan yet.
   - A **rank of play** drawn once at creation and kept for life: clumsy, ordinary, good, very good, excellent, with shares as options (about
     10, 40, 30, 15 and 5 %).
   - What it changes, through the playbooks and nothing else: the time before reacting (a cast, an interrupt, a heal: from about 0.2 s for the best
     to 1.5 s for the clumsy); the skill chosen (the best follow the playbook's order and its chains, the clumsy now and then cast a weaker skill or
     miss an interrupt); standing in an area, pulling a second pack, drinking late, retreating late, more often the lower the rank.
   - How it is dressed at creation: enchantments and stones in step with the rank, as a player who plays well also gears well.
   - **A floor in a player's group**: a bot grouped with a player plays at least *good* (an option), so the clumsy are met in the world and never
     sink a player's dungeon.
   - With the temperaments of lot I2 (chatty, quiet, brawler, loner, sociable), the two make a bot a person; `//bot` shows both. Measured: deaths
     and fights won per rank over a day.
5. **A density that finds its own level** (A4): a mode `FIT` that raises the countryside's share while the heap after a collection stays under a
   target and lowers it above, so a server holds what its machine allows with nothing to tune; once the rest stands.
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

**Left** ([backlog.md](social/backlog.md); 1 to 4 are [group-options-plan.md](social/plans/group-options-plan.md), each small):
1. **The starting level**: a new character, and the bots made for its group, start at level 1 in Poeta or Ishalgen or at the level 10 Ascension in
   the capital (`gameserver.playerbot.start.level`): the Ascension's quests marked done by the engine, the class kept, bind point and starter gear of
   that level. To check first: what upstream does with a character created above level 1.
2. **Bots roll the dice**: today every roll waits for its timeout. *Need* only for a piece its class wears that beats what it wears (the comparison its
   dressing uses), *pass* on the rest, answered within a second or two: a bot in a player's group is there to help, not to compete.
3. **Orders and marks**, per bot or for the whole group, kept until the next: *stay* (holds its place, fights what reaches it), *guard* (holds a place,
   fights within about 20 m and comes back), *passive* (follows, fights nothing); two marks on a monster, *kill first* and *keep asleep* (nobody
   damages it while a bot that can sleep, stun or fear keeps it so), and the client's own numbered marks read as an order of kill.
4. **One's own characters as bots**: `//bot alt <name>`, open to players: a character of the same account and race, offline, within 10 levels (an
   option), comes into the group as a bot with its own gear, skills, stigmas and level, and keeps what it earns. One body at a time (logging it in
   while it plays is refused, or dismisses it first); it leaves with its owner, follows through instances and teleports, is saved every 5 minutes;
   its bag is never touched by the bot. The launcher lists the account's characters and gets a button to bring one in.
5. **Formations** around the player (behind, line, wedge, circle), held while walking and broken by a fight. No plan yet.
6. **The player's hand on a companion's gear**: its bag shown, an item put on by the player, enchanting and extraction paid from the companion's kinah
   on the player's order. No plan yet.
7. **Cohorts**: a bot in an alliance acting as a member of its own group of six ([cohort-plan.md](social/plans/cohort-plan.md)).
8. **Composition, lot E1**: the Recruit Group Members tab and alliances; bots posting instance runs in the Find Group window, a ceiling per tab, and
   whispering a player whose post lacks their role.
9. **Bots group with each other** in the field (lot E2): pairs and trios hunting together, met in the countryside like players; a small group also
   survives what kills a lone bot. Today they never do; lots G1 and I3 need it.
10. **Level 50 bots** for the Theobomos instances and Dark Poeta, asked on 9 October; awaiting another test server's feedback.
11. **Doors, lifts and windstreams used by a bot itself**, only if the 13 s wait proves too long; then **the arenas**, instances of their own.
