# What is left to do

State of the project and what comes next, written to be picked up cold. For how things work, see [playerbot-architecture.md](playerbot-architecture.md), [combat-prototype.md](combat-prototype.md), [navigation-prototype.md](navigation-prototype.md) and [navmesh-plan.md](navmesh-plan.md).

## Where it stands

Bots are real `Player` objects with no connection, driven by `PlayerBotAI`. They load from the database, spawn visible to real clients, fight with class skills, chase, loot, rest, die and resurrect, work a 60 m camp, and plan routes over a generated navmesh. Several run at once without interfering.

Verified in game. Run `//bot` for the command list.

## Done: the vendor run

Wired into `PlayerBotAI.botTick`, right after the health check and before roaming, so a bot heals before travelling and never abandons a fight to go shopping. `//bot sell <name>` forces a trip for testing without waiting for a bag to fill. Verified in game: a bot walks to the nearest shop, sells, returns to its anchor.

The trigger needed two conditions, not one: `hasFullBag(bot)` alone loops forever on a bag full of gear, quest items or anything rare, since nothing there is ever sold. `BotVendorManager.hasJunk(bot)` is required too.

**Selling does not go through `TradeService.performSellToShop`.** It gates on `PlayerRestrictions.canTrade`, which rejects anything not `isOnline()` — true of every real connection, never true of a bot, so every sale silently failed. Rather than patch a restriction 79 call sites rely on, `BotVendorManager.sellJunk` redoes the small amount of business logic itself (price, sell limit, repurchase list, kinah), mirroring the template-less branch a general vendor takes.

## Then

**Skill rotation (M7), started.** Bots heal themselves and cast from a skill's own range rather than walking into melee first — see [combat-prototype.md](combat-prototype.md). Still missing: a priority order per class instead of the highest usable id, self buffs (which target the caster, the same blind spot healing had), and openers.

**Groups.** `model/team/group/` works on `Player` objects, so a headless bot should join like anyone else. Group loot rules already treat bots correctly, and `BotTargetSelector` already spares a team mate's target. This is the gateway to instanced PvE.

**Economy.** Selling is the first step. Then the broker (`BrokerService`), then player shops. `PvpService` dereferences `getClientConnection().getIP()` and will throw the first time a bot kills a real player, so guard it before open world PvP.

**Bot population.** `//bot populate` creates them on reserved accounts from id 900000. Nothing spawns them automatically at server start, and nothing spreads them over several camps.

## Known limits, in order of how much they will bite

1. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the navmesh does not answer — an ungenerated map, the last few metres to a creature — a bot can still wedge itself on one. Along a planned route it no longer applies: those legs are walked on the mesh, which sees them.
2. **Walkable ground comes in islands.** A route between two of them does not exist. Check with `NavmeshTool <mapId> components` before suspecting the search.
3. **Only maps with a generated file are planned on.** Run `tools/navmesh.ps1 <mapId>`; the rest fall back to reactive steering. Only Poeta (210010000) is generated.
4. **Crossing maps is not a navigation problem.** It needs teleporters and flight paths, like a player.
5. **A crash loses whatever bots did since they spawned.** They are saved on despawn and on shutdown only.
6. **Bots never flee and never use potions.** They heal themselves if their class can, and otherwise sit down to regenerate.

## Traps that cost hours, so they do not cost them twice

- **Whatever the client does by itself must be redone server side.** Drawing the weapon, ending spawn protection, standing up, sheathing. Each one was a bug that looked like something else.
- **The server decides faster than the client can show.** Two animations in the same instant leave a bot sliding or floating. The delays in `PlayerBotAI` are empirical and named for it.
- **Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.
- **Maven's incremental build can miss a change** and leave the IDE's error stubs in place, reporting success. Use `clean package` when a build result looks impossible.
- **Nothing but the despawn path saves a bot.** `PeriodicSaveService` only handles legion warehouses.
- **Two systems describing the same world will disagree, and the disagreement will not announce itself.** The navmesh generator dropped every wall while the engine's raycasts kept them, and that one fact produced a day of symptoms that each looked like its own small bug. When bots misbehave near geometry, first ask whether the mesh and the engine agree — `NavmeshTool <mapId> path x1 y1 x2 y2` against what the bot actually does is the fastest way to find out.
- **A plan that is redone every tick is not a plan.** Both ways round an obstacle cost about the same, so fresh plans alternate and the bot paces back and forth.
- **State kept in two places drifts.** A flag cleared by hand on every way a journey can end will miss one — it missed the abandon path, and the bot stood still for good. Derive it from the thing that already knows (`BotMoveController.isTravelling()`).
