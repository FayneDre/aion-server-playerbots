# Documentation

Every document of the bot module, by topic. Start with [roadmap.md](roadmap.md) for what is left, and [built.md](built.md) for what works and why.
Last checked 28 October 2026.

## How the folders work

Each topic folder holds three kinds of document, and a reader looking for how something works today never has to read a plan:

- **at its top**: `how-it-works.md`, what is true today, kept up to date when the code changes, and other references (lists, measurements);
- **`plans/`**: work still open, each opening with its Status;
- **`history/`**: plans that are done, kept unchanged for their reasons and their numbers; what they left open is in the roadmap or a backlog.

A Status line opens every plan and backlog, in fixed words: `not started`, `in progress: <where>`, `built, unverified: <what>`, `done`, or
`reference`; the details go in the paragraph below it. A plan moves from `plans/` to `history/` the day it is done. `tools/check-docs.ps1` checks
all of it (links, this index, sizes, the Status words, a done plan left in `plans/`, a topic with no `how-it-works.md`, a document whose classes
changed since the last patch while it did not); `deploy.ps1` and `tag-patch.ps1` print what it finds.

## Entry points

| File | What it holds |
|---|---|
| [roadmap.md](roadmap.md) | **Start here**: what is left, by priority, with what blocks each item. |
| [built.md](built.md) | What already works, and the reasoning most of it was paid for twice. |
| [bot-commands.md](bot-commands.md) | Every `//bot` command, and the one number that decides who may run them (`check-bot-docs.ps1` keeps it complete). |
| [patch-notes/current.md](patch-notes/current.md) | Every test patch, in French for the testers, newest first, by git tag; older ones in [patch-notes/2026-10.md](patch-notes/2026-10.md). |
| [feedback/README.md](feedback/README.md) | What a useful tester report holds, the commands to type during a session, the template. |

## Architecture and tooling — [architecture/](architecture/)

| File | What it holds |
|---|---|
| [how-it-works.md](architecture/how-it-works.md) | Why bots are real `Player` objects, the core patches (`setAi`, `setMoveController`), the package layout, the reuse map. |
| [moving-a-body.md](architecture/moving-a-body.md) | Driving a body from the server: what a movement packet carries, how often, and the task manager that drops a body that arrived. |
| [plans/module-structure-plan.md](architecture/plans/module-structure-plan.md) | Untangling the module before navigation goes further. In progress: step 5 measured on 28 October. |

History: [test-harness-plan.md](architecture/history/test-harness-plan.md) (notes in the log, feedback zips, patch tags, the command inbox).

## Traps — [traps/](traps/)

Where to look first when a bot does nothing and says nothing about why.

| File | What it holds |
|---|---|
| [engine-traps.md](traps/engine-traps.md) | Questions the engine asks that a bot cannot see, actions anything cancels, methods whose names promise one thing. |
| [world-and-data-traps.md](traps/world-and-data-traps.md) | Geometry two systems describe differently, objects handed over half built, templates that rewrite themselves. |
| [skill-traps.md](traps/skill-traps.md) | One buff under two names, areas measured from the caster, casts a step cancels. |

## Population — [population/](population/)

| File | What it holds |
|---|---|
| [how-it-works.md](population/how-it-works.md) | How a world gets its inhabitants: the flow, the three kinds of presence, the arithmetic. |
| [population-director.md](population/population-director.md) | The pool and the director: how a region keeps the people it should. |
| [bot-careers.md](population/bot-careers.md) | How a bot gains levels, where it lives as it grows, what keeps it out of trouble. |

History: [population-churn-followup.md](population/history/population-churn-followup.md) (the churn of 4 October, the equality audit, the watchdog).

## Combat and groups — [combat/](combat/)

| File | What it holds |
|---|---|
| [combat-skills.md](combat/combat-skills.md) | How a bot chooses a skill: the in-fight order, upkeep versus abilities kept in hand, openers. |
| [group-roles.md](combat/group-roles.md) | What tank, healer, support and damage change, only ever inside a group. |

History: [combat-prototype.md](combat/history/combat-prototype.md) (milestones M0 to M7, kept for their lessons),
[group-positioning-plan.md](combat/history/group-positioning-plan.md) (where each class stands in a group fight; bosses only by default).

## Classes — [classes/](classes/)

The pattern is the same for every class: a `ClassPlaybook`, rules by skill group, a pure `decide`, a data test. The French skill names, one file
per class, are in [glossary/](classes/glossary/README.md): [templar](classes/glossary/templar.md), [chanter](classes/glossary/chanter.md),
[cleric](classes/glossary/cleric.md), [ranger](classes/glossary/ranger.md), [gladiator](classes/glossary/gladiator.md),
[sorcerer](classes/glossary/sorcerer.md); `skill-lookup.ps1 -Name` reads them all.

