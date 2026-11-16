# Gunner: implementation plan

Status: built, unverified: in game. Written and built on 16 November 2026, **without a guide**, as the bard's and the spirit master's were
([bard-plan.md](bard-plan.md), [spirit-master-plan.md](spirit-master-plan.md)): every rule from the data, or marked *(play)* when it is judgement.
`GunnerPlaybook`, 11 decision tests and 7 data tests; never yet watched in game.

## Where the rules come from

- **the data**: `skill_templates.xml` read on 16 November with `tools/skill-lookup.ps1 -Class GUNNER` (61 active groups): cooldowns, cast times,
  every rank's chain links (with their repeat counts), effects, and the weapon each skill asks for. These are facts.
- **(play)**: how the class is generally played in 4.8. Each such rule is the first thing a player of the class reviews and a watch in game
  checks.

The pattern is the spirit master's: a `GunnerPlaybook`, rules by skill **group**, a pure `decide`, its tests, a data test, the French client's names
in [glossary/gunner.md](../glossary/gunner.md).

## What the class is, from the data

A ranged **magical** damage dealer (`spellatk`, `PlayerClass.isPhysicalClass` false), in leather, `BotRole.DAMAGE`, `BotPosition` ranged. Two
weapons in the data: **pistols** (`GUN`, one hand, two held) and the **cannon** (`CANNON`, two hands). Each skill names the one it needs:

| Weapon asked | Groups | What they are |
|---|---|---|
| `GUN` | 27 | Every chain, the fillers, the gun buffs, most stigmas |
| `CANNON` | 19 | Every charged skill, the cannon roots and stuns, Seeker Round (4000 DP) |
| either | 15 | Defences, self buffs, Spend Success (2000 DP), Teleport Escape |

