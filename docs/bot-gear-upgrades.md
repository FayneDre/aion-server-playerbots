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

`ItemSocketService.addManaStone(item, stoneId, false)`, and the stats are applied when the piece goes on. A
piece already worn — which is every piece here, since this runs after equipping — needs
`ItemEquipmentListener.addStoneStats` said over each stone, exactly as the player-facing socketing does. The
stones persist through the ordinary save: `PlayerService.storePlayer` reaches `ItemStoneListDAO.save`.

**One stat stacked across a piece's slots**, not a different stone in each. That is how the game is played —
six crit stones in a weapon — and the only thing that interrupts it is the crit cap, at which point the
stacking moves to whatever the class wants next. Stones carry their stats as ordinary modifiers, so the weights
of `bot-gear.md` choose them with no second table to maintain.

**Which stone level.** Two ceilings, lower wins. The engine's own is `10 × ceil((item level + 10) / 10)`
(`EnchantService.socketManastone`) and is generous — it would allow a level 50 stone in a level 36 torso. The
real limit is that a character has the stones its own levels gave it, so the level is also capped at the
owner's rounded down to a ten, which is the only granularity stones come in: 10, 20, 30, 40, 50, 60, 70.

**How much room there is** comes from `Item.getSockets(false)` rather than the template, because a tuned piece
carries sockets its template never declared — and the loop simply runs until the engine refuses, since special
slots are reserved at the front for ancient stones and `addManaStone` does that accounting itself. As with the
enchant ceiling, accessories answer themselves: **no accessory in the table declares a slot**, so in this build
they take no stones either.

How often a piece is socketed at all, by owner's level: 10% below 20, 50% from 20 to 40, 75% from 41 to 64,
100% at 65. Rolled per piece, so a second re-gear pass fills a few more — a fair reading of a character that
keeps buying stones, and it stops when the pieces are full.

Scored against the stone table, each class picks a stone carrying its own first stat, and falls through
sensibly once crit is capped:

| Class | Level 40 pick | Once crit is capped |
| --- | --- | --- |
| Templar | Crit Strike +13 / Block +11 | HP +75 / Block +11 |
| Gladiator | Crit Strike +13 / Parry +11 | Parry +23 / HP +37 |
| Ranger | Crit Strike +13 / HP +37 | HP +75 / Accuracy +11 |
| Cleric | HP +75 / MP +37 | unchanged |
| Sorcerer | Magic Boost +23 / HP +37 | unchanged |

## Out of scope, deliberately

Two-handed weapon fusion, Idian stones, +15 evolution, re-evaluation and conditioning. The source document
leaves them out and says why: fusion in particular has no simple rule to state, and it matters only at a level
bots are not yet reaching.

## See also

- [bot-gear.md](bot-gear.md) — where gear comes from, whose faction it is, and which piece to pick.
- [stigmas.md](stigmas.md) — the other thing a character sockets, with rules of its own.
