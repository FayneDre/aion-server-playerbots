# Classes: how a class playbook works

Status: reference, written on 28 October 2026 from the code. Six classes have one, each from a guide and each validated in game on 27 October; the
plan that turned each guide into rules is in [history/](history/). Where a playbook sits in a fight is in [../combat/how-it-works.md](../combat/how-it-works.md).

## The seam

`ClassPlaybook` (`combat/playbook`) is what one class does beyond what every class does, **consulted first**: the generic order reads what a skill
*is* from the data and cannot say what a class is meant to *do* with it (a templar keeps Hand of Healing for a fifth of its health, a gladiator would
spend it at half). `BotPlaybooks.of(bot)` gives the class's one shared instance, or a playbook with nothing to say.

| Method | Asked | Answer |
|---|---|---|
| `act(bot, target)` | Every attack tick, before the generic order | true if the bot did something; false means "nothing to say", and leaves the bot as it was |
| `tend(bot)` | Between fights: mending, cleansing | true if it acted |
| `prepare(bot)`, `heal(bot)` | Before a fight, and when the generic order would heal | the same |
| `flees`, `whenOutnumbered`, `onRetreat` | When a fight turns | the class's way out |
| `swings`, `wantsToRest`, `claims(template)` | Whether to auto-attack, to sit, and which skills the generic order must leave to the playbook | |
| `mantraOrder`, `preferredStigmas` | The chanter's mantras, every class's stigmas ([../gear/stigmas.md](../gear/stigmas.md)) | |
| `onTakeOff`, `whileFlying` | Through `BotFlight.Crew` | |
| `forget(bot)` | When the bot leaves the world | drops what the playbook kept for it |

## The pattern every playbook follows

1. **Rules by skill group, never by id.** A group (`KN_STONEBODY`, `PR_HOLYGRAIL`) is stable across the ranks of a skill; ids change with each rank
   and level. The bot's own skill of a group is found at the time.
2. **A pure decision**: `act` gathers a `Situation` (health, mana, what is ready, who is hurt, what the target is doing) and a static `decide…`
   function answers with a move, which `act` then casts. The decision has its unit tests (`<Class>PlaybookTest`) with no server at all.
3. **A data test** (`<Class>PlaybookDataTest`): every group the playbook names exists in the data for that class, and every skill template of the
   game goes through the playbook's rules, so a renamed or missing group fails the build, not a fight.
4. **No state in the playbook.** It is shared by every bot of the class; what one bot's fight needs remembered (when it was stunned, what it opened
   on, holding fire) is in `BotCombatMemory`, handed over by the AI. The few maps a playbook keeps per bot are emptied by `forget`.
5. **Everything every cast obeys still applies**: `BotSkillManager.cast` refuses fears in a group, wide areas among other packs and areas that wake a
   bystander, whoever asked.

## Writing one for a new class

The guide first, then: name its skills in French and English ([glossary/](glossary/README.md), `tools/skill-lookup.ps1 -Name`), map each to its
group with `skill-lookup.ps1`, write the rules as a pure `decide` with its tests, the data test, then `act`. A plan per class in `plans/` while it is
built, with milestones verifiable one at a time. Assassin, spirit master, bard, gunner and aethertech have none yet ([../roadmap.md](../roadmap.md),
"Group play").
