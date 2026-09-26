# What is left to do

State of the project and what comes next, written to be picked up cold. For how things work, see [playerbot-architecture.md](playerbot-architecture.md), [combat-prototype.md](combat-prototype.md), [navigation-prototype.md](navigation-prototype.md) and [navmesh-plan.md](navmesh-plan.md).

## Where it stands

Bots are real `Player` objects with no connection, driven by `PlayerBotAI`. They load from the database, spawn visible to real clients, fight with class skills, chase, loot, rest, die and resurrect, work a 60 m camp, and plan routes over a generated navmesh. Several run at once without interfering.

Verified in game. Run `//bot` for the command list.

## Next: the vendor run

**Half written.** `playerbot/economy/BotVendorManager` exists and compiles, but **nothing calls it yet**. It can already:

- find where shops are (`findVendor`), from the spawn data rather than from what the bot can see, because a shop is in town and a bot farms in the fields;
- recognise a shop it is standing next to (`findVendorNearby`);
- sell ordinary, unequipped, non-quest items (`sellJunk`), through the same `TradeService.performSellToShop` the client's packet calls.

What remains is the behaviour, in `PlayerBotAI.botTick`. The decision order there matters and is already load bearing: an attacker comes first, then loot, then health. The vendor run belongs **after the health check and before roaming**, so a bot heals before travelling and does not abandon a fight to go shopping.

Roughly:

1. `BotVendorManager.hasFullBag(bot)` and no vendor trip in progress → remember `findVendor(bot)`, or give up if the map has none.
2. Further than `TRADE_RANGE` → `moveToPoint` at it. Long trips are planned in the background already; give up if `moveController.isBlocked()`.
3. Within range → `findVendorNearby`, `sellJunk`, forget the trip. The bot then returns to its anchor by itself.

Traps to expect: the bot must **stand up** before travelling (`standUp()`, see the animation delays), the shop may be on another island (`NavmeshTool <mapId> components` says so), and `//bot sell <name>` would make this testable without waiting for a bag to fill.

## Then

**Skill rotation (M7).** `BotSkillManager` casts the highest id usable skill, a decent proxy for "strongest available" and nothing more. No chains, no conditional skills, no opener, no mana management. Worth doing once bots fight constantly, which they now do.

**Groups.** `model/team/group/` works on `Player` objects, so a headless bot should join like anyone else. Group loot rules already treat bots correctly, and `BotTargetSelector` already spares a team mate's target. This is the gateway to instanced PvE.

**Economy.** Selling is the first step. Then the broker (`BrokerService`), then player shops. `PvpService` dereferences `getClientConnection().getIP()` and will throw the first time a bot kills a real player, so guard it before open world PvP.

**Bot population.** `//bot populate` creates them on reserved accounts from id 900000. Nothing spawns them automatically at server start, and nothing spreads them over several camps.

## Known limits, in order of how much they will bite

1. **Obstacles under a metre are invisible to the engine's own probes.** The navmesh sees them; the reactive fallback never will. A bot walking without a plan can still wedge itself.
2. **Walkable ground comes in islands.** A route between two of them does not exist. Check with `NavmeshTool <mapId> components` before suspecting the search.
3. **Only maps with a generated file are planned on.** Run `tools/navmesh.ps1 <mapId>`; the rest fall back to reactive steering. Only Poeta (210010000) is generated.
4. **Crossing maps is not a navigation problem.** It needs teleporters and flight paths, like a player.
5. **A crash loses whatever bots did since they spawned.** They are saved on despawn and on shutdown only.
6. **Bots never flee, heal or use potions.** They only sit down to regenerate.

## Traps that cost hours, so they do not cost them twice

- **Whatever the client does by itself must be redone server side.** Drawing the weapon, ending spawn protection, standing up, sheathing. Each one was a bug that looked like something else.
- **The server decides faster than the client can show.** Two animations in the same instant leave a bot sliding or floating. The delays in `PlayerBotAI` are empirical and named for it.
- **Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.
- **Maven's incremental build can miss a change** and leave the IDE's error stubs in place, reporting success. Use `clean package` when a build result looks impossible.
- **Nothing but the despawn path saves a bot.** `PeriodicSaveService` only handles legion warehouses.
