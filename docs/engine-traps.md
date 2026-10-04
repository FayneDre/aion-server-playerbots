# Traps this engine sets for a bot

What the engine's own methods and actions do that their names do not say: questions it asks where it looks like a refusal, work it expects somebody
else to have done, and state that is true in two places at once. Each of these cost hours at least once; they are written down so they cost them only
once.

For the world this engine describes and the data underneath it, see [world-and-data-traps.md](world-and-data-traps.md). For what is built, see
[built.md](built.md); for what is left, [roadmap.md](roadmap.md).

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

## Removing an object plays an animation you did not ask for

`getController().delete()` reaches `World.removeObject`, which despawns with `FADE_OUT` — the one-argument default — so every client that can see the
object watches it dissolve, and no animation can be passed through `delete()`. To remove something silently, despawn it yourself with
`ObjectDeleteAnimation.NONE` first; `removeObject` then skips its own despawn. It matters wherever the server removes what nobody asked it to: a bot put to
sleep must be *gone*, not seen leaving.

## A summon is not driven by the server at all

**Its movement method is empty.** `SummonMoveController.moveToTargetObject()` has no body, and `PlayableMoveController` gates the interpolation it does
implement behind a private `isControlled()` that is only true under fear or confuse — the same gate `BotMoveController` works around for players.

**Its ai is never started.** `VisibleObjectSpawner.spawnSummon` sends `AIEventType.SPAWNED` only when the template's ai name is `siege_weapon`. So
`FollowSummonTaskAI`, which the engine provides and which looks like the answer, fires `MOVE_VALIDATE` at an ai that is not running, through a move
method that does nothing. It was hung on a bot twice before anybody read where it led.

Everything a pet does comes from its master's client: `CM_SUMMON_MOVE`, `CM_SUMMON_ATTACK`, `CM_SUMMON_CASTSPELL`, `CM_SUMMON_COMMAND`. A headless
master means a statue. See `playerbot/combat/BotServantAI` and `playerbot/movement/BotServantMoveController`.

**A summon extends `Creature`, not `Npc`**, so it has no `getSkillList()` — and no `ai/handler` or `ai/manager` class applies to it, since every one of
those is typed `NpcAI`.

**Its skills are in `npc_skills.xml`, not `pet_skills.xml`.** `SummonController.useSkill` checks the pet table before casting, which reads like the
source of truth and is not: everything in that file is a toy pet of the 833000 range, and no combat summon appears in it, so that path can never fire
for a spirit. The combat summons are in the npc table — its very first entry is npc 201010, a summon. `PetSkillData.petHasSkill` also threw rather than
answering false for anything absent from its map, which is reachable from a client packet.

**`SummonController.attackTarget` audits a fast swing as a speed hack**, writing to the server log and to every administrator's chat. Anything driving a
summon must pace itself by `getAttackSpeed()`, exactly as the client does. The stigma work hit the same wall from the other side.

**`Summon.setMode` clears the skill order queue** on any mode that is not `ATTACK`, so orders have to be queued after the mode is set.

## Two buffs are the same buff when their effect ids collide, whatever their stack groups say

**The stack group is not how the engine decides a buff is already up.** `EffectController.isConflicting` compares the *effect ids* of each template,
within a target slot, and ends the weaker of two that collide — equal levels meaning the newcomer wins. The stack group never enters into it. So a bot
that asks "is this buff up?" by stack group, which is the obvious way and the right way for telling two ranks of one skill apart, will believe a buff is
missing that the engine considers present.

**Where the data gives one effect two names, that belief costs an infinite loop.** The bot casts the buff; the engine ends the one already holding that
effect id; the bot now finds *that* one missing and casts it back. Neither is ever up, and nothing in the log says so, because no one logs a buff.

Both halves of it are in the live 4.8 data:

| Skills | Stack groups | Shared effect |
|---|---|---|
| Bard, `Mvt. 2: Summer` / `Mvt. 3: Autumn` | `BA_SONGOFBRAVE` / `BA_SONGOFFIRM` | `statup` 944261 |
| Ranger, `Transformation: White Tiger` / `Krall` | `RA_LIGHT_WHITETIGER` / `RA_LIGHT_KRALLSCOUT` | `shapechange` 175 |

**A buff that cannot win its slot loops just as hard.** The ranger's Mau transformation carries the same `shapechange` 175 at `basiclvl` 160 against the
others' 100, and `isConflicting` refuses a newcomer whose level is lower. With Mau up, White Tiger is refused for ever — so it never lands, never reads
as up, and is recast for ever.

**Upkeep rules read from cooldown versus duration will adopt transformations.** Those four polymorphs last 120 s on a 10 s cooldown, which is the exact
signature of a buff worth keeping up. They have to be excluded by what they do — `EffectType.SHAPECHANGE` — not by how long they last.

## A movement packet carries a velocity, and it is worth one second

Driving a bot's body from the server is not sending it somewhere. It is speaking the protocol a player's own client speaks, and getting any part of it
wrong shows up as a body that jumps, stalls or plays the wrong animation — six separate faults on one 25 m climb, each looking much like the others
from the ground.

**The three numbers are metres a second, not a distance.** `SM_PLAYER_INFO`, having only a destination to work from, converts it as
`normalize(target - position) * movementSpeed`, and `CM_MOVE` says the same thing coming the other way: *"the movement vector from the client already
accounts for movement speed"*. A vector of the full remaining gap tells every client to fly at the gap's length in metres a second.

**So one packet buys exactly one second.** The destination is `position + vector` — `CM_MOVE` writes `setNewDirection(x + vectorX, ...)` on the way in.
A body that is told once arrives a second later and stops dead until something tells it again, and a leg refreshed only as it expires is a stop at every
refresh. Refresh well inside the second.

**And ten a second is worse than one.** `POSITION | MANUAL` means *"start move or change direction"*. Sent at every step it announces a change of
direction ten times a second, and a body forever restarting its movement never gets anywhere at all.

**The npc form and the player form are different bodies.** `MovementMask.NPC_STARTMOVE` sets `ABSOLUTE`, which the mask's own comment calls "mouse
related movement" and which carries a destination instead of a velocity. For a flying body, clients draw that as the *gliding* animation and walk the
body to the destination along the ground — the target window reads "Altitude =" for the whole climb. `SM_PLAYER_INFO` says as much by refusing to send
it: it converts the absolute form to a velocity before writing.

**Nothing lifts a body but the server.** Clients draw a descent perfectly from one announced destination, because the path ends on the ground and their
own clamp agrees with it. Upwards they do not: a climb has to be driven, with the server saying the position itself.

**And taking off is an animation like any other.** Leaving the ground takes about as long as standing up or drawing a weapon, and a climb started
inside it is swallowed by it: the body sets off, stops, and arrives at the top only when the leg ends. The rule above — *nothing starts inside an
animation already playing* — is not only about sliding feet.

## When diagnosing, mind where the truth lives

**The database lags by up to five minutes.** Bots are saved on a timer and on a clean shutdown, so a query run too soon reports the state before whatever is being tested. Several conclusions today were drawn from stale rows and had to be taken back.

**Never replace the server jar while it runs.** Classes load lazily, so anything not yet loaded disappears. `deploy.ps1` refuses for this reason; `navmesh.ps1` uses the repository's jar.

**Maven's incremental build can miss a change** and leave the IDE's error stubs in place while reporting success. Use `clean package` when a build result looks impossible.

**The IDE and Maven compile into the same directory.** A terminal build corrupts the language server's state and fills the Problems panel with errors that are not in the source. The project disables the language server's own build for this reason.
