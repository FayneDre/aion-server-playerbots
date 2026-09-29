# What is left to do

State of the project and what comes next, written to be picked up cold. For how things work, see [playerbot-architecture.md](playerbot-architecture.md), [population.md](population.md), [combat-prototype.md](combat-prototype.md), [navigation-prototype.md](navigation-prototype.md) and [navmesh-plan.md](navmesh-plan.md).

## Where it stands

Bots are real `Player` objects with no connection, driven by `PlayerBotAI`. They load from the database, spawn visible to real clients, fight with class skills, chase, loot, rest, die and resurrect, work a 60 m camp, and plan routes over a generated navmesh. Several run at once without interfering.

Verified in game. Run `//bot` for the command list.

## Done: the vendor run

Wired into `PlayerBotAI.botTick`, right after the health check and before roaming, so a bot heals before travelling and never abandons a fight to go shopping. `//bot sell <name>` forces a trip for testing without waiting for a bag to fill. Verified in game: a bot walks to the nearest shop, sells, returns to its anchor.

**Arriving is not finding.** The shop spots come from spawn data — where an npc was placed, not where it stands. A bot that reached a spot with nothing on it re-issued a move to the point it was already standing on, arrived instantly, found nothing again, and did that for ever. Two changes end it: the bot walks to a shop keeper it can actually see rather than to a recorded coordinate, and a spot it reaches with no keeper in sight is dropped for ten minutes so the next trip goes elsewhere.

**Gear nobody here will ever wear is sold, whatever its quality.** Selling went by quality alone, so a green breastplate looted by a priest was neither worn nor sold and sat in the bag for good — and since a full bag is what sends a bot shopping, one such piece bought it an endless round of trips that sold a handful of greys and changed nothing. Two tests decide, both of them permanent: the wrong class, race or gender, and a mastery this class is never taught anywhere in its skill tree — plate for a priest, a bow for a templar. What the bot cannot wear because of its **level** is kept: it grows into that. "Does not have the mastery" is not an answer either, since masteries are taught as late as level fifty; "will never be taught it" is.

The trigger needed two conditions, not one: `hasFullBag(bot)` alone loops forever on a bag full of gear, quest items or anything rare, since nothing there is ever sold. `BotVendorManager.hasJunk(bot)` is required too.

**Selling does not go through `TradeService.performSellToShop`.** It gates on `PlayerRestrictions.canTrade`, which used to reject anything not `isOnline()`, so every sale silently failed; `BotVendorManager.sellJunk` redoes the small amount of business logic itself (price, sell limit, repurchase list, kinah). Since `isOnline()` was fixed at the root (below), the engine's own path would now work — the duplicate is kept only because it is tested, and is the first thing to delete when that area is next touched.

## Done: looking after itself

**A bot wears the best of what it owns.** Without this it fights for hours in the gear it spawned with, looting better pieces and walking past them in its own bag. Whether it is *allowed* to wear something is never asked: `Equipment.equipItem` already weighs the abyss rank, the mastery skills, the class, the level, the race, the gender and the room left in the bag, so trying is the question — the same bargain the skill code makes, and for the same reason: a second copy of the engine's rules would drift from it.

A weapon is only ever weighed against a weapon and armour against armour. Slots overlap in ways that make a free-for-all dangerous — a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon would not win it back. Anything finer than that is too strict: the first attempt insisted on the same item group, which is the *material*, and it blocked every real upgrade, since levelling gear crosses from leather to cloth and back with whatever drops.

**Worthwhile gear is bound to the bot before it is worn**, and this is what kept three attempts at equipping wearing nothing at all — `equipItem` does not refuse a soul bound piece, it asks about it and returns null. See [engine-traps.md](engine-traps.md); it is the clearest example of the whole family.

**A piece that scores higher can still be unwearable for good** — a mace a priest has no mastery for, a piece for the other race — and nothing short of trying says so. After a fruitless look the bot leaves its bag alone for a minute, or it would stop and sheathe on every tick of its life for one such item.

**Dressing is committed to, not waited for.** Reading a piece and binding one each take five seconds with the weapon away, standing still, undone by a single step or blow. A bot that farms is in its weapon stance almost always and spends its idle moments walking, so the quiet window never came on its own. The bot now stops, sheathes and holds its tick until the piece is on. Measured: three pieces worn per bot before, four and climbing after.

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

**Economy.** Selling is the first step. Then the broker (`BrokerService`), then player shops. (`PvpService` no longer throws when a bot kills a real player: that dereference was guarded along with the rest of the `isOnline()` work.)

**Bot population.** A map populates itself on first startup, from one configuration line, with its own navmesh generated if missing and its database schema brought up to date if it lacks anything. Bots live on reserved accounts from id 900000, keep a home, a level drawn from where they live and a face of their own. See [population.md](population.md).

**Saving.** Spawned bots are written every 5 minutes, and on a clean shutdown. Nothing else writes them: the engine's `PeriodicSaveService` covers legion warehouses only, and real players are saved when they log out, a door a bot never uses. This matters more than it used to, now that bots gain levels and skills unattended over hours.

