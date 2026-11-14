# Plan: a server installed on a machine that has nothing

Status: in progress: the server's side is built and tried (the bundle, the navmesh assets, the portable MySQL, the Java runtime, both runtimes as release assets named in the manifest); the launcher's side (download, check, unpack, set up, start) and a first install on a clean machine are not started. Written on 13 November 2026, after [fresh-install-readiness-plan.md](fresh-install-readiness-plan.md) made the server installable and [database.md](../database.md) chose its database.

## Why

[release-process-plan.md](release-process-plan.md) stops at testers who already have a server built from git: it updates one. This plan is the other
job, which its last milestone named "the server release plan": a player on a Windows machine with the game client and nothing else gets a server
that runs, from the launcher alone. The server does not need to be built, nor its geometry made, nor Java or a database installed by the person.

## Decisions (13 November, the project's owner)

1. **The client is the player's.** The server never reads it: no path to it in any configuration, no `.pak` read in the code. The launcher finds the
   client and launches it; nothing of the server depends on it.
2. **The geometry is shipped, not generated.** The `.geo` files (151 upstream, 159 MB) are in the repository and in the bundle (`data/**`). The player
   has nothing to extract.
3. **The navmesh is shipped, not generated.** It is not a function of the geometry alone: the glide, ride and flight links, the places a tester walked
   and the golden routes are ours. It travels as release assets (`navmesh-<file>.gz`, the `.nav`, `.glide`, `.ride` and `.flight` of the install), as it
   already does for an update.
4. **Java is embedded**: a runtime made with `jlink` from the official Microsoft JDK, in `<install>\runtime`, so that nothing is asked of the machine
   (no installer, no administrator rights, no PATH). Below.
5. **The geometry and the navmesh go into public releases**, as upstream publishes its geometry. The footer of the release notes already says whose the
   game is. This is a decision of exposure, taken by the owner on the recommendation here; it is not legal advice.
6. **The weight is accepted**: about 2.1 GB at the first install, no map left out.

## What an install holds, and where each piece comes from

| Piece | Size | From | State |
|---|---|---|---|
| Game client | the player's | the player; the launcher finds it | launcher, exists |
| Server bundle: jars, configuration, static data, geometry, handlers, SQL | ~150 MB | server release asset (`package-release.ps1`) | built, used by updates |
| Navmesh: 26 open-world maps, 45 dungeons, glides, flight | ~1.8 GB | server release assets, one per map file | built; the first release uploads all 140 files |
| MySQL 26.7, trimmed | 29 MB | server release asset; pinned in `tools/mysql/portable-mysql.json` | built and tried ([database.md](../database.md)) |
| **Java 25.0.4.1 runtime** | **55 MB** | server release asset; pinned in `tools/java/portable-java.json` | **built and tried (below)** |
| Total download | **~2.1 GB**; ~3 GB on disk | | |

The order on the machine: the runtime, the bundle, the navmesh, MySQL unpacked, `setup-mysql.ps1` (accounts, three schemas, the servers' configuration
written), then `start-server.ps1`. The first start of the game server on an empty database took 313 s to register with the login server (107-196 s
on a database that has its bots): the launcher says so.

## The Java runtime (built on 13 November)

- **Why not the JDK as it is, nor a JRE**: the game server compiles its handlers when it starts (`javax.tools`, 1747 sources), so it needs the
  compiler. The full JDK is 375 MB; installing Java 25 by hand needs a JDK, often administrator rights, and fails the people this is for.
- **What it is**: `tools/package-java.ps1` unpacks the Microsoft JDK zip (210 MB; pinned, SHA-256 equal to the one Microsoft publishes), refuses
  anything not signed by Microsoft, and has `jlink` link the 22 modules of `tools/java/portable-java.json` (28 with their dependencies): what `jdeps`
  finds in the 56 libraries of the three servers, plus `jdk.compiler` (called by reflection, so `jdeps` cannot see it), `jdk.crypto.ec` (MySQL's
  TLS), `jdk.charsets` (the console's `CP850`) and the diagnostic modules. **75 MB unpacked, 55 MB zipped.** It writes `runtime.json`, a NOTICE (GPL
  version 2 with the Classpath Exception) and runs a small program on the result: the compiler answers, TLS, `CP850`, the HTTP server.
- **How the servers use it**: `start-server.ps1` puts `<install>\runtime\bin` first in the PATH when that folder exists, and its own check
  (Java 25 or later) then reads the runtime. Nothing in `start.bat` or upstream's files changed.
- **Tried**: the whole stack on it, on the development install: the three servers on `runtime\bin\java.exe`, 1028 bots, no ERROR line, 15
  connections to MySQL, the addons' page server up; and every one of the 1747 handler sources compiled with the runtime's `javac` against the
  install's libraries (26 s, 2022 classes, exit 0), which the server's compile cache had hidden. `tools/check-portable-java.ps1 -Zip <jdk zip>`
  repeats the build and that compile (6 checks, about a minute); run it before the pin moves.
- **Not covered**: a Java update. The pin is never moved on its own: each server release re-runs the check, and a security fix of Java is a release.

## The runtimes as release assets (built on 14 November)

- **What a release carries**: `package-release.ps1` attaches `runtime-java-<version>.zip` (55 MB) and `runtime-mysql-<version>.zip` (29 MB), made by
  `package-java.ps1` and `package-mysql.ps1` from the pinned official zips (downloaded into `%TEMP%\aion-java-cache` and `aion-mysql-cache`, checked
  against the pin before anything is made). The bundle also carries what the launcher runs on that MySQL: `tools\setup-mysql.ps1`,
  `start-mysql.ps1`, `stop-mysql.ps1`, `backup-db.ps1`, `mysql-common.ps1` and `tools\mysql\my-portable.ini`, `my-light.ini`. Each gets `-ServerRoot`.
- **What the launcher reads**: `manifest.json` has a `runtimes` object, one entry per runtime: `version`, `target` (the folder under the install:
  `runtime`, `mysql`), `sha256`, `size`, `release` and `asset` (the URL is `releases/download/<release>/<asset>`), and `recipe`.
- **Uploaded once, not per release**: a zip differs at every build (file times), so it is rebuilt only when its `recipe` moves: the SHA-256 of the git
  blobs of its pin file and of its script. Otherwise the entry is copied from the previous release's manifest, as the navmesh maps are. The first
  release with a `runtimes` section attaches both.
- **Tried**: a trial build (`-Trial`, 14 November): both runtimes made, signatures valid, the Java smoke program passed, navmesh carried from beta.4. Then
  a throwaway install from those files alone: the three hashes equal to the manifest, the runtimes unpacked to their `target`, the bundle over them,
  `<install>\tools\setup-mysql.ps1` (63, 9 and 2 tables made), `stop-mysql.ps1` clean. Not tried: an entry carried from a published release (it needs
  one published with the section), a game server started on that install (milestone 3).

## Left

1. ~~Host the two runtimes~~: built, above.
2. **The launcher** (its own repository, its own session): the first-launch assistant (disk, memory, a free port for MySQL), the downloads that
   resume and verify, the unpacking, `setup-mysql.ps1`, then starting and stopping the stack with the runtime first in the PATH.
3. **A first install on a clean machine**: Windows Sandbox (Windows 11 Pro has it) or a second account, from the launcher alone, to a player logging in.
   Milestone 5 of the install plan did the server's half on an empty database; the downloads, the unpacking and the SmartScreen warnings are not tried.
4. **The disk and the time**: ~3 GB written, and 2.1 GB downloaded at the first launch; a progress that says what it is doing.

Checked: a person with the client and nothing else installs the server from the launcher and plays on it, with bots around.
