# What is left to do

State of the project and what comes next, written to be picked up cold. For how things work, see [playerbot-architecture.md](playerbot-architecture.md), [combat-prototype.md](combat-prototype.md), [navigation-prototype.md](navigation-prototype.md) and [navmesh-plan.md](navmesh-plan.md).

## Where it stands

Bots are real `Player` objects with no connection, driven by `PlayerBotAI`. They load from the database, spawn visible to real clients, fight with class skills, chase, loot, rest, die and resurrect, work a 60 m camp, and plan routes over a generated navmesh. Several run at once without interfering.

Verified in game. Run `//bot` for the command list.

## Done: the vendor run

Wired into `PlayerBotAI.botTick`, right after the health check and before roaming, so a bot heals before travelling and never abandons a fight to go shopping. `//bot sell <name>` forces a trip for testing without waiting for a bag to fill. Verified in game: a bot walks to the nearest shop, sells, returns to its anchor.

**Arriving is not finding.** The shop spots come from spawn data — where an npc was placed, not where it stands. A bot that reached a spot with nothing on it re-issued a move to the point it was already standing on, arrived instantly, found nothing again, and did that for ever. Two changes end it: the bot walks to a shop keeper it can actually see rather than to a recorded coordinate, and a spot it reaches with no keeper in sight is dropped for ten minutes so the next trip goes elsewhere.

The trigger needed two conditions, not one: `hasFullBag(bot)` alone loops forever on a bag full of gear, quest items or anything rare, since nothing there is ever sold. `BotVendorManager.hasJunk(bot)` is required too.

**Selling does not go through `TradeService.performSellToShop`.** It gates on `PlayerRestrictions.canTrade`, which used to reject anything not `isOnline()`, so every sale silently failed; `BotVendorManager.sellJunk` redoes the small amount of business logic itself (price, sell limit, repurchase list, kinah). Since `isOnline()` was fixed at the root (below), the engine's own path would now work — the duplicate is kept only because it is tested, and is the first thing to delete when that area is next touched.

## Then

**Skill rotation (M7), started.** Bots heal, buff, cast from a skill's own range rather than walking into melee first, shield themselves when a fight turns, and open with a burst — they open with a class's own move — a leap, or a walk in under stealth — and they no longer pick the attacks that leap backwards. They follow their skill chains, do not re-apply a debuff already on the target, and chanters run their mantras. See [combat-skills.md](combat-skills.md). They lead with their strongest skill rather than their newest, which the id ordering had badly wrong. Still missing: an authored rotation per class — what exists is a set of tiers read from the data.

## Done: groups

Invite a bot from the client like anyone else and it joins: the decision tick answers the question window it can never see. `ResponseRequester.respond` returns false when nothing is pending, so asking is the whole test and no engine api had to be widened.

**Following is the anchor, not a new behaviour.** A grouped bot points its camp anchor at its leader every tick, and every rule it already had — roam the camp, come back when it is clear, break off a chase that leaves it — travels with the group for free. There is no second set of movement rules to keep in step with the first. A leader on another map is ignored rather than walked to: that is travel, not navigation, and bots cannot travel yet. `//bot come` has no effect on a grouped bot, since the leader sets the anchor a tick later.

**A grouped bot has no initiative of its own.** It fights what the group fights and otherwise stands by the leader: no picking targets, no roaming, no errands, no shop runs. A member that pulls what it likes is worse than no member, since it brings a second mob into a fight nobody chose and wanders off while it does. Defending itself is not initiative and is untouched — it still comes ahead of everything.

The leader's target is taken first so a group converges instead of each member helping whoever is nearest, and bots pile onto it through `forceClaim`. The level gap a bot applies to its own fights is deliberately not applied: the group chose this fight, and refusing to help because the mob is big is the one thing a member must not do.

**Selecting a target is not fighting it.** Players click mobs to read their level, and a group whose bots pull whatever the leader looks at is unusable, so the mob's own aggro list decides: it holds a grudge against whoever hit it, and equally against whoever it chose to attack, which means a leader under attack is assisted too.

Resting gives way to following: a bot that has fallen behind walks instead of sitting down, since resting takes it out of the fight for as long as it lasts.

**Following re-aims every tick**, unlike returning to a camp. `returnToAnchor` waits for the current leg to end before looking again, which is right for an anchor that does not move and wrong for a leader who is walking: the bot heads for where the leader stood a leg ago, so the longer the journey the further behind it arrives. `moveToPoint` is built to be called repeatedly and keeps its plan unless the destination shifted more than a couple of metres, so the lag is bounded by that distance instead of by the length of a leg.

A bot that catches up from far away **appears rather than walks into view** for its leader, because a client is only told about characters in its known list; the group map dot comes from team updates, which have no such range. That is what a real player at the same distance would look like too.

A bot is passed over when a group looks for a new leader — leading means answering invitations and setting loot rules.

**Left for later:** nothing makes a bot follow through a teleporter, and a group of bots alone has no one to decide for it.