**The module arms a gunner with two pistols** (`BotGearFit.weaponsOfTrade`, from a player of the class: "two pistols, which is how the class is
played"). So the 19 cannon skills cannot be cast: this playbook is a **pistol** gunner's, and a weapon swap is a question for later (below).

## What exists, and what is missing

- **No gunner playbook.** The generic order casts by stated damage with chain links first. It does not know that the chain links repeat
  (`selfcount`, `precount`: Rapidfire twice, then Automatic Fire twice, then Steel Shot), when Reload is worth its cooldown, or that half the class
  needs a weapon it does not hold. Gunners died with the spirit master, the aethertech and the bard on Reshanta's upper layer.
- **Repeated chain links already work**, read from the code rather than measured. `continuesAChain` puts every link of an open chain first and
  sorts them by power, so the bot may ask for Automatic Fire (which needs Rapidfire *twice*) after one Rapidfire. The engine refuses it without
  touching the chain (`ChainCondition.validate` "checks the chain state without changing it"; only a cast that starts resets it),
  `BotSkillManager.cast` goes on to the next candidate in the same tick, the second Rapidfire, and a refusal is never counted towards
  `isPausedAfterFailedCasts`, which counts casts begun and cut short.
- **The cannon skills are offered to it**: nothing in the generic order reads the weapon a skill asks for, so a pistol gunner tries them, the engine
  refuses, and `isPausedAfterFailedCasts` pauses each one for a while. Harmless, but every refusal is a tick spent.
- **No stat weights** (`BotStatWeights`: "nobody who wrote the orders plays these two"): it takes the magical tail.
- **The pieces exist**: the sorcerer's `secondToControl`, the ranger's kinds of enemy (`RangerPlaybook.kindOf`) and its distance keeping, the
  bard's toggle rule for a stance, the DP rules, `flees`, the cast rules on fears and areas.

## The skills, mapped (pistols)

Cooldowns in seconds. "St" is a stigma (its tier). Chains: `1TH` opens, `2TH` follows within 2 s; "x2" a link that may be cast twice in a row.

Chains and fillers.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Gunshot → Rapidfire x2 → Automatic Fire x2 → Steel Shot | Tir au pistolet, Volée répétée, Tir rapide répété, Projectile lourd | `EN_DOUBLEFIRECHAIN`, `EN_ENFIRECHAIN`, `GU_RAPIDENFIRE`, `GU_HEAVYFIRECHAIN` | 16, 1, 1, 1 | Chain A; Automatic Fire needs two Rapidfires, Steel Shot (61) two Automatic Fires |
| Trunk Shot → Volley x2 → Muzzle Flash | Photo d'identité, Volée débilitante, Tir au point faible | `GU_FOREHEADSNIPE`, `GU_CURBINGFIRECHAIN`, `GU_WEAKSPOTSNIPE` | 24, 1, 24 | Chain B; Trunk Shot staggers, Muzzle Flash drains |
| Crosstrigger → Canted Shot x2 → Aerial Shot x2, or → Trigger Pull | Le coup de l'âme, Soul Volley, Rapid Soul Fire, Tir de sceau | `GU_MENTALICFIRECHAIN`, `GU_BREAKINGFIRECHAIN`, `GU_MENTALICRAPIDFIRE`, `GU_MENTALICSHOT` | 24, 1, 1, 24 | Chain D, which burns mana too; Trigger Pull the other branch |
| Steady Fire x5 | Volley rapide | `GU_ONEPOINTRAPIDFIRE` (st 1) | 30 | Chain C: itself five times |
| Pulverizer Cannon x2 | Projectile de magie sauvage | `GU_BLINDLYSHOT` (st 4) | 122 | An **area**, 20 m, a pistol skill despite its name |
| Anti-Enemy Fire x3 | Volley de restauration | `GU_DRAINATTACK` | 90 | Chain F, from 65 |
| Direct Shot | Tir de visée | `GU_SNIPINGFIRE` | 2 | The filler |
| Power Grab, Hemorrhage Shot | Tir de l'aspirateur d'âmes, Pénétration des lignes de vie | `GU_MENTALSNATCHER` (st 1), `GU_VEINSNIPE` (st 2) | 30, 30 | A drain; a bleed |
| Reload, Autoload | Insérer / Charger un projectile magique | `GU_FIRECHAINRELOAD`, `GU_FIRECHAINREFRESH` (st 2) | 30, 60 | **Reset cooldowns** (`skillcooltimereset`): which ones, read at milestone 0 |

Buffs, divine power, stance.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Aion's Favor, Pressurized Chamber | Promesse de pouvoir magique, Amélioration du projectile magique | `GU_MAGICALPROMISE`, `GU_EMPOWERAMMO` (st 2) | 10, 30 | Procs (`provoker`) |
| Sighting, Nature's Favor, Stopping Power | Trueshot Magic Eye, Souffle de puissance magique, Don de pouvoir magique | `GU_BULLSEYE` (st 1), `GU_MAGICALBREATHING` (st 1), `GU_EMPOWERMAGIC` (st 2) | 60, 183, 60 | The opening cooldowns |
| Pursuit Stance, Anticipation | Position de poursuite, L'œil du chasseur | `GU_PURSUIT` (st 4), `GU_MINDREADING` | 183, 180 | Long buffs |
| Spend Success | Force magique | `GU_MAGICALSTRENGTH` | 180 | **2000 DP** |
| Stable Stance | Posture de tir rapide | `GU_RAPIDSTANCE` | 10 | A **toggle** from 58 (`statboost`): what it costs is read at milestone 0 |
| Revealing Scope | Œil magique de clarté | `GU_MAGICALSEARCHEYE` | 120 | Sees the hidden: claimed, never cast until the PvP watch asks |

Defence and control.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Bullet Resistance, Bulletproof | Forme matérialisée du mur, Évasion contrôlée | `EN_FORMWALL`, `GU_CHECKINGTOAVOID` | 60, 30 | A shield; every blow dodged and every spell resisted for a while |
| Parting Shot | Coup de feu de la retraite | `GU_BACKSTEPPINGSNIPE` | 60 | A shot and a jump back: the distance kept |
| Remove Shock → Steal Health / Teleport Escape | Dissipation de choc, Projectile de saisie, S'évader | `ALL_SHOCKREFLECT`, `GU_REFRESHINGATTACK`, `GU_LIGHTNINGMOVEMENT` | 60, 60, 90 | Chain E: a drain, or a jump and a cleanse |
| Survival Instinct | Instinct de survie | `GU_SURVIVALINSTINCT` | 120 | Cleansed and moved: out of a root |
| Memory Manipulation | Manipulation de l'esprit | `GU_MEMORYMANIPULATION` | 120 | Hate (`hostileup`): its sign read at milestone 0 |
| Green Grenade, Spinning Fire, Hot Shot | Bombe encerclante, Tir ciblé à point faible, Tir de perturbation | `EN_ANKLESNIPE`, `GU_AIMINGSTRIKE`, `GU_CRACKSNIPE` | 24, 30, 24 | Root, snare, slow |
| Quieting Gale, Dazzling Fire | Projectile de silence, Entre les yeux | `GU_SILENCESHOT`, `GU_SUNNINGSHOT` (st 3) | 18, 40 | Silence; a stun at 5 m |
| Wing Clip | Tir d'aile ciblé | `GU_AIMWING` | 60 | A flying enemy's flight time |

Cannon only, forbidden while pistols are held: Fiery Blast, Frozen Blitz, Shock and Awe, Missile Guide, Blazing Bombardment, Harassing Fire,
Incendiary Shell, Freeze Cannon, Watery Grave, Siege Cannon, Seeker Round, Juggernaut Cannon, Paralysis Cannon, Sitting Qooqoo, Shellshock,
Sequential Fire, Snow Shell, Aetheric Bombardment, Aerial Fury. Left out: Herb Treatment and MP Recovery (the generic order's).

## Milestones, in verifiable order

All built on 16 November in `GunnerPlaybook` and `BotStatWeights`, as below; the thresholds are the playbook's constants. Where the build differs
from the first plan, the milestone says so.

0. **Name the skills**: `glossary/gunner.md`; every group above checked by `GunnerPlaybookDataTest`, with the weapon each move and stigma needs
   (none asks for a cannon alone, and every gunner skill that does is forbidden). What the data answered:
   - **Reload and Autoload reset cooldown 1802, which Gunshot, Trunk Shot and Crosstrigger share**: the three openers cool down together, one opener
     a cycle, and a reload gives the next one back.
   - **Stable Stance**: auto attacks 10 % faster (`ATTACK_SPEED` -10 %), nothing else: kept on.
   - **Memory Manipulation lowers hate** (`hostileup` -41035): the bard's Ode to Friendship, for a group.
   - **Aion's Favor** lasts five minutes: an upkeep, not a cooldown.
1. *(Dropped: the repeated links were to be measured first; the code answers it, above.)*
2. **The seam**: `GunnerPlaybook` registered, `swings` true (a pistol's shot is its auto attack *(play)*), the cannon groups claimed and never cast,
   `claims` for every move below; the chain links (2TH and after) and Direct Shot stay with the generic order.
3. **The chains** *(play)*: an opener while none is open: Trunk Shot's stagger on a caster, Crosstrigger's mana burn on a player, Gunshot
   otherwise; Reload, then Autoload, while the openers cool down and no chain is open. Steady Fire, Anti-Enemy Fire and Pulverizer Cannon stay with
   the generic order. **Nothing but a defence is cast while a followed chain is open**: the playbook runs before the generic order on every tick,
   and any other cast closes the chain (found building this one; the bard and the spirit master had the same hole, closed the same day).
4. **The stance**: Stable Stance kept on, before a fight and in one, cast only when it is not up, as the bard's.
5. **The buffs** *(play)*: Aion's Favor kept up; Pressurized Chamber, Sighting, Nature's Favor, Stopping Power, Pursuit Stance and
   Anticipation on an elite, a boss or a player, or alone among three; Spend Success at 2000 DP on the same.
6. **Defence**: Remove Shock, then Teleport Escape alone under 30 %, else Steal Health; Bulletproof when hit under 50 %; Bullet Resistance when
   hit; Survival Instinct when rooted or snared with a melee near; Memory Manipulation in a group when the target turns on the gunner (if it
   lowers hate). Alone under 25 %, `flees`.
7. **Distance** *(play)*: alone, a melee target within 5 m: Parting Shot, else Spinning Fire's snare. *The ranger has no step back to reuse*: the
   distance itself is `BotPosition`'s, for every ranged class.
8. **Control**: a second enemy held (`secondToControl`) with Green Grenade in a group and alone; Quieting Gale on a caster casting; Dazzling Fire
   on a player within 5 m; Wing Clip on a flying player.
9. **Stigmas** *(play)*, pistol ones only, the list long enough that no cannon stone is ever reached: regular Steady Fire, Sighting, Nature's
   Favor, Power Grab; advanced Hemorrhage Shot, Pressurized Chamber, Autoload, Dazzling Fire, Pursuit Stance, Stopping Power, Pulverizer Cannon.
10. **Stat weights** *(play)*: magical accuracy then magic boost, as the sorcerer's (`BotStatWeights`), since every attack is a spell.

Tests per milestone, pure as the others' are; then a watch in game: a gunner alone in Eltnen, one in a group, `//bot debug combat` on, and its
deaths on Reshanta's upper layer against the measure of the roadmap's theme 3.

## Questions for a player of the class

Pistols only, or a cannon swapped in for the charged skills (the module holds one weapon set); the openers' order; when Reload is worth it;
Stable Stance; kiting a melee alone; the stigma set at each level.
