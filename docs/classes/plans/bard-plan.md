# Bard: implementation plan

Status: built, unverified: in game. Written and built on 15 November 2026, **without a guide**: the first playbook made that way, by decision, until a
guide exists. `BardPlaybook`, 24 decision tests and 8 data tests; never yet watched in game.

## Where the rules come from

The six playbooks before this one each came from a guide's document, and the guide settled every question. There is none for the bard. Every rule
below comes from two sources, and says which:

- **the data**: `skill_templates.xml` and the skill tree, read on 15 November with `tools/skill-lookup.ps1 -Class BARD`: cooldowns, cast times,
  chains, effects, targets, DP costs. These are facts.
- **(play)**: how the class is generally played in 4.8, which is judgement, not a guide. Each such rule is marked *(play)*: it is the first thing a
  player of the class reviews, and the first thing a watch in game checks.

The pattern is the sorcerer's and the chanter's ([sorcerer-plan.md](../history/sorcerer-plan.md), [chanter-plan.md](../history/chanter-plan.md)): a
`BardPlaybook`, rules by skill **group**, a pure `decide`, its tests, a data test. French names are the French client's (`client_strings_skill.xml`),
in [glossary/bard.md](../glossary/bard.md).

## What the class is, from the data

A ranged magical caster with a harp (every skill asks `weapon="HARP"`), 25 m reach, cloth, `BotRole.SUPPORT` like the chanter. It does three things:
damage by chains, heals by chains, and the group's mana and resistances. Two stances, toggled, 12 s cooldown: **Exultation** (`BA_PLAYINGSTYLESCHANGEA`,
"Mode de guérison": heal boost and magical accuracy) and **Impassion** (`BA_PLAYINGSTYLESCHANGEB`, "Mode Magic Boost": magic boost and accuracy).

## The skills, mapped

Cooldowns in seconds, cast times in brackets (instant otherwise). The chain column is the engine's: `1TH` opens, `2TH` follows within 3 s, `3TH` ends.

Damage.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Song of Ice | Harmonie glaciale | `AR_SONGOFCOLD` | 12 (1 s) | Chain A opener |
| Song of Fire | Harmonie de flammes | `AR_SONGOFFIRE` | 10 | Chain A, second |
| Thronesong, Song of Earth | Harmonie du vent, Harmonie terrestre | `BA_SONGOFWIND`, `BA_SONGOFLAND` | 30, 10 | Chain A, third (two branches) |
| Bright Strike → Bright Stroke → Bright Flourish | Harmonie des âmes, de la mort, de la destruction | `BA_SONGOFSHOCK`, `BA_SONGOFMINDDAMAGE`, `BA_SONGOFMENTALIC` | 16, 14, 14 | Chain C; the last two burn the target's mana too |
| Syncopated Echo → Fluttered Note | Résonance de l'attaque, Son aigu des grilles | `BA_SONGOFDELAYDAMAGE`, `BA_SONGOFINJURY` | 16, 14 | Chain D: delayed damage |
| Fiery Descant → Sonic Splash | Variation de la bataille, Tsunami Requiem | `AR_SONGOFBATTLE`, `BA_SONGOFTIDALWAVE` | 30, 30 | Chain E; Descant is charged (5 s) |
| Fiery Requiem | Variation de l'illusion | `BA_REQUIEM` | 120 (charged 5 s) | Also opens chain E, from 62 |
| Pulse | Le son de la brise | `AR_SONGOFAIR` | 2 (1 s) | The filler |
| Sonic Gust | Gust Requiem | `BA_SONGOFGUST` | 16 (1 s) | |
| Stinging Note, Adagio | La marche des abeilles, Loud Bang | `BA_SONGOFEARTH`, `BA_SONGOFWEIGHT` | 30, 30 | A stat down; a snare |
| Dragon Song, Harpist's Pod | Variation de l'âme ascendante, Variation de la mer | `BA_SONGOFMENTALICBLASTER`, `BA_SONGOFGRAVITY` | 30, 30 (charged 5 s) | Big hits; Pod slows |
| Quaver | L'harmonie du silence | `BA_SONGOFSOULEXTINCTION` | 30 | Damage and silence: on a caster |
| Chorus of Fortitude | Explosion de choc | `BA_SONGOFRESISTANCE` | 60 | Chain G after Remove Shock |
| Combustible Cacophony | Harmonie de la désolation | `BA_MAGICALDESTRUCTION` | 60 | Stigma, **area**: never among other packs (the cast rule already) |
| Paean of Pain, Treble Cleave, Blazing Requiem | Mosky Requiem, Désharmonie, Variation fantastique | `BA_SONGOFPAIN`, `BA_SONGOFGALE`, `BA_ILLUSIONREQUIEM` | 60 each | Stigmas; Requiem charged 5 s |
| Minstrel's Flair | Symphonie de la colère | `BA_SONGOFANGER` | 60 | **2000 DP** |

