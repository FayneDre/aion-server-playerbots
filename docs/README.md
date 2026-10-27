# Documentation

Every document of the bot module, by topic. Start with [roadmap.md](roadmap.md) for what is left, and [built.md](built.md) for what works and why.
A plan's status here is a summary of its own **Status** line, which is the one to trust; last checked 27 October 2026.

## Entry points

| File | What it holds |
|---|---|
| [roadmap.md](roadmap.md) | **Start here**: what is left, by priority, with what blocks each item. |
| [built.md](built.md) | What already works, and the reasoning most of it was paid for twice. |
| [bot-commands.md](bot-commands.md) | Every `//bot` command, and the one number that decides who may run them (`check-bot-docs.ps1` keeps it complete). |
| [patch-notes.md](patch-notes.md) | Every test patch, in French for the testers, newest first, by git tag; older ones in [patch-notes-2026-10.md](patch-notes-2026-10.md). |
| [tester-feedback.md](tester-feedback.md) | What a useful tester report holds, the commands to type during a session, the template. |

## Architecture and tooling — [architecture/](architecture/)

| File | What it holds |
|---|---|
| [playerbot-architecture.md](architecture/playerbot-architecture.md) | Why bots are real `Player` objects, the core patches (`setAi`, `setMoveController`), the package layout, the reuse map. |
| [moving-a-body.md](architecture/moving-a-body.md) | Driving a body from the server: what a movement packet carries, how often, and the task manager that drops a body that arrived. |
| [test-harness-plan.md](architecture/test-harness-plan.md) | Notes in the log, feedback zips, patch tags, debug on demand, bot-only squads, the command inbox. Built. |
| [module-structure-plan.md](architecture/module-structure-plan.md) | Untangling the module before navigation goes further: forgetting a bot, playbooks on their own inputs, navigation as the bottom layer, `PlayerBotAI` cut up. Steps 1 and 2 built. |

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
| [population.md](population/population.md) | How a world gets its inhabitants: the flow, the three kinds of presence, the arithmetic. |
| [population-director.md](population/population-director.md) | The pool and the director: how a region keeps the people it should. |
| [bot-careers.md](population/bot-careers.md) | How a bot gains levels, where it lives as it grows, what keeps it out of trouble. |
| [population-churn-followup.md](population/population-churn-followup.md) | The churn of 4 October and what it uncovered: places no walker reaches, the equality audit, the watchdog. Done. |

## Combat and groups — [combat/](combat/)

| File | What it holds |
|---|---|
| [combat-skills.md](combat/combat-skills.md) | How a bot chooses a skill: the in-fight order, upkeep versus abilities kept in hand, openers. |
| [group-roles.md](combat/group-roles.md) | What tank, healer, support and damage change, only ever inside a group. |
| [group-positioning-plan.md](combat/group-positioning-plan.md) | Where each class stands in a group fight. Built, bosses only by default; validated in game on 27 October. |
| [combat-prototype.md](combat/combat-prototype.md) | The first milestones (M0 to M7), kept for their lessons. Done; M7 went on as the class playbooks. |

## Classes — [classes/](classes/)

One plan per class guide: its skills mapped to the data by group, what the module did differently, the milestones. The pattern is the same for
each: a `ClassPlaybook`, rules by skill group, a pure `decide`, a data test.

| File | Status |
|---|---|
| [templar-plan.md](classes/templar-plan.md) | Built, marks included; validated in game on 27 October. |
| [chanter-plan.md](classes/chanter-plan.md) | Milestones 0 to 8 built; validated in game on 27 October. |
| [ranger-plan.md](classes/ranger-plan.md) | Built (6 closed by decision); validated in game on 27 October. |
| [cleric-plan.md](classes/cleric-plan.md) | Built; validated in game on 27 October. |
| [gladiator-plan.md](classes/gladiator-plan.md) | Built (patch-2026-10-23); validated in game on 27 October. |
| [sorcerer-plan.md](classes/sorcerer-plan.md) | Built (patch-2026-10-23-2); validated in game on 27 October. |
| [skill-glossary.md](classes/skill-glossary.md) | French skill names mapped to groups, with [gladiator](classes/skill-glossary-gladiator.md) and [sorcerer](classes/skill-glossary-sorcerer.md) files; `skill-lookup.ps1 -Name` reads them all. |

Assassin, spirit master, bard, gunner and aethertech have no plan yet ([roadmap.md](roadmap.md), "Group play").

## Gear — [gear/](gear/)

