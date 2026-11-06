# Patch notes (English)

The English twin of [current.md](current.md): the same entries, headed by the same version tags, in the same order, written from the French
one in the same pass. `tools/tag-patch.ps1` refuses a tag whose entry is missing from either file, or does not name the revision `//bot version`
shows. The entries made before versions (the `patch-YYYY-MM-DD` tags) stay French only. When this file nears 200 lines, the oldest entries move
to `<year>-<month>.en.md`, with their French ones.

---

## v0.1.0-beta.1 — 2026-11-06

Revision `eb82601`, the one `//bot version` shows. **The first patch made as a version**: test patches are now named `vX.Y.Z-beta.N`,
published as GitHub pre-releases, with their notes in French and English. This patch holds everything since patch-2026-11-03. It goes with
launcher v0.3.0-beta.1. **No SQL, no navmesh to rebuild.** **One default settings file changes**, `game-server/config/main/playerbot.properties`
(the new options below): `.\tools\sync-install.ps1 -From patch-2026-11-03` says what it will replace, the same command with `-Apply` does it;
your own settings go in `config/mygs.properties`. Then `.\tools\deploy.ps1 -Restart`.

**What changes for players and testers**
- **The world's density has three modes** (`gameserver.playerbot.density.mode`, and `density.percent`, 30 by default):
  - `AUTO` (the default) follows the players: all of a map's countryside where somebody plays (and five minutes after), a fifth elsewhere, a
    twentieth with nobody online;
  - `FIXED` holds the percent everywhere, seen or unseen;
  - `FLOOR` is `AUTO` that never falls below the percent.
  Villages are always full. `//reload config` applies it within 30 seconds.
- **The bot settings** (the ones of the launcher's Bots page), applied by `//reload config`:
  - a bot's experience, as a percent of the official rate whatever the players' rates: alone, in a group and in PvP (1, 1 and 100 by default).
    A bot in a player's group follows the player's rate;
  - PvP: the share of bots that attack on sight, of those that attack only a weakened or weaker player, and of peaceful ones (25, 25 and 50),
    and the level band (3 above, 6 below);
  - the bots' chat, on or off.
- **The launcher's admin channel**: reload the configuration, list the destinations (open-world maps only) and teleport a character.
- **Route searches**: one search waits per bot, a newer one replacing it and keeping its place. The queue grew past a minute's wait (25,000
  searches at 100 percent).
- **The log**: one director line at every review (awake, wanted, asleep, density, route queue) and one line of the queue every 10 seconds,
  instead of one line per slow search.

**Not fixed yet** (known, no need to report it)
- **The route queue at full density** is not measured yet in its new version.
- **After a high-density session**, a start can take over 10 minutes (about 1,900 bots restored before the server announces itself).
- **The bot settings and the teleport** pass their tests but have not been checked in game.

**To test**
- **With launcher v0.3.0-beta.1**: the Bots page shows the server's settings, the teleport list is filled.
- **Density**: `FIXED` at 30, then `//reload config`; the countryside should stay populated from one map to the next.
- Anything wrong: `//bot note <text>` next to the bot.
