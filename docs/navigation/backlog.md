# Navigation: what is left, as seen in game

Status: in progress: point 3 (long journeys) is worked and measured (budgets doubled on 31 October, proofs shared between bots, partial routes); point 1 is built and waits for a watch in game; points 2, 4, 5, 6 and 7 are not started. What 31 October did is in [verification-tools.md](verification-tools.md) and [plans/mesh-defects-plan.md](plans/mesh-defects-plan.md).

Written on 27 October 2026 from the user's notes, to be worked once the module structure plan is done
([module-structure-plan.md](../architecture/history/module-structure-plan.md)). Navigation is the priority after it. Each point is the user's words, what
is already known, and where to look first. Rules: measure before and after (`tools/measure.sh`, `tools/compare-measures.ps1`), with two
baseline windows, since two windows of the same code differed by up to 30 % on 27 October.

Baseline of 27 October evening, per 30 minutes, a player in game: day plans given up 377 and 522, routes refused 562 and 744, steps aside 944 and
1035, could not move at all 22 and 38, rescue teleports 37 and 43, routes with a glide 9 and 13 (of about 29 000 planned).

Third window, 29 October 18:28 to 18:58, a player in game, the code of `patch-2026-10-28` plus the bag fix: day plans given up 523, routes refused 739, steps aside 991, could not move at all 42, rescue teleports 45, routes with a glide 12 (of 28 718 planned): inside the band of the first two. The 523 plans by reason: 223 `long` (the search ran out of budget; median 374 m, longest 1614 m), 198 `noway` (68 of them in Pandaemonium), 46 `cutoff`, 46 `again`, 6 `as-near-as-it-gets`, 4 `no-headway`. The journey searches that gave up spent 580 s of wall time on the route threads in the half hour. Reports: `bot-measures/nav-2026-10-29-1828*.txt` in the server's log folder.

Fourth window, 29 October 19:25 to 19:55, with one change: a search that gave up left its place alone for five minutes instead of for good (`8ba923736`). Day plans given up 636, routes refused 883, `again` 98 (from 46), searches that gave up 955 s of wall time (from 580 s): worse, since the same journeys were asked again and gave up the same way. Reverted on its own (`07b588c94`); the idea is only worth having once a long search that runs out of budget can still walk what it planned. That is the next change (`36d7470df`, `41d979421`), to be measured alone against the first window. Reports: `nav-2026-10-29-1925*.txt`.

Fifth window, 29 October 20:20 to 20:50, with the partial route alone (`36d7470df`, `41d979421`): day plans given up 476, routes refused 648, steps aside 895, could not move 30, rescue teleports 37, searches that gave up 640 s of wall time. Better than the first window, but the line "walks the N m already planned" appeared **0 times**, so the gain is the window's variance, not the change. Replayed offline (`NavmeshTool replay`, one in 25, `-Dnavmesh.pathDebug=true`): 6 of 11 `long` journeys still give up at the 3 s budget, and the debug lines show the fine search hitting its 150 001 node limit again and again from the same start towards goals snapped a few metres apart, on journeys between two levels (348 m from z 81 to z 37, for one). They do not reach the stretch loop the partial route hooks into: the rough guide is empty or the first fine search is what runs out. Next: count where the 185 `long` give-ups of the window stop (rough guide empty, first fine search, a stretch), then act on the largest. The partial route is harmless where it does not fire and is kept. Reports: `nav-2026-10-29-2020*.txt`.

Sixth window, 29 October 21:18 to 21:48, with a long search that runs out of budget or meets a stretch it cannot plan walking what it planned (`36d7470df`, `41d979421`, `664dc0952`): day plans given up 464 (523 and 476 before), routes refused 733, `long` 183 (223 and 185), steps aside 1004, could not move 36, rescue teleports 46, searches that gave up 575 s of wall time (580 and 640). **The partial route fired 74 times, by 25 bots, 332 m on average and 995 m at most**: the journeys it carries are real. The totals hardly moved, so it is a few dozen of the 183. One bot did it 15 times towards the same goal (2233 2199, about 400 m away), each time 25 to 100 m after the last, replanned every 10 to 60 s and taking over five minutes for that distance: the walk is cut short before the end of the stretch it was given, and the budget the replans give up on is small (117 to 2187 ms). To find: why a partial route of 300 m is left after a few seconds (a fight, the day replanning, the leg machinery), and what limits the budget of a replan to 100 ms. Reports: `nav-2026-10-29-2118*.txt`.

