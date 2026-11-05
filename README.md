# Aion Server Playerbots

An Aion 4.8 server emulator whose world is populated by AI-driven **player bots**: real player characters, with no client behind them, that live,
level, travel, fight with their class's skills, group with players, keep stores and talk in the channels.

> Unofficial fan project, not affiliated with or endorsed by NCSoft. Aion is a trademark of NCSoft Corporation.

It is a fork of [beyond-aion/aion-server](https://github.com/beyond-aion/aion-server), the Beyond Aion team's emulator, and stays close to it: the
bots live in a module of their own, so that the emulator's improvements keep coming in.

## What the bots do

- **A world that lives**: about a thousand bots on 26 open-world maps, created asleep in a pool and woken by a director that keeps each region at
  its population. Villages and capitals stay full; the countryside follows the players or holds a fixed density.
- **Careers**: bots gain levels, move on to the region their level calls for, dress from what they loot and buy, and sell what they do not need.
- **Combat**: real class skills, with playbooks written from class guides for the templar, gladiator, chanter, cleric, ranger and sorcerer, and the
  data's own order for the others; group roles, positioning against bosses, retreat from a fight they cannot win.
- **Groups**: invite a bot like anyone; it follows, fights what the group fights, goes into instances with you. The Find Group window fills a party
  with a tank, a healer and a support at your level.
- **Movement**: planning on navigation meshes generated for every open-world map and 45 dungeons, with glides, rides and flight, Reshanta's islands
  crossed by air.
- **Capitals and economy**: a town day, private stores at broker prices, an auction house the server keeps stocked and that buys what players list.
- **Social**: speech in the Region, Trade and Find Group channels and shouts in the capitals, in French or English; duels, friends and trades.

What is built and what is left: [docs/built.md](docs/built.md) and [docs/roadmap.md](docs/roadmap.md). Every document: [docs/README.md](docs/README.md).

## Playing

The easiest way is the launcher, [Echoes of Atreia](https://github.com/FayneDre/echoes-of-atreia-launcher): it finds the server, the client,
Java and MySQL, starts the servers in order, sets the rates and the bots' options, and launches the game.

The game client is not part of this project. The Aion 4.8 NA client is available from the
[Internet Archive](https://archive.org/download/aion-4.8-na/Aion%204.8%20NA.7z); it needs Beyond Aion's
[version.dll](https://github.com/beyond-aion/aion-version-dll/releases/latest) in its `bin32` and `bin64` folders to connect to an unofficial
server and to run on Windows 10 or later.

## Branches

| Branch | What it is |
|---|---|
| `main` | The stable branch: releases are made from it and tagged `vX.Y.Z`. |
| `develop` | Day-to-day work. Test patches for the testers are tagged on it as `patch-YYYY-MM-DD`. |
| `beyond-aion` | A mirror of Beyond Aion's `4.8`, through which its updates come in. |

Updates flow one way: Beyond Aion into `beyond-aion`, merged into `develop`, and `develop` into `main` when a version has been validated in game.

## Building and running

Java 25 and [Maven](https://maven.apache.org/) are required; `mvn package` from the root builds the game, login and chat servers, each as a zip in
its `target` folder, to run with a JDK and a MySQL (or MariaDB) server. Tests are skipped by default: `-Dmaven.test.skip=false` runs them.

Initialise the databases with the `*.sql` file in each server's `sql` folder (default names and users in `config/network/database.properties`).
Local settings go in `config/mygs.properties`, `config/myls.properties` and `config/mycs.properties`, which take precedence over the defaults and are
never touched by an update. The bots' options are in `game-server/config/main/playerbot.properties`.

The navigation meshes are generated, not stored: `tools/navmesh.ps1` builds them. On Windows, `tools/deploy.ps1` builds and installs into a server
folder (`AION_SERVER_HOME`), `tools/start-server.ps1` starts the three servers in order and `tools/stop-server.ps1` stops them without losing data.

## Contributing

Bot work goes in `game-server/src/com/aionemu/gameserver/playerbot/`, away from the emulator's own code, so that merges from Beyond Aion stay small.
Commits are in English and short. Run `tools/install-hooks.ps1` once per clone. How the module is built: [docs/architecture/how-it-works.md](docs/architecture/how-it-works.md).

## Credits and licence

The emulator is the work of the [Beyond Aion](https://github.com/beyond-aion/aion-server) team, built on Aion-Lightning's and on
[Aion-Emu](https://web.archive.org/web/20100128222712/http://aion-emu.com/), the first server project for the game. Collision data comes from
Beyond Aion's [GeoBuilder](https://github.com/beyond-aion/aion-geobuilder).

[GNU General Public License v3.0](LICENSE), as the emulator it is built on.
