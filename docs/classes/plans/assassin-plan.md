# Assassin: implementation plan

Status: not started. Written on 18 November 2026, **without a guide**, as the bard's, the spirit master's, the gunner's and the aethertech's were
([aethertech-plan.md](aethertech-plan.md)): every rule from the data, or marked *(play)* when it is judgement.

## Where the rules come from

- **the data**: `skill_templates.xml`, `skill_tree.xml` and `signet_data_templates.xml`, read on 18 November for every group the class learns
  (`PlayerClass.ASSASSIN`, 79 groups with the scout's): cooldowns, chains, effects, conditions, rune caps; and the engine's `CarveSignetEffect` and
  `SignetBurstEffect`, which say how a rune is carved and what bursting it is worth. These are facts.
- **(play)**: how the class is generally played in 4.8. Each such rule is the first thing a player of the class reviews and a watch in game checks.

The pattern is the aethertech's: an `AssassinPlaybook`, rules by skill **group**, pure `decide` functions, their tests, a data test, the French
client's names in `glossary/assassin.md` (read from the client's `client_strings_skill.xml`), and the chain guard of
[skill-traps.md](../../traps/skill-traps.md).

## What the class is, from the data

A physical melee damage dealer with two weapons (`BotGearFit.weaponsOfTrade`: daggers and swords; it also knows the bow, which the module does not
give it), standing behind its target (`BotPosition.BEHIND`). **Everything rests on its runes**: a rune is an effect of stack `SIGNET1` on the target,
whose skill level is the rune's level, 1 to 5. Some skills carve it one level higher, up to their cap; others burst it, for damage multiplied by the
level it had (`signet_data_templates.xml`, `SIGNET1`):

| Rune level | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| Damage multiplier | 0.1 | 0.2 | 0.5 | 1.0 | 1.2 | 1.5 |
| Chance of the burst's added effect, % | 0 | 0 | 20 | 50 | 70 | 100 |

A burst at no rune does a tenth of its damage; at five, fifteen times that, with its added effect certain. Carving never lowers a rune
(`CarveSignetEffect.applyEffect` keeps the higher), so a cap-3 carver on a rune of 5 only refreshes it.

## What exists, and what is missing

- **No assassin playbook.** The generic order casts by stated damage with chain links first (`statedPower` reads no rune), so it **bursts
  whatever is ready, at whatever rune is up**: Pain Rune, its strongest stated skill, goes the moment it is off cooldown, at whatever rune is up. This
  is the class's whole damage thrown away, and it is the first thing to fix.
- **What already works**: the hide to arrive unseen (`tryApproachUnseen`: Hide, Shadow Walk), the leaps (`tryGapCloser`: Dash Attack, Ambush,
  Fangdrop Stab, Dash and Slash), two weapons, standing behind, the event skills (`isWaitingForTheEventItAnswers`: Counterattack waits for a dodge).
- **Nothing reads the rune on the target.** A `BotSkillManager.runeLevel(target)` reads `SIGNET1`'s level, on the pattern of `isOnTarget`.
- **The weapon coatings and the cooldown buffs** (Apply Deadly Poison, Apply Lethal Venom, Killer's Eye, Deadly Focus, Flurry) are cast by the
  generic buffs whenever they are ready, worth it or not.
- **The pieces exist**: the gunner's `worthABurst`, the sorcerer's `secondToControl`, `isEliteOrBoss`, the gunner's Memory Manipulation rule
  (a damage dealer in a group lowering its hate), the ranger's lone `flees`, the gladiator's way of keeping a cooldown for an elite.

## The skills, mapped

Cooldowns in seconds. "St" is a stigma tier (1 regular, 2 and 3 advanced, 4 linked). Cap is the rune level a carver stops at.

Runes.

| Client name | French | `group` | Cooldown | From the data |
|---|---|---|---|---|
| Rune Carve → Sigil Strike (st 1) | Sculpture runique, Frappe du sigil | `AS_CARVESIGNET`, `AS_CARVESIGNETCHAIN` | 7, 6 | Carve, cap 3; chain D |
| Rune Knife (st 1) | Couteau runique | `AS_SIGNETWAVE` | 7 | Carve at 20 m, cap 3 |
| Fang Strike → Beast Kick → Beast Swipe | Coup de croc, Coup de pied de la bête, Balayage de la bête | `AS_TIGERFANG`, `AS_TIGERKICK`, `AS_TIGERUPPER` | 6 each | Carve, **cap 5**; chain E; cast standing |
| Ripclaw Strike | Croc de bête | `AS_TIGERBEAT` | 60 | Carve cap 5, and hate |
| Massacre | Massacre | `AS_GENOCIDALSWORD` | 90 | Carve cap 5, poison and snare, an area of 16 m |
| Pain Rune | Éclatement de la rune de douleur | `AS_SIGNETBURST` | 18 | Burst, 10 m |
| Rune Burst | Éclatement des runes | `AS_SIGNETFLARE` | 12 | Burst |
| Blood Rune | Rune de sang | `AS_DRAINBURST` | 60 | Burst that drains health |
| Binding Rune | Rune de liaison | `AS_HEAVENSEDGE` | 60 | Burst; its added effect read at milestone 0 |
| Signet Silence | Signet Silence | `AS_SILENTBURST` | 30 | Burst that silences |
| Explosive Rebranding (st 4) | Sculpture runique répétée | `AS_RESIGNETASSAULT` | 60 | Bursts, then carves again |
| Agony Rune (st 2) | Rune d'agonie | `AS_CHAINSIGNETBURST` | 12 | Follows any burst (chain F); plain damage, no rune |

Chains, all the generic order's once open: A (Swift Edge → Soul Slash → Rune Slash, carve cap 3; or → Lightning Slash, st 2), N (Surprise
Attack, damage from behind → Side Strike → Encircling Strike), I (Counterattack after a dodge, or Whirlwind Slash → Cross Slash), J (Assassination
→ Killing Spree three times), P (Dash Attack → Blind Side), and the rune chains D, E and F above. Remove Shock opens Shadow Illusion (a 3 s hide)
and Cyclone Slash (carve cap 5).

