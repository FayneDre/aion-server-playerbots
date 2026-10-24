# Bot store goods: what a bot store sells, and at what price

**Status: built on 24 October (`BotStoreGoods`, `BotManastones`, `BotStoreStock`) and looked over in game the same day: prices judged consistent at a glance.** Planned on 23 October from a
price guide (broker prices remembered from live servers); the guide answered the questions on 24 October, which settled the prices and widened
the scope to manastones and to two kinds of seller. The stores themselves are in [bot-stores-plan.md](bot-stores-plan.md).

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
| Evolution stone | 60 - 120M (a rare drop from the major instances' bosses) |
| Holy Upgrade Serum | 50 - 90M, see the note below |
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

**Under level 60** these ranges halve for every ten levels: a level 50 stone at half, 40 a quarter, 30 an eighth (the guide asked for about a
tenth at 30). Level 70 keeps the ranges as given, which the guide gave for 60 and 70 together.

**Double-stat (composite) manastones**, by how well the two stats suit one class. The guide's source gave 80-150M, 30-70M and 10-25M, which
the guide found a little dear (one paid 70M for a single endgame stone), so a notch under, agreed on 24 October:

| tier | stats | range (kinah) |
|---|---|---|
| 1 | Attack / Crit Strike, Magic Boost / Magical Accuracy, Crit Strike / Magical Accuracy | 60 - 110M |
| 2 | one of those with HP | 25 - 55M |
| 3 | stats at odds (physical with magical, or with a weak defence) | 8 - 20M |

The same halving under level 60 applies to them (composites exist from level 30).

**The serum's price is for another item.** The guide's 50-90M is for stigma evolution serums, which 4.8 does not have; the Holy Upgrade
Serum (166030005) upgrades accessories. Taken at that price, decided on 24 October.

Stacks: coins and medals 10 to 50, as players sell them; stones as today; ancient and composite manastones one or two, they are rare.

## Milestones

1. **Upgrade items, built.** The stones theme keeps only the eight ids above. A stone is stocked ten levels under the gear it is for, as the guide's manastones
   are: L1 from 20, L2 30, L3 45, L4 50, L5 55; All-Powerful, evolution stone and serum from 60, and in about one stocking in ten. Remnant
   stones still in a keeper's bag are thrown away when its shelf is rebuilt after a restart. `BotStoreGoodsDataTest` checks each id against
   the data.
2. **Coins and medals, built**: a theme of its own ("Pièces et médailles") for a quarter of the keepers, read off a hash of its own so the
   others keep the theme they had; stocked by the level table; Ceramium coin included, Iron coins and the three untradeable medals out. Each
   coin has an Elyos and an Asmodian copy in the data (`coin_02`, `coin_d_02`); the keeper's own is chosen by race, since the Elyos copy
   carries no marker the faction filter could read.
3. **Manastones, built** (`BotManastones`), read by the stats a stone adds, not its name. Sold: green single-stat stones (stacks of 5 to 20),
   blue two-stat ones (1 to 3) and ancient ones (1 or 2, keepers of 60 and over); common stones are not. The stats the guide did not name are
   placed with their kind: Crit Spell with the core stats; defence, healing, the other resistances and the PvP ratios with the niche ones;
   flight time with the trash. An ancient stone's top is ours, twice the guide's bottom (300-600k, 500k-1M, 1-2M, eternal 2-10M); one at or
   over the guide's best four values is 10-20M. A stones store's lines: upgrade items 4 in 10, single stones 3, two-stat 2, ancient 1, since
   the two-stat stones outnumber the single ones six to one. `BotManastonesDataTest` prices every stone of the data.
4. **Gear and jewellery from blue up, built**: blue 70, gold 25, eternal 5 in a hundred, eternal from level 60 only. The bots' own gear ceiling
   ([bot-gear.md](bot-gear.md)) is not the store's: the guide's rule is blue at least whatever the level.
5. **Dedicated and opportunist sellers, built.** A third of the keepers are opportunists ("Bric-à-brac 35-45"), read off a hash of their own so
   the others keep their theme. The **dedicated** keeper's wide choice: gear and jewels from 20 levels under its own to 5 over ("Bijoux
   20-45"), manastones of every level up to ten over its own ("Pierres 10-50"), every coin its level allows. The **opportunist**: of eight
   lines about 3 stones, 2 coins or medals, 2 jewels and 1 piece of gear, all within 5 levels (stones 10, coins 20) of its own, in stacks a
   quarter of a merchant's.
6. **Watched in game, 24 October**: the stores and their prices looked over by the user, consistent at a glance.