History, one plan per class guide, each validated in game on 27 October: [templar](classes/history/templar-plan.md),
[chanter](classes/history/chanter-plan.md), [cleric](classes/history/cleric-plan.md), [ranger](classes/history/ranger-plan.md),
[gladiator](classes/history/gladiator-plan.md), [sorcerer](classes/history/sorcerer-plan.md). Assassin, spirit master, bard, gunner and aethertech
have no plan yet ([roadmap.md](roadmap.md), "Group play").

## Gear — [gear/](gear/)

| File | What it holds |
|---|---|
| [how-it-works.md](gear/how-it-works.md) | What a bot wears: the stats that rank a piece, the quality ceiling, the right kind of piece for a class, wings, power shards. |
| [bot-gear-obtainability.md](gear/bot-gear-obtainability.md) | Where a piece can be reached from, what is vetoed outright, whose faction it is. |
| [bot-gear-upgrades.md](gear/bot-gear-upgrades.md) | Enchantment and manastones on a piece already chosen. |
| [stigmas.md](gear/stigmas.md) | How a bot gets its stigma stones, and what the engine demands before one is socketed. |

## Navigation — [navigation/](navigation/)

| File | What it holds |
|---|---|
| [backlog.md](navigation/backlog.md) | **What is left, as seen in game** (27 October): sliding, obstacles, long journeys, gliding, parasitic moves, dungeons. |
| [navigation-prototype.md](navigation/navigation-prototype.md) | How a bot moves: mesh versus probes, legs, detours, the anti-stuck bounds. |
| [instance-navmesh-list.md](navigation/instance-navmesh-list.md) | The 110 instance maps by category, and which share a geometry. |
| [plans/openworld-navigation-plan.md](navigation/plans/openworld-navigation-plan.md) | Glide and ride links, journeys given up. In progress: next, the day plans given up. |

History: [navmesh-plan.md](navigation/history/navmesh-plan.md) (path planning on a generated grid, N0 to N6),
[long-walk-planner.md](navigation/history/long-walk-planner.md) (routes over 150 m),
[navigation-stuck-plan.md](navigation/history/navigation-stuck-plan.md) (bots stuck round obelisks),
[obstacle-steering-plan.md](navigation/history/obstacle-steering-plan.md) (steps aside and rescue teleports),
[dungeon-navmesh-plan.md](navigation/history/dungeon-navmesh-plan.md) (45 dungeon meshes, floors restored).

## Flight — [flight/](flight/)

| File | What it holds |
|---|---|
| [plans/abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md) | Flight links between Reshanta's islands. In progress: 1 to 5 built, 6 not started. |
| [plans/flying-with-a-player-plan.md](flight/plans/flying-with-a-player-plan.md) | The 19 October report. In progress: 1 to 5 validated, 6 waits on the tester. |

History: [flight-plan.md](flight/history/flight-plan.md) (take-off, landing, terraces, following a flying leader, ambient flights).

## Capitals — [capitals/](capitals/)

History, all built: [living-capitals-plan.md](capitals/history/living-capitals-plan.md) (the umbrella plan, milestones 0 to 5),
[living-capitals-findings.md](capitals/history/living-capitals-findings.md) (Sanctum's bridges and rides, the crowd test, the town routine),
[bot-stores-plan.md](capitals/history/bot-stores-plan.md) (bots' private stores),
[bot-store-goods-plan.md](capitals/history/bot-store-goods-plan.md) (stones, manastones, coins and medals at broker prices),
[capital-life-plan.md](capitals/history/capital-life-plan.md) (visitors of every level and shouted speech; duels and pets on hold).

## Social — [social/](social/)

| File | What it holds |
|---|---|
| [plans/player-interactions-plan.md](social/plans/player-interactions-plan.md) | Trades with a companion, friends, duels. Built, unverified in game. |
| [plans/cohort-plan.md](social/plans/cohort-plan.md) | Bots in an alliance. Not started. |

History: [chat-channels-plan.md](social/history/chat-channels-plan.md) (bots in the Region, Trade and Find Group channels).

## Tester reports — [feedback/](feedback/)

One file per report: each point, what the log showed, the cause and the change. Older reports are answered in the plans they shaped.

| File | Status |
|---|---|
| [feedback-2026-10-20.md](feedback/feedback-2026-10-20.md) | 19 and 20 October, group play in Eltnen. Validated in game on 27 October. |
| [feedback-2026-10-21.md](feedback/feedback-2026-10-21.md) | 21 October, the capitals. Validated in game on 27 October; point 3 became the capital visitors. |
| [feedback-2026-10-25.md](feedback/feedback-2026-10-25.md) | 25 October, cleric thresholds, invitations, shards, wings, lava. Shipped in patch-2026-10-25-2. |
