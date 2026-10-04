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

## A coarse cell carries every stretch of ground that touches it

The mesh keeps, for each 4 m cell, the ids of the walkable stretches passing through it — derived from the fine regions, so "the rough grid knows what
the fine grid keeps apart". That makes it a cheap way to ask whether two places are joined: `NavmeshService.regionsAt` reads it without touching a tile.

**It is necessary and not sufficient, and the gap is vertical.** A cell covers a 4 m column and carries every stretch in it, at every height. Measured
at Eltnen's village square, 268 2730: four walkable surfaces stacked at z 240, 270, 276 and 294. Were the villagers on a floor other than the one the
mainland reaches, the cell would carry the mainland's stretch and the test would say yes about a floor that is not on it. No refinement of the grid at
ground level reaches that — the ambiguity is between floors, not between neighbours.

**Asking for a route does not close the gap, which is worth knowing before writing one.** Past `LONG_DISTANCE` a search is planned on that same coarse
grid and refined stretch by stretch, so every way the plan can fail is reported as a search that gave up — honestly, since one blocked corridor is no
proof that another does not exist. The only refusal a long search can therefore *prove* is the one the grid already settles. Measured: 152 route probes
across Eltnen took 73 seconds and proved nothing new.

**What does close it is asking the opposite question.** Not "can this place be reached from over there", which needs the whole map, but "how much ground
does this place have" — a flood fill over the fine surfaces, bounded, which either runs out of ground (a proof that nothing walks off it, whatever the
column overhead says) or passes the bound. Eltnen's 152 places cost 397 ms that way, against 73 seconds, and `BotPathFinder.isPocket` carries the
reasoning. The two tests are complementary and both are kept: the grid names a large island that votes for a region of its own, the fill names a pocket
too small to be anywhere.

**Its bound had to be measured, and the first one chosen was wrong by an order of magnitude.** Ground comes in two clearly separated sizes: 23, 79, 283
and 615 m² for the pockets — a rooftop, a shelf, a ravine floor — against 4560, 4664 and 12127 m² for the whole settled part of a small map, shared by
two or three places and perfectly liveable. A bound of 12500 m² read those three as pockets and emptied two maps of every place they had. 1000 m² sits
in the middle of the gap. **A number invented for a threshold will be wrong in whichever direction nobody measured.**

**And the islands themselves are not a fault.** Reading 19299 stretches on one map as a broken mesh was wrong. Eltnen, Verteron and Poeta all carry
the `GLIDE` flag, and the mesh describes the terrain correctly: a map of cliffs, terraces, rooftops and ledges has thousands of walkable islands by
construction, because a player reaches them through the air. They are islands *to something that walks*. So the question a bot asks is never "is this
ground valid" but "can I get there on foot", which is a fact about the bot and not about the map — and one that stops being true the day bots glide.
See `docs/roadmap.md` under Flight.

## A coordinate that has been to the database is not the number you wrote

`home_x/y/z` are `float` columns, and a coordinate written to one does not come back bit for bit. Measured in the live log: a village centre held in
memory as `270.65002` read back out of the pool as `270.65`, with x and y identical. One bit, in the last place, on one of three components.

`Vector3f.equals` compares with `Float.compare`, so those two are not the same place. Nothing warns you, because the two values print almost alike and
every arithmetic use of them agrees.

**What it cost.** `BotPlaces.isSettlement` asked that question by equality, so **98 of Eltnen's 102 residents were classified as living in open country
while their homes sat exactly on a village**. From there: the villages read as empty and were permanently short; the countryside read as massively
over-supplied; and the population director's two rules then ordered opposite things about the same bots — asleep for being surplus in the field, awake
for being needed in a village — every thirty seconds. 2200 world entries and exits in 97 reviews, with the net population unchanged. The fix that
moved sleepers into the short villages wrote the right value on every review and read back the other one, for ever.

**The rule.** *A world coordinate that has crossed the database is never compared for equality, only for nearness.* `BotPlaces.SAME_PLACE` is that
tolerance, at one metre — no two places the question is asked about are within a metre of each other, and no round trip moves a coordinate by anything
like it. A caller that keys a map by place must use the centre `BotPlaces.settlementAt` returns, not the coordinate it passed in, or the two spellings
of one village become two entries and every lookup made with the other one misses.

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
