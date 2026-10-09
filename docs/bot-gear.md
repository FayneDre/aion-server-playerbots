# What a bot wears

A bot used to be dressed from the whole item table, filtered only by what the engine would refuse anyway, and
ranked by item level. That produced characters in white and green gear with six empty accessory slots, no
enchantment and no manastones — legal, and roughly half the character a real player of that level would be.

This is the plan for the rest of it, and the reasoning behind each number. It comes from a player's write-up of
how these classes are actually geared; the engine facts beside it were measured here.

Three questions decide a piece: can this bot reach it, is it this bot's faction — both in section 1 — and is
it better than what it wears, which is the rest of this file.

## 1. Obtainability and faction

Both live in [bot-gear-obtainability.md](bot-gear-obtainability.md): the four tables a player reaches gear
through, the four kinds of item that are vetoed outright, and how the faction is read off a model name.

## 2. Stat weights per class

Ranking by item level put a dagger one level newer in a gladiator's hands. Ranking by what a piece actually
grants means reading `ItemTemplate.getModifiers()` — a list of `StatFunction`, each a `StatEnum` and a value —
and weighing them per class.

The orders below are the author's, by class and in priority order:

| Class | Order |
| --- | --- |
| Templar | physical crit (to 500), block / magic resist, HP |
| Gladiator | physical crit (to 500), attack, parry / magic resist |
| Assassin, Ranger | physical crit (to 500), attack |
| Chanter | physical crit (to 500), parry |
| Cleric | MP, HP |
| Sorcerer, Spiritmaster, Bard | magical accuracy, magical boost |

Physical classes in general: physical crit, attack, HP, accuracy, evasion.
Magical classes in general: magical crit, magical boost, magical accuracy, HP, MP.

Two rules bend those orders with level:

- **Physical crit soft-caps at 500**, which is 50% against a target with no crit defence. Past it the weight
  goes to zero and the surplus moves to the next stat in the class's order.
- **Magical accuracy is worth little before level 50** and rises after, because landing debuffs is what
  high-level content asks for.

Accessories are the main source of magic resistance, which is the whole reason for filling those seven slots
from level 20 rather than leaving them empty.

Gunner and Aethertech have no authored order — the source says so plainly. Both cast as far as the engine is
concerned, so both take the generic magical order until somebody who plays them says otherwise.

**A weighted sum needs the stats on one scale**, or it is the item-level proxy in disguise: the game hands them
out in wildly different sizes, so summing them raw makes any piece with HP beat any piece with attack whatever
the class wants. Each stat is therefore divided by the median amount a piece of level 20 to 50 grants of it,
measured over about 100k modifiers — physical crit 31, attack 16, accuracy 60, magic resist 59, HP 117, MP 181,
evasion 80, magical boost 26, magical accuracy 28, block 61, parry 33, magical crit 9. A score of 1 means
"about as much of this class's first stat as a piece of that level usually carries". That map is also the
whitelist: a stat not in it scores nothing, which keeps the resistances and the pvp ratios out of a judgement
with no business weighing them.

Percentages go through the same mill, divided by their own measured median — movement speed 22, attack speed
17, hostility 18, damage reduction 40, flight speed 8, casting speed 9. They are kept *out* of the per-class
order, though, and given fixed weights instead: attack speed is not what makes a gladiator a gladiator, it is
good for everything that swings in the same measure, so threading it into an ordered list would push the stats
that do define a class down a rank for nothing. See section 3 for what they are worth.

**Read the sign the client shows, not the sign in the file.** Attack speed is stored as a reduction of the
delay between swings, so a weapon advertised as "+19% attack speed" carries the value `-19`, while every other
stat here is stored the way it reads. Taken at face value a bot would have hunted for the *slowest* weapon it
could find, and most deliberately for the classes that care most. `StatEnum.getSign()` states the direction,
and `modifier.getValue() * stat.getSign()` is the same expression the item tooltip is built from.

**Where the score sits in the ranking decides everything.** It goes *below* the item level, not above: a
piece's level is what says how much of everything it carries, so a level 25 ring with crit really is worse than
a level 40 ring without, and ranking on stats alone picks the 25. Below the level it still decides nearly every
choice there is, because gear arrives in level steps and a shortlist is mostly pieces of one level that differ
only in what they give.

The Haramel accessories are the clean test, since each comes as a "Jewel" and a "Crystal" of the same level
differing in one stat, and they are the four pieces the source names. Scored, every one lands on the class it
was named for:

| Pair (level 20–21) | Jewel grants | Crystal grants | Jewel wins for | Crystal wins for |
| --- | --- | --- | --- | --- |
| Ring | +8 physical crit | +10 magical boost | all physical | cleric, sorcerer, gunner |
| Necklace | +18 accuracy, +42 HP | +10 magical boost, +42 HP | all physical | cleric, sorcerer |
| Earrings | +28 accuracy | +11 magical accuracy | all physical | sorcerer |
| Belt | +19 accuracy | +7 magical accuracy | all physical | sorcerer |

