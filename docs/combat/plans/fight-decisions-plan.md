# Plan: the fight's decisions out of `PlayerBotAI`, each with a suite

Status: in progress: steps 1 and 2 built (`FightGiveUps`, `FightVerdicts` and their suites, 9 November); `SkillOrder` left, then the measure of a day before and after. Written on 9 November, from a reading of `PlayerBotAI` at b637b7aab, as the detail of milestone 6 of
[fresh-install-readiness-plan.md](../../architecture/plans/fresh-install-readiness-plan.md).

## Why

`PlayerBotAI` is 1924 lines and its combat half has no test ([known-limits.md](../../known-limits.md), Tests). The upstream merge (milestone 7 of
the same plan) touches the skill engine under it, and nothing would say what changed. `TownRoutine` and `LostTrail` show the shape that worked:
the decision in a small class of the same package, its inputs passed in (the clock included), the AI calling it and doing what it says, a suite
for it. No behaviour changes in this plan: every rule keeps its order, its thresholds and its comments, which move with it.

## What moves

Each line: where the rule is today, what it becomes, and what its suite pins down.

| Today (`PlayerBotAI`) | Becomes | The suite proves |
| --- | --- | --- |
| `abandonStalledFight` (watchdog) | `FightVerdicts.stalled(now, lastAttackTick, lastBlow, swingQueued)` returning `KEEP`, `QUEUED` (busy pool, keep), `LOOP_STOPPED` or `FRUITLESS` (let go and ignore the target) | a queued swing is never let go; the loop's gap is read before the blows'; `FRUITLESS` only past `FRUITLESS_FIGHT_MILLIS` |
| `useBestSkill` (the order) | `SkillOrder.steps(healer, closing, approachSpent, openingSpent, targetAlmostDead)` returning the steps in order (`PLAYBOOK`, `HEAL_SELF_FIRST`, `HEAL_ALLY_FIRST`, `APPROACH`, `OPENING`, `DEFENSIVE`, `HEAL_SELF`, `POTIONS`, `HEAL_ALLY`, `GAP_CLOSER`, `ATTACK`); the AI tries each until one goes off, and marks approach and opening spent when it reaches them, as it does now | a healer's heals come first and outside the finishing rule; a target almost dead skips the defensive, the self heal, the potions and a non-healer's ally heal; approach only while closing and once a fight; opening once a fight; the gap closer only while closing |
| `closesInRatherThanCasting` (the condition before the chase) | `FightVerdicts.closesIn(pulledFromAfar, position, targetFleeing)` | only FRONT and BEHIND close in, only after the pull, never on a fleeing or returning target |
| `retreatIfLosing` (the verdict) | `FightVerdicts.retreats(botLevel, hpPercent, attackerLevels, outnumberedCall, flees)`; the AI still filters the ignored attackers, the group and the death penalty first | outmatched leaves at any health; two attackers leave below `RETREAT_HP_PERCENT` under `GENERIC`, never under `STAND`, at once under `FLEE`; one attacker at the level gap does not |
| `findAttacker`'s filter | `FightVerdicts.defendsAgainst(dead, scenery, hating, ignored, groupHealer, aimedAtBot)` | scenery and the ignored are never answered; a group's healer answers only what is aimed at it (the 20 October cleric) |
| `healerHasNoSurplus`, `isHealthyEnoughToFight` | `FightVerdicts`, their inputs as values | the healer keeps a fight aimed at it whatever the mana; the engage thresholds are inclusive |
| `fight()`'s give-up branch, `failedChases`, `giveUpsInARow`, `ignoredTargets`, `isIgnored`, `unreachableFor` | `FightGiveUps`, a small stateful class like `LostTrail`: `ignore(id, until)`, `isIgnored(id, now)`, `chaseFailed(id, now)` returning the ignore time and whether to go back to camp, `landed()` | the ignore time grows 1, 2, 3+ failures; three give-ups in a row send the bot to camp and start over; a blow landed resets the run; an expired entry is dropped; the map of failures is cleared past `MAX_FAILED_CHASES_KEPT` |

## What stays

The attempts that act on the world keep their place in `fight()` and `decide()`: `chase.takeStation`, `chase.comesIntoSight`, `chase.after`,
`takeOffAfter`, `castFromAfar` and every `BotSkillManager.try*`. They are questions only the world can answer (the engine refuses a skill out
of range; the mesh refuses a chase), and the order between them is short and read in one place. Splitting them into a "would" and a "do" would
double every one for no test that the measures do not already give. The flight (`fightFromTheAir`, `fightInTheAir`) stays too: it is tied to
`BotFlight` throughout. `withdrawFromPlayers` already decides through `BotPvpRules.shouldWithdraw`, which has its suite.

## Order of work

1. `FightGiveUps` first: it holds state the others read (`isIgnored` has 14 callers in `PlayerBotAI`, the group's assist and the PvP defence
   among them), and a read of it removes an expired entry, which its suite must keep.
2. `FightVerdicts`, one rule at a time, each moved with its suite in the same commit.
3. `SkillOrder` last: it is the order of the whole combat judgement, and the commit that moves it changes nothing else.

## How it is checked

- The suites pass, and the full suite with them (`-Dmaven.test.skip=false`, report timestamps checked).
- A day of play measured before the first commit and after the last with `tools/measure.sh`, compared with `tools/compare-measures.ps1`: kills,
  deaths, give-ups, retreats and stalled fights stay in the band of the reports before. A rule moved without a change shows none.
