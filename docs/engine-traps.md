# Traps this engine sets for a bot

Each of these cost hours at least once. They are written down so they cost them only once. For what is built, see [built.md](built.md); for what is left, [roadmap.md](roadmap.md).

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

It was `getClientConnection() != null`, and 81 call sites used it to mean "is this character present". Fixed at the root — see [built.md](built.md). The general shape is worth keeping in mind: **a method whose name asks one question while its body answers another will be used for the question in its name.**

## Two systems describing the same world will disagree

And the disagreement will not announce itself. The navmesh generator dropped every wall while the engine's raycasts kept them; later, blocking reused a routine that clears a whole column, so a tree's canopy deleted the ground beneath it. Each produced a day of symptoms that looked like separate small bugs.

When bots misbehave near geometry, first ask whether the mesh and the engine agree. `NavmeshTool <mapId> path x1 y1 x2 y2` against what the bot actually does is the fastest way to find out, and `NavmeshTool <mapId> audit` now asks the question for a whole map at once.

**A routine that answers one question will be reused for another that merely sounds the same.** "What can a body stand on" is not "what blocks a body"; "forbid this column" is not "something solid stands here". Both cost a day.

## Whatever the client does by itself must be redone server side

Drawing the weapon, ending spawn protection, standing up, sheathing, answering a window. Each was a bug that looked like something else.

And **the server decides faster than the client can show**: two animations in the same instant leave a bot sliding or floating.

Nobody has to send that spacing for a real player, which is why it is easy to miss that somebody must. Their client refuses input until the animation
it is playing has finished, so the pauses an onlooker sees between sitting, standing, drawing and setting off are produced by the acting client and
never travel. A bot has no acting client, so the server has to produce them.

The delays for this are empirical — animations live in the client and are exposed nowhere server side — but tuning them was never the answer to a bot
that slid. Getting up and drawing the weapon were spaced apart while drawing the weapon and walking off were not, and **no value of a delay that does
not exist is the right one**. Each pair in the chain needs its own, which is one delay per animation rather than one per pair: whatever the bot last
did claims the next stretch of time (`holdAnimation`), measured from the end of what is already playing rather than from now, and nothing starts
inside it.

Where that check goes matters as much. Inside the one funnel every journey passes through it would have been cheap and wrong: a refusal there means
the geometry leads nowhere, and two callers abandon their errand for good on one, so a bot would have given up the corpse it was walking to because it
had just stood up. It belongs in the decision tick, next to the same rule for casting — **"not yet" and "never" must not come back as the same
answer.**

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

## Nothing stops a second cast, and the guard that looks like a mutex is a sieve

`Skill.useSkill` does not ask whether the caster is already casting. It calls `setCasting(this)`, which merely overwrites the reference — the previous
cast's `endCast` is already scheduled and still runs. And the cooldown is set at the *end* of a cast, not at its start, so `isSkillDisabled` says
nothing about a skill being incanted right now. A real player is protected by their own client, which will not send the second cast at all.

`endCast` opens with `if (!effector.isCasting()) return`, which reads like a mutex and is not one: it asks whether *any* cast is current, not whether
this one is. So with three overlapping casts the first ends and applies, clearing the flag; the second ends and applies, because the third has set the
flag again; only the third is dropped. **Two effects land from what the client drew as a single cast**, the later start having overwritten the earlier
one's cast bar.

The decision tick runs every second. Bandage Heal, which every character knows, incants for four, so a single heal had three casts competing over it.
Measured on forty five bots in Poeta: **507 decision ticks landed inside a cast in three minutes**, twice on the same bot's same Bandage Heal a second
apart. Heals are only where it was noticed — Smite led the count at 226, and those are casts the bot was cancelling by walking off mid incantation.

So a bot holds still while it casts, exactly as the attack tick has always done, and the single place every bot cast passes through refuses to start
one on top of another.

## A refusal you were counting on may be a silent substitution

Asking the engine rather than restating its rules is right almost everywhere, and it is how the gear filters and the cast path are written. But it
only works where the engine answers `no`. Aimed at somebody else, a skill marked `first_target="ME"` is not refused: `FirstTargetProperty` replaces
the target with the caster, casts, and reports success. Over five thousand skills are marked that way, including the Bandage Heal every character in
the game knows.

So a bot offering its heals to a hurt ally always found one that "worked" — it healed itself, returned true, and the ally stayed hurt for ever. A
melee bot beside a wounded player bandaged itself over and over instead of fighting, because tending to the group is checked before fighting and it
never stopped succeeding. The same hole sat under ally buffs.

**Ask the data what a skill can be aimed at, and only then ask the engine whether it lands.** Target relation does not answer it: a friendly skill is
not thereby a skill you may cast on a friend.

The item path has the mirror image of this. `SkillUseAction` reads `player.getTarget()` both to decide whether an item may be used and to decide who
it lands on, because for a real player the target answers both. A bot's target is its quarry in a fight and its ally out of one, so a flask drunk
because the bot was dying went elsewhere, and one drunk beside somebody at full health was refused as pointless. **A bot uses an item on itself, which
means aiming at itself first** — what a player does by using the item with nothing selected.

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

## A getter in the data layer can be a write

`QuestKill.getNpcIds()` moves its ids into a second list on the first call, clears the first and nulls it. It reads as an accessor and is a lazy
migration of shared state, so two threads calling it at once leave one of them clearing a list the other has already taken away — a null pointer
inside the engine, from a method whose name promises a read.

Nothing in the engine meets it, because a quest template is reached one player at a time. Bots meet it on the first populated map: eight tick threads
scanning 8043 templates for different level bands at once. **Any sweep of the static data from a bot thread is serialised**, and once a template has
been read it is in its settled state and safe for everyone for the rest of the run.

The general shape is worth keeping in mind: this engine's template classes were written for a single reader and lazily build whatever is expensive.
A bot module reads the same data from a pool.

## Removing an object plays an animation you did not ask for

`getController().delete()` reaches `World.removeObject`, which despawns with `FADE_OUT` — the one-argument default — so every client that can see the
object watches it dissolve, and no animation can be passed through `delete()`. To remove something silently, despawn it yourself with
`ObjectDeleteAnimation.NONE` first; `removeObject` then skips its own despawn. It matters wherever the server removes what nobody asked it to: a bot put to
sleep must be *gone*, not seen leaving.

## A character read back from the database knows the skills of level one

`PlayerService.newPlayer` teaches skills from level 1 to the level the character has **at that moment**, and a bot is created at 1 and then given its
experience — so a level-30 templar loaded from the database holds level-1 skills and nothing else. Nothing since repairs it: `onLevelChange` only teaches
levels actually gained, and a bot gains none while it is not in the world.

What depends on that quietly is the outfitter: it will not put armour on a character that does not already hold the mastery for it — the same question the
engine asks when it refuses a piece — so dressing before teaching leaves the character in its starting kit and says so at `debug` level only. Teach, then
dress. This held for as long as it did only because teaching happened on the way into the world, which was once the only way a bot was ever dressed.

## When diagnosing, mind where the truth lives

**The database lags by up to five minutes.** Bots are saved on a timer and on a clean shutdown, so a query run too soon reports the state before whatever is being tested. Several conclusions today were drawn from stale rows and had to be taken back.

**Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.

**Maven's incremental build can miss a change** and leave the IDE's error stubs in place while reporting success. Use `clean package` when a build result looks impossible.

**The IDE and Maven compile into the same directory.** A terminal build corrupts the language server's state and fills the Problems panel with errors that are not in the source. The project disables the language server's own build for this reason.
