# Gladiator: implementation plan

Status: all nine milestones built and unit tested (42 pure tests, 8 data tests), validated in game on 27 October, solo and grouped; a move the engine refuses gives way to
the next in the order (`act`), found in review; milestone 0 (naming the skills) done, every
name settled against the French client's strings, the guide's questions answered. Source: the guide's document
"Gladiateur 101" (six pages, in French): the gladiator is a melee physical damage dealer with a polearm, sustained by lifesteal and a heal of its
own, with many area attacks, whose game is knocking its target down (stumble) to open its conditional skills. After the tank it is the one best fit
to take hits. The pattern is the templar's ([templar-plan.md](templar-plan.md)): a `ClassPlaybook`, rules by skill **group**, a pure `decide` over
a `Situation`, a data test. This file is only the distance between the guide and what the module does today.

The guide gives the levels of another client version; the levels below are 4.8's skill tree (`skill-lookup.ps1 -Class GLADIATOR`). Where they
differ the data wins: Earthquake Wave and Crippling Cut are learnt at 20, not 30 and 40; Severe Precision Cut at 45, not 50; Rage at 12, not 7.

## What the guide asks for, mapped to the data

| The guide | Client name | `group` | From | Cooldown | Notes from the data |
|---|---|---|---|---|---|
| Ferocious Strike, Robust Blow, Wrathful Strike | same | `WA_ROBUSTHIT`, `WA_ROBUSTBLOW`, `FI_ROBUSTCRASH` | 11, 13, 13 | 10, 8, 20 s | Chain A, the filler. Wrathful Strike stumbles (sub-effect 8218) |
| Rupture, Reckless Strike | same | `FI_ROBUSTSMASH`, `FI_ROBUSTIMPULSE` | 16, 31 | 8, 8 s | Chain A: Rupture is the other third link (when Wrathful Strike is on cooldown), Reckless Strike follows it |
| Body Smash, Repeated Body Smash | Body Smash, Body Combo | `WA_POWERSLASH`, `FI_TRIPLESLASH` | 10, 55 | 12, 12 s | Chain B |
| Body Blow (tank only) | Body Slice | `FI_BODYSMASH` | 62 | 12 s | Chain B's third link, `hostileup`: banned outside the tank-dead mode |
| Cleave, Great Cleave, Righteous Cleave | same | `FI_WINGBLADE`, `FI_GUSTBLADE`, `FI_STORMBLADE` | 19, 45, 56 | 18, 14, 14 s | Chain D. Cleave reaches 15 m plus the weapon and snares 6 s: the opener from afar |
| Fury Absorption | Absorbing Fury | `FI_SEISMICDRAIN` | 10 | 10 s | Chain C opener, area 7 m around the caster, lifesteal |
| Absorption Wave | Roiling Hack | `FI_DRAINWAVE` | 10 | 40 s | Chain C second link, area 7 m, lifesteal |
| Pressure Wave | same | `FI_SEISMICIMPACT` | 37 | 8 s | Chain C second link, the alternative: "instead of Absorption Wave if health > 80 % or it is on cooldown" |
| Seismic Billow | same | `FI_SEISMICBILLOW` | 28 | 8 s | Chain C third link, area 7 m, stumbles |
| Weakening Blow | Severe Weakening Blow | `FI_ENFEEBLEHIT` | 34 | 30 s | Opens chain E, lowers defences 10 s. The opener in reach |
| Energy Explosion | Energy Impact | `FI_CONSECUTIVEHIT` | 58 | 10 s | Chain E second link |
| Aerial Lockdown | same | `FI_RAISINGIMPACT` | 22 | 180 s | Puts Aether's Hold (`OPENAERIAL`, 3 s) on the target |
| Crashing Blow | same | `FI_DROPIMPACT` | 25 | 60 s | Target must be `OPENAERIAL`: after Aerial Lockdown |
| Crushing Blow | same | `FI_DESTRUCTBLOW` | 30 | 180 s | Stumbles (8218) |
| Springing Slice | same | `FI_JUMPINGCUT` | 28 | 12 s | Target must be `STUMBLE`; a dash, 15 m |
| Final Strike | same | `FI_CRUELBLOW` | 52 | 60 s | Target must be `STUMBLE` or `OPENAERIAL` |
| Counter Leech | same | `FI_COUNTERDRAIN` | 60 | 60 s | Lifesteal of 150 %. No condition in the data: the guide's "under 60 % health" is the only one |
| Piercing Rupture | same | `FI_STIGMA_SHOCKBURST` | 50 | 60 s | Area 10 m, lowers defences 30 s |
| Explosion of Wrath | Wrathful Explosion | `FI_RAGEBURST` | 53 | 180 s | After Remove Shock (chain `ALL_CHAINA`), area 10 m, stumbles; preferred from 53 |
| Dragon Cut Variation | Whirling Advance | `FI_DRAGONSLASH` | 64 | 90 s | Area 7 m |
| Raging Blow | Ferocious Chop | `FI_FLYINGSLASH` | 65 | 180 s | A dash into an area of 7 m, 17 m reach: a gap closer |
| Combat Preparation | Slaughter | `FI_BLADEMODE` | 13 | toggle | +15 % physical attack, +300 magical accuracy. The only posture solo and grouped |
| Defence Preparation (tank only) | Defense Preparation | `FI_DEFENSEMODE` | 25 | toggle | +400 parry and block, more hate. Tank-dead mode only |
| Charge | same | `WA_DASH` | 16 | 240 s | A run speed buff, not a leap: when the target is more than 20 m away |
| Second Wind | same | `FI_SURVIVORSTANCE` | 20 | 180 s | Heal and max health for 60 s: under 50 % health |
| Armour of Attrition | Armor of Attrition | `FI_FORTITUDE` | 34 | 180 s | When something is attacking the gladiator |
| Unwavering Devotion | same | `WA_STEADINESS` | 37 | 180 s | After a stun, knockback, stumble, spin or Aether's Hold on itself |
| Remove Shock | Remove Shock I | `ALL_SHOCKREFLECT` | 40 | 60 s | Under control; opens `ALL_CHAINA` |
| Wrath | Ferocity | `FI_FIGHTERRAGE` | 48 | 60 s | Second link after Remove Shock, 8 s buff; Wrathful Explosion instead from 53 |
| Wall of Steel | same | `FI_PARRYFOCUS` | 42 | 300 s | Parries everything for 30 s: when something is attacking the gladiator |
| Shadow Rage | Berserking | `FI_BERSERKSTANCE` | 44 | 180 s | 30 s, attack and defence: in a fight as soon as it is ready, once in reach |
| Strengthen Wings | same | `FI_ENERGYWING` | 46 | 180 s | Already cast on take-off by `BotSkillManager.tryFlightBuff` |
| Explosion of Rage (DP) | same | `FI_ABYSALRAGE` | 10 | 10 s | **2000 DP**, big single target hit, stumbles |
| Daevic Fury (DP) | same | `FI_RAMPAGEMODE` | 31 | 1800 s | **2000 DP**, 30 s attack and attack speed; from 50 only if Zikel's Threat is not ready |
| Zikel's Threat (DP) | Empyrean Blessing | `FI_CRUSADE` | 50 | 1800 s | **4000 DP**, 30 s: physical attack +80 %, defence, speed. Preferred from 50 |
| Do not use | Rage, Taunt, Wrathful Wave, Mocking Blast | `WA_RAGE`, `WA_PROVOKE`, `FI_WHIRLWIND`, `FI_SLASHIGFORCE` | 12, 10, 40, 65 | | Rage is a chain A second link and would break the filler; Wrathful Wave is 4000 DP over 20 m; Taunt and Mocking Blast (Blade of Incitement, `hostileup`) are the tank-dead mode's |

