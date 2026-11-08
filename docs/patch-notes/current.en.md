# Patch notes (English)

The English twin of [current.md](current.md): the same entries, headed by the same version tags, in the same order, written from the French
one in the same pass. `tools/tag-patch.ps1` refuses a tag whose entry is missing from either file, or does not name the revision `//bot version`
shows. The entries made before versions (the `patch-YYYY-MM-DD` tags) stay French only. When this file nears 200 lines, the oldest entries move
to `<year>-<month>.en.md`, with their French ones.

---

## v0.1.0-beta.4 — 2026-11-08

Revision `a494079`, the one `//bot version` shows. It goes with addons v0.1.0-beta.4 (the combat meter), which it serves. **No SQL, no navmesh
to rebuild.** **One default settings file changes**, `game-server/config/main/playerbot.properties` (the new option below); the launcher
replaces it if it was not edited, and puts the new version beside it otherwise.

**What changes for players and testers**
- **The combat meter**: the server counts each player's damage and healing, by skill (overhealing included, a summon's numbers going to its
  master), and the bots' while they are in a player's group. The count is the group's since its last reset; per second figures only count the
  time in combat.
- **The addons' pages**: the server serves them on port 7790 (`gameserver.playerbot.addon.port`, 0 to serve none). A page shows a character
  only to a request coming from the address of its game connection.

**To test**
- Install addons v0.1.0-beta.4, then in game open Combat Meter (the game's menu, Additional Functions, or `/dps`) and fight, alone and then in a
  group with a bot: damage, healing, a member's skills with a click on its name, the reset.

---

## v0.1.0-beta.3 — 2026-11-07

Revision `9164482`, the one `//bot version` shows. It goes with launcher v0.3.0-beta.5, which sets the premium rates. **No SQL, no navmesh to
rebuild.** **One default settings file changes**, `login-server/config/network/network.properties` (the new option below); the launcher
replaces it if it was not edited, and puts the new version beside it otherwise.

**What changes for players and testers**
- **Every player account is premium** (`loginserver.accounts.minimum_membership = 1`): new accounts are created premium, older ones become
  premium when the login server starts. The premium rates are therefore the players', and the regular rates the bots', which have no account.
- **A bot in a player's group earns at the player's rates** (the highest of the group's players), no longer at the regular ones.
- **Group instance experience** stays the official game's whatever the rates: it is now divided by the premium rate instead of the regular one.

**To test**
- Change a premium rate on the launcher's Settings page, restart, and check the gain in game, alone and then in a group with a bot.

---

## v0.1.0-beta.2 — 2026-11-06

Revision `c979e97` for the code (the one of the server deployed here); a server installed by the launcher shows in `//bot version` the tag's
commit, which only adds these notes and the release scripts. **The first patch shipped built**: it goes with launcher v0.3.0-beta.3, which
installs it by itself, without Maven or a build. **No SQL, no navmesh to rebuild**, no default settings file changes.

**What changes for testers**
- **The server installs from the launcher**: the pre-release carries the built server (`aion-server-v0.1.0-beta.2.zip`, 149 MB), its
  manifest and the navmesh maps (1.8 GB the first time; after that, only the maps rebuilt). The launcher downloads and checks everything while
  the servers run, shows you what it will do, then stops them, installs, updates the database and starts them again. "Return to..." puts the
  previous version back.
- **Your files stay yours**: a default settings file you edited is not replaced, the new version is put beside it
  (`<file>.new-v0.1.0-beta.2`); `mygs.properties`, the logs and what your server wrote into the navmesh are never touched.
- **The server's version**: `//bot version` shows the version, the revision and the launcher API level, and the launcher asks the server for
  it to say when it is too old.
- **A JDK 25 is needed** (the server compiles its handlers at every start); Maven and Git no longer are.

**Not fixed yet** (known, no need to report it)
- **Installing through the launcher** was checked in tests and on a trial bundle only, not on your install yet.
- **A database that never went through `apply-sql.ps1`**: its SQL statements are recorded as already done, not run again. If you never applied
  `game-server/sql/update.sql` to your database, say so before installing.

**To test**
- **With launcher v0.3.0-beta.3**: "Update the server", install, play, then "Return to the server built from its sources", and check that the
  server starts and the bots log in after both.
- Anything wrong: `//bot note <text>` next to the bot, or a screenshot of the "Server version" panel.

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
