# Patch notes (English)

The English twin of [current.md](current.md): the same entries, headed by the same version tags, in the same order, written from the French
one in the same pass. `tools/tag-patch.ps1` refuses a tag whose entry is missing from either file; it writes the revision
`//bot version` shows (the tagged commit's) as the first line of the published notes, so an entry names none. The entries made before versions (the `patch-YYYY-MM-DD` tags) stay French only. When this file nears 200 lines, the oldest entries move
to `<year>-<month>.en.md`, with their French ones.

---

## v0.1.0-beta.8 — 2026-11-15

It goes with launcher v0.3.0-beta.12. **No SQL, no navmesh to rebuild.** **One default settings file changes**,
`game-server/config/main/playerbot.properties` (the new option below); the launcher replaces it if it was not edited, else it puts the
new version beside it.

**What changes for players and testers**
- **Bots stop arriving when the PC's RAM is full**: besides its own memory, the server watches the RAM of the whole computer, the game and
  the other programs included (the task manager's figure). Past the mark, 90 % by default, no more bots arrive; those already there stay,
  and arrivals resume 5 points under it. The Bots page of launcher v0.3.0-beta.12 turns it on or off and sets the mark
  (`gameserver.playerbot.guard.machine.enable` and `.hold`). `//bot pool` and the launcher's "Population now" box show the PC
  RAM in use.

**To test**
- On a PC where the game and the server take a lot of RAM: see the Population box say "paused" past the mark, and the bots come back when
  it is raised or the box unticked.

---

## v0.1.0-beta.7 — 2026-11-14

It goes with launcher v0.3.0-beta.11. **No SQL, no navmesh to rebuild.** **One default settings file changes**,
`game-server/config/administration/admin.properties` (the new option below); the launcher replaces it if it was not edited, else it puts
the new version beside it.

**What changes for players and testers**
- **The combat meter works with an administrator account**: it said "The server does not recognise this character", because the game gave
  it the name with its »Admin« tag.
- **Game master modes from the launcher**: the Account page of launcher v0.3.0-beta.11 switches, for an administrator account whose
  character is in game, Invulnerable, Invisible, Left alone, See hidden, No cooldowns and Fly anywhere, through the game master commands
  themselves.
- **The »Admin« tag can be removed**: `gameserver.administration.customtags.enable = false` (or the launcher's box) shows administrators'
  names without their tag.

**To test**
- With an administrator account: open the combat meter in game, then switch each mode from the launcher and see it in game.

---

## v0.1.0-beta.6 — 2026-11-14

It goes with launcher v0.3.0-beta.8. **No SQL, no navmesh to rebuild, no settings file changes.** The game server is the same as beta.5's:
only the database an install from nothing downloads changes.

**What changes for players and testers**
- **The install from nothing works on a fresh Windows**: the MySQL database would not start on a computer that never installed Microsoft's
  "Visual C++ Redistributable" ("The database could not be made"). It now carries the four Microsoft files it needs. An existing install is
  not affected.

**To test**
- With launcher v0.3.0-beta.8 in Windows Sandbox (8 GB): "Install the server", log in and see bots, then "Uninstall the server"; the
  characters' backup must stay in `Documents\Echoes of Atreia\backups`.

---

## v0.1.0-beta.5 — 2026-11-14

It goes with launcher v0.3.0-beta.6, which can install it on a computer that has nothing but the game client. **No SQL, no navmesh to
rebuild.** **Nine default settings files change** (among them `game-server/config/main/playerbot.properties` and `custom.properties`, options
below); the launcher replaces each one if it was not edited, and puts the new version beside it otherwise.

**What changes for players and testers**
- **An install from nothing**: the release carries its own Java (55 MB) and its own MySQL database (29 MB), with the scripts that make it.
  Nothing is installed on Windows: no Java, no MySQL service, no administrator rights. A new install populates the 24 measured maps (both
  races, the capitals, the contested ground) instead of Poeta alone.
- **The server fits in less memory**: the navigation tiles of every open map share one budget (12 % of the heap,
  `gameserver.playerbot.navmesh.tile_budget`), and a guard holds the bots' wake-ups while memory or route planning runs short
  (`gameserver.playerbot.guard.*`). While it holds, bots of maps nobody plays are exchanged for arrivals where a player is: there are people
  around you even on a small machine.
- **The bots carry potions**: when they are made and when they change their gear, from level 10, they get life and mana flasks, the best a
  merchant sells.
- **The emulator updated** (49 changes from the original team, up to 12 November): weather and channel change as on the official game, identify
  failing after the first item, free flight ending when gliding out of a flight zone, the duel timer, falls from elevators, quests where an NPC
  follows, Eternal Challenge rebalanced, plumes selectable again, failed trade messages, and many guards against malformed packets. A creature
  that will die from a hit in flight is now treated as dead from the end of the attack, as on the official game
  (`gameserver.combat.death_at_hittime`, `false` by default).

**To test**
- With launcher v0.3.0-beta.6 on a machine with no server (or Windows Sandbox): "Install the server" on the home page, up to logging in and
  seeing bots.
- On an existing install: the update from beta.4, then an hour of play; bots of level 10 and above drink their potions.

---

## v0.1.0-beta.4 — 2026-11-08

Revision `0d967aa`, the one `//bot version` shows. It goes with addons v0.1.0-beta.4 (the combat meter), which it serves. **No SQL, no navmesh
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

