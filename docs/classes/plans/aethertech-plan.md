# Aethertech: implementation plan

Status: not started. Written on 16 November 2026, **without a guide**, as the bard's, the spirit master's and the gunner's were
([gunner-plan.md](gunner-plan.md)): every rule from the data, or marked *(play)* when it is judgement.

## Where the rules come from

- **the data**: `skill_templates.xml` and `skill_tree.xml` read on 16 November for every group the class learns (73, `PlayerClass.RIDER`):
  cooldowns, chains, effects, conditions, costs. These are facts.
- **(play)**: how the class is generally played in 4.8. Each such rule is the first thing a player of the class reviews and a watch in game
  checks.

The pattern is the gunner's: an `AethertechPlaybook`, rules by skill **group**, pure `decide` functions, their tests, a data test, the French
client's names in `glossary/aethertech.md`, and the chain guard of [skill-traps.md](../../traps/skill-traps.md) (nothing but a defence while a
chain the generic order carries on is open).

## What the class is, from the data

A **magical** melee class (every attack is `spellatk`) in chain armour, and **a main tank, as the templar is** (decided on 16 November). The module
still files it as damage (`BotRole.DAMAGE`), though it already stands in front like the templar (`BotPosition` `FRONT`) and wants hostility on its
gear (`BotStatWeights.wantsHostility`); making it a tank is milestone 1. Its weapon is the cipher-blade (`KEYBLADE`, `BotGearFit.weaponsOfTrade`), and **everything rests on its mech**: Embark (`RI_SUMMONARMOR`) is a toggle
that asks for the key and grants 4 m of reach, and every other `RI_` skill it casts carries `ride_robot` (62 groups; only Embark and two passives do not). The module already boards it
(`BotSkillManager.tryBoardRobot`, before anything in a fight) and leaves it to open a store.

## What exists, and what is missing

- **No aethertech playbook.** The generic order casts by stated damage with chain links first, and the chains are many and short (below), so it
  already fights. What it cannot do: **the toggles**, since the generic order casts none but mantras and the mech (`skills` refuses every other
  toggle, since casting a live one turns it off); the DP skills at the right time; the defences on health; holding the enemy, which the generic order
  does not try to do; the mana, which this class spends on its shields as well as its skills.
- **The engineer's gun skills are in its tree** (Gunshot, Rapidfire, Direct Shot, Green Grenade, Hot Shot, Bullet Resistance, learned 10 to 14):
  they ask for a gun, and an aethertech holds a key. Forbidden, as the gunner's cannon skills are.
- **No way to end a toggle by group.** `leaveRobot` ends the mech by its effect; Stability Thrusters, which drains mana, has to be turned off after a
  fight, so a `BotSkillManager.endToggle(bot, group)` is added on the same pattern.
- **No stat weights** (`BotStatWeights` returns an empty list for `RIDER`): it takes the generic tail.
- **It is not a tank to the module.** `BotRole.of` gives `TANK` to the templar alone, and everything that serves the tank asks that: recruiting
  (`RecruitPlan.TANKS`), marking the group's target (`PlayerBotAI`), the taunts a grouped non-tank may not cast (`isTauntForbidden`), whom the
  cleric, the chanter, the bard and the spirit master heal, shield or choose a spirit for, where the others stand (`BotStations`), damage reduction
  in the stat weights, hate stones first among stigmas (`BotStigmaFitter`).
- **One group, one tank.** Every one of those asks for *the* tank, and two tanks on the taunts pull the same enemy back and forth. A group holding
  a templar and an aethertech needs one of them named.
- **The pieces exist**: the templar's playbook (what a tank does with its taunts and its hate), the gladiator's tank-down rule (`Group.tankDead`)
  for the tank that is not the group's, the sorcerer's `secondToControl`, `isEliteOrBoss`, the toggle rule of the bard's stances, `flees`, the charged skills of the spirit
  master's Stone Scour, the area rules.

## The skills, mapped

Cooldowns in seconds. "St" is a stigma tier (1 regular, 2 and 3 advanced, 4 linked). All in the mech unless said.

Chains, all the generic order's (each opener has its own cooldown, so nothing is gained choosing between them).

