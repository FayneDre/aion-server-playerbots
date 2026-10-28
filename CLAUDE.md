# Aion Server 4.8 — Playerbots Project

## Project context

This repository is a personal fork of [beyond-aion/aion-server](https://github.com/beyond-aion/aion-server) (branch `4.8`), a Java server emulator for the MMORPG *Aion: The Tower of Eternity*.

**Goal of this fork**: add a server-side bot system inspired by WoW's *Playerbots* mod (AzerothCore/TrinityCore) — bots that are real player characters driven by AI (not simple NPCs), able to join groups, fight with real class skills, use the auction house and shops, etc. The end goal is to populate the world and make it feel alive.

This is **not** a client automation project (no bot connecting as an external player) — the bot lives inside the game-server process and reuses its internal logic directly (see "Target architecture" below).

## Functional scope (in priority order)

1. **PvE** — content playable by/with bots.
2. **Outdoor open-world PvP** — non-instanced PvP.
3. **Auction house** — active bot buyers/sellers.
4. **Player shops** — bots using/running shops.
5. **Legions** — bots as functional legion members.
6. **RvR (Realm vs Realm)** — final/stretch goal, the hardest one (sieges, mass coordination). Not blocking if the rest works.

Don't over-engineer phases 1-5 to anticipate RvR needs — that's a separate effort, to be tackled once the rest is stable.

## Related project (outside this repo)

A game repack + launcher (installer, server/client launching, character backup) is planned as a **separate project**, in a different repository. Don't mix that work in here.

## Target architecture for the bot module

New dedicated package, isolated from the existing engine to minimize merge conflicts with upstream (`beyond-aion/aion-server`):

```text
game-server/src/com/aionemu/gameserver/playerbot/
```

### Existing anchor points to reuse (not duplicate)

- **`Player`** (`game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java`) extends `Creature` and only holds a **nullable** reference to the network connection (`clientConnection`). Nothing structurally prevents instantiating a headless `Player` (no socket) driven by AI.
- **`PlayerController`** (`game-server/src/com/aionemu/gameserver/controllers/PlayerController.java`) is the shared logic layer: network handlers (`CM_*`) and NPC AI call the same methods (e.g. `useSkill(...)`). This is the key reuse seam for driving a bot in pure Java code.
- **AI framework**: package `game-server/src/com/aionemu/gameserver/ai/`. `AITemplate<T extends Creature>` (no-op defaults over `AbstractAI`) is the base to extend — `data/handlers/ai/siege/SiegeWeaponAI.java` is the non-Npc precedent. **Caution: every `ai/handler/*` and `ai/manager/*` class is typed `NpcAI`, not `AbstractAI`, so none of them is reusable from a `Player`-typed AI** — their equivalents must be re-implemented (they are small; `ai/manager/SimpleAttackManager.java` is the reference for an attack loop).
- **Reference pattern for an AI-controlled character**: `Servant.java` (`game-server/src/com/aionemu/gameserver/model/gameobjects/Servant.java`) — not a `Player`, but shows the existing follow/attack pattern for summoned servants.
- **Programmatic skill casting**: `SkillEngine.java` (`game-server/src/com/aionemu/gameserver/skillengine/SkillEngine.java`) — `getSkillFor(...)` then `skill.useSkill()`, no packet parsing involved. Already used this way by NPC AI.
- **Groups**: `game-server/src/com/aionemu/gameserver/model/team/group/` (`PlayerGroup`, `PlayerGroupService`, loot rules in `team/common/legacy/LootGroupRules.java`) — operates on `Player` objects, not sockets; a headless `Player` in a group should follow the same rules as a real member.

### Navigation: built on a generated navmesh

`geoEngine` (`game-server/src/com/aionemu/gameserver/geoEngine/`) only provides collision/raycasting (line of sight, ground height) — no path planning.
The bot module therefore generates its own grid navmesh offline (`tools/navmesh.ps1`) and plans on it with A\* (`BotPathFinder`): 26 open-world maps
and 45 dungeons, with glide, ride and flight links. Raycast steering is only the fallback where no mesh answers. See
[docs/navigation/how-it-works.md](docs/navigation/how-it-works.md) and the rest of that folder.

### Headless `Player` lifecycle: built

No upstream code path constructs a `Player` without an `AionConnection`; `lifecycle/` does, and `Player.isOnline()` now means "is present", so
whatever needs the socket tests the socket. See [docs/architecture/how-it-works.md](docs/architecture/how-it-works.md) and [docs/traps/](docs/traps/) before touching either.

### Design docs

[docs/README.md](docs/README.md) is the index: every document by topic, with each plan's status. The ones to open first:

- [docs/roadmap.md](docs/roadmap.md) — **start here**: what is left, by priority, with what blocks each one.
- [docs/built.md](docs/built.md) — what already works, one paragraph a topic, each pointing to its `how-it-works.md`.
- [docs/bot-commands.md](docs/bot-commands.md) — every `//bot` command.
- [docs/patch-notes/current.md](docs/patch-notes/current.md) — every test patch, in French for the testers, by git tag; `tag-patch.ps1` refuses a patch without its entry.
- [docs/traps/](docs/traps/) — what the engine, the world data and the skill engine do to a bot that no documentation warns about.

Folders: `architecture/`, `traps/`, `population/`, `combat/`, `classes/` (with `glossary/`, the French skill names `skill-lookup.ps1 -Name`
reads), `gear/`, `navigation/`, `flight/`, `capitals/`, `social/`, `feedback/` (one file per tester report), `patch-notes/`. In a topic folder,
`how-it-works.md` says what is true today and is kept up to date with the code; `plans/` holds open plans, `history/` the done ones, unchanged. A
plan opens with a Status line in fixed words (`not started`, `in progress: <where>`, `built, unverified: <what>`, `done`) and moves to `history/`
the day it is done. A new document goes in the folder of its topic and gets a line in the index; `check-docs.ps1` checks all of it.

## Dev environment

- **Java**: version 25 (`maven.compiler.release` in root `pom.xml`) — already installed (Microsoft OpenJDK build).
- **Maven**: manually installed in `C:\Tools\apache-maven-3.9.16` (no official winget package) and added to the user PATH. No `mvnw` wrapper in the repo.
- **VSCode**: Java/Maven extensions installed (`vscjava.vscode-java-pack`, `redhat.java`, `vscjava.vscode-maven`, etc.). Non-standard Maven layout: `sourceDirectory` = `src` (not `src/main/java`), configured in each module's `pom.xml`.
- **Git**: installed via winget. Remotes:
  - `origin` → personal fork (`https://github.com/FayneDre/aion-server-playerbots.git`)
  - `upstream` → `https://github.com/beyond-aion/aion-server.git`
  - Branch `4.8`: tracks `origin/4.8` (= upstream), stable base to merge periodically.
  - Branch `feature/playerbots`: bot development branch, created from `4.8`.

To pull upstream updates: `git fetch upstream`, then merge/rebase `upstream/4.8` into `4.8`, then merge `4.8` into `feature/playerbots`.

## Repo modules (reminder)

- `login-server` — account authentication.
- `chat-server` — chat channels.
- `game-server` — the game core (world, combat, NPCs, AI, etc.) — **this is where the bot work happens**.
- `commons` — code shared across modules.

## Commit conventions

- English, short and to the point (a single summary line, or a few short bullets for larger changes).
- No AI attribution/co-author lines.
- Never name a colleague or the user in code, comments, tests, docs, config or commit messages: write "the guide", "the design document". Run
  `tools/install-hooks.ps1` once per clone: its `commit-msg` and `pre-commit` hooks refuse a co-author trailer, a name from the local
  `.git/name-blocklist.txt` (kept out of the repository on purpose) and a doc comment stranded in the bot module. They exist because the same two rules were
  broken twice by habit.

## Documentation conventions

- Keep each doc file (CLAUDE.md, `.claude/*`, `docs/**`) under ~200 lines. `docs/README.md` indexes every doc by folder: a new doc gets its line there.
- Split into multiple topic-focused files instead of letting one grow indefinitely.
- Every `//bot` command must appear in [docs/bot-commands.md](docs/bot-commands.md). `tools/check-bot-docs.ps1` compares the handler's switch with
  the doc and names what is missing; `deploy.ps1` runs it and refuses to deploy while the two disagree, and an editor hook reports it sooner. The
  rule exists because the discipline failed: `//bot number` was added and the doc was not touched.

## Plan documents and tools

- A plan document opens with a **Status** line (not started, in progress with the milestone, or built and what is still unverified). A plan that has been
  built and is not kept up to date is worse than none: two of them said "not built" for a day after they were. `tools/check-docs.ps1` now flags
  a plan whose classes changed since the last patch while the plan did not.
- **Measure before and after**, with the versioned tools: `tools/measure.sh <from> <to> [day]` writes the same report for any window of the log
  (into the server's `log/bot-measures`, plus the day plans given up as journeys), and `NavmeshTool replay <file>` plans those journeys again
  offline. `tools/compare-measures.ps1 -Before a,b -After c` puts reports side by side per half hour and marks what left the band
  of the reports before. A measurement tool written in a scratch folder is lost with the session: write it here.
- `tools/apply-sql.ps1` runs the statements of `game-server/sql/update.sql` the database has not had yet, once each (a table `schema_applied_sql` holds
  their hashes), after a `mysqldump` and with the game server stopped; `deploy.ps1` runs it after the build, so nobody runs upstream's SQL by hand.
  A database that has them already is told once with `-MarkApplied -Apply`.
- `tools/sync-install.ps1` brings the installed servers in line with the repository. `deploy.ps1` runs its `-LibsOnly` part on every deploy (the
  libraries the poms ask for, the login server's jar, commons for the login and chat servers; nothing is resolved while the poms are unchanged). After an
  upstream merge, `-From <the commit or patch tag the install holds>` adds the data and configuration files the install still holds as the repository
  had them (and lists the ones edited here, for a hand merge).
- `tools/check-server-log.ps1` reads the game server log for errors, a log that has gone silent (a console clicked into pauses the whole server) and the
  number of bots in the world; `start-server.ps1` runs it after startup. `tools/skill-lookup.ps1` finds skills by name (French ones through the glossary), group, id or class, with their group,
  cooldown, reach and tier. Use them before reading the data by hand.
- A class guide becomes rules by skill **group** (stable across levels), never by id, and every group a playbook names is checked against the data by a
  test (`TemplarPlaybookDataTest`), which also puts every skill template in the game through the playbook's rules.
- Prefer the editor tool for a single change; reserve scripted multi-line replacements for bulk work, and check the result: backslashes, `\n` and line
  endings are easy to get wrong through a shell.

## Java code conventions

- Follow standard Java best practices (encapsulation, naming, exception handling, thread-safety where relevant).
- Match the existing codebase's idioms and structure rather than introducing a different style.
