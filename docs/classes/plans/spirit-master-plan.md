# Spirit master: implementation plan

Status: built, unverified: in game. Written and built on 16 November 2026, **without a guide**, as the bard's was ([bard-plan.md](bard-plan.md)):
every rule from the data, or marked *(play)* when it is judgement. `SpiritMasterPlaybook`, 17 decision tests and 9 data tests; the commands carried
out by `BotServantAI`; never yet watched in game.

## Where the rules come from

- **the data**: `skill_templates.xml` read on 16 November with `tools/skill-lookup.ps1 -Class SPIRIT_MASTER` (75 active groups), the effects
  of each group's first rank, and the engine's code for the spirit's orders. These are facts.
- **(play)**: how the class is generally played in 4.8. Each such rule is the first thing a player of the class reviews and a watch in game
  checks.

The pattern is the bard's: a `SpiritMasterPlaybook`, rules by skill **group**, a pure `decide`, its tests, a data test, the French client's
names in [glossary/spirit-master.md](../glossary/spirit-master.md). The chains were read again at milestone 0, every rank of each group: the first
pass had caught links of neighbouring templates, and **Emnity Swap follows no chain** (the table below is the corrected one).

## What the class is, from the data

A ranged magical caster in cloth (orb or spellbook), 25 m reach, whose damage is **two**: its own spells, mostly damage over time and
curses, and its **spirit**, a summon (`EffectType.SUMMON`) that fights beside it. `BotServantAI` already says it: without its spirit a spirit
master is a cloth caster with no damage. Role: `RANGED`, damage, never the group's healer.

## What exists, and what is missing

What the module had before this plan, and what it lacked (all of it built since, milestones below):

- **The spirit is called up and driven** (`BotSkillManager.trySummonServant`, `PlayerBotAI.callUpServant`, `BotServantAI`): it follows its
  master, defends it, attacks what it attacks, and casts its own npc skills (`npc_skills.xml`) when the data says they are ready. It is sent
  away at a stall.
- **Which spirit is not chosen**: `trySummonServant` takes whatever the generic order's sort puts first (chain links, then stated damage,
  which a summon has none of). Fire, Earth, Water and Wind are the same in its eyes.
- **The commands never happen (the trap).** Every "Command:" and "Spirit …" skill (`petorderuseultraskill`) only *queues* an order on the
  summon (`Summon.addSkillOrder`) and tells the client; the order is carried out when the client answers with `CM_SUMMON_CASTSPELL`, which
  calls `summon.getController().useSkill(order)`. A bot has no client: the cooldown is spent, the order sits in the queue, nothing hits. If
  the generic order casts these today, it casts them for nothing, and the queue only grows. To be confirmed in the log first
  (milestone 1), then drained by `BotServantAI`, as the client would.
- **The spirit's buffs and heals** aim at `FRIEND/ONLYONE/MYPET`: Divine Spirit Armor, Replenish Element, Armor Spirit, Healing Spirit,
  Sympathetic Mind, Elemental Spirit Armor, Spirit Absorption. Whether `BotSkillManager.cast` can aim at the summon at all is unchecked: every
  rule there aims at the bot, a mate or an enemy.
- **The pieces exist**: the sorcerer's controls on a second enemy (`secondToControl`), the fear refused in a group (`BotSkillManager.cast`),
  the area refused among other packs, the DP rules, hold fire, `flees`, the charge release (`releaseWhenCharged`, for Stone Scour).

## The skills, mapped

Cooldowns in seconds. "St" is a stigma (its tier). Charged is `CHARGE` in the data.

The spirit.

