# Cleric: implementation plan

Status: milestones 0 to 8 are built and unit tested, and the heal thresholds were corrected by the tester on 25 October after a first run in game (below).
Built: the naming, the seam (`ClassPlaybook.heal`), the heals, the cleanses, the buffs, the resurrections, the offence, the divine power and the stigmas. What is left is what only play can say: whether the thresholds read as the guide meant them, which the open points below list. Source: the guide's document "Clerc 101" (eleven pages, in
French, with the tester's comments in the margin): the cleric is the game's main healer, keeps the group in health and adds to the damage; alone it kills with
magic and heals itself, and what limits it is mana. The pattern is the templar's and the chanter's ([templar-plan.md](templar-plan.md),
[chanter-plan.md](chanter-plan.md)): a `ClassPlaybook`, rules by skill **group**, a pure `decide` over a `Situation`, a data test. This file is the distance
between the guide and what the module does today.

How the guide was read: italic and yellow-highlighted passages count, as for the other classes (none is red here). The tester's margin comments are kept
as open points, not as rules. Names below are the client's English ones from `skill_templates.xml`, matched to the guide's French by level and description;
cooldowns are in seconds. Levels 1 to 9 are in the **priest** tree (`classId="PRIEST"`), which is why the class tree starts at 10.

## What the guide asks for, mapped to the data