| File | What it holds |
|---|---|
| [bot-gear.md](gear/bot-gear.md) | What a bot wears: the stats that rank a piece, the quality ceiling, the right kind of piece for a class, wings, power shards. |
| [bot-gear-obtainability.md](gear/bot-gear-obtainability.md) | Where a piece can be reached from, what is vetoed outright, whose faction it is. |
| [bot-gear-upgrades.md](gear/bot-gear-upgrades.md) | Enchantment and manastones on a piece already chosen. |
| [stigmas.md](gear/stigmas.md) | How a bot gets its stigma stones, and what the engine demands before one is socketed. |

## Navigation — [navigation/](navigation/)

| File | Status |
|---|---|
| [navigation-prototype.md](navigation/navigation-prototype.md) | How a bot moves: mesh versus probes, legs, detours, the anti-stuck bounds. Reference. |
| [navmesh-plan.md](navigation/navmesh-plan.md) | Path planning on a generated grid (N0 to N6). Done. |
| [long-walk-planner.md](navigation/long-walk-planner.md) | Routes over 150 m: a rough route, then stretches. Built. |
| [openworld-navigation-plan.md](navigation/openworld-navigation-plan.md) | **In progress**: glide and ride links, journeys given up; next, the day plans given up. |
| [obstacle-steering-plan.md](navigation/obstacle-steering-plan.md) | Walking into scenery, steps aside and rescue teleports. Built through milestone 4. |
| [navigation-stuck-plan.md](navigation/navigation-stuck-plan.md) | Bots stuck round obelisks and sent home. Built; its open part moved to the obstacle plan. |
| [dungeon-navmesh-plan.md](navigation/dungeon-navmesh-plan.md) | 45 dungeon meshes, glide links, floors restored. Built; Theobomos Lab and Rentus under 80 %. |
| [instance-navmesh-list.md](navigation/instance-navmesh-list.md) | The 110 instance maps by category, and which share a geometry. Reference. |

## Flight — [flight/](flight/)

| File | Status |
|---|---|
| [flight-plan.md](flight/flight-plan.md) | Take-off, landing, terraces, following a flying leader, ambient flights. Milestones 1 to 4 built. |
| [abyss-crossing-plan.md](flight/abyss-crossing-plan.md) | Milestone 5: flight links between Reshanta's islands. Built; long journeys and milestone 6 left. |
| [flying-with-a-player-plan.md](flight/flying-with-a-player-plan.md) | The 19 October report. Shipped in patch-2026-10-19-3; validated in game on 27 October. |

## Capitals — [capitals/](capitals/)

| File | Status |
|---|---|
| [living-capitals-plan.md](capitals/living-capitals-plan.md) | The umbrella plan: decisions and milestones 0 to 5. Milestones 0 to 4 built; ambience dropped or on hold. |
| [living-capitals-findings.md](capitals/living-capitals-findings.md) | What milestones 0 and 1 measured: Sanctum's bridges and rides, the crowd test, the town routine. |
| [bot-stores-plan.md](capitals/bot-stores-plan.md) | Bots' private stores: who keeps one, stalls, stock, prices. Built; bought from in game. |
| [bot-store-goods-plan.md](capitals/bot-store-goods-plan.md) | Enchantment stones, manastones, coins and medals at broker prices. Built and approved. |
| [capital-life-plan.md](capitals/capital-life-plan.md) | Visitors of every level and shouted speech. Built; duels and pets on hold. |

## Social — [social/](social/)

| File | Status |
|---|---|
| [chat-channels-plan.md](social/chat-channels-plan.md) | Bots in the Region, Trade and Find Group channels. Built and validated in game. |
| [player-interactions-plan.md](social/player-interactions-plan.md) | Trades with a companion, friends, duels. Built; not watched in game. |
| [cohort-plan.md](social/cohort-plan.md) | Bots in an alliance. Not started. |

## Tester reports — [feedback/](feedback/)

One file per report: each point, what the log showed, the cause and the change. Older reports are answered in the plans they shaped.

| File | Status |
|---|---|
| [feedback-2026-10-20.md](feedback/feedback-2026-10-20.md) | 19 and 20 October, group play in Eltnen. Deployed 23 October; validated in game on 27 October. |
| [feedback-2026-10-21.md](feedback/feedback-2026-10-21.md) | 21 October, the capitals. Deployed; point 3 became the capital visitors. |
| [feedback-2026-10-25.md](feedback/feedback-2026-10-25.md) | 25 October, cleric thresholds, invitations, shards, wings, lava. Shipped in patch-2026-10-25-2. |

`tools/check-docs.ps1` checks this folder (links, this index, sizes, Status lines, plans whose classes changed since the last patch); `deploy.ps1`
and `tag-patch.ps1` print what it finds.
