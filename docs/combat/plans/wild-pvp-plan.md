# Plan: wild PvP, rifts and the arenas

Status: not started. Analysed on 31 October (roadmap point 8). Nothing of it is written; the engine facts below were read in the code, not yet tried with a bot.

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

1. **An option**: `gameserver.playerbot.pvp.enable`, false by default; off, nothing below runs.
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
- **Rewards.** Read what `PvpService` does against repeated kills of one victim; a bot killed by a player gives no abyss points (else a player farms them);
  a bot's kill of a player follows the engine's rules unchanged.
- **Tests and traces.** `//bot pvp` (two bots face to face, a forced invasion) listed in `bot-commands.md`; one log line per PvP death or kill for the
  measure; a short chat reaction after a kill of a player (as the other reactions, in both languages).

## Milestones

0. **Check the ground.** List the PvP maps and zones from the data (maps flagged, polygons), say which have a mesh, and fix the hunting grounds.
1. **See a player as a target** (smallest, testable alone). `BotTargetSelector.findPlayerTarget` returns an enemy `Player` in a PvP zone, picked with
   the same line-of-sight, height and level rules and the temperament above; used only when the bot stands in a PvP zone **and** the property is on. Test: two bots of different
   races placed in the Heiron PvP polygon (`//bot squad` + a teleport), they fight; one player of the other race attacked in that polygon is answered.
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