## 3. Quality, by level

`BotOutfitter.BEST_QUALITY` was `RARE` in a constant, on the grounds that a village of people in heroic armour
reads as a costume party. That is true of a village of beginners and false of everybody else — **a character
past the middle twenties still in green is undergeared, not modest** — so the ceiling now moves with the level:

| Level | Ceiling | What that opens |
| --- | --- | --- |
| below 26 | `RARE` (green) | as before |
| 26 and up | `LEGEND` (blue) | bronze-coin armour, "Elite Rank 7"; movement speed on boots, flight speed on torso |
| 36 and up | `UNIQUE` (yellow) | silver-coin armour, "Sun Legionary"; attack speed |

It stays a ceiling and not a floor: below those levels a bot still comes out mostly in green, because little
else exists down there.

**What the percentages are worth.** They are not class identity, so they sit outside the per-class order with
fixed weights, varying on only two questions — does the character hit or cast, and does it want to be hit:

| Stat | Weight | Why |
| --- | --- | --- |
| Attack speed | 0.9 hitting, 0.2 casting | the stat the yellow tier adds, and the reason to reach it |
| Casting speed | 0.9 casting, 0.2 hitting | the same thing for the other half of the roster |
| Movement speed | 0.6 everyone | a bot that cannot keep up with its group is a bot not in the fight |
| Flight speed | 0.4 everyone | the same, in the air |
| Damage reduction | 0.8 tank, 0.4 otherwise | shields only, and it rises with quality by itself: 30/35/40/45% |
| Hostility | ±0.5 | sign depends on the class, below |

**Hostility is the one stat whose sign is a matter of taste.** Plate carries a positive aggro bonus and cloth a
negative one, which is the game handing each class the help it wants. Templar, gladiator and aethertech want to
be hit; everything else would rather the monster looked elsewhere, so the weight is negated for them and a
robe's −30% is worth as much to a sorcerer as a breastplate's +30% is to a templar.

That list is *not* `BotRole`, and the gladiator is the difference. It is filed under damage there, correctly —
putting two classes on the taunts means two bots pulling the same monster in opposite directions — but it still
wears plate and still picks things up when the templar loses them.

**The right kind of piece is a filter, not a preference.** Sorting the right answers first was taken to settle
the armour type and the weapon, and it settles neither: only the first eight candidates survive, a wrong piece
that sorts second is still handed out when the first is refused, and the loot path ranked purely on score. A
cleric of 13 fought with a staff, a templar of 37 wore leather gloves, and the comparison window counted cloth
of the same level as an equal. `BotGearFit` now answers both questions on both paths:

- **Armour**: the heaviest type the class is *trained for today* and nothing lighter — plate for templar and
  gladiator, chain for cleric and chanter, leather for the scouts, robe for the casters, and one step down for
  a character too low to have been taught its own type yet. A class may legally wear everything below its own,
  which is exactly the freedom no player uses. The generic `ALL_ARMOR` groups declare no type and so never
  match; that is intended, since almost nothing a player reaches is in one.
- **Weapons**: `weaponsOfTrade` only. Dressing keeps a fallback — a class too low to have earned its own weapon
  goes out with something rather than empty-handed, because no weapon means no weapon skills — but looting has
  none, so nothing a bot picks up can displace the weapon of its trade.

**Uniformity is the risk this creates.** Choosing the single best piece for a stat puts a whole region in the
same boots. The existing trick holds: sort so the right answers come first, then pick at random *among the
answers that tie*.

## See also

- [bot-gear-upgrades.md](bot-gear-upgrades.md) — enchanting and socketing a piece once it is chosen.
- [combat-skills.md](combat-skills.md) — what a bot does with the weapon this gives it.
- [group-roles.md](group-roles.md) — where the hostility split comes from.
- [stigmas.md](stigmas.md) — the other half of a character's build.
- [world-and-data-traps.md](world-and-data-traps.md) — the filter that hid accessories, and the `cName` shapes.

## Characters that belong to a player

The gear of a bot somebody owns, meaning a companion made with `//bot create` or a character made in game and spawned as a bot (`PlayerBotAI.isOwned`),
is its owner's. `//bot regear` skips it by name and under `all`, and the stigma fitting on entering the world leaves its sockets alone. What it may do by
itself is the one thing a player would: put on a piece it has found **when that piece is better than what it wears** (`BotErrands.dressUp`). The piece goes
on as it is. `BotEquipManager.wear` does not enchant it or socket manastones into it for a bot that is owned, which is what the world's own inhabitants get
and a player's character does not. Looting and selling are unchanged.
