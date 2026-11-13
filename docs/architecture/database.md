# The database: how MySQL runs, what it costs, how it is saved

**Status**: built and applied to the development machine on 13 November: the light settings are in its option file (188 MB resident measured, from
~530), the binary logs are deleted (4.9 GB), `start-server.ps1` and `deploy.ps1 -Restart` tried (clean log, 904 bots, MySQL at 255 MB resident and
16 connections at most under that load). The development MySQL cannot yet be stopped cleanly: its game account lacks `SHUTDOWN`
(`grant-shutdown.ps1`, once, by the person). The portable MySQL of an install is built (`package-mysql.ps1`, `setup-mysql.ps1`) and tried end to end on a
throwaway install, then **with the whole stack on it** (13 November, `server-fresh`, empty database, `ActiveProcessorCount=4`): registered with the
login server after 313 s (107 s on the live database), no ERROR line, 2002 bot characters and 6034 broker lots made, 318 bots in the world and
rising, 16 connections at most, MySQL 261 MB resident, the game server 2.7 GB. **A player then logged in** (the account made by the login server on its
own, a character made from the client, 927 bots around, bots seen moving) and logged out: the character was saved, with its new position and one
item more, no error in the log; `stop-server.ps1 -WithMysql` then dumped the databases and stopped MySQL cleanly. Not yet tried: the launcher.
**The development install was moved to its own portable MySQL on 13 November** (port 3307, `D:\Jeux\Aion\server\mysql`): 63, 9 and 2 tables, every
table's `CHECKSUM TABLE` equal to the old database's, 2024 characters, servers up with no ERROR line and 904 bots. The old MySQL (3306) is
untouched and idle until the game has been tried; see "Moving an install" below.

## What runs

One MySQL 26.7 on the machine, three schemas: `aion_gs` (game, 88 MB of data, 63 tables), `aion_ls` (login), `aion_cs` (chat). Each server keeps a
pool of 5 connections to `localhost:3306` under the account its `my*.properties` names. MySQL is **native on Windows**: Docker Desktop and a WSL
distro were weighed and left (below).

## Starting and stopping

- `tools/start-mysql.ps1` starts `mysqld.exe` with the installer's option file, hidden, with no Windows service and no administrator rights, and waits
  for the port. A MySQL that already answers is left alone. `start-server.ps1` and `deploy.ps1` call it first, so nobody starts the database by hand.
- `tools/stop-mysql.ps1` stops it with `mysqladmin shutdown` under the game account, and never kills it. The game server must be stopped first.
  The account needs the global privilege, given once by a database administrator: `tools/grant-shutdown.ps1`, run by the person in a terminal, asks
  for the administrator's password (a secure string, kept for that one statement, never written) and checks the result.
  `stop-server.ps1 -WithMysql` does both in order.
