# The database: how MySQL runs, what it costs, how it is saved

**Status**: built and applied to the development machine on 13 November: the light settings are in its option file (188 MB resident measured, from
~530), the binary logs are deleted (4.9 GB), and start and dump are tried. `stop-mysql.ps1` refuses cleanly for now: the game account lacks
`SHUTDOWN`, to be given once (below). `start-server.ps1` tried on 13 November with MySQL already up: clean log, 904 bots, MySQL at 255 MB
resident and 16 connections at most under that load. Not yet tried: a start from a stopped MySQL, and `deploy.ps1 -Restart` with these calls.

## What runs

One MySQL 26.7 on the machine, three schemas: `aion_gs` (game, 88 MB of data, 63 tables), `aion_ls` (login), `aion_cs` (chat). Each server keeps a
pool of 5 connections to `localhost:3306` under the account its `my*.properties` names. MySQL is **native on Windows**: Docker Desktop and a WSL
distro were weighed and left (below).

## Starting and stopping

- `tools/start-mysql.ps1` starts `mysqld.exe` with the installer's option file, hidden, with no Windows service and no administrator rights, and waits
  for the port. A MySQL that already answers is left alone. `start-server.ps1` and `deploy.ps1` call it first, so nobody starts the database by hand.
- `tools/stop-mysql.ps1` stops it with `mysqladmin shutdown` under the game account, and never kills it. The game server must be stopped first.
  The account needs the global privilege, given once by a database administrator: `GRANT SHUTDOWN ON *.* TO '<account>'@'localhost';`.
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

## For the launcher (a separate repository)

Not done here. The launcher starts the person's own MySQL (service, or "my own my.ini" mode) and does not install one; it also copes with MariaDB.
What carries over:

1. Ship `tools/mysql/my-light.ini` with the server bundle, in two variants: MySQL 8 and later as it is, MariaDB without `mysqlx` and `skip-log-bin`
   (MariaDB refuses to start on an option it does not know). The launcher already reads the version to pick `mysqldump` options.
2. Start MySQL through the launcher's program mode with that file, instead of asking for administrator rights each time.
3. Make sure the servers' account has `SHUTDOWN` (the launcher's program mode already needs it to stop MySQL).
4. The launcher keeps its own backups (its backup service); `backup-db.ps1` is for the development machine and the scripts.
