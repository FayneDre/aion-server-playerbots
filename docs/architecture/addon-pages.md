# Addon pages

Status: reference: how the interface addons' pages are served, and who may read them. Written 7 November 2026.

The 4.8 client's Lua cannot tell who healed whom (no healing event, damage events without a reliable source), so an addon that shows combat
numbers shows a page the game server serves, in a browser inside its window. The addons themselves, their install and the client patch they
need live in a separate repository (`echoes-of-atreia-addons`); this file says what the server does.

## The server

- `playerbot/addon/AddonWebServer` serves HTTP on `gameserver.playerbot.addon.port` (7790 by default; 0 turns it off), with the JDK's
  `HttpServer` on virtual threads, started by `GameServer` after `AdminChannel`.
- `/addon/combat-meter` is the page (`CombatMeterPage`), `/addon/combat-meter/data` its numbers as JSON, `/addon/combat-meter/reset` starts the
  viewer's team over from zero.

## Who a page belongs to

The client's browser carries nothing of the game session: the call that would have sent a session token went through the publisher's web
sign-on, which is gone, and the addons' installer patches it to load the address as it is. So the addon names its character (`?name=`), and the
server shows that character's page only when the request comes from the address its game connection comes from. A player on the server's own
machine reaches the game by a network address and the page by the loopback one: two addresses of the server's machine count as one.

What this protects is little (a team's damage), and it holds as long as two players do not share an address; a token the server hands the client
would need a channel the Lua can read, which none was found to be.

## The combat meter's count

`CombatMeter` is called from `CreatureLifeStats.reduceHp` (the health the target lost) and `increaseHp` (the health it gained, and the rest of
the heal as overhealing; a heal without a skill, natural regeneration, is not counted). A fault of the meter is logged and never stops the hit or the heal. The source is the attacker's or the effector's master, so
a summon's or a servant's numbers go to its player. Counted: players, and bots while they are in a team with a player, so a world of bots alone
costs one test per hit. Per second figures are over the time in combat: an action less than five seconds after the previous one adds the time
between them, a later one a second. The numbers are kept per player since its team's last reset; idle ones are dropped past 500 players.

## The address the addon uses

`http://127.0.0.1:7790` by default, the server on the player's machine. A client launched with `-updsvr:http://<host>:<port>` uses that one: the
client keeps the option's text in `g_launcherUpdateURL`, which it reads for nothing else, and the addon reads it with `GetConsole`, a function the client gives only to a plugin it trusts
that asks `## Require: console` (the addons' installer adds them to its trusted plugins).
