# Documentation

Every document of the bot module, by topic. Start with [roadmap.md](roadmap.md): what is done and what is left, by theme, each theme's work in order.
Last checked 7 November 2026.

## How the folders work

Each topic folder holds three kinds of document, and a reader looking for how something works today never has to read a plan:

- **at its top**: `how-it-works.md`, what is true today, kept up to date when the code changes, and other references (lists, measurements);
- **`plans/`**: work still open, each opening with its Status;
- **`history/`**: plans that are done, kept unchanged for their reasons and their numbers; what they left open is in the roadmap or a backlog;
- **`backlog.md`**: the detail of what is left in a topic that has no single plan for it, in the roadmap's order.

A Status line opens every plan and backlog, in fixed words: `not started`, `in progress: <where>`, `built, unverified: <what>`, `done`, or
`reference`; the details go in the paragraph below it. A plan moves from `plans/` to `history/` the day it is done. `tools/check-docs.ps1` checks
all of it (links, this index, sizes, the Status words, a done plan left in `plans/`, a topic with no `how-it-works.md`, a document whose classes
changed since the last patch while it did not); `deploy.ps1` and `tag-patch.ps1` print what it finds.

## Entry points

| File | What it holds |
|---|---|
| [roadmap.md](roadmap.md) | **Start here**: what is done and what is left, by theme in the project's priority order, each theme's work in order. |
| [known-limits.md](known-limits.md) | What the project does not control or lives with, in order of how much it bites; reviewed with each patch. |
| [built.md](built.md) | What already works, one paragraph a topic, each pointing to its `how-it-works.md`. |
| [bot-commands.md](bot-commands.md) | Every `//bot` command, and the one number that decides who may run them (`check-bot-docs.ps1` keeps it complete). |
| [patch-notes/current.md](patch-notes/current.md) | Every test patch, in French for the testers, newest first, by version tag; older ones in [patch-notes/2026-11.md](patch-notes/2026-11.md) and [patch-notes/2026-10.md](patch-notes/2026-10.md). |
| [patch-notes/current.en.md](patch-notes/current.en.md) | The same entries in English, from the first version tag on, for the launcher and the public releases. |
| [feedback/README.md](feedback/README.md) | What a useful tester report holds, the commands to type during a session, the template. |

## Architecture and tooling — [architecture/](architecture/)

| File | What it holds |
|---|---|
| [how-it-works.md](architecture/how-it-works.md) | Why bots are real `Player` objects, the core patches (`setAi`, `setMoveController`), the package layout, the reuse map. |
| [addon-pages.md](architecture/addon-pages.md) | The pages the interface addons show: the web server, who a page belongs to, the combat meter's count. |
| [moving-a-body.md](architecture/moving-a-body.md) | Driving a body from the server: what a movement packet carries, how often, and the task manager that drops a body that arrived. |
| [release-process-plan.md](architecture/plans/release-process-plan.md) | Plan: one release process for the server and the launcher (`develop` and pre-releases on both sides, a compatibility level the launcher checks, a beta launcher installing the server pre-releases, notes in French and English). In progress: milestones 1-6 validated; next, the first stable pair. |
| [fresh-install-readiness-plan.md](architecture/plans/fresh-install-readiness-plan.md) | Plan: what the server needs before the launcher installs it from nothing (the schemas in the bundle and `apply-sql.ps1 -Create`, the heap guard, one budget for every navmesh, the density in the launcher and defaults for an unknown machine, a first start on an empty database measured, tests for the combat half of `PlayerBotAI`, the upstream merge and the stable pair). In progress: milestones 1 to 3 built and measured on the server side. |

History: [upstream-merge-plan.md](architecture/history/upstream-merge-plan.md) (69 upstream commits merged on 28 October: the trial, the install, the measure),
[module-structure-plan.md](architecture/history/module-structure-plan.md) (the module untangled before navigation went further, five steps, each measured),
[test-harness-plan.md](architecture/history/test-harness-plan.md) (notes in the log, feedback zips, patch tags, the command inbox).

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
| [resident-leftovers.md](population/resident-leftovers.md) | What is left of the dropped resident/adventurer system, to check before any removal. |
| [backlog.md](population/backlog.md) | What is left in detail: the director's stocking, pre-filling and moves across maps; bots yielding to players. |
| [plans/living-world-plan.md](population/plans/living-world-plan.md) | A world that lives with or without a player: memory, learned ground, road careers, PvP, groups, the director, a window in the game. Not started. |

History: [the-pool.md](population/history/the-pool.md) (what the pool cost to build, and the residents it replaced),
[population-churn-followup.md](population/history/population-churn-followup.md) (the churn of 4 October, the equality audit, the watchdog).

## Combat and groups — [combat/](combat/)

