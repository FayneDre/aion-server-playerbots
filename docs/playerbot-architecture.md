# Playerbot module architecture

## Why this design

Bots are **real `Player` objects** driven by server-side AI, not NPCs wearing player models and not external clients speaking the network protocol. Three findings from the engine drive every decision below:

1. **Player loading is already connection-free.** `services/player/PlayerService.java:102` `getPlayer(int objId, Account)` and `services/AccountService.java:75` `loadAccount(int)` never touch `AionConnection`. The `players` table has an `account_id` column with no foreign key (accounts live in the login-server DB), so bot characters need no login-server account.
2. **Packet sending is already null-safe.** `utils/PacketSendUtility.java:75` guards with `if (player.isOnline())`, and `Player.isOnline()` is `getClientConnection() != null`. Every broadcast helper funnels through it, so a connectionless bot no-ops for itself while **nearby real players still receive its attack and cast packets**.
3. **The existing AI managers cannot be reused.** `ai/manager/*` (`AttackManager`, `SkillAttackManager`, `SimpleAttackManager`, `FollowManager`, `WalkManager`) and every `ai/handler/*` are typed `NpcAI`, not `AbstractAI`. A `Player`-typed AI must re-implement their equivalents. They are small; `SimpleAttackManager` is the reference.

## AI attachment: the first core patch

`model/gameobjects/Creature.java:43` declares `private final AbstractAI<? extends Creature> ai;`, assigned in the constructor from `objectTemplate.getAiName()`. `PlayerCommonData extends CreatureTemplate` does not override `getAiName()`, so players get `AIEngine.DummyAI`. There is no `setAi()`.

The patch:

```java
- private final AbstractAI<? extends Creature> ai;
+ private volatile AbstractAI<? extends Creature> ai;
+
+ public void setAi(AbstractAI<? extends Creature> ai) {
+     this.ai = Objects.requireNonNull(ai);
+ }
```

Justification: **the engine already defeats this `final` itself.** `data/handlers/admincommands/Ai.java:84-91` does `getDeclaredField("ai")` + `setAccessible(true)` + `Field.set` on a live NPC (despawn → swap → respawn). The keyword is incidental, not an invariant.

`volatile` rather than a plain field: dropping `final` loses the Java Memory Model's final-field freeze that guarantees every thread sees a non-null `ai` without synchronization. `getAi()` is on the hot path for every NPC, and `Ai.java` swaps AIs *after* spawn, so "we only swap before spawn" is not a safe assumption. On x86-64/ARM64 a volatile read compiles to a plain load — no measurable cost.

Attach directly with `player.setAi(new PlayerBotAI(player))`. Do **not** route through `AIEngine.registerAI()` / `@AIName`: that registry is scanned from the script directory `data/handlers/ai` only, and registering a core-package AI there is a layering inversion for no benefit.

### What the patch buys for free

The engine fires AI events on any `Creature`, so an attached bot AI receives, with no scheduler code of our own:

| Event | Fired from | Use for the bot |
|---|---|---|
| `ATTACK` | `controllers/CreatureController.java:176` | React when attacked |
| `MOVE_ARRIVED` / `MOVE_VALIDATE` | `taskmanager/tasks/MoveTaskManager.java:49/52` | 200 ms heartbeat (once registered) |
| `ATTACK_COMPLETE` | `skillengine/model/Skill.java:586` | Chain the next skill after a cast |

Events dispatched via `forEachNpc(...)` (`CREATURE_SEE`, `CREATURE_MOVED`, `CREATURE_NEEDS_SUPPORT`) are Npc-filtered and will **not** reach bots.

Neither will **`DIED`, `SPAWNED` and `DESPAWNED`**: they are fired from `NpcController` only, because real players carry a dummy AI nobody ever needed to notify. `PlayerController.onDie` says nothing to the AI. Bots therefore fire their own spawn/despawn events from the lifecycle services, and notice death from the decision tick (`PlayerBotAI.handleDeath`) rather than patching a core file upstream changes often.

## Package layout

```text
com.aionemu.gameserver.playerbot/
  PlayerBotService            Singleton facade: spawn/despawn/despawnAll/onShutdown — the only public entry point
  PlayerBot                   Per-bot handle: Player ref + PlayerBotAI + metadata (NOT a Player subclass)
  PlayerBotRegistry           ConcurrentHashMap<Integer, PlayerBot>, lookup by objId/name, iteration on shutdown

  lifecycle/
    PlayerBotCreationService    Headless CM_CREATE_CHARACTER: new characters on reserved bot accounts
    PlayerBotLoader             accountId -> AccountService.loadAccount + PlayerService.getPlayer
    PlayerBotEnterWorldService  Headless subset of PlayerEnterWorldService (state only, zero packets)
    PlayerBotLeaveWorldService  Headless subset of PlayerLeaveWorldService (cleanup + controller.delete())

  ai/
    PlayerBotAI                 extends AITemplate<Player> — handleAttack/handleMoveArrived/handleDied/think()
    PlayerBotTickTask           Self-rescheduling ThreadPoolManager task, fallback heartbeat

  combat/
    BotTargetSelector           Target picking via Player.getAggroList() / known list
    BotAttackManager            Player-typed re-implementation of SimpleAttackManager
    BotSkillManager             Cooldown/MP/range-aware skill selection and casting

  movement/
    BotMoveController           extends PlayerMoveController — legs, detours, stuck detection
    BotGeoHelper                Corridor probing and sidestep search over GeoService
```