| Client name | `group` | Cooldown | Notes |
|---|---|---|---|
| Summon: Fire / Wind / Earth / Water Spirit | `EL_SUMMON_FIREELEMENTAL`, `…WINDELEMENTAL`, `…EARTHELEMENTAL`, `…WATERELEMENTAL` | 5 | From 10, 13, 16, 19; no time limit |
| Summon: Tempest / Lava Spirit | `EL_SUMMON_TEMPESTELEMENTAL`, `EL_SUMMON_MAGMAELEMENTAL` | 600 | From 50, **2000 DP**, 20 min (`time="1200"`) |
| Replenish Element | `EL_PET_RECOVERHEALTH` | 10 | Heals the spirit |
| Spirit Absorption | `EL_UNSUMMON` | 120 | Heals the master from the spirit |
| Divine Spirit Armor | `EL_ETHERINFUSION` | 30 | **2000 DP**, the spirit healed and raised |
| Armor Spirit, Elemental Spirit Armor, Sympathetic Mind | `EL_ENCHANTARMOR` (st 3), `EL_ELEMENTALSHIELD`, `EL_ELEMENTALBOOST` | 60, 180, 10 | The spirit's defence; Sympathetic Mind its stats |
| Healing Spirit | `EL_ELEMENTALCHARGE` (st 2) | 90 | Heals and cleanses the spirit |
| Summoning Alacrity | `EL_ELEMENTALCALL` | 60 | Faster summoning: before a resummon in a fight |

Commands: each queues one of the spirit's own skills (milestone 1).

| Client name | `group` | Cooldown | Notes |
|---|---|---|---|
| Spirit Disturbance, Spirit Detonation Claw | `EL_PET_INTERUPT`, `EL_ORDER_ASSAIL` | 14, 18 | Interrupt; damage |
| Spirit Erosion, Spirit Wrath Position | `EL_ORDER_DECAYING`, `EL_PET_ELEMENTALWRAITH` | 120, 120 | The spirit's damage cooldowns |
| Spirit Wall of Protection, Spirit Burn-to-Ashes, Spirit Ruinous Offensive | `EL_ORDER_ELEMENTALFIELD`, `EL_STIGMA_ORDER_ETHERERUPTION`, `EL_STIGMA_ORDER_DESTRUCTIMPACT` | 122, 183, 30 | Stigmas (1, 1, 2) |
| Command: Bodyguard, Command: Absorb Wounds, Spirit Preserve | `EL_ORDER_PROTECT`, `EL_SOULPROTECT` (st 4), `EL_ETERNALSHIELD` | 180, 306, 180 | The spirit protects the master |
| Spirit Pique | `EL_PET_WIDETAUNT` | 30 | The spirit taunts around it: alone, to take a pack off the master |
| Command: Kamikaze | `EL_ORDER_EXPLODE` | 180 | The spirit is **released** in the blast |
| Spirit's Empowerment | `EL_SOULKING` (st 4) | 183 | Unsummons the spirit for the master's spell power |

Damage and curses.

