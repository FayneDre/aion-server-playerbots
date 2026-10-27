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

One line per package and its main classes; the class javadoc is the detail. Brought up to date on 27 October 2026.

```text
com.aionemu.gameserver.playerbot/
  PlayerBotService, BotPopulator, BotCommands    Who is in the world, waking and sleeping, startup and save sweep; filling a map; one method per //bot command
  BotRecruiter, BotVisitors, BotScheduler        Bots answering Find Group offers; capital visitors; the shared thread lanes
  BotDiagnostics, BotInbox                       Debug on demand; the command inbox read by tools/bot-command.ps1

  lifecycle/    Headless creation, loading, entering and leaving the world (PlayerBot*Service, PlayerBotLoader), BotRoster (the pool, a query),
                BotPacing (levels per region), BotMapChange (a map change with no client), BotAppearance
  ai/           PlayerBotAI (extends AITemplate<Player>: the decision tick, fights, following), BotDay and TownRoutine (what a bot does with its
                day, in the field and in town), BotErrands, BotConvalescence, BotPosture, Occupation, LostTrail, HealthRecord
  combat/       BotSkillManager (the generic order), BotTargetSelector, BotTargetRegistry (one bot per mob), BotAttackManager, BotLootManager,
                BotPotionManager, BotRestManager, BotServantAI
  combat/playbook/  ClassPlaybook + BotPlaybooks: templar, chanter, ranger, cleric, gladiator, sorcerer, consulted before the generic order
  positioning/  BotPosition, BotStations, CombatSpots, AggroZone, PositioningMode: where each class stands in a group fight
  movement/     BotMoveController (legs, detours, stuck detection, flight mode), BotFlight, BotGeoHelper, BotServantMoveController
  navmesh/      BotPathFinder (grid A*, long walks), NavmeshBuilder/NavmeshTool (offline generation), glide, ride and flight links,
                InstanceReach, ObeliskAudit, SearchSpace
  world/        BotDirector and BotPresence (who should be where), BotPlaces, BotLanding, StrandedPlaces, HarmfulGround, TownServices
  economy/      Gear (BotOutfitter, BotEquipManager, BotGearFit, BotGearSources, BotGearRefiner, BotStatWeights, BotStigmaFitter, BotPowerShards),
                selling (BotVendorManager), stores (BotStores, BotStoreStock, BotStoreGoods, BotStorePricing, BotManastones), BotTrades
  social/       Groups and instances (BotGroupManager, BotInstances, BotRole, BotMarks, BotSquads, RecruitPlan, LeaderGlides, BotTransports),
                requests (BotRequests), speech and chat (BotSpeech, BotChat, BotChatLines)
```

The admin command lives **outside** core sources, at `game-server/data/handlers/admincommands/Bot.java` (runtime-compiled scripts): zero core diff.

Future subsystems plug in as: auction house / player shops → `economy/`; legions and cohorts → `social/`; RvR → a coordinator above behavior
selection, beside `BotDirector`.

## Core files touched

