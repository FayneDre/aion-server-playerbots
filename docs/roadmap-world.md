# Roadmap, themes 5 to 12: flight, capitals, gear, PvP, the broker, shops, legions, RvR

The second page of [roadmap.md](roadmap.md), whose first three sections are the whole picture (everything left by theme, the order, what is not
done by decision) and which details themes 0 to 4. Same rules here: **Done** in a line, **Left** in the order to do it, numbered as in the table.

## 5. Flight and the Abyss

**Done**: take-off, landing, cruise and flown descents, following a flying player, Reshanta crossed by air and lived on both layers, 120 of its 134
places ([flight/how-it-works.md](flight/how-it-works.md)).

**Left**: nothing, by decision of 15 November ([backlog.md](flight/backlog.md)). The Terminon camp entrance, the stalled ambient flights and
gliding on the way down are not done; the Abyss's deaths come down to the playbooks of theme 3, not to flight.

## 6. Capitals and social life

**Done**: a town day in the capitals, 40 bot stores a capital, high-level visitors, shouted adverts, the bots' chat in French or English with jokes
and reactions ([capitals/how-it-works.md](capitals/how-it-works.md)); pages in the client's browser and an interface addon with the combat meter
([addon-pages.md](architecture/addon-pages.md)); a staff member's modes switched from the launcher.

**Left** (1 to 3 are lot I of [living-world-plan.md](population/plans/living-world-plan.md)):
1. **Personalities** (I2): each bot drawn once a temperament, kept for life: *chatty*, *quiet*, *brawler*, *loner*, *sociable*. One draw sets what is
   drawn apart today or not at all: how often it speaks and of what, its PvP temperament (the `pvp.share.*` shares become what the temperaments add
   up to), whether it groups in the field and answers invitations quickly, whether it camps a prize. The brawler who shouts in Region is the one who
   attacks on sight.
2. **True rumours** (I1): bots say in the region's chat what the server knows and a player would want to: a named monster just back near a place,
   the other faction seen in numbers somewhere, a prize just taken. Only true things, said once, by a bot of the region, a few minutes late so it
   reads as hearsay rather than a radar; never a rumour that sends a player to a fight far above their level.
3. **Contested named monsters** (I3): the open world's named monsters (hero and legendary ratings, long respawns, a drop worth the trip) become
   prizes the bots want too, as on a real server.
   - *The catalogue*, from the data: each one's spawn point, level, respawn and drop; counted per map first, quest targets set apart.
   - *Who goes*: a group of bots of the right level forms when the prize is due back (it needs E2), the director waking them if the region has
     none; a share of prizes contested (an option, 0 to 100), the rest left to players.
   - *Knowing the timing*: bots learn when a prize was killed and come back for its respawn window, as campers do; a player who knows the window
     meets them there.
   - *Fair play both ways*: whoever hits first owns the fight, the engine's rule; a bot never attacks a prize a player's group is fighting and gives
     up a camp a player held first. In a PvP zone the other faction's bots may fight a player's group for it: the prize becomes a battle.
   - *What it feeds*: the drop goes into a bot's bag and on to a travelling merchant (C1); the kill becomes a rumour (I1), the group's name with it.
   - Measured: prizes killed per day by bots and by players, and how long a prize stays up after its respawn: a player on time gets it most of the
     time, a late one finds it taken.
4. **Bots that take the first step**, rarely: a bot on a player's friend list offers a dungeon of their level when the player logs in, one in the
   region congratulates a player in Region chat on a level gained or a named monster killed; each with a ceiling per player and per day, so it stays
   a surprise and never a spam. It reads what the server knows (friends, levels, kills), as rumours do (I1). No plan yet.
5. **Bots that answer when spoken to**: a whisper or a line naming a bot answered in its own words, a request in chat ("invite me", "come") acted on
   when it can be. No plan yet.
6. **A window on the bots inside the game, the rest of lot H**:
   - a page of one's companions: roster, roles, formations, gear;
   - **GM tools** (H3) as forms on a page, for a staff account or the client on the server's own machine: the character (level, heal, resurrect,
     cooldowns, speed, invisibility, invulnerability, skills, titles), items found by name, quests, the world (an npc, weather, time, a rift, a
     siege, an announcement), players (online, kick, ban); the quickest as buttons of the addon bar, a destructive one confirmed;
   - **what is used while playing** (H4): teleport, the world map (bots awake and asleep, players, density, the guard; never the prizes' state);
     the tester's report is theme 13 of [roadmap.md](roadmap.md); the world map needs an admin channel command (`world`) the launcher reads too.

## 7. Gear

**Done**: bots dressed at creation (enchanted, socketed, with stigmas), wearing what they loot, wings renewed with the level, junk sold, the best of
what they own worn, life and mana flasks from level 10 ([gear/how-it-works.md](gear/how-it-works.md)).

**Left**: nothing. By decision (15 November), bots neither seek, buy nor enchant their own gear: what they loot, and the prizes of I3, is what they get.

## 8. Wild PvP and rifts

**Done**: where PvP really is, a player as a target, defence and PvP behaviour, built, unverified; `gameserver.playerbot.pvp.enable` is on (true by default since 31 October), yet no bot has fought in PvP: no `PVP KILL` line in the logs of 14 and 15 November, since bots do not hunt PvP zones before point 2
([wild-pvp-plan.md](combat/plans/wild-pvp-plan.md)).

**Left**:
1. **A watch in game** with the property on: no bot has met a player yet.
2. **Hunting grounds in PvP zones** (milestone 3), then **rifts** (4), then the **measure** (5).
3. **Limits on a fight** (D1), beside our shares and level band, each an option: pursuit no further than about 60 m from where it began and no
   longer than about 25 s, then a pause; a fight started only when the other side's visible strength is at most about 1.5 times ours; at most three
   attackers on one target; retreat under a quarter of health.
4. **Rift raids** (D2): a group of one faction walks to an open rift, goes through, hunts the other side for some minutes and comes back; a share of
   leaders tries it, with a rest between attempts and a blocked trip given up.
5. **Raids the world answers** (D3): a raid said in the region's chat in the bots' own words ("des Asmodiens à Eltnen, près de la forteresse"), and
   the wardens and the bots nearby going to meet it: a battle a player hears about and can join, even when no player started it.
6. **Grudges** (D4), last: a bot remembers who killed it (a few names, a few days), greets them in chat, looks for them first, or keeps away if it
   lost too often. Players feel known.

## 9. Broker

**Done**: a house the server stocks, one per race, the seller, settling and relisting, and a buyer of what players list, seen working in game on 15
November ([auction-house-plan.md](capitals/plans/auction-house-plan.md)).

**Left**: 1. **prices and goods adjusted** from the tester's list; 2. the **measure** (milestone 7): heap, tick cost, listings against targets.

## 10. Player shops

**Done**: bots sell their junk to merchants ([capitals/backlog.md](capitals/backlog.md)). Gathering and crafting by bots are left out by decision.

**Left**: 1. **player shops** run by bots in the world, beside the travelling merchants of theme 2 (C1).

## 11. Legions

**Not started.** **Left**:
1. **Answering a legion's invitation** (asked in game on 19 October), and its rank's permissions respected.
2. **Bot legions born of who played together** (G1): a legion formed from bots that grouped often (E2), with a name from a list written for it, a
   chat and a home region; the wardens of a region (C2) may be one.

## 12. RvR and sieges

**Not started**, the stretch goal. Its supply line is designed: bots at the level cap become the elders the endgame needs
([population/how-it-works.md](population/how-it-works.md)). Panesterra's four fields hold no civilians and have no mesh (no heightmap in the data).
