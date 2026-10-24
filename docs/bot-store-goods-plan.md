# Bot store goods: what a bot store sells, and at what price

**Status: not started.** Planned on 23 October from a price guide (broker prices remembered from live servers); the guide answered the
questions on 24 October, which settled most of it and widened the scope to manastones and to two kinds of seller. Three prices are still asked
(end of this page). The stores themselves are in [bot-stores-plan.md](bot-stores-plan.md).

## The names: why the first reading was wrong

The server's item data and the client do not name the same things alike. The guide reads the client, so a name from the guide is looked up by
**id**, never by name:

| the guide (client name) | id | the server's data | tradeable |
|---|---|---|---|
| L1 Enchantment Stone (rare gear, level 20) | 166000191 | Alpha Enchantment Stone | yes |
| L2 Enchantment Stone (heroic, level 40) | 166000192 | Beta Enchantment Stone | yes |
| L3 Enchantment Stone (fabled, level 55) | 166000193 | Gamma Enchantment Stone | yes |
| L4 Enchantment Stone (eternal, level 60) | 166000194 | Delta Enchantment Stone | yes |
| L5 Enchantment Stone (mythic, level 65) | 166000195 | Epsilon Enchantment Stone | yes |
| All-Powerful Enchantment Stone | 166020000 | Omega Enchantment Stone | yes |
| Evolution Stone (also called amplification stone) | 166500002 | Amplification Stone | yes |
| Holy Upgrade Serum (accessories) | 166030005 | Tempering Solution, group `TAMPERING` | yes |

The other 190-odd templates named "... Enchantment Stone" (`L1` to `L190`, one per grade) are **remnants of older versions** that no player
obtains in 4.8. Today's stones store stocks them by grade round the keeper's level: that is the first thing to undo.

## Decisions taken (24 October)

1. **Enchantment stones**: the five above and the All-Powerful one, nothing else. The guide's five tiers are these five stones, not grade bands.
2. **Evolution stone and serum** exist and go in, with the enchantment stones.
3. **Platinum, Mithril and Ceramium medals: never**, not even for bots through the engine. Ceramium medals are an endgame goal for the best PvP
   gear, and a bot selling them would short-circuit it.
4. **Iron coins: no** (no worth between players, and a store has 10 slots). **Ceramium coins: yes, about 100 000.**
5. **Coins and medals get a theme of their own.**
6. **Two kinds of seller**, as on a live server:
   - the **dedicated** seller: one category, a wide choice across every level (a store of coins of almost every value);
   - the **opportunist**: a mix of every category round its own level, as a player sells after a session (a level 40 with a few level 50
     manastones, 5 silver medals, a blue jewel and one unique piece of armour).

## The six categories

| category | contents | rule |
|---|---|---|
| coins and medals | by the keeper's level band | levels below |
| upgrade items | the six enchantment stones, the evolution stone, the serum | |
| standard manastones | single stat (green, `RARE`) and double stat (blue, `LEGEND`) | levels below |
| ancient manastones | `SPECIAL_MANASTONE`, level 70, green to eternal (`RARE` to `EPIC`) | keepers of 60 and over only |
| gear | the class's own, **blue at least**; eternal (`EPIC`, orange) allowed from 60 | |
| jewellery | **blue at least** | |

Gear and jewellery today draw green 70% of the time: the floor moves to blue.

Keeper level each good asks for (the guide's table):

| coin | keeper | medal | keeper | manastone | keeper |
|---|---|---|---|---|---|
| Bronze | 18 | Silver | 35 | level 30 | 20 |
| Silver | 21 | Gold | 45 | level 40 | 30 |
| Gold | 31 | | | level 50 | 40 |
| Platinum | 41 | | | level 60 | 50 |
| Mithril | 51 | | | level 70 | 60 |
| Ceramium | 61 | | | level 80 | not sold (none in 4.8 anyway) |

## Prices

The guide's ranges; the keeper's temperament places it in the range, an unsold piece drops 10% an hour **down to the bottom of the range** and
stops there (today it falls to the npc floor, a few kinah for these goods).

| goods | range (kinah) |
|---|---|
| L1 / L2 / L3 stone | 20-50k / 100-250k / 500k-1.5M |
| L4 / L5 stone | 3-6M / 15-25M |
| All-Powerful stone | 75M |
| Bronze / Silver / Gold / Platinum / Mithril coin | 10-15k / 15-25k / 35-50k / 60-90k / 120-180k |
| Ceramium coin | about 100k |
| Silver / Gold medal | 10-20k / 40-70k |
| ancient manastone, green / blue / gold | 300k / 500k / 1M at least |
| ancient manastone, eternal | 2 to 20M; the best (Crit Strike +24, Attack +8, Magic Boost +32, HP +105) 10M at least |

**Standard manastones, levels 60 and 70**, by stat, the guide's broker ranges:

| stats | examples | range (kinah) |
|---|---|---|
| premium | Crit Strike +17/+19, Magic Boost +27/+28, Attack +5 | 1.5 - 4.5M |
| core | HP +95/+100, Magical Accuracy +14/+16, Accuracy +27/+29 | 300k - 1M |
| niche defence | Strike Resist +11, Block +27/+29, Magic Resist +14 | 50 - 200k |
| trash | Evasion +17, Parry +27, MP +95 | 10 - 25k |

**Double-stat (composite) manastones**, by how well the two stats suit one class: tier 1 (Attack / Crit Strike, Magic Boost / Magical
Accuracy, Crit Strike / Magical Accuracy) 80-150M; tier 2 (one of those with HP) 30-70M; tier 3 (stats at odds, physical with magical or with a
weak defence) 10-25M. The guide finds these a little dear but closer than they remembered (one paid 70M for a single endgame stone).

Stacks: coins and medals 10 to 50, as players sell them; stones as today; ancient and composite manastones one or two, they are rare.

## Milestones

1. **Upgrade items.** The stones theme keeps only the eight ids above; the price table by id; the decline stops at the range's bottom. A data
   test that each id exists, is tradeable and keeps its group.
2. **Coins and medals**, a theme of its own ("Pièces et médailles") stocked by the level table; Ceramium coin included, Iron coins and the
   three untradeable medals out. Same data test.
3. **Manastones**: standard ones priced by stat class and level; composite ones by tier; ancient ones only at keepers of 60 and over, priced by
   quality. A test that sorts every manastone of the data into a stat class, so a stat the table forgets shows up.
4. **Gear and jewellery from blue up**, eternal from 60.
5. **Dedicated and opportunist sellers**: the dedicated one a single category over every level, the opportunist a mix round its own level.
6. **Watched in game**: one store of each kind in each capital, prices read in the store window.

## Still asked of the guide

1. **Evolution stone and Holy Upgrade Serum**: what price range?
2. **Standard manastones under level 60**: the ranges given are for 60 and 70. Scale them down by level (a level 30 stone at a tenth of a 60),
   or other figures?
3. **Composite manastones**: keep the tiers as given (80-150M, 30-70M, 10-25M), or lower them, since the guide finds them dear? They are also
   sold under level 60 (levels 30 to 70 in the data): the same tiers there, or less?