| Client name | `group` | Cooldown | Notes |
|---|---|---|---|
| Erosion | `MA_GRAVITYCAGE` | 3 | The filler, with damage over time |
| Vacuum Choke | `EL_VACUUMEXPLOSION` | 1 | The other filler |
| Chain of Earth → Stone Shock | `EL_EARTHGRAB`, `EL_STONESHOCK` | 15, 30 | A chain; the first snares |
| Ignite Aether, Aegis Breaker, Magic Implosion, Disenchant | `EL_DARKBURST`, `EL_DISPELEXPLOSION`, `EL_MANAREVERSE` (st 2), `EL_DISENCHANTMENT` | 30, 24, 30, 1800 | Strip a buff and hit; Disenchant is an area, **2000 DP** |
| Weaken Spirit → Ritual Push | `EL_DIMISSPOLYMORPH`, `EL_ELEMENTALSTRIKE` | 12, 12 | A chain: damage |
| Backdraft, Vengeful Backdraft | `EL_COUNTHPMPDRAIN`, `EL_QUICKHPMPDRAIN` | 24, 60 | Drain health and mana |
| Soul Torrent | `EL_SOULSTEAL` | 60 | Damage |
| Withering Gloom, Infernal Pain, Infernal Blight | `EL_ENERVATIONCURSE` (st 1), `EL_HELLPAIN` (st 2), `EL_HELLCURSE` (st 3) | 122, 30, 183 | Curses: damage over time, a stat down |
| Magic's Freedom, Shackle of Vulnerability | `EL_MAGICALBREAKDOWN` (st 1), `EL_ENFEEBLEMENT` (st 2) | 60, 30 | Defence down; slow and casting slowed |
| Stone Scour | `EL_CURSEDBREATH` (st 1) | 30 | Charged |
| Cyclone of Wrath | `EL_STIGMA_STORMBLADE` (st 1) | 12 | Damage |
| Summon Wind / Cyclone Servant | `EL_SLAVE_WINDSERVENT`, `EL_SLAVE_STORMSERVENT` (st 2) | 10, 30 | A servant that flies at the target (`summonhoming`), not the spirit |
| Sandblaster, Blood Funnel, Cursecloud | `EL_AREACAGE`, `EL_ENERGYSINK` (st 4), `EL_PLAGUESWAM` | 10, 183, 1800 | **Areas**; Cursecloud **4000 DP** |
| Wing Root | `EL_DECAYINGWING` | 120 | Flight time down: a flying enemy |

Control and defence.

| Client name | `group` | Cooldown | Notes |
|---|---|---|---|
| Fear, Fear: Poppy, Fear: Ginseng, Curse of Fire / Water | `EL_FEAR`, `EL_FEAR_PORGUSS`, `EL_FEAR_SAAM`, `EL_FIREFEAR`, `EL_WATERFEAR` | 30, 30, 30, 300 | Fears: alone only (the cast rule already) |
| Fear Shriek, Flames of Anguish | `EL_TERRORSPIRIT`, `EL_DESPAIRCURSE` | 60, 180 | Area fear around it; fear and root, 10 m |
| Root of Enervation, Body Root, Nightmare, Blade of Earth | `EL_SLOW`, `EL_BIND`, `EL_NIGHTMAREROOT`, `EL_GAIAGRAB` | 10, 20, 16, 15 | Slow, bind, root; Blade of Earth an area snare |
| Sigil of Silence, Dispel Magic | `EL_SILENCE`, `EL_DISPEL` | 20, 10 | On a caster; strip a buff |
| Stone Skin, Earthen Call, Contract of Resistance | `MA_STONESKIN`, `EL_EARTHGUARDIANCE` (st 1), `EL_RESISTMAGIC` | 120, 122, 300 | Self shield; defence; resists every spell for a while |
| Cloaking Word | `EL_ESCAPE` (st 1) | 183 | The master and the spirit hidden: the flight |
| Emnity Swap | `EL_SYMPATHETICSWITCH` (st 1) | 30 | Swaps hate between the master and the spirit, 15 m; no chain |
| Remove Shock | `ALL_SHOCKREFLECT` | 60 | Out of a stun |

