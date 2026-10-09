# Group positioning: plan

Plan, not built. Source: The guide's page on positioning for the members of a group (two pages and a diagram, in French) and his page on aggro distance.
Where the module stands today is in [group-roles.md](group-roles.md) and [navigation-prototype.md](navigation-prototype.md); this file is the distance
between that and what he describes.

## What he asks for

The aim: every class uses its skills in the best conditions. The constraint over everything: **a bot's position must not draw new enemies.**

| Where | Classes (his names, then the engine's) | Why |
|---|---|---|
| In front of the enemy, on the side **opposite the rest of the group** | templar `TEMPLAR`, Ethertech `RIDER` | Enemies hold their attention on the tank, so they turn their back on everyone else. A cleave (a cone in front of the enemy) then hits only the tank |
| In contact, **behind** the enemy | assassin `ASSASSIN`, aède `CHANTER`, gladiator `GLADIATOR` | Melee classes out of the cone |
| At a good distance, never in melee, never in front | spiritualiste `SPIRIT_MASTER`, sorcier `SORCERER`, barde `BARD`, rôdeur `RANGER`, pistolero `GUNNER` | Enemy area attacks; a ranged class in melee is a class being hit |
| At a good distance, **every member within heal range** | clerc `CLERIC` | Its job is to survive and keep the group healthy |

In his diagram: the tank under the enemies, the enemies facing it, the melee just behind the enemies, the ranged further back, the healer off to one side,
and grey circles for the detection radius of the packs that are not part of the fight, which nobody may enter.

## What the engine says about aggro (his third page)

His page gives general figures, 15 to 20 m, shrinking with level. **This engine does it differently**, and the plan uses the engine's numbers:

- Each monster template has its own `aggrorange` and `aggroAngle`. A creature is noticed only if it is within that range **and in front of the monster**
  (within half the angle either side), or within a short radius all round: 4 m, or half the range when that is under 8 (`CreatureEventHandler.isInSeeRange`,
  `Npc.getShortAggroRange`).
- A monster ignores a player ten levels or more above it, guards excepted (`validateAggro`). That part of his page is right.
- So a pack is dangerous from the front and nearly harmless from behind. The same geometry that keeps melee out of a cleave keeps them out of the next
  pack's cone.

## Where the bots stand today

- Followers stand in slots around the leader (`BotGroupManager.formationSpot`), in world directions.
- In a fight, melee and ranged alike walk to the target's own position (`PlayerBotAI.chase`) and stop at weapon range, wherever that is relative to the
  way it faces. Casters stay put if a skill reaches. A healer holds position beside the group and does not close in.
- Nothing reads the enemy's facing, the other packs, or the group's shape. `PositionUtil.isBehind` and `isInFrontOf` exist and are unused by the module.

## Design

**A table of where, and a function of what.** `BotPosition` (authored, like `BotRole`): `FRONT`, `BEHIND`, `RANGED`, `HEALER`. It is not `BotRole`: a chanter
and a bard are both `SUPPORT` there and stand in different places here.

**One mechanism for all four.** Around the group's target (the skull, else the weakest enemy: [group-roles.md](group-roles.md)), lay candidate points on a ring
whose radius is the role's, keep those that satisfy the role's angle, drop the unsafe ones, take the nearest to where the bot stands.

| Role | Radius | Angle, measured from the enemy | Also |
|---|---|---|---|
| `FRONT` | contact | towards the tank's side, away from the group's centre (opposite within a margin) | the bot already holds the enemy, so it only moves when the line is wrong |
| `BEHIND` | contact | the half away from the tank | |
| `RANGED` | its skill or weapon range, less a margin | the half away from the tank | outside the enemy's area radius |
| `HEALER` | within heal range of every member | the half away from the tank | outside the area radius |

**Safe** means inside no aggro zone of an enemy that is not already fighting the group: its range, its cone, its short radius, and the ten level rule for the
bot's own level. A pure function over a list of zones, tested with made up numbers.

**Pure first.** The geometry takes a record (enemy position and heading, tank position, group centre, zones, role) and returns a point or nothing, the same
shape as `TemplarPlaybook.decide`: argued in a test, not in a play session.

**Calm.** A bot moves only when it is outside a tolerance of its spot, and recomputes only when the enemy has moved a few metres, as `chase` already does
with `RETARGET_STEP`. Without it every bot dances whenever an enemy takes a step, and every move is a packet ([moving-a-body.md](moving-a-body.md)).
A cast roots a bot, so casters reposition between casts.

**Group fights only.** A solo bot is untouched, as everywhere else.

## Milestones, in verifiable order

0. **Look before writing.** Measure what is assumed: the spread of `aggrorange` and `aggroAngle` over the monsters of one map; whether an enemy's heading
   follows its target while it fights; whether any monster skill states a cone or an area radius in the data, or whether those two numbers have to be
   chosen; the heal skills' range (25 m on the one looked at). And measure today: how often a melee swing is made from the front, and how often a ranged
   bot is in melee range. These are the before figures every later step is judged against.
1. **The table and the geometry, no behaviour change.** `BotPosition`, the spot function, the safety function, unit tests for each role on made up coordinates.
2. **Melee behind, ranged back.** `chase` aims at the spot instead of the target for `BEHIND` and `RANGED`. In game: the share of melee swings from behind, the
   distance of the ranged, both against the step 0 figures.
3. **The healer.** The heal range band. In game: no member out of its reach, and no healer walking into the fight.
4. **The tank.** It moves to the side opposite the group when the line is wrong, which can mean walking round the enemy. In game: the enemy's back to the
   group in a stabilised fight, as in his diagram.
5. **Routes that respect aggro zones.** Until now only the destination is checked; the walk there may still cut through a pack's cone. The path search
   has no cost for ground, so this needs one, and it is the likeliest piece to be larger than it looks. Decided after 2 to 4 have been seen.

## Risks

- **Dancing.** An enemy that follows the tank moves the whole formation. Tolerances and the recompute step are the defence, and they are guesses until step 2.
- **Reactive steering.** Walking round an enemy is exactly the sort of move that gets a bot stuck on a rock ([navigation-prototype.md](navigation-prototype.md)).
- **Packs.** With several enemies on the tank, the anchor is the marked one and the others are wherever they stand. Good enough for a pull of two or three.
- **Walls.** Indoors, the side opposite the group may be stone. A candidate with no ground is dropped, and a role with no candidate stays where it is.

## Questions for the guide's author

1. "At a good distance": in metres? The plan derives it from the skill and weapon range minus a margin, and from the heal range for the cleric. A number
   from him would be better.
2. The cleave cone and the enemy area radius: has he figures, or are they to be read from the data if they are there and chosen if not?
3. His aggro page gives general figures. The engine's own (per monster template, with a cone) are what the plan uses. Agreed?
4. May the tank walk round the enemy mid fight to reach the far side, or only when engaging?
5. Does "behind" mean the rear half, or a narrow back arc? Some assassin skills need the back; a narrow arc would be worth it for them alone.
