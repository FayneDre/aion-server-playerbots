# Traps this engine sets for a bot

Each of these cost hours at least once. They are written down so they cost them only once. For what is built and what is left, see [roadmap.md](roadmap.md).

## The engine asks questions a bot cannot see

The most expensive family, because every one of them looks like a refusal.

`Equipment.equipItem` returns `null` when a piece is soul bound — but it has not refused anything. It has opened a confirmation window and is waiting for an answer. Since every upgrade worth having is soul bound, bots wore nothing at all, and three wrong diagnoses were spent on it. The same shape appears in group invitations, and will appear again.

`ResponseRequester.respond(messageId, 1)` is the answer, and it returns false when nothing was pending — so **asking is the whole test**. No engine api has to be widened to find out whether a question was asked:

```java
bot.getResponseRequester().respond(SM_QUESTION_WINDOW.STR_SOUL_BOUND_ITEM_DO_YOU_WANT_SOUL_BOUND, 1);
```

**A null return is not a reason.** When something the engine does silently produces nothing, read the whole method before inferring why. All three wrong diagnoses above came from reading the first half of `equipItem` and reasoning about the rest; the answer was in its last thirty lines.

## Anything that takes time is cancelled by everything

Identifying a piece and binding one each take five seconds watched by an `ItemUseObserver`, which gives up on movement, on a blow landed or taken, on a cast, on sitting down, on equipping anything. A bot that farms is in its weapon stance almost always and spends its idle moments walking, so waiting for a quiet five seconds to happen by chance never worked.

A bot has to **commit**: stop, put the weapon away, and hold its decision tick until the action is over. Hoping for calm is not a plan.

`hasScheduledTask`, not `hasTask`: the latter only asks whether that slot was ever filled, and nothing empties it when the task finishes. A bot guarding on `hasTask` reads one thing and then claims to be busy for the rest of its life.

## `isOnline()` asked one question and answered another

It was `getClientConnection() != null`, and 81 call sites used it to mean "is this character present". Fixed at the root — see [roadmap.md](roadmap.md). The general shape is worth keeping in mind: **a method whose name asks one question while its body answers another will be used for the question in its name.**

## Two systems describing the same world will disagree

And the disagreement will not announce itself. The navmesh generator dropped every wall while the engine's raycasts kept them; later, blocking reused a routine that clears a whole column, so a tree's canopy deleted the ground beneath it. Each produced a day of symptoms that looked like separate small bugs.

When bots misbehave near geometry, first ask whether the mesh and the engine agree. `NavmeshTool <mapId> path x1 y1 x2 y2` against what the bot actually does is the fastest way to find out, and `NavmeshTool <mapId> audit` now asks the question for a whole map at once.

**A routine that answers one question will be reused for another that merely sounds the same.** "What can a body stand on" is not "what blocks a body"; "forbid this column" is not "something solid stands here". Both cost a day.

## Whatever the client does by itself must be redone server side

Drawing the weapon, ending spawn protection, standing up, sheathing, answering a window. Each was a bug that looked like something else.

And **the server decides faster than the client can show**: two animations in the same instant leave a bot sliding or floating. The delays in `PlayerBotAI` are empirical and named for it.

## State kept in two places drifts

A flag cleared by hand on every way a journey can end will miss one — it missed the abandon path, and the bot stood still for good. Derive it from the thing that already knows (`BotMoveController.isTravelling()`).

**An unanswered question is not a no.** A long route is planned on another thread, so for a moment the bot has a destination and no plan; what the
reactive probes see in that moment is a wall. Treating that as a dead end made the bot abandon the journey, which made the arriving route stale, which
threw it away — forty five bots logged a thousand dead ends in five minutes for routes the mesh had solved. A planner must answer even when it finds
nothing, and the caller must wait for that answer.

**A plan that is redone every tick is not a plan.** Both ways round an obstacle cost about the same, so fresh plans alternate and the bot paces back and forth. The same applies to a leg in progress: re-deciding a sidestep halfway through measures the new deviation from a bearing that has itself rotated, and the bot arcs ever wider.

## Standable is not reachable, and the difference costs a bot its life

