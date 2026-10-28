# Ranger: implementation plan

Status: done; validated in game on 27 October.

Milestones 1 to 5, 7 and 8 built and unit tested, 6 closed by decision; validated in game on 27 October with a player in the group. The guide's italics and yellow highlights count as validated; its red paragraph on traps does not (see "Traps"). Source: the guide's document "Rôdeur 101" (eight pages, in French). The ranger is a ranged physical DPS that uses its bow
only, stays at a good distance and does as much damage as fast as it can; alone it kills before the enemy arrives, with personal buffs before the fight and
handicaps during it. The pattern is the chanter's ([chanter-plan.md](chanter-plan.md)) and the templar's: a `ClassPlaybook`, rules by skill **group**, a pure
`decide` over a `Situation`, a data test. Names are the English client names from `skill_templates.xml` and `skill_tree.xml`; the levels below 10 are in
the **scout** tree (`classId="SCOUT"`, the base class), the rest in the ranger's.

## What the guide asks for, mapped to the data

| The guide | Client name | `group` | From | Cooldown | Notes from the data |
|---|---|---|---|---|---|
| Évasion ciblée | Focused Evasion | `SC_WHISPEROFWIND` | 1 | 30 s | 5 s of always dodge and always resist: the combo with Seizure Arrow |
| Cacher | Stealth | `SC_HIDE` | 5 | 60 s | Hide for 50 s. For travel only |
| Dévotion | Devotion | `SC_INVOKEPOWER` | 9 | 30 s | 5 s stat buff, in combat before the damage, after the 1 minute buffs |
| Transformation (ED) | Transformation: White Tiger / Krall / Mau (twice) | `RA_WHITETIGER`, `RA_KRALLSCOUT`, `RA_SABAGEROAR`, `RA_L_SABAGEROAR` | 10 | 10 s | **2000 DP**, 120 s. Four variants: **which one is not said** |
| Esquive | Dodging | `RA_HAWKEYE` | 10 | 1 s | 60 s, +200 evasion. One of the "1 minute" buffs |
| Viser | Aiming | `RA_EAGLEEYE` | 19 | 1 s | 60 s, +200 accuracy. 1 minute buff |
| Renforcement de l'œil | Strong Shots | `RA_SNAKEEYE` | 25 | 1 s | 60 s, +5 % attack. 1 minute buff |
| Fureur bestiale | Bestial Fury | `RA_STIGMA_TIGEREYE` | 30 | 1 s | 60 s, +20 % attack, **-10 m attack range**. That is the "changes the distances" |
| Souffle de la nature | Breath of Nature | `RA_BREATHOFNATURE` | 34 | 10 s | A **toggle**: once after login or resurrection, never switched off |
| Vitesse du vent | Speed of the Wind | `RA_PANTHERMOVE` | 44 | 180 s | 60 s. Right after the 1 minute buffs |
| Les doigts agiles | Nimble Fingers | `RA_QUICKSETTING` | 55 | 120 s | Trap casting time +100 %: a trap skill's buff, so unused while no trap is cast |
| Tir d'enchevêtrement | Entangling Shot | `RA_ENTANGLESHOT` | 10 | 16 s | Snare 16 s: the opener against a melee enemy |
| Flèche de saisie | Seizure Arrow | `RA_APPROACHSHOT` | 15 | 45 s | Damage and a drain on the target |
| Chuchotement apaisant | Calming Whisper | `AS_BREEZEOFCALM` | 19 | 120 s | Aimed at an enemy, `hostileup`, range 25: drops the ranger's hate |
| Flèche de sommeil | Sleep Arrow | `RA_PARALYZEARROW` | 19 | 180 s | Sleep 12 s, range 15 |
| Taillage en retrait | Retreating Slash | `RA_BACKDASHSTAB` | 22 | 60 s | Back dash and stun (a `BACKDASH`, which the generic order already never picks) |
| Tir silencieux | Silence Arrow | `RA_SILENTARROW` | 25 | 18 s | Silence 8 s: the opener against a caster |
| Retraite tactique | Tactical Retreat | `SC_SPIRITOFGALE` | 28 | 120 s | 30 s `provoker` effect: the flight |
| Dissipation de choc | Remove Shock I | `ALL_SHOCKREFLECT` | 40 | 60 s | As the templar's and the chanter's |
| Flèche de Manille | Shackle Arrow | `RA_ROOTARROW` | 42 | 30 s | Root 10 s: solo, when an enemy is at 1 m |
| Guérir les ailes | Heal Wings | `RA_RESTOREWING` | 46 | 600 s | Restores flight points: under 50 % of flight time |
| Sprint instantané | Feint | `RA_INSTANTSPRINT` | 62 | 180 s | Dispel and a random move: banned in a group |
| Fusil de chasse Manaleech | Manaleech Shot | `RA_DRAINSHOT` | 58 | 15 s | End of chain B, see below |
| Tempête de flèches | Arrow Storm | `RA_ARROWSTORM` | 40 | 30 s | Area, 5 m, 6 enemies, 15 m: "point de vigilance", not banned |
| Pièges (never cast) | Spike (13), Poisoning (16), Sandstorm (35), Spike Bite (37), Sleeping (52), Propelling (64) | `RA_SPIKETRAP`, `RA_POISONTRAP`, `RA_SANDSTORMTRAP`, `RA_SPIKEBITETRAP`, `RA_SLEEPINGTRAP`, `RA_SHOCKWAVETRAP` | | 20 to 120 s | `SUMMONTRAP`. "Piège à chocs" is read as Propelling Trap (?) |
| Not in a group | Oeil du chasseur (31), Oeil qui voit tout (40) | `RA_MINDSEYE`, `AS_SIXSENSE_H` | | | Seeing hidden players: not wanted |

