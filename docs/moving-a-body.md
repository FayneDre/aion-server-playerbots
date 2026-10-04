# Moving a body from the server

What it takes to drive a bot's body the way a player's own client drives theirs. Split out of [engine-traps.md](engine-traps.md), which it was
outgrowing, and every line of it was paid for by watching one bot climb 25 m from the ground.

The shape of the whole subject: **a client is not told where a body is, it is told how the body is moving.** Everything below follows from that.

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

## A method whose answer decides whether a task manager keeps ticking you

`MoveTaskManager` calls `moveToDestination()` on everything in its list, then asks `getAi().isDestinationReached()` — and **removes from the list
whatever says yes**. Nothing is told it was dropped. So the honest-looking question "have I arrived" is also the switch that stops a body being moved
at all, and any disagreement between the two is a body that stands still for ever while its owner waits for a journey to end.

Two ways to get it wrong, both found in one afternoon of flying:

**Answering in the wrong number of dimensions.** `BotMoveController.isArrived` compares x and y, correctly, since on foot the height is the ground's
business. A bot slanting up to an apex reaches the apex's x and y while still 25 m below it, was dropped from the movement tick there, and hung until
its flight points ran out. Worse, a body that stops being ticked stops telling the clients where it is, and every one of them carries on drawing it
along the last velocity it was given: 329 m up, by the target window, while the body sat still.

**Deciding the same thing twice, slightly differently.** The step that reaches the target must mark the leg finished *itself*, because there is no next
step to notice — and it must mark it by exactly the test the manager will apply a moment later. A step that stopped 47 cm short satisfied the removal
and not the flag, and every leg ended a second and a half late.

**A movement that depends on being ticked should check that it still is.** Not by trusting the manager, which never reports a removal, but by watching
the one thing it can see for itself: whether the body is still moving. A second and a half of a leg making no headway hands it over again, with a line
in the log naming where. It paid for itself the first time it ran.

## A decision taken again is a decision never acted on

Anything that re-decides every tick — an escort keeping station on a player, a chase, a formation — will re-decide the end of itself too. A flight
down to fifteen flight points ordered a descent, then found itself short again a fifth of a second later and ordered another one from where the body
still was, each order restarting the leg. The log wrote the same line at the same height eleven times and the body never moved; once the points ran out
it did the same thing falling. **A loop that re-decides needs a state that says it has already decided**, and every path into that state has to check it
first.

## The ground under a body at altitude is not where the obvious call looks

`GeoService.getZ(worldId, x, y, z, instanceId)` searches two metres either side of the height it is given, and the mesh's `groundNear` snaps within a
radius of the point. Both are right for a body on the ground and useless 70 m above it: they answer nothing, or — worse — that the ground is where the
body already is. A flight that asked them landed in mid air with its flight points intact, reported itself down, and then took off again, because
walking anywhere from the sky is proved impossible.

The question has to be asked as a drop: `getZ(worldId, x, y, z + 1, z - 500, instanceId)`, from just above the body to further down than any outdoor
map is tall.
