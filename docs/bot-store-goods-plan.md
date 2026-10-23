# Bot store goods: enchantment stones, coins and medals

**Status: not started.** Planned on 23 October from a price guide (broker prices remembered from live servers), with questions for the user
below. The stores themselves are in [bot-stores-plan.md](bot-stores-plan.md).

## What the guide asks

Broker prices for enchantment stones (five tiers, and the All-Powerful stone), evolution stones, stigma evolution serums, coins and medals,
and these goods put on sale in bot stores.

## What 4.8 has of it

| the guide | in the 4.8 data | tradeable |
|---|---|---|
| enchantment stones L1 to L5 (Alpha to Epsilon) | one stone per grade, `L1 Enchantment Stone` to `L190`, group `ENCHANTMENT`, template price 5 × grade | yes |
| All-Powerful Enchantment Stone | none; the nearest is `Omega Enchantment Stone` (166020000, mythic) | yes |
| evolution stones | none: only test swords named `EquipmentEvolutionTest_*` | — |
| stigma evolution serums | none: the stigma is enchanted with its own stones in 4.8 | — |
| Bronze, Silver, Gold, Platinum, Mithril coins | 186000002 to 186000005, 186000018 (each also under a second id), Iron and Ceramium besides | yes |
| Silver and Gold medals | 186000031, 186000030 | yes |
| Platinum, Mithril and Ceramium medals | 186000096, 186000147, 186000242 | **no**: the engine refuses them in a store |

The guide's tiers are read as grade bands: L1 = grades 1 to 29, L2 = 30 to 59, L3 = 60 to 84, L4 = 85 to 104, L5 = 105 and over.

## Today's prices, against the guide

`BotStorePricing` asks 50 kinah a grade for an enchantment stone: an L100 stone sells at 5 000, the guide says 3 to 6 million. Coins and medals
are not stocked at all.

| goods | guide | proposed rule |
|---|---|---|
| stone, grades 1-29 | 20 000 - 50 000 | the band's range, the keeper's temperament choosing where in it |
| stone, grades 30-59 | 100 000 - 250 000 | same |
| stone, grades 60-84 | 500 000 - 1 500 000 | same |
| stone, grades 85-104 | 3 - 6 million | same |
| stone, grades 105+ | 15 - 25 million | same |
| Omega Enchantment Stone | 75 million (as All-Powerful) | same |
| Bronze / Silver / Gold / Platinum / Mithril coin | 10-15k / 15-25k / 35-50k / 60-90k / 120-180k | same |
| Silver / Gold medal | 10-20k / 40-70k | same |

- **Unsold**: 10% less per hour as today, but down to the bottom of the guide's range rather than the npc floor, which for these goods is a few
  kinah.
- **Stacks**: coins and medals in stacks of 10 to 50, as players sell them; stones as today.

## Milestones

1. **Prices.** A table of ranges by goods (`BotStorePricing`), the temperament placing each keeper in the range, the unsold decline stopping at
   its bottom. Tests on each band and on the decline.
2. **A store of coins and medals**, a fourth theme ("Pièces et médailles"), its goods by the keeper's level: Bronze from 20, Silver from 30,
   Gold and Silver medals from 40, Platinum and Gold medals from 50, Mithril from 60. A data test that each id exists and is tradeable.
3. **Omega stones** in the stones theme, rare (one line in ten stores) and only for keepers of 60 and over.
4. **Watched in game**: one store of each theme in each capital, the prices read in the store window.

## Questions for the user

1. The tiers as grade bands (above): right, or should L1 to L5 map to something else?
2. Omega Enchantment Stone as the All-Powerful stone, at 75 million?
3. Evolution stones and stigma serums do not exist in 4.8: leave them out?
4. Platinum, Mithril and Ceramium medals cannot go in a store: leave them out, or allow them through the engine for bots only?
5. Iron and Ceramium coins, which the guide does not price: leave out, or a price of our own?
6. A store of coins and medals of its own, or coins and medals added to the stones store?