The admin command lives **outside** core sources, at `game-server/data/handlers/admincommands/Bot.java` (runtime-compiled scripts): zero core diff.

Future subsystems plug in as: auction house / shops → `economy/` plus new behaviors; legions → `social/`; RvR → a `squad/` coordinator above behavior selection; navigation → replace the reactive steering behind the same interface (see [navigation-prototype.md](navigation-prototype.md)).

## Core files touched

| File | Change | Merge risk |
|---|---|---|
| `model/gameobjects/Creature.java` | `final` → `volatile` + `setAi()` | Minimal — these lines never change upstream |
| `model/gameobjects/Creature.java` | `moveController` → `volatile` + `setMoveController()` | Minimal — same reasoning as `setAi()` |
| `ShutdownHook.java` | One line: `PlayerBotService.despawnAll()` after the leave world tasks | Low — nothing else saves bots (see below) |
| `configs/Config.java:36-41` | Add `PlayerBotConfig.class` to the `CONFIGS` array | Low but **recurring**: upstream appends to the same list. Defer while the prototype uses constants. |

Nothing else. `services/player/MultiClientingService.java:24` dereferences the connection's IP/MAC and would NPE — it needs no patch, because `PlayerBotEnterWorldService` simply never calls it.

## Bot characters

Bots live on their own accounts, ids from **900000** up, one per bot. Accounts belong to the login server and `players.account_id` has no foreign key, so those ids never need to exist there. This keeps bots off the player's real accounts, where they would fill the character selection screen, and makes them a single `WHERE account_id >= 900000` away in the database — which is also what makes `//bot delete` safe to restrict.

Creation clones an existing character's race, gender and appearance, because a default `PlayerAppearance` is all zeroes including a height of 0, which the client cannot render. Two traps found the hard way: the creation insert has **no `exp` column**, so the level must be written separately, and levels above 9 are gated on daeva status, which only the ascension quest grants.

## Reuse map

**Reuse as-is:**

- `PlayerService.getPlayer` / `storePlayer`, `AccountService.loadAccount`
- `World.createPosition` / `storeObject` / `spawn`, `CreatureController.delete()`
- `PlayerController.onEnterWorld()` (:253)
- `PlayerController.attackTarget(Creature, int, boolean)` (:399) — the exact entry point `CM_ATTACK` uses
- `SkillEngine.getSkillFor(...)` then `Skill.useNoAnimationSkill()` (:289) — skips the client-hit-time anti-cheat checks a bot cannot satisfy
- `PacketSendUtility.*` (already null-safe), `GeoService` (line of sight), `ThreadPoolManager.schedule`
- `Player.getAggroList()`, `AITemplate` no-op defaults

**Must re-implement** (core version is `NpcAI`-typed): the attack loop (model: `ai/manager/SimpleAttackManager.java`), skill selection, target arbitration, and later movement — `PlayableMoveController` has no `moveToPoint` (server-driven player movement exists only for fear/confuse).

## Known risks

1. **Enter/leave-world duplication drifts from upstream.** `PlayerBotEnterWorldService` and `PlayerBotLeaveWorldService` mirror an ordered call list from core services. Keep them as a bare ordered list with a javadoc header naming the source file and the commit reviewed against; re-diff both on every upstream merge.
2. **`isOnline()` doubles as a validity test.** It means "has a connection", but other systems use it as "is a real, valid player" and may silently exclude bots. Audit each call on any path a bot touches.
3. **`PvpService` dereferences `getClientConnection().getIP()`** and will NPE the first time a bot kills or is killed by a real player. The prototype stays PvE; add a null guard before open-world PvP.
4. **Tick reentrancy.** `AbstractAI` has a `thinking` latch, but our scheduled tick and engine-fired events (`MOVE_ARRIVED`, `ATTACK`) run on different pool threads. Guard behavior dispatch per bot; never mutate `Player` state from the tick thread while a cast is in flight.
5. **Nothing saves a bot but the despawn path.** `PeriodicSaveService` only stores legion warehouses, and the shutdown path saves players through their connection, which a bot does not have. `PlayerBotLeaveWorldService` therefore calls `PlayerService.storePlayer`, and `ShutdownHook` despawns every bot so that path runs. A crash still loses everything a bot did since it spawned.
6. **Leaked bots on crash or shutdown.** World objects and object IDs persist if `storePlayer` never runs. Register a shutdown hook that drains the registry through the normal leave path.

## Not yet designed

Groups, economy, legions, RvR mass coordination.

Navigation has a first reactive implementation, documented in [navigation-prototype.md](navigation-prototype.md); a navmesh and real path planning remain to be designed.