| The guide | Client name | `group` | From | Cooldown | Notes |
|---|---|---|---|---|---|
| Béguin | Smite | `CL_SMITE` | 1 | 2 | Opener, cast when ready (the priest's first spell; settled by the chanter's plan) |
| Flashbolt / Étincelle divine | Flashbolt / Divine Spark | `PR_LIGHTNINGSHOCK` / `PR_DIVINESPARK` | 10 / 22 | 10 / 16 | Damage; the spark is the guide's "(proc)" |
| Grève sacrée | Hallowed Strike | `CL_HALLOWSWING` | 3 | 8 | Melee (1 m + weapon). **Solo only**, and only if the monster is already in melee |
| Embrasement incandescent | Infernal Blaze | `CL_SONICERUPTION` | 7 | 24 | Ranged opener, stun |
| Colère de la terre / Caisse claire Aero | Earth's Wrath / Aero Snare | `PR_PURGATORY` / `PR_HARSHPAIN` | 10 / 52 | 12 / 24 | **DoTs**: first in a group |
| Vent violent / Le toucher divin | Slashing Wind / Divine Touch | `PR_DIVINEBOLT` / `PR_DIVINETOUCH` | 10 / 17 | 16 / 14 | Damage |
| Invoquer le serviteur sacré | Summon Holy Servant | `PR_HOLYSERVENT` | 15 | 30 | "Equivalent of a DoT": first in a group, and the rotation's opener |
| La terre qui punit | Punishing Earth | `PR_POWERSMITE` | 30 | 16 | Damage |
| Châtiment | Chastise | `PR_REPENTANCE` | 36 | 16 | DoT (a debuff in the data: check it deals damage) |
| Un marteau étonnant | Slowing Hammer | `PR_RAISEREPENTANCE` | 64 | 8 | Melee (1 m + weapon): treated as Hallowed Strike |
| Explosion de puissance | Land's Bargain | `PR_HOLYEXPLOSION` | 60 | 60 | Costs HP for damage. The guide says "à voir": **not cast** until it settles |
| Mot de destruction | Word of Destruction | `PR_GODSVOICE` | 40 | 600 | 2000 DP, area. **Never** |
| Bénédiction du rocher | Blessing of Guardianship | `CL_BLESSOFROCK` | 5 | 5 | Group buff on self and members, **unless a chanter is in the group** or a better one is on. Shares an effect with the chanter's: see its traps |
| Contrainte | Root | `PR_ROOT` | 10 | 10 | With 2 or more enemies in the fight: on one that is not the priority and is engaged, not already rooted, asleep or polymorphed |
| Pénitence | Penance | `PR_MEDITATION` | 15 | 180 | MP over 30 s for HP. Under 50 % MP, with Light of Rejuvenation to offset |
| Stabilité | Stability | `PR_STIGMA_SUGGESTION` | 16 | 60 | **Group only**: an enemy has the cleric as its target; target it, cast, go back to the main target and the heals |
| Main de la réincarnation | Hand of Reincarnation | `PR_REVIVEHAND` | 22 | 600 | Out of combat, on self and on each member without it |
| Splendeur du vol | Splendor of Flight | `PR_CIRCLEOFWINGS` | 25 | 180 | As the cleric takes off (`onTakeOff`), if ready |
| La sagesse des sages | Sage's Wisdom | `PR_SAGESWISDOM` | 34 | 180 | No mana cost 10 s, -50 % cast time. Solo: when ready. Group: mana under 10 % or tank under 10 % |
| Prière de concentration | Prayer of Focus | `PR_FOCUSCASTING` | 36 | 60 | After a stun, stumble or spin, or after Remove Shock |
| Dissipation de choc | Remove Shock | `ALL_SHOCKREFLECT` | 40 | 60 | Then Impervious Veil (`PR_INVINSIBLEVEILL`, chain, under 30 % health) or else Storm of Vengeance (`PR_THUNDERBOLT`, chain): the chanter's Backshock pattern |
| Bouclier béni | Blessed Shield | `PR_BLESSEDSHIELD` | 40 | 120 | +100 % healing power and a weak shield, 20 s. Solo, in combat under 30 %, then heal to full |
| Linceul immortel | Immortal Shroud | `PR_INVINSIBLEWALL` | 46 | 180 | Medium shield, 8 s. When ready and not up; recast at once if lost in combat |
| Amplification | Amplification | `PR_PREPAREHOLYWAR` | 51 | 180 | Damage and heal. In combat when ready |
| Bouclier de vide | Life Curtain | `PR_CLOAKOFREMEDY` | 57 | 120 | Heals HP and MP, weak shield. Conditions unclear: see open points |
| Lumière de guérison | Healing Light | `CL_HEAL` | 1 | 0 | The standard heal |
| Lumière de rajeunissement | Light of Rejuvenation | `PR_RAPIDREGENERATION` | 10 | 5 | HoT. Solo: on self in combat. Group: on the tank from the start of each fight, recast as it ends |
| Lumière de résurrection | Light of Resurrection | `CL_REVIVE` | 10 | 0 | Single revive. See milestone 5 |
| Nettoyer / Splendeur de la purification | Cleanse / Splendor of Purification | `PR_PURIFY` / `PR_MASSDISPEL` | 13 / 42 | 0.5 / 10 | Cleanse; the area one first when several members need it |
| Cure radieuse | Healing Grace | `PR_GREATHEAL` | 19 | 6 | Big heal, 3 s cast. Tank under 60 % |
| L'éclair de la reprise | Flash of Recovery | `PR_FIRSTAID` | 24 | 30 | Instant, medium. Group under 50 %, self alone under 30 % |
| Vent de guérison | Healing Wind | `PR_MASSHEAL` | 28 | 3 | Area, 3.5 s cast |
| La lumière du rétablissement | Light of Recovery | `PR_EMERGENTHEAL` | 31 | 2 | Flash heal, 1 s. **Solo**, avoided in a group |
| Loci de résurrection | Resurrection Loci | `PR_CALLRESURRECTION` | 37 | 30 | Area revive, 6 s cast. Only for the exception below |
| Splendeur de la récupération | Splendor of Recovery | `PR_MASSEMERGENTHEAL` | 40 | 60 | Area heal and HoT, 2 s, **range 1 m**: group only, two members under 50 % |
| Éclair de guérison / Splendeur curative | Flash of Healing / Healing Splendor | `PR_HOLYLIGHT` / `PR_HOLYLIGHTWAVE` | 54 / 65 | 2 / 3 | 7 s channel (`skillcharge`), released at the top stage. Tank under 30 %, first of all / two members under 30 % |
| Le salut (2000 ED) | Salvation | `PR_SECRETPRECEPT` | 10 | 60 | Gives 50 % HP and MP back to the target. Solo under 20 %; in a group before 31, on self with mana under 20 % |
| Acquittement (2000 ED) | Acquittal | `PR_HOLYGRAIL` | 31 | 1800 | Cleanse and 20 % per 2 s for 10 s on the group |
| Une protection brillante (4000 ED) | Brilliant Protection | `PR_HOLYCIRCLE` | 50 | 1800 | +30 % HP and heal on the group, +healing power, auto-revive 1 minute |
| Saving Grace / Sympathetic Heal / Noble Grace / Reverse Condition | same | `PR_RESTORELIFE` / `PR_BINDINGHEAL` / `PR_HEALINGGRACE` / `PR_REVERSECONDITION` | 20 | 122 / 10 / 184 / 612 | The guide's stigmas ("Sigma"): all four are regular ones |