| File | What it holds |
|---|---|
| [how-it-works.md](combat/how-it-works.md) | **How a bot fights today**: the two ticks, what it fights, how it picks a skill, where it stands, death. |
| [combat-skills.md](combat/combat-skills.md) | How a bot chooses a skill: the in-fight order, upkeep versus abilities kept in hand, openers. |
| [group-roles.md](combat/group-roles.md) | What tank, healer, support and damage change, only ever inside a group. |
| [plans/wild-pvp-plan.md](combat/plans/wild-pvp-plan.md) | Wild PvP, rifts, arenas. Not started: decisions taken, milestones listed. |

History: [combat-prototype.md](combat/history/combat-prototype.md) (milestones M0 to M7, kept for their lessons),
[group-positioning-plan.md](combat/history/group-positioning-plan.md) (where each class stands in a group fight; bosses only by default).

## Classes — [classes/](classes/)

[how-it-works.md](classes/how-it-works.md): **how a class playbook works**, the seam, the pattern every one follows, writing one for a new class.
The French skill names, one file
per class, are in [glossary/](classes/glossary/README.md): [templar](classes/glossary/templar.md), [chanter](classes/glossary/chanter.md),
[cleric](classes/glossary/cleric.md), [ranger](classes/glossary/ranger.md), [gladiator](classes/glossary/gladiator.md),
[sorcerer](classes/glossary/sorcerer.md); `skill-lookup.ps1 -Name` reads them all.

History, one plan per class guide, each validated in game on 27 October: [templar](classes/history/templar-plan.md),
[chanter](classes/history/chanter-plan.md), [cleric](classes/history/cleric-plan.md), [ranger](classes/history/ranger-plan.md),
[gladiator](classes/history/gladiator-plan.md), [sorcerer](classes/history/sorcerer-plan.md). Assassin, spirit master, bard, gunner and aethertech
have no plan yet ([roadmap.md](roadmap.md), theme 3).

## Gear — [gear/](gear/)

| File | What it holds |
|---|---|
| [how-it-works.md](gear/how-it-works.md) | What a bot wears: the stats that rank a piece, the quality ceiling, the right kind of piece for a class, wings, power shards. |
| [bot-gear-obtainability.md](gear/bot-gear-obtainability.md) | Where a piece can be reached from, what is vetoed outright, whose faction it is. |
| [bot-gear-upgrades.md](gear/bot-gear-upgrades.md) | Enchantment and manastones on a piece already chosen. |
| [looking-after-itself.md](gear/looking-after-itself.md) | Selling junk, wearing the best of what it owns, identifying and binding, drinking potions: what each cost to learn. |
| [stigmas.md](gear/stigmas.md) | How a bot gets its stigma stones, and what the engine demands before one is socketed. |

## Navigation — [navigation/](navigation/)

| File | What it holds |
|---|---|
| [backlog.md](navigation/backlog.md) | **What is left, as seen in game** (27 October): sliding, obstacles, long journeys, gliding, parasitic moves, dungeons. |
| [how-it-works.md](navigation/how-it-works.md) | **How a bot moves today**: the layers, the mesh and its links, planning and its budgets, walking, what happens when it does not get there. |
| [instance-navmesh-list.md](navigation/instance-navmesh-list.md) | The 110 instance maps by category, and which share a geometry. |
| [verification-tools.md](navigation/verification-tools.md) | **Checking the meshes against what a tester walked** (31 October): golden routes, the note's route lines, walked ground. |
| [history/openworld-navigation-plan.md](navigation/history/openworld-navigation-plan.md) | Glide and ride links, journeys given up. Done 1 November, closed with backlog point 3. |
| [history/mesh-defects-plan.md](navigation/history/mesh-defects-plan.md) | **What to correct in the meshes** (30 October): the cave ramp of Morheim, islets under monsters, cuts between grounds. Done 1 November: erosion options A and C on, B, E and F not taken. |
| [history/glide-use-plan.md](navigation/history/glide-use-plan.md) | Bots gliding on their own (backlog point 4). Done 1 November: ambient glides built, the time-saving glide dropped. |
| [plans/step-links-plan.md](navigation/plans/step-links-plan.md) | Walking over steps of 1 to 2 m (the dock, Altgard, the Fire Temple). In progress: the census is built, the links are not. |

History: [navigation-prototype.md](navigation/history/navigation-prototype.md) (the foundations of September),
[navmesh-plan.md](navigation/history/navmesh-plan.md) (path planning on a generated grid, N0 to N6),
[long-walk-planner.md](navigation/history/long-walk-planner.md) (routes over 150 m),
[navigation-stuck-plan.md](navigation/history/navigation-stuck-plan.md) (bots stuck round obelisks),
[obstacle-steering-plan.md](navigation/history/obstacle-steering-plan.md) (steps aside and rescue teleports),
[dungeon-navmesh-plan.md](navigation/history/dungeon-navmesh-plan.md) (45 dungeon meshes, floors restored).

## Flight — [flight/](flight/)

