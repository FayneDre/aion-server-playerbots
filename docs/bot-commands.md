# The `//bot` command

Everything the bot system exposes in game, in one place. `//bot` on its own, or `//bot help`, prints the same list in the chat window.

**Anyone may use it.** `bot = 0` in `config/administration/commands.properties` is what says so — the access level is compared with
`accessLevel >= 0`, which every account satisfies, so no code decides this and changing that one number is the whole of the permission. Set it back to
`9` to make it staff only again.

**Unless the system is off.** `gameserver.playerbot.enable = false` refuses every one of these with one line, because with bots disabled there is no
save sweep and no director: a bot spawned by hand would run unmanaged and lose whatever it did at the next shutdown. The setting takes effect at
startup and leaves every bot character in the database untouched.

The prefix is `//` even for a regular player: the chat processor looks a command up by name and only then asks whether the caller may run it. Uses are
recorded in the admin audit log when `LOG_GMAUDIT` is on, which is worth leaving on — it is the only record of who emptied the world.

## What to expect of a name

Most of these take the character name of one bot. They act on a **spawned** bot and answer `No bot spawned with name X` otherwise, which is the same
answer you get for a name that never existed — the command cannot tell those apart. `//bot list` is how you find out which names are real.

## Populating a world

| Command | What it does |
|---|---|
| `//bot populate <count> [race]` | Creates `count` inhabitants for the map you are standing on, up to 20 at a time, and leaves them **asleep**. With 0 it creates the busy plan — the most the region can ever ask for, so the surge has somebody to wake. Their levels come from the creatures living in each place, their classes are spread over what a character of that level could be, and each is fitted with gear that suits it. Without a race, each place names its own, read off the civilians standing in it: a faction's own region comes out all one side, and contested ground — Reshanta, Silentera, Kaldor, Levinshor, Panesterra — comes out split the way the world splits it, Teminon Elyos and Primum Asmodian. Naming a race overrides the lot. |
| `//bot create <name> <class> <level> <race>` | One bot, made to order, with a face of its own. Class names are the engine's own (`WARRIOR`, `GLADIATOR`, `CLERIC`…), race is `ELYOS` or `ASMODIANS`. It is created and stored, not spawned, and it is already dressed for its level, with the enchantment, manastones and stigmas a populated resident gets, and holds the skills of that level. |
| `//bot delete <characterName>` | Deletes the character from the database. Refuses anything that is not on a reserved bot account, so a mistyped name cannot reach a real player, and refuses a bot that is still spawned. |
| `//bot clear [here\|mapId\|name]` | **Deletes bot characters**, not just their presence — with no argument, every one of them on every map. Same account restriction as `delete`. Name a map and only that map's inhabitants go, counted from where each one actually is: in the world for a bot that is awake, from the database for a sleeper. This argument exists because `clear` is global and `populate` is not — it fills the map you are standing on — so the pair emptied a whole world and refilled one region of it. |

A map normally populates itself on first start from `gameserver.playerbot.populate` — see [population.md](population/how-it-works.md). `populate` is for adding to
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
| `//bot gate [characterName]` | Has a sorcerer bot open a gate to its faction's capital at its feet (Summon Rift, level 22, a minute's cooldown, no item): the one named, or with no name the sorcerer bot in your group. Says why it cannot: not a sorcerer, the skill not learnt or cooling down. The guide's proposal, [sorcerer-plan.md](classes/history/sorcerer-plan.md). |
| `//bot fly <characterName> [height]` | Flies the bot up on a slant, holds it there four seconds, and lands it again where it stood. 25 m by default, capped at 60 and at its own fly zone's ceiling. Refuses with the reason: not a daeva, not in a fly zone, fighting, or short of the flight points the climb *and the landing back down* would cost. |
| `//bot flyto <characterName>` | Flies the bot to where **you** stand, climbing over whatever is in the way and landing on the walkable ground nearest your feet. The one way to aim a bot at a terrace, a ledge or a rooftop it has no way of walking to. Refuses past 300 m, and refuses any path that would leave its fly zone — a body that leaves one in flight is dumped out of flight and written into the audit log. |
| `//bot land <characterName>` | Brings a flying bot down now, straight onto the ground beneath it, wherever it has got to. |
| `//bot duel <characterName>` | Answers a duel request you have already sent, at once. A bot answers one by itself within a second (`BotRequests`): yes when it is free, within 10 levels of you, outside an instance and has not duelled you in the last 3 minutes. |
| `//bot give <characterName> all\|kinah <amount>\|<item name>` | Your companion's half of a trade you have opened with it: puts everything it carries but wears, an amount of kinah (all of it with no amount), an item linked in the chat (shift and click, whatever the client's language), or the items whose **English** name holds the words given, on its side of the window. Name what it gives **before you lock**: it locks once you have, and confirms once you have. Only for your own companion; any other bot refuses a trade. |
| `//bot auto <characterName>` | Turns autonomy off and on. A bot with autonomy off only does what it is told; it still defends itself. |