Stigmas (the guide's section IV):

| The guide | Client name | `group` | Tier | Rule |
|---|---|---|---|---|
| Draining Blow | same | `FI_STIGMA_DRAINCUT` | 1, 20 | Target `STUMBLE`, and the gladiator under 60 % health |
| Earthquake Wave | same | `FI_HEAVYIMPACT` | 1, 20 | Area 10 m, snare: several enemies on the gladiator |
| Crippling Cut | same | `FI_CRIPPLINGCUT` | 1, 20 | Target `STUMBLE` |
| Sure Strike | same | `FI_BURSERKLANCE` | 2, 45 | Right after Weakening Blow (same 30 s cooldown) |
| Severe Precision Cut | same | `FI_CHARGINGSHOCK` | 2, 45 | Area 7 m, stumbles: several enemies |
| Draining Sword | same | `FI_DRAINSWORD` | 3, 55 | Lifesteal, under 40 % health (the guide's "above" was a slip) |

The chains the data states, which is what the guide calls combos (`<chain precategory category>`):

| Chain | Links |
|---|---|
| A, the filler | Ferocious Strike → Robust Blow (or Rage, banned) → Wrathful Strike (stumble) or Rupture → Reckless Strike after Rupture |
| B | Body Smash → Body Combo → Body Slice (banned) |
| C, the area combo | Absorbing Fury → Roiling Hack or Pressure Wave → Seismic Billow (stumble) |
| D, from afar | Cleave → Great Cleave → Righteous Cleave |
| E | Severe Weakening Blow, Sure Strike or Severe Precision Cut, each an opener → Energy Impact |
| `ALL_CHAINA` | Remove Shock → Ferocity or Wrathful Explosion |

The guide's "if it procs (10 %)" for Reckless Strike, Righteous Cleave and Seismic Billow is not in 4.8's data, where each is an ordinary link: a
third link is offered whenever the second went through. The generic order already tries a continuation before anything else (`continuesAChain`).

## What exists, and where it disagrees with the guide

- **No gladiator playbook.** The gladiator runs the generic order: chain continuations first, then the strongest stated damage. So it casts Rage
  (a chain A link), Taunt and Body Slice, Wrathful Wave alone (the 20 m ban is for groups only), and never puts Slaughter up: toggles are refused
  to the generic order, mantras and robots aside.
- **The conditionals are luck.** Springing Slice, Crippling Cut, Final Strike, Draining Blow and Crashing Blow are offered every tick and refused by
  the engine unless the target happens to be knocked down; the generic order does not read the target's state before asking.
- **Areas hit whatever stands there.** The only rule is the 20 m ban in a group (`isWideAreaAmongOthers`). The guide asks for more, solo and
  grouped: never catch an enemy that is not already in the fight. A 7 m area around a gladiator next to a camp pulls the camp.
- **One target at a time.** `BotTargetSelector` picks one monster; the guide's gladiator takes two or three of its level at above 90 % health.
- **It runs.** `retreatIfLosing` sends a lone bot off from three attackers; the guide's gladiator does not run, except from an elite or worse.
- **Crowd control, Unwavering Devotion and Remove Shock** exist for the templar (`lastControlledAt`); the gladiator can share them.
- **Divine power** is spent by the templar and the chanter through their playbooks, not by the generic order.
- **Role and place**: `BotRole.DAMAGE`, `BotPosition.BEHIND`; nothing in the guide asks to change either.

## Design

`GladiatorPlaybook`, registered for `GLADIATOR`, with `claims` over every group above that it times, the bans included, and a pure
`decide(Situation)` tested without a server. The `Situation` holds the level, health, DP, the skills ready, the target's state (`STUMBLE`,
`OPENAERIAL`), how many enemies are on the gladiator, whether something attacks it, whether an area would reach an enemy not in the fight, and
whether the group's templar is dead. The fight order, most urgent first:

1. **Survive**: Second Wind under 50 %; Counter Leech, Draining Blow (target down) and Draining Sword under their thresholds; Armor of Attrition and
   Wall of Steel when attacked; Unwavering Devotion after a control, Remove Shock under one, then Wrathful Explosion (53+) or Ferocity.
2. **Burst**: Berserking once in reach; Empyrean Blessing at 4000 DP from 50, else Daevic Fury at 2000 DP; Explosion of Rage at 2000 DP when no buff
   is waiting for the points.
3. **The target down**: Springing Slice, Crippling Cut, Final Strike, Draining Blow on a `STUMBLE` target; Crashing Blow and Final Strike on an
   `OPENAERIAL` one.
4. **Several enemies**: chain C (Pressure Wave when above 80 % or Roiling Hack is cooling), Earthquake Wave, Severe Precision Cut, Piercing
   Rupture, Whirling Advance, each only if no bystander is within its area.
5. **One enemy**: Severe Weakening Blow then Sure Strike; Aerial Lockdown; Crushing Blow; chain B; chain A as the filler.

Before contact: Cleave and its chain from 15 m; Charge beyond 20 m; Ferocious Chop as a gap closer from 65.

## Milestones, in verifiable order

0. **Name the skills.** *Done above, from the data; the names are the guide author's to confirm.*
1. **The seam and the claims.** *Built: `GladiatorPlaybook`, registered; `FORBIDDEN_GROUPS` (Rage, Taunt, Wrathful Wave, Body Slice, Mocking
   Blast, Defense Preparation) claimed and never cast; `GladiatorPlaybookDataTest` holds the groups and puts every template through the claims.*
   `GladiatorPlaybook` registered and silent, its groups and claims held by a data test (every group exists, every
   skill template through the claims), the bans in force. In game: no Rage, Taunt, Body Slice, Wrathful Wave or Mocking Blast in the debug lines.
2. **The posture.** *Built: `decide` puts Slaughter up when it is off, from `tend`, `prepare` and `act`, so out of a fight, before one and in one
   (a death takes it off). Pure tests in `GladiatorPlaybookTest`.* Slaughter up whenever it is off, solo and grouped, before a fight (`prepare`), checked first so that a toggle is never cast
   twice. In game: the buff on the bar at all times.
3. **Survival.** *Built in `decide`, in this order: Remove Shock under control and nothing else; Wrathful Explosion, else Ferocity, while its chain
   is open; Second Wind under 50 %; Draining Sword under 40 %; under 60 % Draining Blow on a target knocked down, else Counter Leech; when an enemy
   aims at the gladiator, Wall of Steel then Armor of Attrition, each when off; Unwavering Devotion within 8 s of a control; the posture last.
   15 pure tests. Wrathful Explosion is an area of 10 m and waits for milestone 5's bystander rule like the rest.* The step 1 rules, with the
   crowd control memory the templar uses.
4. **Conditionals.** *Built in `exploitTheOpening`, after the heal and the drains and before the defensives, since the window is three seconds:
   Final Strike on a target down or in the air; then on a target down Crippling Cut, then Springing Slice; on a target in the air Crashing Blow.
   The four are claimed, so the generic order no longer tries them blind. 5 more pure tests (20). Not done: casting the setups (Aerial Lockdown,
   Crushing Blow) on purpose, which is the single target order of milestone 6; for now the generic order casts them for their damage.*
   The target's state read into the `Situation`; step 3. In game: Springing Slice and Final Strike in the log after a stumble,
   no repeated refusals of them.
5. **Areas without bystanders.** *Built. `BotSkillManager.wakesABystander`, in `cast` for every class and every playbook move, alone and in a
   group: an enemy area, around the caster or the target, is not cast when it would take in an enemy that hates neither the bot nor a member (the
   target excepted). It replaces the group-only check the generic order made. The gladiator: chain C's second link chosen right after Absorbing Fury
   (Roiling Hack at 80 % or less, Pressure Wave above, either one when the other is cooling), and with two enemies or more within 7 m the areas in the
   guide's order, Absorbing Fury, Severe Precision Cut, Piercing Rupture, Earthquake Wave, Whirling Advance. 25 pure tests. The measure in game
   is still to take.* A generic rule in `cast`, for every class: an enemy area skill is not cast when an enemy within its range, around
   the caster or the target, is not already fighting the bot or its group. Then the gladiator's step 4, from two enemies on it. Measured as the
   pulls a gladiator causes, before and after.
6. **Openers, burst and divine power.** *Built. While one of the chains the generic order carries on is open (`FOLLOWED_CHAINS`: the filler,
   chain B's first link, the end of C, Cleave's, Energy Impact), the playbook says nothing past the posture, since anything cast would close it.
   In reach: Berserking; Empyrean Blessing at 4000 DP, the points kept for it while it is ready; else Daevic Fury at 2000; else Explosion of Rage.
   Then the areas with several enemies, then Severe Weakening Blow, Sure Strike, Aerial Lockdown, Crushing Blow. From afar: Charge beyond 20 m,
   Cleave, Ferocious Chop within 17 m. The filler, chain B and the chains' later links stay the generic order's. 34 pure tests; a data test holds
   that every followed chain has a link the generic order may cast.* Cleave from afar, Charge beyond 20 m, Severe Weakening Blow in reach,
   Berserking, the DP order.
7. **Solo temper.** *Built. `ClassPlaybook.whenOutnumbered`, a new hook read by `retreatIfLosing` with two attackers or more: the gladiator
   leaves when an elite or worse is among them, at any health, or under 25 % health ("in danger", against the generic 40 %), and holds otherwise.
   The level gap is not overruled. Alone, above 90 % health, with a single monster on it and that one in reach, it pulls a second with Cleave:
   a normal monster it would have picked itself (`BotTargetSelector.isFairGame`), within 15 m, with no idle monster within 6 m of it. Never a third:
   the guide's "2 à 3" was brought down to two.*
8. **The tank-dead mode.** *Built: in a full group (six) whose templars are all dead, Slaughter is switched off and Defense Preparation on;
   then Taunt on a target hitting somebody else, Mocking Blast when ready, Body Slice when chain B offers it. Once a templar stands again, Defense
   Preparation off and Slaughter back. Both postures are toggles, switched off by casting them again; whether the engine lets both be on at once
   was not checked, so the mode never relies on it.* In a group of six whose templar is dead: Defense Preparation instead of Slaughter, Taunt, Body Slice and Mocking Blast when
   ready; back to Slaughter once the templar is raised.
9. **Stigmas.** *Built: `preferredStigmas`, Draining Blow, Earthquake Wave and Crippling Cut, then Sure Strike, Severe Precision Cut and
   Draining Sword; a data test holds the six groups.* `preferredStigmas` from section IV.

## Answered by the guide's author

1. **The names.** The guide translated the French client's names itself, which is why they are found in no English list. The French client's
   `strings/client_strings_skill.xml` (in `langue/FRA/Data/Data.pak`, a plain zip; the string id is the template's `nameId`) settled all five:
   Onde d'absorption is Roiling Hack, Variation de la coupe du dragon Whirling Advance, "Raging Blow (coup de fouet)" Ferocious Chop, Lame
   d'incitation Mocking Blast, Colère Ferocity. They are in [skill-glossary.md](skill-glossary.md).
2. **Draining Sword is for under 40 % health**, the "above" was a slip.
3. **Two enemies on purpose, three not.** A lone gladiator above 90 % health takes on two normal monsters of its level, and does not run from
   them, unless it is in danger. Elites and worse are never pulled in numbers, and are still run from. The guide said two or three; it was
   settled at two at most.
4. **The tank-dead mode is for a group of six only**, from the templar's death to its resurrection.
5. **Chain E is open.** Energy Impact after Sure Strike or Severe Precision Cut as well as after Weakening Blow: the generic order already takes a
   continuation first, so it will; to be watched in game.
