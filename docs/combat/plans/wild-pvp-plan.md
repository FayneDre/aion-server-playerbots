# Plan: wild PvP, rifts and the arenas

Status: in progress: milestones 0, 1, 2 and 2b built, unverified in game (the property `gameserver.playerbot.pvp.enable` is false by default; the pure rules are
tested, everything that needs a world only compiles, no bot has met a player yet). Milestones 3 onwards not started.

## What the engine already does

- **Who is an enemy.** `Player.isEnemyFrom(Player)` is true for a duel partner, or when both stand in a PvP zone and the races differ (`isPvPEnemyOf`). A
  PvP zone is a `ZoneType.PVP` polygon or a map flagged `PVP_ENABLED` (`Creature.isInsidePvPZone`); the zones file has a few in Heiron, Inggison, Beluslan and
  the capitals' arena grounds. So **outside such a zone a bot of the other race is not an enemy at all**: nothing to do, and no accident either.
- **Rifts.** `RiftService` opens them on a cron (`config/schedule/rift_schedule.xml`: every hour on the open maps, guarded ones at fixed days).
  `RiftEnum` gives each rift its destination, entry count and level band (20 to 65, 50+ in Cygnea). A player talks to the rift npc (`RVController`),
  accepts a question window, and is teleported to the matching rift on the other side's map. The level check is the only gate.
- **Kills.** `PvpService` no longer throws on a bot killing a player (done earlier); points and rewards go through the usual paths.
- **Bot side.** `BotTargetSelector` only ever lists `Npc`s (`knownList.forEachNpc`), so a bot never picks a player. Defence against a player who
  attacks it is untested. `BotRequests` already answers duels.

## Decisions taken (31 October)

1. **An option**: `gameserver.playerbot.pvp.enable`, **true by default since 31 October** (the tester decided); off, nothing below runs.
2. **Bots attack real players and bots of the other race**, never above their level + 3 and never beneath level - 6.
3. **No extra death penalty**: the usual convalescence.
4. **Rifts**: a bot goes through one as a daily-plan item chosen by few bots (low weight: most bots never go, a handful do each hour), for 10 to 20
   minutes, then goes home. **On such an invasion the bot is there to fight**: it attacks every enemy it meets.
5. **Arenas are a plan of their own**, not part of this one (instances, own meshes), written later.

**Temperament, so that a bot does not attack on sight.** In a PvP zone met by chance (not an invasion), each bot has a fixed temperament drawn once
from its object id: *fighter* (attacks an enemy in range, about 1 bot in 4), *opportunist* (attacks only a weakened or lower level enemy, 1 in 4),
*peaceful* (never starts a fight, only defends itself, 1 in 2), as human players differ. Temperament is ignored during an invasion. The shares are a first guess to tune.

## Added on 31 October (all taken)

- **Where PvP really is (to check first).** Which maps are flagged PvP as a whole (Reshanta, the Abyss, where bots fly) was not verified; only the zone
  polygons were. It decides the hunting grounds and touches the refusal of the other side's islands. Milestone 0.
- **Combat made for a player, not a monster.** The playbooks are tuned for mobs: no stun or immunity handling, no flight. A PvP behaviour of their own:
  heal and flee under a life threshold, never pick a player already dying, no mob-only skill on a player. Probably the largest part (milestone 2b).
- **Fairness against a lone player.** A bot does not attack a lone player in a party of more than two bots, and withdraws when outnumbered.
- **A player's own bots.** Bots grouped with a player in a PvP zone help the player against an enemy, whatever their temperament; they never attack
  a player of their own side.
- **Rewards.** Decided: a player who kills a bot gets the engine's own rewards (abyss and divine points, kill count, bounties, quest and event credit), exactly as for a player victim; nothing is patched in `PvpService` except the log line of a kill. A bot's kill of a player also follows the engine unchanged.
- **Tests and traces.** `//bot pvp` (two bots face to face, a forced invasion) listed in `bot-commands.md`; one log line per PvP death or kill for the
  measure; a short chat reaction after a kill of a player (as the other reactions, in both languages).

## Milestone 0 result: where PvP really is (read from `world_maps.xml` and `data/static_data/zones`, meshes in the install's `data/navmesh`)

