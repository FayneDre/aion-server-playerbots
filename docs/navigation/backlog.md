# Navigation: what is left, as seen in game

Status: not started; the user's six points from the game, worked once the module structure plan is done.

Written on 27 October 2026 from the user's notes, to be worked once the module structure plan is done
([module-structure-plan.md](../architecture/history/module-structure-plan.md)). Navigation is the priority after it. Each point is the user's words, what
is already known, and where to look first. Rules: measure before and after (`tools/measure.sh`, `tools/compare-measures.ps1`), with two
baseline windows, since two windows of the same code differed by up to 30 % on 27 October.

Baseline of 27 October evening, per 30 minutes, a player in game: day plans given up 377 and 522, routes refused 562 and 744, steps aside 944 and
1035, could not move at all 22 and 38, rescue teleports 37 and 43, routes with a glide 9 and 13 (of about 29 000 planned).

## 1. A bot slides for an instant before it runs

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
