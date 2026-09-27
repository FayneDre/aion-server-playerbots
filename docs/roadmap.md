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

## Done: looking after itself

**A bot wears the best of what it owns.** Without this it fights for hours in the gear it spawned with, looting better pieces and walking past them in its own bag. Whether it is *allowed* to wear something is never asked: `Equipment.equipItem` already weighs the abyss rank, the mastery skills, the class, the level, the race, the gender and the room left in the bag, so trying is the question — the same bargain the skill code makes, and for the same reason: a second copy of the engine's rules would drift from it.

A weapon is only ever weighed against a weapon and armour against armour. Slots overlap in ways that make a free-for-all dangerous — a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon would not win it back. Anything finer than that is too strict: the first attempt insisted on the same item group, which is the *material*, and it blocked every real upgrade, since levelling gear crosses from leather to cloth and back with whatever drops.

**Gear is identified before it is worn.** A piece that rolls bonus stats drops unidentified (`tuneCount == -1`) and the engine refuses to equip it, which is why the first version of this quietly wore nothing at all. Identifying costs nothing but five seconds: `CM_TUNE` calls `identifyItem` with no scroll when the item is unknown, and the scroll a player buys is for re-rolling a piece that has already been read. Only pieces worth wearing are read, and the judgement holds either way, since identifying rolls bonus stats without touching the level or quality it was judged on. Those five seconds are watched by an observer that cancels them if their owner moves or fights, so the bot holds its tick until they are over.

Which piece is better is judged on its level first and its quality second. That is a proxy and says so: comparing what items actually grant would mean weighing attack against defence against a resistance, which has no answer that holds for every class.

**Bots drink.** A class with no heal of its own had nothing between being hurt and sitting down, and healers spent their last mana without ever reaching for the flask that would have given it back. Potions are found by what they do rather than by a list of item ids — an item whose use casts a skill that heals — and used through the same path the client packet takes, so the prison, the abnormal states, the cooldown and "this would do nothing at full health" are all still the engine's call. That path only became reachable once `isOnline()` stopped meaning "has a socket".

## Then

**Skill rotation (M7), started.** Bots heal, buff, cast from a skill's own range rather than walking into melee first, shield themselves when a fight turns, and open with a burst — they open with a class's own move — a leap, or a walk in under stealth — and they no longer pick the attacks that leap backwards. They follow their skill chains, do not re-apply a debuff already on the target, and chanters run their mantras. See [combat-skills.md](combat-skills.md). They lead with their strongest skill rather than their newest, which the id ordering had badly wrong. Still missing: an authored rotation per class — what exists is a set of tiers read from the data.

## Done: groups

Invite a bot from the client like anyone else and it joins: the decision tick answers the question window it can never see. `ResponseRequester.respond` returns false when nothing is pending, so asking is the whole test and no engine api had to be widened.

**Following is the anchor, not a new behaviour.** A grouped bot points its camp anchor at its leader every tick, and every rule it already had — roam the camp, come back when it is clear, break off a chase that leaves it — travels with the group for free. There is no second set of movement rules to keep in step with the first. A leader on another map is ignored rather than walked to: that is travel, not navigation, and bots cannot travel yet. `//bot come` has no effect on a grouped bot, since the leader sets the anchor a tick later.

**A grouped bot has no initiative of its own.** It fights what the group fights and otherwise stands by the leader: no picking targets, no roaming, no errands, no shop runs. A member that pulls what it likes is worse than no member, since it brings a second mob into a fight nobody chose and wanders off while it does. Defending itself is not initiative and is untouched — it still comes ahead of everything.

The leader's target is taken first so a group converges instead of each member helping whoever is nearest, and bots pile onto it through `forceClaim`. The level gap a bot applies to its own fights is deliberately not applied: the group chose this fight, and refusing to help because the mob is big is the one thing a member must not do.

**A group mate's attacker is assisted, not just the leader's selection.** Reading `getTarget()` alone left a bot standing by while its leader was being eaten, because a player whose selection is elsewhere — or who never clicked the mob that jumped them — is being attacked all the same. The mob's aggro list is asked instead: it remembers who it is fighting whatever anyone has selected. The leader's attacker comes first, then any member's.

**Healing and buffing reach the group.** The bot uses the same skills it heals and buffs itself with, aimed at a group mate; which of them can reach someone else is the engine's question, answered as each is cast, so no list of party-capable skills is kept. Allies are healed below 75%, a higher bar than the bot uses for itself, because a bot can sit down and regenerate what it does not heal while an ally it leaves hurt stays hurt. In a fight an ally's life comes before the bot's damage but after the bot's own survival.

**Selecting a target is not fighting it.** Players click mobs to read their level, and a group whose bots pull whatever the leader looks at is unusable, so the mob's own aggro list decides: it holds a grudge against whoever hit it, and equally against whoever it chose to attack, which means a leader under attack is assisted too.

Resting gives way to following: a bot that has fallen behind walks instead of sitting down, since resting takes it out of the fight for as long as it lasts.

**Each follower has its own place around the leader**, not the leader's own feet. Everyone aiming at the same point is what made bots pile onto their leader and jostle for it; a slot each fixes both at once, and removing that competition is most of what read as restlessness. The places are laid out in **world** directions rather than relative to where the leader faces — tied to a facing, the formation would swing round every time the leader turned on the spot and send everyone running for nothing. Members are ordered by object id so a bot keeps its place from tick to tick, and each sits a little off its exact share of the circle and a little nearer or further than the rest, by an amount fixed to its own id — an exact share of an exact circle is what a surveyor would lay out, and it reads that way. Since everything downstream already works off the anchor, moving the anchor to that place is all "stand with the group" needed.

Following also stops re-planning on every tick: a leader at a run drifts past the replan distance each second, and each new plan turns the bot a little differently. It now keeps its plan until its place has drifted more than `RETARGET_STEP`, the same guard chasing a target has always used.

**A follower stops when it arrives, rather than finishing its leg.** The leg in progress aims at wherever the leader stood when it was issued, so letting it run walks the bot past a leader who has turned round, and the next tick walks it back — the small pacing of a follower forever arriving where its leader no longer is. Stopping is safe because the ai reads whether the bot is travelling from the move controller rather than from a flag of its own, so nothing is left believing the journey is still on.

**Following re-aims every tick**, unlike returning to a camp. `returnToAnchor` waits for the current leg to end before looking again, which is right for an anchor that does not move and wrong for a leader who is walking: the bot heads for where the leader stood a leg ago, so the longer the journey the further behind it arrives. `moveToPoint` is built to be called repeatedly and keeps its plan unless the destination shifted more than a couple of metres, so the lag is bounded by that distance instead of by the length of a leg.

A bot that catches up from far away **appears rather than walks into view** for its leader, because a client is only told about characters in its known list; the group map dot comes from team updates, which have no such range. That is what a real player at the same distance would look like too.

A bot is passed over when a group looks for a new leader — leading means answering invitations and setting loot rules. That has a tail: a team keeps its leader field when that member leaves, and nothing replaces a leader a bot may not become, so the group of a player who has just walked out still names them. Bots followed that ghost until `leaderToFollow` started checking the leader is still a member. A group with no player left in it is then left outright, rather than leaving bots counting each other as team mates for targeting and loot for ever.

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
