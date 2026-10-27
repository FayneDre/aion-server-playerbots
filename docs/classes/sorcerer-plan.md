# Sorcerer: implementation plan

Status: milestones 1 to 11 built and unit tested (26 pure tests, 6 data tests), validated in game on 27 October, solo and grouped; milestone 0 (naming the skills) done from the
data and the French client's strings, every name settled; the guide's questions answered below. Source: the guide's document "Sorcier 101" (seven pages, in French): a ranged magical damage dealer with an orb, extreme single target damage and
many controls, fragile ("glass cannon"), who never swings its weapon ("Always Be Casting") and never breaks off a cast it has begun. The pattern is the
gladiator's ([gladiator-plan.md](gladiator-plan.md)): a `ClassPlaybook`, rules by skill **group**, a pure `decide`, a data test. This file is only the
distance between the guide and what the module does today.

The guide's English names are its own translations of the French client's; the French names below settled every one ([skill-glossary-sorcerer.md](skill-glossary-sorcerer.md)).
Levels are 4.8's sorcerer tree; the skills learnt before 10 are the mage's (`MA_*`).

## What the guide asks for, mapped to the data

Damage. Cast times in brackets; instant otherwise.

| The guide | Client name | `group` | Cooldown | Notes from the data |
|---|---|---|---|---|
| Flame Bolt, Flame Harpoon | same | `MA_FLAMEBOLT`, `WI_BURNINGSOUL` | 0, 3 s | (2 s) Chain A openers; Harpoon replaces Bolt from 13 |
| Blaze, Ember | Blaze, Conflagration | `MA_IGNICBURN`, `WI_CHAINBURN` | 30, 30 s | Chain A: Bolt or Harpoon → Blaze (magic resist down) → Ember (from 56) |
| Ice Chain, Frozen Shock | same | `MA_ICEGRAB`, `MA_FROZENSHOCK` | 10, 30 s | (2 s) Chain B; the solo opener only, never in a group |
| Erosion, Flame Cage | same | `MA_GRAVITYCAGE`, `WI_FLAMECAGE` | 3, 5 s | Damage over 15 s on the main target, renewed when it runs out; Cage replaces Erosion from 16 |
| Spear of Wind | Wind Spear | `WI_WINDSPEAR` | 90 s | Multicast: a chain that follows itself three times (`selfcount="3"`) |
| Delayed Burst | Delayed Blast | `WI_DELAYEDEXPLOSION` | 30 s | (2 s) Delayed damage: the solo opener before the monster notices, never in a group |
| Freezing Wind | same | `WI_HYDROIMPACT` | 2 s | 3 m: only when something has come within 3 m, never walked to |
| Aether's Hold, Aether Flame, Magic Fist | same | `WI_WIZARDRYHOOK`, `WI_FIRESHOOTER`, `WI_MAGICFIST` | 120, 0.5, 10 s | (2.8 s) Hold puts `OPENAERIAL` on; Flame twice, then Fist, which ends the hold (`closeaerial`) |
| Refracting Shard, Soul Absorption | same | `WI_MAGICALCHAIN`, `WI_SOULEATER` | 3, 20 s | Mana drains. Shard opens chain F one time in four (`chain_skill_prob="25"`), the guide's "proc", then Soul Absorption |
| Flame Fusion | same | `WI_MELTDOWN` | 10 s | (1 s) Fire resistance down: first of the fire rotation |
| Flaming Meteor | same | `WI_METEOR` | 120 s | (1.5 s) Area 5 m **on a point**, stuns: cast on the ground at the target's feet (`Skill.setTargetPosition`) |
| Stamina Absorption, Frost | Stamina Absorption, Freeze | `WI_VITALITYDRAIN`, `WI_FROZENCORE` | 60, 60 s | Lifesteal; snare |
| Big Magma Eruption | Magma Burst | `WI_STIGMA_VOLCANICFLAME` | 60 s | (2 s) Area 10 m on the target, delayed damage |
| Summon Meteorite | Meteorite | `WI_RAISINGSTORM` | 60 s | (1.5 s) Area 7 m **around the sorcerer**, multicast ×4: useless at 25 m |
| Fire of Magic Power | Aetherflame | `WI_MAGICALFLAME` | 120 s | (4 s) Big damage, from 65 |
| Aetheric Spell (DP) | Empyrean Fire | `MA_PANDEMONIUMFOCUS` | 60 s | **2000 DP**, big damage |
| Lumiel's Lament, Lumiel's Wrath (DP) | Shadowburst, Empyrean Wrath | `WI_ABYSALSTALKER`, `WI_ABYSALSLAYER` | 1800 s | **4000 DP**, against players only |