## What a bot is for: residents and adventurers

The fork was built to populate a world and produced five machines for killing things. Measured on the spawn data, **Poeta is a region of levels one to eight** (median 4, ninth decile 8) — and its five inhabitants reached thirteen in a day, farming grey mobs in a valley meant for beginners. Left to progress, every bot drifts upwards and the low regions empty. That is mechanical, not bad luck.

So a bot is one of two things, and it is written down per name in `server_variables`:

- A **resident** is part of a place. It gains no experience (`setNoExp`), so its region keeps inhabitants who belong to it. This is what populating a world means: a cast, not a set of careers.
- An **adventurer** is a character. It levels, it is grouped with, and one day it will travel. There are meant to be few of them.

`//bot kind <name> [resident|adventurer]` reads or sets it. The flag is applied as a bot enters the world, and to a spawned bot on the spot.

### A resident has a day, not a task

Farming was the whole of a bot's life, which is why a populated map read as a hunting ground. A resident now draws an **occupation** with a duration and draws again when it runs out:

- **Farming** — what every bot did all the time.
- **Loitering** — standing about in a settlement. Doing nothing is a thing people do, and it is most of what makes a village look inhabited.
- **Wandering** — walking to another settlement, ending on arrival rather than waiting out the clock.

The choice leans on a **temperament fixed to the bot's own id**, so one that likes the fields keeps going back to them and one that likes company is usually found in a village. Without that a population averages out: every bot does a third of everything and none of them has a character. Every bot keeps a taste for all three, because a villager who never once leaves is as mechanical as one who never stops.

Adventurers have no occupation. They hunt, which is how they level.

**A level belongs to a region, not to a camp.** Poeta reads as Akarios 3 and its camps at 5, 6 and 7, but a character of eight walks all of it and one of thirty has no business anywhere in it. So `BotPlaces.levelOf` gives a whole map its band and `suitsLevel` says who belongs on it; nothing restricts where a resident goes once it lives there. Filtering each camp separately was the first attempt and it was wrong — it would have kept a level six villager out of its own valley's village.

**A resident has a home, and it is not simply the nearest place.** Loitering used to send a bot to whichever settlement it happened to be beside, which means a village nobody spawned next to is a village nobody ever visits: Akarios stayed empty while three camps were crowded. A home fixed to the bot's own id spreads a population over the places a map actually has, weighted by how many townsfolk stand in each — so most residents live where the world itself put most of its people.

**Settlements are read from the world, not authored.** `BotPlaces` clusters the spawns of peaceful npcs (tribe `GENERAL`) and keeps the gatherings of three or more. Poeta yields Akarios village and its three camps out of 43 townsfolk against 986 hostiles. A settlement becomes the bot's anchor while it is there, so everything that already works off the anchor keeps it in place without a second set of rules.

### Filling a region with people who belong to it

`//bot populate <count> <templateName>` used to clone one template character that many times: one class, one level, one set of training gear repeated. Nothing about it was a population.

What a region needs is now taken from the region. The **level** comes from the creatures living there (`BotPlaces.levelOf`), drawn a couple either side so a crowd is not uniform. The **class** is spread over those a character of that level could actually be — below ten only the four starting classes exist, and a cleric of level six is a character the game itself cannot make. The **gear** is fitted per bot by `BotOutfitter`, which reads the item data for what that class may wear at that level, keeps nothing above superior quality, and picks at random among the best few so a village is not in uniform. Each piece is created already identified and already bound, since otherwise a new bot spends its first ten minutes reading and binding instead of living.

They are **residents from birth**, so they stay the level their home is worth.

Only the restrictions the engine enforces anyway are applied when choosing gear — class, race, required level, quality. A second copy of its rules would drift from it, which is the mistake this project keeps paying for elsewhere.

## Known limits, in order of how much they will bite

1. **Obstacles under a metre are invisible to the engine's own probes**, so wherever the navmesh does not answer — an ungenerated map, the last few metres to a creature — a bot can still wedge itself on one. Along a planned route it no longer applies: those legs are walked on the mesh, which sees them.
2. **Walkable ground comes in islands.** A route between two of them does not exist. Check with `NavmeshTool <mapId> components` before suspecting the search.
3. **Only maps with a generated file are planned on.** Run `tools/navmesh.ps1 <mapId>`; the rest fall back to reactive steering. Only Poeta (210010000) is generated.
4. **Crossing maps is not a navigation problem.** It needs teleporters and flight paths, like a player.
5. **A crash loses at most 5 minutes of what bots did.** That is the periodic save interval; nothing writes them between two sweeps.
6. **Bots never flee.** They heal, drink and shield themselves, and otherwise sit down to regenerate, but nothing makes them run from a fight they are losing.
7. **Bots cannot leave the map they are on.** No teleporter, no flight path, so a group that changes zone leaves its bots behind.

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

## Traps

Moved to [engine-traps.md](engine-traps.md), which is where to look first when a bot does nothing and says nothing about why.