**Economy.** Selling is the first step. Then the broker (`BrokerService`), then player shops. `PvpService` dereferences `getClientConnection().getIP()` and will throw the first time a bot kills a real player, so guard it before open world PvP.

**Bot population.** `//bot populate` creates them on reserved accounts from id 900000. `GameServer` then calls `PlayerBotService.onStartUp()`, which puts back whatever was in the world before, each bot at its own saved position — so a restart resumes rather than resets, and nothing has to place them by hand. The roster is a list of names in `server_variables`, written on every spawn and despawn because the case it exists for is a shutdown that never ran.

**Saving.** Spawned bots are written every 5 minutes, and on a clean shutdown. Nothing else writes them: the engine's `PeriodicSaveService` covers legion warehouses only, and real players are saved when they log out, a door a bot never uses. This matters more than it used to, now that bots gain levels and skills unattended over hours.

## Known limits, in order of how much they will bite

1. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the navmesh does not answer — an ungenerated map, the last few metres to a creature — a bot can still wedge itself on one. Along a planned route it no longer applies: those legs are walked on the mesh, which sees them.
2. **Walkable ground comes in islands.** A route between two of them does not exist. Check with `NavmeshTool <mapId> components` before suspecting the search.
3. **Only maps with a generated file are planned on.** Run `tools/navmesh.ps1 <mapId>`; the rest fall back to reactive steering. Only Poeta (210010000) is generated.
4. **Crossing maps is not a navigation problem.** It needs teleporters and flight paths, like a player.
5. **A crash loses at most 5 minutes of what bots did.** That is the periodic save interval; nothing writes them between two sweeps.
6. **Bots never flee and never use potions.** They heal themselves if their class can, and otherwise sit down to regenerate.

## `isOnline()` means "is present", not "has a socket"

`Player.isOnline()` was `getClientConnection() != null`, and 81 call sites used it to ask whether a character is **there** — can be given experience, counted in a group, offered a trade. Bots broke that equation: they are in the world, act in it, and have no connection by design.

Patching each site as it bit was going nowhere. A bot in a group would have earned no experience at all, not even for its own kills, because `PlayerTeamRewardStats.accept` skips members that are not online — and a grouped kill no longer takes the solo reward path. The same filter sits across loot distribution.

So the method now answers the question its callers ask: `bot || getClientConnection() != null`, with an explicit flag set as the bot enters the world. Real players are unaffected, by construction.

The counterpart is that **whatever needs the socket must test the socket**. Six places did not:

| Where | Was | Now |
|---|---|---|
| `PacketSendUtility.sendPacket` | `isOnline()` as the null guard — the one hot path, and it would have thrown on every packet sent to a bot | tests the connection |
| `MultiClientingService` | dereferenced the connection of every world player, bots included | skips connectionless players |
| `World` (out of bounds kick) | dereferenced with no guard | null checked |
| `PunishmentService` | dereferenced with no guard | null checked |
| `PvpService` (PL logging) | dereferenced with no guard | null checked |
| `ChangeLeaderEvent` | would have handed a group to a bot | bots are passed over: leading means answering invitations, which they cannot |

Three of those were already broken before any of this: they walk the world player list, which has contained bots all along. Legion and friend lists are untouched — they use a different `isOnline()`, on `PlayerCommonData`, fed by the database column, where a bot is correctly absent.

## Traps that cost hours, so they do not cost them twice

- **Whatever the client does by itself must be redone server side.** Drawing the weapon, ending spawn protection, standing up, sheathing. Each one was a bug that looked like something else.
- **The server decides faster than the client can show.** Two animations in the same instant leave a bot sliding or floating. The delays in `PlayerBotAI` are empirical and named for it.
- **Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.
- **Maven's incremental build can miss a change** and leave the IDE's error stubs in place, reporting success. Use `clean package` when a build result looks impossible.
- **Nothing but the despawn path saves a bot.** `PeriodicSaveService` only handles legion warehouses.
- **A method whose name asks one question while its body answers another will be used for the question in its name.** `isOnline()` cost a workaround in the vendor code and would have silently starved every grouped bot of experience, because callers reasonably read it as "is present". Fixed at the root; see above.
- **Two systems describing the same world will disagree, and the disagreement will not announce itself.** The navmesh generator dropped every wall while the engine's raycasts kept them, and that one fact produced a day of symptoms that each looked like its own small bug. When bots misbehave near geometry, first ask whether the mesh and the engine agree — `NavmeshTool <mapId> path x1 y1 x2 y2` against what the bot actually does is the fastest way to find out.
- **A plan that is redone every tick is not a plan.** Both ways round an obstacle cost about the same, so fresh plans alternate and the bot paces back and forth.
- **State kept in two places drifts.** A flag cleared by hand on every way a journey can end will miss one — it missed the abandon path, and the bot stood still for good. Derive it from the thing that already knows (`BotMoveController.isTravelling()`).