Utility and buffs.

| The guide | Client name | `group` | Cooldown | Rule |
|---|---|---|---|---|
| Stone Skin | same | `MA_STONESKIN` | 120 s | A shield of 5 min: renewed when it is gone, first of all in a fight; alone, no fight without it |
| Flame Robes / Frozen Robes | Robe of Flame / Robe of Cold | `WI_FLAMESEAL` / `WI_FROZENSEAL` | 10 s | 30 min, one or the other: Cold alone, Flame in a group |
| Zikel's Wisdom | Empyrean Wisdom | `WI_ARCANEPOWER` | 30 s | 15 s: before engaging, or as soon as it is ready in a fight |
| Supplication of Focus | same | `WI_SOULGAIN` | 120 s | 30 s magical accuracy: before Soul Freeze or Aether's Hold |
| Magic Assist | same | `WI_MANABOOST` | 180 s | 60 s: as soon as ready in a fight |
| Gain Mana | same | `WI_GAINMANA` | 180 s | Under 50 % mana |
| Illusion | same | `WI_ILLUSIONDANCE` | 120 s | 10 s of dodging: when something hits the sorcerer physically |
| Boon of Iron-Clad | same | `WI_PHYSICALWALL` | 300 s | Under 50 % health in a fight, Stone Skin off and cooling down |
| Boon of Peace | same | `WI_ABSENCESOUL` | 120 s | Lowers hate, 5 m around: the main target is on the sorcerer and within 5 m |
| Remove Shock, Curse of Old Roots | same | `ALL_SHOCKREFLECT`, `WI_CURSEDCORRUPTTREE` | 60, 180 s | Old Roots follows Remove Shock (`ALL_CHAINA`), on a second enemy, with several |
| Restraint, Curse: Tree, Induce Deep Sleep | Root, Curse of Roots, Somnolence | `MA_ROOT`, `WI_CURSEDTREE`, `WI_DEEPSLEEP` | 60, 30, 20 s | Controls, Sleep > Tree > Root (Somnolence casts for 5 s) |
| Soul Freeze | same | `WI_SOULFREEZE` | 20 s | Silence: on an enemy that casts |
| Illusion Gate | Summon Rift | `WI_ILLUSIONGATE` | 60 s | A gate to the capital for the group, cast on a point: the proposed `//bot gate` |

Do not use: Blind Leap `WI_DIMENSIONDOOR` and Winter Binding `WI_FROZENFIELD` (the flight only), Boon of Clairvoyance `WI_MINDSEYE`, Tranquilizing
Cloud `WI_SLEEPINGCLOUD`, Vaizel's Wisdom `WI_ARCANEBOOST`, Rift Jump `WI_DIEMSIONJUMP`, Graspbreaker `WI_CRYSTALMIRROR`, Thick Shield
`WI_RAPIDSHIELD` (the guide's Repeated Protective Shield), Mounting Explosion `WI_DELAYEDSTRIKE` (the guide's Delayed Heatwave).

