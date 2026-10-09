# Templar: implementation plan

Plan, not built. Source: The guide's document "Templier 101 — Rôle du templier dans un groupe" (four pages, in French). It describes how a templar
should play in a group; the guide confirmed its defensive rules hold solo as well. What the module does for a tank today is in [group-roles.md](group-roles.md) and
[combat-skills.md](combat-skills.md); this file is only the distance between the two.

## What the guide asks for, mapped to the data

Skill ids change with every level of a skill (4.8 gives each level its own id), so the stable handle is the template's `group`, which is what the
code should match on. Names below are the English client names found in `skill_templates.xml`; the French ones are the guide's.

| The guide | Client name | `group` | From | Cooldown | Notes from the data |
|---|---|---|---|---|---|
| Provoquer | Taunt | `WA_PROVOKE` | 10 | 10 s | `hostileup` on one target, range 15 |
| Capture | Aether Leash | `KN_STUNNINGSNACHER` | 16 | 30 s | A taunt per the guide (tooltip: "increasing its wrath toward you"). In the data: damage, snare and sub effect 8441 `pulled`, **no `hostileup`**, so the BOOSTHATE test cannot see it |
| Rugissement narquois | Provoking Roar | `KN_MASSIVEPROVOKE` | 25 | 12 s | Centred on the caster, up to 6 enemies, 8 m, `hostileup` 1151 |
| Saint Châtiment | Empyrean Chastisement | `KN_ABYSALJUDGEMENT` | 10 | 6 s | Damage, **costs 2000 DP** |
| Armure empyréenne | Empyrean Armor | `KN_STONEBODY` | 13 | 300 s | Heals 25 %, then +HP buff for 180 s |
| Dévotion inébranlable | Unwavering Devotion | `WA_STEADINESS` | 28 | 180 s | +800 stun/stumble resistance for 90 s |
| Main de la guérison | Hand of Healing | `KN_DIVINEHAND` | 31 | 1800 s | Heals 100 %, **costs 2000 DP** |
| Peau de fer | Iron Skin | `KN_IRONBODY` | 40 | 180 s | Shield; needs a weapon in hand |
| Dissipation de choc | Remove Shock I | `ALL_SHOCKREFLECT` | 40 | 60 s | Chain skill: only castable while stunned, staggered, stumbling, spinning or airborne |
| Rafraîchir l'esprit | Refresh Spirit | `KN_PROTECTPROUD` | 48 | 60 s | Chain skill, 1.5 s after Remove Shock; heals 25 % |
| Effet de l'esprit têtu (not to use) | Stubborn Spirit | `KN_MOVINGSTANCE` | 10 | — | Toggle stance |
| Garde du corps (not to use) | Bodyguard | `KN_GRANDPROTECTION` | 37 | 120 s | Maintained, party target |

"ED" in the document is the engine's DP (`dp` start condition and `dpuse` action). The Asmodian counterpart of Empyrean Armor that the document
mentions could not be found: the templar tree is not split by race in `skill_tree.xml`, so both factions seem to share these entries. To confirm on
an Asmodian templar before anything depends on it.

## What exists, and where it disagrees with the document

**Which rules need a group.** The guide confirmed the defensive rules hold when the templar is alone too, so the playbook splits in two. *Defensives*
(health thresholds, DP, crowd control) are consulted solo and in a group, and replace the generic defensive and heal rules for a templar either way.
*Aggro* (taunts, peeling, Roar, marking) is group play by nature and is consulted only with a team; a solo templar taunting the monster already
hitting it would be a wasted cast, which is what the existing rule says.

- `PlayerBotAI.useBestSkill` already prefers a taunt in a group (`isTaunt`, `BOOSTHATE`) and pulls back a monster hitting a mate
  (`BotGroupManager.enemyLooseOnAMate`).
- **It spends taunts freely**, the guide wants Taunt and Capture saved for peeling, and Roar fired whenever it is available.
- `BotSkillManager.tryDefensiveCooldown` fires every held-back defensive at 70 % health, and `tryHealSelf` heals at 50 %. Together they would spend
  **Hand of Healing at 50 %** and Iron Skin at 70 %, both against the document (20 % and 50 %).
- Nothing marks a target. `BotGroupManager.targetToAssist` makes the group follow the tank's target, then the leader's.
- Nothing reacts to being crowd controlled, so Unwavering Devotion and the Remove Shock chain are never used.
- The offensive pick takes any damaging skill, area ones included. The guide wants AoE left out except Provoking Roar.
- Mana: the bot sits down below 35 % mana. The guide says a templar never needs to; probably true, to be measured rather than assumed.

## The marking system

The engine has it: `TemporaryPlayerTeam.updateBrand(brandId, targetObjectId)` stores the brand and sends `SM_SHOW_BRAND` to the members.
`CM_SHOW_BRAND` only lets the **leader** (or an alliance captain) call it; the bot calls `updateBrand` directly, which skips that check. The guide's
`/Brand 14` is brand id 14, the skull.

Needs one small core change: `targetIdsByBrandId` is `protected` with no reader, so the tank cannot ask whether a mark exists. A
`getBrandedTarget(int brandId)` on `TemporaryPlayerTeam` is the whole patch, and goes in `playerbot-architecture.md` with the other two.

