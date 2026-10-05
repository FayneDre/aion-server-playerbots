# What a bot wears

A bot used to be dressed from the whole item table, filtered only by what the engine would refuse anyway, and
ranked by item level. That produced characters in white and green gear with six empty accessory slots, no
enchantment and no manastones — legal, and roughly half the character a real player of that level would be.

This is the plan for the rest of it, and the reasoning behind each number. It comes from a player's write-up of
how these classes are actually geared; the engine facts beside it were measured here.

## The three questions about a piece

1. **Can this bot reach it?** — the obtainability index, below. Replaces the old guess.
2. **Is it this bot's faction?** — the name marker, below. The index answers first where it can.
3. **Is it better than what it wears?** — stat weights per class, below. Replaces ranking by item level.

## 1. Obtainability: where gear comes from

`BotEquipManager.isPlayerGear` used to ask two questions of the template itself: does it require an armour or
weapon mastery, and does it declare its own level restrictions. Both were proxies for "a player could be handed
this", and both are wrong in the same direction — they reject real gear:

- **Every accessory fails the first one.** `RING`, `EARRING`, `NECKLACE`, `BELT` and `HEAD` are
  `ArmorType.ACCESSORY`, so `ItemGroup.requiresMastery()` is false and `SkillData.getMasterySkills` returns an
  empty set. No ring, earring, necklace, belt or head piece could ever be worn by a bot.
- **The coin-vendor tier fails the second one.** `Eltnen Sun Legionary Boots` (level 36, `UNIQUE`, the
  silver-coin armour) carries no `restrict` attribute at all, so `hasLevelRestrictions()` is false and
  `getRequiredLevel` answers -1 for every class.

So the gear worth having was the gear most reliably excluded. The replacement asks where an item comes from,
reading the same data a player would reach it through:

| Source | Read from | Gives faction? | Gives level? |
| --- | --- | --- | --- |
| Quest rewards | `QuestsData.getQuestTemplates()`, then `getRewards()` and `getSelectableRewardByClass()` | `getRacePermitted()` | `getMinlevelPermitted()` |
| Vendors | `TradeListData`, then `GoodsListData`, then the seller's `NpcTemplate.getRace()` | yes, when the seller is Elyos or Asmodian | no |
| Named drops | `GlobalDropData.getAllRules()`, then `getDropItems()` | `getRestrictionRace()`, else the `ASMODAE` / `ELYSEA` world type | no |
| Chest drops | `DataManager.CUSTOM_NPC_DROP`, then `DropGroup.getRace()` | yes, when it names a player race | no |

An item in the index is gear a player can get, and usually arrives with its faction already settled — which is
better than any guess from its name. Items in no source fall back on the old mastery-and-restrictions test, so
ordinary levelling drops keep working.

Quest rewards were going to be restricted to `QuestCategory.MISSION` — the yellow campaign quests, a few
hundred against several thousand ordinary ones. Counted, that keeps 899 pieces of gear out of 4348, and it
drops the tier this was written for: the level 36 fabled armour is the reward of "Mamaki Patrol", category
`IMPORTANT`. A map of four thousand entries needs no narrowing, so every category is read except `EVENT` —
otherwise a bot wears the reward of a quest that only runs for two weeks in December.

## 2. Faction: reading the model name

No armour template in the table declares a race, and all 12995 weapon templates declare `PC_ALL`, so
`ItemTemplate.getRace()` cannot answer this. The faction lives only in the client model name, and a body has no
model for the other side's gear: the piece simply does not draw. Reported in game as a cleric with only its
head and arms visible.

The marker is a single-letter segment of `cName`: `d` is Asmodian, `n` and `g` are Elyos, `u` and `e` belong to
neither. It is **not at a fixed position** — `ch_torso_d_n_c1_light_30a` has it third, `harp_d_n_r1_16n` and
`ring_n_c_21a` second, `ac_hat_d_n_c1_10a` third while `mask_n_c_11a` has it second. Scanning the segments in
order and taking the first marker found handles all four shapes, because no prefix word in the table is a
single letter.

