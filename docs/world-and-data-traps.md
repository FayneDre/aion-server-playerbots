# Traps in the world and its data

The other half of [engine-traps.md](engine-traps.md). Those are things the engine *does*; these are things the world, its spawn data and the database
underneath it *are* — geometry that two systems describe differently, objects handed over half built, templates that rewrite themselves as you read
them.

What they have in common is not a mechanism but a cost: none of them announces itself. Each produced a day of symptoms that read as several unrelated
small bugs, which is why the measurement that settled each one is kept alongside it.

## Two systems describing the same world will disagree

And the disagreement will not announce itself. The navmesh generator dropped every wall while the engine's raycasts kept them; later, blocking reused a routine that clears a whole column, so a tree's canopy deleted the ground beneath it. Each produced a day of symptoms that looked like separate small bugs.

When bots misbehave near geometry, first ask whether the mesh and the engine agree. `NavmeshTool <mapId> path x1 y1 x2 y2` against what the bot actually does is the fastest way to find out, and `NavmeshTool <mapId> audit` now asks the question for a whole map at once.

**A routine that answers one question will be reused for another that merely sounds the same.** "What can a body stand on" is not "what blocks a body"; "forbid this column" is not "something solid stands here". Both cost a day.

## Standable is not reachable, and the difference costs a bot its life

A spot can be ground a body fits on and still lead nowhere: a ledge, a hollow, the far side of a low wall, a shelf five metres up. From inside one,
every destination is refused — including spots a pace away — so the bot asks for a route hundreds of times and never moves.

This was learned four separate times before it was written down, because **each placement is its own decision and none of them inherits the others'
checks**: scattering villagers round a village, choosing the point that stands for a place, putting a bot back on spawn, and picking a corner to idle
in. Every new place a bot can be put needs all three conditions asked again:

1. **a real spot** — a spawn the world already uses, never the average of several, which lands between them;
2. **standable** — the mesh accepts it, bounded in height, or the snap climbs a cliff;
3. **reachable** — a route exists from where the bot is, or from the place it belongs to.

And none of that survives contact with the map, because the bot then walks by itself and the reactive layer can walk it behind a rock. **Whatever the
placement guarantees, there must be a way out**: a bot refused every destination a dozen times running is put back home, the way players are given an
unstick command rather than advice.

## Nobody announces that a bot has moved

An aggressive monster does not watch the world; it is *told*. A player's own client sends `CM_MOVE` and the packet handler calls
`PlayerController.onMove()`, which notifies every npc in the known list; an npc's `NpcMoveController` calls the same hooks itself. A bot has neither a
client nor that controller, so nothing ever announced it. Monsters therefore re-checked their aggro only at the moment a bot first entered their known
list — hundreds of metres out, always too far — and never again as it walked straight past them.

Measured before and after: **zero** aggro decisions in favour of a bot over 1981 checks, against five over 7364 once `BotMoveController` called
`onStartMove` / `onMove` / `onStopMove` like everything else that moves.

This is the same trap as drawing a weapon or standing up, in its most expensive form yet, and it is worth asking of anything shared: **who tells the
rest of the world, and does a bot go through them?**

## Colours are stored blue first

A flesh tone of 224, 192, 168 written the obvious way round is read as a pale blue, and nothing in the code looks wrong: the value is right, the order
is not. A village of smurfs is the only symptom. Two things follow. **Absolute colours must be packed deliberately** — `BotAppearance.stored(r, g, b)`
exists to say so once. And **varying a colour by scaling every channel by the same factor is order independent**, which is why the shading code was
right all along while the constants beside it were wrong.

Whenever a byte order is in doubt, the database already holds a known good answer: an existing character's stored colour is one reading if you are
right and an absurd one if you are not.

## An index is not the truth

"Is this map populated?" was asked of the roster table, which is derived data. It can be emptied, rebuilt or lost without a single character moving —
and when it was, a map of forty five inhabitants was judged empty and populated a second time, leaving ninety. Ask the thing that *is* the state
(`players`), not the thing that indexes it.

The same shape as the `null` traps above: two different situations were giving the same answer, and the code picked the wrong meaning.

## A half built object is what the creation path hands you

`PlayerService.newPlayer` builds only what character creation has to store. The effect controller, the known list, the flight controller and the
predefined stat functions are all still null, and `PlayerService.getPlayer` is what fills them. A client never sees that object either: it returns to
character selection and enters the world through the load path. **Anything made programmatically is stored and then read back**, or the first passive
skill applied to it dies on a null controller.

