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

**A plan that is redone every tick is not a plan.** Both ways round an obstacle cost about the same, so fresh plans alternate and the bot paces back and forth. The same applies to a leg in progress: re-deciding a sidestep halfway through measures the new deviation from a bearing that has itself rotated, and the bot arcs ever wider.

## When diagnosing, mind where the truth lives

**The database lags by up to five minutes.** Bots are saved on a timer and on a clean shutdown, so a query run too soon reports the state before whatever is being tested. Several conclusions today were drawn from stale rows and had to be taken back.

**Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.

**Maven's incremental build can miss a change** and leave the IDE's error stubs in place while reporting success. Use `clean package` when a build result looks impossible.

**The IDE and Maven compile into the same directory.** A terminal build corrupts the language server's state and fills the Problems panel with errors that are not in the source. The project disables the language server's own build for this reason.