Two profiles on 29 October, with `NavmeshTool <map> path ...` and `-Dnavmesh.pathDebug=true -Dnavmesh.glideDebug=true`. (1) Quileor, 210020000, 2126 2580 to 2233 2199: the rough guide had 38 points and 36 stretches planned; the last stretch is proved shut (goal at z 337, nearest ground z 323, a plateau the rough grid counts as joined). A bot walked about a kilometre through monsters before being told. `goalIsCutOffFromTheGuide` (`bcd7037c6`) now says so at planning time, from both of the guide's last two points; replayed on 77 journeys, no outcome changed. (2) 210060000, 2172 2561 z 81 to 2003 2257 z 37 (a `long` give-up, 3.3 s): the rough pass finds nothing, the glide planner drops the four near take-offs (z 130, no short walk) and spends its budget on two far ones (z 152, 83 m away): each walk from the start hits the fine search's 150 001 node limit. With 40 s of budget it still gives up: **this goal is out of reach for the bot, and the give-up is the cost of not being able to prove it**. So most of the `long` give-ups that start no stretch are goals on ground the bot cannot get to (a cliff, a plateau, a pit), not journeys short of time: a longer budget would not turn them into trips. Gains left: stop paying 1 to 4 s of a route thread for each (a refusal remembered per map, start ground and goal for some minutes, shared by the bots; 575 s of a 30 minute window), and stop choosing such goals (`BotPlaces`: vet a hunting ground against the bot's ground once, with the fine grid and the take-off walks, before offering it).

Where the `long` give-ups stop (replayed offline at the game's 3 s budget, one in four of the 185 of that window, 30 gave up of 46): 10 hit the fine search's 150 001 node limit (4.3 s each, no stretch started), 10 ran out of budget before any stretch, 8 had a stretch refused at once with a proof (0.2 s: `findLongPath` returns it as a give-up, `gaveUp` true, though the stretch said no way), 1 in the stretch loop. So two thirds never reach the loop the partial route hooks into. Tried and dropped: widening `NEAR_TAKE_OFF` (the capped walk to a glide take-off, 60 m) to 150 m lost walks it should have found (found on foot 10 to 5, gave up 23 to 28). Offline runs on this machine with the server up differ by a quarter from run to run (30 then 23 gave up on the same file), so compare only runs made together, or with the server stopped. Next: the 8 refused stretches (take what was planned before it, like the budget case), then what the 20 searches that never start a stretch spend their 3 to 4 s on.

## Where the points stand (31 October)

1 slide, 2 obstacles, 4 gliding, 5 parasitic moves, 6 dungeons, 7 Draupnir: not started. 3 long journeys: partly done (see its section). Reported by the tester in game on 31 October and
fixed since: a group's bots landed on a one-cell pillar and never followed (the rescue now counts a refusal in a pocket, `BotRescue`); bots would not come into Morheim's cave, nor out of it
(a rock lip, a floor the data does not hold, a corridor the erosion shut: `MapOpenings`); a bot's place beside its leader was behind a wall of rock (`walkableFormationSpot`). Reported and
not looked at: **bots that will not glide when the player does** (point 4).

## 1. A bot slides for an instant before it runs

**31 October, built, unverified in game**: `moveToPoint` now holds the walk for the stand-up time (`BotRestManager.STAND_UP_MILLIS`, 1 s) when it is the one that stands a seated bot up. Every other transition (weapon, sit, stand-up through the posture) already went through `BotPosture`, checked by search: no other caller sets off right after an emote. To watch: a bot resting that sets off to follow or to chase.

*"Sur certaines animations où le bot se repose puis se lève et devrait enchaîner sa course, il glisse un court instant avant de courir. Plusieurs
animations ont un problème de glisse."*

- `BotPosture` holds the tick while an animation plays (`isAnimating`), but only for what goes through it. `BotMoveController.moveToPoint` stands a
  seated bot up itself (`BotRestManager.standUp`, "a seated bot would slide across the ground") and sets off in the same call: the stand-up animation
  and the run start together, which is the slide described. First lead.
- Other moments to check the same way: the weapon drawn or sheathed on the move, the end of a cast (fixed once on 25 October: the walk is told again
  after a cast), the landing of a glide or a flight, a revival at an obelisk.
- Measure: no log says it today. A debug line when a leg starts while an animation is still running would count them per kind.

## 2. Obstacles and slopes: still stuck, then a jump further on

*"Il faut une meilleure détection encore des obstacles et pentes, il arrive que des bots se bloquent encore dans ce genre d'éléments puis se
téléportent plus loin d'un coup."*

- Two different things look the same from the client: a rescue teleport (`BotRescue.freeItself`, 37 to 43 per 30 minutes, logged) and the client's
  own correction when the server walked the bot through something the client collides with, which no log says.
- Known causes from [obstacle-steering-plan.md](history/obstacle-steering-plan.md): rays blind under a metre, gatherables outside the mesh, the last stretch
  to a creature walked without the mesh, slopes the mesh allows that the client does not climb.
- First step: milestone 6 of [openworld-navigation-plan.md](plans/openworld-navigation-plan.md), the walk against the plan: log where a leg ended against
  where it was going, which separates the two kinds of jump. The user's `//bot note` on the spot gives the place.

## 3. Long journeys given up

**31 October**: of the 33 "long" give-ups sampled from a window and replayed, 8 are found at the old budgets (3 s, levelled pass 1.5 s), 17 at 6 s and 3 s, 21 at 30 s: the levelled pass is the
budget that matters, and both are doubled (`4318fb938`). A monster the planner proved out of reach is told to the bots near, as a day plan is, and a hunting ground proved out of reach is skipped
(`BotRefusals`, `BotPlaces.groundToWork`). Measured over four windows, the day plans given up stayed at 317 to 347 and the routes refused at 501 to 659: the variance is larger than these changes.

*"Un bot doit être capable de faire des longs trajets sans les abandonner, actuellement il y a des problèmes."*

- The agreed next step, measured on 25 October (item 3 of "Open after 24 October" in [openworld-navigation-plan.md](plans/openworld-navigation-plan.md)):
  walk the stretches already planned when the search runs out of time and plan the rest on the way; stop at the nearest reachable point for a hunting
  ground or a stroll; put nothing in `BotDay.unreachable` without a proof; then the levels apart and the glides refused in game.
- Tools ready: `measure.sh` writes the day plans given up as journeys, `NavmeshTool replay` plans them again offline at any budget.

## 4. Gliding on their own

*"Les bots doivent mieux employer le vol en planant, d'eux-mêmes je n'ai pas l'impression qu'ils l'utilisent ou trop rarement. En groupe ils
suivent le joueur s'il le fait."*

- A bot glides only when the planner's best route goes through a glide link, and the open world keeps few of them by design
  (`GlideLinks.OPEN_WORLD`: from ground of 400 surfaces onto ground of 2000 or more, 4 links a pair, a cap set by memory on 11 October). Result: 9 to
  13 routes with a glide per 30 minutes out of about 29 000.
- Leads: a glide is chosen only when walking is longer, never because it is quicker or looks better; a cost that favours a glide down a slope or a
  cliff; links from smaller ground where the file stays small; ambient flights (25 to 300 m, half the time, inside a fly zone) are rare because few
  trips start and end in one.
- Following a player's glide works (`LeaderGlides`, validated 27 October).
- Plan: [plans/glide-use-plan.md](plans/glide-use-plan.md) (1 November).

## 5. Every movement that gives a bot away

*"Tous les mouvements parasites qui font bot doivent être éliminés au maximum pour arriver à des mouvements les plus fluides possible."*

- Known sources: steps aside (about 1000 per 30 minutes, nine in ten "planned, something spawned in the way"); a chase re-routed every 600 ms; a
  follower re-aimed every tick; turning on the spot between two plans; standing up and sitting down at a spot that is refused.
- Needs a measure of its own first: per bot and minute, changes of heading over 90 degrees and legs under a metre, which would rank the sources.
  The user's eye stays the judge: a short list of what looks wrong, with `//bot note`, gives the cases.

## 6. Dungeons worse than the open world

*"La navigation des bots dans les donjons est largement moins bonne que dans l'open world, les règles sont pourtant les mêmes. Navmesh de
mauvaise qualité ?"*

- The meshes were measured on 19 October ([dungeon-navmesh-plan.md](history/dungeon-navmesh-plan.md)): most reach over 90 % of their spawns from the
  entrance, Theobomos Lab (72 %) and Rentus (77 and 72 %) less. A good reach figure does not mean good walking: corridors, stairs, doors and props
  are where the open world's fixes were never tuned.
- What differs inside: no obelisk, so the rescues put a bot beside its player; floors stacked on each other, where a goal snapped to the wrong one is
  a route to nowhere; narrow corridors where the body's width erosion closes passages.
- First step: `measure.sh` and `nav-stats` per instance map during a run, against the open world's figures, and the dungeon and the moments the user
  saw, to tell the mesh from the mover.

## 7. Bots hovering in Draupnir Cave

*"Les bots se mettent à planer dans Draupnir alors que le chemin est largement praticable à pied."* (tester, 28 October, a group at level 41 to 43)

- Not looked at yet. Two suspects to tell apart: the flight rule choosing the air for a trip a walk would make (`BotFlight.worthFlyingTo`, the
  following-in-the-air rule when the player flies), or a mesh of the cave that has no ground route where the walk exists.
- First step: the map and a journey from the tester, then `NavmeshTool replay` on it, with and without the flight links.