The sigmas: Bow of Blessing `RA_ENCHANTBOW` (20), Focused Shots `SC_TRUESHOTMIND` (30, replaces Aiming), Arrow Deluge `RA_SPOUTARROW` (40, area),
Lethal Arrow `RA_MASSEXPLOSIONARROW` (45), Gale Arrow `RA_MOVINGSHOT` (50), Agonizing Arrow `RA_PAINARROW` (55). Focused Shots states -30 % physical defence as
its cost.

## What exists, and where it disagrees with the guide

Read from the code and the data, not watched:

- **Class**: `BotPosition.RANGED`, `BotRole.DAMAGE`. The ranged place (8 m from the enemy up to its reach, in its back half) is built and measured for
  groups; alone the bot closes to weapon range and stops.
- **The four "1 minute" buffs** (Dodging, Aiming, Strong Shots, Bestial Fury) each last as long as their cooldown, so `tryBuffSelf` keeps **all four** up, and
  the guide says a third removes the first: the infinite loop it warns of. Their effect ids all differ, so `coversTheSameEffect` does not stop it. How the
  engine enforces the two is to be read before milestone 2.
- **Transformation** is excluded from every buff rule (`isTransformation`, written for the rangers' four polymorphs), so it is never cast: the guide wants it
  in combat with 2000 DP.
- **Breath of Nature** is a toggle, and toggles are the mantras' alone, so it is never cast.
- **Stealth** (50 s hide) is cast once per fight while closing in (`tryApproachUnseen`): the guide says it is for travel and of no use in a fight.
- **Sleep Arrow, Entangling Shot, Silence Arrow, Shackle Arrow** are damaging skills with a debuff, so the generic offence casts them as attacks. Sleep on the
  main target would put it to sleep, to be woken by the next arrow.
- **Traps** (`SUMMONTRAP`) are never cast: only the lasting `SUMMON` is.
- **Seizure Arrow**, Focused Evasion, Devotion, Heal Wings: the generic order casts them as an attack, a defensive at 70 % health, an opener or never; none by
  the guide's conditions.
- **Misery Shot** (31, needs a controlled target), **Rupture Arrow** (after Stunning Shot), **Finishing Arrow** (53, after Remove Shock) are the "compétences
  conditionnelles": the engine asks for the state, and the generic order tries continuations first.
- **Bestial Fury**: -10 m attack range puts a ranged bot next to its enemy.
- **Being the target** is not looked at: nothing makes a grouped ranger stop shooting when the enemy turns on it. `enemy.getTarget() == bot` is the test, as
  for the chanter's `isHeldByEnemy`.

## Design

`RangerPlaybook` beside the chanter's: `act` in a fight, `tend` before one (the buffs go on before the target is chosen, which is where `tend` is called),
`claims` for what it times itself, `preferredStigmas` for the sigmas. Two things are new:

1. **A hold-fire memory**, in `BotCombatMemory` (in `PlayerBotAI` until 27 October) as `lastControlledAt` and `mendingToFull` are: set when the ranger's target turns on it in a group, read by the
   attack loop to not shoot for 5 s or until it is no longer the target, and Calming Whisper first. This changes the loop, not only a skill choice, so it
   needs the one hook `ClassPlaybook.holdsFire(bot, target)`.
2. **An enemy kind**: melee or caster, which decides between Entangling and Silence Arrow. The data should say (an NPC that casts versus one that only hits);
   to be read from the NPC template before milestone 3.

## Milestones, in verifiable order

0. **Name the skills.** *Done above, from the data, with the open points below.*
1. **The seam and the claims.** *Built: `RangerPlaybook`, registered; claims by skill group (`NEVER_GROUPS` for what a ranger never casts, `NOT_IN_A_GROUP` for the end of chain B and Arrow Deluge, the moves it times); a data test holds every group against the game data and puts every skill template through the claims. Still to confirm in game: that the generic order no longer casts any of them.* `RangerPlaybook` registered and silent, the groups and their claims in a data test (every skill template can be asked
   about, every group named exists). Claimed from the generic order: Stealth, Sleep, Entangling, Silence, Shackle, Calming Whisper, Seizure Arrow, Focused
   Evasion, Devotion, Heal Wings, Feint, the traps, Hunter's Eye and Advanced Eye.
2. **Personal buffs.** *Built in `nextBuff`: Breath of Nature once (a toggle, only when it is not up), the one minute buffs best first (Strong Shots, Focused Shots, Aiming, Dodging) and never more than two on at once, Bow of Blessing, Speed of the Wind, then in a fight Devotion and the Mau Transformation with 2000 DP. Called from `tend` before a fight, in a group and alone, and from the fight itself. The engine's own limit of two is not read: the rule keeps the bot from ever asking for a third.* Before a fight, alone or grouped: the 1 minute buffs, two at most, in the order Strong Shots, Aiming, Dodging, Bestial Fury
   (solo only, if at all); Bow of Blessing; Focused Shots in place of Aiming; Speed of the Wind right after; Devotion in combat before the first damage; the
   Transformation in combat with 2000 DP; Breath of Nature once after login or resurrection. Done when the unit tests of the order pass and a ranger holds two
   of the four and does not cast the third.
3. **Openers and handicaps.** *Built: Silence Arrow on a caster, Entangling Shot on a melee enemy, once per enemy (`BotCombatMemory.openedOn`), alone; Shackle Arrow within 2.5 m, alone; Sleep Arrow on another enemy than the main one, grouped (`BotGroupManager.otherEnemyOnTheGroup`); Seizure Arrow under 75 %, Focused Evasion before it when targeted under 50 % and never alone; Remove Shock then Finishing Arrow. An enemy is a caster if its skill list has a magical attack, melee if its attack range is 3 m or less, and neither otherwise.* Solo, at the start of the fight: Entangling Shot against a melee enemy, Silence Arrow against a caster. Shackle Arrow when an
   enemy is at 1 m. Sleep Arrow, grouped, on a target that is not the main one with two or more enemies. Seizure Arrow under 75 % health, with Focused
   Evasion first under 50 % when targeted (to be settled with the guide). Remove Shock when controlled, then Finishing Arrow, as the chanter's Backshock.
4. **Not becoming the target.** *Built: `nextHold`, a pure rule. A grouped ranger whose target aims at it casts Calming Whisper and stops shooting for five seconds or until the target looks away, and will not hold again for ten; the fight loop is not touched, `act` answers "I did something" and so the swing is not taken. The state is in `PlayerBotAI` (`holdingFireSince`, `holdFireRearmAt`).* The hold-fire hook above; positioning in the back of the enemy already exists.
5. **Flight and hide.** *Built: `ClassPlaybook.flees` (alone, three attackers, at any health) read by `retreatIfLosing`, and `onRetreat` which casts Tactical Retreat; `whileFlying`, called on each flight step, casts Heal Wings under half the flight points; Stealth from `tend` on a journey, with an aggressive enemy within 15 m or three enemies near. The hide is read from where the bot stands, not from its route.* Tactical Retreat with three or more enemies on a ranger alone; Stealth on a journey through dense ground or inside the notice of an
   enemy (the module has `AggroZone` for that); Heal Wings under 50 % flight points (`BotFlight` knows them).
6. **Traps: closed, by decision.** The only use the guide gives them, laying one before a fight, is its red paragraph, which is not to be done. Every trap
   and Nimble Fingers stay claimed and never cast; the group ban is therefore total, and the section is closed until the guide has a rule.
7. **Offence**: the generic order for everything else, the bans (`NEVER_GROUPS`, `NOT_IN_A_GROUP`), and the measurement as for the chanter (the debug line exists). *Built; the measurement needs a server.*
8. **Stigmas** from the sigma list. *Built: `preferredStigmas`, the guide's six; the list is its work in progress.*

## Group bans

Feint, Retreating Slash (both dashes pull monsters), Hunter's Eye and Advanced Eye, Bestial Fury (undecided), Stealth in a fight, Tactical Retreat, every trap,
and the end of chain B (Fleshcutter Arrow, Manaleech Shot) which the guide says forces melee. Fighting Withdrawal is a `BACKDASH` and is never chosen anyway.

## Proposed answers, and what is still open

Each is the plan's default; the author may overrule it. Marked **ask** where a wrong guess is costly.

1. **Damage skills**: the generic order, which ranks by stated damage and tries chain continuations first, minus the bans. The conditionals (Misery Shot, Rupture
   Arrow, Finishing Arrow) come through the engine's own conditions. Measured with the debug line as for the chanter, and changed only where it disagrees.
2. **Chain B**: Fleshcutter Arrow and Manaleech Shot never cast **in a group**, allowed alone; "Tir de sniper" is not found and is read as Swift Shot, the opener
   of chain B. The data gives them a bow's reach, so the engine decides whether they really force melee. **Ask** whether alone is right.
3. **Transformation**: **Mau**. The four variants are the same cost; White Tiger and Krall give +10 % attack, -30 % attack speed, +30 % speed, Mau +26 %, -26 %,
   +39 %, so Mau is the better of the two for a damage dealer. `RA_SABAGEROAR` and `RA_L_SABAGEROAR` differ by model, the second likely the other faction's;
   the bot casts whichever it knows. **Ask**: the guide may want the speed for the fight, or none at all given the attack speed.
4. **Seizure Arrow**: two rules. Under 75 % health, cast it. Under 50 % and being the target, Focused Evasion first, then Seizure Arrow. Focused Evasion is
   not cast alone.
5. **Retreating Slash**: never cast, alone or grouped (it is a `BACKDASH`, which the generic order refuses already). Shackle Arrow is the answer to an enemy at 1 m.
6. **Bestial Fury**: never, alone or grouped. It also costs 20 % attack speed and 10 m of range, which is against "stay at a distance". Last in the guide's
   priority behind two slots that Strong Shots and Aiming fill from level 25, it would never have been cast in practice. Its being claimed makes that
   explicit. **Ask** only if the guide wants it for some fight.
7. **Data not in the guide**: left to the generic order, except the toggle Sharpen Arrows (never: toggles are not the bot's to switch), Aether Arrow (4000 DP
   and 30 minutes: not cast until the guide says), and the traps. **Ask** about Aether Arrow and Nature's Resolve.
8. **Arrow Deluge**: not cast in a group (an area attack pulls the others), alone allowed; Arrow Storm, 5 m, is allowed everywhere as the guide says.
9. **Propelling Trap** as "Piège à chocs": moot, no trap is cast.
10. **Health**: the generic rules (Herb Treatment, potions, the leave-a-fight rule) plus Seizure Arrow and Focused Evasion; nothing else.
11. **Italics and highlights**: valid. That brings in Remove Shock, Shackle Arrow, Heal Wings, Feint, Manaleech, the stigma list, hiding on journeys, the group role
    ("without becoming the target", Calming Whisper, five seconds without shooting) and the bans.