| Chain | Client names | French | Groups |
|---|---|---|---|
| I | Bludgeon → Battery → Beatdown, or → Hindering Blade (slow) | Matraque, Attaque légère, Attaque en force, Affaiblissement de l'aiguillon | `RI_RIGHTJAB`, `RI_LEFTJAB`, `RI_POWERJAB`, `RI_SHOCKATTACK` |
| C | Rocket Punch → Heat Burst, or → Flame Emission (20 m) | Frappe à deux mains, La flamme de la démolition, Jet de flamme | `RI_EXPLODINGPUNCH`, `RI_ANGRYPUNCH`, `RI_AIMINGATTACK` |
| D | Sundering Blade → Rain of Knuckles x3 (st 2) | Effondrement Smash, Tempête | `RI_WEAKENINGATTACK`, `RI_RAPIDATTACK` |
| E | Siphoning Slash → Siphoning Stab, or → Conversion Pulse (area drain) | L'élan de la gloire, Absorption de mana, Vague vampirique | `RI_DRAINJAB`, `RI_CHAINDRAIN`, `RI_DRAININGNET` |
| F | Cinder Cannon (20 m) → Gatling Gun x2 | Coup de canon brûlant, Coup de canon répété | `RI_DIRECTFIRE`, `RI_CHAINFIRE` |
| B | Chilling Wave (area snare) → Riplash (st 1, area) | Vague de refroidissement, Vague de destruction | `RI_FORWARDATTACK`, `RI_FORWARDSTRIKE` |
| H | Electric Shock (paralysis) → Lightning Tether (root) | Choc électrique, Caisse claire électrique | `RI_TANGLINGATTACK`, `RI_BINDINGATTACK` |
| G | Remove Shock → Uppercut, or → Protective Shell | Dissipation de choc, Uppercut, Voile de protection | `ALL_SHOCKREFLECT`, `RI_UPPERCUT`, `RI_PROTECTIONCURTAIN` |

Toggles, buffs, divine power, mana.

| Client name | French | `group` | Cooldown | From the data |
|---|---|---|---|---|
| Stability Thrusters | Augmentation de la puissance de combat | `RI_POWEROVERDRIVE` | 30 | Toggle: magic boost +200, resistances, a cleanse; **4 % of mana every 4 s** |
| Kinetic Battery | Coquille mystique | `RI_EATHIUMSHIELD` | 30 | Toggle: 20 % of the damage taken from mana, parry, resistances; 64 mana every 6 s |
| Kinetic Bulwark | Bouclier Id | `RI_EATHIUMWALL` (st 3) | 306 | Toggle: 60 % from mana, magic resist; 46 mana every 6 s |
| Mounting Frustration | Dialogue d'alerte | `RI_WARNINGALARM` | 10 | Toggle: **hate +100 %**, nothing else: a tank's |
| Mobility Thrusters | Renforcement de la mobilité | `RI_SPEEDOVERDRIVE` (st 2) | 40 | Toggle: speed +25 %, **physical defence -810**: forbidden |
| Aimbot Assist, Life Support Trigger | Amélioration de la perception, Focalisation sur la magie | `RI_TUNESENSOR`, `RI_EATHIUMCOMPRESS` (st 1) | 184, 122 | One minute: magical accuracy +150; mana +750 and regeneration |
| Aethercharged Steel | Augmentation de la puissance | `RI_OVERCHARGE` (st 2) | 91 | Ten seconds: attack speed, magic boost +100 |
| Charging Activation, Leeching Steel | Activer la régénération, Bouclier réflecteur absorbant | `RI_ACTIVATEGENERATOR`, `RI_ITHEUMREFLECTOR` (st 1) | 10, 184 | Procs (`provoker`), three minutes and ten seconds |
| Overdrive Trigger | Percée | `RI_HIGHENDOVERDRIVE` | 90 | **2000 DP**: a minute of magic boost and speed |
| Annihilation Barrage | Canon dragon divin | `RI_EXPLODEDP` | 600 | **4000 DP**, an area of 15 m round it |
| Power Flux, Recharge, Aether Recharge | Régénération complète, Recharge rapide, Régénération de la magie | `RI_DPCHARGE`, `RI_RAPIDCHARGE`, `RI_EATHIUMREACTIVE` (st 1) | 10, 180, 61 | Mana: half for 2000 DP; 4000 mana and 3000 health; 700, charged |
| Powerspike Trigger | Recharge rapide | `RI_EATHIUMCHARGE` (st 4) | 184 | Mana, the linked stigma |
| Fuel Reserves | Recharge Temps de vol | `RI_REPLENISHBLAST` | 300 | Flight time |

Defence, control, taunts.

| Client name | French | `group` | Cooldown | From the data |
|---|---|---|---|---|
| Nullification Trigger | Bouclier défensif instantané | `RI_EATHIUMCURTAIN` | 30 | Every blow parried, every spell resisted, 8 s |
| Trauma Plate Trigger | Voile magique | `RI_MAGNETICFIELD` (st 2) | 184 | Parry, PvP defence, control resistances, 20 s |
| Repair Protocol | Entretien d'urgence | `RI_INSTANTTHRUST` | 90 | A cleanse: of what, read at milestone 0 |
| Hypergate Detonation | Sortie de secours | `RI_ESCAPEROBOT` | 600 | A cleanse, a jump, a trap left behind: whether it ends the mech, read at milestone 0 |
| Electric Shock, Drillbore, Backlash | Choc électrique, Clivage de l'armure, Riposte au coup de canon | `RI_TANGLINGATTACK`, `RI_SHIELDDESTROYER` (st 1), `RI_BREAKINGATTACK` | 16, 61, 24 | Paralysis; a stun and a dispel; a stun at 20 m |
| Aetherlock Blade | Silence Smash | `RI_SHOCKSTRIKE` | 18 | Silence |
| Convulsion Beam | Reliure électrique | `RI_BINDINGSLAM` (st 2) | 61 | A root at 20 m |
| Steam Rush | Sprint Strike | `RI_CHARGEATTACK` | 12 | A dash of 15 m |
| Provoking Whispers, Magnetic Wave | Vent d'incitation, Vague de rage | `RI_PROVOKINGATTACK`, `RI_PROVOKEWAVE` | 12, 30 | Taunts; the wave turns what is round it onto it |