Flight is at its first milestone: a bot can leave the ground and come back, and nothing decides to do it on its own yet. The landing is flown rather
than fallen, because falling is client side and a bot out of flight points would otherwise hang in the air for ever — see
[flight-plan.md](flight/history/flight-plan.md).

## What a bot is, and what it carries

| Command | What it does |
|---|---|
| `//bot kind <characterName> [resident\|adventurer]` | With no third word, says which it is. A **resident** gains no experience, so the region it lives in keeps inhabitants of its own level; an **adventurer** levels normally. Takes effect on the next spawn, and on a spawned bot straight away. |
| `//bot bag <characterName>` | What the bot is carrying, and its kinah. Read from the bot in memory, not from the database, which only ever sees a bot when it despawns. |
| `//bot sell <characterName>` | Sends the bot to a shop now instead of waiting for a full bag. Says so if it has nothing worth selling, or if the map has no shop. Refused for a player's own bot: nothing in a companion's bag is ever sold, put on, identified or bound by the bot itself. |
| `//bot regear <characterName\|all>` | Brings a bot up to what it would be given if it were created today: takes off anything it should not be wearing — the other faction's gear, npc costume, event pieces, anything that expires, armour of the wrong type, a weapon its class does not fight with — and fills every place still empty. It also tops up the four speed scrolls (running, flight, attack, casting: 20 of each, the strongest its level reads), which no vendor sells and creation hands over too. A bot is dressed once, at creation, so a world settled before a change to the wardrobe never sees it otherwise — and the only alternative was deleting the population and building it again, losing every level, home and name. `all` does every spawned bot and saves each as it goes, so an interrupted pass keeps the half it finished. See [bot-gear.md](gear/how-it-works.md) and [bot-gear-obtainability.md](gear/bot-gear-obtainability.md). **Leaves a player's own characters alone**, named or under `all`: a companion made with `//bot create`, or a character made in game and spawned as a bot, keeps exactly the gear, enchantments and stones its owner gave it, and the command says so. |

## Diagnosis

| Command | What it does |
|---|---|
| `//bot nav` | Which maps' navigation meshes are loaded in memory. A map missing here is one where bots will not travel; see [navmesh-plan.md](navigation/history/navmesh-plan.md). |
| `//bot number [region]` | How many inhabitants a map holds, by faction, and what it asks for. A map id or the start of a map's name; yours by default. |
| `//bot pool [map\|here]` | What the last population review concluded: how many inhabitants are awake, how many exist, and how many the region wants right now — split between the villages and the countryside, because the two are filled differently and only the split says which half is short. A line per map, or just the one asked for, by id or by part of its name; `here` means the map you are standing on. |
| `//bot position [auto\|standard\|spread]` | Reads or sets when grouped bots stand where their class belongs in a fight (tank on the far side, melee behind the enemy, ranged back from it): `auto` against a boss only, a hero or legendary monster, `standard` never, `spread` always. Otherwise melee walks into contact and ranged classes close to their reach. Takes effect at once and lasts until the next restart, when `gameserver.playerbot.positioning` applies again. `//bot positioning on\|off` still works and means `auto\|standard`. The log samples how well placed they are every 200 looks either way, so two modes can be compared in the same session. |

`//bot number` counts three things apart, and the distinction is the point of it. **Inhabitants** are the world's own, by faction. **Companions** are
characters somebody owns, which belong to no region and must not be counted as its population. **Players** are real people, whose presence is what
raises the countryside's density. It then prints the three figures the director works to: what the region wants at this moment, what it keeps awake
with nobody online at all, and what it holds with a player on it — so the gap the director reports can be read on demand, on any map, rather than
waited for in the log.

Those are targets, not stock. Earlier it quoted the **establishment** — every inhabitant the region has a character for — which reads as a figure the
map is permanently short of, because the countryside is only ever awake in the share attention justifies: Poeta was quoted as asking for 19 while the
review it answers to wanted 10.

It reads the world rather than the roster or the database. The database lags a save sweep, up to five minutes; the roster says what should come back
after a restart, not who is standing there now.

