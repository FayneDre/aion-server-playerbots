# What the server needs before the launcher installs it from nothing

Status: in progress: milestone 1 built and checked on 8 November on the server's side (the launcher's side of it left: below); milestone 2 built
and measured the same day; milestone 3 built and measured that evening (the day's plans given up left to compare); milestone 4 built, its measurement at a small heap left. Written on 8 November, once release milestones 5 and 6 were validated: the launcher can update an installed server, and
the next launcher work is installing one on a machine that has none. Seven milestones, in this order; each says how it is checked. The class
playbooks left (spirit master, aethertech, bard, gunner, assassin) are not part of it: they come later.

## Why

Updating a server and installing one are not the same job. An update finds a database, a configuration and a heap that a person already set up;
an install finds an empty MySQL, a machine of unknown size and nobody who knows what `mygs.properties` is. What the bundle lacks today:

- **No schema.** The bundle carries `game-server/sql/update.sql` and `tools/apply-sql.ps1`, not `aion_gs.sql` nor `aion_ls.sql`: an empty
  database cannot be made from it.
- **A fresh database would be updated twice.** `aion_gs.sql` already holds what `update.sql` adds (`pvp_exp`, `chin_height`): run on a database
  made from it, `apply-sql.ps1` would replay `ALTER TABLE ... ADD COLUMN` and fail.
- **The login server's updates are run by nobody.** `apply-sql.ps1` reads the game server's `database.url` only; `login-server/sql/update.sql`
  (the `toll` column, `account_rewards`) has always been run by hand.
- **The navmesh holds memory nobody uses.** A cache per map, 32 MB each, 26 maps open: tiles of maps no bot walks stay loaded (until milestone 3).
- **The defaults are one machine's.** `density.mode = AUTO` at 30 %, a 2560 MB heap and no guard: about 800 awake bots fill that heap, and nothing
  stops the director before it does ([living-world-plan.md](../../population/plans/living-world-plan.md), lot A).
- **Memory is not the only limit.** Route planning ran out first at full density on sixteen cores; a player's machine has fewer.
- **Nobody has ever started this server on an empty database.** The bot pool, the broker's 6000 lots and the first navmesh loads have only ever
  met a database that had them already.

## Milestones

### 1. The schemas in the bundle, a fresh database made in one step

- `package-release.ps1` adds `game-server/sql/aion_gs.sql` and `login-server/sql/aion_ls.sql`, plus `login-server/sql/update.sql`, at their
  repository paths; the manifest lists them like every other file.
- `apply-sql.ps1` learns the login server: `-Server login` reads `login-server/config/network/database.properties` and `config/myls.properties` on top
  of it, with its own ledger table in that database. `deploy.ps1` runs both.
- `apply-sql.ps1 -Create`: creates the database if missing, runs the schema, then records every statement of `update.sql` as applied, in one
  transaction where MySQL allows it, refusing a database that has tables already. The launcher runs exactly that, so the rules stay here.
- A rule for the schemas: after an upstream merge, `tools/check-schema.ps1 -Server game|login|chat` makes the database from nothing into a
  scratch MySQL (`<schema>_check`, dropped after) and compares it with the live one, table by table, column by column; exit code 1 lists what
  differs.

The database is a portable MySQL the launcher ships and starts itself (decided on 8 November): the zip archive of the same major version as
the one the server is developed on, in its own folder, on its own port, with a user and password the launcher sets. Nothing is installed on the
system, nothing collides with a MySQL a person has already, and the assistant has nothing to ask about the database. MySQL rather than MariaDB
because the server's driver (`mysql-connector-j`), upstream and every daily test run against MySQL.

**Built and checked on 8 November**, on the official MySQL 26.7.0 zip (Oracle's signature checked), started on port 3307 beside the live
server: the three databases made in 1.9, 0.6 and 0.4 s (63, 9 and 2 tables, `aion_gs.sql` holding `playerbot_characters` already);
`check-schema.ps1` finds them the live databases' tables column for column, and finds a column added to a copy of the schema; a second
`-Create` refuses; a broken schema is taken back to nothing, the database dropped when the run made it, its tables when it was there empty; a
login database without a ledger whose two statements are in it records them `already` (`DROP TABLE` of a missing table, error 1051, now
counts as done). The live `aion_ls` had no ledger: marked once (`-Server login -MarkApplied -Apply`). The chat server has a schema and no
`update.sql`, so three schemas ship and two `update.sql`.

Left, on the launcher's side: its update runs the login server's `update.sql` too (with `-Server login`, a tester's login database marked
once when its ledger is absent, as the game one is); the install from nothing runs `-Create` for the three servers. And the portable MySQL
itself: the zip is 346 MB and 1.3 GB unpacked, 809 MB of it debug symbols (`.pdb`) and much of the rest tools a server never runs (`mysqld.exe` is
52 MB, the two clients 15), to be trimmed to what `mysqld` and the two clients need before it ships.

### 2. A1: the heap guard on the director

As written in [living-world-plan.md](../../population/plans/living-world-plan.md): the old generation after a collection
(`MemoryPoolMXBean.getCollectionUsage`), a review that wakes nobody above the upper threshold and again below the lower one, a log line and a
line in `//bot pool`. The thresholds come from a measurement: `FIXED` at 100 % on the live server, the heap after each collection logged until
awake bots level off. Checked: the server stays up with `FIXED` at 100 %, and `//bot pool` shows the guard holding. **Built on 8 November**
(`BotLoadGuard`, `gameserver.playerbot.guard.*`, 85/80 % and 20/5 s until measured; six tests of the decision); under G1 the heap figure is 0 below
about 45 % of the heap, measured on a probe, and read as unknown. **Measured on 8 November**: `FIXED` 100 % held 1895 bots in 75 to 80 %
of the 2560 MB heap with the route queue empty, so the guard never held at 85/80; lowered to 72/67 it held, kept and released as meant, and
two holes the measurement found are closed (the heap read again at each wake's turn and at the startup's roster, which brought 1907 bots back
unseen and held the login off for 8 minutes). For milestone 5: a startup that puts back 1205 bots took 322 s to reach the login server.

A second signal beside the heap: route planning. On 5 November, at `FIXED` 100 % on sixteen cores, it gave out before the memory did (25 000
searches queued, ten-minute waits); on a player's four or six cores it gives out much sooner. The guard also holds the wakes while the planning
queue stays above a threshold, measured the same way, and says which of the two holds it.

### 3. A2: one budget for every navmesh

The largest share of the heap is not the bots: on 5 November navmesh tile heights (`short[]`) were 603 MB of a 1771 MB live set, where 200 more
awake bots cost 30 MB. Each open map has its own tile cache today (`Navmesh.TILE_BUDGET_BYTES`, 32 MB, a clock evicting past it), so 26 open maps
may hold 832 MB whether bots walk them or not. One budget for all of them, as a share of the heap, with the same clock run across maps and a tile
unused for some minutes given back, puts that memory where bots are. Measured with `tools/heap-snapshot.ps1` before and after, and the routes
with `measure.sh` (a tile read again from its file costs planning time: the route queue must not grow). It comes before the defaults because
every megabyte it frees is bots the smallest machine can hold.

Checked: the live set after a collection, the plans given up and the route waits of a day, before and after.

**Built on 8 November** (`TileBudget`, `gameserver.playerbot.navmesh.tile_budget`, 12 % of the heap: 307 MB of 2560): every map's tiles under
one budget, the maps' clocks turned in turn when the total passes it, down to 95 % of it. A tile unused for minutes is not given back on a clock
of its own: under one budget the tiles nobody touched are the ones given back first, wherever they are, and that is what puts the memory where
bots are. Measured the same evening at `AUTO`, nobody online, about 910 awake, nine minutes after a start each time: the live set after a full
GC **1663 MB before, 1406 MB after** (tile heights, `short[]`, 581 then 407 MB); in steady play some 19 000 tiles read again every five
minutes, in 3.5 to 3.8 s of reading (about 0.2 ms a tile, about 1 % of one core); route waits at 0 s. The day's plans given up, before and
after, are still to compare (`measure.sh`).

### 4. A3: the density in the launcher, and defaults for an unknown machine

- Server: the density mode, percent and the guard's state on the admin channel the launcher already reads (`version` gives `API_LEVEL`; the
  level is raised, the launcher's minimum with it). The mode and percent are written to `playerbot.properties` by the launcher, read at start.
- Launcher: the mode as a list, the percent as a slider bounded by what milestones 2 and 3 measured, the guard shown on the Bots page.
- Defaults: the heap chosen from the machine's memory (a table in the launcher: 8 GB, 16 GB, more), the density at a percent that stays under
  the guard on the smallest of them. Measured on the live server with that heap and with the cores of a modest machine
  (`-XX:ActiveProcessorCount=4`), not reasoned.

Checked: the slider moves the world on a restart; at the smallest heap the guard is never reached in an hour of `AUTO`.

Built on 8 November. The server answers `population` (API level 2): the density in force, the last review's awake, wanted, asleep and short
totals, the guard (`open`, `off`, or `held` and its causes), the heap after a collection and the route wait. The launcher offers the mode and the
percent on its Bots page (written to `mygs.properties` and applied by `reload-config` at the next review, so no restart), shows the population
above the bot list, and asks for it only from a server of level 2: an older one shows the page as before rather than refusing to start, so the
launcher's minimum stays at 1. The heap is chosen at start from the machine's memory, only ever below start.bat's `-Xmx`: under 6 GB 1024 MB,
under 10 GB 1536 MB, under 14 GB 2048 MB, start.bat's (2560 MB) above. Those bands are the reasoning the measurement checks.

Measured the same evening on the live server, nobody online, at the worst case: `-Xmx1536m -XX:ActiveProcessorCount=4`, `FIXED` 100 %
(2002 wanted). The startup restored 441 of 914 and left the rest to the guard; the world then settled at 650 to 690 awake, the guard holding at
85 to 92 % and letting go at 77 to 79 %, the check at each wake's turn letting 1 to 12 through a review of about 1230 asked for. No error, no
`OutOfMemoryError`; the tile budget (184 MB) full, 15 000 to 50 000 tiles read per 5 minutes in 2 to 6.6 s. Four processors gave one route
thread: queues of up to 98 bots behind searches that give up, the longest wait 15 s against the guard's 20. With at least two route threads
(the same run again, 22 minutes): the queue at most 41, the longest wait 6 s, the plateau the same (686 to 689), searches given up at the same
rate (158, about 7 a minute, which are goals out of reach, not waits). So a 1536 MB heap holds about 680 bots: `AUTO` stays far under it, and
`FIXED` on such a machine is about 30 %. Left: the hour of `AUTO` with a player in game at that heap.

### 5. A first start on an empty database, measured

A second install beside the live one (other folder, other ports, the portable MySQL and the databases of milestone 1), started from the bundle with the defaults of
milestone 4, the live server stopped first (two servers do not fit this machine's memory). Written down, in a measurement under
`log/bot-measures` and a section here:

- each phase's time: the data loaded, the bot pool created (how many characters, how long), the broker filled, the first bots awake, the
  login server registered;
- the heap after collections and the planning queue through that hour, at four cores, against the live server's;
- every error and warning of the log (`check-server-log.ps1`);
- every setting a person had to type for it to start: the database user and password, the addresses, the admin account. That list is what the
  launcher's first-launch assistant must ask for or set itself, and nothing else.

Checked: a client logs in to the fresh server, creates a character and sees bots around it.

### 6. Tests for the combat half of `PlayerBotAI`

`PlayerBotAI` is 1924 lines and its combat half has no test ([known-limits.md](../../known-limits.md), Tests). Before the upstream merge
touches the skill engine under it: the decisions it takes (which target, when to close in or back off, when to drink, when to give up a fight)
moved into pure functions of a situation record, as `TownRoutine` and `LostTrail` are, with a suite each. No behaviour changes: the measures of
the day before and after are compared with `compare-measures.ps1`. Checked: the suites pass, the measure stays in its band.

### 7. The upstream merge, then the first stable pair

- `git fetch upstream`, `beyond-aion` fast-forwarded, merged into `develop` the way
  [upstream-merge-plan.md](../history/upstream-merge-plan.md) did it: the trial first, the tests of milestone 6, `sync-install.ps1 -From` for
  the install, `update.sql` checked against the schemas by the rule of milestone 1.
- Then release milestone 7 ([release-process-plan.md](release-process-plan.md)): a server pre-release and a launcher pre-release validated in
  game together, `develop` merged into `main` in both repositories, each tagged with its stable version. The launcher's install from nothing
  installs that pair, never a beta.

Checked: a tester's launcher updates to the stable pair and plays on it.

## After this plan

The launcher's side, in its own roadmap: the first-launch assistant (Java, memory, disk; the portable MySQL unpacked, started and its
user made; the settings of milestone 5 asked once), the first download that resumes and verifies, and the install from nothing running `apply-sql.ps1 -Create`.
