# Making a piece better than it is

Choosing the right piece is [bot-gear.md](bot-gear.md). This is the other half: what the game lets a character
do to a piece it already owns, and what the engine charges for it. Both turn out to be nearly free here,
because the stats are applied when the piece is equipped and nothing has to be simulated.

## Enchantment

A player does not wear a piece as it dropped. Enchanting is the ordinary business of levelling, and what it
adds is not a rounding error — a plastron gains about 90 physical defence and 120 health between +0 and +15, so
a bot in unenchanted gear is wearing roughly two thirds of a character.

The level a bot's gear carries is **how long it has owned it**: `bot level − item level`, give or take 2 so a
population is not a spreadsheet, clamped to `ItemTemplate.getMaxEnchantLevel()`. A level 40 templar in a level
36 torso wears it at about +4. Never downwards: a looted piece already carrying more keeps what it has.

**`EnchantService.setEnchantLevel`, not `Item.setEnchantLevel`.** The latter only writes the field. The service
ends the old effect, applies the new stats when the piece is already worn, and marks the right store dirty —
all three needed, because this runs both on a piece going on and on one worn for weeks.

**Which pieces, settled by the data rather than by a list.** Every accessory in the table declares no
`max_enchant` at all, so the ceiling comes out 0 and rings, earrings, necklaces, belts and head pieces are
excluded without being named: in this game they are socketed, not enchanted. Ordinary gear declares 10 or 15.

**Stigmas must not go near this.** A stigma stone stores *its skill level* in the same `enchantLevel` field, so
enchanting one would silently change what the bot knows. The re-gear pass walks
`getEquippedItemsWithoutStigma`, and nothing else touches a stigma.

**The one way this can throw.** `applyEnchantEffect` raises `IllegalArgumentException` when the enchant
templates do not cover the level asked for and their own maximum is below 21. Counted: every real gear group —
all fifteen weapon kinds, the shield, and the twenty armour groups — covers levels 1 to 21, and the only groups
stopping at 10 are `TSHIRT_TEST*`. Clamping to a ceiling of 10 or 15 therefore cannot reach it.

It hangs off `BotEquipManager.wear`, the single point everything a bot puts on goes through. Hanging it off the
dressing instead would have left every looted replacement at +0 — a visible downgrade from the gear it replaced.

What it is worth, on the population as it stands: of 1517 pieces worn by bots, 1243 are enchantable and 692 of
those come out above +0, mostly +1 to +4 and tailing off around +10. The other 274 are accessories and power
shards, which have no ceiling to clamp to.

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