## The database truncates what does not fit, and says nothing

`server_variables.value` is a `varchar(30)`. A comma separated list of bot names fits three of them; the fourth was cut off by MySQL, so a population
of forty five came back empty and the cause was a `MysqlDataTruncation` two hundred lines up the log. **State whose size grows with the number of bots
gets its own table**, keyed by character id with a foreign key onto `players`, so deleting a character takes its row with it.

## A getter in the data layer can be a write

`QuestKill.getNpcIds()` moves its ids into a second list on the first call, clears the first and nulls it. It reads as an accessor and is a lazy
migration of shared state, so two threads calling it at once leave one of them clearing a list the other has already taken away — a null pointer
inside the engine, from a method whose name promises a read.

Nothing in the engine meets it, because a quest template is reached one player at a time. Bots meet it on the first populated map: eight tick threads
scanning 8043 templates for different level bands at once. **Any sweep of the static data from a bot thread is serialised**, and once a template has
been read it is in its settled state and safe for everyone for the rest of the run.

The general shape is worth keeping in mind: this engine's template classes were written for a single reader and lazily build whatever is expensive.
A bot module reads the same data from a pool.

## A character read back from the database knows the skills of level one

`PlayerService.newPlayer` teaches skills from level 1 to the level the character has **at that moment**, and a bot is created at 1 and then given its
experience — so a level-30 templar loaded from the database holds level-1 skills and nothing else. Nothing since repairs it: `onLevelChange` only teaches
levels actually gained, and a bot gains none while it is not in the world.

What depends on that quietly is the outfitter: it will not put armour on a character that does not already hold the mastery for it — the same question the
engine asks when it refuses a piece — so dressing before teaching leaves the character in its starting kit and says so at `debug` level only. Teach, then
dress. This held for as long as it did only because teaching happened on the way into the world, which was once the only way a bot was ever dressed.

## A creature's level does not say whether one character can fight it

`rating` does, in one word, and nothing else in the template hints at it. An `ELITE` of a bot's own level is several times the creature a `NORMAL` one
is, so every level-based filter passes it — which is how bots came to walk into the group quest camps and be torn apart. 16401 spawns are `ELITE`,
6712 `HERO`, 1994 `LEGENDARY`: a quarter of Morheim and of Beluslan.

Refusing to **attack** them is not enough, and the reason is in the same enum: `NpcRating` maps `ELITE` to `SEARCH1` and `HERO` and `LEGENDARY` to
`SEARCH2`, so these creatures look for somebody to fight rather than waiting to be provoked. A bot that merely stood in such a camp was attacked
without having chosen anything. They have to be kept out of the grounds bots are **sent** to as well — `BotPlaces.locateHuntingGrounds` and
`BotQuestGrounds`.

## Rift, base and siege spawns are not in the world's spawn list

`SpawnsData` files them into indexes of their own (`riftSpawnMaps`, `baseSpawnMaps`, `siegeSpawnMaps`), and `getSpawnsByWorldId` reads only
`allSpawnMaps`. So anything walking the world's spawns sees none of them: the Asmodian camp in Theobomos and the Elyos one in Brusthonin are invisible
to the population code, which is correct for settling residents and wrong the day bots are meant to invade. `getRiftSpawnsByLocId` is the other door.

## Quest data does not say which quests need a group

`category` separates a mission from a task from an event and never mentions party size, so reading `quest_data.xml` as an itinerary sends a lone bot
wherever the game sends five players. What answers the question is what the quest points at: a group quest names elites, so the target's `rating` is
the filter, not anything on the quest.

## No armour template states its faction

A bot was seen as a cleric of thirty with only its head and arms visible, wearing a "Defeated Guardian's Hauberk", greaves and boots. The pieces are
Asmodian, but **not one armour template in the table declares a race** — the outfitter's race filter, which works for weapons and accessories, has
nothing to read. The faction lives only in the model name the client is told to draw, and an Elyos body has no Asmodian model, so the part vanishes.

The third segment of `cName` is the faction: `ch_torso_d_n_c1_light_30a` against `ch_torso_n_c_10a`. Verified against zones rather than assumed —
every Altgard, Morheim and Pandaemonium piece is `d`, every Verteron, Eltnen and Sanctum piece is `n` or `g`, nothing crossing over. Other markers
(`a`, `npc`, `t`) are left alone: there is no evidence they belong to one side, and excluding them would strip gear to fix nothing.

`cName` was not mapped on `ItemTemplate` at all and had to be added to read it.