**The plan's first reading was wrong.** 152 of 161 maps carry the `PVP` flag, every open-world map included (Poeta, Verteron, Reshanta...), so
`Creature.isInsidePvPZone` is true almost everywhere. The `ZoneType.PVP` polygons with `flags="0"` (the ones in Heiron and the others below) are not PvP
grounds: `ZoneService` builds a `DisablePvPZoneInstance` from them, i.e. **safe zones** that switch PvP off inside. Only polygons with `flags="64"` are
real PvP polygons, and those are the capitals' arena grounds and the arena instances. So the zone never limits a fight: **the race does**
(`isPvPEnemyOf`). Two bots or a bot and a player meet as enemies wherever the two races share a map.

| Map | Flagged PvP | Safe polygons (flags 0) | Real PvP polygons (flags 64) | Mesh | Races meet here |
|---|---|---|---|---|---|
| Reshanta 400010000 | yes | none | none | yes | yes (the Abyss) |
| Kaldor 600090000, Levinshor 600100000 | yes | 6 and 4 | none | yes | to check (maps for both races, not verified) |
| Belus, Aspida, Atanatos, Disillon (4000x0000) | yes | 4 each | none | no | to check (Abyss pieces, not verified) |
| Heiron, Inggison, Beluslan, Gelkmaros | yes | 3, 2, 3, 2 | none | yes | no (one race's land) |
| Other Elyos and Asmodian lands (Poeta to Cygnea, Ishalgen to Enshar) | yes | none | none | yes (not Griffoen, Habrok) | no |
| Sanctum 110010000, Pandaemonium 120010000 | yes | none | 1 each | yes | no (one race's capital) |
| Arenas 3003x0000 to 30110000 (instances) | yes | none | 6 to 12 each | no | own plan (decision 5) |

Consequences. Off the shared maps a bot of the other race is no enemy, so `findPlayerTarget` returns nothing there with no zone test at all; its
`isInsidePvPZone` guard only excludes the safe polygons. **Hunting grounds (milestone 3) are the shared maps with a mesh: Reshanta, Kaldor, Levinshor**;
the four Abyss pieces need a mesh first. The refusal of the other side's islands matters for exactly these maps. The test of milestone 1 ("two bots in the
Heiron polygon") cannot work: Heiron is Elyos only and its polygons are safe. Use Reshanta.

## Milestone 2 and 2b result: what the code did, and what it does now

**Before (read from the code).** A player who hits a bot lands on the bot's own `AggroList` like a monster does (`isAware` is true for an enemy), which fires
`onAddHate` and so `PlayerBotAI.handleAttack`: that one **did** retaliate (`startAttacking` on any enemy, a `Player` included, when the bot was not
already fighting and `autonomous`). What was missing: `findAttacker` and `findAttackers` only read `Npc`s, so after the first reaction nothing re-picked a
player attacker (a fight dropped for any reason was not resumed), `retreatIfLosing` never counted players (a bot died to a player at any health),
nobody helped a grouped mate hit by an enemy player (`targetToAssist` reads monsters only), and `BotCombatMemory`/the playbooks held no assumption about the
attacker's kind. `canKeepFighting` already asks `isEnemy` for a `Player` target, so a duel end or a lost enmity stops the fight.

**Now (all independent of `gameserver.playerbot.pvp.enable`: a real player can attack a bot with the option off).**
- `BotPvpDefence` lists the enemy players on the bot's aggro list (`attackersOf`) and the enemy players on a group mate (`enemyOnAMate`, 40 m, in sight).
  `findAttacker` falls back on the nearest such player, so the bot answers and re-picks; a grouped bot (healers excepted: they heal) helps a mate
  against an enemy player whatever its temperament. `isEnemy` is the only gate, so a player of its own side is never attacked.
- `withdrawFromPlayers` (decision tick, not in a group, like `retreatIfLosing`): `BotPvpRules.shouldWithdraw` is true under 30 % life against any
  enemy player, under 50 % against two, and at three or more at any health. It tries a defensive ability (or a healing potion under 30 %), then
  ignores the attackers for 30 s, sheathes and walks home. `handleAttack` no longer answers a player it has just broken off from.
- Selection (`isPlayerFairGame`) skips a player at 10 % life or less (dying). More than two bots on a lone player was already refused.
- `BotSkillManager.cast` skips a skill whose `targetSpecies` is NPC when the target is a `Player` (the engine refuses them late; they hid the usable ones).
  The playbooks only touch `Npc` behind `instanceof` (checked: gladiator, ranger, templar, sorcerer), and `NpcRating`/`NpcRank` are read in
  `BotTargetSelector` for monsters only, so a `Player` target needs no more guarding.
- Not done: no PvP rotation (stun/immunity answers, no kiting), and a bot in a group never flees a player (a hole in the party).

**Rewards (read from `PvpService.doReward`).** A player kill gives the killer's group AP and DP from the victim's rank and level (`calculatePvpApGained`), XP from a PvP table (capped by `PvpExpLimitService`), a kill count with `PER_X_KILLS` bounty items, quest and event credit; the victim loses AP in proportion to the damage that counted. The daily limit (`MAX_DAILY_PVP_KILLS`) is per victim object id, so a player can in principle farm AP on many different bots. **Decided on 31 October by the tester: a player who kills a bot gets all of it, unchanged**; an earlier patch refusing those rewards was taken out. A bot that kills (a player or a bot) earns AP and bounty like any player, which may be capped if bots ever climb the abyss ranks.

**Traces.** `BotPvpTraces.onKill` (called from `PvpService.doReward` once the winner is known, so one line per kill that involves a bot) writes
`PVP KILL killer <name> (bot|player <race> <level>) victim <name> (...) at map <id> <x> <y> <z>` to the game log, and the killer bot may say a
`react.kill` line in Region (50 %, the pace of the other reactions, both languages). `//bot pvp <a> <b>` forces two bots of different races onto each other.
`measure.sh` does not count `PVP KILL` yet.

## Milestones

0. **Check the ground (done, see above).** List the PvP maps and zones from the data (maps flagged, polygons), say which have a mesh, and fix the hunting grounds.
1. **See a player as a target** (smallest, testable alone). `BotTargetSelector.findPlayerTarget` returns an enemy `Player` in a PvP zone, picked with
   the same line-of-sight, height and level rules and the temperament above; used only when the bot stands in a PvP zone **and** the property is on. Test: two bots of different
   races placed on Reshanta (`//bot squad` + a teleport; Heiron cannot serve, see milestone 0), they fight; one player of the other race attacked there is answered.
2. **Defence.** A bot attacked by a player (any zone where `isEnemy` holds) fights back or flees below 30% life. Check what `BotAttackManager` does
   today with a `Player` attacker before writing anything; the duel path (milestone already built) is the likely seam.
2b. **PvP combat behaviour** (see above), then the fairness rule and the player's own bots' help.
3. **Hunting ground in a PvP zone.** `BotPlaces` offers the PvP polygons as a hunting ground to bots of level 30+ only while the property is on and
   the feature has a quota (at most N bots per zone, to keep ticks and deaths down). Needs the navmesh to cover those polygons: check `check-golden-routes` style
   routes into each one first.
4. **Rifts.** A day-plan item "go through a rift": find an open `RiftLocation` of the bot's side (`RiftManager.getSpawnedRifts(worldId)`), walk to the
   rift npc, call the same acceptance path as `RVController` (a bot has no window: ask for a direct method, like `BotRequests` does for a trade),
   hunt for the stay's length, return. The Abyss crossing refusal of the other side's islands stays as it is; only this item lifts it, for its own journey.
5. **Measure.** Same windows as the other work: deaths per hour with the feature on and off, bots in PvP zones, kills of real players, journeys given up.
6. **Arenas.** Out of this plan, own document later.

## What can go wrong

- **Bots that farm each other**: the same two kill each other for ever. Mitigation: `DeathSpots` already remembers a death for 15 minutes; add the
  killer's id to a 5-minute shy list.
- **A real player ambushed at low level**: the level band (level + 3 at most, never beneath level - 6) keeps it fair; the property keeps it optional.
- **Ticks**: scanning known players on every tick costs; scan only when the bot is inside a PvP polygon.
- **Cleaning up**: the rift closes at the hour (`closeAutoCloseableRifts`); a bot that stays too long on the other side must be sent home by its obelisk (the existing rehoming path).

## First step proposed

Milestone 0 (the check of the ground), then milestone 1 plus the property, test-only, then a watch in game with the tester standing in the Heiron polygon. Nothing else before that works.