A spot can be ground a body fits on and still lead nowhere: a ledge, a hollow, the far side of a low wall, a shelf five metres up. From inside one,
every destination is refused — including spots a pace away — so the bot asks for a route hundreds of times and never moves.

This was learned four separate times before it was written down, because **each placement is its own decision and none of them inherits the others'
checks**: scattering villagers round a village, choosing the point that stands for a place, putting a bot back on spawn, and picking a corner to idle
in. Every new place a bot can be put needs all three conditions asked again:

1. **a real spot** — a spawn the world already uses, never the average of several, which lands between them;
2. **standable** — the mesh accepts it, bounded in height, or the snap climbs a cliff;
3. **reachable** — a route exists from where the bot is, or from the place it belongs to.

And none of that survives contact with the map, because the bot then walks by itself and the reactive layer can walk it behind a rock. **Whatever the
placement guarantees, there must be a way out**: a bot refused every destination a dozen times running is put back home, the way players are given an
unstick command rather than advice.

## Nobody announces that a bot has moved

An aggressive monster does not watch the world; it is *told*. A player's own client sends `CM_MOVE` and the packet handler calls
`PlayerController.onMove()`, which notifies every npc in the known list; an npc's `NpcMoveController` calls the same hooks itself. A bot has neither a
client nor that controller, so nothing ever announced it. Monsters therefore re-checked their aggro only at the moment a bot first entered their known
list — hundreds of metres out, always too far — and never again as it walked straight past them.

Measured before and after: **zero** aggro decisions in favour of a bot over 1981 checks, against five over 7364 once `BotMoveController` called
`onStartMove` / `onMove` / `onStopMove` like everything else that moves.

This is the same trap as drawing a weapon or standing up, in its most expensive form yet, and it is worth asking of anything shared: **who tells the
rest of the world, and does a bot go through them?**

## Colours are stored blue first

A flesh tone of 224, 192, 168 written the obvious way round is read as a pale blue, and nothing in the code looks wrong: the value is right, the order
is not. A village of smurfs is the only symptom. Two things follow. **Absolute colours must be packed deliberately** — `BotAppearance.stored(r, g, b)`
exists to say so once. And **varying a colour by scaling every channel by the same factor is order independent**, which is why the shading code was
right all along while the constants beside it were wrong.

Whenever a byte order is in doubt, the database already holds a known good answer: an existing character's stored colour is one reading if you are
right and an absurd one if you are not.

## An index is not the truth

"Is this map populated?" was asked of the roster table, which is derived data. It can be emptied, rebuilt or lost without a single character moving —
and when it was, a map of forty five inhabitants was judged empty and populated a second time, leaving ninety. Ask the thing that *is* the state
(`players`), not the thing that indexes it.

The same shape as the `null` traps above: two different situations were giving the same answer, and the code picked the wrong meaning.

## A half built object is what the creation path hands you

`PlayerService.newPlayer` builds only what character creation has to store. The effect controller, the known list, the flight controller and the
predefined stat functions are all still null, and `PlayerService.getPlayer` is what fills them. A client never sees that object either: it returns to
character selection and enters the world through the load path. **Anything made programmatically is stored and then read back**, or the first passive
skill applied to it dies on a null controller.

## The database truncates what does not fit, and says nothing

`server_variables.value` is a `varchar(30)`. A comma separated list of bot names fits three of them; the fourth was cut off by MySQL, so a population
of forty five came back empty and the cause was a `MysqlDataTruncation` two hundred lines up the log. **State whose size grows with the number of bots
gets its own table**, keyed by character id with a foreign key onto `players`, so deleting a character takes its row with it.

## When diagnosing, mind where the truth lives

**The database lags by up to five minutes.** Bots are saved on a timer and on a clean shutdown, so a query run too soon reports the state before whatever is being tested. Several conclusions today were drawn from stale rows and had to be taken back.

**Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.

**Maven's incremental build can miss a change** and leave the IDE's error stubs in place while reporting success. Use `clean package` when a build result looks impossible.

**The IDE and Maven compile into the same directory.** A terminal build corrupts the language server's state and fills the Problems panel with errors that are not in the source. The project disables the language server's own build for this reason.
