# Making a piece better than it is

Choosing the right piece is [bot-gear.md](bot-gear.md). This is the other half: what the game lets a character
do to a piece it already owns, and what the engine charges for it. Both turn out to be nearly free here,
because the stats are applied when the piece is equipped and nothing has to be simulated.

## Enchantment

`Item.setEnchantLevel` before equipping, and `ItemEquipmentListener` calls `EnchantService.applyEnchantEffect`
when the piece goes on.

The level a bot's gear carries: `bot level − item level`, give or take 2, clamped to
`ItemTemplate.getMaxEnchantLevel()` and skipped where `isNoEnchant()`. A level 40 templar in a level 36 torso
wears it at about +4. Enchantment applies to torso, pants, boots, gloves, shoulders, weapon and shield.

## Manastones

Also free: `ItemSocketService.addManaStone(item, stoneId, false)` before equipping, and the stats are applied
when it goes on. Slot counts come from `getManastoneSlots()` and `getSpecialSlots()`; candidates from
`DataManager.ITEM_DATA.getManastones(level)`; each stone carries one modifier, so the weights of section 3
choose them with no second table to maintain.

The engine's own rule, which must be respected: a stone's level may not exceed
`10 × ceil((item level + 10) / 10)` (`EnchantService.socketManastone`).

How often a piece is socketed at all, by bot level: 10% below 20, 50% from 20 to 40, 75% from 41 to 64, 100% at
65.

## Out of scope, deliberately

Two-handed weapon fusion, Idian stones, +15 evolution, re-evaluation and conditioning. The source document
leaves them out and says why: fusion in particular has no simple rule to state, and it matters only at a level
bots are not yet reaching.

## See also

- [bot-gear.md](bot-gear.md) — where gear comes from, whose faction it is, and which piece to pick.
- [stigmas.md](stigmas.md) — the other thing a character sockets, with rules of its own.