- The installer's service (`MySQL267`) stays disabled: it needs administrator rights at every start.
- **Trap**: `mysqld` has no CTRL+C handler on Windows. `send-ctrl-c.ps1` (the game server's way out) ends it at once, with no "Shutdown complete" in its
  log: InnoDB survives it (a dump taken after was identical to the one before) but it is a crash, not a stop. Only `mysqladmin shutdown` is clean.

## Memory

Measured on 13 November with the game database loaded and every table scanned, on a throwaway instance (other port and data folder):

| Settings | Resident | Allocated |
|---|---|---|
| the installer's defaults (binary log on, `performance_schema`, X protocol, 151 connections, `table_open_cache=4000`, 8 buffer pool instances) | ~530 MB | ~730 MB |
| [tools/mysql/my-light.ini](../../tools/mysql/my-light.ini) | ~210 MB (188 MB on the real instance, after the change) | ~410 MB (385) |

The file says what each line gives up. The 128 MB buffer pool is kept: it holds the whole 88 MB database.

## Backups

`tools/backup-db.ps1` writes one `.sql` per schema into `<server>/backups/auto-<date>`, each with its own server's account, and keeps the newest
ten of those folders (other folders are never touched). `stop-server.ps1` runs it after the game server has saved and exited, so every stop and every
`deploy.ps1 -Restart` leaves a dump of about 22 MB. `apply-sql.ps1` still takes its own dump before a schema change.

The binary log is **off** in the light settings: it grew by 5 GB in about three weeks, nothing replays it, and a dump is what the project restores from.
`--single-transaction` is not used: it asks for `RELOAD`, which the game account does not have.

## Why not Docker or WSL

The database is 88 MB: the cost is the layer around it. Docker Desktop adds its own VM and services (1.5 GB or more). A WSL distro adds a VM that
the system keeps (an estimate of 150-300 MB beyond a native MySQL, not measured: no distro is installed), and a data folder on `/mnt/d` is slow and
unsafe for InnoDB. Native with the light settings is the smallest of them, at ~210 MB measured. Data can still live in the server folder by pointing
`datadir` at it (NTFS, no translation layer), which has not been needed.

## MySQL or MariaDB, for the public install

Measured on 13 November: MariaDB 11.8.9 (LTS), official Windows zip, same light settings, same database, same load as the MySQL measure above.

| | MySQL 26.7 | MariaDB 11.8.9 |
|---|---|---|
| Zip / unpacked | 346 MB / 1.3 GB (809 MB of it `.pdb` symbols) | 93 MB / 295 MB (71 MB of it one `.pdb`) |
| Resident, database loaded and scanned | 188-255 MB (live instance), 210 MB (trial) | 171 MB (trial) |
| Threads | ~45-50 | 15 |
| The repository's install schema | loads, 63 tables with the ledger | loads in 0.5 s, 62 tables (no ledger: it is `apply-sql.ps1`'s) |
| A dump taken from the other engine | not tried | fails at its first statement (`SET @@GLOBAL.GTID_PURGED`); with that line removed, all 63 tables and every row (2024 players, 152587 items) |
| The server's driver (`mysql-connector-j` 26.7.0): connect, queries, prepared statements, `ON DUPLICATE KEY`, `REPLACE`, `INSERT IGNORE`, transactions | the vendor's own pair | worked on the probe |
| Executable | signed by the vendor (checked on 8 November) | not signed (the zip's checksum comes from the same host) |

What the probe does not cover: the game server itself on MariaDB (not run), and the driver's future: Oracle's Connector/J has already stopped
connecting to MariaDB once (11.0, fixed in 11.1 when MariaDB added `transaction_isolation`; MySQL bugs
[111697](https://bugs.mysql.com/bug.php?id=111697) and [112960](https://bugs.mysql.com/bug.php?id=112960)) and does not support it. The other
way out is MariaDB's own driver, which changes the poms the upstream merges touch.

**Choice: MySQL**, the portable zip of the plan of 8 November, trimmed. After trimming (`mysqld` and the clients, no symbols) the size gap mostly
goes away, 20-60 MB of RAM is small against a 2.5 GB heap, and the pair of driver and server that upstream and every test here run stays the one
that ships. MariaDB is a measured fallback if the download or the memory of a weak machine ever decides it; it needs the game server run on it first.

## The portable MySQL of an install (built on 13 November)

The plan of 8 November ([fresh-install-readiness-plan.md](plans/fresh-install-readiness-plan.md)) has an install carry its own MySQL in
`<install>\mysql`. Three scripts make and run it; each was tried end to end on a throwaway install (other folder, port 3307), the development
machine's database and port 3306 untouched.

| Script | What it does |
|---|---|
| `package-mysql.ps1 -Source <official zip> -OutDir <folder> [-ZipOut <zip>]` | Refuses anything not signed by Oracle, keeps `mysqld`, the three clients and the DLLs they import (read from the programs' import tables), ICU data, the English messages and the licence, adds a NOTICE (GPL v2, where the source is) and `runtime.json` (version, SHA-256 of the source zip). **330 MB zip, 1.3 GB unpacked, down to 29 MB zip, 89 MB unpacked, 5 DLLs.** The zip is written entry by entry with `/`: `Compress-Archive` of PowerShell 5.1 writes `\`, which a Node extractor may refuse. |
| `setup-mysql.ps1 -ServerRoot <install> [-Port 3307]` | Writes `my.ini` (`my-portable.ini` + `my-light.ini`), has mysqld make the data folder, starts it, makes the accounts, writes each server's `database.url`, user and password into its override file (`mygs.properties`, `myls.properties`, `mycs.properties`; the old file is kept as `.bak`), then runs `apply-sql.ps1 -Create` for the three databases. 63, 9 and 2 tables, the game server entered at the login server. Leaves MySQL running. |
| `start-mysql.ps1`, `stop-mysql.ps1`, `backup-db.ps1`, `grant-shutdown.ps1` | Use `<install>\mysql` when it exists (its programs, its `my.ini`, the port of the game server's `database.url`), the installer's MySQL otherwise. |
| `check-portable-mysql.ps1` | The whole of the above, tried on a throwaway install in the temp directory (port 3317, the repository's own configuration and schemas, nothing of this machine read or written): 24 PASS or FAIL lines, exit code 1 on a failure, about two minutes. Run it after a change to these scripts or the option files, after an upstream merge that touches the schemas, and before the pinned version moves. |

Decisions, taken on 13 November:

- **Port 3307** by default, so it never meets a MySQL on 3306; `-Port` for another. Refused when something already answers on it.
- **Local only**: `bind-address=127.0.0.1`; the database is never offered to a network.
- **Passwords**: 24 random letters and digits (no character a properties file or a statement would read differently), one per account, made at
  setup. The servers' is in their override files, which a deploy never touches; root's is in `<install>\mysql\root.cnf`, readable by the person who
  set up. `aion` has all rights on the three databases and `SHUTDOWN`, so `mysqladmin shutdown` is how it stops (a clean stop, "Shutdown complete").
- **Never near existing data**: setup refuses a data folder that is not empty, an existing `my.ini`, a port that answers, and `apply-sql.ps1 -Create`
  refuses a database with tables. The development install has no `mysql` folder, so it cannot be set up by mistake.
- **The version is pinned**, never updated on its own (an update touches the data): [portable-mysql.json](../../tools/mysql/portable-mysql.json) holds
  MySQL 26.7.0, its URL and the zip's SHA-256 (same bytes on every download from `cdn.mysql.com` so far); the launcher can read the same file.
- **A path with a letter outside ASCII is refused**: the option file is read in the system's code page.

Measured on the throwaway install: 206 MB resident with the three databases made; the server's driver connects under TLS and runs its upserts; the
cycle start, dump, stop, start from cold, stop (5 s) worked, and a second start left a running MySQL alone.

## Moving an install to its portable MySQL (done on 13 November)

`tools/migrate-to-portable-mysql.ps1 -ServerRoot <install> -Zip <official zip>` moves an install whose servers run on another MySQL. The old MySQL
is only read; going back is copying each `config\my*.properties.bak` over its file (and deleting `myls.properties`). Servers stopped first (the script
refuses otherwise), then: unpack, dump with `--hex-blob`, `CHECKSUM TABLE` of every table, `setup-mysql.ps1`, restore, `CHECKSUM TABLE` again, and
exit 1 naming any table that differs. `-Resume` goes on from the dumps of an earlier run.

- **Trap: `mysqldump` writes a `FLOAT` with six digits.** A position `1081.015380859375` came back as `1081.02` (up to 0.005 units): `players.x/y/z` and
  `playerbot_characters.home_x/y/z`, the only two tables that differed. It is harmless in play but it is not the same data, and **every restore of
  `backup-db.ps1`'s dumps does it**. The migration copies the exact values from the old database (17 digits, by primary key) and compares again;
  a restore from a dump has no such source, so expect that rounding there.
- `CHECKSUM TABLE` is the proof, not the row count: the counts were equal while two checksums were not. The ledger `aion_cs.schema_applied_sql`
  exists only on the new side (`apply-sql.ps1` makes it); the comparison ignores a table the old side never had.
- `mysql -e "SOURCE file"` does not run a file with this client (the server gets the word `SOURCE`): the script feeds the file as bytes on standard input.

## For the launcher (a separate repository)

Not done here. What the launcher does with the above, in its first-launch assistant: download the 29 MB zip and check its SHA-256 against the
manifest, unpack it into `<install>\mysql`, ask for a port when 3307 answers, call `setup-mysql.ps1`, then start and stop MySQL with
`start-mysql.ps1` / `stop-mysql.ps1` (or its own program mode, which needs the same `mysqladmin shutdown`). It keeps its own backups (its backup
service); `backup-db.ps1` is for this repository's scripts. Dumps of a MySQL restore into a MySQL: take them with `--set-gtid-purged=OFF` if one
ever has to cross engines. The first start of the game server on this database, with a player logging in, is milestone 5 of the plan, to repeat.