| File | What it holds |
|---|---|
| [how-it-works.md](flight/how-it-works.md) | **How a bot flies today**: what the engine leaves to the client, when a bot leaves the ground, Reshanta, the limits. |
| [backlog.md](flight/backlog.md) | What is left in detail: the Abyss on foot, long journeys, the middle layer, ambient flights that stall. |
| [plans/abyss-crossing-plan.md](flight/plans/abyss-crossing-plan.md) | Flight links between Reshanta's islands. In progress: 1 to 5 built, 6 not started. |
| [plans/flying-with-a-player-plan.md](flight/plans/flying-with-a-player-plan.md) | The 19 October report. In progress: 1 to 5 validated, 6 waits on the tester. |

History: [flight-plan.md](flight/history/flight-plan.md) (take-off, landing, terraces, following a flying leader, ambient flights).

## Capitals — [capitals/](capitals/)

[how-it-works.md](capitals/how-it-works.md): **how the capitals live today**, the town day, private stores, visitors, speech.

[backlog.md](capitals/backlog.md): what is left in the economy in detail (selling, shops, gear that keeps up).

Plans: [plans/auction-house-plan.md](capitals/plans/auction-house-plan.md) (an auction house stocked by the server on the AHBot model; seller, settling and buyer built, unverified).

History, all built: [bot-store-consumables-plan.md](capitals/history/bot-store-consumables-plan.md) (food, scrolls, higher stones and more lines in the bots' stores, validated 1 November), [living-capitals-plan.md](capitals/history/living-capitals-plan.md) (the umbrella plan, milestones 0 to 5),
[living-capitals-findings.md](capitals/history/living-capitals-findings.md) (Sanctum's bridges and rides, the crowd test, the town routine),
[bot-stores-plan.md](capitals/history/bot-stores-plan.md) (bots' private stores),
[bot-store-goods-plan.md](capitals/history/bot-store-goods-plan.md) (stones, manastones, coins and medals at broker prices),
[capital-life-plan.md](capitals/history/capital-life-plan.md) (visitors of every level and shouted speech; duels and pets on hold).

## Social — [social/](social/)

| File | What it holds |
|---|---|
| [how-it-works.md](social/how-it-works.md) | **How bots deal with players today**: groups, requests, the chat channels. |
| [groups.md](social/groups.md) | Why groups work the way they do: following as the anchor, no initiative, assisting, slots, leaders. |
| [backlog.md](social/backlog.md) | What is left in detail: dungeons and instances, group play, what was asked in game on 19 October, the chat server. |
| [plans/player-interactions-plan.md](social/plans/player-interactions-plan.md) | Trades with a companion, friends, duels. Built, unverified in game. |
| [plans/cohort-plan.md](social/plans/cohort-plan.md) | Bots in an alliance. Not started. |
| [plans/group-options-plan.md](social/plans/group-options-plan.md) | Playing with one's bots: the starting level, bots rolling the dice, stay, guard, passive and two marks. Not started. |

History: [chat-fun-and-languages-plan.md](social/history/chat-fun-and-languages-plan.md) (fun lines in Region, French or English, validated 1 November), [chat-channels-plan.md](social/history/chat-channels-plan.md) (bots in the Region, Trade and Find Group channels).

## Tester reports — [feedback/](feedback/)

One file per report: each point, what the log showed, the cause and the change. Older reports are answered in the plans they shaped.

| File | Status |
|---|---|
| [feedback-2026-10-20.md](feedback/feedback-2026-10-20.md) | 19 and 20 October, group play in Eltnen. Validated in game on 27 October. |
| [feedback-2026-10-21.md](feedback/feedback-2026-10-21.md) | 21 October, the capitals. Validated in game on 27 October; point 3 became the capital visitors. |
| [feedback-2026-10-25.md](feedback/feedback-2026-10-25.md) | 25 October, cleric thresholds, invitations, shards, wings, lava. Shipped in patch-2026-10-25-2. |
| [feedback-2026-10-28.md](feedback/feedback-2026-10-28.md) | 28 October, resurrection not taken, gladiator postures, Draupnir hovering, trades with a companion. Trades: bag past its limit, fixed and validated. |
| [feedback-2026-11-01.md](feedback/feedback-2026-11-01.md) | 1 November, the spirit master's spirit (fights, jerky walk, drain), a level 28 bot among level 34 monsters, obstacles. Spirit changed, to check in game. |
| [feedback-2026-11-01-broker.md](feedback/feedback-2026-11-01-broker.md) | 1 November, the broker: what a player should not meet there (tests, cash shop, bundles, skill books, what npcs sell) and the price floors. Changed, to check in game. |
| [feedback-2026-11-02.md](feedback/feedback-2026-11-02.md) | 2 November, fall damage on the Sanctum lift (upstream fix `9b171d2` applied). Done. |
| [feedback-2026-11-03-broker.md](feedback/feedback-2026-11-03-broker.md) | 3 November, the broker: material prices by level and quality (no ceiling), stigmas times 20, every mythic piece listed, event gear refused. Changed, to check in game. |