"ED" is the engine's DP, as for the templar and the chanter.

## The rules, by situation

**Alone.** Damage as soon as available, no mana limit; DoTs first because they keep working while the cleric heals. Opening for level 15 to 20: Holy Servant →
Smite → Flashbolt → Slashing Wind → Earth's Wrath → Infernal Blaze. Own health: under 40 %, heal to full, preferring the instant ones (Flash of Recovery,
Light of Recovery) and Healing Light when those are not ready; Healing Grace avoided in a fight (long cast, easily interrupted); Light of Rejuvenation kept on
self in combat; after a fight Healing Light under 90 %. Cleanse on self when needed. Penance under 50 % mana. Blessed Shield under 30 %, then heal. Life Curtain
above 70 % health.

**In a group.** Priority **heal > cleanse > damage**, and damage only with mana above 50 %, DoTs first. No heal while health is above 90 %. Never interrupt a heal
cast to change spell: once started, it ends. Stay in heal range of the tank, and out of melee with the main target (the stations of
[group-positioning-plan.md](../combat/group-positioning-plan.md) already do both). Order of need: tank = cleric, then the rest.

| Moment | Cast |
|---|---|
| The lowest threshold wins | Tank under 30 % Flash of Healing, under 50 % Flash of Recovery, under 60 % Healing Grace (not when an enemy is hitting the cleric) |
| Area or single | Two members hurt: the area heal first while the tank is at 50 % or more, the tank's own heal first under 50 % |
| Two members hurt | Under 30 % Healing Splendor, under 50 % Splendor of Recovery, under 60 % Healing Wind |
| A member under 80 % | Healing Light |
| In combat | Light of Rejuvenation on the tank from the first moment, recast as it ends |
| Cleric hurt, health under 70 % | Heals another member with an area heal, which heals it too |
| Tank under 60 % | Saving Grace (one-minute buff, 2 min cooldown) |
| Cleanse | Self or tank first, then the others if mana is above 50 %; the area cleanse when several need it |
| Light of Recovery | Avoided: poor mana efficiency |

## What existed, and where it disagreed with the guide

Read from the code, not watched. `BotRole.HEALER` already exists ([group-roles.md](../combat/group-roles.md)): the cleric heals first, joins the attack only above 60 %
mana, keeps its distance, and takes a heal station (this session's fix). Beneath that, nothing is the guide's:

- **One generic heal, picked by power.** `tryHealAlly` takes every skill with a heal effect that can reach others and casts **the strongest first**
  (`statedPower`), at `HEAL_ALLY_PERCENT` 75 %: a cleric opens with Healing Grace or a big area heal on a member at 74 %, where the guide wants Healing Light under
  80 % and Healing Grace only for a tank under 40 %. No notion of the tank, of the cleric's own priority, of a HoT, of a threshold that depends on who is hit.
- **Every skill that is not a toggle is offered.** That includes Word of Destruction, Flash of Healing (a seven second channel the bot releases early),
  Land's Bargain and Resurrection Loci. Hallowed Strike is cast in a group.
- **Buffs by the generic upkeep.** Blessing of Guardianship is put on whoever lacks it even when a chanter's better buff is on (the loop the chanter's plan documents).
- **No resurrection, no cleanse rule, no Stability, no use of DP at all**; Penance and Sage's Wisdom are cast whenever they are ready.

## Design

`ClericPlaybook` beside the other three, registered in `BotPlaybooks`; decisions a pure function of a `Situation`, thresholds argued in
`ClericPlaybookTest`, and a data test that puts every skill template through `claims` (`ClericPlaybookDataTest`): a template with no group has already thrown on
every tick once. Two seams the interface lacks, since a healer's work is not in the fight loop but around it:

