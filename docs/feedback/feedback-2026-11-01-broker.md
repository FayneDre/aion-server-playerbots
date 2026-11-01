# Feedback of 1 November: the broker's goods and prices

Status: changed, not yet checked in game. The pure rules are tested (`BrokerHouseRulesTest`); the rules that read the item, recipe and skill data run only on the server.

A tester went through the Elyos broker the house had filled and listed what a player would not meet there, and what costs too little. Each point below is
what he wrote, then what the house does now (`BrokerHouseStock.isPlayerGoods`, `BrokerHousePricing`, `BrokerHouseCatalog`).

## What is no longer listed

| The tester saw | The rule |
| --- | --- |
| `ExtractionControlTest`, `Equipement_Blessing-Test`, `Pvp_Active Defence_Test_A`, `Test-T-Shirt 02`, `Balance-Test...`, `Assembly Testing Item` | A test word is looked for on the name with its separators opened: underscores, hyphens and camel case (the word boundary of a pattern does not break at an underscore, so `ExtractionControlTest` and `Pvp_..._Test_A` got through). A client name with `test` too. |
| `Level 55 Asmodian Random Drop U1 Dagger`, `Level 52 ... Mace C2` | The name `Random Drop`. |
| `Supremo's Special ... Flux` | The name `Supremo's Special`. |
| Cash shop items at 1 kinah in Consumables > Other (`Gender Switch Ticket`, `Birthday Celebration`...) | A client name with `cash`; and anything of the data's placeholder price (5 or less) that is not gear or a guide good: `Inner Chamber Key`, `Full Recovery Potion`, `PC XP Extraction Item`. |
| Boxes and bundles (`Bundle of ...`, `Mithril Coin Chest`, `Chest containing ...`, `Sunayaka's Jewel Box`) | An item whose action is decompose (it opens into other items), or exp extraction; the name `Bundle`. |
| Quest collection items (`Sign of Earnest Faith`) | An item kept in an extra inventory of the client. |
| The whole skill book section | An item whose action teaches a skill: 4.8 learns them by itself. Stigmas stay. |
| Enchantment stones | The enchantment group, whatever its grade. |
| Basic designs, the craft consumables, the socketing supplements | What an npc sells for kinah (`BotGearSources.isSoldForKinah`), for anything that is not gear or a stigma. |
| Scrolls | Only those a recipe makes (`BrokerHouseCatalog.isCrafted`) and the guide's. The 30 and 60 minute versions expire, and the house lists nothing that expires. |

Listings the house already holds are withdrawn on its next pass by the same rules, and so are those whose unit price is under the new floor.

## Prices

- Gear over level 60: ten times the price it had (`scaleOf`). A mythic weapon asks 500 million to 1 billion, any other mythic piece 200 to 400 million (`mythicRange`), as the tester wrote.
- Stigmas: a hundred times, which also answers the inert stigmas an npc sells for a few times their price.
- Crafting materials, gathered or looted: never under 1000 kinah a piece, never over 100 000, the quality and the npc value placing them between. Designs are
  not capped.
- Potions that no npc sells: at least twice the health and mana points they restore, read from the item's skill (`BrokerHouseCatalog.potionPoints`).
- Food from level 60: 50 000 a piece, from level 70: 100 000.

## Not done

- The material's price does not read the skill level the gathering asks, only the quality: the template does not hold it.
