# Where a bot's gear may come from

Two of the three questions that decide a piece, split out of [bot-gear.md](bot-gear.md) to keep both files
readable: can this bot reach it at all, and is it this bot's faction. What makes one piece better than another
stays there.

## 1. Obtainability: where gear comes from

`BotEquipManager.isPlayerGear` used to ask two questions of the template itself: does it require an armour or
weapon mastery, and does it declare its own level restrictions. Both were proxies for "a player could be handed
this", and both are wrong in the same direction — they reject real gear:

- **Every accessory fails the first one.** `RING`, `EARRING`, `NECKLACE`, `BELT` and `HEAD` are
  `ArmorType.ACCESSORY`, so `ItemGroup.requiresMastery()` is false and `SkillData.getMasterySkills` returns an
  empty set. No ring, earring, necklace, belt or head piece could ever be worn by a bot.
- **The coin-vendor tier fails the second one.** `Eltnen Sun Legionary Boots` (level 36, `UNIQUE`, the
  silver-coin armour) carries no `restrict` attribute at all, so `hasLevelRestrictions()` is false. That test
  alone is what hid it; the level filters beside it were never the problem, because the default restriction is
  an array of 1s, so `getRequiredLevel` answers 1 and `isClassSpecific` answers true for every class.

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

**Being listed is not enough on its own**, and neither is the fallback test: an event sword and a three-day
mace are both on a vendor's shelf and both declare proper level restrictions. `BotGearFit.isObtainable` is
therefore asked first, as a veto, on both paths. It turns away four kinds, each of them reported from in game:

| Turned away | Read from | Seen as |
| --- | --- | --- |
| npc costume, dev leftovers | an `npc`/`test` segment of `cName`, **numbered forms included** | a level 43 sorcerer holding `hidden_test6_book_e1_60b`; `test6` is why the bare-word test missed it |
| event gear | an `[Event]` name tag or an `event` segment | a level 6 character with a snow-crystal event sword |
| anything that expires | `expire_time > 0`, 1890 pieces | "Warhammer of Inconstancy (3 days)" |
| abyss gear | `ItemType.ABYSS` | the grimoire above; a bot earns no rank, and many abyss pieces ask for none |

The `cash` marker is deliberately kept: `world_cash_*` is the level 65 gear sold for real money, and it is real
gear with real stats.

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

Two faults followed from the old positional rule. **Shields** carry `ArmorType.GENERAL`, so `isArmor()` is true
and the code counted to the third segment, but a shield names itself in one word — `shield_d_n_c_03a` read as
`n` where the marker is `d`, and every Asmodian shield passed as Elyos. **Looted gear was never checked at
all**: only the dressing path asked. Counted live, of 1411 pieces worn by bots 14 were the other faction's, 11
of them shields. Both paths now ask, and `//bot regear` takes off what is already on.

## See also

- [bot-gear.md](bot-gear.md) — ranking, quality by level, and the right kind of piece.
- [world-and-data-traps.md](world-and-data-traps.md) — the filter that hid accessories, and the `cName` shapes.