`//bot pool` is the other half of that, and the one to reach for first now that most of a population is asleep: `//bot number` can only count what is
in the world, so a map reading 12 inhabitants tells you nothing about whether it holds 12 or 60. The review runs every 30 seconds, and the first one is
half a minute after startup — before that the command says so rather than printing an empty table. A map whose line never moves is a map at its target;
a map short of its target for review after review is usually one where somebody is standing in the only place the missing inhabitants live, which is a
refusal by design and not a fault. See [population.md](population/how-it-works.md).

## Testing

Made for test sessions, and for whoever reads their log afterwards; the plan is [test-harness-plan.md](architecture/history/test-harness-plan.md).

| Command | What it does |
|---|---|
| `//bot note <text>` | Writes a note to the server log the moment something looks wrong: one `BOT NOTE #n` line with the text and where you stand, then one line per bot in your group (or within 30 m when you have none) with its class, role, health, mana, divine power, state, cast in progress, target, the target's health and whom the target is on. `tools/collect-feedback.ps1` cuts the minutes around each note out of a log. |
| `//bot squad [form <leader> <member>... \| disband <name>]` | Bot-only groups, for watching a group fight with no player in it. `form` brings the members to the leader and groups them under it; the leader keeps playing on its own, pulling round the place it stood (its camp), and waits before each pull for every member to be alive and rested; the members follow and assist it as they would a player. A squad is never put to sleep by the director, nor dissolved for having no player. `disband` and any member's name ends it; alone, the command lists squads with each member's health and mana. Same race, six at most. |
| `//bot version` | The revision, branch and build date the server runs. A report names it, so the fix it is about can be found. |
| `//bot debug [combat\|all\|off]` | Logs the detail of the bot module until the next restart: `combat` (or `on`) is every cast with its target and every fight started and given up, `all` is the whole module. `off` gives back what `logback.xml` set. |
| `//bot speech [on\|off]` | Reads or sets whether bots speak in the capitals, by shout, heard round the speaker: a store keeper calls out its store every 2 to 4 minutes, a resident of 20 and over asks for a group every 3 to 6 minutes, only near a real player, never the same bot twice in half an hour. Covers the chat channels too ([chat-channels-plan.md](social/history/chat-channels-plan.md)). `off` lasts until the next restart. See [capital-life-plan.md](capitals/history/capital-life-plan.md). |
| `//bot chat <region\|trade\|group> [text]` | Has the bot nearest you, of your faction and on your map, say a line in the Region, Trade or Find Group channel of its faction: a fixed test line, or the text given. Needs the chat server connected (`gameserver.chatserver.enable` and the chat password in `mygs.properties`), and goes nowhere if nobody reads the channel. See [chat-channels-plan.md](social/history/chat-channels-plan.md). |

**Without a game client.** The server reads `bot-commands.txt` in its `game-server` folder every three seconds and runs each line as the words after
`//bot`, answering in the log as `BOT INBOX`. `tools/bot-command.ps1 '<command>', ...` appends the lines and prints the answers. There is no caller, so
it takes only `squad`, `note`, `version`, `debug`, `position`, `list`, `create` (the bot then belongs to the world), `despawn`, `delete`, `stop`,
`auto`, `fly <name> [height]`, `land <name>`, `populate <map id> <count> [race]` (asleep, like the command; 0 is as many as the map still asks for), `number <map>`, `pool [map]`, `kind <name> [resident|adventurer]`, `clear <map>` (one map only: with no caller there is nobody to stand on a map, and a global clear from a file is one typo away), and three of its own: `attack <name> <monster name>`, at the nearest monster of that name the bot sees whatever its level (a squad leader picks no
boss by itself), `spawn <name> near <bot>`, next to a spawned bot instead of a player, `teleport <name> <map> <x> <y> <z>`, which
also makes that place the bot's camp, `flyto <name> <x> <y> <z>`, `//bot flyto` aimed at coordinates instead of at the commander, and `goto <name> <x> <y> <z>`, `//bot come` to
coordinates, which crosses the Abyss by its flight links. A `note` from the inbox describes every bot in a squad, since there is no caller to stand next to. Anyone who can write to the server's folder could already do anything to it, so the file opens nothing new.

## Two things worth knowing before handing this out

`clear` and `delete` destroy characters, and `populate` writes to the database once per bot. They are open to everyone because that is what was asked
for, and the account restriction means the damage stops at the bots — but there is no undo, and no confirmation. On a server with strangers on it,
`bot = 9` is the setting you want.

`populate` is capped at 20 per command for the same reason: creating a character is a database round trip, and a mistyped count must not be able to
hold the server up.