Heals and mana.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Soothing Melody → Counterpoint → Finale / Joyous Carol | Écho doux, Mild Echo, Écho doux, Mélodie de la joie | `AR_SONGOFHEAL`, `BA_SONGOFEMERGENTHEAL`, `BA_SONGOFGREATHEAL`, `BA_SONGOFWARMTH` | 6 (1 s), 4, 4, 24 | Chain B on one ally |
| Soothing Hymn | Variation de la guérison | `BA_SONGOFMASSHEAL` | 3 (charged 5 s) | Stigma, the big single heal |
| Hymn of Rejuvenation | Mélodie de rajeunissement | `BA_SONGOFREGENERATION` | 60 | Heal and heal over time |
| Chorus of Blessing | Mélodie d'éclaircie | `BA_SONGOFBLESS` | 60 | Stigma: the **group** healed and cleansed |
| Hymn of Thanksgiving | Mélodie d'appréciation | `BA_SONGOFAPPRECIATION` | 60 | Chain G after Remove Shock: a self heal |
| Resonating Melody → Resonant Hymn | Mélodie de la réflexion, Variante de la paix | `AR_SONGOFRELAX`, `BA_SONGOFSERENITY` | 30, 60 | Chain F: own mana, then the **group's** mana within 25 m |
| Resonant Counterpoint | L'écho de la clarté | `BA_SONGOFREFRESHMIND` | 6 | Mana to one ally |
| Cherubim Call | Rhapsodie céleste | `BA_SONGOFRESURRECTION` | 30 | Resurrection |
| Purifying Paean, Purging Paean | Mélodie de purification, Sérénade de purification | `BA_SONGOFPURIFY`, `BA_MASSDISPEL` | 8, 30 | **Physical** debuffs only (`DISPELDEBUFFPHYSICAL`); the second, the group |
| Aria of Hope | Mélodie de l'espoir | `BA_SONGOFHOPE` | 1800 | **4000 DP**: group healed 30 % and raised if it dies within 60 s |

Upkeep, defence, control.

| Client name | French | `group` | Cooldown | Notes |
|---|---|---|---|---|
| Etude | Mélodie de la vie | `BA_SONGOFSTAMINA` | 0.5 | +15 % max health, **1 h**, one ally: every member |
| Mvt. 2: Summer, Mvt. 3: Autumn | Mélodie du courage, de la discipline | `BA_SONGOFBRAVE`, `BA_SONGOFFIRM` | 60 | Self, 30 min: fear, sleep, paralysis / silence, stagger resistances |
| Protective Ode | Mélodie du bouclier | `AR_SONGOFSHIELD` | 120 | Self shield, 5 min |
| Mvt. 1: Spring | Mélodie du char d'assaut | `BA_FOCUSDEFENCE` | 180 | Self, 12 s of stun, stumble, spin resistance |
| Mvt. 4: Winter | Mélodie de la fleur de neige | `BA_SANCTUARY` | 180 | Self shield, heal and mana, 30 s: the emergency |
| Remove Shock | Dissipation de choc | `ALL_SHOCKREFLECT` | 60 | Out of a stun; opens chain G |
| Ode to Friendship | Sérénade paisible | `BA_SONGOGPEACE` | 60 | Lowers its hate on the target (`hostileup` negative) |
| Sonicportation | Danse dérangeante | `BA_SONGOFDIMENSIONDOOR` | 60 | Random jump and a dispel: the flight |
| Freestyle, Requiem | La danse du bouffon, La marche du bouffon | `BA_LULLABY`, `AR_SONGOFREST` | 30, 30 | Sleep 15 s on one; sleep 6 s on 6 within **7 m around the bard** |
| Captivate, Composer's Bar, Staggered Rest | Barrière, Une explosion..., Paralysie Résonance | `AR_SONGOFROOT`, `BA_SONGOFBIND`, `BA_DELAYPARALYZE` | 16, 30, 122 | Root 8 s; bind 5 s; paralysis after 8 s |
| Delusional Dirge, Half-Stop | Tonalité de l'illusion, Requiem de l'oubli | `BA_SONGOFILLUSION`, `BA_SONGOFMANAREVERSE` | 30, 30 | Enemy attack down; strips a buff from an enemy |
| Symphony of Destruction | Symphonie de la destruction | `BA_SONGOFDESTROY` | 1800 | **2000 DP**: the group's attack and casting speed, 5 min |
| Inspiration, Rejuvenating Aria | Mélodie d'attaque, Mélodie de la joie | `BA_SONGOFADVANCE`, `BA_EXCITINGMELODY` | 30, 90 | Group speed 15 s; own attack speed 15 s |