| File | Change | Merge risk |
|---|---|---|
| `model/gameobjects/Creature.java` | `final` → `volatile` + `setAi()` | Minimal — these lines never change upstream |
| `model/gameobjects/Creature.java` | `moveController` → `volatile` + `setMoveController()` | Minimal — same reasoning as `setAi()` |
| `world/geo/GeoService.java` | two more overloads of `getClosestCollision`: one taking the ray's start point, one taking a map instead of a body | Additive; the first now delegates to the second. The bot module tests each stretch of a flight from its own start, and asks whether a place is under a roof before anybody stands there |
| `model/team/TemporaryPlayerTeam.java` | `getBrandedTarget(int)`, a four line reader of the brand map | Minimal — the map was `protected` with no reader, and only bots need to look at a brand without a client |
| `skillengine/effect/EffectTemplate.java` | One accessor: `getHitType()` | Minimal — a getter over an existing protected field, no behaviour |
| `model/gameobjects/player/Player.java` | `isOnline()` means "is present"; adds `setBot()`/`isBot()` | Low per line, wide in effect — see [built.md](../built.md) |
| `PacketSendUtility`, `World`, `MultiClientingService`, `PunishmentService`, `PvpService`, `ChangeLeaderEvent` | One null check each, on the connection instead of on `isOnline()` | Minimal — and three of them were already missing a guard |
| `services/findgroup/FindGroupService.java` | `getInstanceGroups()`, a read-only view of the Instance Groups offers | Additive, no existing line touched. The offers were private with no reader, and `BotRecruiter` answers them |
| `ShutdownHook.java` | One line: `PlayerBotService.onShutdown()` after the leave world tasks | Low — nothing else saves bots (see below) |
| `GameServer.java` | One line: `PlayerBotService.onStartUp()` beside the shutdown hook registration | Low — the symmetric counterpart, on a line that rarely moves |
| `configs/Config.java:36-41` | Add `PlayerBotConfig.class` to the `CONFIGS` array | Low but **recurring**: upstream appends to the same list. |

| `ShutdownRequestWatcher.java` (new) | A `shutdown.request` file beside the server stops it through the normal hook | None — a new file. `stop-server.ps1` uses it: a console CTRL+C lost the save one time in two |
| `dao/SchemaUpdater.java` (new) | Adds the bot module's tables and columns on start | None — a new file |
| `configs/main/PlayerBotConfig.java` (new) | The `gameserver.playerbot.*` properties | None — a new file |
| `model/gameobjects/player/ResponseRequester.java`, `RequestResponseHandler.java` | `requesterOf(messageId)`, `getRequester()` | Additive — a bot reads who asked instead of a question window |
| `controllers/attack/AttackUtil.java`, `skillengine/model/Effect.java` | A block, dodge, parry or resist is noted where it is decided | Low — the engine noted it only when writing the client's packet, which a bot has not |
| `skillengine/model/Skill.java` | The client-timing audit of a charge skill is skipped for a bot | Low — it accused every bot of cheating; the server's own timing still applies |
| `skillengine/model/SkillTemplate.java`, `SkillLearnTemplate.java` | `getCounterSkills()`, `isAdvancedStigma()` | Additive readers |
| `model/templates/item/ItemTemplate.java` | `getCName()`, `hasLevelRestrictions()` | Additive readers: the model name is the only place an armour piece's faction is stated |
| `services/StigmaService.java` | The two socket counts made public | Minimal |
| `dataholders/PetSkillData.java` | No longer throws for a pet that is not a toy pet | Minimal, a null check |
| `services/DebugService.java` | Bots left out of the half-hourly "player without connection" warning | Minimal |
| `network/chatserver/ChatServer.java`, `SM_CS_BOT_MESSAGE.java` (new) | A bot's line sent to the chat server | Additive; the chat server has its counterpart `CM_BOT_MESSAGE` ([chat-channels-plan.md](../social/chat-channels-plan.md)) |
Nothing else in the game server (checked against the merge base on 27 October 2026). `MultiClientingService` skips connectionless players, as in the row above. The chat server has its own small patch for bot lines.

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
2. **`isOnline()` doubles as a validity test.** It now means "is present" ([built.md](../built.md)); whatever needs the socket must test the socket. Audit each new call on any path a bot touches.
3. **Tick reentrancy.** `AbstractAI` has a `thinking` latch, but our scheduled tick and engine-fired events (`MOVE_ARRIVED`, `ATTACK`) run on different pool threads. Guard behavior dispatch per bot; never mutate `Player` state from the tick thread while a cast is in flight.
4. **A crash loses up to five minutes.** Bots are saved every 5 minutes and on a clean shutdown (`PlayerBotService.onShutdown`, which drains the registry through the normal leave path); nothing writes them in between.

Settled since this file was first written: `PvpService` no longer dereferences a bot's connection, and the shutdown hook saves every bot.
