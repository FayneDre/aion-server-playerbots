# Plan: bots that glide on their own (backlog point 4)

Status: built, unverified: milestone 2 (ambient glides) is written and compiled on 1 November, waiting for a deploy and a watch in game; milestone 0 measured (below); milestone 1 is much smaller than first thought.

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

## Milestone 0, measured (1 November)

`NavmeshTool <map> glidegain [samples]` (`tool/GlideGain.java`) samples descents of 80 to 400 m between two walkable places of a map, plans each on foot and by glides alone, and compares by time (run 6 m/s, glide 8 m/s). 150 samples on Eltnen, Morheim, Heiron, Beluslan and Inggison:

- **The glide links in the files never apply to a walkable journey: 0 glide routes on 750 samples.** A link joins two stretches of ground that **no walk joins**; where a walk exists, start and goal are on one stretch, so the planner has no link to offer. Milestone 1 as written
  ("also plan the glide when the walk is long") cannot work with today's links; it needs a **new kind of link, a shortcut inside one stretch** (a ledge to the ground below, where the walk is at least twice the crow's flight), to be generated and stored.
- **The bound for such shortcuts is low.** A glide covers 2.5 m for 1 m of drop, so on journeys of 80 m and more it only fits drops over 30 m: nothing below, and 11 to 35 % of the walkable descents above (Eltnen 11, Morheim 14, Heiron 13, Inggison 19, Beluslan 35). Drops of 8 to 30 m: none worth 30 % of the time on journeys of this length.

So a rule that glides to save time would fire on the cliffs of Beluslan and little elsewhere, for a new link file. **Decision taken from the figures: milestone 2 first** (a bot glides off a ledge for the look of it, near a player, onto ground it can see below: no link needed, only the geometry under it and `GlideLinks.MIN_DROP`), then milestone 1 only if the tester asks for time-saving glides, restricted to the big drops with shortcut links on the maps this tool ranks first.

## Milestone 2, built (1 November)

- `LedgeGlides` (navmesh): from the bot's place, 16 rays of up to 25 m on the same ground; where the ground ends, a floor 8 to 40 m lower at 70 to 95 % of a glide's reach (2.5 m forward per metre of drop) is the landing. `NavmeshTool <map> ledges` samples it offline: **40 to 50 % of random places on Eltnen, Heiron and Beluslan have an edge within 25 m**, 450 to 600 edges per 300 places, average drop 19 to 20 m. Many are steep hillsides rather than cliffs, which the air test below throws out.
- `NavmeshService.findLedge` vets an edge: the air between is clear (the game's geometry, as for the links), the landing is not siege ground, and the ground below is joined to the bot's by a walk of 600 m at most (no island).
- `BotLedgeGlide` (ai): while a bot strolls or loiters (never when hunting, in a group, owned, in town or already flying) it looks once a minute; one look in twenty, with a real player within 150 m, it walks to the edge and `glideAlong`s to the landing, then rests 10 minutes. Log lines: "goes to glide off the edge at ... to ...", "glides off the edge", "could not glide off the edge".
- Option `gameserver.playerbot.ambient.glide` (true by default), in `playerbot.properties`.
- **To watch in game**: a bot glides off a ledge near you and lands on its feet; none into a wall or a monster camp; the log lines above count them. Not tested: the glide of a line steeper than the glide ratio (landings are kept within 70 to 95 % of reach, so it should be a glide, not a dive).
