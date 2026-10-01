# The `//bot` command

Everything the bot system exposes in game, in one place. `//bot` on its own, or `//bot help`, prints the same list in the chat window.

**Anyone may use it.** `bot = 0` in `config/administration/commands.properties` is what says so — the access level is compared with
`accessLevel >= 0`, which every account satisfies, so no code decides this and changing that one number is the whole of the permission. Set it back to
`9` to make it staff only again.

The prefix is `//` even for a regular player: the chat processor looks a command up by name and only then asks whether the caller may run it. Uses are
recorded in the admin audit log when `LOG_GMAUDIT` is on, which is worth leaving on — it is the only record of who emptied the world.

## What to expect of a name

Most of these take the character name of one bot. They act on a **spawned** bot and answer `No bot spawned with name X` otherwise, which is the same
answer you get for a name that never existed — the command cannot tell those apart. `//bot list` is how you find out which names are real.

## Populating a world

| Command | What it does |
|---|---|
| `//bot populate <count> [race]` | Fills the map you are standing on with `count` inhabitants, up to 20 at a time. Their levels come from the creatures living in each place, their classes are spread over what a character of that level could be, and each is fitted with gear that suits it. Without a race, yours. |
| `//bot create <name> <class> <level> <race>` | One bot, made to order, with a face of its own. Class names are the engine's own (`WARRIOR`, `GLADIATOR`, `CLERIC`…), race is `ELYOS` or `ASMODIANS`. It is created and stored, not spawned. |
| `//bot delete <characterName>` | Deletes the character from the database. Refuses anything that is not on a reserved bot account, so a mistyped name cannot reach a real player, and refuses a bot that is still spawned. |
| `//bot clear` | Empties the world of bots **and deletes every one of their characters**. The map has to be populated again afterwards. Same account restriction as `delete`. |

A map normally populates itself on first start from `gameserver.playerbot.populate` — see [population.md](population.md). `populate` is for adding to
a map by hand, or for a map the config does not cover.

## Putting bots in and out of the world

| Command | What it does |
|---|---|
| `//bot spawn <characterName>` | Loads the character and puts it in the world next to you. |
| `//bot despawn <characterName>` | Takes one bot out of the world. Its character stays in the database. |
| `//bot despawnall` | Takes every bot out of the world and empties the roster, so a restart comes back to an empty map. Nothing is deleted. |
| `//bot load <characterName>` | Loads the character and prints what it is — level, class, gear, skills, health — without spawning it. A way to see whether a character reads correctly before it is put anywhere. |
| `//bot list` | Every bot currently in the world. |

`despawnall` and `clear` are the two that look alike and are not: the first is an operator emptying the world, the second destroys the characters.

## Telling a bot what to do

| Command | What it does |
|---|---|
| `//bot attack <characterName>` | Sets the bot on your target, or on you if you have none — which is what makes it usable in a duel. |
| `//bot stop <characterName>` | Ends the fight. |
| `//bot come <characterName>` | Walks the bot to where you stand, and makes that its new home, so it will come back there when it has nothing to do. Answers that it is blocked when no route exists. |
| `//bot duel <characterName>` | Answers a duel request you have already sent. A bot has no window to click. |
| `//bot auto <characterName>` | Turns autonomy off and on. A bot with autonomy off only does what it is told; it still defends itself. |

## What a bot is, and what it carries

| Command | What it does |
|---|---|
| `//bot kind <characterName> [resident\|adventurer]` | With no third word, says which it is. A **resident** gains no experience, so the region it lives in keeps inhabitants of its own level; an **adventurer** levels normally. Takes effect on the next spawn, and on a spawned bot straight away. |
| `//bot bag <characterName>` | What the bot is carrying, and its kinah. Read from the bot in memory, not from the database, which only ever sees a bot when it despawns. |
| `//bot sell <characterName>` | Sends the bot to a shop now instead of waiting for a full bag. Says so if it has nothing worth selling, or if the map has no shop. |

## Diagnosis

| Command | What it does |
|---|---|
| `//bot nav` | Which maps' navigation meshes are loaded in memory. A map missing here is one where bots will not travel; see [navmesh-plan.md](navmesh-plan.md). |
| `//bot number [region]` | How many inhabitants a map holds, by faction, and what it asks for. A map id or the start of a map's name; yours by default. |

`//bot number` counts three things apart, and the distinction is the point of it. **Inhabitants** are the world's own, by faction. **Companions** are
characters somebody owns, which belong to no region and must not be counted as its population. **Players** are real people, whose presence is what
raises the countryside's density. It then prints what the region asks for both ways — quiet, and with somebody on it — so the gap the director
reports can be read on demand, on any map, rather than waited for in the log.

It reads the world rather than the roster or the database. The database lags a save sweep, up to five minutes; the roster says what should come back
after a restart, not who is standing there now.

## Two things worth knowing before handing this out

`clear` and `delete` destroy characters, and `populate` writes to the database once per bot. They are open to everyone because that is what was asked
for, and the account restriction means the damage stops at the bots — but there is no undo, and no confirmation. On a server with strangers on it,
`bot = 9` is the setting you want.

`populate` is capped at 20 per command for the same reason: creating a character is a database round trip, and a mistyped count must not be able to
hold the server up.
