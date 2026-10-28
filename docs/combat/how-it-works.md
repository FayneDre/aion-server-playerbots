# Combat: how it works today

Status: reference, written on 28 October 2026 from the code and the references beside it. The detail is in [combat-skills.md](combat-skills.md) (how a
skill is chosen) and [group-roles.md](group-roles.md) (what a role changes in a group); each class's own rules are in
[../classes/how-it-works.md](../classes/how-it-works.md). What is left is in [roadmap.md](../roadmap.md), "Group play".

## Two ticks

- **The decision tick** (`PlayerBotAI.decide`, about once a second, on one of the bot tick threads): what the bot does now. In this order, each one
  ending the tick when it acts: dead, landing, in the air (only the group's fight), an animation or a cast still playing (nothing on top of it),
  following its player to another map or into an instance, **defending itself** (or running from a fight it cannot win, `retreatIfLosing`, never in
  a group), a healer's mate in danger, the body (loot, dress, rest), the errands (a shop when the bag is full), the servant and the robot, the class's
  care between fights (`ClassPlaybook.tend`), then, unless recovering from a death, **picking a fight**, buffing for it, and starting it.
- **The attack tick** (`attackTick`, at weapon speed, while a fight is on): casting, nothing; in range, stop and cast or swing; out of range, chase
  (`BotChase`: 15 s, within the leash of the anchor, re-routed at most every 600 ms; `handleMoveValidate` stops the chase the moment the target is in
  reach, every 200 ms). A target not reached is ignored for 10 s.

An exception anywhere in a tick is logged as "failed a decision tick" and the tick goes on: it used to stop that bot thinking for the rest of its life.

## What it fights

- **Alone** (`BotTargetSelector`): what attacked it first; otherwise what it picks within the camp around its anchor, at most `MAX_LEVEL_GAP` above it,
  not more than 8 m above or below. A target is claimed (`BotTargetRegistry`) so two bots do not pile onto one.
- **In a group**, no initiative of its own: the tank's target, then the leader's, then any mate's attacker, read from the monster's aggro list
  (who it is fighting, whatever anyone has selected). Selecting a monster to read its level starts nothing. The level gap does not apply.

## How it chooses a skill

`useBestSkill` asks, in this order: **the class's playbook first** (`BotPlaybooks.of(bot).act`, then its `heal`), then the generic order read from the data:
approach, opening burst, defensive, heal, gap closer, attack ([combat-skills.md](combat-skills.md)). The engine validates mana, cooldown, range,
target and conditions as each candidate is tried, so trying is the question. Rules that bind every cast, a playbook's included, sit in
`BotSkillManager.cast`: never a fear in a group or an instance, never an area of 20 m or more among other packs, no smaller area that would take in
a bystander. A class that heals keeps 25 % of its mana for it.

- **A playbook** (`combat/playbook`, one per class with a guide: templar, chanter, cleric, ranger, gladiator, sorcerer) is a pure `decide` over
  the bot's inputs, rules by skill **group**; what it remembers of one bot's fight is in `BotCombatMemory`, which the AI hands over. The five other
  classes play the generic order alone.
- **Roles** (`BotRole`, authored: tank, healer, support, damage) change things only in a group ([group-roles.md](group-roles.md)).

## Where it stands

- **Positioning** (`positioning/`): in a group, each class takes the place it fights from (`BotPosition`: behind a boss, out of its cleave, a
  healer within reach of everyone), **against bosses only by default**: `//bot position auto|standard|spread`. Elsewhere melee walks to the target
  and stops at weapon range, casters stay put if a skill reaches. Followers out of a fight stand in slots round the leader, in world directions.
- [history/group-positioning-plan.md](history/group-positioning-plan.md) has the guide's page, the data and the group tests behind it.

## After a fight, and death

- Loot (`BotLootManager`), potions (`BotPotionManager`, found by what they do), rest (`BotRestManager`; a bot fallen behind its leader walks instead).
- **Death** (`ai/BotDeath`): revived by a resurrection somebody casts, at an instance's entrance, or at its obelisk on a quarter of its health, then the death
  sickness waited out before any fight is started; three deaths in ten minutes move the bot's home (`BotDay`).

## Limits

- Five classes have no playbook; a group of bots alone has nobody to decide for it short of `//bot squad`; no cohorts ([../social/plans/cohort-plan.md](../social/plans/cohort-plan.md)).
- The skill choice reads levels and kinds from the data, not damage figures: no weighing of mana against damage outside a playbook.