Buffs, divine power.

| Client name | French | `group` | Cooldown | From the data |
|---|---|---|---|---|
| Apply Deadly Poison | Appliquer un poison mortel | `AS_STUNINGINTENTION` | 10 | Proc on its blows (`provoker`) |
| Apply Lethal Venom (st 2) | Appliquer le venin mortel | `AS_EXPLOSIONPOISON` | 184 | Proc on its blows |
| Killer's Eye | L'œil du tueur | `AS_STRIKINGINTENTION` | 30 | The next skill harder |
| Eye of Wrath (st 1) | L'œil de la colère | `AS_VISIOUSEYE` | 61 | The next skill critical |
| Deadly Focus, Flurry, Devotion | Focalisation mortelle, Flurry, Dévotion | `AS_RAIDSTANCE`, `AS_QUICKMOVE`, `SC_INVOKEPOWER` | 300, 120, 30 | Attack buffs |
| Deadly Abandon (st 1) | Abandon mortel | `AS_STABSTANCE` | 306 | Attack up, a defence down: *(play)* alone only on a monster it can kill |
| Oath of Accuracy (st 1), Sensory Boost (st 2) | Serment de précision, Boost sensoriel | `AS_HITMANMIND`, `AS_SENSEBOOST` | 184 | Accuracy |
| Scoundrel's Bond (st 3) | Serment de la dague | `AS_BACKFOCUS` | 122 | Proc |
| Divine Strike | Frappe divine | `AS_DIVINEASSAULT` | 30 | **2000 DP** |
| Triniel's Dirk, Vaizel's Dirk | Dirk de Triniel, Dirk de Vaizel | `AS_DEATHBRINGER`, `AS_SOULBREAK` | 1800 | **4000 DP**, one per faction |
| Spiral Slash | La lame hélicoïdale | `AS_SPIRALSTAB` | 600 | **4000 DP**, a dash and a stun at 25 m |
| Wind Walk | Marche au vent | `AS_WINDWALK` | 60 | **2000 DP**, a hide and speed: never in a fight *(play)* |

Defence, control.

