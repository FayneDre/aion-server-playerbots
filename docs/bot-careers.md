# A bot's career

How a bot gains levels, where it lives as it grows, and what stops it growing out of its region. Split out of
[population.md](population.md), which it was outgrowing.

`Rates.XP_HUNTING` multiplies by the `BOOST_HUNTING_XP_RATE` **stat**, so a bot's pace is adjustable per character with no core patch. Dwell time is
therefore the control knob, and it is set to **1%** of the ordinary rate.

That number was guessed at a quarter and the guess was out by a factor of twenty five, which only a measurement could show. Two attempts to read it
from the database gave nothing usable: bots are saved on a five minute sweep, so a five minute window reads two saves or none, and a level read
afterwards says nothing about when it was reached. Logging each level at the moment it lands settles it in minutes.

What one percent produces in hours has **not** been measured, and the arithmetic cannot be done on paper because of the cap in `Rates.XP_HUNTING`:

```java
Math.min(xp * rate, player.getCommonData().getExpNeed() * 0.2f)
```

The cap is applied *after* the rate, so it binds at the full rate and not at one percent — which makes the two regimes behave differently in kind, not
only in degree, and an extrapolation from one to the other wrong. Two readings taken an hour apart disagree by a factor of two to three for exactly
that reason, and neither is worth writing down.

What is measured is the direction, over nineteen bots on one map:

| | level gains |
|---|---:|
| at 25%, per bot | one every 1 to 4 min |
| at 1%, whole population, 16 min | **one** |

That is the honest state of it. The number of hours a region takes comes from watching it over days, not from a calculation, and until it is watched
the population model's arrival rate is an assumption rather than a figure.

**One percent is the floor.** `StatSetFunction` carries an int, and zero is not a slower pace but no experience at all. Anything slower than a day per
region has to come from the director retiring a bot out of the region, not from the bot earning less.

Where a bot hunts comes from `quest_data.xml` read as an **itinerary**, not as quests: 8043 quests, every one carrying a minimum level, 93% carrying a
zone, and 4282 distinct npc ids between them. That answers "where does the game send a character of level 4, and what should it kill" as a file read —
no handlers, no quest state. Quests that *gate* progression are completed outright, as the ascension quest already is.

Every new character is given kinah, ten thousand per level, because the engine charges 25 000 to socket a stigma and a bot earns only by selling loot —
one created at level fifty has never earned a coin. **This mints kinah**, which costs nothing while bots trade only with the engine and is the thing to
look at again the day they trade with players.

## After a death

A bot comes back at its bind obelisk on a quarter of its health, under the soul sickness, which cuts every stat for minutes. Two answers failed before the
current one: walking straight back to the farm ground (a crippled bot among the mobs that had killed it, dying in a loop), and waiting where it stood
(every obelisk in the region filled with seated bots).

Now the bot walks to a **convalescence spot** (`BotConvalescence`) and waits there until the sickness ends, then returns to its anchor as usual. The
spot is 16 to 45 m from the obelisk, on ground the navmesh joins to it, with no hostile creature within 25 m and nobody standing on it. It is fixed to
the bot's id, so a crowd of simultaneous deaths spreads out, and it is chosen again at each death. A bot with no obelisk on its map, or one more than
150 m away, rests where it stands. A bot that is attacked still defends itself, and errands (selling, looting) still run. Not yet verified in game.
