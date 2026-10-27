# Chanter: implementation plan

Status: milestones 1 (the seam), 2 (mantras), 3 (buffs and the dispel), 4 (heals), 5 (resurrection), 6 (divine power) 7 (offence, the part that needs no measuring) and 8 (stigmas) built and unit tested, validated in game on 27 October with a player in the group; milestone 0 (naming the skills) done from the data, every name settled. Nothing watched in game. Source: the guide's document "Aède 101" (eight pages, in French): the chanter is a melee DPS and a support, who keeps up to
three mantras running for the allies within 25 m. The pattern is the templar's ([templar-plan.md](templar-plan.md)): a `ClassPlaybook`, rules by skill
**group**, a pure `decide` over a `Situation`, a data test. This file is only the distance between the guide and what the module does today.

Names below are the English client names from `skill_templates.xml`, matched to the guide's French ones by level and by the guide's own description.
Milestone 0 checked them against `skill_templates.xml` and `skill_tree.xml`; the two it could not settle are marked (?).

## What the guide asks for, mapped to the data

| The guide | Client name | `group` | From | Cooldown | Notes |
|---|---|---|---|---|---|
| Prière de protection | Protectorate's Prayer | `CH_BLESSOFROCK` | 10 | 0.5 s | `TARGETORME`: reaches allies. The group buff; replaced from 45 by the stigma below |
| Bénédiction de la pierre | Blessing of Rock | `CH_BLESSPROTECT` | 45 (stigma) | 0.5 s | Replaces the prayer from 45. Both may share an effect id (see traps) |
| Promesse de la terre | Promise of Earth | `CH_IMBUEEARTH` | 16 | 10 s | Self, kept up for ever |
| Sort de rage | Rage Spell | `CH_NIGHTWISH` | 20 | 15 s | Self, kept up for ever |
| Quartier plus sûr | Protective Ward | `CH_PROTECTSELF` | 22 | 120 s | Self shield, 10 s, +500 stumble resistance. Under 50 % health |
| Accélération magique | Word of Quickness | `CH_ZEST` | 37 | 300 s | Party, -50 % casting time for 15 s. Not the stigma "Acceleration Cheer" (`CH_ENCOURAGERUN`) |
| Parole d'éveil | Ascension Spell | `CH_IMPROVEDPOWERATTACK` | 40 | 10 s | Self, lasts exactly 120 s, which is the guide's "every 2 minutes". In combat |
| Parade parfaite / Frappe de parade | Perfect Parry / Parrying Strike | `CH_CROSSPARRY` / `CH_PARRYINGSTRIKE` | 44 / 25 | 30 s each | The buff goes on together with the strike |
| L'aile du martinet | Swiftwing | `CH_CHANT_IMPROVEDFLY` | 46 | 300 s | Party, +8000 flight speed for 30 s, range 20 m, cast on every take-off (the guide's answer) |
| Rideau divin | Block Curtain | `CH_ANGELICWALL` | 51 | 300 s | Party. Solo: self under 30 %. Grouped: the tank under 30 % |
| Inarrêtable | Unstoppable | `CH_FRONTALATTACK` | 57 | 180 s | Self, in combat whenever ready |
| Mantras | Celerity, Shield, Revival, Wind, Invincibility, Hit | `CH_CHANT_IMPROVEDMOVE`, `_IMPROVEDDEFEND`, `_REGENERATION`, `_WINDYWINGS`, `_INVINCIBLE`, `_IMPROVEDCRITICAL` | 10, 13, 22, 25, 28, 39 | 0 | Toggles, the engine ends the oldest past three |
| Lumière de guérison | Healing Light | `CL_HEAL` | 1 (priest tree) | 0 | Solo only |
| Lumière de résurrection | Light of Resurrection | `CL_REVIVE` | 10 | 0 | `FRIEND`/`TARGET`: aimed at a dead ally |
| Sort de renaissance | Word of Revival | `CH_REJUVENATION` | 13 | 5 s | Solo only |
| Magie de la récupération | Recovery Spell | `CH_STIGMA_RECOVERWORD` | 45 | 10 s | Solo only, and it is **not** a stigma in the data |
| Récupération de l'endurance | Stamina Restoration | `CH_CHAKRA` | 45 | 60 s | Self, HP or MP under 60 % (the guide's answer) |
| Rayonnement de l'endurance | Energy Discharge | `CH_RENEWEDVITALITY` | 59 | 60 s | Self, **a dispel** (`dispeldebuff`, level 1, 268 mp), used as one: a debuff is on the chanter. Not a heal, whatever the guide's list says |
| Le mot du vent (ED) | Word of Wind | `CH_PROVIDENCE` | 31 | 1800 s | Party buff, **costs 2000 DP**, on cooldown in combat |
| Protection divine (ED) | Winter Circle | `CH_ABYSSGLORIA` | 10 | 60 s | Self, **costs 2000 DP**, +100 % physical attack for 30 s |
| Do not use | Smite ("Béguin", the guide's "Châtiment"), Binding Word, Tremor, Stilling Word | `CL_SMITE`, `CH_SWORDBIND`, `CH_TREMOR`, `CH_ETERNALSEAL` | 1, 20, 40, 50 | | Never cast. Smite is the priest's level 1 spell, learnt in the priest tree and kept |
| Bénédiction du rocher | Blessing of Guardianship | `CL_BLESSOFROCK` | 5 | | The level 5 form of the group buff; Protectorate's Prayer replaces it at 10. Shares the effect with it: see the traps |

The offensive list is the guide's "debuffs and handicaps", cast when ready, **as long as a chain is not broken**. The data states the chains, which
is what the guide calls "enchaînement":

| Chain | Links | What each does |
|---|---|---|
| J | Hallowed Strike `CL_HALLOWSWING` (opener, attack speed -30 % on the target) → Booming Strike `CH_SONICSWING` (defence -5 %) or Booming Smash `CH_SONICRUSH` (slow 50 %) | The guide's "Grève sacrée", "Grève en plein essor", "Booming smash" |
| D | Meteor Strike `CH_VIOLENTSWING` (opener, fly speed down) → Incandescent Blow `CH_TRIANGLESHOCK` (defence -50) → Pentacle Shock `CH_PENTACLESHOCK` (stumble) | "Frappe de météores", "Souffle incandescent", "Choc de pentacle" |
| B | Infernal Blaze `CL_SONICERUPTION`: a ranged spell opener, stun 3 s | "Embrasement incandescent" |
| Single | Inescapable Judgment `CH_MORTALSTRIKE` (stun 4 s), Parrying Strike `CH_PARRYINGSTRIKE` (stun 3 s), Soul Crush `CH_HEROICSTRIKE` (opener of chain I, stumble) | |
| After a shock | Backshock `CH_SHOCKATTACK` (53, stun 3 s) asks for chain `ALL_CHAINA_1TH` opened 1.5 s before | **Only Remove Shock (40) or Rise open it**, so the generic order never reaches it. The guide confirms: Remove Shock when stunned, then Backshock, as the templar does with Refresh Spirit (milestone 7) |

"ED" in the guide is the engine's DP, as for the templar.

## What existed, and where it disagreed with the guide

As found before milestones 1 to 8; what each milestone did about it is under "Milestones" below.

Read from the code, not watched:

- **The class is `SUPPORT`** (`group-roles.md`): it tends to the group first, then fights. The guide says the opposite: a DPS that buffs, and keeps its heals
  for itself. `useBestSkill` therefore lets a chanter heal mates at `HEAL_ALLY_PERCENT` with Healing Light, Word of Revival and Recovery Spell, all of which
  the guide forbids in a group.
- **Mantras** (`BotSkillManager.tryChantMantra`): up to three, **highest skill id first**. The guide gives an order, and a different one solo and grouped:
  solo Hit > Invincibility > Revival > Celerity > Shield > Wind; grouped Hit > Invincibility > Celerity > Revival > Shield > Wind.
- **Self buffs** (`tryBuffSelf`) already keep up Promise of Earth and Rage Spell, because each lasts as long as its cooldown. Ascension Spell would be cast
  out of combat as well, where the guide wants it in combat, every two minutes.
- **Group buffs**: `tendToTheGroup` calls `tryBuffAlly`, which already puts Protectorate's Prayer on a mate that lacks it (it is `TARGETORME`), and the
  stack check covers re-applying after a resurrection. It also takes any other buff that reaches others, which the guide does not ask for.
- **Defensives**: `tryDefensiveCooldown` fires a held-back defensive at 70 % health (cooldown up to 3 min) or 50 % (over), so Protective Ward goes at 70 %
  where the guide wants 50 %, and Block Curtain, a party buff, is aimed at nobody in particular.
- **Resurrection**: nothing in the module casts a resurrection on an ally. A dead bot is brought back by `PlayerBotAI.revive()` after ten seconds, so a
  chanter would have nothing to resurrect for a bot.
- **DP**: nothing spends it except the templar playbook.
- **Behind the enemy** exists (`BotPosition.BEHIND`, `group-positioning-plan.md`), but the guide adds "only if the enemy is not targeting the chanter".
- **Flight**: ambient flight exists ([flight-plan.md](../flight/flight-plan.md)); nothing casts Swiftwing as the bot takes off.
- **Chains**: the generic order already tries a continuation before anything else and does not re-apply a debuff the target carries
  ([combat-skills.md](../combat/combat-skills.md)). That covers "use them when ready without breaking a chain" and is to be measured, not rewritten.

## Design

`ChanterPlaybook` beside `TemplarPlaybook`, registered in `BotPlaybooks`. Two things the interface lacks, since the chanter does as much outside a fight
as in one:

1. **An out-of-combat hook**, `boolean tend(Player bot)` (default false), called where `serveTheGroup` and the solo chain call `tryBuffSelf` /
   `tryChantMantra`, ahead of them. It carries the buff order, mantras, post-fight healing and resurrection, and answers "nothing to say" otherwise.
2. **A mantra ranking**, `List<String> mantraOrder(boolean inTeam)` (default empty = today's highest id first), read by `tryChantMantra`. Data, not a rule,
   in the way `preferredStigmas` is.

`act` (in combat) and `claims` stay as they are. `claims` is what stops the generic heal and defensive rules from casting what the chanter decides alone:
Healing Light, Word of Revival, Recovery Spell **in a group** (solo they stay the generic heal's and the critical ladder's); Protective Ward, Block Curtain,
Perfect Parry, Word of Wind, Word of Quickness always; the four forbidden groups always, as the templar's `FORBIDDEN_GROUPS`. The decisions are a pure
function of a `Situation`, thresholds argued in a test (`ChanterPlaybookTest`), and a data test puts every skill template through `claimsSkill`
(`ChanterPlaybookDataTest`), because a template with no group or no properties has already thrown on every tick once.

## Milestones, in verifiable order

0. **Name the skills, no code.** *Done, from the data.* Run `tools/skill-lookup.ps1 -Class CHANTER` for every (?) above, and for the levels the dump does not show (nothing under
   10 appears in the class tree, so Healing Light at 1, Hallowed Strike at 3 and the lvl 5 "Bénédiction du rocher" must be found by name). Settle
   Protection divine and the three stigmas the guide does not name (Coup mortel). Write the confirmed table back into this file. Done when no (?) is left.
   *Found: Protection divine is Winter Circle (2000 DP). Levels 1 to 9 are in the **priest** tree (`classId="PRIEST"`, the base class chanter and cleric both
   come from), which is why the class tree starts at 10: Smite 1 (the guide's "Béguin", the priest's first spell), Healing Light 1, Hallowed Strike 3, Blessing
   of Guardianship 5, Infernal Blaze 7, so the guide's levels are right and the chanter has them before 10. Energy Discharge has one rank and it is a dispel.
   Coup mortel is Leaping Flash, by the guide's answer.*
1. **The seam, no behaviour change.** *Built: `ChanterPlaybook` registered and silent, `ClassPlaybook.tend` and `mantraOrder` (read by `tryChantMantra`, empty means today's order), `tend` called ahead of `tryBuffSelf` solo and in a group, and `ChanterPlaybookDataTest` holding the six mantra groups and the three forbidden ones against the data. The forbidden groups are named but not yet claimed.* `ChanterPlaybook` returning "nothing to say", the `tend` hook and `mantraOrder` defaults, the two data tests with
   the group names of this file. Done when the existing tests pass and a chanter plays as before.
2. **Mantras by the guide's order.** *Built: `ChanterPlaybook.SOLO_MANTRAS` and `GROUP_MANTRAS`, sorted by `BotSkillManager.byMantraOrder` (stable, null safe: it was thrown by a template with no group in the first test run). Tests: both orders are a permutation of the six groups, and the real templates sort by them. In game still to check, below.*  `mantraOrder` for solo and grouped, from the six groups above. Take care of the count: `activeMantras` counts by
   stack group and the engine drops the oldest past three, so a better mantra learnt later must not turn two others off in a loop. In game: which three
   run at levels 13, 28 and 39, solo and grouped, and that a mate within 25 m carries them.
3. **Buffs, and the dispel.** *Built, in `ChanterPlaybook.decide`: dispel (in a fight and out of one) > Block Curtain (tank under 30 %, or the chanter alone or with no tank) > Protective Ward under 50 % > Ascension Spell when off > Unstoppable when off > Perfect Parry when Parrying Strike is ready, then the strike. Swiftwing through a new `ClassPlaybook.onTakeOff` called from `BotFlight.begin`. The group buff and the Blessing of Rock swap were left to the generic order on purpose: the two share effect id 916601, so `coversTheSameEffect` already stops the loop, and it takes the higher id, the stigma, first. Claimed from the generic order: all of those but Parrying Strike. 6 pure tests for the thresholds, 3 data tests. Not built: re-casting Prayer after a resurrection is left to the existing stack check, to be measured in game.* Energy Discharge as a dispel, when the chanter carries a dispellable debuff and it is ready: needs a query on the bot's effects (what `isCrowdControlled` is to stuns), and a check that the debuff's `dispel_category` and level are within `dispel_level` 1. Protectorate's Prayer on every member including after a resurrection (already so, to be measured), swapped for Blessing of Rock from 45
   (check `coversTheSameEffect`: the two may share an effect id, which is the loop the buff code already documents). Protective Ward under 50 %. Perfect
   Parry together with Parrying Strike. Ascension Spell in combat, every two minutes. Unstoppable in combat when ready. Swiftwing as the bot takes off
   (the hook is wherever ambient flight starts). Block Curtain: self under 30 % solo, the tank under 30 % grouped.
4. **Healing as the guide rations it.** *Built. Claimed from the generic order: Healing Light, Word of Revival, Recovery Spell, Stamina Restoration and Word of Quickness. Stamina Restoration under 60 % health or mana, in a fight or after one, in a group too. Alone and after a fight: Healing Light under 80 %. Alone in a fight: under 20 % the sequence Word of Quickness, Recovery Spell, Word of Revival, Healing Light, each when ready, and it goes on until full: `PlayerBotAI.mendingToFull` holds that it began, set by the playbook and cleared at 100 % or on joining a group. In a group nothing else heals, itself included, as the guide says. 11 pure tests. Left open: Healing Burst (`CH_SURPERIORHEAL`, an advanced stigma, `TARGETORME`) is not claimed, so were a chanter to wear it the generic order would heal mates with it; milestone 8 must keep it out of the fitter or claim it. Energy Discharge as a dispel was done in milestone 3.* In a group no Healing Light, Word of Revival or Recovery Spell. Solo: Healing Light out of combat under 80 %;
   Stamina Restoration under 60 % on health or mana; the critical ladder under 20 %: Word of Quickness, Recovery Spell,
   Word of Revival on itself, then Healing Light until full.
5. **Resurrection.** *Built. How the engine delivers it, read: Light of Resurrection (`CL_REVIVE`, 15 m, a 6 s cast that cannot move) only marks the dead player (`setPlayerResActivate`) and sends a window; the player's click is `CM_REVIVE` and ends in `PlayerReviveService.skillRevive`, which comes back on 35 % with soul sickness, where they lay. A bot never clicks, so `PlayerBotAI.handleDeath` now notices the mark while it waits for its obelisk revival, cancels that, and takes the resurrection two seconds later (`acceptResurrection`); without a mark nothing changes. The chanter side: `BotGroupManager.mateToResurrect` finds a dead mate within 15 m that nobody is already raising, and says no when a human cleric or chanter stands within 25 m of the body (they do it themselves) or a bot chanter with a lower id does. A bot cleric does not count, since it has no playbook to resurrect with. In a fight: Word of Quickness if it is not up, then Light of Resurrection; after one, the spell alone. Not built: walking to a body further than 15 m, and the solo stranger case (milestone 9). 2 pure tests; the engine side is not unit tested, it needs a bot to die in a group.*  Light of Resurrection on a dead mate, bot or player, only when nobody else in the group can resurrect (a cleric, another chanter nearer the body); alone or after the fight; in a fight Word of Quickness first.
   Needs two things the module lacks: a target query for a dead mate (beside `mostHurtMember`), and an answer to **how a bot accepts a resurrection** (see
   open questions). Out of combat, alone, a dead member of the faction is resurrected too.
6. **DP.** *Built. `divinePowerMove`: Word of Wind whenever it is ready and 2000 DP is held; Divine Protection (Winter Circle) under level 31 whenever ready and 2000 held, and from 31 only with 4000, so that spending it still leaves the Wind's 2000. Both claimed from the generic order, which was casting Winter Circle as a fight opener. The four forbidden groups (Smite, Binding Word, Tremor, Stilling Word) are claimed now, which closes the "named, not claimed" of milestone 1. 3 pure tests plus the claims in the data test. The DP cost is read from the data (2000 for both), the engine itself refuses a cast it cannot pay, which is why the playbook asks only so as not to waste the tick.*  Divine Protection before 31 whenever ready in combat, and after 31 only with 4000 DP; Word of Wind whenever ready in combat. The templar's
   `dp` read and its "keep 2000" rule are the pattern; Tremor and Stilling Word stay forbidden.
7. **Offence.** *Built: Remove Shock when the chanter is held, then Backshock at once while the chain is open (the guide's answer: it is how Backshock is ever cast, since nothing else opens `ALL_CHAINA_1TH`); "behind, only if not targeted" as `BotStations.isHeldByEnemy`, for the chanter only since only its guide says so; and a debug line on every skill any bot casts (`BotSkillManager.cast`), which is the measurement tool. Not done, and it needs a server: the measurement itself. Enable DEBUG for `com.aionemu.gameserver.playerbot.combat.BotSkillManager` in `logback.xml`, watch a chanter through a dozen fights, and compare the sequence with the chains in the table above. Area attacks were not claimed: unlike the templar's guide the chanter's names only Tremor and Stilling Word, which are forbidden already.*  Nothing new if the generic order already plays the chains; milestone 7 is the measurement: in a fight, log the sequence of skills cast and
   compare with the guide's openers (Inescapable Judgment and Infernal Blaze at range, then the chain, then the conditionals). Rule changes only where the
   log disagrees. Area attacks left out in a group, as for the templar. The "behind, only if not targeted" clause goes into `BotStations`.
8. **Stigmas** (the guide's section is "WIP"). *Built, with the guide's list as it stands: `preferredStigmas` returns Word of Life, Annihilation, Leaping Flash for the regular sockets and Blessing of Rock, Disorienting Blow, Numbing Blow for the advanced ones, and the fitter fills empty sockets in that order. Word of Life is a move of the playbook, claimed from the generic order: alone when the chanter is under 60 %, in a group when two of it and the mates near it are (`hurtAround`), in a fight and after one. Healing Burst, the advanced stigma that heals a mate, is claimed as never cast, which closes the open point of milestone 4; the fitter takes the three on the list first, so it would only be worn if a socket stayed empty. Annihilation, Leaping Flash and the two blows are left to the generic offence. The list is the guide's work in progress: revisit when it is final. 2 pure tests, and the group names in the data test.*
9. **Optional.** Solo, out of combat: Protectorate's Prayer on a mate of the faction that lacks it, and a resurrection for a dead one. Needs a perception
   query for friendly players around the bot, and a rate limit so that two chanters do not cast at each other for ever.

## Stigmas

By the socket it fills, with the guide's level (the same as the templar's: regular 20, 30, 40; advanced 45, 50, 55) and the groups found in the data:

| Sockets | Stigma | `group` | Role |
|---|---|---|---|
| Regular, 20 | Word of Life | `CH_IMPROVEDLIFESTREAM` | Group heal over time: solo under 60 % health, grouped when two mates (the chanter counted) are under 60 % |
| Regular, 30 | Annihilation | `CH_TRIPLETHRUST` | Damage, "three times in a row" |
| Regular, 40 | "Coup mortel" = Leaping Flash | `CH_HARSHTHRUST` | Damage; the chanter's only charge skill, so the guide's two stages. Also a gap closer for the generic order |
| Advanced, 45 | Blessing of Rock | `CH_BLESSPROTECT` | Replaces the prayer |
| Advanced, 50 | Disorienting Blow | `CH_SHOCKWAVE` | Damage and a stumble |
| Advanced, 55 | Numbing Blow | `CH_SLOWCRASH` | Damage and a handicap |

`preferredStigmas` takes the six, as for the templar, and the fitter only fills empty sockets. Word of Life is the one rule beyond the order: a heal the
guide spends by headcount, so it is a move of the playbook and not left to the generic rules. The tier numbers in the data (tier 1, 2, 3) and the guide's
sockets agree for the first and last, but the level at which each socket opens is to be confirmed against `stigmas.md`.

## Open questions for the guide's author

1. ~~Stamina Restoration threshold~~ answered: 60 %. Energy Discharge is a dispel, so its condition is "a dispellable debuff is on the chanter". Settled.
2. ~~Protection divine~~ is Winter Circle (2000 DP). ~~Quartier plus sûr~~ is Protective Ward (a shield). Both settled by the data. Whether Ward is used grouped under 50 % as well is still to say.
3. ~~Béguin~~ is Smite, the priest's first spell (`CL_SMITE`): never cast. ~~Coup mortel~~ is Leaping Flash. ~~Backshock~~ follows Remove Shock when stunned. All three answered.
6. ~~Resurrect bots too?~~ Answered: both, when nobody else in the group can. Still open on our side: a bot is brought back by itself after ten seconds today, so how it accepts a resurrection, and how a chanter knows another member can resurrect.
7. ~~Swiftwing~~ answered: on every take-off.

## Traps to check before trusting a rule

- `Skill.canUseSkill` resets a chain whenever a non chain skill passes its checks: any playbook move cast in the middle of the chain (Ascension Spell, a
  buff) breaks it. The templar's Refresh Spirit is the same case and is put first. Buffs go on **before** the first blow, never between.
- A `ME` skill aimed at somebody else is quietly turned back on the caster (`FirstTargetProperty`). Prayer and Light of Resurrection are `TARGETORME` and
  `TARGET`, so they do reach; Block Curtain, Word of Quickness and the mantras are `ME` and `PARTY`, and reach the group by their own area.
- A toggle cast while on turns itself off. The mantra code asks first; a playbook that casts one must too.
- Resurrection is asked of the dead, not forced: how the engine delivers it (a window for the target to accept) decides whether a bot can be the target.
  Read `ResurrectEffect` before milestone 5.

## Where this stands

Milestones 0 to 8 are done, 7 without its measurement. Left: 9 (optional), and above all a first look at a chanter in game: nothing from milestone 2 on has been watched. Nothing is left for the guide's author.