Rules, as confirmed: one skull at a time, other kinds of mark being ignored; an existing skull is never moved or replaced (so a human leader's wins); the tank marks the enemy with the **least absolute HP** at engagement; when the marked one dies, it marks the next lowest. The group side is the cheap half: `targetToAssist` looks at the mark first,
so every bot in the group follows it, and so does any real player who looks at the skull.

## Design: a playbook per class

The guide has written more of these documents, so the first job is not templar code but the place for it. Proposal: a small `ClassPlaybook` in
`playerbot/combat/playbook/` that `PlayerBotAI.useBestSkill` consults **before** the generic order and that may answer "I did something" or "nothing
to say, carry on". `TemplarPlaybook` is the first. Everything not covered falls through to today's code, so a class without a playbook behaves
exactly as it does now.

The decisions are a pure function of a `Situation` (health %, DP, which groups are off cooldown, which buffs are up, whether the group is engaged,
enemies on the bot, enemies on a mate, time since last crowd control). That is the shape `BotDirector.sort` has, and for the same reason: a rule
that can be checked without a world can have its thresholds argued about in a test instead of in a play session.

## Milestones, in verifiable order

1. **Playbook seam, no behaviour change.** Interface, a registry by class, the templar playbook returning "nothing to say", one call at the top of `useBestSkill`. `Situation` is left to milestone 2, where the first rule gives it something to hold. Done when the existing tests
   pass and a templar plays as before.
2. **Defensives by the document, solo and grouped.** *Built: thresholds unit tested, the in game check still to do.* Hand of Healing at < 20 % health with 2000 DP; Empyrean Armor under 75 %; Iron Skin under 50 % while Armor is
   not up; and the generic defensive and heal rules told to leave these three alone. Chastisement used on cooldown from 10 to 30 while DP is
   2000 or more, and not from 31 so the points are kept for Hand of Healing. Unit tests for every threshold. In game: a log line per use with
   health and DP, over a dozen fights.
3. **Aggro as the guide describes it.** *Built, without the retargeting: The guide also has the tank keep attacking the loose monster until it has its attention, and doing that now would drag the whole group onto it, since they follow the tank's target. That half waits for the mark in milestone 4. Capture is named by its skill group, not by an effect.* Roar on cooldown whenever an enemy is on the group; Taunt/Capture only to peel (an enemy whose victim is
   not the tank), preferring whichever is ready; Capture counted as a taunt, as the guide confirms. Measure before and after:
   times a monster's most hated target is not the tank during a three monster pull.
4. **Marking.** *Built (core getter `getBrandedTarget`, `BotMarks`, the group following the skull, the tank marking before it assists and as it fights). Not built: keeping the tank on a monster that has got loose until it has its attention, because it is a state the bot has to remember from tick to tick, which is the same per-bot memory milestone 5 needs for crowd control.* The core getter, the tank's marking, `targetToAssist` reading the mark, clearing it on death. In game: a group of three with a
   cleric and a gladiator, watching all three follow the skull.
5. **Crowd control.** *Built; what was unverified is settled in the code, not yet in play.* Remember the last time the bot was stunned, knocked down, spun or drained of ether; Unwavering Devotion when it comes
   free, once per cooldown. Remove Shock then Refresh Spirit (the latter only under 75 % health). Settled by reading the engine: a stunned character may cast only skills with an evade effect (`Skill.canUseSkill`), which
   Remove Shock has, so nothing needed patching. Refresh Spirit asks for the chain Remove Shock opens (`ChainCondition`), and any other skill cast in between
   closes it, so it is the very next thing the templar casts. Devotion's five resistances are exactly the five states Remove Shock accepts: stun, stumble,
   stagger (knockback), spin and held in the air. The memory of "was just controlled" lives in `PlayerBotAI.lastControlledAt`.
6. **Exclusions and mana.** *Built for the exclusions; the mana half is instrumentation, by design.* No Stubborn Spirit, no Bodyguard, no area skill other than Roar for a tank in a group. Then watch mana across a long
   instance before deciding whether the rest rule may be skipped for a tank.
   The exclusions are by skill group (Stubborn Spirit, Bodyguard: never) and by property (any area attack on enemies while in a group; Roar is a move
   and so exempt). Six of the templar's skills are such areas: Divine Grasp, Punishing Wave, Illusion Chains, Sword Storm and the roar. On mana,
   nothing was changed: the rest rule only applies *between* fights for a tank (`mustHoldTheLine` keeps it on its feet while the group is engaged), so it
   costs the group nothing, and a templar whose mana did run out would lose its defensives to the engine's refusal. A debug line is written whenever a
   templar is under 25 % mana in a fight, so a long instance settles it.

## Where this stands

All six milestones are built and unit tested (19 cases in `TemplarPlaybookTest`, 3 in `BotMarksTest`, 2 in `BotPlaybooksTest`). None has been watched in
game. What to look at first, by milestone: DP actually accumulating on a bot (2), a taunt cast at a monster that is not selected landing (3), the skull
appearing on a real client (4), whether a short stun is ever seen by a once a second check (5), the mana line staying silent (6). Left out on
purpose: keeping the tank on a loose monster until it has its attention (3, needs per bot memory).

## Answered by the guide's author

1. **Capture is a taunt** (tooltip: "increasing its wrath toward you"), although the data carries no `hostileup` for it. Open on our side: whether the
   pull alone puts the monster on the tank in the engine. Milestone 3 measures it before relying on it.
2. **"Peu de pierre" was a slip for "peau de fer"** (Iron Skin).
3. **The defensive rules hold solo too.** Aggro stays group only.
4. **Only the skull counts as a mark.** Another kind of mark present does not stop the tank placing the skull; an existing skull is never moved or
   replaced, a human leader's included. So the core getter is `getBrandedTarget(14)`.
5. **"Least HP" is absolute**, not a percentage.
