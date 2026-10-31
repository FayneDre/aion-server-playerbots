# Plan: bots that glide on their own (backlog point 4)

Status: not started. Analysed on 1 November from the code; nothing measured yet.

The tester's words: *"Les bots doivent mieux employer le vol en planant, d'eux-mêmes je n'ai pas l'impression qu'ils l'utilisent ou trop rarement. En groupe ils suivent le joueur s'il le fait."*

## What stands today

- **A glide is a last resort.** `BotPathFinder.searchPath` plans on foot first and asks `GlideRoutes.plan` only when the walk found nothing (`!walking.isEmpty() ... return walking`). A bot
  with a long but possible walk never looks at a glide, however much shorter it would be.
- **Few links by design.** `GlideLinks.OPEN_WORLD`: from ground of at least 400 surfaces onto ground of at least 2000, four links a pair, a cap set by memory (11 October). Measured:
  9 to 13 routes with a glide in 30 minutes, out of about 29 000 planned.
- **Following works.** A group follows the player's glide (`LeaderGlides`, validated 27 October). That is the only glide a bot makes by choice.
- **The glide itself**: 8 m/s, 2.5 m forward for 1 m down (`GLIDE_SPEED`, `GLIDE_RATIO` in `BotMoveController`), circling down when the drop is steep.
- **Ambient flights** (25 to 300 m, half the time, inside a fly zone) exist for flying, not for gliding off ledges.

## What the tester likely sees

A bot at the top of a cliff, a rampart or a plateau walks a long way round to the ground a player glides down to in seconds, and never launches itself from a height for the look of it.

## Milestones

0. **Measure the gain before changing anything.** A mode of `NavmeshTool` that, for the journeys of a window (start and goal, as `measure.sh` already writes for the given-up ones; extended to the
   successful routes, whose log line has no height, so it takes the mesh's), plans the walk and the glide route of each and reports: how many journeys have a glide route at all, how many save
   30% or more of the time (walk at the bot's speed, glide at 8 m/s plus the walk to the take-off), the metres saved. This says whether a rule is worth writing and which maps.
1. **Choose a glide when it is clearly quicker.** In `searchPath`, for a walk longer than about 80 m, also plan the glide route within a small budget (a quarter of the walk's) and take it when its
   time is under 70% of the walk's, the drop is at least `LeaderGlides.MIN_DROP`, the bot has wings and the map allows flight. Never for a chase, a fight or a group follower (the leader decides). Threshold and
   distance are properties.
2. **Glides for the look of it, only where someone sees.** A bot strolling or loitering on a ledge with a drop of 8 m or more and ground to land on may glide down instead of walking,
   one stroll in about twenty, and **only when a real player is within 150 m**: the bots nobody watches cost nothing. The landing is a spot the planner joined to the ledge.
3. **More links if the gain asks for it**, after milestone 0 says where: the file sizes are the limit (`OPEN_WORLD` cap), so only on the maps the measure ranks first.
4. **Measure in game**: routes with a glide per 30 minutes, time saved, deaths and rescues around take-offs and landings, search time per route (milestone 1 pays for a second plan), and the tester's eye.

## Decisions to take

1. Is "quicker by 30% or more" the right bar, or should a bot glide more often even for a small gain? (Recommendation: 30%, tuned after the measure.)
2. Glides for the look of it only near a player, as above? (Recommendation: yes; unseen glides are pure cost.)
3. Do bots glide in dungeons too? (Recommendation: no: dungeons have their own glide links built for reaching ground; keep their rules.)

## Risks

- **Planning cost**: a second search on every long walk. Mitigation: small budget, only past 80 m, measured in milestone 4 against the 575 s of route threads the given-up searches already cost in half an hour.
- **Deaths from falls or landing among monsters**: the glide planner already checks the air and the landing's ground; the take-off and landing must also be outside `DeathSpots`.
- **Looks wrong at a glance**: a bot gliding down a cliff it could walk is the point, but one that takes off from a flat road is not: the drop minimum and the "ledge" test guard it.