1. **`heal(Player bot)`**, default false, called first in `PlayerBotAI.tendToTheGroup` and in the fight loop ahead of `useBestSkill`, so that the generic heal
   never acts for a cleric. It carries the whole table above.
2. **A rolling health record of the tank**: one sample a second on the decision tick, thirty of them, giving how much the tank lost in 30 s (the sum of its
   drops: heals are not known, which makes it an under-estimate and so a prudent one). It is what Acquittal and Brilliant Protection ask for. The tester's
   doubt, "une usine à gaz", is why it is a single small array in the bot's own AI state and not a service.

`claims` returns true for every skill the playbook decides: all heals, cleanses, the buffs and defensives, the forbidden groups always. `bot.isCasting()` already
stops a second cast starting (`BotSkillManager.cast`); what remains is that **nothing else may move or stop the body during a heal cast**, which is checked in
milestone 2.

## Milestones, in verifiable order

All built, in `ClericPlaybook` (decisions are pure functions of a `Situation`, argued in `ClericPlaybookTest`, 37 cases; `ClericPlaybookDataTest` puts every skill
template through `claimsSkill` and checks every group named exists). What each one did, and the choices the guide left to the code:

0. **Name the skills.** *Done from the data* (table above).
1. **The seam.** *Built.* `ClassPlaybook.heal(bot)`, default false, called first in `tendToTheGroup` and, after `act`, in `useBestSkill`. `BotPlaybooks` maps `CLERIC`.
2. **Heals as the guide rations them.** *Built: `decideHeal`.* Group (the tester's thresholds of 25 October): nobody above 90 %; under 80 % Healing Light; on the tank, the lowest threshold first: the
   channel under 30 %, the instant heal under 50 %, Healing Grace under 60 % unless something is hitting the cleric; Saving Grace on the tank under 60 % once; for two members, the
   channelled area heal under 30 %, Splendor of Recovery under 50 %, Healing Wind under 60 %, ahead of the tank's own heal unless the tank is under 50 %; a cleric under 70 % heals
   itself through the area heal when another member is hurt; the heal over time on the tank from a fight's first moment; Salvation on itself before 31 with mana under 20 %. **Who first:** the tank and the cleric are one rank and the lower
   goes first, then the others, and a mate under 30 % outranks them while neither is under 50 %: that last clause is the code's, the guide only says "tank = cleric > the rest".
   Sympathetic Heal and the instant heal are fallbacks when Healing Light is not ready. Alone: under 40 % heals to full (the instant ones first in a fight, Flash of Recovery only under 30 %, and it goes on to 100 % through
   `mendingToFull`), Healing Light after a fight under 90 %, Blessed Shield under 30 %, Salvation under 20 % with 2000 DP, the heal over time on itself in a fight, Life Curtain. The
   heal is never interrupted: the decision tick does nothing while `isCasting()`, and `BotSkillManager.cast` refuses to start a second cast. The two bandage skills every class knows
   (`MENDING`, `AROMATHERAPY`) are claimed so that the generic heal cannot fall back on them.
3. **Cleanse.** *Built.* After the heals, before the damage: the cleric itself, then the tank, then another member only with mana above 50 %; Splendor of Purification when two or more
   carry something. "Something a cleanse can reach" is `hasDispellableDebuff` at level 3; the engine decides whether the effect is then strong enough.
4. **Buffs and utility.** *Built.* Out of a fight, `upkeep`: Blessing of Guardianship on the cleric and each member near it that lacks it (and not when a chanter is in the group), Hand of
   Reincarnation the same. In a fight, `decideUtility`: Penance under 50 % mana with 50 % health, Sage's Wisdom (alone when ready, in a group at mana or tank under 10 %), Immortal
   Shroud, Amplification, Stability on an enemy that has the cleric as the one it hates most, Root on another enemy of the group that is not held, asleep or polymorphed.
   `decideFight`: held means Remove Shock and nothing else, then Impervious Veil under 30 % or Storm of Vengeance while the chain is open, then Prayer of Focus. Splendor of Flight
   in `onTakeOff`, in a group. Penance's cost in health is not in the data (see open points).
5. **Resurrection.** *Built.* Light of Resurrection out of a fight on `mateToResurrect`, which now gives the chanter the right of way over a cleric bot and a human cleric or chanter
   over both; Resurrection Loci on a dead member within 30 m when more than three are dead in an instance, in a fight or out of one. The engine side is the chanter's.
6. **Offence.** *Built: `decideOffence`.* The servant (`trySummonServant`), then the damage over time spells (Earth's Wrath, Aero Snare, Chastise) when not already on the target; in a
   group only with mana above 50 %. The rest of the damage is the generic order's, which `hasManaToSpare` now holds to the same 50 % (it was 60). Hallowed Strike and Slowing Hammer
   only alone and only when the monster is within 3 m. Forbidden and claimed: Word of Destruction, Land's Bargain, Reverse Condition. The opening
   "servant, Smite, Flashbolt, Slashing Wind, Earth's Wrath, Infernal Blaze" is not a coded order: the servant and the damage over time come first and the generic order, which
   casts the strongest first, does the rest.
7. **DP.** *Built.* Acquittal from 31 to 50 when two members have each lost 100 % in 30 s (`HealthRecord`, one reading a second per member, the sum of the drops); Brilliant Protection
   from 50 with 4000 DP when the cleric is under 20 % or the tank has lost 200 % in 30 s; Salvation alone. The tester's doubt is met by the record being thirty integers per member.
8. **Stigmas.** *Built:* `preferredStigmas(false)` is Saving Grace, Sympathetic Heal, Noble Grace; the advanced sockets have no opinion. Noble Grace and Reverse Condition are the
   guide's, and the latter is never cast.

## Open points for the guide's author

1. **The sign of the thresholds.** *Settled by the tester on 25 October*: every "HP > x %" was "below", and the self-through-area rule is "under 70 %". The
   corrected values are in the tables above. Life Curtain is still read as written ("> 70 %" solo, "> 50 % or MP > 30 %" grouped).
2. **Reverse Condition** swaps HP and MP (`switchhpmp`); the guide highlights it without a rule and the tester calls it dangerous. Planned: never cast.
3. **Béguin = Smite** and **Bouclier de vide = Life Curtain** are matched by level and description, not by name.
4. **Penance** is "HP for MP over time": the data shows only an MP heal; check what it costs before relying on the guide's "offset with Rejuvenation".
5. **"Aede present"** (Blessing of Guardianship, Light of Resurrection): the bot class is the chanter's; does a templar or another class count? Planned: chanter only.
6. **Tank loss rate** over 30 s, for Acquittal and Brilliant Protection: the planned approximation (sum of drops) under-counts when the tank is being healed.

## Traps to check before trusting a rule

- Charge skills (Flash of Healing, Healing Splendor): `BotSkillManager.releaseWhenCharged` lets them go as the top stage begins. Cast since 25 October: check in game that the heal lands at the top stage and is not cut short by the cleric moving.
- Area heals have `first_target="ME"` and a range: the engine heals the members **within it of the caster** (Splendor of Recovery 1 m), not the whole party.
- A one second cooldown buff is "ready" every second (the ranger's lesson): Penance, Prayer of Focus and Blessed Shield need an "already on" test.
- The chanter's buff shares an effect id with Blessing of Guardianship: use `coversTheSameEffect`, or two bots cast at each other for ever.
- A cleanse is aimed only at what the engine would remove: the debuff slot and level, a debuff category (an assassin's rune is none), no sanctuary and
  no day-long effect (`BotSkillManager.isCleansable`). The power cannot be read beforehand, so an effect still there once a cleanse has landed is
  left alone until it ends. A tester saw the cleric cleanse an undispellable rune until it wore off (14 October).
- Cast time: a heal of 3 s on a tank at 40 % can land late. The cleric's mana, not its timing, is what the guide rations.

## Where this stands

Built and unit tested. A first run in game (25 October) found the heal over time never put on an unhurt tank, and moved the thresholds; both fixed, not yet watched again. The next step is a run in game with a cleric in a group, reading the debug log (`ClericPlaybook` logs every cast with the health and mana it was made at) against the guide: the tank under 40 % and the big heal, the cleric hurt and the area heal, a stun and Remove Shock followed by the veil or the storm.