Measured against the items whose English name says "Elyos" or "Asmodian", skipping names with an `npc` or
`test` segment: **3432 right, 4 wrong** — the four being `Elyos Daevanion` level 60 pieces whose name says
Elyos and whose `cName` says `d`. A data quirk, left alone rather than special-cased.

Where the obtainability index knows the faction, it wins; the name marker only decides for items the index
does not cover.

**Shields were the quiet casualty of the positional rule.** `ItemSubType.SHIELD` carries `ArmorType.GENERAL`, so
`isArmor()` is true and the old code counted to the third segment — but a shield names itself in one word,
`shield_d_n_c_03a`, so it read `n` where the marker is `d`. Every Asmodian shield passed as Elyos.

**The other half was never checked at all.** `BotOutfitter` asked the question when it dressed a new bot;
`BotEquipManager.equipUpgrades` did not, so anything looted went on regardless of side. Counted over the live
population: of 1411 pieces worn by bots, 14 are the other faction's — 11 shields from the first fault and the
rest looted armour from the second. Both paths now ask.

Those 14 stay on until something takes them off: equipping only ever adds. Stripping them is a job for the
re-gear pass that M2 needs anyway.

## 3. Stat weights per class

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

Gunner and Aethertech have no authored order — the source says so plainly. They take the generic magical and
physical orders respectively until somebody who plays them says otherwise.

## 4. Quality, by level

`BotOutfitter.BEST_QUALITY` was `RARE` in a constant, so nothing above green was ever reachable:

| Level | Ceiling | What that opens |
| --- | --- | --- |
| below 26 | `RARE` (green) | as today |
| 26 and up | `LEGEND` (blue) | bronze-coin armour, "Elite Rank 7"; movement speed on boots, flight speed on torso |
| 36 and up | `UNIQUE` (yellow) | silver-coin armour, "Sun Legionary"; attack speed |

Movement and flight speed are not cosmetic here: a bot that cannot keep up with its group is a bot that is not
in the fight. They pay for themselves against the work already done on following and flying.

Shields improve on their own with this change — damage reduction rises with quality, 30/35/40/45% for
common/superior/heroic/fabled — so no shield-specific code is needed.

**Hostility** comes with the same tier and matters for group play: plate carries a positive aggro bonus, cloth
a negative one. Mapped onto the roles `BotRole` already knows:

| Classes | Armour | Hostility |
| --- | --- | --- |
| Templar, Gladiator | plate | + |
| Aethertech | chain | + |
| Cleric, Chanter | chain | − |
| Ranger, Assassin | leather | − (and evasion) |
| Gunner | leather | − (and magical boost) |
| Sorcerer, Spiritmaster, Bard | cloth | − |

**Uniformity is the risk this creates.** Choosing the single best piece for a stat puts a whole region in the
same boots. The existing trick holds: sort so the right answers come first, then pick at random *among the
answers that tie*.

## 5. Enchantment

Free, as far as the engine is concerned: `Item.setEnchantLevel` before equipping, and `ItemEquipmentListener`
calls `EnchantService.applyEnchantEffect` when the piece goes on. Nothing has to be simulated.

The level a bot's gear carries: `bot level − item level`, give or take 2, clamped to
`ItemTemplate.getMaxEnchantLevel()` and skipped where `isNoEnchant()`. A level 40 templar in a level 36 torso
wears it at about +4. Enchantment applies to torso, pants, boots, gloves, shoulders, weapon and shield.

## 6. Manastones

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

- [combat-skills.md](combat-skills.md) — what a bot does with the weapon this gives it.
- [group-roles.md](group-roles.md) — where the hostility split comes from.
- [stigmas.md](stigmas.md) — the other half of a character's build.
- [world-and-data-traps.md](world-and-data-traps.md) — the filter that hid accessories, and the `cName` shapes.