Left to the generic order: the chains above, the charged Kinetic Slam, Ravager Cannon, Particle Whip, Steel Storm and Explosive Exhaust (under the
area and charge rules), Meteor Strike, Debilitating Blade, Nerve Pulse, Boost and Double Boost (movement, never cast: `randommoveloc`, read at
milestone 0), Herb Treatment and MP Recovery. Forbidden: the seven engineer gun skills and Mobility Thrusters.

## Milestones, in verifiable order

0. **Name the skills**: `glossary/aethertech.md`; every group above checked by `AethertechPlaybookDataTest`, with `ride_robot` on each move that
   needs the mech and none of the forbidden ones castable with a key. Read: what Repair Protocol and Stability Thrusters cleanse, whether
   Hypergate Detonation and resting end the mech or the toggles, whether two mana shields stack, whether Boost is a dash forward or a random jump.
1. **A tank**: `BotRole.of(RIDER)` is `TANK`; `RecruitPlan.TANKS` asks for the templar, then the aethertech *(play: the templar has been watched in
   game, the aethertech not yet)*. **The group's tank** is one member: a templar if there is a living one, else an aethertech, the first of each in
   the group's order; a `BotRole.isGroupTank(bot)` that the rules above ask instead of the role alone, so the other tank of a group with two
   behaves as the gladiator does (damage, taunting only when the group's tank is dead). Tests for the group of one, two and none.
2. **The seam**: `AethertechPlaybook` registered; the gun skills and Mobility Thrusters claimed and never cast; the chain guard.
3. **The toggles** *(play)*: in a fight, Kinetic Battery on above 20 % mana and Stability Thrusters above 40 %, each cast only when it is not up;
   off again under those, and both off between fights (`tend`, with `endToggle`), so a resting aethertech regains its mana.
4. **Holding the enemy** *(play)*, as the group's tank: Mounting Frustration on in a fight; Magnetic Wave when two enemies are on somebody else,
   Provoking Whispers when one is, as the templar's taunts. Not the group's tank: the gladiator's rule, Mounting Frustration off until the tank
   is dead. Alone, off: the hate is its own anyway, and the toggle is a cast spent.
5. **The DP** *(play)*: Overdrive Trigger at 2000 on an elite, a boss or a player; Annihilation Barrage at 4000 alone among three, or on a boss;
   Power Flux at 2000 under 30 % mana when neither is wanted.
6. **The buffs**: Charging Activation kept up; Aimbot Assist, Life Support Trigger, Leeching Steel and Aethercharged Steel on what is worth them
   (the gunner's `worthABurst`).
7. **Mana**: Aether Recharge under 50 % with nothing on it; Recharge or Powerspike Trigger under 25 % in a fight.
8. **Defence**, a tank's, which takes the blows on purpose: Remove Shock, then Protective Shell under 50 %, else Uppercut; Nullification
   Trigger hit under 40 %; Kinetic Bulwark under 60 % with half its mana, an elite or a boss on it; Trauma Plate Trigger hit by a player; Repair Protocol rooted or snared. Alone under 25 %: Hypergate Detonation, then
   `flees`.
9. **Control**: a second enemy held (`secondToControl`) with Electric Shock or Convulsion Beam; Aetherlock Blade on a caster; Drillbore or
   Backlash on a player; Steam Rush on a target beyond 8 m.
10. **Stigmas** *(play)*: regular Riplash, Aimbot Assist, Drillbore, Steel Storm, Aether Recharge; advanced Rain of Knuckles, Aethercharged Steel,
   Trauma Plate Trigger, Kinetic Bulwark, Convulsion Beam, Meteor Strike. Never Mobility Thrusters.
11. **Stat weights** *(play)*: a tank's, as the templar's (`BotRole.TANK` already weighs damage reduction), with magical accuracy, every attack
    being a spell and a taunt that misses holding nothing.
12. **Fuel Reserves**: flying, under a third of its flight time.

Tests per milestone, pure as the others' are; then a watch in game: an aethertech alone in Eltnen, one tanking a group with no templar and one beside a templar,
`//bot debug combat` on, its mana through a fight, and its deaths on Reshanta's upper layer against the measure of the roadmap's theme 3.

## Questions for a player of the class

Which of a templar and an aethertech tanks a group that has both; the toggles' thresholds; which
stigmas; whether Boost belongs in a fight; whether Mobility Thrusters is worth turning on to travel, between fights.
