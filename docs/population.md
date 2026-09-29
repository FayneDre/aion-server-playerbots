# Populating a map

How a region ends up with inhabitants, and why each decision is the way it is. For what bots then *do*, see [roadmap.md](roadmap.md); for the traps met on the way, [engine-traps.md](engine-traps.md).

## It happens by itself

`gameserver.playerbot.populate` in `config/main/playerbot.properties` is the whole of the setup:

- `210010000:45:ELYOS` — a list of `<mapId>:<count>:<race>`, semicolon separated. **The shipped default**, Poeta alone, which is the map this has been proven on.
- `auto` — every open world map of both races. Which maps those are, which race lives on each and how many people each holds are all read from the world.
- empty — populate nothing.

Carried out by `PlayerBotService.onStartUp()`, which `GameServer` calls **before the login server is connected**, so before anyone can log in. A map that already has bots is left alone, so this runs once on a fresh installation and is silent on every start after.

A missing navmesh is generated first, in the same startup (`NavmeshBuilder`). It costs about a gigabyte and some seconds — nine for Poeta — and it is done synchronously on purpose: bots on a map without one cannot plan a route, so they spend their first minutes walking into scenery and being rescued. A map is either ready or it is not.

Nothing else is required. No character to copy, no command to type, no row to write by hand. **The database schema is not required either**: `SchemaUpdater` compares it against the reference schema carried inside the jar and adds what is missing.

## Where people live

A **place** is somewhere the world already put something, found by clustering spawn data (`BotPlaces`):

- **Settlements** — clusters of peaceful npcs. Poeta has four.
- **Hunting grounds** — clusters of at least eight hostile creatures. Poeta has fifty six.

Each place carries the level of what lives there and how many npcs it holds. A settlement counts once per eight townsfolk, so a village draws more inhabitants than a roadside camp; a hunting ground counts once.

Places are ordered so that each is as far as possible from those already taken. That matters because **there are always more places than people** — Poeta has sixty and is given forty five — so whoever populates stops partway down the list, and what the list is ordered by decides what is left out. Ordered by size, the tail was the small outlying grounds: the lake and the farms had nobody while the same few busy fields had somebody each.

Then the list is cut to `count / BOTS_PER_PLACE` entries and walked round twice. At 1.5 that is half the places with a pair and half with one. **A lone character standing in a field reads as a stray npc; two working the same ground read as players.** One per place everywhere was the first attempt and looked wrong; two everywhere emptied two thirds of the map.

### Three conditions for a spot

Learned four separate times, once per place a bot can be put — see [engine-traps.md](engine-traps.md):

1. **A real spot**, a spawn the world uses, never the average of a cluster, which lands between its members and hangs in the air.
2. **Standable**, accepted by the mesh, bounded in height — an unbounded snap put a villager on a clifftop seventy metres above the village.
3. **Reachable**, with a route from where the bot is or from the place it belongs to. Good ground that leads nowhere is worse than none: from inside, every journey is refused.

And since a bot walks by itself afterwards, none of that is enough on its own. A bot refused every destination a dozen times running is put back home.

## Who they are

**Level comes from the place**, not from the map: a map is one number, and living by it is what put a character of two in a forest of eights. Class is drawn from those a character of that level could actually be — below ten, only the four starting classes exist.

**Appearance is built, not copied** (`BotAppearance`). Cloning a character was the first way this worked and it made the whole system depend on somebody having played the server first. A blank appearance was thought unusable because it renders as nothing, but height is the only field where zero is meaningless; every other one is a slider about a neutral middle. So:

- colours are drawn from small palettes of real complexions and hair colours, then shaded — every channel by the same factor, so the hue survives and only the shade changes. **Colours are stored blue first.** Writing a flesh tone the other way round turns a village into smurfs, and nothing in the code looks wrong.
- head and hair models come from what the creation screen offers; a number outside that is a missing face, not a different one.
- sliders are nudged, never redrawn. Random ones give gargoyles.
- height moves least of all — its extremes are the ones that read as broken rather than as somebody else.

Gender is drawn per bot, whatever the server was given.

**Gear is fitted to the class and level** (`BotOutfitter`), and the hard part is telling gear made for players from the rest. The item table holds guard equipment and development leftovers — `NPC Veteran Guard Chain Shoes Cleric` at level 65, `Test Spaulders Level 35` — and nothing refuses them: they declare no level restriction, so they ask level one of every class, and they sit in generic groups naming no armour type, so no mastery is needed either. An outfitter sorting by item level picks them first. Two tests exclude them: the group must name a mastery, and the piece must declare its own level restrictions (`ItemTemplate.hasLevelRestrictions`).

Below level four there is barely any gear made for players at all, so those bots keep the training kit every character is created in — which is what a beginner wears anyway.

## What they then do

An occupation is drawn with a duration and drawn again when it runs out, leaning on a temperament fixed to the bot's own id so each keeps a character (`Occupation`):

| | |
|---|---|
| **Farming** | hunting around home, 5 to 15 minutes |
| **Loitering** | standing about, 3 to 10 minutes |
| **Wandering** | visiting somewhere else, 2 to 6 minutes |

**Loitering belongs to villages.** It was first sent to the nearest village, which piled everyone within walking distance into Akarios; moving it home instead put a third of the population standing about in fields. Out in the country it is now four times rarer and four times shorter. Each idler goes to a corner of its own — fixed to its id, clear of npcs, reachable — because one point per place put them inside each other and inside the blacksmith.

**Wandering is bounded**, to 250 m and to places within four levels: the walk crosses everything in between, which is how a character of two came to be standing in a forest of eights. Residents found more than 150 m from home at startup are put back.

**Residents gain no experience** (`PlayerCommonData.setNoExp`). Left to progress, every bot drifts upwards and the low regions empty — five reached thirteen in a day farming grey mobs in a valley meant for beginners.

## What is kept between runs

`playerbot_characters`, keyed by character id with a foreign key onto `players`, so deleting a character takes its row with it:

| | |
|---|---|
| `resident` | fixed at its region's level, or an adventurer that levels |
| `in_world` | put back on the next start |
| `home_x/y/z` | where it lives |

Everything else — position, inventory, skills — is saved with the character itself.

The roster is an **index, not the truth**. Whether a map is already populated is asked of the characters (`PlayerBotCreationService.hasBotsOn`), because an index can be emptied or rebuilt without a character moving: asking it instead is how a map of forty five inhabitants was judged empty and populated again, leaving ninety.