Left out: Summon Group Member `EL_SUMMONAID` (a social choice, not a fight's), the flight passives, Herb Treatment and MP Recovery (the
generic order's).

## Milestones, in verifiable order

All built on 16 November in `SpiritMasterPlaybook`, `BotServantAI` and `BotSkillManager`, as below; the thresholds are the playbook's constants.
Where the build differs from the first plan, the milestone says so.

0. **Name the skills**: `glossary/spirit-master.md` from the French client, cast times and chain links read again, every group above
   checked by `SpiritMasterPlaybookDataTest`.
1. **The commands carried out** (module, before any rule): measure first whether the generic order casts them and what happens (the log,
   `//bot debug combat`); then `BotServantAI` takes the next queued order each tick, as `CM_SUMMON_CASTSPELL` does, and carries it out with
   `summon.getController().useSkill(order)`, the target checked still there. Every other summoning class gains it too (none has commands, so
   nothing else changes). *Built without the measure first*: the playbook claims every command, so what the generic order did with them no
   longer matters. No unit test: a summon needs a world; the watch in game looks for `carries out order` (`BotServantAI` at DEBUG).
2. **Aim at the spirit**: *nothing to change in `BotSkillManager`*: the engine aims a `MYPET` skill at the caster's summon whatever target it
   is given (`FirstTargetProperty`). The playbook passes the spirit as the target (`Aim.SPIRIT`) so that the cast's own checks measure the
   right creature, and casts nothing at it while there is none. The data test holds every move's aim to its data's first target.
3. **The seam**: `SpiritMasterPlaybook` registered, `swings` false *(play)*, `claims` every group above but the fillers (Erosion, Vacuum
   Choke) and the damage chains, which stay with the generic order.
4. **Which spirit** *(play)*: Earth alone (it holds the enemy while the master casts), Fire in a group with a tank, Wind never chosen first
   (`servantOrder`, a new `ClassPlaybook` method that `trySummonServant` follows). The spirit dismissed and called again only out of a fight
   (`tend`), never a great one. A second summon fails while one is out, so the great spirits and Water against a player are only for a
   spirit called up again mid fight: Tempest or Lava at 2000 DP against an elite or a boss, Water against a player, Summoning Alacrity first.
5. **Upkeep**: before a fight Stone Skin, Armor Spirit and Elemental Spirit Armor; Sympathetic Mind kept up; nothing while idle in town.
6. **The spirit kept alive**: Replenish Element under 60 %, Healing Spirit under 40 %, Divine Spirit Armor at 2000 DP when it is about to
   die in a fight that is not won *(play)*. The spirit dead mid-fight: Summoning Alacrity and a resummon, unless the master is under 40 %.
7. **Damage** *(play)*: the spirit sent first (it has the hate), then the curses kept on the target (Withering Gloom, Infernal Pain,
   Infernal Blight, Magic's Freedom), Erosion renewed, the commands (Detonation Claw, Erosion, Wrath Position, Ruinous Offensive) on cooldown
   on anything worth it, the strip-and-hit skills on a target with a buff. Stone Scour only while nothing hits the master. The areas under the
   rules every cast obeys.
8. **Defence**: Emnity Swap, then alone Spirit Pique, when an enemy turns on the master and the spirit lives; Absorb Wounds, Bodyguard,
   Spirit Preserve, Wall of Protection under 50 % while hit; Spirit Absorption under 40 % with a spirit over 50 %; Contract of Resistance against a caster; Cloaking Word then the generic
   retreat, alone, under 25 %; Remove Shock.
9. **Control**: alone, a fear on a second enemy (the sorcerer's `secondToControl`); in a group Body Root, never a fear (the cast rule); Sigil
   of Silence on a caster. Spirit Disturbance is cast on cooldown among the commands, to open Detonation Claw's chain.
10. **The big moments** *(play)*: Kamikaze only to finish a fight already won or to save the master, never with a long cooldown spirit out;
    Spirit's Empowerment never chosen (it throws the spirit away) until a player of the class says otherwise; Cursecloud at 4000 DP against
    three enemies or more, alone; Disenchant at 2000 DP against a player.
11. **Stigmas** *(play)*: regular Withering Gloom, Magic's Freedom, Cloaking Word; advanced Infernal Pain, Healing Spirit, Infernal Blight,
    Armor Spirit.

Tests per milestone, pure as the others' are. Then a watch in game: a spirit master alone in Eltnen, one in a group, `//bot debug combat` on,
its spirit seen casting a command. Then its deaths on Reshanta's upper layer, against the measure of the roadmap's theme 3.

## Questions for a player of the class

The spirit by situation; whether a spirit master ever fights without a spirit (Spirit's Empowerment); Kamikaze's moment; the curse order;
the stigma set at each level; whether Erosion or Vacuum Choke is the filler.