Stigmas (section IV): Ice Harpoon `WI_STIGMA_ICELANCE`, Storm Strike `WI_CYCLONESTRIKE` (the guide's Cyclone Strike), Arcane Thunderbolt
`WI_STORMSHOCK` (stuns), then Flame Spray `WI_FLAMESTRIKE` (after Flame Fusion), Summon Rock `WI_ROCKFALL`, Glacial Shard `WI_ZEROPOINT` (over 30 % mana).

The fire rotation: Flame Fusion → Flame Spray → Magma Burst → Flame Harpoon, Blaze, Ember. The single target sequence at 51: robes and Empyrean
Wisdom before engaging; Delayed Blast; Ice Chain, Frozen Shock; Flame Cage; Storm Strike; Arcane Thunderbolt; the fire rotation; Wind Spear ×3;
Freeze; Summon Rock; Ice Harpoon; Flame Cage again; then the long cooldowns as soon as ready, Ice Harpoon (6 s) and the fire rotation in between.

## What exists, and where it disagrees with the guide

- **No sorcerer playbook.** The generic order casts by stated damage with chain links first, so the fire chain and chain B go off, but nothing keeps
  Erosion or Flame Cage on the target, nothing times the robes, Stone Skin or the mana, and the banned skills are not kept from it.
- **It swings.** A sorcerer in reach of its weapon auto attacks, and one saving mana falls back on the weapon; the guide's sorcerer never does.
- **Resting**: the generic rule engages above 90 % health and 35 % mana. The guide rests after a fight under 50 % mana, up to 100 %.
- **Ground spells** (target on a point): nothing in the module casts one; the engine takes a position (`Skill.setTargetPosition`), as the client sends it.
- **The pieces exist**: the ranger's hold fire (`holdingFireSince`) for the guide's ten seconds of hate in a full group; `mendingToFull` for a state
  kept from tick to tick (the mana drain from 30 % to 60 %); `otherEnemyOnTheGroup` and the ranger's caster test for control and silence; the
  conditionals and the chain rule of the gladiator; `flees` and `onRetreat`; positioning puts it at range in a group already.

## Milestones, in verifiable order

All built in `SorcererPlaybook`, with what the class needed of the module:

0. **Name the skills.** *Done above.*
1. **The seam and the bans.** *Built: registered; `FORBIDDEN_GROUPS` claimed; the Lumiel pair claimed and cast against players only; the solo
   openers never in a group. `ClassPlaybook.swings`, read by the fight loop: the sorcerer never auto attacks, it waits for a spell.*
2. **Upkeep.** *Built: before a fight (`prepare`) Stone Skin, the robe (Cold alone, Flame in a group), Empyrean Wisdom; in a fight Stone Skin first of
   all, the robe, the wisdom, Magic Assist; Supplication of Focus before Soul Freeze and Aether's Hold. Idle, nothing but Gain Mana: a resident in
   town renews no shield for nobody. Stone Skin is renewed as soon as ready, and no fight waits for it.*
3. **Mana.** *Built: Gain Mana under 50 %; Refracting Shard from under 30 % until 60 % (a state kept per bot); Soul Absorption on chain F; Glacial
   Shard over 30 %. `ClassPlaybook.wantsToRest`, read by the engage bar: under 50 % after a fight it sits down and rests to 100 %, never while its
   group fights.*
4. **Defence.** *Built: Remove Shock under control, then Curse of Old Roots on a second enemy with several; Boon of Iron-Clad under 50 % with Stone
   Skin gone and cooling; Illusion when an enemy within 5 m is on it; Boon of Peace with the target on it within 5 m; Freezing Wind within 3 m.*
5. **The damage order.** *Built: the multicasts recast while their chain is open (the engine waives the cooldown then: `Player.isSkillDisabled`);
   Frozen Shock after Ice Chain alone; the fire chain left to the generic order while open; Aether Flame twice then Magic Fist on a target held in
   the air; Empyrean Fire at 2000 DP, Shadowburst and Empyrean Wrath at 4000 against a player; Delayed Blast and Ice Chain on an idle target alone;
   Flame Cage, else Erosion, kept on the target; then Storm Strike, Arcane Thunderbolt, Aetherflame, Aether's Hold, Flame Fusion, Flame Spray (after
   Fusion), Magma Burst, Flaming Meteor, Wind Spear, Freeze, Summon Rock, Glacial Shard, Stamina Absorption, Meteorite (an enemy within 7 m), Ice
   Harpoon, Flame Harpoon. A move the engine refuses gives way to the next.*
6. **Ground spells.** *Built in `BotSkillManager.cast` for every class: a skill cast on a point is given the feet of whom it is aimed at, the reach
   asked there; see [skill-traps.md](../traps/skill-traps.md).*
7. **Control.** *Built: on an engaged enemy that is not the target and not already held, slept, transformed or silenced, Somnolence > Curse of Roots
   > Root, in a group or alone with several; Soul Freeze on a target that casts (the ranger's caster test).*
8. **Not becoming the target.** *Built: in a full group with a living templar, ten seconds with the target on it and the sorcerer stops attacking
   (`HOLD_FIRE`) until it looks away; its defences still go.*
9. **The flight, alone.** *Built: `flees` under 30 % health; `onRetreat` casts Winter Binding then Blind Leap; the walk away is the generic retreat's.*
10. **Stigmas.** *Built: `preferredStigmas`, the guide's six.*
11. **`//bot gate`.** *Built: [bot-commands.md](../bot-commands.md).*

Not checked in game, and worth watching first: the ground spells landing where they should, the multicasts going three and four times, and the hold
of fire in a full group.

## Answered by the guide's author

1. **Stone Skin** is renewed as soon as it is ready; no fight waits for it.
2. **Summon Meteorite** is damage round the sorcerer only (7 m in the data): kept for an enemy that has come within reach of it.
3. **The multicasts** are chained, every cast one after the other while the chain is open.
4. **Lumiel's Lament and Wrath**: in PvP only, as written.
5. **Ten seconds on the sorcerer**: it stops attacking until it has lost the monster's attention, and keeps its defences.
6. **`//bot gate`**: wanted, built.
