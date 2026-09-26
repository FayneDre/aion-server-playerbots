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

### Main obstacle: no pathfinding

`geoEngine` (`game-server/src/com/aionemu/gameserver/geoEngine/`) only provides collision/raycasting (line of sight, ground height) — no path planning. The only existing "paths" are fixed scripted waypoints (`spawnengine/WalkerGroup.java`) or fixed flight paths.

Bots currently use **reactive steering** built on those raycasts: it handles open ground, slopes and single obstacles, and gives up on anything that needs real planning. A navmesh plus A\* is the next stage, and still gates the outdoor PvP and RvR phases. See [docs/navigation-prototype.md](docs/navigation-prototype.md).

### Design docs

- [docs/roadmap.md](docs/roadmap.md) — **start here**: what works, what is half written, what is left, and the traps that already cost hours.
- [docs/playerbot-architecture.md](docs/playerbot-architecture.md) — module layout, the two core patches (`Creature.setAi()` / `setMoveController()`) and their justification, reuse map, risks.
- [docs/combat-prototype.md](docs/combat-prototype.md) — milestone-by-milestone plan for the first combat prototype, with verification steps and known traps.
- [docs/navigation-prototype.md](docs/navigation-prototype.md) — how bots move: geo primitives, corridor probing, detours, the anti-stuck bounds and their limits.
- [docs/navmesh-plan.md](docs/navmesh-plan.md) — design for real path planning, to replace reactive steering.

### Secondary obstacle: headless `Player` lifecycle

No existing code path constructs a `Player` without an `AionConnection`. The world-entry flow (login, character selection, client-side group UI sync, `SM_*` packet sends) will need to be audited for implicit non-null connection assumptions.

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

## Documentation conventions

- Keep each doc file (CLAUDE.md, `.claude/*`, `docs/*`) under ~200 lines.
- Split into multiple topic-focused files instead of letting one grow indefinitely.

## Java code conventions

- Follow standard Java best practices (encapsulation, naming, exception handling, thread-safety where relevant).
- Match the existing codebase's idioms and structure rather than introducing a different style.