Left out: Soaring Sonnet `BA_SONGOFEAGLE` and the flight passives (theme 5 has nothing left), Herb Treatment and MP Recovery (the generic order's).
Banned, claimed and never cast: Rejuvenating Aria `BA_EXCITINGMELODY` (attack speed, for a bard that never swings) and Inspiration `BA_SONGOFADVANCE`
(fifteen seconds of run speed, which the generic buffs would cast every half minute).

What the data said that the first reading missed:

- **The stances, Summer, Autumn, Resonant Hymn, Freestyle and Hymn of Rejuvenation are stigmas.** A bard has them only if they are socketed, so the
  stigma list decides the build: without the stance stigma there is no stance before 45. A bard that wears one stance keeps that one
  (`BotSkillManager.knows`).
- **A charged song is two kinds of template**: the learnt one is `CHARGE`, its stages (one second, five) are plain casts of the same group the engine
  releases into. The data test asks the group, not its last template, which is a stage.
- **The two stances share a cooldown id** and the engine allows a bard one toggle of that kind: casting the other stance ends the first.

## What exists, and what the bard needs of the module

- **No bard playbook.** The generic order casts by stated damage with chain links first: the chains go off, but it does not pick a stance, keeps
  nobody alive on purpose, and spends heals as an afterthought. Bards died with the spirit master, aethertech and gunner on Reshanta's upper layer.
- **The stances are toggles**, kept from the generic order like every toggle but a mantra; the playbook casts one only when it is not up, since a
  toggle cast again turns itself off.
- **Physical cleanses**: the module read every cleanse as a general one. `CleanseReach` now says what a cleanse reaches, as
  `EffectController.removeEffectByDispelCat` does, and a cleanse notes as resisted only the effects of its own kind: a bard's physical one had
  otherwise kept a cleric's general one off a sleep.
- **Resurrection**: `BotGroupManager.mateToResurrect` knew the chanter and the cleric only, so a bard and a cleric would both have raised one body.
  The order is now chanter, bard, cleric (`resurrectorRank`): the cleric, the group's healer, keeps
  its mana for the living.
- **The pieces exist**: the chanter's heals of mates and its resurrection (`BotGroupManager.mateToResurrect`), the cleric's group reading, the
  sorcerer's controls on a second enemy and its silence on a caster, the charge release (`releaseWhenCharged`), the DP rules, hold fire, `flees`.

## Milestones, in verifiable order

All built on 15 November in `BardPlaybook`, as below; the thresholds are its constants.

0. **Name the skills**: the glossary file, every group above checked by the data test.
1. **The seam**: `BardPlaybook` registered, `swings` false (a bard never auto attacks *(play)*), `claims` for every group above but the damage
   chains, Pulse, Sonic Gust and Quaver, which stay with the generic order.
2. **The stance** *(play)*: Impassion alone and in a group with a living cleric; Exultation in a group without one near. Not on a
   member's health, which would switch it every twelve seconds of a hard fight (found in review).
   Switched no more than once per 12 s, never mid-chain.
3. **Upkeep**: before a fight Summer and Autumn (30 min), Protective Ode, Etude on every member without it; nothing while idle in town (the
   sorcerer's rule). In a fight, Protective Ode renewed when gone.
4. **Healing, in a group** *(play)*: the healer's second in a group with a cleric, the healer without one. Chain B on the most hurt member under 70 %;
   Soothing Hymn under 40 %; Chorus of Blessing with three members under 70 %; Hymn of Rejuvenation on the tank; Cherubim Call after a fight, the
   chanter's rule. Alone: Chain B under 60 %, then back to damage.
5. **Mana**: chain F when its own mana or two members' are under 50 %, its second link (the group's mana) once open; Resonant Counterpoint on a
   healer under 30 %.
6. **Damage**: chains A, C, D, E kept running by the generic order, which follows a chain while it is open, and **no song of the offence begun
   while one is open** (`DAMAGE_CHAINS`, added 16 November: the playbook runs first and its next song had closed the chain); the charged songs (Fiery Requiem,
   Blazing Requiem, Dragon Song, Harpist's Pod, Fiery Descant) only while nothing hits the bard, and alone as the opener (a charge is cancelled by a
   hit *(play)*); Minstrel's Flair at 2000 DP, keeping Aria of Hope's 4000 in a group. Quaver on a caster, Half-Stop on a player, Delusional Dirge
   on a target that turned on the bard.
7. **Defence**: Remove Shock then Hymn of Thanksgiving; Spring when a stun-heavy enemy is on it *(play)*; Winter under 30 %; Ode to Friendship when
   the target is on the bard in a group; Sonicportation then the generic retreat alone, under 25 %.
8. **Control**: Freestyle, then Captivate, Staggered Rest, Composer's Bar on a second enemy engaged and not held, in a group or alone with several
   (the sorcerer's rule and its `secondToControl`); Requiem alone, under 50 %, with two enemies within 7 m.
9. **The big moments** *(play)*: Symphony of Destruction at 2000 DP on a boss or an elite with the group; Aria of Hope at 4000 DP when two members
   are under 30 %, or before a wipe.
10. **Stigmas** *(play)*: regular Hymn of Rejuvenation, Resonant Hymn, Freestyle; advanced Soothing Hymn, Chorus of Blessing, Exultation. The
    linked ones are granted by the engine.

Tests per milestone, pure as the others' are; then a watch in game: a bard alone in Eltnen, a bard in a group with a cleric and one without,
`//bot debug combat` on, and its deaths on Reshanta's upper layer against the 36 of 40 of the measure of the roadmap's theme 3.

## Questions for a player of the class

The stance rule; whether the bard heals first or deals damage first in a group with a cleric; the charged skills in a fight; Spring's trigger; the
stigma set at each level; which chain branch (Thronesong or Song of Earth) a good bard ends chain A with.