| Client name | French | `group` | Cooldown | From the data |
|---|---|---|---|---|
| Focused Evasion | Évasion ciblée | `SC_WHISPEROFWIND` | 30 | Every blow dodged, every spell resisted |
| Aethertwisting, Spelldodging | Aethertwisting, L'assoupissement | `AS_WHISPEROFGALE`, `AS_ALERTSTANCE` | 60, 300 | Every spell resisted |
| Evasive Boost | Renforcement des capacités d'évasion | `AS_REPEATEVADE` | 60 | Evasion |
| Flash of Speed | L'éclair de vitesse | `AS_SHADOWDODGE` | 180 | Cleanses roots and snares, **a 15 m dash forward** |
| Break Away (st 2) | Posture de fuite | `AS_RUNNERSSTANCE` | 122 | A cleanse, and stun resistance for 7 s |
| Slayer Form | Test de compétence du tueur (the data's last rank) | `AS_DEFENDROAR` | 180 | A cleanse, magic resistance, 3000 health, a minute |
| Calming Whisper | Chuchotement apaisant | `AS_BREEZEOFCALM` | 120 | **Lowers its hate** on the target |
| Shimmerbomb (st 4), Blinding Burst | Éclat aveuglant (both) | `AS_WIDENEWBLINDINGBURST`, `AS_BLINDINGBURST` | 184, 180 | Blinds round it; Shimmerbomb also turns them away |
| Shadow Walk (st 1), Hide, Tactical Retreat | Marche des ombres, Cacher, Retraite tactique | `AS_STIGMA_SHADOWWALK`, `SC_HIDE`, `SC_SPIRITOFGALE` | 184, 60, 120 | Out of a fight; the way out |
| Searching Strike (st 1), Quickening Doom (st 3) | Raid embusqué, Accélérer le malheur | `AS_BACKBLOW`, `AS_VENOMSLASH` | 30, 61 | Stuns |
| Beast Leap | Le saut de la bête | `AS_TUMBLING` | 60 | A stun and a jump back |
| Shadowfall (st 1), Crashing Wind Strike | Chute de l'ombre, Automne | `AS_SHADOWDROP`, `AS_HELLSBLADE` | 61, 60 | Only on a target stunned, and thrown in the air |
| Weakening Blow, Venomous Strike (st 1) | Briseur de dos, Frappe venimeuse | `AS_BACKASSAULT`, `AS_VENOMSTAB` | 60, 122 | Damage and a weakening; damage and poison |
| Searching Eye, Advanced Eye | Recherche de l'œil, L'œil qui voit tout | `AS_SIXSENSE_L`, `AS_SIXSENSE_H` | 120, 300 | Reveal the hidden: nothing asks for it yet |

Left to the generic order: the chains, Weakening Blow, Venomous Strike, the leaps (`tryGapCloser`) and the approach hide (`tryApproachUnseen`),
Herb Treatment and MP Recovery. Kept from it: Searching Eye and Advanced Eye, Wind Walk, Sprinting (a toggle the generic order never casts anyway).

## Milestones, in verifiable order

0. **Name the skills**: `glossary/assassin.md`; every group above checked by `AssassinPlaybookDataTest`: each carver's cap, each burst's
   `SIGNET1`, the chain categories derived as the aethertech's are. Read: Binding Rune's added effect, whether Fangdrop Stab and Blind Side need
   the target's back, what Break Away cleanses, and whether `SIGNET1` is the one rune every carver and burst of the class names.
1. **The seam**: `AssassinPlaybook` registered, `BotSkillManager.runeLevel`, the chain guard, the bursts and carvers claimed.
2. **The runes**: carve while the rune is under 5, with the highest cap ready (Fang Strike's chain and Ripclaw Strike before Rune Carve); a cap-3
   carver only while the rune is under 3. **Burst at 5**, at 3 or more when the bot knows no carver of cap 5 (under level 16), or at any rune
   when the target is under 15 % and will die before the rune grows *(play)*. Which burst: Signet Silence on a caster, Blood Rune under 60 %
   health, then Pain Rune, Rune Burst, Binding Rune; Explosive Rebranding at 5, which carves again. Tests: no burst at 0, 1 or 2 on a target
   that will live.
3. **The opening** *(play)*: from hiding, Surprise Attack when behind (its damage is from behind), Ambush when not; Killer's Eye before the first
   burst at 5.
4. **The coatings and the buffs**: Apply Deadly Poison kept up in a fight; Apply Lethal Venom, Deadly Focus, Flurry, Oath of Accuracy, Sensory
   Boost, Eye of Wrath and Scoundrel's Bond on what is worth them (the gunner's `worthABurst`); Deadly Abandon alone, on an ordinary monster.
5. **The divine power** *(play)*: the faction's dirk at 4000 on a boss or a player, Spiral Slash at 4000 on a player out of reach, Divine Strike at
   2000 otherwise on an elite.
6. **Hate** in a group: Calming Whisper when the target turns to it, the gunner's rule; Shimmerbomb when two or more are on it.
7. **Defence**: Remove Shock, then Shadow Illusion alone under 30 %, else Cyclone Slash; Focused Evasion hit under 50 %; Aethertwisting by a
   caster; Flash of Speed or Break Away rooted or snared; Slayer Form under 40 %; Evasive Boost hit by two. Alone under 25 %: Shadow Walk or
   Tactical Retreat, then `flees`.
8. **Control**: Searching Strike or Quickening Doom on a caster or a player; Shadowfall and Crashing Wind Strike when the target is stunned or in
   the air, as the data asks; Beast Leap on a melee player.
9. **Stigmas** *(play)*: regular Sigil Strike, Rune Knife, Eye of Wrath, Oath of Accuracy, Shadow Walk; advanced Agony Rune, Lightning Slash,
   Apply Lethal Venom, Quickening Doom, Explosive Rebranding, Shimmerbomb.
10. **Stat weights**: already crit then attack (`BotStatWeights`); accuracy after, a dodge-reliant class that misses carves nothing *(play)*.

Tests per milestone, pure as the others' are; then a watch in game: an assassin alone in Eltnen and one in a group with `//bot debug combat` on,
the rune level at each burst in the log, and its damage against the measure of the roadmap's theme 3.

## Questions for a player of the class

Whether bursting at 5 is worth waiting for against an ordinary monster or only against an elite; which burst first; whether Deadly Abandon belongs in
PvE; which stigmas; whether the bow is ever worth holding.
