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

A **magical** melee class (every attack is `spellatk`) in chain armour, held in the module as a damage dealer that stands in front like the templar
(`BotRole.DAMAGE`, `BotPosition` `FRONT`, `BotStatWeights.wantsHostility` true): the gladiator's shape, a second tank when the first is down. Its
weapon is the cipher-blade (`KEYBLADE`, `BotGearFit.weaponsOfTrade`), and **everything rests on its mech**: Embark (`RI_SUMMONARMOR`) is a toggle
that asks for the key and grants 4 m of reach, and every other `RI_` skill it casts carries `ride_robot` (62 groups; only Embark and two passives do not). The module already boards it
(`BotSkillManager.tryBoardRobot`, before anything in a fight) and leaves it to open a store.

## What exists, and what is missing

- **No aethertech playbook.** The generic order casts by stated damage with chain links first, and the chains are many and short (below), so it
  already fights. What it cannot do: **the toggles**, since the generic order casts none but mantras and the mech (`skills` refuses every other
  toggle, since casting a live one turns it off); the DP skills at the right time; the defences on health; the taunts in the one case they are
  wanted; the mana, which this class spends on its shields as well as its skills.
- **The engineer's gun skills are in its tree** (Gunshot, Rapidfire, Direct Shot, Green Grenade, Hot Shot, Bullet Resistance, learned 10 to 14):
  they ask for a gun, and an aethertech holds a key. Forbidden, as the gunner's cannon skills are.
- **No way to end a toggle by group.** `leaveRobot` ends the mech by its effect; Stability Thrusters, which drains mana, has to be turned off after a
  fight, so a `BotSkillManager.endToggle(bot, group)` is added on the same pattern.
- **No stat weights** (`BotStatWeights` returns an empty list for `RIDER`): it takes the generic tail.
- **The pieces exist**: the gladiator's tank-down rule (`Group.tankDead`), the generic taunt rule (`isTauntForbidden`, a grouped non-tank never
  taunts), the sorcerer's `secondToControl`, `isEliteOrBoss`, the toggle rule of the bard's stances, `flees`, the charged skills of the spirit
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
1. **The seam**: `AethertechPlaybook` registered; the gun skills and Mobility Thrusters claimed and never cast; the chain guard.
2. **The toggles** *(play)*: in a fight, Kinetic Battery on above 20 % mana and Stability Thrusters above 40 %, each cast only when it is not up;
   off again under those, and both off between fights (`tend`, with `endToggle`), so a resting aethertech regains its mana.
3. **The second tank** *(play)*: in a group whose tank is dead, or that has none, Mounting Frustration on and Magnetic Wave on two enemies off the
   healer, Provoking Whispers on one; otherwise Mounting Frustration off. Alone, off: the hate is its own anyway.
4. **The DP** *(play)*: Overdrive Trigger at 2000 on an elite, a boss or a player; Annihilation Barrage at 4000 alone among three, or on a boss;
   Power Flux at 2000 under 30 % mana when neither is wanted.
5. **The buffs**: Charging Activation kept up; Aimbot Assist, Life Support Trigger, Leeching Steel and Aethercharged Steel on what is worth them
   (the gunner's `worthABurst`).
6. **Mana**: Aether Recharge under 50 % with nothing on it; Recharge or Powerspike Trigger under 25 % in a fight.
7. **Defence**: Remove Shock, then Protective Shell under 50 %, else Uppercut; Nullification Trigger hit under 40 %; Kinetic Bulwark under 50 %
   with half its mana; Trauma Plate Trigger hit by a player; Repair Protocol rooted or snared. Alone under 25 %: Hypergate Detonation, then
   `flees`.
8. **Control**: a second enemy held (`secondToControl`) with Electric Shock or Convulsion Beam; Aetherlock Blade on a caster; Drillbore or
   Backlash on a player; Steam Rush on a target beyond 8 m.
9. **Stigmas** *(play)*: regular Riplash, Aimbot Assist, Drillbore, Steel Storm, Aether Recharge; advanced Rain of Knuckles, Aethercharged Steel,
   Trauma Plate Trigger, Kinetic Bulwark, Convulsion Beam, Meteor Strike. Never Mobility Thrusters.
10. **Stat weights** *(play)*: magical accuracy then magic boost, as the gunner's, every attack being a spell.
11. **Fuel Reserves**: flying, under a third of its flight time.

Tests per milestone, pure as the others' are; then a watch in game: an aethertech alone in Eltnen and one in a group whose templar dies,
`//bot debug combat` on, its mana through a fight, and its deaths on Reshanta's upper layer against the measure of the roadmap's theme 3.

## Questions for a player of the class

Whether an aethertech should tank a group with no templar outright (a `BotRole` change, not this playbook's); the toggles' thresholds; which
stigmas; whether Boost belongs in a fight; whether Mobility Thrusters is worth turning on to travel, between fights.
