# Module structure: plan

Status: step 1 built on 27 October 2026 (not deployed); steps 2 to 5 not started. Decided on 27 October 2026. Five of the eight points of that day's review of the module, in the order below. Points 6 to 8
(one accessor for `instanceof PlayerBotAI`, one thread owning each bot's state, tuning constants as properties) are kept for later, on evidence.

Why: navigation is far from finished and has to become very stable and fast, and the five class playbooks still to write must be testable before
they reach the game. None of this changes what a bot does: a step that changes behaviour is a fault of the step.

## The rule for every step

- **Behaviour unchanged.** No tuning, no fix, no new rule mixed into a step. A fault found on the way is written down and fixed in its own commit.
- **Proof, not reasoning.** The game server's tests pass (`deploy.ps1` runs them; check the report count, see the Maven trap). A step that touches
  movement, navigation or the tick is measured with `tools/measure.sh` over 30 minutes before and after, the same population, and the figures
  (journeys given up, rescues, steps aside, slow searches, CPU) must not move beyond the noise of two windows before it.
- **Small commits**, each building and passing on its own, so a regression is found by bisecting rather than by reading.

## 1. Forgetting a bot that leaves the world (review point 2)

About 25 static maps hold state by bot object id (`BotSkillManager.castAttempts`, three in `SorcererPlaybook`, `spokeAt` in `BotChat` and
`BotSpeech`, `BotTransports`, `LeaderGlides`, `BotInstances`, `BotTargetRegistry`, ...). Leaving the world clears only `BotRequests` and `BotTrades`,
so a bot woken from the pool inherits what it remembered before it slept.

- Inventory every static map keyed by a bot (not those keyed by a map, a place or a monster: those are world caches and stay).
- Each owner exposes `forget(Player bot)`; `PlayerBotLeaveWorldService` calls them in one place.
- Test: a source test lists the static maps keyed by object id in the module and fails when one has no `forget` that clears it.

## 2. The playbooks decide on what they are given (review point 1)

Nine places in the playbooks read the AI itself (`bot.getAi() instanceof PlayerBotAI`): `mendingToFull` and the critical step (chanter),
`holdingFireSince` (ranger), `lastControlledAt` (gladiator), the cleric's fight ladder, the templar's. Those decisions escape the playbooks' tests,
and `combat.playbook` depends on `ai`, which depends on it.

- The values go into each playbook's `Situation`; what the playbook writes back (a sequence begun, fire held) goes through a small per-bot
  combat memory the AI owns and hands over, an interface in `combat.playbook`.
- Test: each moved read gets a case in its playbook's test; no `import ...playerbot.ai` is left in `combat.playbook`.

## 3. The offline toolchain in a package of its own (review point 4)

`navmesh` holds 30 classes, about half of them run only offline: `NavmeshBuilder`, `NavmeshWriter`, `NavmeshTool`, `Heightfield*`, the audits
(`ObeliskAudit`, `InstanceReach`, `FieldReach`, `RegionProof`, `GlideCheck`, `GapReport`, `Flood`, `Blockers`), `JourneyReplay`.

- They move to `navmesh.tool`; what stays in `navmesh` is what the running server reads: the mesh, its links, the path finder.
- `tools/navmesh.ps1` and the docs name `NavmeshTool` by its full name: both are updated in the same commit.
- Proof: a mesh rebuilt for one small map is byte for byte the one before (`Navmesh` files and links), and `obelisks` gives the same report.

## 4. Navigation as the bottom layer (review point 3)

`navmesh` should depend on no other bot package. Today `NavmeshService` imports `BotTargetSelector`, `BotFlight`, `BotPlaces` and `BotScheduler`
(the dangers kept from a landing, siege grounds, a crossing's air, the route lane). And `movement` imports `combat`: `BotFlight` casts flight
buffs and drinks serums, `BotMoveController` stands a seated bot up through `BotRestManager`.

- The runtime glue of `NavmeshService` that asks about the world moves up to `world/`, or is handed in as a predicate; the route lane is handed in.
- `movement` asks the AI through a callback for what to do on take-off and in flight, instead of calling the combat classes.
- Test: an architecture test reads the imports of the module's sources and fails on a forbidden direction: `navmesh` to any bot package,
  `movement` to `combat`, `combat` to `ai`. It is what keeps the layers from drifting back.

## 5. `PlayerBotAI` cut into its parts (review point 5)

2481 lines and about fifty fields: fighting, chasing, dying and reviving, rescues, a leader's glide, errands. Already out: `BotDay`, `BotErrands`,
`BotPosture`, `BotConvalescence`. Next, one at a time, each a commit with tests on what it decides:

- `BotCombatState`: the opener, holding fire, mending to full, the last blow and control; it is the memory step 2 hands to the playbooks.
- `BotDeath`: dying, waiting for a resurrection, reviving, where.
- `BotRescue`: moves refused, give-ups in a row, the unproven goal, `freeItself`.
- `BotChase`: the anchor, the chase timers, the station a grouped bot keeps.

`PlayerBotAI` keeps the events, the order of the tick and the hand-over between the parts. Whenever a fix touches one of these parts before its
turn, it is taken out then.

## Where this stands

**Step 1, built 27 October.** The inventory found less than the review feared: most maps were already cleared (by the AI on `DESPAWNED`:
convalescence, target claims, flights; by their owner: visitors, recruits) or recompute themselves on the next tick (the sorcerer's drain from its
mana). Four owners gained a `forget(Player)`, all called from `PlayerBotLeaveWorldService`: `BotSkillManager` (casts that did not go through),
the sorcerer's three states (through `ClassPlaybook.forget`), `BotTransports` and `BotInstances`. `BotMemoryTest` reads the sources and fails on a
static map keyed by an id that nobody has classified, or on a forgotten one its class's `forget` does not clear (checked by removing a line). 398 tests.
